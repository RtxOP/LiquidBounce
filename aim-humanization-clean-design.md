# Clean Aim-Humanization Design (proposal)

## 1. Goal
Produce rotation output that is **indistinguishable on the measured axes** from human mouse aim, with the smallest mechanism that can do that.

**Primary success signals (from the flagged sessions, §13 / progress.md):**
- **Acquisition latency is a distribution, never a constant.** 3,330 target switches showed median 1 tick, 98% next tick, stdev 0.311, 6 values — degenerate. A human-like design must yield non-trivial spread and a plausible support over many acquisitions.
- **Post-attack / post-retarget delta windows have human-like shape.** 150-delta windows showed IQR ~2.0× and |mean−mode| ~2.37× the human reference (5/9 gate checks failed). The design must model engagement disturbance + correction as a stochastic process, not a single deterministic servo trace.
- **Grid/quantization hygiene stays clean** (already exonerated). Output must remain GCD-consistent (`getFixedAngleDelta()` / sensitivity grid).
- **No catch-up snaps, no cap violations.** Per-axis configured ceilings apply to the **emitted** per-tick step (command + residual), never silently ignored; no threshold gates except the `>= 1 gcd` quantization floor.
- **Calm-tracking stability.** Avoid the current sep22 failure mode: end-flip rate inflated (real sign reversals near convergence) from globally-fit noise memory.

**Non-goals:** mimicking every biomechanical detail; adversarially training against a specific detector; exposing many tuning knobs; keeping inert stages for theory’s sake.

## 2. Minimal architecture (3 runtime components + 1 offline)

```text
Target/event input  ─┐
(current, reference,  │   ┌──────────────────┐    ┌──────────────┐    ┌──────────────┐
 gcd, tick, seed,     ├──▶│ Engagement model  │──▶│ Motion core   │──▶│ Emit/quantize │──▶ Rotation
 targetId, attack?)  │   │ (when to move)    │    │ (how to move) │    │ (cap + GCD)  │
                      ┘   └──────────────────┘    └──────────────┘    └──────────────┘
                                   ▲
                                   │ offline fit / priors
                            ┌──────────────┐
                            │ Corpus→params │  (tools only, no runtime bloat)
                            └──────────────┘
```

One `State` per `RotationSettings` channel (keep the current channel-per-owner pattern). **No** write-only scheduler stage. Every runtime parameter must be observable in emitted output and covered by a gate.

## 3. Component design

### 3.1 Engagement model — *when motion starts/changes*
Solves the two signals that actually failed.

**Inputs:** `freshTarget` (edge, derived from `targetId` identity — keep the corrected semantics; never from `engaged` transitions), optional `attackEdge`/`hitRegistered` event if available from combat modules, `tick`, `seed`.

**Acquisition latency (fresh target):**
- On `freshTarget`, draw `L_ms ~ Distribution(params)` and convert to ticks at 20 TPS (store remainder/subtick policy explicitly). **`L_ms = 0` only when explicitly disabled.**
- **Distribution, not a point estimate:** lognormal or empirical CDF with non-trivial CV. Default ships as a **labeled physiological prior** until corpus-fittable; once `>= 6` clean `rest → acquire` transitions exist (per `reaction_delay_report` criteria), replace with a fitted distribution (mean, variance/skew, optionally by range/target speed). Record `(n, source=prior|fit, params)` in metadata.
- **During delay:** hold the last emitted rotation (no servo advance). Do **not** inject independent convergence noise while holding; keep output stable but avoid a globally unique freeze signature by letting the *latency length* and *first-step kinematics* carry variance (the flag was on degeneracy of onset, not on a still frame). Re-arm only on a new `freshTarget` edge.
- **Determinism:** latency RNG stream separated from motion noise (keep stream-salt policy), seeded per channel.

**Post-attack / retarget micro-dynamics:**
- Model as an **event impulse + settling** applied to the motion reference/velocity for `W` ticks after `attackEdge` or `freshTarget`:
  - draw impulse `(dYaw,dPitch, tau, shape)` from fitted/priored ranges (small, bounded, signed with realistic correlation);
  - let it decay into the main motion profile (mixture/settling), producing spread in post-event windows rather than a single exponential/servo shape.
- This is the minimal hook to fix IQR/|mean−mode| failures without simulating a hand. Parameters fit from post-attack windows in the corpus; validated by the same 150-delta window metrics that failed.

### 3.2 Motion core — *how it moves*
A single smooth tracker, not six conceptual stages.

- **Plan/track:** critically damped or bounded min-jerk convergence toward `reference` with **bounded velocity and bounded acceleration** (per axis / magnitude policy chosen once and tested). Preserve velocity continuity across retargets (no teleport). Allow mid-course reference updates without replanning explosions.
- **No threshold gates** beyond: (a) `|error| < 1 gcd` quantization floor / hold behavior, (b) physical/config caps. Dwell/stop “events” emerge from convergence, not a random gate that can’t act.
- **Caps:** accept `maxYawStep/maxPitchStep` (from configured ranges, default unconstrained). Core produces a command; emit stage enforces the cap on the **total emitted step** using a **budget** (`command` spends first, residual gets remaining room) so a tight cap slows motion but never creates a permanent offset — keep the corrected stage-6 semantics.
- **Reference conditioning:** keep branch continuity (360° unwrap against current emitted branch) and finite/NaN guards. That’s it.

### 3.3 Residual variation — *human irregularity, without artifacts*
- Small correlated residual (AR(1) or equivalent) added **pre-quantize on the motion path only**; **zeroed/frozen when converged inside the floor** to keep holds bit-stable.
- **State-dependent to fix sep22 end-flip:** scale residual amplitude and/or correlation by proximity to convergence (e.g. `sigma_eff = sigma(v) * g(|error|, speed)` with `g -> 0` near settle) and/or fit `phi` **per speed regime** (the documented next step) instead of one global `phi`. Acceptance: sep22 held-out `end_flip_rate` within its per-sub-corpus bound, without regressing oct02.
- Residual must never be the only source of variance on the failing signals; it complements, not replaces, the engagement model.

### 3.4 Emit/quantize
- `emitted = quantize_to_gcd(budget_limited(command + residual))`, pitch clamped to `±(90-gcd)`. GCD from runtime sensitivity (`getFixedAngleDelta()`), never a fitted constant.
- Record/verify: max emitted step <= cap (+gcd epsilon), grid residual clean, no NaN.

## 4. Configuration surface (small, all live)
- `Humanize` (bool), `ApplyServerSide`/lifecycle unchanged.
- `Speed`/tracking responsiveness (or keep a single scale) — **only if** it observably affects output and gates pass.
- `ReactionDelay` — single control (ms/distribution scale), default = labeled prior, `0` disables. **Remove/keep-hidden** all duplicate/dead controls (no second delay slider, no inert `EventRateScale`, no deprecated raw tremor/overshoot/curvature knobs in the GUI).
- `Horizontal/VerticalAngleChange` = real emitted caps (honored).
- **Rule:** adding a knob requires an “observability” test (non-default => output changes on a fixed replay) and a gate metric; otherwise it does not ship.

## 5. Validation (gates must match failures)
Offline, deterministic per seed, on held-out sub-corpora (keep per-sub-corpus thresholds — do not average away style):
- existing hygiene: GCD/grid, no snaps, cap compliance, speed distribution sanity;
- **acquisition latency distribution** over switches: median/range, stdev/CV, value entropy/support — **fail on degeneracy** (the exact §13 signature);
- **post-attack/retarget 150-delta window** IQR and |mean−mode| (and any other §13 features that failed) vs per-corpus human bounds;
- **end-flip rate** per sub-corpus (sep22 must pass);
- falsifiable baselines: HUMAN passes, TRAIL/constant-latency/degenerate engines fail.
- MX mechanical rules remain **informational only**.

## 6. Codebase integration points (concrete)
- `RotationUtils.setTargetRotation(..., targetId)` + `freshTarget` edge: **keep** (correct). Add/propagate `attackEdge` if post-attack modeling needs it (from KillAura/click path) — optional but recommended given §13.
- `RotationUtils.limitAngleChange` / per-`RotationSettings` channel: replace `AimEngine.step` internals with the 3-component pipeline; keep channel seed/state map and cap.
- `RotationSettings`: collapse to live controls + hidden compat registrations; `engineKnobs()` (or equivalent) only carries live params.
- `Aimbot`/`KillAura`: keep unified handoff (target selection owns destination; shaping shared). No per-module duplicate shapers.
- `RotationRecorder`: ensure it logs acquisition events, attack edges, target ids/run ids, and emitted deltas needed to compute the §13 metrics (it already records much; align schema to the gates).
- `tools/`: keep corpus/harness/fitting offline; regenerate params artifact; do **not** gate `gradle build` on untracked corpora.

## 7. Migration / deletion candidates
- Remove `EventScheduler` from the motion path (and as a behavioral concept) — it is provably non-gating under the current invariants; keep only if re-specified as an *observable* mechanism (it currently isn’t). Delete or reduce `AimEngine` stage layering accordingly.
- Collapse deprecated compat clutter over time; keep `excludeWithState` registrations only where old configs exist.
- Delete/stop growing generated corpus JSON in-tree if size becomes a problem; prefer reproducible artifacts or LFS/offline storage.

## 8. Expected outcome
A smaller, auditable runtime whose every degree of freedom maps to an emitted, gated behavior — directly targeting the two distributions that got flagged (acquisition latency, post-event dynamics) and the one held-out regression (calm end-flip) — instead of a larger fitted pipeline that passes proxy mechanical gates while missing the real decision boundary. The main dependency is **corpus support for acquisition-delay fitting** (>=6 clean rest→acquire transitions); until then, ship an explicitly-labeled, non-degenerate prior and keep the degeneracy gate failing-closed for “constant latency” configs.
