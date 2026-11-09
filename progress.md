# Progress — aim humanization (MX-Project / Polar anticheat)

**Branch:** `aim` (base `origin/legacy`, caae3622d). Phase 1–3 landed as
`b7b73f428`; the corpus-v2 work below is **working-tree only, uncommitted**.
**Updated:** 2026-10-02 (corpus v2 landed: Oct-02 recordings fitted, four
engine defects fixed, gate extended)
**Primary doc:** [`tools/human_corpus_findings.md`](tools/human_corpus_findings.md)
(findings §1–§7, next-steps §8, unification §9, harness baseline §10,
redo spec §11, **corpus-v2 §12**)

## Phase status

| Phase | What | Status |
|---|---|---|
| 0 | Regression harness `tools/aim_harness.py` — falsifiable gate: HUMAN passes / TRAIL fails / SHAPER fails (exit 0/1/2/3) | ✅ done |
| 1 | Release-linger fix, recorder **schema 9**, dual-axis re-baseline | ✅ done |
| 2 | Redo spec (§11) + JUnit + headless-replay spikes | ✅ done |
| 3 | Engine redo vs harness → 6-stage `AimEngine` + §11.6 Aimbot merge | ✅ done (`b7b73f428`) |
| 4 | **Corpus v2 on the target server** — 7× schema-9 manual recordings fitted, defects fixed, held-out gate added | ✅ done (below) |
| 5 | Reaction-delay fit | ⛔ **blocked on more recordings** — needs ≥6 clean rest→acquire transitions, corpus has 1 |
| 6 | Shaper (KillAura) session on the target server | ⛔ **blocked on you** |

## Phase 4 — what the Oct-02 recordings changed

7× `tools/aim-2026-10-02_*.jsonl`, 6,939 ticks, `source: manual` on 100%,
`aimbot_engaged`/`rotation_active` false on 100%, schema 9, **eu.gamester.org
at 547 ms ping**. Guard passes: GCD-grid residual ≤ 0.0012 steps on ≥99.9% of
deltas — genuinely raw vanilla mouse. This is the deployment-condition corpus
Phase 4 was blocked on.

**Corpus group is now pooled** (14 files, 8,492 non-zero gcd steps, +34% data),
with the two sub-corpora kept separately addressable so the harness can judge
each play style against **its own** baseline.

| | sep22 (calm) | oct02 (target server) | shipped fit was |
|---|---|---|---|
| speed p50/p90/p99 °/tick | 1.32 / 5.22 / 17.47 | **1.49 / 6.02 / 32.32** | 17.47 p99 |
| max °/tick | 62.53 | **168.86** | 62.53 |
| lag-1 yaw | +0.800 | **+0.682** | +0.800 |
| end-flip rate | 0.024 | **0.047** | 0.024 |
| σ(v) fast bin, pitch | 0.166 | **0.234** | 0.166 |
| dwell mean / p90 | 4.00 / 7 | 4.18 / 9 | 4.00 / 7 |
| min-jerk R² | 0.800 | 0.804 | 0.800 |

The heavy tail is **real player aim, not a teleport** — confirmed by the user.
`SERVO_SPEED_P99` had been fitted at half the real p99, and `SERVO_SPEED_MAX`
at the Sep-22 sample maximum, i.e. with zero headroom.

## Defects found and fixed this phase

1. **`SERVO_SPEED_MAX` had no headroom and clipped real flicks.** Fitted to
   the sample max (62.525 vs observed 62.53), so on the Oct-02 corpus it
   truncated 14 real ticks (0.20%) and the engine's own ceiling was 63.19
   °/tick against a real 168.86. Now `observed_max × 1.25` over **every**
   sub-corpus (`gen_fitted_params.py:SPEED_MAX_HEADROOM`), pinned by
   `FittedParamsSyncTest`. Tail fidelity after the fix: >17.5 °/tick human
   1.68% / engine 1.68%; >30° 0.87/0.90; >60° 0.14/0.13; cap binds on 0 ticks.
2. **`ReactionDelayMs` was wired to the wrong event.** The delay armed off an
   `engaged` transition, but the adapter passes `engaged = true` on every
   requested tick, so the edge fired at most once per engine *channel* and
   re-armed on tick gaps — measured effect: **nothing** on target
   re-acquisition, and a freeze *after a lag spike*. Fixed by plumbing real
   target identity: `setTargetRotation(..., targetId)` → `Input.freshTarget`
   (a big destination jump is equally a same-target flick, so rotation
   cannot tell acquisitions apart — the modules know which entity they hold).
   KillAura and Aimbot pass their target's `entityId`. Pinned by
   `ReactionDelaySemanticsTest`. The knob stays at default 0 (Phase 5).
3. **`EventRateScale` was provably inert** — removed from `AimEngine.Knobs`.
   Stage 5's dwell cannot gate motion (I1 freezes output inside the 1-gcd
   floor; I2 preempts the dwell the tick error reaches 1 gcd), so the drawn
   remainder was write-only bookkeeping. Replayed the corpus at 0.25 and 4.0:
   **byte-identical output**. It shipped as a live slider anyway, and
   `EventSchedulerTest` tested the sampler in isolation so nothing caught it.
   The config value stays registered (config compat) as a documented no-op.
   `KnobsObservabilityTest` now enforces *"every knob must change the emitted
   output"* and pins the knob set by reflection.
4. **Recorder reported `max_speed 0.0` / `teleport_jumps 0` for every manual
   session** — both counters were gated on `rotationActive`, so the summary
   fields carried no information for the corpus the servo bounds are fitted
   from. Added unconditional player-side stats (`player_ticks`,
   `player_max_speed`, `player_fast_ticks`, `player_teleport_jumps`).
5. **The gate could not see a play-style miss.** Pooling loosened the lag-1
   bound 0.650 → 0.555 and a known-bad TRAIL engine slipped through it.
   `aim_harness.py` now derives thresholds **per sub-corpus** and judges each
   against its own human baseline as a real second gate.

## Verification (all re-run 2026-10-02)

| Gate | Result |
|---|---|
| `./gradlew test --rerun-tasks` | **17/17 PASS** (was 11; +6 new regression tests) |
| `aim_harness.py` (falsifiability) | **exit 0** — HUMAN passes, TRAIL fails 3, SHAPER fails 3 |
| `aim_harness.py --candidate` (pooled gate) | **all 9 checks PASS** |
| held-out per-sub-corpus gate | oct02 **passes every check**; sep22 **FAILS `end_flip_rate`** → exit **4** |

## Confirmed positive — flagged KillAura session (2026-10-02)

2× `tools/aim-2026-10-02_20-*.jsonl`, twerion.net @ 111 ms (same anticheat as
the manual corpus), 7,707 KillAura ticks / 87 runs. **The user was flagged.**
Full analysis in findings **§13**.

Config in force: `humanize=true, speed_scale=1.0, noise_scale=1.0,
reaction_delay_ms=0, h_max=20, v_max=15`, `max_speed=107.5 deg/tick` — which
independently proves the engine path ran (the legacy path caps at `h_max`).

**Exonerated by a matched control** (the same player's own mouse on the same
ticks): exact delta-pair repeats 113 vs 115 per 1000 (quantisation, not a
tell); dwell 0.013 vs 0.228 (the error was inside the 1-gcd hold floor on
1.91% of ticks vs 1.31% frozen — **the servo tracks correctly**, the
reference simply never quiesces because KillAura re-acquires constantly).

**Real:** acquisition latency over 3,330 switches had median exactly 1 tick,
98% on the next tick, **stdev 0.311**; post-attack 150-delta windows showed
IQR 2.00× and \|mean−mode\| 2.37× the same-tick human. 5 of 9 gate checks
fail. Grid hygiene and the mechanical MX checks are clean — the ML layer is
what fired.

### Fixed this round

1. **Configured speed cap was ignored on the humanize path** (`h_max=20` vs
   107.5 deg/tick emitted, recorder `verdict: FAIL`). `Knobs.maxYawStep` /
   `maxPitchStep` now plumb the ceiling to the servo, re-applied *after*
   stage-4 noise as a per-tick budget (clamping the total froze a permanent
   0.62 deg offset once the command converged). Defaults unconstrained.
   Pinned by `SpeedCapHonouredTest`.
2. **Acquisition latency is now a distribution.** `ReactionDelayMs` defaults
   to the 230 ms physiological prior, drawn lognormal (CV 0.24, ~55 ms sd)
   from a seed-derived stream. Targets the variance, which is the actual
   signature. Prior, not a fit — §12.7 stands. Pinned by
   `ReactionDelaySemanticsTest`.

3. **Ten dead sliders were still rendered in the click GUI.** The §11.5
   deprecated set (`SimulateShortStop`, `RotationDiffBuildUpToStop`,
   `MaxThresholdAttemptsToStop`, `ShortStopDuration`, `HumanizeTremor`,
   `HumanizeOvershootChance`, `HumanizeOvershootMagnitude`, `HumanizeCurvature`,
   `HumanizeReactionDelay`) plus `EventRateScale`. The sharpest case:
   **`HumanizeReactionDelay` (0..10) sat directly above `ReactionDelayMs`
   (0..600)** — both rendered, both gated on `humanize`, only the second read.
   All now use `excludeWithState()` (the codebase idiom, cf. `resetTicksValue`,
   `strictValue`, `rotationsValue`): still registered so old configs load, no
   longer rendered. Pinned by `NoDeadControlsTest`, which also fails on any
   *new* value declared in `RotationSettings` that is not classified as live or
   dead — so the next inert knob cannot be added unnoticed.

   Fixing the engine side of `eventRateScale` was not enough: removing it from
   `Knobs` left a slider that provably does nothing still sitting in the menu.

Also fixed: `human_paths()` / `HeadlessReplaySpikeTest` are now
**content-filtered**, not glob-filtered — the Oct-02 date prefix matches bot
recordings too, and replaying one silently contaminated the candidate
(`dwell_frac` 0.199 → 0.260). Schema-aware discriminator, since schema 8
lingers `rotation_active` on manual ticks.

## Standing gap: end-flip on calm play

The held-out gate immediately earned its keep. The engine's end-flip rate is
**0.065 on sep22 against a human 0.024 (2.7×)**, while matching oct02
(0.069 vs 0.047, 1.5×). It passes the pooled bound only because pooling the
two styles averages them.

This is **pre-existing, not a regression** — Phase 3 step 2 landed it at
0.047 against a 0.048 bound, i.e. 98% of budget, which is why it looked
green.

Diagnosis so far: flips are real sign reversals at ~0.4°/tick (4–5 gcd), **not**
sub-count quantisation shimmer. Both the clean pre-noise command (0.035–0.045)
and the emitted stream (0.059–0.089) contribute, so it is a control-loop +
noise-autocorrelation issue.

**Tried and rejected:** clamping the AR(1) carry-over to the current regime's
stationary σ (the σ(v) gate only bounds the *innovation*, so φ-decayed memory
from a preceding fast tick leaks into closing ticks whose fitted σ is ~100×
smaller). Principled, and it did cut the flip magnitudes — but it made
`end_flip_rate` **worse** (0.066 → 0.080), because removing memory persistence
*increases* independent tick-to-tick sign changes. Reverted; hypothesis wrong.

**Next concrete step:** `NOISE_PHI_YAW/PITCH` (0.466/0.540) are fitted
*globally*, but the closing-tick regime plausibly decorrelates faster. Fit φ
per speed regime in `human_features.slow_noise_features` and let
`ColoredNoise` select φ by the same bin as σ, then re-measure. The
`ColoredNoiseTest` stationarity contract already covers the per-bin case.

## Reaction delay — still unfittable, now with a measured reason

`human_features.reaction_delay_report()` (new) reports feasibility instead of
fabricating a fit. Corpus reality:

- 9 fresh engagements (entity never seen before in its session), 308
  crosshair re-acquisitions of an entity already being fought.
- **1** of the 9 is preceded by a genuine rest window; the other 8 land while
  the player is already turning (median pre-onset motion 8.99 °/tick), so the
  measured lag is zero-or-negative and fitting it would encode a constant no
  human has.
- Floor is 6 clean samples. `FittedParamsSyncTest.reactionDelayStaysUnfittedUntilCorpusSupportsIt`
  pins that `reactionDelayMs` keeps its default 0 until the corpus supports it.

Each recording *does* start clean (crosshair null, at rest, 8–215 idle ticks
before the fight), so the corpus is usable for everything else — it is the
onset *transitions* that are too few.

## Recording instructions that would unblock Phase 5

Enable `RotationRecorder` **before** a target is acquired, then let a fight
end and a *new* opponent be acquired **from a full stop** several times.
Aim at stationary or slow-walking targets at conversational range; fast
flicks produce onsets with no rest window and cannot be used. ~6+ clean
rest→acquire transitions is enough; the report prints the count it found.

## Decisions (locked)

- **No adversarial proxy classifier** — feature-distribution/KS checks only.
- 6-stage architecture + invariants **I1** (no threshold gates beyond the ≥1
  gcd quantisation floor) and **I2** (dwells preempt at ≥1 gcd error).
- **Every engine knob must be observable in the emitted rotation** (new,
  enforced by `KnobsObservabilityTest`).
- **Servo speed bound is an upper bound with headroom**, never a sample
  maximum or a quantile.
- **Thresholds are derived per sub-corpus**, not from a pooled blend.
- MX-rule checks are **informational and never gate** — the raw Oct-02 mouse
  stream (a real player) trips `smooth_run3` and `basic_rob` against the
  calm-corpus baseline, so they do not discriminate bot from human here.
- Reaction delay stays unfitted until the corpus supports it.
- σ-lognormal deferred (min-jerk R² 0.81); dual-axis check dropped (humans
  are the *most* dual-axis group at every basis).

## Working-state cautions

- The git index holds **old staged copies** of some files (`AM`/`MM`) —
  `git restore`/`checkout` from the index will clobber working-tree changes;
  re-`git add` before committing.
- Corpus files are **untracked** (Sep-22 + Sep-19 are committed, ~8.6 MB, for
  reproducibility); the Oct-02 seven are not. The replay test skips via
  `Assumptions` when absent, so plain `gradle build` stays green elsewhere.
- `RandomizationSettings.kt` was deleted in this line of work.
- `settings.gradle` needs `mavenLocal()` plus a hand-installed ForgeGradle
  jar (JitPack pruned the pinned commit) — see the comment at the top.
- Pipeline order when the corpus changes:
  `human_features.py` → `gen_fitted_params.py` → `./gradlew test` →
  `./gradlew test --tests '*HeadlessReplaySpikeTest*'` →
  `python3 tools/aim_harness.py --candidate build/replay-spike/replay_candidate.jsonl`
