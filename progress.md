# Progress — aim humanization redo (MX-Project anticheat bypass)

**Branch:** `aim` (base `origin/legacy`, caae3622d) — all work uncommitted,
working-tree only; nothing committed or pushed.
**Updated:** 2026-09-23 (**Phase 3 COMPLETE** — Aimbot merge + runtime swap landed, **Gate 1 GREEN**, **Gate 2 queue empty**, 11/11 tests)
**Primary doc:** [`tools/human_corpus_findings.md`](tools/human_corpus_findings.md)
(findings §1–7, next-steps §8, unification §9, harness baseline §10, **redo spec §11**)

## Phase status

| Phase | What | Status |
|---|---|---|
| 0 | Regression harness `tools/aim_harness.py` — falsifiable gate: HUMAN passes / TRAIL fails / SHAPER fails (exit 0/1/2/3) | ✅ done |
| 1 | Release-linger fix (`RotationUtils.kt`, root cause: all 6,337 stored snapshots had `angle_reset_difference: 0.0`), recorder **schema 9** (per-tick crosshair marker + `server_ip`/`ping_ms`), dual-axis re-baseline @0.25° (not a tell — dropped) | ✅ done |
| 2 | Redo spec (findings **§11**) + test-strategy spikes: JUnit offline ✅, headless replay → `aim_harness.py --candidate` ✅ | ✅ done |
| 3 | Engine redo vs harness — step 0 constants → conditioner+servo (flip test #1) → σ(v) fit → noise → scheduler → Aimbot merge → GCD unchanged | ✅ **done — exit conditions met** (Gate 1 exit 0 + Gate 2 empty; adapter now runs `AimEngine`, §11.6 merge landed, `HumanAimPath.kt` + `human_aim_test.py` deleted) |
| 4 | Corpus v2 on target server (schema 9 recorder): update globs+guard schema together, absolute KS ≈ 0.15, fit reaction delay from crosshair onsets | ⬜ next — **blocked on your recordings** (needs live sessions on the target server) |

## Key results

- **Corpus partition (guarded):** 7× HUMAN `tools/aim-2026-09-22_*.jsonl`
  (signal `player_*`) vs 2× SHAPER `tools/aim-2026-09-19_*.jsonl` (signal
  `server_*`); `human_features.py` exits 3 on violation. The
  `source: KillAura` label on human files = release-linger artifact, not
  ground truth.
- **Fitted (→ `human_corpus_features.json`):** noise σ 0.19°/0.22°,
  residual lag-1 +0.466/+0.540; min-jerk R² 0.80 (τ 0.425, duration
  exponent b 0.502); dwell 20.6% (mean 4 / p90 7), epoch rate 1.00/s;
  grid exact (p99 0.00057 steps); speed p50/p90/p99 = 1.32/5.22/17.47 °/tick.
- **Stop-and-go defect (user's snap issue), TRAIL vs HUMAN:**
  epoch rate 2.01/1.00 per s, min-jerk 0.27/0.80, end-flip 0.129/0.024,
  lag-1 0.475/0.800. This is redo **acceptance test #1**, deliberately *not*
  patched in the old engine.
- **Spike baseline (2026-09-23):** headless replay of the current engine
  → `aim_harness.py --candidate` **exits 1** with exactly the recorded
  TRAIL failure set: lag-1 0.231, min-jerk 0.310, epoch rate 2.607,
  end-flip 0.176 (+ epoch-KS 0.388); grid 0.000 ✅. Two independent paths
  (recorded corpus vs fresh replay) detect the same defect — this table is
  the Phase-3 work queue (spec §11.8).
- **Gate 1 GREEN (2026-09-23, Phase-3 step-1 engine):** `./gradlew test` 4/4
  + `aim_harness.py --candidate` **exit 0** — all nine checks pass:
  lag-1 **0.814** (≥0.650; human 0.800), min-jerk **0.823**, epoch rate
  **0.992**, dwell **0.206**, end-flip **0.019**, grid **0.000**, KS
  0.249/0.271/0.091. Acceptance test #1 (snap issue) green at feature
  level. Three decisive fixes (amended into findings §11.2/§11.4/§11.8):
  **reference-velocity feedforward** (§11.2-A — profile R² 0.572→0.823),
  **float-lattice floor epsilon** `gcd − 1e-3` (epoch rate 1.210→1.019,
  stops 1-step mid-run freezes), **session-split candidate judging**
  (`load_candidate` — cross-session teleports had inflated variance;
  lag-1 0.466→0.814 with no engine change). Informal MX queue now only
  `pattern_repeat` 0.96 (Gate-2 ≤0.74; `factor_hits` already 0.00 ✅) —
  stage-4 noise is the intended fix.
- **Gate 1 GREEN (step-2, 2026-09-23) + Gate 2 queue empty:** landed
  `ColoredNoise.kt` (position AR(1), σ(speed) lookup, seeded from
  `Input.seed`, frozen during holds) plus the clean pre-noise command
  split (§11.2-B — feeding noise back through `input.current` had
  collapsed dwell to 0.083 / min-jerk 0.007). First iteration with flat σ
  regressed `end_flip_rate` 0.019 → **0.172** (32/37 tail flips were pure
  noise on 0.09–0.5°/tick closing ticks). Root cause *fitted*, not tuned:
  human emission noise is signal-dependent (exact-zero dwells 20.6% +
  flip rate 0.026 at the same residual process) → σ(v) refit with 7
  sub-bins (edges 0.15/0.4/0.9/1.5/5/15; near-rest σ = 0.0041, n 292;
  empty middle bins carry; fast [1.5,5) = 0.4344/0.1659, n 42) →
  end-flip **0.047 ≤ 0.048**, all nine checks pass (lag-1 **0.810**,
  min-jerk **0.818**, epoch **1.030**, dwell **0.208**, grid **0.000**,
  KS 0.249/0.271/0.084); `pattern_repeat` **0.96 → 0.24 = human**
  (Gate 2 ≤0.74 ✓, `factor_hits` 0.00 ✓ → **queue empty**),
  `complex_similar` 2.40 → 1.68 ok. Findings amended: §11.2 known-gap
  closed, §11.2-B/§11.2-C, §11.4 `NOISE_BIN_*` rows, §11.7 cmd forensics,
  §11.8 results.
- **Step 3 — event scheduler (2026-09-23):** `EventScheduler.kt` —
  geometric dwell drawn at hold entry with p_eff =
  clamp((1/SCHED_DWELL_MEAN)·`EventRateScale`, 0.01, 0.95) (population mean
  = fitted 3.995, draw p90 8 vs fitted 7), explicit I2 same-tick preempt,
  own `Input.seed`-derived stream so stage-4 noise bytes stay untouched.
  Under I1+I2 the draw is provably output-neutral (can neither stretch a
  hold past reference quiescence nor delay motion past error) — Gate 1
  reran **byte-identical, exit 0**. `EventSchedulerTest` (3 tests: fitted
  mean/p90, p_eff clamps + mean shift, seeded determinism + preempt
  clears) → 10 tests total green.
- **Phase-3 endgame — runtime swap + §11.6 Aimbot merge (2026-09-23):**
  `RotationUtils` now runs `AimEngine` (one channel + fresh seed per
  engagement per settings), `RotationSettings` gained the 4 knobs (+4
  snapshot fields), the short-stop state machine + `humanizeConfig` +
  speed getters are gone (§11.5), and `Aimbot` is a pure
  target/destination-policy module handing off via
  `setTargetRotation(destination, options)` with `ApplyServerSide=false`,
  `Humanize=true` defaults (configs migrate by value name). Critical
  subtlety: the client-side release block is now guarded by
  `needsReleaseSync(...)` — without a pending spoof, per-tick requests
  must NOT call `resetRotation()` or the engine state would churn every
  tick. Recorder collapses to shared stamps (`engagedShaper`,
  owner-based `aimbot_engaged`, `targetRotation` as the one aim-point
  stamp). Deletions: `HumanAimPath.kt`, `tools/human_aim_test.py`.
  `RotationHandoffTest` added → **11/11 tests green, Gate 1 exit 0
  (numbers byte-identical to step-2), Gate 2 empty** → Phase-3 exit
  conditions met (parity claim still gated on corpus v2, §8.4).

## Deliverables map

| Artifact | File |
|---|---|
| Findings + next-steps + unification + harness baseline + **redo spec** | `tools/human_corpus_findings.md` §1–§11 |
| Harness (group mode + `--candidate`) | `tools/aim_harness.py` |
| Corpus analysis + partition guard | `tools/human_features.py` → `tools/human_corpus_features.json` |
| Release-linger fix (floor 2.0° / settle 6 / timeout 400) | `src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationUtils.kt` |
| Recorder v2 (schema 9) | `src/main/java/.../misc/RotationRecorder.kt` |
| Headless replay spike (JUnit, skips if corpus absent) | `src/test/java/.../rotation/HeadlessReplaySpikeTest.kt` |
| JUnit wiring (jupiter 5.10.1, launcher 1.10.1 pinned) | `build.gradle` |
| Python mirror + `HumanAimPath.kt` — **deleted** (Phase-3 end) | ~~`tools/human_aim_test.py`~~ / ~~`utils/rotation/HumanAimPath.kt`~~ |

## Decisions (locked, rationale in findings doc)

- **No adversarial proxy classifier** — feature-distribution/KS checks only.
- 6-stage architecture (spec §11.2) + invariants: **I1** no threshold gates
  (only the ≥1 gcd quantization floor), **I2** dwells preempt at ≥1 gcd error
  (the anti-snap guarantee).
- **4 knobs only:** `SpeedScale`, `NoiseScale`, `EventRateScale`,
  `ReactionDelayMs` (unfitted → default 0 until corpus v2).
- Fitted values ship as **generated `FittedAimParams.kt`** (+ JUnit sync
  test); old humanize settings → **deprecated no-ops** (configs keep loading).
- Testing: JUnit stage units + headless replay → `--candidate`; thresholds
  single-sourced from the JSON; Python mirror never ported again.
- σ-lognormal deferred (min-jerk R² 0.80 sufficient); dual-axis check
  dropped (humans are the *most* dual-axis group at every basis).
- Aimbot unification: keep its target/destination policy →
  `setTargetRotation(destination, aimbotSettings)`,
  `applyServerSide = false`, must own release/reset; module-toggle handoff
  test (spec §11.6).

## Working-state cautions

- The git index holds **old staged copies** of some files (`AM`/`MM`) —
  `git restore`/`checkout` from the index will clobber working-tree changes;
  re-`git add` before committing.
- Corpus files are **untracked**; the replay test skips via `Assumptions`
  when absent so plain `gradle build` stays green elsewhere.
- `RandomizationSettings.kt` is deleted in the working tree (user change) —
  left as-is.

## Current work (Phase 3, ordered per spec §11.8 / §8.3 item 6)

1. **Step 0 ✅ (2026-09-23):** intercepts + flick OLS
   (`peak_vs_amplitude_A15`) in `human_features.py`, JSON regen →
   `tools/gen_fitted_params.py` → `FittedAimParams.kt` → sync + purity
   tests; `./gradlew test` 4/4 green.
2. **Step 1 ✅ (2026-09-23):** `AimEngine` + conditioner + plan + servo
   landed, replay drives `AimEngine.step`, iterated to **Gate 1 exit 0**
   (acceptance test #1 green — numbers in Key results). Fixes amended
   into findings §11.2/§11.4/§11.8: reference-velocity feedforward
   (§11.2-A), flick envelope on plan error, ramp floors (3/2 ticks),
   `GCD_EPS` floor hygiene, session-split candidate judging.
3. **Step 2 ✅ (2026-09-23):** σ(v) 7-sub-bin refit (`noise_by_speed`) +
   `ColoredNoise.kt` stage in `AimEngine` (AR(1), σ(speed) lookup, seeded
   via `Input.seed`, gated off during holds) + clean pre-noise command
   split (§11.2-B) → **Gate 1 exit 0 at step-2** (end-flip repaired
   0.172 → 0.047 by the signal-dependent sub-bins, §11.2-C) and **Gate 2
   queue empty** (pattern_repeat 0.24 = human, factor_hits 0.00).
4. **Step 3 ✅ (2026-09-23):** `EventScheduler.kt` + `EventSchedulerTest`
   — dwell sampler at hold entry, `EventRateScale` mounted (the last of
   the 4 knobs), I2 preempt explicit; gate rerun byte-identical (exit 0,
   10 tests green).
5. **Merge ✅ (2026-09-23):** runtime adapter swap to `AimEngine`,
   §11.5 settings migration, §11.6 Aimbot merge + recorder collapse,
   `RotationHandoffTest`, deletions — **11/11 tests, Gate 1 exit 0,
   Gate 2 empty → Phase 3 complete.**
6. **Phase 4 (next, blocked on you):** record corpus v2 on the target
   server with the schema-9 recorder (normal play + a KillAura session;
   the `aim-2026-09-22_*.jsonl` human glob and guard schema are frozen
   until then). When files exist: update globs + guard schema together,
   re-baseline absolute KS ≈ 0.15, fit `ReactionDelayMs` from crosshair
   onsets (`reaction_delay` field). No bypass/parity claim before that
   (§8.4).
