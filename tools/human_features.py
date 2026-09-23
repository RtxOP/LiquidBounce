#!/usr/bin/env python3
"""Extract human-aim feature distributions from RotationRecorder sessions.

PARTITION (enforced by assertions below - never mix these groups):
  HUMAN  = aim-2026-09-22_*.jsonl  schema 8, manual mouse input.
           Signal = player_yaw / player_pitch (raw, GCD-quantised mouse).
           NOTE: source reads "KillAura" on every tick - artifact of the
           RotationUtils release-branch linger (activeSettings never reset
           after a prior KillAura test). Ground truth = target_yaw null on
           100% of ticks (no live targeting) + attacker being the player.
  SHAPER = aim-2026-09-19_*.jsonl  schema 7, real bot output.
           Signal = server_yaw / server_pitch (shaper-produced).
           Fresh targets present on essentially every KillAura tick.

Also derives a third series for diagnostics: the TRAILING shaper output of
the manual sessions (server_yaw, Sep 22) - what today's engine does to a
real human input stream (exposes the deferred stop-and-go defect).

Outputs tools/human_corpus_features.json and a printed report.
Stdlib only; no learned models (distribution/KS comparisons only).
"""
from __future__ import annotations

import glob
import json
import math
import sys
from pathlib import Path

FS = 20.0  # tick rate
HUMAN_GLOB = "tools/aim-2026-09-22_*.jsonl"
SHAPER_GLOB = "tools/aim-2026-09-19_*.jsonl"
OUT_JSON = Path("tools/human_corpus_features.json")
ZERO_EPS = 1e-9
GRID_RESID_TOL = 0.02  # fractional gcd steps, matches RotationRecorder
MX_DUAL_THRESHOLD = 0.25  # deg: MX-Project's dual-axis motion threshold (findings §8.3 item 4)


# ---------------------------------------------------------------- loading

def load_session(path):
    session, ticks, packets = None, [], 0
    end = None
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            t = r.get("type")
            if t == "session":
                session = r
            elif t == "tick":
                ticks.append(r)
            elif t == "packet":
                packets += 1
            elif t == "end":
                end = r
    return {"path": path, "session": session, "ticks": ticks,
            "packets": packets, "end": end}


def load_candidate(path):
    """Load a candidate file that may hold MULTIPLE session segments.

    The headless replay concatenates one recording per source session; the
    human group is judged per file, so candidate deltas must never cross
    those seams either (cross-session teleports are not engine motion —
    they would inflate variance and crush lag-1). Splits on 'type: session'
    rows into the same dict shape [load_session] returns.
    """
    sessions, cur, end = [], None, None
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            t = r.get("type")
            if t == "session":
                if cur is not None and cur["ticks"]:
                    sessions.append(cur)
                cur = {"path": path, "session": r, "ticks": [],
                       "packets": 0, "end": None}
            elif t == "tick":
                if cur is None:
                    cur = {"path": path, "session": None, "ticks": [],
                           "packets": 0, "end": None}
                cur["ticks"].append(r)
            elif t == "packet" and cur is not None:
                cur["packets"] += 1
            elif t == "end":
                end = r
    if cur is not None and cur["ticks"]:
        sessions.append(cur)
    for s in sessions:
        s["end"] = end
    return sessions


def wrap180(d: float) -> float:
    return (d + 180.0) % 360.0 - 180.0


def deltas(ticks, yaw_key, pitch_key):
    """Wrapped per-tick yaw/pitch deltas from a tick list. Missing keys -> skipped."""
    ys, ps = [], []
    prev = None
    for t in ticks:
        y, p = t.get(yaw_key), t.get(pitch_key)
        if y is None or p is None:
            prev = None
            continue
        if prev is not None:
            ys.append(wrap180(y - prev[0]))
            ps.append(p - prev[1])
        prev = (y, p)
    return ys, ps


# ---------------------------------------------------------------- helpers

def pct(sorted_vals, q):
    if not sorted_vals:
        return float("nan")
    idx = min(len(sorted_vals) - 1, max(0, int(round(q * (len(sorted_vals) - 1)))))
    return sorted_vals[idx]


def describe(vals):
    vals = [v for v in vals if v == v]  # drop NaN
    if not vals:
        return {"n": 0}
    s = sorted(vals)
    n = len(s)
    mean = sum(s) / n
    var = sum((x - mean) ** 2 for x in s) / n
    return {"n": n, "mean": mean, "rms": math.sqrt(sum(x * x for x in s) / n),
            "p10": pct(s, 0.10), "p50": pct(s, 0.50), "p90": pct(s, 0.90),
            "p99": pct(s, 0.99), "max": s[-1], "std": math.sqrt(var)}


def acf(vals, k):
    n = len(vals)
    if n <= k + 3:
        return float("nan")
    m = sum(vals) / n
    c0 = sum((x - m) ** 2 for x in vals) / n
    if c0 <= 0.0:
        return float("nan")
    ck = sum((vals[i] - m) * (vals[i + k] - m) for i in range(n - k)) / n
    return ck / c0


def mean_nan(vals):
    vals = [v for v in vals if v == v]
    return sum(vals) / len(vals) if vals else float("nan")


def ols(x, y):
    n = len(x)
    if n < 3:
        return {"slope": float("nan"), "intercept": float("nan"), "r": float("nan")}
    mx, my = sum(x) / n, sum(y) / n
    sxx = sum((a - mx) ** 2 for a in x)
    syy = sum((b - my) ** 2 for b in y)
    sxy = sum((a - mx) * (b - my) for a, b in zip(x, y))
    if sxx <= 0 or syy <= 0:
        return {"slope": float("nan"), "intercept": float("nan"), "r": float("nan")}
    slope = sxy / sxx
    return {"slope": slope, "intercept": my - slope * mx,
            "r": sxy / math.sqrt(sxx * syy)}


def ks2(a, b):
    if not a or not b:
        return float("nan")
    a, b = sorted(a), sorted(b)
    i = j = 0
    d = 0.0
    while i < len(a) and j < len(b):
        fa, fb = (i + 1) / len(a), (j + 1) / len(b)
        d = max(d, abs(fa - fb))
        if a[i] <= b[j]:
            i += 1
        else:
            j += 1
    d = max(d, abs((i) / len(a) - (j) / len(b)))
    return d


def run_lengths(mask):
    """Lengths of True runs and False runs."""
    trues, falses = [], []
    cur, ln = (mask[0] if mask else False), 0
    for v in mask + [None]:
        if v == cur:
            ln += 1
        else:
            (trues if cur else falses).append(ln)
            cur, ln = (v, 1) if v is not None else (False, 0)
    return trues, falses


# ---------------------------------------------------------------- spectra

def _fft(x):
    """In-place iterative radix-2 Cooley-Tukey; len(x) must be a power of 2."""
    n = len(x)
    j = 0
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j ^= bit
        if i < j:
            x[i], x[j] = x[j], x[i]
    length = 2
    while length <= n:
        ang = -2.0 * math.pi / length
        wl = complex(math.cos(ang), math.sin(ang))
        half = length >> 1
        for i in range(0, n, length):
            w = 1.0 + 0j
            for k in range(half):
                u = x[i + k]
                v = x[i + k + half] * w
                x[i + k] = u + v
                x[i + k + half] = u - v
                w *= wl
        length <<= 1
    return x


def _linear_detrend(v):
    if len(v) < 3:
        return list(v)
    x = list(range(len(v)))
    fit = ols(x, v)
    return [b - (fit["intercept"] + fit["slope"] * a) for a, b in zip(x, v)]


def _segments(series, size=512, step=256):
    """Hann-windowed segments of `size` (zero-padded if the series is shorter)."""
    n = len(series)
    if n == 0:
        return []
    starts = list(range(0, n - size + 1, step)) if n >= size else [0]
    if n < size:
        starts = [0]
    segs = []
    for s in starts:
        chunk = series[s:s + size]
        m = len(chunk)
        if m < 3:
            continue
        hann = [0.5 * (1.0 - math.cos(2.0 * math.pi * i / (m - 1))) for i in range(m)]
        energy = sum(h * h for h in hann) or 1.0
        segs.append(([chunk[i] * hann[i] for i in range(m)], energy))
    return segs


def welch(series, size=512, step=256):
    """Averaged periodogram -> (freqs[1..size/2], powers[1..size/2])."""
    segs = _segments(series, size, step)
    if not segs:
        return [], []
    half = size >> 1
    acc = [0.0] * half
    for vec, energy in segs:
        buf = [complex(v, 0.0) for v in vec]
        buf.extend([0j] * (size - len(buf)))
        _fft(buf)
        for k in range(1, half + 1):
            acc[k - 1] += (buf[k].real ** 2 + buf[k].imag ** 2) / energy
    freqs = [k * FS / size for k in range(1, half + 1)]
    return freqs, [a / len(segs) for a in acc]


def spectral_features(positions, deltas_list):
    """Beta exponents, band fractions, peak frequency for position & velocity."""
    out = {}
    freqs, pp = welch(positions)
    _, vp = welch(deltas_list)
    for tag, p in (("position", pp), ("velocity", vp)):
        if not p or not freqs:
            out[tag] = {}
            continue
        df = FS / 512
        total = sum(p) * df or 1.0
        bands = {}
        for lo, hi in ((0.25, 1.0), (1.0, 3.0), (3.0, 6.0), (6.0, 10.0)):
            bands[f"{lo}-{hi}"] = sum(
                pi for fi, pi in zip(freqs, p) if lo <= fi < hi) * df / total
        fit_pts = [(math.log2(fi), math.log2(pi))
                   for fi, pi in zip(freqs, p) if 0.25 <= fi <= 8.0 and pi > 0]
        beta = ols([a for a, _ in fit_pts], [b for _, b in fit_pts])
        peak_f, peak_p = max(
            ((fi, pi) for fi, pi in zip(freqs, p) if 0.25 <= fi <= 9.5),
            key=lambda t: t[1], default=(float("nan"), float("nan")))
        out[tag] = {"beta": beta["slope"], "beta_r": beta["r"],
                    "band_fractions": bands, "peak_hz": peak_f}
    return out


# ---------------------------------------------------------------- features

def axis_features(ys, ps, gcd, with_spectral=True):
    """Full feature block for one signal stream (yaw deltas, pitch deltas)."""
    n = len(ys)
    speeds = [math.hypot(a, b) for a, b in zip(ys, ps)]
    abs_y = [abs(v) for v in ys]
    abs_p = [abs(v) for v in ps]

    # GCD grid (vanilla mouse is integer steps; skip clamped pitch)
    resid, steps_abs = [], []
    for t_idx, (dy, dp) in enumerate(zip(ys, ps)):
        if abs(dy) > ZERO_EPS:
            s = dy / gcd
            resid.append(abs(s - round(s)))
            steps_abs.append(abs(s))
    grid_p99 = pct(sorted(resid), 0.99) if resid else float("nan")

    # Dwell / motion segmentation (per stream of ticks; caller splits files)
    motion = [s > ZERO_EPS for s in speeds]
    ep_runs, dw_runs = run_lengths(motion)
    dwell_frac = sum(dw_runs) / n if n else float("nan")

    # Movement epochs
    epochs = []
    i = 0
    while i < n:
        if motion[i]:
            j = i
            while j < n and motion[j]:
                j += 1
            epochs.append((i, j))
            i = j
        else:
            i += 1

    durs, nets, paths, peaks, taus = [], [], [], [], []
    rev_per_ep, end_flip, yaw_dom = [], [], []
    prof_sum = [0.0] * 8
    prof_cnt = 0
    bell = []
    for b in range(8):
        u = (b + 0.5) / 8.0
        bell.append(30.0 * u * u * (1.0 - u) ** 2)
    bmax = max(bell)
    bell = [b / bmax for b in bell]

    for (s, e) in epochs:
        dy_e, dp_e = ys[s:e], ps[s:e]
        length = e - s
        durs.append(length)
        py = sum(abs(v) for v in dy_e)
        pp = sum(abs(v) for v in dp_e)
        paths.append(py + pp)
        nets.append(math.hypot(sum(dy_e), sum(dp_e)))
        sp = [math.hypot(a, b) for a, b in zip(dy_e, dp_e)]
        pk = max(sp)
        peaks.append(pk)
        taus.append(sp.index(pk) / max(1, length - 1))
        dom = dy_e if py >= pp else dp_e
        yaw_dom.append(py >= pp)
        signs = [1 if v > 0 else -1 for v in dom if abs(v) > ZERO_EPS]
        rev_per_ep.append(sum(1 for a, b in zip(signs, signs[1:]) if a != b))
        end_flip.append(len(signs) >= 2 and signs[-1] != signs[-2])
        if length >= 8:
            bins = [0.0] * 8
            cnt = [0] * 8
            for idx, v in enumerate(sp):
                b = min(7, int(idx * 8 / length))
                bins[b] += v
                cnt[b] += 1
            if all(c > 0 for c in cnt):
                prof_sum = [a + b / c for a, b, c in zip(prof_sum, bins, cnt)]
                prof_cnt += 1

    profile = [v / prof_cnt for v in prof_sum] if prof_cnt else []
    minjerk_r2 = float("nan")
    if profile:
        pm = sum(profile) / 8
        bm = sum(bell) / 8
        num = sum((a - pm) * (b - bm) for a, b in zip(profile, bell))
        den = math.sqrt(sum((a - pm) ** 2 for a in profile) *
                        sum((b - bm) ** 2 for b in bell))
        minjerk_r2 = (num / den) ** 2 if den > 0 else float("nan")

    # Fitts-like regressions
    fitts = ols([math.log2(1.0 + a) for a in nets], peaks)
    dur_amp = ols([math.log(a + gcd) for a in nets],
                  [math.log(float(d)) for d in durs]) if durs else {"slope": float("nan"), "r": float("nan")}

    # Windowed std (rolling, step 1) - the statistic AimSmooth-style checks use
    win = {}
    for w in (5, 10, 20):
        if n >= w:
            stds, mabs = [], []
            c = sum(ys[:w])
            # simple loop for clarity
            for s0 in range(0, n - w + 1):
                seg = ys[s0:s0 + w]
                m = sum(seg) / w
                stds.append(math.sqrt(sum((x - m) ** 2 for x in seg) / w))
                mabs.append(sum(abs(x) for x in seg) / w)
            win[w] = {"std": describe(stds), "mean_abs": describe(mabs)}

    both = sum(1 for a, b in zip(abs_y, abs_p) if a > ZERO_EPS and b > ZERO_EPS)
    both_gcd = sum(1 for a, b in zip(abs_y, abs_p) if a > gcd and b > gcd)
    both_025 = sum(1 for a, b in zip(abs_y, abs_p)
                   if a > MX_DUAL_THRESHOLD and b > MX_DUAL_THRESHOLD)

    micro = [s for s in speeds if s > ZERO_EPS and s <= gcd * 1.5]

    out = {
        "n_ticks": n,
        "speed": describe(speeds),
        "yaw_delta": describe(ys),
        "pitch_delta": describe(ps),
        "yaw_abs": describe(abs_y),
        "pitch_abs": describe(abs_p),
        "lag1_yaw_delta": acf(ys, 1), "lag2_yaw_delta": acf(ys, 2),
        "lag5_yaw_delta": acf(ys, 5),
        "lag1_pitch_delta": acf(ps, 1), "lag1_yaw_position": float("nan"),
        "gcd": gcd,
        "grid_residual_steps": {"p50": pct(sorted(resid), 0.5) if resid else float("nan"),
                                "p99": grid_p99,
                                "frac_ok": (sum(1 for r in resid if r <= GRID_RESID_TOL)
                                            / len(resid)) if resid else float("nan")},
        "gcd_steps_abs": describe(steps_abs),
        "dwell_frac": dwell_frac,
        "dwell_len": describe(dw_runs),
        "epoch_len_ticks": describe([float(d) for d in ep_runs]),
        "motion_frac": sum(ep_runs) / n if n else float("nan"),
        "epochs": {
            "count": len(epochs),
            "rate_per_s": len(epochs) * FS / max(1, n),
            "duration": describe([float(d) for d in durs]),
            "amplitude_net": describe(nets),
            "amplitude_path": describe(paths),
            "peak_speed": describe(peaks),
            "time_to_peak_tau": describe(taus),
            "minjerk_r2": minjerk_r2,
            "profile_8bin": profile,
            "fitts_peak_vs_log2_amp": {"slope": fitts["slope"],
                                       "intercept": fitts.get("intercept"),
                                       "r": fitts["r"]},
            "duration_vs_amplitude_exponent": {"slope": dur_amp.get("slope"),
                                               "intercept": dur_amp.get("intercept"),
                                               "r": dur_amp.get("r")},
            # Flick envelope (Phase-3): OLS peak-speed vs net amplitude on
            # A >= 15. The duration law (r below) under-predicts human
            # re-acquisition peaks 2-3x; this fit feeds PLAN_FLICK_*.
            "peak_vs_amplitude_A15": dict(
                ols([a for a in nets if a >= 15.0],
                    [pk for a, pk in zip(nets, peaks) if a >= 15.0]),
                n=sum(1 for a in nets if a >= 15.0)),
            "reversals_per_epoch": describe([float(v) for v in rev_per_ep]),
            "end_flip_rate": (sum(1 for v in end_flip if v) / len(end_flip)
                              if end_flip else float("nan")),
            "yaw_dominant_frac": (sum(1 for v in yaw_dom if v) / len(yaw_dom)
                                  if yaw_dom else float("nan")),
        },
        "dual_axis": {"both_nonzero_frac": both / n if n else float("nan"),
                      "both_gt_gcd_frac": both_gcd / n if n else float("nan"),
                      "both_gt_025_frac": both_025 / n if n else float("nan")},
        "micro_frac_nonzero": (len(micro) / max(1, sum(1 for s in speeds if s > ZERO_EPS))),
        "windowed": win,
    }

    # position-based extras
    pos_y, acc = [], 0.0
    for v in ys:
        acc += v
        pos_y.append(acc)
    out["lag1_yaw_position"] = acf(pos_y, 1)
    ma = []
    if len(pos_y) >= 11:
        for i2 in range(10, len(pos_y)):
            ma.append(pos_y[i2] - sum(pos_y[i2 - 10:i2]) / 10.0)
        out["fast_wobble_rms_deg"] = math.sqrt(sum(v * v for v in ma) / len(ma))
    else:
        out["fast_wobble_rms_deg"] = float("nan")
    if with_spectral:
        out["spectrum"] = spectral_features(_linear_detrend(pos_y), ys)
    return out


# ---------------------------------------------------------------- groups

def group_features(files, yaw_key, pitch_key):
    all_ys, all_ps = [], []
    per_file = []
    epoch_durs, epoch_nets, dwell_lens = [], [], []
    lag1s, lag1ps = [], []
    profile_sum, profile_n = [0.0] * 8, 0
    sess_meta = []
    wrap_count = 0
    for f in files:
        sess = f["session"] or {}
        gcd = sess.get("gcd") or 0.09143358
        ys, ps = deltas(f["ticks"], yaw_key, pitch_key)
        # raw (pre-wrap) jump count on yaw
        raw_prev, raw_now = None, None
        for t in f["ticks"]:
            y = t.get(yaw_key)
            if y is None:
                raw_prev = None
                continue
            if raw_prev is not None and abs(y - raw_prev) > 180.0:
                wrap_count += 1
            raw_prev = y
        feat = axis_features(ys, ps, gcd)
        per_file.append({"file": Path(f["path"]).name, "features": feat})
        all_ys.extend(ys)
        all_ps.extend(ps)
        lag1s.append(feat["lag1_yaw_delta"])
        lag1ps.append(feat["lag1_pitch_delta"])
        sess_meta.append({"file": Path(f["path"]).name,
                          "schema": sess.get("schema"), "gcd": gcd,
                          "sens": sess.get("mouse_sensitivity"),
                          "ticks": len(f["ticks"]), "packets": f["packets"]})
        # roll up epochs/dwells from per-tick streams for pooled distributions
        speed = [math.hypot(a, b) for a, b in zip(ys, ps)]
        motion = [s > ZERO_EPS for s in speed]
        ep, dw = run_lengths(motion)
        epoch_durs.extend(float(d) for d in ep)
        dwell_lens.extend(float(d) for d in dw)
        prof = feat["epochs"]["profile_8bin"]
        if prof:
            profile_sum = [a + b for a, b in zip(profile_sum, prof)]
            profile_n += 1
    pooled = axis_features(all_ys, all_ps,
                           sess_meta[0]["gcd"] if sess_meta else 0.09143358)
    # pooled rolling window-std distribution across the whole group
    for w in (5, 10, 20):
        stds = []
        n = len(all_ys)
        if n >= w:
            for s0 in range(0, n - w + 1):
                seg = all_ys[s0:s0 + w]
                m = sum(seg) / w
                stds.append(math.sqrt(sum((x - m) ** 2 for x in seg) / w))
        pooled.setdefault("windowed", {})[w] = {"std": describe(stds)}
    pooled["lag1_yaw_delta_per_file"] = describe(lag1s)
    pooled["lag1_pitch_delta_per_file"] = describe(lag1ps)
    pooled["epoch_duration_pooled"] = describe(epoch_durs)
    pooled["dwell_duration_pooled"] = describe(dwell_lens)
    pooled["yaw_wrap_count"] = wrap_count
    if profile_n:
        pooled["epochs"]["profile_8bin_mean"] = [v / profile_n for v in profile_sum]
    return {"meta": sess_meta, "pooled": pooled, "per_file": per_file}


def slow_noise_features(human_files, yaw_key="player_yaw", pitch_key="player_pitch",
                        speed_cap=1.5, window=9):
    """Motor-noise floor: residual over a local linear trend inside slow
    segments. Plan/curvature is removed by the local fit, so what remains is
    tremor + micro-correction noise - the parameter the redo's noise stage must
    reproduce. (Raw-spectrum betas are movement-dominated; per-epoch min-jerk
    residuals are curvature-dominated; neither is a noise sigma.)
    """
    res_y, res_p, slow_speeds = [], [], []
    windows = 0
    half = window // 2
    for f in human_files:
        ticks = f["ticks"]
        ys, ps, posy, posp = [], [], [], []
        cy = cp = 0.0
        prev = None
        for t in ticks:
            if t.get(yaw_key) is None:
                prev = None
                continue
            if prev is not None:
                dy = wrap180(t[yaw_key] - prev[0])
                dp = t[pitch_key] - prev[1]
                ys.append(dy)
                ps.append(dp)
                cy += dy
                cp += dp
                posy.append(cy)
                posp.append(cp)
            prev = (t[yaw_key], t[pitch_key])
        n = len(ys)
        speed = [math.hypot(a, b) for a, b in zip(ys, ps)]
        slow_speeds.extend(s for s in speed if s < speed_cap)
        slow = [s < speed_cap for s in speed]
        i = half
        while i + half < n:
            lo, hi = i - half, i + half + 1
            if all(slow[lo:hi]):
                windows += 1
                xs = list(range(lo, hi))
                for arr, acc in ((posy, res_y), (posp, res_p)):
                    seg = arr[lo:hi]
                    mx, my = sum(xs) / window, sum(seg) / window
                    sxx = sum((x - mx) ** 2 for x in xs)
                    if sxx <= 0:
                        continue
                    b = sum((x - mx) * (y - my) for x, y in zip(xs, seg)) / sxx
                    a = my - b * mx
                    acc.extend(y - (a + b * x) for x, y in zip(xs, seg))
            i += 1
    ss = sorted(slow_speeds)
    ry = math.sqrt(sum(v * v for v in res_y) / len(res_y)) if res_y else float("nan")
    rp = math.sqrt(sum(v * v for v in res_p) / len(res_p)) if res_p else float("nan")
    return {
        "method": f"slow segments (speed<{speed_cap}), {window}-tick local linear detrend",
        "slow_windows": windows,
        "position_resid_rms_yaw_deg": ry,
        "position_resid_rms_pitch_deg": rp,
        "residual_lag1_yaw": acf(res_y, 1),
        "residual_lag1_pitch": acf(res_p, 1),
        "slow_regime_speed_p50_deg": pct(ss, 0.5),
        "slow_regime_speed_p90_deg": pct(ss, 0.9),
        "slow_regime_zero_frac": (sum(1 for v in ss if v < ZERO_EPS) / len(ss)
                                  if ss else float("nan")),
    }


def noise_by_speed(human_files, yaw_key="player_yaw", pitch_key="player_pitch",
                   bin_edges=(0.15, 0.4, 0.9, 1.5, 5.0, 15.0), window=9,
                   min_windows=30):
    """Sigma(v): the Phase-3 step-2 refit behind the §11.2 known-gap note.

    Same local-detrend residual method as slow_noise_features, but windows
    are binned by mean speed; bins above the slow cap detrend QUADRATICALLY
    so flick/acceleration curvature is not read as noise. Sparse bins
    (< min_windows) carry the previous populated bin's sigma. Feeds the
    noise stage's sigma(speed) lookup (FittedAimParams.NOISE_BIN_*).
    """
    n_bins = len(bin_edges) + 1
    res_y = [[] for _ in range(n_bins)]
    res_p = [[] for _ in range(n_bins)]
    half = window // 2
    xs = [j - half for j in range(window)]
    sx2 = sum(x * x for x in xs)
    sx4 = sum(x * x * x * x for x in xs)
    det = window * sx4 - sx2 * sx2
    for f in human_files:
        ticks = f["ticks"]
        posy, posp = [], []
        cy = cp = 0.0
        prev = None
        for t in ticks:
            if t.get(yaw_key) is None:
                prev = None
                continue
            if prev is not None:
                dy = wrap180(t[yaw_key] - prev[0])
                dp = t[pitch_key] - prev[1]
                cy += dy
                cp += dp
                posy.append(cy)
                posp.append(cp)
            prev = (t[yaw_key], t[pitch_key])
        n = len(posy)
        if n < window:
            continue
        # speed[m] = dy_m over the SAME indices as posy (dy_0 = posy[0]).
        speed = [math.hypot(posy[0], posp[0])]
        for j in range(1, n):
            speed.append(math.hypot(posy[j] - posy[j - 1],
                                    posp[j] - posp[j - 1]))
        # speed[j-1] = delta j; a window over positions [lo2, hi2) spans
        # deltas [lo, hi). Classification = ALL deltas inside one bin, so
        # bin0 reproduces the strict slow-floor window set exactly and no
        # window straddles a boundary (straddlers are skipped).
        i = half
        while i + half < n:
            lo, hi = i - half, i + half
            # 9 deltas dy[lo..hi] = the exact window set slow_noise_features
            # uses (bin0 must reproduce the floor sigma), which covers every
            # delta entering or produced inside positions[lo..hi].
            seg_sp = speed[lo:hi + 1]
            b = 0
            while b < len(bin_edges) and max(seg_sp) >= bin_edges[b]:
                b += 1
            lo_edge = bin_edges[b - 1] if b > 0 else 0.0
            if min(seg_sp) < lo_edge:
                i += 1
                continue
            # Sub-1.5 bins stay linear (the slow-floor regime); faster bins
            # go quadratic so acceleration curvature is not read as noise.
            quad = lo_edge >= 1.5
            lo2, hi2 = i - half, i + half + 1
            for arr, out in ((posy, res_y[b]), (posp, res_p[b])):
                seg = arr[lo2:hi2]
                my = sum(seg) / window
                s1 = sum(x * y for x, y in zip(xs, seg))
                if quad:
                    s0 = my * window
                    s2 = sum(x * x * y for x, y in zip(xs, seg))
                    b0 = (sx4 * s0 - sx2 * s2) / det
                    b2 = (window * s2 - sx2 * s0) / det
                    k = s1 / sx2
                    out.extend(y - (b0 + k * x + b2 * x * x)
                               for x, y in zip(xs, seg))
                else:
                    k = s1 / sx2
                    out.extend(y - (my + k * x) for x, y in zip(xs, seg))
            i += 1
    raw = []
    for b in range(n_bins):
        ry = (math.sqrt(sum(v * v for v in res_y[b]) / len(res_y[b]))
              if res_y[b] else float("nan"))
        rp = (math.sqrt(sum(v * v for v in res_p[b]) / len(res_p[b]))
              if res_p[b] else float("nan"))
        raw.append((ry, rp, len(res_y[b]) // window))
    edges = list(bin_edges) + [1e9]
    last, bins = None, []
    for b, (ry, rp, n_w) in enumerate(raw):
        if n_w >= min_windows:
            last = (ry, rp)
            sy, sp, carried = ry, rp, False
        elif last is not None:
            sy, sp, carried = last[0], last[1], True
        else:
            sy, sp, carried = ry, rp, False
        bins.append({"max_speed": edges[b], "sigma_yaw": sy, "sigma_pitch": sp,
                     "n_windows": n_w, "carried": carried})
    return {
        "method": (f"9-tick windows fully inside one speed bin (all deltas); "
                   f"linear detrend below 1.5 deg/tick (floor-compatible), "
                   f"quadratic above; sparse (<{min_windows} windows) carry "
                   f"the previous bin"),
        "bin_edges_deg": list(bin_edges),
        "bins": bins,
    }


# ---------------------------------------------------------------- guards

def guard(human_files, shaper_files):
    """Fail loudly if the partition rules are violated (the confusion check).

    For the manual files, the interesting failure mode is not trail magnitude
    (fast player flicks make the lingering release-branch shaper lag behind by
    >15 deg all the time) but whether the server rotation was ever aimed
    SOMEWHERE ELSE than the player. Two semantic tests settle it:
      1. every >15 deg trail episode must be explained by player motion
         (path length since trail was last <2deg >= 0.4 x growth) - the server
         cannot have snapped away from a slow player;
      2. every idle run of >=12 ticks must END with the server converged back
         on the player (trail <2deg) - an entity-aimed server parks away from
         the crosshair while the player rests, the release-branch follower
         does not.
    Plus the raw-mouse fingerprint: the player stream itself must sit on the
    GCD grid (residual <=0.02 steps on >=99% of nonzero deltas).
    """
    problems = []
    rows = []
    for f in human_files:
        sess = f["session"] or {}
        ticks = f["ticks"]
        gcd = sess.get("gcd") or 0.09143358
        tgt = sum(1 for t in ticks
                  if t.get("target_yaw") is not None)
        srcs = {}
        near_attack = 0
        for t in ticks:
            srcs[t.get("source")] = srcs.get(t.get("source"), 0) + 1
            tsa = t.get("ticks_since_attack")
            if tsa is not None and tsa <= 15:
                near_attack += 1

        # per-tick trail + player speed + player grid residual
        recs, prev = [], None
        resid = []
        for t in ticks:
            if t.get("player_yaw") is None or t.get("server_yaw") is None:
                prev = None
                continue
            py, pp = t["player_yaw"], t["player_pitch"]
            sy, sp = t["server_yaw"], t["server_pitch"]
            if prev is not None:
                trail = math.hypot(wrap180(sy - py), sp - pp)
                ps = math.hypot(wrap180(py - prev[0]), pp - prev[1])
                recs.append((trail, ps))
                # GCD grid of the raw mouse stream (skip clamped pitch)
                dy = wrap180(py - prev[0])
                if abs(dy) > ZERO_EPS:
                    s = dy / gcd
                    resid.append(abs(s - round(s)))
                clamped = abs(abs(pp) - 90) < 1e-6 or abs(abs(prev[1]) - 90) < 1e-6
                if not clamped:
                    dp = pp - prev[1]
                    if abs(dp) > ZERO_EPS:
                        s = dp / gcd
                        resid.append(abs(s - round(s)))
            prev = (py, pp, sy, sp)
        n = len(recs)
        resid_sorted = sorted(resid)
        grid_frac_ok = (sum(1 for r in resid if r <= GRID_RESID_TOL) / len(resid)
                        if resid else float("nan"))

        # test 1: episodes >15deg explained by player motion
        ep_count, ep_fail, ratios = 0, 0, []
        i = 0
        while i < n:
            if recs[i][0] > 15.0:
                j = i
                while j + 1 < n and recs[j + 1][0] > 15.0:
                    j += 1
                peak = max(range(i, j + 1), key=lambda k: recs[k][0])
                b = peak
                while b > 0 and recs[b - 1][0] >= 2.0:
                    b -= 1
                base = recs[b - 1][0] if b > 0 else 0.0
                growth = recs[peak][0] - base
                motion = sum(recs[k][1] for k in range(b, peak + 1))
                if growth > 0:
                    ratio = motion / growth
                    ratios.append(ratio)
                    ep_count += 1
                    if ratio < 0.4:
                        ep_fail += 1
                i = j + 1
            else:
                i += 1

        # test 2: idle runs >=12 ticks end converged on the player
        idle_count, idle_fail, idle_max = 0, 0, float("nan")
        idle_ends = []
        i = 0
        while i < n:
            if recs[i][1] < 0.1:
                j = i
                while j + 1 < n and recs[j + 1][1] < 0.1:
                    j += 1
                if j - i + 1 >= 12:
                    te = recs[j][0]
                    idle_ends.append(te)
                    idle_count += 1
                    if te >= 2.0:
                        idle_fail += 1
                i = j + 1
            else:
                i += 1
        if idle_ends:
            idle_max = max(idle_ends)

        pm = sum(1 for k in range(1, len(recs)) if recs[k][1] > ZERO_EPS) \
            / max(1, len(recs) - 1)
        trail_vals = sorted(r[0] for r in recs)

        name = Path(f["path"]).name
        row = {
            "file": name, "schema": sess.get("schema"), "ticks": len(ticks),
            "sources": srcs, "target_nonnull": tgt,
            "click_evidence_frac": near_attack / max(1, len(ticks)),
            "player_motion_frac": pm,
            "trail_p50": pct(trail_vals, 0.5), "trail_p95": pct(trail_vals, 0.95),
            "trail_max": trail_vals[-1] if trail_vals else float("nan"),
            "grid_frac_ok": grid_frac_ok,
            "grid_p99": pct(resid_sorted, 0.99),
            "episodes_gt15": ep_count, "episode_buildup_fail": ep_fail,
            "episode_ratio_min": min(ratios) if ratios else float("nan"),
            "idle_runs": idle_count, "idle_fail": idle_fail,
            "idle_trail_max": idle_max,
        }
        rows.append(row)
        if sess.get("schema") != 8:
            problems.append(f"{name}: expected schema 8, got {sess.get('schema')}")
        if tgt > 0:
            problems.append(f"{name}: {tgt} fresh targets - not a clean manual recording")
        if ep_fail:
            problems.append(
                f"{name}: {ep_fail}/{ep_count} high-trail episodes NOT explained by "
                f"player motion (min ratio {row['episode_ratio_min']:.2f}) - server "
                f"may have aimed at an entity")
        if idle_fail:
            problems.append(
                f"{name}: {idle_fail}/{idle_count} idle runs end with the server "
                f"parked away from the player (max trail {idle_max:.1f}deg) - "
                f"server aiming elsewhere during manual play")
        if not (grid_frac_ok >= 0.99):
            problems.append(
                f"{name}: player stream off GCD grid (ok={grid_frac_ok:.3f}, "
                f"p99={row['grid_p99']:.3f}) - not raw vanilla mouse input")
        if pm < 0.10:
            problems.append(f"{name}: player mouse nearly frozen "
                            f"({pm:.0%} moving) - not manual play")
    for f in shaper_files:
        sess = f["session"] or {}
        ticks = f["ticks"]
        kaur = [t for t in ticks if t.get("source") == "KillAura"]
        tgt = sum(1 for t in kaur if t.get("target_yaw") is not None)
        name = Path(f["path"]).name
        rows.append({"file": name, "schema": sess.get("schema"),
                     "ticks": len(ticks),
                     "killaura_ticks": len(kaur),
                     "target_nonnull": tgt,
                     "target_ratio": tgt / max(1, len(kaur))})
        if kaur and tgt / len(kaur) < 0.5:
            problems.append(f"{name}: KillAura ticks mostly lack fresh targets "
                            f"({tgt}/{len(kaur)}) - not the expected bot reference")
    return rows, problems


# ---------------------------------------------------------------- main

def main():
    here = Path.cwd()
    human_paths = sorted(glob.glob(HUMAN_GLOB))
    shaper_paths = sorted(glob.glob(SHAPER_GLOB))
    if not human_paths or not shaper_paths:
        print("missing recordings", file=sys.stderr)
        return 2

    human_files = [load_session(p) for p in human_paths]
    shaper_files = [load_session(p) for p in shaper_paths]

    rows, problems = guard(human_files, shaper_files)

    print("=" * 78)
    print("PARTITION GUARD")
    print("=" * 78)
    for r in rows:
        if "sources" in r:
            print(f"  HUMAN  {r['file']}  schema={r['schema']} ticks={r['ticks']} "
                  f"src={r['sources']} tgt={r['target_nonnull']}")
            print(f"         click_frac={r['click_evidence_frac']:.2f} "
                  f"player_move={r['player_motion_frac']:.2f} "
                  f"grid_ok={r['grid_frac_ok']:.3f} grid_p99={r['grid_p99']:.4f} "
                  f"trail p50={r['trail_p50']:.2f} p95={r['trail_p95']:.2f} "
                  f"max={r['trail_max']:.1f}")
            print(f"         episodes>15deg={r['episodes_gt15']} "
                  f"unexplained={r['episode_buildup_fail']} "
                  f"(min buildup ratio {r['episode_ratio_min']:.2f}) | "
                  f"idle_runs={r['idle_runs']} unconverged={r['idle_fail']} "
                  f"(max end trail {r['idle_trail_max']:.2f})")
        else:
            print(f"  SHAPER {r['file']}  schema={r['schema']} ticks={r['ticks']} "
                  f"kaur={r.get('killaura_ticks')} tgt={r['target_nonnull']} "
                  f"ratio={r.get('target_ratio', float('nan')):.2f}")
    if problems:
        print("\n!! CONFUSION DETECTED:")
        for p in problems:
            print("   -", p)
        return 3
    print("  OK: partition holds (Sep22 = manual raw-mouse / fresh-target-free, "
          "Sep19 = bot / fresh targets)")
    print()

    human = group_features(human_files, "player_yaw", "player_pitch")
    trail = group_features(human_files, "server_yaw", "server_pitch")
    shaper = group_features(shaper_files, "server_yaw", "server_pitch")
    slow_noise = slow_noise_features(human_files)
    noise_bins = noise_by_speed(human_files)

    def line(tag, f):
        e = f["epochs"]
        sp = f["speed"]
        print(f"  {tag:<9} speed p50={sp.get('p50', float('nan')):.4f} "
              f"p90={sp.get('p90', float('nan')):.3f} p99={sp.get('p99', float('nan')):.3f} | "
              f"lag1={f['lag1_yaw_delta']:+.3f} | dwell_frac={f['dwell_frac']:.3f} | "
              f"ep/s={e['rate_per_s']:.2f} ep_dur p50={e['duration'].get('p50', float('nan')):.1f} | "
              f"dual={f['dual_axis']['both_nonzero_frac']:.2f} | "
              f"minjerkR2={e['minjerk_r2']:.2f}")

    print("=" * 78)
    print("FEATURE COMPARISON (pooled)")
    print("=" * 78)
    line("HUMAN", human["pooled"])
    line("TRAIL", trail["pooled"])
    line("SHAPER", shaper["pooled"])
    print()

    h, t, s = human["pooled"], trail["pooled"], shaper["pooled"]

    # KS comparisons (distributional distance only - no trained model)
    def abs_samples(files, yk, pk):
        sy, sp = [], []
        for f in files:
            ys, ps = deltas(f["ticks"], yk, pk)
            sy.extend(abs(v) for v in ys)
            sp.extend(abs(v) for v in ps)
        return sy, sp

    hy, hp = abs_samples(human_files, "player_yaw", "player_pitch")
    ty, tp = abs_samples(human_files, "server_yaw", "server_pitch")
    sy, sp_ = abs_samples(shaper_files, "server_yaw", "server_pitch")

    ks = {
        "yaw_abs_human_vs_shaper": ks2(hy, sy),
        "yaw_abs_human_vs_trail": ks2(hy, ty),
        "pitch_abs_human_vs_shaper": ks2(hp, sp_),
        "epoch_dur_human_vs_shaper": ks2(h["epoch_duration_pooled"].get("n") and
                                         _raw_run_samples(human_files, "player") or [],
                                         _raw_run_samples(shaper_files, "server")),
        "win10std_human_vs_shaper": ks2(
            _win_std_samples(human_files, "player_yaw"),
            _win_std_samples(shaper_files, "server_yaw")),
    }
    print("KS distance (0=identical, 1=disjoint):")
    for k, v in ks.items():
        print(f"  {k:<34} {v:.3f}")
    print()

    # ------------------------------------------------------------ fitted
    spec = h.get("spectrum", {})
    fitted = {
        "partition": {
            "human_files": [Path(p).name for p in human_paths],
            "shaper_files": [Path(p).name for p in shaper_paths],
            "human_signal": "player_yaw/player_pitch",
            "shaper_signal": "server_yaw/server_pitch",
            "linger_caveat": "Sep22 source label says KillAura; it is the "
                             "release-branch linger. target_yaw null everywhere.",
        },
        "noise": {
            "beta_position": spec.get("position", {}).get("beta"),
            "beta_velocity": spec.get("velocity", {}).get("beta"),
            "peak_hz": spec.get("position", {}).get("peak_hz"),
            "band_fractions": spec.get("position", {}).get("band_fractions"),
            "fast_wobble_rms_deg": h.get("fast_wobble_rms_deg"),
            "lag1_yaw_delta": h["lag1_yaw_delta"],
            "lag1_pitch_delta": h["lag1_pitch_delta"],
            "lag2_yaw_delta": h["lag2_yaw_delta"],
            "lag5_yaw_delta": h["lag5_yaw_delta"],
            "floor": slow_noise,
            "by_speed": noise_bins,
        },
        "dwell": {
            "frac_ticks": h["dwell_frac"],
            "mean_ticks": h["dwell_duration_pooled"].get("mean"),
            "p90_ticks": h["dwell_duration_pooled"].get("p90"),
            "max_ticks": h["dwell_duration_pooled"].get("max"),
            "transitions_per_s": h["dwell_frac"] * FS / max(
                1.0, h["dwell_duration_pooled"].get("mean", 1.0)),
        },
        "epochs": {
            "rate_per_s": h["epochs"]["rate_per_s"],
            "duration": h["epochs"]["duration"],
            "amplitude_net": h["epochs"]["amplitude_net"],
            "time_to_peak_tau": h["epochs"]["time_to_peak_tau"],
            "minjerk_r2": h["epochs"]["minjerk_r2"],
            "profile_8bin": h["epochs"].get("profile_8bin_mean") or h["epochs"]["profile_8bin"],
            "fitts_peak_vs_log2_amp": h["epochs"]["fitts_peak_vs_log2_amp"],
            "duration_vs_amplitude_exponent": h["epochs"]["duration_vs_amplitude_exponent"],
            "peak_vs_amplitude_A15": h["epochs"]["peak_vs_amplitude_A15"],
        },
        "correction": {
            "micro_frac_nonzero": h["micro_frac_nonzero"],
            "gcd_steps_abs": h["gcd_steps_abs"],
            "grid_residual_p99_steps": h["grid_residual_steps"]["p99"],
            "reversals_per_epoch": h["epochs"]["reversals_per_epoch"],
            "end_flip_rate": h["epochs"]["end_flip_rate"],
        },
        "servo_hints": {
            "human_lag1": h["lag1_yaw_delta"],
            "trail_lag1": t["lag1_yaw_delta"],
            "shaper_lag1": s["lag1_yaw_delta"],
            "human_dwell_frac": h["dwell_frac"],
            "trail_dwell_frac": t["dwell_frac"],
            "shaper_dwell_frac": s["dwell_frac"],
            "trail_minjerk_r2": t["epochs"]["minjerk_r2"],
            "human_minjerk_r2": h["epochs"]["minjerk_r2"],
            "trail_end_flip_rate": t["epochs"]["end_flip_rate"],
            "human_end_flip_rate": h["epochs"]["end_flip_rate"],
        },
        "mc": {"gcd": h["gcd"], "windowed_std": h["windowed"]},
        "ks": ks,
    }

    out = {"meta": {"created": "analysis of manual + bot recordings",
                    "fs": FS,
                    "guard_rows": rows},
           "human": human, "trailing_shaper": trail, "bot_shaper": shaper,
           "fitted": fitted}
    with open(OUT_JSON, "w") as fh:
        json.dump(out, fh, indent=1, allow_nan=True)

    print("=" * 78)
    print("FITTED PARAMETERS (for the architecture redo)")
    print("=" * 78)
    print(json.dumps({k: fitted[k] for k in
                      ("noise", "dwell", "correction", "servo_hints")}, indent=1))
    print(f"\nwrote {OUT_JSON}")
    return 0

def _raw_run_samples(files, which):
    out = []
    for f in files:
        yk, pk = (("player_yaw", "player_pitch") if which == "player"
                  else ("server_yaw", "server_pitch"))
        ys, ps = deltas(f["ticks"], yk, pk)
        speed = [math.hypot(a, b) for a, b in zip(ys, ps)]
        motion = [s > ZERO_EPS for s in speed]
        ep, _ = run_lengths(motion)
        out.extend(float(d) for d in ep)
    return out


def _win_std_samples(files, yaw_key, w=10):
    out = []
    for f in files:
        ys, _ = deltas(f["ticks"], yaw_key, "server_pitch" if "server" in yaw_key else "player_pitch")
        n = len(ys)
        if n >= w:
            for s0 in range(0, n - w + 1):
                seg = ys[s0:s0 + w]
                m = sum(seg) / w
                out.append(math.sqrt(sum((x - m) ** 2 for x in seg) / w))
    return out


if __name__ == "__main__":
    sys.exit(main())
