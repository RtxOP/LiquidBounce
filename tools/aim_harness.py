#!/usr/bin/env python3
"""Feature-parity + MX-rule harness for the aim-humanization redo.

Replaces /tmp/opencode/mx_sim.py (recovered from the opencode session db;
/tmp does not persist). Two test groups, thresholds derived from the fitted
human baselines in tools/human_corpus_features.json:

  1. FEATURE-PARITY checks (gating): lag-1 autocorr, min-jerk R2, epoch rate,
     dwell fraction, end-flip rate, GCD grid residual, KS distance vs the
     human corpus. Thresholds = human baseline +/- explicit margins below.

  2. MX-RULE checks (informational, per-window fixed): the seven published
     MX-Project heuristics ported from mx_sim.py. The original session-level
     cumulative counters were misleading (no window reset); these evaluate
     per-window trips as MX does (window = last 10 rotations etc.). A
     candidate stream must not look more bot-like than the human stream on
     any metric (rate <= human * 1.5 + abs tolerance).

Acceptance contract (this is what makes validation non-circular):
  - HUMAN  (Sep 22 player stream = vanilla-wire-equivalent raw mouse)
    must PASS every feature check          -> thresholds are satisfiable
  - TRAIL  (Sep 22 server stream = today's engine on human input)
    must FAIL >= 1 feature check           -> harness can detect the engine
  - SHAPER (Sep 19 server stream = bot output)
    must FAIL >= 1 feature check           -> ditto

Exit codes: 0 = contract met, 1 = contract violated, 2 = missing inputs,
3 = partition guard violation (see human_features.py).

Stdlib only; no learned models (distribution/KS comparisons only, per
project decision: no adversarial proxy classifier).

Run from the repo root:
    python3 tools/aim_harness.py                       # group contract (0/1/2/3)
    python3 tools/aim_harness.py --candidate <file>    # judge one engine
                                                        # replay (0 pass / 1 fail / 2 bad input)
"""
from __future__ import annotations

import glob
import json
import math
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import human_features as hf  # noqa: E402  (path set above)

FEATURES_JSON = Path("tools/human_corpus_features.json")

# Threshold margins below/around the fitted human baselines. Every feature
# threshold is derived from the JSON so the corpus stays the single source of
# truth; margins are the only free numbers and are documented here.
LAG1_MARGIN = 0.15        # human 0.800 -> require >= 0.650
MINJERK_MARGIN = 0.10     # human 0.80  -> require >= 0.70
RATE_MARGIN = 0.20        # human 1.00/s -> require 0.80..1.20
DWELL_MARGIN = 0.05       # human 0.206 -> require 0.156..0.256
ENDFLIP_FACTOR = 2.0      # human 0.024 -> require <= 0.050
ENDFLIP_FLOOR = 0.002
GRID_TOL = hf.GRID_RESID_TOL   # 0.02 steps; human 0.00057
KS_MAX = None             # set from JSON/corpus at runtime (see derive_thresholds)

# MX metric tolerance: candidate rate per 1000 deltas must not exceed
# human_rate * MX_TOL_FACTOR + MX_TOL_ABS.
MX_TOL_FACTOR = 1.5
MX_TOL_ABS = 0.5          # per 1000 deltas
MX_TOL_ABS_RATIO = 0.01   # for ratio metrics (AimConstant)


# ------------------------------------------------------------------ basics

def shannon(seq):
    if not seq:
        return 0.0
    c = Counter(seq)
    n = len(seq)
    return -sum((v / n) * math.log2(v / n) for v in c.values())


def ks2(a, b):
    return hf.ks2(a, b)


def abs_samples(files, yk, pk):
    sy, sp = [], []
    for f in files:
        ys, ps = hf.deltas(f["ticks"], yk, pk)
        sy.extend(abs(v) for v in ys)
        sp.extend(abs(v) for v in ps)
    return sy, sp


def half_split_ks(samples):
    """Self-consistency KS: first half vs second half of the human corpus."""
    n = len(samples)
    if n < 40:
        return float("nan")
    return ks2(samples[: n // 2], samples[n // 2:])


# -------------------------------------------------------------- MX port
# Ported from mx_sim.py with the windowing fix: MX evaluates rolling/block
# windows (AimBasic: window = last 10 rotations), not session-cumulative
# counters. Formulas/thresholds preserved verbatim from the MX-Project
# sources the original sim was ported from.

def mx_metrics(dys, dps, gcd):
    """One file's stream (yaw deltas, pitch deltas) -> MX check counters."""
    n = len(dys)
    m = {"n": n}
    if n < 30:
        return m

    # ---- AimSmoothCheck: motion angle atan2(dy, dp) deg % 90, 3-consecutive
    angles = []
    for dy, dp in zip(dys, dps):
        if (abs(dp) > 1.5 and abs(dy) > 0.32) or abs(dy) > 1.5:
            angles.append(math.degrees(math.atan2(dy, dp)) % 90.0)
    run3 = 0
    for i in range(2, len(angles)):
        if (abs(angles[i] - angles[i - 1]) < 1e-6
                and abs(angles[i - 1] - angles[i - 2]) < 1e-6):
            run3 += 1
    m["smooth_run3"] = run3
    m["smooth_qual"] = len(angles)

    # ---- AimBasicCheck: per-window trips, W = 10 rotations
    W = 10
    trips = {"rob": 0, "mac": 0, "con": 0, "agg": 0, "gcdx": 0}
    for i in range(W, n + 1):
        w = dys[i - W:i]
        first = w[0]
        rob = mac = con = agg = gx = 0
        for yc in w[1:]:
            if abs(yc - first) < 2 and yc > 2.5:
                rob += 1
            if abs(yc - first) < 0.99 and yc > 4:
                mac += 1
            if abs(yc - first) < 0.02 and yc > 3:
                con += 1
            if abs(yc - first) < 2 and yc > 3:
                agg += 1
            if round(yc, 2) in (0.1, 0.01):
                gx += 1
        if rob > 8:
            trips["rob"] += 1
        if mac > 7:
            trips["mac"] += 1
        if con > 3:
            trips["con"] += 1
        if agg > 8:
            trips["agg"] += 1
        if gx > 0:
            trips["gcdx"] += 1
    for k, v in trips.items():
        m[f"basic_{k}"] = v
    m["basic_windows"] = max(0, n - W + 1)

    # ---- AimPatternCheck: exact repeated Vec2f pairs (sep >= 3) + cross-axis
    pairs = list(zip(dys, dps))
    rep, seen = 0, {}
    for i, p in enumerate(pairs):
        if abs(p[0]) > 0.26 and abs(p[1]) > 0.26:
            if p in seen and i - seen[p] >= 3:
                rep += 1
            elif p not in seen:
                seen[p] = i
    m["pattern_repeat"] = rep
    m["pattern_cross"] = sum(1 for i in range(1, n)
                             if abs(dys[i] - dps[i - 1]) < 1e-4)

    # ---- AimComplexCheck: shannon entropy blocks of 50
    WIN = 50
    blocks = list(range(0, max(0, n - WIN + 1), WIN))
    yawE = [shannon([round(d / gcd) for d in dys[i:i + WIN]]) for i in blocks]
    pitE = [shannon([round(d / gcd) for d in dps[i:i + WIN]]) for i in blocks]
    m["complex_perfect"] = sum(
        1 for i in range(1, len(yawE))
        if abs(yawE[i] - yawE[i - 1]) < 1e-5 and abs(pitE[i] - pitE[i - 1]) < 1e-5)
    m["complex_similar"] = sum(1 for a, b in zip(yawE, pitE) if abs(a - b) < 0.05)
    flags9 = 0
    for i in blocks:
        w = dys[i:i + WIN]
        d = len(set(round(x / gcd) for x in w))
        avg = sum(abs(x) for x in w) / len(w)
        if d < 8 and avg > 2.5:
            flags9 += 1
    m["complex_distinct"] = flags9
    m["complex_blocks"] = len(blocks)

    # ---- AimInconsistentCheck: 2nd-order deltas, non-overlapping 60-blocks
    # (session-cumulative IQR counts in mx_sim are length-dependent and
    # therefore not comparable across streams; block them like MX buffers.)
    def iqr_counts(s):
        if len(s) < 4:
            return 0, 0
        s2 = sorted(s)
        k = len(s2)
        q1, q3 = s2[k // 4], s2[3 * k // 4]
        r = q3 - q1
        lo, hi = q1 - 1.5 * r, q3 + 1.5 * r
        outs = sum(1 for v in s2 if v < lo or v > hi)
        dup = len(s2) - len(set(s2))
        return outs, dup

    joltx = [round(abs(dys[i] - abs(dys[i] - dys[i - 1])), 2) for i in range(1, n)]
    joltp = [round(abs(dps[i] - abs(dps[i] - dps[i - 1])), 2) for i in range(1, n)]
    incons = blocks60 = 0
    for i in range(0, len(joltx), 60):
        bx, bp = joltx[i:i + 60], joltp[i:i + 60]
        if len(bx) < 4:
            continue
        blocks60 += 1
        ox, dx_ = iqr_counts(bx)
        op, dp_ = iqr_counts(bp)
        dupsum = dx_ + dp_
        too_clean = dupsum <= 3 and ox < 10 and op < 7
        zero_ax = (ox == 0 or op == 0) and (ox > 1 or op > 1) and dupsum <= 3
        if too_clean or zero_ax:
            incons += 1
    m["inconsistent_trips"] = incons
    m["inconsistent_blocks"] = blocks60

    # ---- AimFactorCheck: still-big-still snap windows, punish at vl >= 3
    stack, vl, hits, punishes = [], 0.0, 0, 0
    for d in dys:
        v = round(abs(d), 2)
        stack.append(v)
        if len(stack) > 3:
            stack.pop(0)
        if len(stack) < 3:
            continue
        centre = stack[1]
        if not (centre > 35):
            continue
        invalid = ((stack[0] < 1.2 and stack[2] < 1.2)
                   or (stack[0] > 55 and stack[1] < 2 and stack[2] > 55)
                   or (max(stack) > 70 and min(stack) < 1.2
                       and len(set(stack)) != 3))
        if invalid:
            loc = 3 if centre > 160 else (1 if centre < 60 else 2)
            vl += loc
            hits += 1
            if vl >= 3:          # punish event, buffer cleared (mx_sim never
                punishes += 1    # punished - session-cumulative only)
                vl = 0.0
    m["factor_hits"] = hits
    m["factor_punish"] = punishes

    # ---- AimConstantCheck type 1: expanded gcd of consecutive pitch deltas
    def egcd(a, b):
        a, b = abs(int(round(a * (1 << 24)))), abs(int(round(b * (1 << 24))))
        while b:
            a, b = b, a % b
        return a
    bad = tot = 0
    for i in range(1, n):
        dy_, dp_ = abs(dys[i]), abs(dps[i])
        if not (0.25 < dy_ < 20 and 0.25 < dp_ < 20):
            continue
        tot += 1
        g = egcd(dps[i], dps[i - 1])
        if g < 131072:
            bad += 1
    m["const_qual"] = tot
    m["const_bad"] = bad
    return m


# count metrics normalised per 1000 deltas; ratio metrics separately
MX_COUNT_METRICS = [
    "smooth_run3", "basic_rob", "basic_mac", "basic_con", "basic_agg",
    "basic_gcdx", "pattern_repeat", "pattern_cross", "complex_perfect",
    "complex_similar", "complex_distinct", "inconsistent_trips",
    "factor_hits", "factor_punish",
]
MX_RATIO_METRICS = ["const_bad"]  # over const_qual


def mx_group_rates(files, yaw_key, pitch_key, gcd_default=0.09143358):
    counts = Counter()
    n_total = qual_total = 0
    for f in files:
        sess = f["session"] or {}
        gcd = sess.get("gcd") or gcd_default
        ys, ps = hf.deltas(f["ticks"], yaw_key, pitch_key)
        m = mx_metrics(ys, ps, gcd)
        n_total += m.get("n", 0)
        for k in MX_COUNT_METRICS + MX_RATIO_METRICS + ["const_qual"]:
            counts[k] += m.get(k, 0)
    rates = {k: (counts[k] / n_total * 1000.0) if n_total else float("nan")
             for k in MX_COUNT_METRICS}
    rates["const_bad"] = (counts["const_bad"] / counts["const_qual"]
                          if counts["const_qual"] else float("nan"))
    return rates, counts, n_total


# ------------------------------------------------------------- thresholds

def derive_thresholds(bas):
    h = bas["human"]["pooled"]
    return {
        "lag1_yaw_delta": ("min", h["lag1_yaw_delta"] - LAG1_MARGIN),
        "minjerk_r2": ("min", h["epochs"]["minjerk_r2"] - MINJERK_MARGIN),
        "epoch_rate_per_s": ("range", (h["epochs"]["rate_per_s"] - RATE_MARGIN,
                                       h["epochs"]["rate_per_s"] + RATE_MARGIN)),
        "dwell_frac": ("range", (h["dwell_frac"] - DWELL_MARGIN,
                                 h["dwell_frac"] + DWELL_MARGIN)),
        "end_flip_rate": ("max", max(h["epochs"]["end_flip_rate"] * ENDFLIP_FACTOR,
                                     h["epochs"]["end_flip_rate"] + ENDFLIP_FLOOR)),
        "grid_residual_p99": ("max", GRID_TOL),
        "ks_yaw_abs": ("max", KS_MAX),
        "ks_pitch_abs": ("max", KS_MAX),
        "ks_epoch_dur": ("max", KS_MAX),
    }


def feature_values(group, ks_row):
    h = group["pooled"]
    return {
        "lag1_yaw_delta": h["lag1_yaw_delta"],
        "minjerk_r2": h["epochs"]["minjerk_r2"],
        "epoch_rate_per_s": h["epochs"]["rate_per_s"],
        "dwell_frac": h["dwell_frac"],
        "end_flip_rate": h["epochs"]["end_flip_rate"],
        "grid_residual_p99": h["grid_residual_steps"]["p99"],
        "ks_yaw_abs": ks_row["yaw"],
        "ks_pitch_abs": ks_row["pitch"],
        "ks_epoch_dur": ks_row["epoch"],
    }


def check(name, value, spec):
    if value != value:  # NaN
        return False
    kind, bound = spec
    if kind == "min":
        return value >= bound
    if kind == "max":
        return value <= bound
    if kind == "range":
        return bound[0] <= value <= bound[1]
    raise ValueError(kind)


# ------------------------------------------------------------------- main

def main():
    human_paths = sorted(glob.glob(hf.HUMAN_GLOB))
    shaper_paths = sorted(glob.glob(hf.SHAPER_GLOB))
    if not human_paths or not shaper_paths:
        print("missing recordings - run from the repo root "
              "(need tools/aim-2026-09-22_*.jsonl and tools/aim-2026-09-19_*.jsonl)",
              file=sys.stderr)
        return 2
    if not FEATURES_JSON.exists():
        print(f"missing {FEATURES_JSON} - run python3 tools/human_features.py first",
              file=sys.stderr)
        return 2

    bas = json.loads(FEATURES_JSON.read_text())

    human_files = [hf.load_session(p) for p in human_paths]
    shaper_files = [hf.load_session(p) for p in shaper_paths]

    # ---- partition guard (reuses human_features semantic tests)
    rows, problems = hf.guard(human_files, shaper_files)
    print("=" * 78)
    print("PARTITION GUARD")
    print("=" * 78)
    if problems:
        for p in problems:
            print("  !!", p)
        return 3
    print(f"  OK: {len(human_paths)} manual files / {len(shaper_paths)} shaper files")

    # ---- staleness: live human features must match the fitted JSON
    human = hf.group_features(human_files, "player_yaw", "player_pitch")
    trail = hf.group_features(human_files, "server_yaw", "server_pitch")
    shaper = hf.group_features(shaper_files, "server_yaw", "server_pitch")
    live_lag1 = human["pooled"]["lag1_yaw_delta"]
    json_lag1 = bas["human"]["pooled"]["lag1_yaw_delta"]
    stale = abs(live_lag1 - json_lag1) > 0.02

    # ---- KS distances (0 = identical, 1 = disjoint); human = split-half
    hy, hp = abs_samples(human_files, "player_yaw", "player_pitch")
    ty, tp = abs_samples(human_files, "server_yaw", "server_pitch")
    sy, sp_ = abs_samples(shaper_files, "server_yaw", "server_pitch")
    he = hf._raw_run_samples(human_files, "player")
    te = hf._raw_run_samples(human_files, "server")
    se = hf._raw_run_samples(shaper_files, "server")

    ks = {
        "human": {"yaw": half_split_ks(hy), "pitch": half_split_ks(hp),
                  "epoch": half_split_ks(he)},
        "trail": {"yaw": ks2(hy, ty), "pitch": ks2(hp, tp),
                  "epoch": ks2(he, te)},
        "shaper": {"yaw": ks2(hy, sy), "pitch": ks2(hp, sp_),
                   "epoch": ks2(he, se)},
    }

    # The KS bound must separate human self-distance from candidate distance;
    # derived at runtime (0.15 nominal, snapped below observed candidate KS).
    global KS_MAX
    cand_ks = max(v for g in (ks["trail"], ks["shaper"]) for v in g.values()
                  if v == v)
    human_ks = max(v for v in ks["human"].values() if v == v)
    KS_MAX = max(0.15, round(human_ks + 0.05, 3))
    discriminate = KS_MAX < cand_ks

    thresholds = derive_thresholds(bas)
    groups = {"HUMAN": human, "TRAIL": trail, "SHAPER": shaper}

    # ---- feature-parity table
    values = {g: feature_values(gr, ks[g.lower()]) for g, gr in groups.items()}
    print()
    print("=" * 78)
    print("FEATURE-PARITY CHECKS (thresholds from fitted human baselines)")
    print("=" * 78)
    print(f"  {'check':<22} {'threshold':<26} {'HUMAN':>9} {'TRAIL':>9} {'SHAPER':>9}")
    fails = {g: [] for g in groups}
    for name, spec in thresholds.items():
        kind, bound = spec
        if kind == "range":
            thr = f"[{bound[0]:.3f}, {bound[1]:.3f}]"
        else:
            thr = f"{'>=' if kind == 'min' else '<='} {bound:.3f}"
        row = []
        for g in groups:
            v = values[g][name]
            ok = check(name, v, spec)
            if not ok:
                fails[g].append(name)
            row.append(f"{v:.3f}{'' if ok else ' X'}")
        print(f"  {name:<22} {thr:<26} {row[0]:>9} {row[1]:>9} {row[2]:>9}")

    # ---- MX-rule table
    mx = {
        "HUMAN": mx_group_rates(human_files, "player_yaw", "player_pitch"),
        "TRAIL": mx_group_rates(human_files, "server_yaw", "server_pitch"),
        "SHAPER": mx_group_rates(shaper_files, "server_yaw", "server_pitch"),
    }
    print()
    print("=" * 78)
    print("MX-RULE CHECKS (per-window; rate <= human*1.5 + tol  =>  no tell)")
    print("=" * 78)
    print(f"  {'metric':<22} {'HUMAN':>9} {'TRAIL':>9} {'SHAPER':>9}  verdict")
    mx_queue = []
    for k in MX_COUNT_METRICS:
        hr = mx["HUMAN"][0][k]
        tol = max(hr * MX_TOL_FACTOR, hr + MX_TOL_ABS)
        marks = []
        for g in ("TRAIL", "SHAPER"):
            r = mx[g][0][k]
            bad = r == r and r > tol
            marks.append(f"{g}{' X' if bad else ''}")
            if bad:
                mx_queue.append((k, g, r, hr))
        print(f"  {k:<22} {hr:>9.2f} {mx['TRAIL'][0][k]:>9.2f} "
              f"{mx['SHAPER'][0][k]:>9.2f}  {' '.join(marks)}")
    hr = mx["HUMAN"][0]["const_bad"]
    tol = max(hr * MX_TOL_FACTOR, hr + MX_TOL_ABS_RATIO)
    marks = []
    for g in ("TRAIL", "SHAPER"):
        r = mx[g][0]["const_bad"]
        bad = r == r and r > tol
        marks.append(f"{g}{' X' if bad else ''}")
        if bad:
            mx_queue.append(("const_bad(ratio)", g, r, hr))
    print(f"  {'const_bad(ratio)':<22} {hr:>9.3f} {mx['TRAIL'][0]['const_bad']:>9.3f} "
          f"{mx['SHAPER'][0]['const_bad']:>9.3f}  {' '.join(marks)}")

    # ---- acceptance contract
    print()
    print("=" * 78)
    print("ACCEPTANCE CONTRACT")
    print("=" * 78)
    reasons = []
    if stale:
        reasons.append(f"JSON stale: live human lag1 {live_lag1:.3f} vs "
                       f"json {json_lag1:.3f} - rerun tools/human_features.py")
    if fails["HUMAN"]:
        reasons.append(f"HUMAN fails {fails['HUMAN']} - thresholds unsatisfiable")
    if not fails["TRAIL"]:
        reasons.append("TRAIL passes everything - harness cannot detect "
                       "the current engine (loosen nothing; fix the check)")
    if not fails["SHAPER"]:
        reasons.append("SHAPER passes everything - harness cannot detect "
                       "bot output")
    if not discriminate:
        print(f"  note: KS bound {KS_MAX} does not separate human self-distance "
              f"({human_ks:.3f}) from candidates ({cand_ks:.3f}) - "
              f"KS check unreliable, rely on the rest")

    print(f"  HUMAN : {'PASS' if not fails['HUMAN'] else 'FAIL ' + str(fails['HUMAN'])}")
    print(f"  TRAIL : {'PASS' if not fails['TRAIL'] else 'FAIL ' + str(fails['TRAIL'])}"
          "   <- redo work queue (expected: FAIL)")
    print(f"  SHAPER: {'PASS' if not fails['SHAPER'] else 'FAIL ' + str(fails['SHAPER'])}"
          "   <- expected: FAIL")

    if mx_queue:
        print()
        print("  MX work-queue items (candidate exceeds human tolerance):")
        for k, g, r, h_ in mx_queue:
            print(f"    {g:<7} {k:<22} rate={r:.2f} > human {h_:.2f} + tol")

    if reasons:
        print()
        for r in reasons:
            print("  !!", r)
        print("\nRESULT: FAIL (contract violated)")
        return 1
    print("\nRESULT: OK "
          "(HUMAN passes / TRAIL fails / SHAPER fails - validation is falsifiable)")
    return 0


def candidate_mode(path: str) -> int:
    """Judge one candidate session (headless replay / future engine output).

    Same frozen thresholds and MX tolerances as the group contract; the
    candidate replaces the TRAIL/SHAPER columns with a single CANDIDATE
    column. Exit 0 = passes every feature check (indistinguishable at corpus
    resolution), 1 = fails — the printed failure list is the work queue,
    2 = missing inputs, 3 = partition guard violation.
    """
    if not FEATURES_JSON.exists() or not glob.glob(hf.HUMAN_GLOB) \
            or not glob.glob(hf.SHAPER_GLOB):
        print("missing corpus or fitted JSON - run from the repo root "
              "(tools/human_features.py first)", file=sys.stderr)
        return 2

    bas = json.loads(FEATURES_JSON.read_text())
    human_files = [hf.load_session(p) for p in sorted(glob.glob(hf.HUMAN_GLOB))]
    shaper_files = [hf.load_session(p) for p in sorted(glob.glob(hf.SHAPER_GLOB))]

    _, problems = hf.guard(human_files, shaper_files)
    if problems:
        for p in problems:
            print("  !!", p)
        return 3

    # Session-segmented: the human group is judged per file, so candidate
    # deltas must not cross concatenated recording seams either.
    cands = hf.load_candidate(path)
    n_ticks = sum(len(s["ticks"]) for s in cands)
    if n_ticks < 200:
        print(f"candidate has {n_ticks} ticks (< 200) - not judgeable",
              file=sys.stderr)
        return 2

    print("=" * 78)
    print(f"CANDIDATE: {Path(path).name} ({n_ticks} ticks, "
          f"{len(cands)} sessions)")
    print("=" * 78)

    # Staleness: thresholds are only meaningful against a fresh fitted JSON.
    human = hf.group_features(human_files, "player_yaw", "player_pitch")
    live_lag1 = human["pooled"]["lag1_yaw_delta"]
    json_lag1 = bas["human"]["pooled"]["lag1_yaw_delta"]
    stale = abs(live_lag1 - json_lag1) > 0.02

    group = hf.group_features(cands, "server_yaw", "server_pitch")

    hy, hp = abs_samples(human_files, "player_yaw", "player_pitch")
    cy, cp = abs_samples(cands, "server_yaw", "server_pitch")
    he = hf._raw_run_samples(human_files, "player")
    ce = hf._raw_run_samples(cands, "server")

    ks_row = {"yaw": ks2(hy, cy), "pitch": ks2(hp, cp), "epoch": ks2(he, ce)}
    human_self = {"yaw": half_split_ks(hy), "pitch": half_split_ks(hp),
                  "epoch": half_split_ks(he)}
    global KS_MAX
    human_ks = max(v for v in human_self.values() if v == v)
    KS_MAX = max(0.15, round(human_ks + 0.05, 3))

    thresholds = derive_thresholds(bas)
    values = feature_values(group, ks_row)

    print()
    print(f"  {'check':<22} {'threshold':<26} {'CANDIDATE':>12}")
    fails = []
    for name, spec in thresholds.items():
        kind, bound = spec
        if kind == "range":
            thr = f"[{bound[0]:.3f}, {bound[1]:.3f}]"
        else:
            thr = f"{'>=' if kind == 'min' else '<='} {bound:.3f}"
        v = values[name]
        ok = check(name, v, spec)
        if not ok:
            fails.append(name)
        print(f"  {name:<22} {thr:<26} {v:>12.3f}{'' if ok else ' X'}")

    # MX rules: informational (relative rule vs human rates), same as groups.
    human_mx = mx_group_rates(human_files, "player_yaw", "player_pitch")[0]
    cand_mx, _, _ = mx_group_rates(cands, "server_yaw", "server_pitch")
    print()
    print(f"  {'mx metric':<22} {'HUMAN':>9} {'CANDIDATE':>12}  verdict")
    mx_queue = []
    for k in MX_COUNT_METRICS:
        hr = human_mx[k]
        tol = max(hr * MX_TOL_FACTOR, hr + MX_TOL_ABS)
        r = cand_mx[k]
        bad = r == r and r > tol
        if bad:
            mx_queue.append((k, r, hr))
        print(f"  {k:<22} {hr:>9.2f} {r:>12.2f}  {'CAND X' if bad else 'ok'}")
    hr = human_mx["const_bad"]
    tol = max(hr * MX_TOL_FACTOR, hr + MX_TOL_ABS_RATIO)
    r = cand_mx["const_bad"]
    bad = r == r and r > tol
    if bad:
        mx_queue.append(("const_bad(ratio)", r, hr))
    print(f"  {'const_bad(ratio)':<22} {hr:>9.3f} {r:>12.3f}  {'CAND X' if bad else 'ok'}")

    print()
    if stale:
        print("  !! fitted JSON stale vs live corpus - rerun tools/human_features.py")
    if mx_queue:
        print("  MX tells (informational):")
        for k, r, h_ in mx_queue:
            print(f"    {k:<22} rate={r:.2f} > human {h_:.2f} + tol")
    if fails or stale:
        print(f"\nRESULT: FAIL {fails or ['stale_json']} - redo work queue")
        return 1
    print("\nRESULT: PASS - candidate within human thresholds at corpus resolution")
    return 0


if __name__ == "__main__":
    argv = sys.argv[1:]
    if argv and argv[0] == "--candidate":
        if len(argv) < 2:
            print("--candidate requires a path", file=sys.stderr)
            sys.exit(2)
        sys.exit(candidate_mode(argv[1]))
    sys.exit(main())
