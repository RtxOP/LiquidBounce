# Human Aim Corpus — Findings

**Date:** 2026-09-23
**Scope:** feature analysis of the recorded aim sessions, establishing the human
baseline for the MX-Project anticheat bypass redesign (6-stage pipeline).
**Method constraint:** feature-distribution / KS comparison only — **no adversarial
proxy classifier is trained**, per project decision.

Reproduce everything below with:

```bash
python3 tools/human_features.py   # exit 0 = OK, 2 = missing recordings, 3 = partition violation
```

Machine-readable output: [`human_corpus_features.json`](human_corpus_features.json).

---

## 1. Corpus partition

| Group | Files | Schema | Signal used | Role |
|---|---|---|---|---|
| **HUMAN** | 7 × `aim-2026-09-22_*.jsonl` (4,182 ticks) | 8 | `player_yaw` / `player_pitch` (raw mouse) | human baseline corpus |
| **SHAPER** | 2 × `aim-2026-09-19_*.jsonl` (2,543 ticks) | 7 | `server_yaw` / `server_pitch` | reference for what the current engine emits while aiming at entities |

- The 2026-09-19 sessions were recorded **using KillAura** (file 1: 1,602 KillAura
  + 388 Aimbot ticks; file 2: 553 KillAura ticks). Fresh targets on **100 %** of
  KillAura ticks (1,602/1,602 and 553/553). Excluded from every human statistic.
- The 2026-09-22 sessions are the **manual** corpus. All seven files carry that
  date; all are schema 8 and treated as manual.

The split is hardcoded in globs (`HUMAN_GLOB = aim-2026-09-22_*`,
`SHAPER_GLOB = aim-2026-09-19_*`) and enforced by assertions — the tool exits 3
if either side stops matching its expected identity.

### Ground-truth checks (manual files)

| Check | Result | What it proves |
|---|---|---|
| `target_yaw` non-null | **0 / 4,182 ticks** | no live rotation target existed during recording |
| Player stream on GCD grid | residual p99 ≤ **0.0006 steps**, ≥99.9 % within 0.02 | raw vanilla mouse input (integer gcd counts) |
| Player motion fraction | 0.51 – 0.90 | a human was actually moving the mouse |
| Click evidence (`ticks_since_attack` ≤ 15) | 0.42 – 0.79 of ticks | active manual fights |

### Why every manual tick is labeled `source: KillAura`

Label artifact, not KillAura aiming: after a pre-recording KillAura test,
`RotationUtils.activeSettings` was never cleared (release branch,
`resetTicks == 0`), so `limitAngleChange` kept stamping `lastShapedTick` every
tick → `isShapingNow()` true → `source = KillAura`. The recorder still requires
`hasFreshTarget(2)` for the aim point, hence `target_yaw = null` everywhere.
Ground truth for "was KillAura aiming" is therefore **target freshness**, not the
`source` label. `player_*` (client view) is untouched while `applyServerSide=true`,
so the manual signal is raw mouse regardless.

---

## 2. Trail forensics — release-branch lag vs entity aiming

Two files (`19-26-42`, `19-27-09`) initially tripped a naive guard
(server deviates >15° from player on 6–9 % of ticks). Resolved: **the lag is
player-caused**, the shaper is a trailing follower, never an independent aimer.

- Worst episodes: player flicks at **40–62°/tick**, trail grows to 150°+, then
  the player stops and the server converges **back onto the player**
  (trail → ~0.8°, the deadband residual) within **7–11 ticks**.
- Guard test 1 — every >15° episode must be explained by player motion:
  **98 episodes, 0 unexplained**, min buildup ratio 1.01 (motion ≥ growth).
- Guard test 2 — every idle run ≥12 ticks must end converged on the player:
  **12 runs, 0 unconverged**, max end-trail 1.32° (< 2.0 threshold).

A server aiming at an entity parks away from the crosshair while the player
rests and shows fresh targets; neither occurs.

---

## 3. Feature comparison (pooled, 20 Hz)

| Feature | HUMAN | TRAIL (current engine on human input) | SHAPER (Sep 19) |
|---|---|---|---|
| speed p50 / p90 / p99 °/tick | 1.32 / 5.23 / 17.47 | 0.66 / 9.04 / 20.76 | 0.70 / 13.97 / 24.95 |
| **lag-1 autocorr (yaw)** | **+0.800** | +0.475 | +0.652 |
| lag-1 / lag-2 / lag-5 (yaw) | 0.800 / 0.544 / 0.149 | — | — |
| lag-1 (pitch) | +0.716 | — | — |
| dwell fraction | 0.206 | 0.193 | 0.302 |
| epochs per second · duration p50 | 1.00 · 9 ticks | 2.01 · 5 ticks | 1.45 · 4 ticks |
| dual-axis fraction (any nonzero) | 0.69 | 0.70 | 0.54 |
| dual-axis fraction @ MX 0.25° | 0.496 | 0.388 | 0.431 |
| **min-jerk R²** | **0.80** | **0.27** | 0.81 |
| end-flip (overshoot) rate | 0.024 | **0.129** | — |

KS distances (0 = identical, 1 = disjoint):

| Comparison | yaw abs | pitch abs | epoch dur | win-10 std |
|---|---|---|---|---|
| human vs shaper | 0.249 | 0.271 | 0.315 | 0.347 |
| human vs trail | 0.249 | — | — | — |

KS ≈ 0.25–0.35 confirms the original diagnosis: bot output is **distinguishable
but not disjoint** from human play — the bypass target is closing this gap by
matching the server's legit feature distribution.

---

## 4. Fitted parameters (for the redo)

### 4.1 Noise stage — colored, σ ≈ 0.19°/0.22°

Method: slow segments (speed < 1.5°/tick), 9-tick local linear detrend,
532 windows — removes the plan, leaves tremor + micro-correction noise.

| Parameter | Value |
|---|---|
| position residual RMS yaw / pitch | **0.188° / 0.216°** |
| residual lag-1 yaw / pitch | +0.466 / +0.540 → smooth **colored** noise, not white |
| slow-regime speed p50 / p90 | 0.204° / 1.192° per tick |
| slow-regime zero fraction | 0.386 |

**Dead ends — do not retry:** raw-spectrum exponents
(β_position −3.50, β_velocity −1.67, peak 0.43 Hz) are *movement*-dominated, not
noise; per-epoch min-jerk position residual (~24°) is trajectory *curvature*
(per-axis min-jerk forces a straight 2D line); 10-tick moving-average wobble
(17°) is lag-dominated during fast movement. None of these is a noise sigma.

### 4.2 Event scheduler (dwell/hold)

| Parameter | Value |
|---|---|
| dwell fraction of ticks | 0.206 |
| dwell duration mean / p90 / max | 4.0 / 7 / 179 ticks |
| dwell transitions | ~1.03 per second |

### 4.3 Motor plan (σ-lognormal / min-jerk stage)

| Parameter | Value |
|---|---|
| epochs | 208 total, **1.00 per second** |
| duration p50 / p90 / max | 9 / 38 / 100 ticks |
| net amplitude p10 / p50 / p90 / max | 0.74° / 9.12° / 61.8° / 213° |
| peak speed p50 / p90 | 4.18° / 12.90° per tick |
| time-to-peak τ | **0.425** (mean; p50 0.429) |
| Fitts: peak ≈ slope · log₂(amplitude) | slope 3.32, r 0.65 |
| duration ∝ amplitude^b | b **0.50**, r 0.74 |
| yaw-dominant epochs | 0.50 |
| mean speed profile (8 bins) | [2.47, 3.08, 3.71, 3.67, 4.11, 3.96, 3.30, 1.56] — bell-shaped |
| min-jerk R² of profile | **0.80** — the plan stage is justified |

### 4.4 Corrections / GCD quantizer

| Parameter | Value |
|---|---|
| gcd steps abs p10 / p50 / p90 / p99 / max | 2 / 12 / 61 / 213 / 682 |
| micro-correction fraction (≤1.5 gcd of nonzero) | 0.055 |
| reversals per epoch mean / p50 / p90 | 1.08 / 0 / 3 |
| end-flip rate | 0.024 |
| grid residual p99 | **0.00057 steps** (quantizer target ≈ exact) |

---

## 5. Deferred defect: stop-and-go tracking, now quantified

User-reported symptom (stationary target + strafe → holds, then jumps),
deliberately deferred pending the architecture redo. Measured on real human
input (TRAIL vs HUMAN):

| Metric | HUMAN | TRAIL | Damage |
|---|---|---|---|
| epochs/s | 1.00 | 2.01 | movements fragmented 2× |
| epoch duration p50 | 9 ticks | 5 ticks | halved |
| min-jerk R² | 0.80 | 0.27 | profile destroyed |
| end-flip rate | 0.024 | 0.129 | 5.4× overshoot tail |
| lag-1 autocorr | 0.800 | 0.475 | momentum lost |

Root-cause hypothesis for the redo: hold-interval modeling — the conditioner's
pursuit deadband + gain interaction turns slow tracking into stick-slip
(error must accumulate past the deadband before the output moves). The servo +
event-scheduler stages replace this behavior; `servo_hints` in the JSON is the
regression baseline.

---

## 6. Implications for the 6-stage pipeline

| Stage | Corpus says |
|---|---|
| reference conditioner | must not add lag beyond human lag-1 0.80 (TRAIL already drops it to 0.475) |
| motor plan | min-jerk/σ-lognormal justified: R² 0.80, τ 0.425, b 0.50 |
| jerk-limited servo | target continuous tracking — kill the 2× epoch fragmentation |
| colored noise | single stage, σ 0.19°/0.22°, lag-1 ≈ +0.5 (pink-ish), applied on top of plan |
| event scheduler | dwell 20.6 %, mean 4 ticks, p90 7, ~1 transition/s |
| GCD quantizer | keep; humans are exact (p99 0.00057 steps) |

**Next steps:** ~~port `mx_sim.py` into a feature-parity + MX-rule CI harness~~
**done — `tools/aim_harness.py` (§10 baseline)**; remaining: wire it into CI
and fit the noise/dwell parameters live against the harness.

---

## 7. Artifacts

| File | Contents |
|---|---|
| `tools/human_features.py` | analysis + partition guard (stdlib only) |
| `tools/human_corpus_features.json` | full per-file/pooled features + `fitted` block |
| `tools/aim-2026-09-22_*.jsonl` | manual corpus (raw mouse) |
| `tools/aim-2026-09-19_*.jsonl` | KillAura/Aimbot reference (excluded from human stats) |

---

## 8. Report: what to do next

Reconciles the original session investigation (diagnosis + 6-stage proposal +
migration steps, from the opencode session file) with everything learned since.

### 8.1 Scorecard — original plan vs. today

| Original plan step | Status | Evidence |
|---|---|---|
| 1. Fix the recorder's three bugs | ✅ Done | schema 7→8; stale target / `aimbot_engaged` / `rotationActive` dilution fixed |
| 2. Record a manual-play human corpus | ✅ Done | 7 × Sep-22 files, 4,182 ticks; partition guard green |
| 3. Land the feature-parity + MX-rule harness and watch it fail | ✅ Done (2026-09-23) | `tools/aim_harness.py`; contract exit 0 — HUMAN passes 9/9, TRAIL fails 4, SHAPER fails 3 (§10) |
| 4. Add reference conditioner + jerk limit (the two loudest tells) | ⚠️ Partial | Jitter fix landed (EMA α=0.35, persistence gate, pursuit gain 0.5) — but it introduced the deferred stop-and-go defect |
| 5. Replace noise with the single fitted colored process | ⚠️ Fit, not wired | σ 0.19°/0.22°, lag-1 +0.47/+0.54 exist in JSON; engine untouched (redo pending) |
| 6. Merge Aimbot/KillAura into one path | ❌ Not done | dual path still in `Aimbot.kt` |
| 7. σ-lognormal swap only if parity still missed | Likely **unnecessary** | corpus: min-jerk R² = 0.80, bell profile confirmed — the plan shape was never the problem |

The pattern: steps 1–2 (data trust) are done, the fitted parameters exist, but
**step 3 — the thing the original report called the work-queue generator — was
never executed.** The original diagnosis was "validation was circular"; a full
architecture redo without a harness that can *fail* repeats the same sin.

### 8.2 What changed since that report

- **Corpus exists and overturned an assumption — re-baselined ✅
  (2026-09-23):** the old report treated high dual-axis motion as a tell
  (44 % vs "humans are axis-dominant"). Measured on every basis, humans are
  the *more* dual-axis group: any-nonzero 0.685 vs 0.538, >gcd 0.624 vs
  0.489, and at MX's actual 0.25° threshold **0.496 (human) vs 0.388 (trail)
  vs 0.431 (shaper)** — same ordering at all three thresholds. Per-file
  spread: human 0.323–0.592 *contains* both candidates → dual-axis is *not a
  usable discriminator* at this corpus size (same heterogeneity limit as KS,
  §10.2). Drop it as a tell: the engine sits at or below the human pooled
  rate, so any excess-dual-axis heuristic already passes; only a *deficit*-
  side heuristic could care, and within human session variation it can't.
- **Reaction-delay tell is unfittable from this corpus** — manual ticks have
  `target_yaw = null`, no engagement onsets. Reaction (log-normal ~190 ms) and
  attack lead need recorder v2 additions first.
- **Stop-and-go is now quantified**: epoch rate 2.01/s vs 1.00, duration 5 vs
  9 ticks, min-jerk R² 0.27 vs 0.80, end-flip 0.129 vs 0.024, lag-1 0.475 vs
  0.800. It is the redo's acceptance test case #1.
- **A real bug found — ✅ fixed 2026-09-23:** the release branch never
  reset. Verified: all **6,337** recorded settings snapshots store
  `angle_reset_difference: 0.0` (runtime config; the code default
  `5f.withGCD()` ≈ 5.03° at sens 0.5 never applied), so any nonzero gap
  failed the gate — and the deadband floor (~0.9–1.3°) means the gap is never
  0. The monotonic-convergence condition
  (`diffs <= lastTickDiffs` on both axes) independently can't pass while the
  floor dominates the spoof's own 2-tick displacement (~0.1–0.3°). Effect:
  `source: KillAura` linger on every manual tick *and* indefinite post-fight
  spoofing — itself a behavioral tell. **Landed in `RotationUtils`:**
  tolerance floored at 2.0° (covers stored-0 configs), settle window of 6
  ticks within tolerance (bypasses the monotonic check), hard timeout 400
  ticks / 20 s gated at gap ≤ 10° (handoff jump stays below human per-tick
  p99 ≈ 17.5°), counters cleared on engagement and reset. Labels self-correct:
  `resetRotation()` nulls `activeSettings`, so `lastShapedTick` goes stale and
  `source: manual` resumes.
- **Constraints locked:** no adversarial proxy classifier (feature-KS only);
  architecture will be completely redone.

### 8.3 Recommended next steps (ordered)

**Phase 0 — Measurement infrastructure first (highest value, cheap)**

1. **Port `mx_sim.py` into a CI test harness** (feature-parity + MX rules)
   with thresholds read from `human_corpus_features.json`: lag-1 ≥ ~0.65,
   min-jerk R² ≥ 0.7, epoch rate 0.8–1.2/s, dwell frac 0.15–0.25, end-flip
   ≤ 0.05, grid residual ≤ 0.02, KS below a chosen bound. **Prove it fails on
   the current TRAIL output** — that failure list *is* the redo's work queue.
   **✅ Landed 2026-09-23: `tools/aim_harness.py` (exit 0).** HUMAN 9/9 pass;
   TRAIL fails 4 checks, SHAPER fails 3 (§10). `mx_sim.py` recovered from the
   opencode session db (tmp was wiped) and re-ported with per-window counters.

**Phase 1 — Data trust & corpus v2 (small code, unblocks everything)**

2. **Fix the release-linger reset** (verify `angleResetDifference`, reset on
   convergence) and the label semantics — otherwise every future recording
   ships wrong labels and the post-fight spoof persists.
   **✅ Landed 2026-09-23 (`RotationUtils.kt`):** tolerance floor 2.0° +
   settle window 6 ticks + timeout 400 ticks (gap ≤ 10°); labels self-correct
   via `resetRotation()`'s `activeSettings = null`. Compile OK, harness
   exit 0, mirror 84/84. See §8.2 for the verified root cause.
3. **Recorder v2 engagement markers**: log the crosshair-raycast entity even
   in manual mode + server/sens/ping metadata — required to fit reaction time
   and attack lead later.
   **✅ Landed 2026-09-23 (`RotationRecorder.kt`), schema 9 (additive):**
   per-tick `crosshair_entity_id` / `crosshair_entity_name` (raycast at
   vanilla reach 4.5 on the *player* view, unconditioned on engagement —
   null→id transitions are engagement onsets) + session `server_ip` /
   `ping_ms`. Schema stays 9-compatible but the corpus globs/guard are
   date-frozen on schemas 8/7 — update both together when corpus v2 arrives.
4. **Re-baseline dual-axis** at MX's 0.25° threshold from the existing JSON
   before treating it as a defect.
   **✅ Landed 2026-09-23:** `both_gt_025_frac` added to
   `human_features.py` → regenerated JSON; pooled human 0.496 / trail 0.388 /
   shaper 0.431, human per-file 0.323–0.592 contains both candidates → *not a
   tell*, dropped from redo design concerns (§8.2).

**Phase 2 — Redo spec**

5. **Write the redo spec**: freeze the 6 stage contracts + ~4 knobs, with the
   fitted JSON values as named inputs. **Decide the testing strategy now**:
   kill the Python mirror-port pattern (84 checks duplicated in
   `human_aim_test.py`) — test the Kotlin directly (JUnit) or generate
   expectations from shared JSON fixtures, so the mirror isn't ported a third
   time.
   **✅ Spikes green 2026-09-23 (strategy locked):**
   - **Spike A:** JUnit 5.10.1 wired into `build.gradle` (`useJUnitPlatform`,
     launcher 1.10.1 pinned to override Gradle 6.9's bundled 1.7.0; jupiter
     stack was already in the Gradle cache, network confirmed).
   - **Spike B:** `HeadlessReplaySpikeTest` (src/test) drives the MC-free
     `HumanAimPath` over all 7 Sep-22 files — release-branch semantics (shaper
     chases the player stream), RotationSettings default ranges 10..20/8..15,
     one seeded `Random` per file → byte-deterministic — and emits
     `build/replay-spike/replay_candidate.jsonl` (schema 9).
     `python3 tools/aim_harness.py --candidate <file>` judges it against the
     same frozen thresholds; skips cleanly (Assumptions) when the untracked
     corpus is absent so plain `gradle build` stays green elsewhere.
   - **Result:** candidate fails exactly the recorded TRAIL signature —
     lag-1 0.231, min-jerk 0.310, epoch rate 2.607/s, end-flip 0.176 (+epoch-dur
     KS 0.388), grid 0.000 exact — **exit 1 with the work queue**. Recorded
     corpus and fresh replay are independent paths detecting the same defect;
     the Phase-3 gate is proven end-to-end. (Replay numbers are harsher than
     recorded trail's — defaults vs. live KillAura settings — same failure
     set.)
   - **Locked:** JUnit for seeded stage-contract units + headless replay →
     `--candidate` for distributional acceptance; Python mirror **deleted at
     Phase-3 end, never ported**. Fitted values reach the engine as a
     **generated `FittedAimParams.kt`** (build-time from the JSON, no runtime
     IO); the old ~35 humanize properties become **deprecated no-ops** when
     the engine lands (existing configs keep loading).
   - **✅ Spec written: §11 (2026-09-23) — Phase 2 complete.** Frozen: stage
     contracts + invariants I1/I2, the 4-knob budget, fitted-input manifest
     (+ generator/sync-test contract), settings migration, §9 unification
     contract, spike-proven test strategy, and the acceptance appendix
     (spike exit-1 baseline = Phase-3 work queue).

**Phase 3 — Execute the redo against the harness**

6. Order: conditioner + jerk-limited servo (fixes stop-and-go → must flip test
   case #1 to green) → colored noise (**gap: σ only fitted on slow segments —
   fit σ(v) across speed bins first**) → event scheduler (dwell 20.6 %, mean 4,
   p90 7, ~1 transition/s) → merge the Aimbot path → keep the GCD quantizer
   (humans are exact, p99 0.00057 steps). Defer σ-lognormal — R² 0.80 says
   min-jerk is fine.

### 8.4 Explicit non-goals

- No adversarial proxy classifier — feature-KS + MX-rule harness only.
- No tuning the old engine's ~35 constants; no fixing stop-and-go in the old
  engine (it becomes the redo's acceptance test, not a patch target).
- No schema churn beyond corpus-v2 needs.

**Honest limit:** the corpus is ~4.2k ticks / ~3.5 min across 7 sessions, and
MX's boundary is its server-side labeled data — before any final bypass claims,
refresh the corpus on the actual target server at the sensitivity you'll play
with.

**Immediate next action:** Phase 0 item 1 — port the harness. It is the only
piece that makes every later decision falsifiable instead of circular again.

---

## 9. Unification analysis: Aimbot vs KillAura (plan step 6)

Question: *will Aimbot just be KillAura with `applyServerSide` off?*

**Answer: correct at the rotation-pipeline level, but today Aimbot is a
parallel reimplementation — unification is not a flag flip.**

### 9.1 The flag intuition is right

In the shared path, `applyServerSide = false` does exactly what Aimbot does
manually:

- **KillAura** (`applyServerSide = true`): `setTargetRotation` → `update()` →
  writes `currentRotation` (spoofed server stream; client camera stays at raw
  mouse = silent aim). Two streams.
- **`applyServerSide = false`**: `update()` (RotationUtils.kt lines 710–711) →
  `rotation.toPlayer(player)` — writes the shaped rotation straight into the
  client view. One stream: what you see is what vanilla packets send. That is
  Aimbot.kt lines 302/325 behavior, hand-rolled.

So Aimbot *behaves* like "KillAura with applyServerSide off" — visible aim
assist instead of silent spoof.

### 9.2 The delta is bigger than the flag today

| | KillAura | Aimbot (today) |
|---|---|---|
| Entry point | `setTargetRotation` → `activeSettings` → shared `update()` | never touches it — calls `HumanAimPath.step` **directly** (line 289) |
| State | shared `aimPaths` per settings | **private second `HumanAimPath.State`** (`aimState`, line 98) |
| Destination | `searchCenter` with `outborder` tied to post-click window (the zigzag source) | `searchCenter(outborder = false)` always, or pure box `center`; own body-point range |
| Prediction | KillAura's settings | own: enemy extrapolation ×(2 + `predictEnemyPosition`) + `SimulatedPlayer` self-sim + `setPosAndPrevPos` restore hack |
| Humanize knobs | `RotationSettings` humanize fields | **duplicate set** (tremor/overshoot/curvature/reaction, lines 42–46) with its own defaults |
| Non-humanize fallback | shared `performAngleChange` via settings | duplicated inline (line 317) with gaussian turn speed |
| Target/attack | priority/switch/CPS/attack machinery | nearest-in-FOV, **never attacks**, `lock`/`onClick`/`breakBlocks` gating |
| Instrumentation | `lastShapedTick`/`isShapingNow` on RotationUtils | parallel stamps on the module — why the recorder needs the `aimbot_engaged`/`lastAimTarget` special cases |

### 9.3 What "unify" should mean

Aimbot keeps what is legitimately its own — **target policy** (nearest visible,
no attack, lock/onClick gating) and **destination policy** (center/no-outborder,
its prediction) — then hands the destination to the shared pipeline:

```kotlin
setTargetRotation(destination, aimbotSettings)   // aimbotSettings: applyServerSide = false
```

Delete: the private `aimState`, the duplicated humanize knobs (map them onto
the shared `RotationSettings` humanize fields), the inline `performAngleChange`
fallback, and the parallel stamps.

Consequences:

- The redo's conditioner → plan → servo → noise → quantizer applies to Aimbot
  automatically, with one state per settings instead of two.
- The recorder's Aimbot special-case (`rotationSnapshot()` fallback,
  `lastAimTarget` for aim-error) collapses into the shared stamps — the
  `aimbot_engaged` bug class disappears.
- Aimbot's direct-write path never touches `currentRotation`/`activeSettings`,
  so it never tripped the release-linger bug — but the unified path must
  **own release/reset correctly** (fix `angleResetDifference`, §8.3 item 2),
  or `applyServerSide = false` inherits the same never-reset state machine.

One observable behavior change to test: after unification,
`applyServerSide = false` goes through `setTargetRotation`'s release-sync
(lines 600–607: write pending `currentRotation` back into the view, then
`resetRotation()`); today's Aimbot skips that entirely, so toggling modules
mid-fight could snap differently. Write the test for module-toggle handoff.

---

## 10. Harness baseline — first run (2026-09-23)

`python3 tools/aim_harness.py` → **exit 0** (HUMAN passes / TRAIL fails /
SHAPER fails). Exit codes: 0 contract met, 1 contract violated, 2 missing
inputs, 3 partition guard violation. Thresholds are derived from
`human_corpus_features.json` with explicit margins in the script.

### 10.1 Feature-parity (gating)

| check | threshold | HUMAN | TRAIL | SHAPER |
|---|---|---|---|---|
| lag-1 yaw delta | ≥ 0.650 | 0.800 ✅ | **0.475 ❌** | 0.652 ✅ |
| min-jerk R² | ≥ 0.700 | 0.800 ✅ | **0.273 ❌** | 0.814 ✅ |
| epoch rate /s | [0.796, 1.196] | 0.996 ✅ | **2.007 ❌** | **1.448 ❌** |
| dwell fraction | [0.156, 0.256] | 0.206 ✅ | 0.193 ✅ | **0.302 ❌** |
| end-flip rate | ≤ 0.048 | 0.024 ✅ | **0.129 ❌** | **0.543 ❌** |
| grid residual p99 (steps) | ≤ 0.020 | 0.001 ✅ | 0.002 ✅ | 0.001 ✅ |
| KS yaw/pitch/epoch | ≤ 0.378 (auto) | 0.321/0.328/0.212 | 0.249/0.271/0.246 | 0.249/0.271/0.315 |

**Redo work queue (TRAIL ❌):** lag-1, min-jerk R², epoch rate, end-flip.
**SHAPER adds:** dwell fraction + worst end-flip (0.543).

### 10.2 KS caveat (revises §3 interpretation)

The KS bound is auto-derived as *human split-half self-distance + 0.05*.
Observed human self-distance is **0.321–0.328** — the seven sessions are
internally more heterogeneous than the candidate distances (0.249). At this
corpus size (≈4.2k ticks) **KS-vs-pooled-human cannot discriminate**; the
scalar features above carry the discrimination. A tighter absolute KS bound
(0.15) requires corpus v2 (more sessions, target server). Until then the KS
rows print a non-discrimination note and must not be read as "candidates are
human-like" — only that the corpus is too wide to judge by KS alone.

### 10.3 MX-rule checks (informational; rate per 1000 deltas, tol = human×1.5+0.5)

| metric | HUMAN | TRAIL | SHAPER | verdict |
|---|---|---|---|---|
| smooth_run3 (AimSmooth) | 0.24 | 0.00 | **2.36** | SHAPER ❌ |
| pattern_repeat (AimPattern, exact pairs) | 0.24 | **5.75** | 0.00 | TRAIL ❌ |
| pattern_cross (cross-axis identity) | 175.09 | 127.19 | **293.19** | SHAPER ❌ |
| complex_similar (AimComplex entropy) | 1.44 | 1.92 | **2.36** | SHAPER ❌ |
| basic_* (AimBasic, per-window) | ≈0 | ≈0 | ≈0 | no trips |
| factor/inconsistent/complex_distinct | 0 | 0 | 0 | no trips |
| const_bad ratio (AimConstant) | 0.860 | 0.715 | 0.904 | within tol |

Findings:

- **New engine tell:** TRAIL repeats exact (Δyaw, Δpitch) pairs 5.75×/1000
  over the human rate — the settle/snap path emits identical delta pairs
  (AimPatternCheck would flag today's engine even before the redo).
- SHAPER trips AimSmooth ~10× human and cross-axis identity ~1.7× human.
- AimBasic never trips per-window on any group — confirms the earlier
  session-level counts were artifacts of non-resetting cumulative counters
  (the bug this port fixes).
- `const_bad` ratio is dominated by the port's scaling (human 0.86): only the
  relative rule (candidate ≤ human×1.5+0.01) is meaningful here.

---

## 11. Phase-2 redo spec — frozen design (2026-09-23)

Phase-2 deliverable (§8.3 item 5). The six stage contracts, the four-knob
budget, the fitted-input manifest, the settings migration and the test
strategy are frozen here. **No engine code in Phase 2** — §8.4 non-goals
apply (no proxy classifier, no tuning old-engine constants, σ-lognormal
deferred: min-jerk R² 0.80 is good enough). Spikes A/B (§8.3 item 5) proved
the test loop before any of this prose existed.

### 11.1 Placement and adapter contract

- Package `net.ccbluex.liquidbounce.utils.rotation.aim`: `AimEngine.kt`
  (driver), `ReferenceConditioner.kt`, `MotorPlan.kt`, `Servo.kt`,
  `ColoredNoise.kt`, `EventScheduler.kt`, `FittedAimParams.kt` (generated,
  §11.4). Stage 6 reuses the existing absolute-grid function
  (`getFixedSensitivityAngle(target, 0f, gcd)`) — one implementation, not a
  copy.
- **Purity rule:** no `net.minecraft.*`, no `MinecraftInstance`, no
  Module/Configurable types inside the package — rotation values in,
  rotation value out. Enforced by an architecture unit test that scans the
  package imports (spike B already proved `Rotation` classloads headless).
- Signature shape:

  ```kotlin
  object AimEngine {
      fun step(input: Input, state: State): Rotation

      data class Input(
          val current: Rotation,   // last emitted rotation (branch + grid anchor)
          val reference: Rotation, // destination from the targeting layer
          val engaged: Boolean,    // fresh-target edge, from the adapter
          val tick: Int,           // absolute tick; gap > 1 resets state
          val gcd: Float,          // runtime sensitivity gcd — NOT the fitted constant
          val seed: Long,          // determinism root: every stochastic draw derives here
      )
  }
  ```
- The adapter (in `RotationUtils`, where it already lives) owns:
  engage/release edges, `applyServerSide` routing (spoof `currentRotation`
  vs `view.toPlayer`), the Phase-1 release-linger logic exactly as landed
  (floor 2.0° / settle 6 ticks / timeout 400 @ gap ≤ 10°), and stage-6 grid
  application. The engine never learns which flag is set.
- One `State` per `RotationSettings` (the current `aimPaths` pattern).
  Reset triggers: engage edge, tick discontinuity, module toggle — the §9
  handoff test asserts exactly this.

### 11.2 Stage contracts

| # | Stage | In → out | Fitted inputs (JSON keys) | Knob | Gate |
|---|---|---|---|---|---|
| 1 | Reference conditioner | raw destination + engage edge → conditioned reference | `fitted.correction.micro_frac_nonzero` (correction floor = 1 gcd) | ReactionDelayMs | structural (I1 below) |
| 2 | Motor plan | error ≥ 1 gcd → epoch {T ticks, profile} | `fitted.epochs.duration_vs_amplitude_exponent.slope` (0.502), `fitted.epochs.profile_8bin`, `fitted.epochs.fitts_peak_vs_log2_amp.slope` (3.316) | SpeedScale | min-jerk R² ≥ 0.700; epoch-KS ≤ bound |
| 3 | Servo (jerk-limited) | plan + per-tick reference → position command | profile bin-to-bin slopes (jerk limits derived from the active plan — no free constants); `human.pooled.speed.*`, `human.pooled.epochs.peak_speed.p90` (sanity caps); `fitted.mc.windowed_std.*.p90` (smoothness target) | SpeedScale | **acceptance test #1: lag-1 ≥ 0.650, epoch rate ∈ [0.796, 1.196], end-flip ≤ 0.048** |
| 4 | Colored noise | command → +AR(1) per axis, gated by stage 5 | `fitted.noise.floor.{position_resid_rms_*, residual_lag1_*}` | NoiseScale | KS yaw/pitch ≤ bound |
| 5 | Event scheduler | motion intent → gate (motion vs exact hold) | `fitted.dwell.{mean_ticks, p90_ticks, transitions_per_s}` (geometric p ≈ 1/4), `fitted.epochs.rate_per_s` (0.996) | EventRateScale | dwell ∈ [0.156, 0.256] |
| 6 | GCD quantizer | gated command → absolute-grid snap | `human.pooled.grid_residual_steps.p99` (0.00057 target, ≤ 0.020 gate) | — | grid p99 ≤ 0.020 |

**Structural invariants (the stop-and-go fix, stated as testable rules):**

- **I1 — no threshold gates.** The old 2.0° release floor, 6-tick settle,
  400-tick timeout and monotonic check die with `HumanAimPath`. The
  conditioner's only threshold is **error ≥ 1 gcd step** — the quantization
  floor (below it the grid cannot act, so it is not a gate but physics).
  Reference motion at any speed above ~0.09°/tick therefore produces motion
  within 1–2 ticks, always.
- **I2 — dwells are preemptible.** The scheduler may hold output only while
  the conditioned reference stays within 1 gcd of the current output (either
  axis). Any accumulated error ≥ 1 gcd during a hold **preempts it the same
  tick** and the servo resumes. Dwell time can therefore only ever be
  consumed by reference-quiescent periods — catch-up snaps after a strafe
  are structurally impossible, which is the user's snap issue (§5) failing
  test #1 today.

**Stage notes (the non-obvious math):**

- **Plan (2):** duration power law `T = c_T · (A_net + gcd)^b` with
  b = 0.502 (log-log OLS, `human_features.py:356`), c_T ≈ 2.95 ticks·deg^-0.5
  from the p50 anchor (9 ticks @ 9.12°; intercept added to the JSON, see
  §11.4). Shape = `profile_8bin` relative speed weights sampled by u = t/T
  (8 bins, linear interpolation), integrated to position; amplitude closes
  exactly. Fitts peak model (slope 3.316 °/tick per log2(1+A), r 0.65) is a
  sanity cross-check on the servo ceiling, not a second shape source.
  `time_to_peak_tau.mean` 0.425 is a **diagnostic** (validation against the
  fit family), not consumed. Mid-epoch replans are allowed (a replan =
  a reversal; humans average 1.08/epoch, p50 0 — diagnostic, not gated).
- **Servo (3):** every tick while engaged and error ≥ 1 gcd: move. Target
  speed = current profile bin's weight, oriented along the current error
  vector, with per-tick acceleration bounded by the bin-to-bin slope of the
  active plan (jerk limit = change of that acceleration). No hold states
  exist in this stage. Reference updates mid-epoch rotate the error vector —
  the servo follows immediately (this is I1's servo half).
- **Noise (4):** `n_t = φ·n_{t-1} + ε_t`, ε ~ N(0, σ_ε), per axis, one
  stage, one seeded draw stream. Stationary σ and φ are the fitted residual
  values; the generator derives `σ_ε = σ·√(1−φ²)` (yaw ≈ 0.1661°,
  pitch ≈ 0.1814°). Gaussian via the existing 3-uniform Irwin-Hall
  approximation (cheap, bounded, KS-insensitive at harness resolution).
  **Known gap — closed by Phase-3 step 2 (2026-09-23):** σ(speed) now
  comes from the sub-bin refit `fitted.noise.by_speed` (§11.2-C): σ is
  looked up per tick by the servo's current speed, ≈ 0.004° below
  1.5°/tick (humans are near-still there — signal-dependent noise) and
  0.434°/0.166° yaw/pitch above, no longer σ_slow-flat.
- **Scheduler (5):** human cadence is ~1 motion epoch (15.9 ticks mean) per
  dwell (3.995 ticks mean, p90 7) → 0.996 cycles/s, dwell_frac 0.206 —
  internally consistent (dwell ticks / total = 0.206 ✓). Model: after each
  completed epoch draw a geometric dwell with p ≈ 1/4 (mean 4; p90 7–8 ✓),
  then a new epoch if error remains. During a dwell the output is frozen
  **pre-quant** — human dwells are exact-zero runs (raw mouse idle), and
  the frozen value is already on-grid, so quantized deltas are exactly 0
  and `dwell_frac` measures honestly. Long idle stretches (> p99) arise
  naturally from unengaged holds, not from the sampler.
- **Gating:** stage 5 gates *both* servo output and noise (a dwell is
  bit-exact stillness; no ±1-step quantizer chatter during holds).

**Amendments from Phase-3 step 1 (2026-09-23 — fitted values only, no
redesign; implementation names in parentheses):**

- **§11.2-A — reference-velocity feedforward (stage 3, `Servo`).** Target
  velocity = profile bin along the current error **+ 1.0 × the
  reference's own conditioned tick velocity** (stage-1 side product).
  Position-only error chase reproduced neither the human profile (its
  pooled 8-bin profile went flat/plateau: R² 0.572) nor lag-1 (0.466):
  in steady state the error self-stabilizes at ≈ refVel and the
  displacement clamp binds, so the engine transmits the reference's
  velocity structure directly — profile R² → 0.823, lag-1 → 0.814;
  after a reference pause the FF term is exactly 0 and pure error-chase
  closes the residual. Acceleration bound = max(profile peak × 8/T,
  profile peak / 3 ticks, |refVel| / 2 ticks) — the two floors
  (`RAMP_TICKS`, `REF_RAMP_TICKS`) let flicks ramp human-fast
  (+20–30 °/tick²) without loosening steady tracking.
- **Flick envelope (stage 2, `MotorPlan`).** `rateScale = max(median,
  flickPeak(A)/PEAK)` with `flickPeak(A) = PLAN_FLICK_BASE +
  PLAN_FLICK_SLOPE·A` — OLS of epoch peak speed on net amplitude for
  A ≥ 15 (n = 72, JSON `fitted.epochs.peak_vs_amplitude_A15`). The
  duration law under-predicts re-acquisition peaks 2–3× (human flick
  chains sustain 40–60 °/tick). Flick input = the plan's own error
  amplitude; the reference-run variant measured better on lag-1 but
  destroyed min-jerk (ordinary plans must keep median speed), reverted.
- **PLAN_SPEED_NORM = p50(A_net)/p50(T) = 1.0131** normalizes the rate
  scale so `profile_8bin` reads as absolute °/tick weights — sampled
  once per plan by age, never re-derived per bin.
- **Float-lattice floor hygiene (I1, `AimEngine`).** The hold gate
  compares each axis to `gcd − 1e-3f` (~1% of one step). Positions
  drift into the hundreds of degrees, so an exact-1-gcd error can land
  ulp-below the floor in float subtraction; the engine then froze
  exactly one step behind the reference mid-run — single-tick holds
  splitting human runs (epoch rate 1.210, lag-1 stuck at 0.466). The
  epsilon is below any quantizable motion, so I1's "only threshold =
  1 gcd" stands.
- **Candidate judging is session-split (→ §11.7.2).** The replay writes
  one `type: session` row per source recording and
  `human_features.load_candidate` splits there. The human group is
  judged per file, so candidate deltas must never cross concatenated
  recording seams either — 6 cross-session teleports (ΣΔ² ≈ 61 800
  deg²) had inflated candidate variance to 33.4 vs human 18.6 and
  alone held lag-1 at 0.466; with the split (no engine change)
  lag-1 = 0.814.

**Amendments from Phase-3 step 2 (2026-09-23 — σ(v) refit + noise stage;
fitted values only, no redesign; implementation names in parentheses):**

- **§11.2-B — clean pre-noise command split (stages 1–3, `AimEngine`).**
  The control loop (hold gate, Servo, plan) runs on
  `State.cmdYaw/cmdPitch` (seeded from `input.current` when `!cmdValid`,
  cleared in `resetMotion`); stage-4 noise exists only in the emitted
  rotation. Feeding the noised output back through `input.current` made
  the engine chase its own jitter during reference pauses — dwell
  collapsed 0.206 → 0.083, epochs merged, min-jerk 0.007. The clean
  command is also written to the candidate JSONL (`cmd_yaw`/`cmd_pitch`)
  as forensics.
- **§11.2-C — signal-dependent σ(v) sub-bin refit (`noise_by_speed`; the
  end-flip repair).** With flat σ = σ_slow the noise stage regressed
  Gate-1 `end_flip_rate` to 0.172 (gate ≤ 0.048): 32 of 37 emitted tail
  flips were pure noise on run-closing ticks of 0.09–0.5°/tick, all
  attributed to bin 0. Humans flip only 0.026 with the same residual
  process *and* keep exact-zero dwells 20.6% of ticks — i.e. human
  emission noise is signal-dependent: it vanishes near rest. Two
  hypotheses were fitted and rejected first (temporal-φ: within-window
  φ_t = 0.44 ≈ pooled 0.466, so no long-memory tail protection; pitch-bin
  inflation: flips were bin-0, not bin-misattribution). Fix: σ looked up
  from 7 sub-bins, edges [0.15, 0.4, 0.9, 1.5, 5, 15] °/tick — windows
  with all 9 deltas < 0.15 (n = 292) fit σ ≈ 0.0041/0.0035 yaw/pitch;
  the middle bins are empty (human slow motion never runs 9 ticks without
  a dwell tick) and carry forward; fast bin [1.5, 5) = 0.4344/0.1659
  (n = 42), carried above. Closing ticks draw ≈ no noise → end-flip
  0.172 → **0.047** ≤ 0.048 with no threshold change (I1 intact), and
  `complex_similar` fell 2.40 → 1.68 (ok) with it; motion ticks ≥ 1.5 draw
  the full fitted σ, which dithers `pattern_repeat` 0.96 → 0.24 = human
  (Gate 2 ✓).

### 11.3 The four knobs (frozen budget)

| Knob | Type | Range | Default | Effect |
|---|---|---|---|---|
| `SpeedScale` | float | 0.25..4.0 | 1.0 | planned durations ÷ s (T ≥ 1 tick); peak-speed expectation × s. 1.0 = corpus baseline (speed p50/p90/p99 = 1.32/5.22/17.47 °/tick) |
| `NoiseScale` | float | 0..3 | 1.0 | multiplies σ on both axes (φ unchanged); 0 = deterministic plan only |
| `EventRateScale` | float | 0.25..4.0 | 1.0 | dwell exit probability p_eff = clamp(p·s, 0.01, 0.95) → mean dwell ≈ 4/s ticks; >1 = snappier cadence, <1 = longer holds |
| `ReactionDelayMs` | int | 0..600 | 0 | delay from engage edge to first plan. **UNFITTED** — default 0 until corpus v2 fits it (schema-9 crosshair markers exist for this); documented placeholder log-normal ~150–200 ms |

Everything else is a fitted constant (§11.4), not a setting. Mode switches
(`Rotations`, `ApplyServerSide`, `Humanize`, `Strafe`/`Strict`,
`KeepRotation`, `ResetTicks`, rotation-mode lists) are on/off semantics read
by the adapter — they are not part of the tuning budget.

### 11.4 Fitted-input manifest — JSON → `FittedAimParams.kt`

Single source of truth: `tools/human_corpus_features.json`. The generated
Kotlin object must carry, verbatim:

| Constant | JSON path | Value now |
|---|---|---|
| `NOISE_SIGMA_YAW_DEG` | `fitted.noise.floor.position_resid_rms_yaw_deg` | 0.1878 |
| `NOISE_SIGMA_PITCH_DEG` | `fitted.noise.floor.position_resid_rms_pitch_deg` | 0.2155 |
| `NOISE_PHI_YAW` | `fitted.noise.floor.residual_lag1_yaw` | 0.4662 |
| `NOISE_PHI_PITCH` | `fitted.noise.floor.residual_lag1_pitch` | 0.5401 |
| `NOISE_INNOV_YAW/PITCH_DEG` | derived `σ·√(1−φ²)` in the generator | ≈0.1661 / 0.1814 |
| `NOISE_BIN_MAX_DEG` | `fitted.noise.by_speed.bins[].max_speed` | [0.15, 0.4, 0.9, 1.5, 5, 15, 1e9] |
| `NOISE_BIN_SIGMA_YAW_DEG` | `fitted.noise.by_speed.bins[].sigma_yaw` | [0.0041, 0.0041·3 carried, 0.4344, 0.4344·2 carried] (n 292/0/0/0/42/0/0) |
| `NOISE_BIN_SIGMA_PITCH_DEG` | `fitted.noise.by_speed.bins[].sigma_pitch` | [0.0035, 0.0035·3 carried, 0.1659, 0.1659·2 carried] — method `fitted.noise.by_speed.method` (§11.2-C) |
| `PLAN_DURATION_EXP_B` | `fitted.epochs.duration_vs_amplitude_exponent.slope` | 0.502 (r 0.740) |
| `PLAN_DURATION_C_T` | derived from stored quantiles (p50 T, p50 A) | 2.9525 |
| `PLAN_PROFILE_8BIN` | `fitted.epochs.profile_8bin` | [2.507, 2.723, 3.232, 3.754, 4.426, 4.258, 3.469, 1.405] |
| `PLAN_FITTS_SLOPE` | `fitted.epochs.fitts_peak_vs_log2_amp.slope` | 3.316 (r 0.653) |
| `PLAN_SPEED_NORM` | derived from stored quantiles (p50 A_net / p50 T) | 1.0131 (rate-scale normalizer, §11.2-A) |
| `PLAN_FLICK_SLOPE` / `PLAN_FLICK_BASE` | `fitted.epochs.peak_vs_amplitude_A15.{slope, intercept}` | 0.2378 / −0.7088 (A ≥ 15, n 72) |
| `PLAN_TAU_PEAK_DIAG` | `fitted.epochs.time_to_peak_tau.mean` | 0.425 (diagnostic only) |
| `SCHED_DWELL_MEAN/P90` | `fitted.dwell.mean_ticks` / `p90_ticks` | 3.995 / 7 → p = 1/4 |
| `SCHED_EPOCH_RATE` | `fitted.epochs.rate_per_s` | 0.996 |
| `SERVO_SPEED_P50/P90/P99/MAX` | `human.pooled.speed.*` | 1.32 / 5.22 / 17.47 / 62.53 |
| `SERVO_PEAK_P90` | `human.pooled.epochs.peak_speed.p90` | 12.90 |
| `SERVO_WIN_STD_P90_5/10/20` | `fitted.mc.windowed_std.*.std.p90` | 2.71 / 3.56 / 4.37 |
| `CORRECTION_MICRO_FRAC` | `fitted.correction.micro_frac_nonzero` | 0.0546 |
| `CORRECTION_REVERSALS_MEAN` | `fitted.correction.reversals_per_epoch.mean` | 1.077 (diagnostic) |
| `QUANT_TARGET_STEPS` | `human.pooled.grid_residual_steps.p99` | 0.00057 |
| `ACCEPT_LAG1_YAW/PITCH` | `human.pooled.lag1_yaw_delta` / `lag1_pitch_delta` | 0.800 / 0.716 |
| `ACCEPT_MINJERK_R2` | `human.pooled.epochs.minjerk_r2` | 0.800 |
| `ACCEPT_END_FLIP` | `fitted.correction.end_flip_rate` | 0.024 |

`fitted.mc.gcd` (0.0914) is **provenance only** — gcd arrives at runtime
from sensitivity via `Input.gcd`, never baked in.

**Generator contract:** `tools/gen_fitted_params.py` reads the JSON and
emits `FittedAimParams.kt`; run manually whenever the corpus changes.
Because the JSON currently stores regression slopes but not intercepts,
Phase-3 step 0 adds `intercept` to the `dur_amp` and `fitts` blocks of
`human_features.py` (additive, regen JSON). A `FittedParamsSyncTest` (JUnit)
re-derives the selected fields and fails on staleness — so the file cannot
drift from the corpus silently.

### 11.5 `RotationSettings` migration

| Fate | Properties |
|---|---|
| **Keep — adapter/mode semantics** | `Rotations`, `ApplyServerSide`, `Strafe`, `Strict`, `KeepRotation`, `ResetTicks`, `AngleResetDifference` (keeps Phase-1 floor logic), `Humanize` (mode: engine path on/off — a switch, not a knob), `prioritizeRequest`/`immediate`/`instant`, rotation-mode lists (`RotationSettingsWithRotationModes`) |
| **→ new-knob successors** (old value *not* auto-migrated; intent only) | `HorizontalAngleChange` + `VerticalAngleChange` → `SpeedScale`; `HumanizeTremor` → `NoiseScale`; `HumanizeReactionDelay` → `ReactionDelayMs` (its ×50 ms intent) |
| **Deprecated no-ops** (stay registered so saved configs load; unread by the path; hidden in GUI where the value API allows; one-time log) | `SimulateShortStop`, `RotationDiffBuildUpToStop`, `MaxThresholdAttemptsToStop`, `ShortStopDuration` (superseded by scheduler dwells); `HumanizeOvershootChance`, `HumanizeOvershootMagnitude` (overshoot is the anti-goal — end-flip ≤ 0.048); `HumanizeCurvature` (no separate arc term once the profile shapes the path); `MinRotationDifference`, `MinRotationDifferenceResetTiming` (the stick-slip enabler — conditioner I1 replaces it structurally) |
| **Removed with the old engine** | `humanizeConfig` getter, `horizontalSpeed`/`verticalSpeed` getters, Aimbot's duplicate humanize fields (§9.2), `RandomizationSettings.kt` (already deleted in the working tree) |

`RotationSettings.snapshot()` (recorder) gains the four new knobs when they
land — additive, schema stays 9 (corpus-v2 task).

**Implemented 2026-09-23 (Phase-3 endgame):** the four knobs landed in
`RotationSettings` (`SpeedScale`, `NoiseScale`, `EventRateScale`,
`ReactionDelayMs`, predicates `rotationsActive && humanize`) with four
additive snapshot fields (`speed_scale`, `noise_scale`,
`event_rate_scale`, `reaction_delay_ms`) — schema stays 9. Removed:
`humanizeConfig` getter, `horizontalSpeed`/`verticalSpeed` getters (the
non-humanize path samples `horizontalAngleChange.random()` inline),
Aimbot's duplicate fields, and the whole short-stop state machine
(`rotDiffBuildUp`/`maxThresholdReachAttempts` + the three methods + both
branches in `RotationUtils`). One deliberate deviation: the
**successor-row** knobs (`HorizontalAngleChange`, `HumanizeTremor`,
`HumanizeReactionDelay`, …) are also kept registered as no-ops rather
than unregistered — saved configs with those keys keep loading by value
name (same rationale as the no-op row); only Aimbot's duplicates were
removed outright, because name collisions inside one module's value bag
are not survivable. `NoRotateSet`'s adapter-level hold branch stays
(module policy, not a humanize knob).

### 11.6 Aimbot unification contract (interface-level; rationale §9)

- Aimbot keeps **target policy** (nearest visible, no attack, lock/onClick
  gating) and **destination policy** (center / no-outborder, its prediction),
  then hands off: `setTargetRotation(destination, aimbotSettings)` with
  `applyServerSide = false`.
- Delete on unification: the private `aimState` (Aimbot.kt:98), the direct
  `HumanAimPath.step` call (line 289), the duplicated humanize knobs
  (lines 42–46 → shared `RotationSettings`), the inline non-humanize
  fallback (line 317), the parallel engagement stamps (→ shared
  `lastShapedTick`/`isShapingNow`).
- The unified path **must own release/reset** (the Phase-1 logic) —
  `applyServerSide = false` would otherwise inherit the never-reset state
  machine that caused the linger.
- Required test: **module-toggle handoff** — toggling Aimbot/KillAura
  mid-fight runs `setTargetRotation`'s release-sync (pending
  `currentRotation` written back to the view, then `resetRotation()`);
  today's Aimbot skips it entirely, so this is the observable behavior
  change to pin down.
- Recorder special cases (`rotationSnapshot()` fallback, `lastAimTarget`,
  `aimbot_engaged`) collapse into the shared stamps.

**Implemented 2026-09-23 (Phase-3 exit):**

- `Aimbot` now owns only target/destination policy; `options =
  RotationSettings(this)` with `ApplyServerSide=false` + `Humanize=true`
  applied at init (saved configs overwrite by value name — same module
  value bag). Axis masks (`HorizontalAim`/`VerticalAim`) became
  destination policy: disabled axes are pinned at the current view, so
  the engine's error there is exactly 0 and the 1-gcd floor holds them
  still. `MaxAngleChange`/`InViewMaxAngleChange` feed the shared
  `Horizontal/VerticalAngleChange` ranges (recorder cap forensics keep
  their meaning; non-humanize mode keeps its turn speeds). `onDisable` →
  `RotationUtils.releaseOwnedBy(Aimbot)`.
- **The churn guard:** `setTargetRotation`'s client-side release block is
  now `if (needsReleaseSync(options.applyServerSide, currentRotation))`.
  The unguarded form called `resetRotation()` on *every* request — for a
  per-tick client-side module that would destroy the stored
  `AimEngine.State` every tick (plan/noise/cmd never persist). The guard
  releases only when a server-side spoof is actually pending
  (`currentRotation != null`) — exactly the KillAura→Aimbot toggle
  scenario this section's handoff test describes.
- Storage: `aimPaths` → `aimStates` — one `AimChannel(state, seed)` per
  `RotationSettings`, fresh `Random.nextLong()` seed per stored channel =
  per-engagement variety, stable within an engagement (§11.7.5
  determinism: the replay still passes an explicit seed; the runtime seed
  is adapter-owned). The adapter passes `engaged = true` — engage/release
  edges are the channel lifecycle (create at first request, drop at
  release/reset/world change); tick discontinuity is handled inside the
  engine.
- Stage-6 note: the adapter does not snap — every consumer already does
  (`fixedSensitivity()` on the release/server branches, `toPlayer()`
  inside the client branch), so `AimEngine.step` output reaches the grid
  on all three paths.
- Recorder: `engagedShaper = RotationUtils.isShapingNow()` covers Aimbot
  (its ticks are stamped by `limitAngleChange` like everyone else);
  `engagedAimbot = engagedShaper && settings.ownerModule === Aimbot`
  keeps the `aimbot_engaged` schema-field semantics; `source` derives
  from the owner (still `"Aimbot"`); `rotationSnapshot()`/`lastAimTarget`
  and Aimbot's stamps deleted — `targetRotation` is the one aim-point
  stamp for all shapers.
- Test: `RotationHandoffTest` pins the guard decision (client-side
  releases a pending spoof, never a null one; server-side never) — the
  headless part of the required module-toggle handoff test; the mc-side
  write-back is the Phase-1-corroborated path (§8.3 item 2). 11/11 tests
  green; Gate 1 exit 0 with byte-identical numbers; Gate 2 queue empty.

### 11.7 Test strategy (frozen by the spikes)

1. **JUnit stage-contract units** (seeded, deterministic): plan duration
   law vs fitted exponent + profile amplitude closure; servo invariants I1
   and I2 (dwell preempt latency = 0 ticks; no sub-1-gcd gate); noise AR
   parameters over long draws (stationary σ within 5%, φ within 0.03);
   scheduler dwell mean/p90 vs fitted; quantizer exactness (integer gcd
   steps); package-import purity scan; state reset/handoff semantics.
2. **Headless replay → `aim_harness.py --candidate`** for everything
   distributional (spike-proven end-to-end). At Phase-3 the replay test's
   drive switches from `HumanAimPath.step` to `AimEngine.step` — same
   corpus, same seeding, same output path, same harness command.
   (Done 2026-09-23; the replay also emits one `type: session` row per
   source recording and `human_features.load_candidate` splits there —
   candidate deltas are judged per session, exactly symmetric with the
   human group, see the §11.2 amendments. Rows carry `server_*` (snapped
   output — what the harness judges), `player_*` (verbatim reference
   echo) and `cmd_*` (clean pre-noise command, §11.2-B forensics).)
3. **Thresholds single-sourced:** harness derives them from the JSON;
   `FittedAimParams.kt` is generated from the same JSON with a sync test —
   acceptance criteria exist in exactly one place.
4. **The Python mirror dies — DONE (2026-09-23):** `human_aim_test.py`
   (84 checks frozen since Phase 2) and `HumanAimPath.kt` were deleted
   together with the §11.6 merge; the runtime adapter drives `AimEngine`.
   Never ported a third time.
5. **Determinism policy:** `Input.seed` → one `Random` advanced in fixed
   call order → byte-identical candidate JSONL per seed (spike B verified
   this property end-to-end).

### 11.8 Acceptance appendix — how Phase 3 is declared done

Gate 1 — the identical spike command must exit **0**.

**Results 2026-09-23 — exit 0 at all three milestones:**

- **merge engine (runtime swap + §11.6 Aimbot unification): exit 0 —
  GREEN**, numbers byte-identical to step-2 (lag-1 **0.810** … end-flip
  **0.047**, grid 0.000, KS 0.249/0.271/0.084), **11/11 tests** including
  `RotationHandoffTest`; `HumanAimPath.kt` + `human_aim_test.py` deleted.
- **step-2 engine (σ(v) sub-bins + noise stage): exit 0 — GREEN**, lag-1
  **0.810**, min-jerk **0.818**, epoch rate **1.030**, dwell **0.208**,
  end-flip **0.047**, grid **0.000**, KS **0.249 / 0.271 / 0.084**. (The
  first noise iteration with flat σ had regressed end-flip to 0.172;
  repaired by the §11.2-C signal-dependent sub-bins, no threshold change.)
- **step-1 engine: exit 0 — GREEN**, lag-1 **0.814**, min-jerk **0.823**,
  epoch rate **0.992**, dwell **0.206**, end-flip **0.019**, grid
  **0.000**, KS **0.249 / 0.271 / 0.091**.

(The old-engine spike below exited 1:)

```bash
./gradlew test
python3 tools/aim_harness.py --candidate build/replay-spike/replay_candidate.jsonl
```

Spike baseline (old engine, 2026-09-23) = the work queue:

| check | gate | spike candidate | recorded TRAIL |
|---|---|---|---|
| lag-1 yaw delta | ≥ 0.650 | **0.231 ❌** | 0.475 ❌ |
| min-jerk R² | ≥ 0.700 | **0.310 ❌** | 0.273 ❌ |
| epoch rate /s | [0.796, 1.196] | **2.607 ❌** | 2.007 ❌ |
| dwell fraction | [0.156, 0.256] | 0.174 ✅ | 0.193 ✅ |
| end-flip rate | ≤ 0.048 | **0.176 ❌** | 0.129 ❌ |
| grid residual p99 | ≤ 0.020 | 0.000 ✅ | 0.002 ✅ |
| KS yaw / pitch / epoch | ≤ 0.378 | 0.249 / 0.271 / **0.388 ❌** | 0.249 / 0.271 / 0.246 |

(KS rows are non-discriminative at this corpus size — §10.2 — the scalar
features carry the gate.) Gate 2 — for a *parity claim*, the informational
MX queue must also be empty: `pattern_repeat` ≤ 0.74 and `factor_hits` ≤
0.50. Step-1 engine (2026-09-23, after session-split): **0.96 / 0.00** —
`factor_hits` already passed; `pattern_repeat` was the holdout, and exact
delta pairs dither away once AR(1) noise runs on motion ticks (stage 4).
Step-2 engine (2026-09-23, with stage 4): **0.24 / 0.00 — both pass, the
queue is empty** (pattern_repeat = human 0.24; `complex_similar` also
inside tolerance at 1.68 vs human 1.44 after the σ(v) fix).

**Amendment from Phase-3 step 3 (2026-09-23 — event scheduler, stage 5):**

- **§11.2-D — dwell sampler + `EventRateScale` mount
  (`EventScheduler.kt`).** Geometric draw on hold entry (epoch
  completion): p_eff = clamp((1/SCHED_DWELL_MEAN)·s, 0.01, 0.95) →
  population mean = fitted 3.995 ticks, draw p90 = 8 vs fitted 7
  (spec's "p90 7–8 ✓"). I2 preempt is now an explicit same-tick call on
  any err ≥ 1 gcd. Under I1+I2 the draw is provably output-neutral — it
  can neither stretch a hold past reference quiescence (I1's floor
  freezes output regardless) nor delay motion past error (I2 preempts) —
  so dwell_frac stays reference-driven (0.208 ✓) and the gate reran
  **byte-identical, exit 0**. Draws use their own stream derived from
  `Input.seed` (+ `SCHED_SEED_SALT`) so the stage-4 noise bytes are
  scheduler-independent; §11.7.5 determinism (byte-identical output per
  seed) still holds. Contract pinned by `EventSchedulerTest` — the
  §11.7.1 "scheduler dwell mean/p90 vs fitted" unit now exists (mean
  ±3%, p90 ±2, p_eff clamps, seeded determinism, preempt-clears).

Stage-to-failure mapping = execution order (§8.3 item 6): conditioner +
servo flip lag-1/min-jerk/epoch-rate/end-flip — **✅ done 2026-09-23**
(the user's snap issue, test #1) → σ(v) refit + noise (KS + Gate-2
pattern_repeat) — **✅ done 2026-09-23** (end-flip regression repaired by
the §11.2-C sub-bins) → scheduler pins dwell — **✅ done 2026-09-23**
(§11.2-D: dwell 0.208 stayed in range, gate reran byte-identical) →
Aimbot merge — **✅ done 2026-09-23** (§11.6: churn-guarded release,
recorder collapsed to shared stamps, handoff predicate tested, old engine
deleted) → quantizer unchanged (already green). **Phase-3 exit
conditions met** (Gate 1 exit 0 + empty MX queue); a parity/bypass claim
still requires corpus v2 (§8.4).
