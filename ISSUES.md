# Rotation Humanization Issues and Required Fixes

This document records the blocking findings from the branch-wide review against `origin/legacy`. The branch should not
be considered ready for merge or gameplay calibration until every P1 issue is closed and its acceptance criteria are
covered by automatically executed tests.

The order below is intentional. Request lifecycle and action correctness must be fixed before trajectory calibration;
otherwise later measurements will describe state-management defects rather than the intended engine.

## Severity definitions

- **P1 — Blocker:** correctness, lifecycle, compatibility, privacy, or action-safety defect that blocks merge.
- **P2 — Required:** substantial implementation or verification defect that must be resolved before release.
- **P3 — Follow-up:** maintainability or calibration work that may follow the correctness fixes but must be tracked.

## Recommended implementation order

1. Remove the committed runtime log.
2. Repair request lifecycle, handoffs, and instant transitions.
3. Make validity and deadlines operational for action owners.
4. Complete the acquisition-to-tracking state machine.
5. Correct target-point selection and Off-mode behavior.
6. Add purpose-aware timing for exact and time-sensitive actions.
7. Implement configuration migration.
8. Correct prediction, speed override, and quantization defects.
9. Replace the ad hoc verification entry point with automatic tests.
10. Collect runtime traces and calibrate profiles only after all correctness gates pass.

## Current remediation status

The following source changes are implemented in the working tree but remain open until maintainer compilation and the
automatic verification task pass:

- ROT-HUM-001 through ROT-HUM-008;
- ROT-HUM-010 through ROT-HUM-014.

ROT-HUM-009 is removed from the effective tree and covered by ignore rules; removing the already-published historical
commit requires an explicitly authorized history rewrite. ROT-HUM-015 remains intentionally pending until correctness
verification and representative runtime traces are available.

---

## ROT-HUM-001 — Make request validity operational

**Severity:** P1  
**Affected code:**

- `RotationUtils.isRotationValid`
- `RotationUtils.activeRotationValid`
- all `RotationRequest` producers

### Problem

`activeRotationValid` is calculated and logged but never consumed. A request marked `RAYCAST` or `EXACT` therefore
does not prevent its owning action from firing while the generated rotation is invalid.

Entity validation currently intersects only the supplied box. It ignores entity identity, `bodyRange`,
`horizontalRange`, world obstruction, configured reach, and the predicted observer position used to select the aim
point. Projectile requests also validate ballistic aim against the entity's current box, which is not meaningful for
an arcing intercept.

### Required fix

1. Define validity semantics per target and purpose:
   - `NONE`: no action gate.
   - `RAYCAST`: geometrically intersects the allowed target region using the correct observer position and reach.
   - `EXACT`: the quantized output passes the actual world/entity/block raycast required by the action.
2. Store the observer origin and functional reach in the request or target metadata when prediction changes them.
3. Validate `EntityRegion` against its allowed normalized horizontal/body ranges, not merely the full box.
4. Distinguish direct-hit projectile validity from ballistic-solution validity. Do not apply a direct entity raycast to
   an arcing projectile solution.
5. Give request owners an operational result:
   - query the current request's validity and ownership safely; or
   - provide a coordinator callback/action gate tied to the request ID.
6. Update AutoPot, Fireball, MLG, Scaffold, BedDefender, KillAura, and other action producers so the action is executed
   only when its declared validity policy succeeds or an explicitly documented fallback is selected.
7. Clear validity on handoff, reset, world change, and request expiry.

### Acceptance criteria

- No exact action is emitted from an invalid quantized rotation.
- Entity validation rejects points outside configured body/horizontal ranges.
- Exact block validation rejects intervening blocks and wrong faces.
- Projectile validity matches the projectile model being used.
- A validity result cannot be consumed by a different owner or stale request.

### Required tests

- Valid/invalid entity-region raycasts, including range boundaries and obstructions.
- Valid/invalid block and face raycasts after sensitivity quantization.
- Predicted observer-origin validation.
- Ballistic projectile request that is not a direct entity raycast.
- Owner handoff and stale-validity rejection.

---

## ROT-HUM-002 — Preserve motion across request and target handoffs

**Severity:** P1  
**Affected code:**

- `RotationUtils.setTargetRotation`
- `RotationHumanizer.reset` / movement initialization
- request arbitration and movement IDs

### Problem

`setTargetRotation` resets the humanizer whenever owner, purpose, settings, axis selection, or logical target changes.
The reset discards previous input, velocity, and target motion. The next movement therefore assumes zero velocity even
when the last emitted packet was rotating quickly, violating the required C1-continuous handoff.

### Required fix

1. Separate these operations:
   - **world/session reset:** discard all motion state;
   - **camera/reset completion:** discard state after packet motion has actually settled;
   - **request handoff:** preserve observed quantized position and velocity while replacing goal/context;
   - **same-target refresh:** retain movement ID and full tracking state.
2. Add an explicit `handoff`/`replan` input to the pure engine containing current quantized rotation and observed
   velocity.
3. Start the new path/controller from the observed outgoing velocity. Do not attempt to preserve continuity merely by
   adding velocity to a Bézier control point while minimum-jerk time still has zero initial derivative.
4. Give target switches a new movement ID without forcing zero velocity.
5. Reset sensitivity residuals independently from kinematic state where required.

### Acceptance criteria

- Target, owner, and purpose switches never exceed configured velocity/acceleration limits unless an explicit instant
  action is requested.
- Same-target refreshes retain one movement ID.
- Target switches create a new movement ID while retaining position/velocity continuity.
- World change still clears all state.

### Required tests

- Moving target A to target B while rotating at nonzero velocity.
- Owner A to owner B handoff.
- Server-side request to camera-side request and back.
- Handoff around the `-180°/180°` yaw boundary.
- Handoff with quantization residual present.

---

## ROT-HUM-003 — Define and implement instant-mode transitions

**Severity:** P1  
**Affected code:**

- `RotationUtils.setTargetRotation`
- `RotationUtils.limitAngleChange`
- Scaffold's `instantRotation`

### Problem

`instant` is not included in request-change detection. While instant is active, the humanizer is bypassed but retains
its old curve, input, and phase. When instant becomes false, the stale humanizer resumes after an unrelated direct
jump. Scaffold can toggle this state dynamically.

### Required fix

1. Treat entry into and exit from instant mode as explicit state transitions.
2. On instant entry, emit the exact quantized rotation and mark the cosmetic movement as interrupted.
3. On instant exit, begin a handoff from the actual quantized output and observed packet velocity; never resume an old
   curve.
4. Include instant state in request lifecycle comparison or handle it through a dedicated coordinator method.
5. Document whether instant movement may exceed normal acceleration and whether hard per-axis angle limits still apply.

### Acceptance criteria

- No stale path resumes after an instant request.
- Scaffold can toggle block-safe instant mode without an unexplained velocity reversal.
- Instant output and the first subsequent non-instant output are deterministic and bounded according to policy.

### Required tests

- Normal → instant → normal on the same target.
- Instant target switch followed by normal tracking.
- Repeated Scaffold-style instant toggling.

---

## ROT-HUM-004 — Complete the moving-target tracking state machine

**Severity:** P1  
**Affected code:** `RotationHumanizer`

### Problem

A target that moves throughout acquisition generally remains in `PRIMARY`/`SETTLE`. `TRACKING` is entered only after
the output exactly reaches the moving goal within a `1e-6` threshold. Target-velocity feed-forward is therefore often
never used in a real engagement.

The engine can also return `phase = TRACKING` with `complete = true`, which is an invalid state combination.

### Required fix

1. Define explicit state transitions:
   - `PRIMARY` ends when its planned acquisition interval ends, not only after exact capture.
   - If the logical target remains active and is moving, transition to `TRACKING`.
   - If the target is stationary but terminal error remains, transition to `SETTLE`.
   - `COMPLETE` requires a stable target, bounded terminal error, and settled output velocity.
2. At acquisition completion, initialize tracking from actual quantized position/velocity and the filtered target
   velocity.
3. Ensure moving-target tracking continuously uses target-velocity feed-forward plus bounded error correction.
4. Use sensitivity-aware completion tolerances rather than a universal `1e-6` degree threshold.
5. Make `HumanizationStep.complete` derived from the emitted phase or enforce an invariant that
   `complete == (phase == COMPLETE)`.
6. Bound acceleration and jerk across `PRIMARY`, `SETTLE`, `TRACKING`, `OVERSHOOT`, and `CORRECTION` transitions.

### Acceptance criteria

- A continuously moving target enters `TRACKING` after acquisition without first becoming stationary.
- Tracking maintains bounded velocity and acceleration during target reversals.
- `TRACKING` is never returned with `complete = true`.
- A stationary target reaches `COMPLETE` without sustained oscillation or residual drift.

### Required tests

- Constant-angular-velocity target for at least 100 ticks.
- Accelerating and reversing target.
- Target that begins moving before acquisition completes.
- Target that stops after prolonged tracking.
- Phase/complete invariant property test.

---

## ROT-HUM-005 — Make target-point behavior respect Humanization Off

**Severity:** P1  
**Affected code:**

- `TargetPointTracker`
- `RotationUtils.searchCenter`
- KillAura, Aimbot, and ProjectileAimbot target-key usage

### Problem

A non-null target key activates sticky target state even when variation is zero. Off mode therefore retains the first
fallback point instead of using the compatibility target-selection behavior. Switching modes can also freeze or reuse
state created by a different profile.

### Required fix

1. Define Off-mode behavior explicitly. For compatibility, either bypass `TargetPointTracker` entirely or use a
   separate deterministic non-humanized policy that updates its fallback normally.
2. Reset or reinitialize point state when:
   - mode changes between Off and an enabled profile;
   - owner/target changes;
   - allowed body/horizontal ranges change materially;
   - the stored point is no longer feasible.
3. Do not retain a randomized point after humanization is disabled.
4. Store the profile/mode epoch in point state so state cannot silently cross incompatible configurations.

### Acceptance criteria

- Humanization Off does not retain randomized or stale target points.
- Balanced → Off and Off → Balanced transitions are deterministic and reinitialize correctly.
- Stored points remain inside current safe ranges and are reselected when infeasible.

### Required tests

- Repeated fallback changes with variation zero.
- Balanced → Off → Balanced on the same entity.
- Body/horizontal range changes while tracking.
- Target loss, reacquisition, and world reset.

---

## ROT-HUM-006 — Remove target-grid snapping and iid outborder selection

**Severity:** P1  
**Affected code:** `RotationUtils.searchCenter`

### Problem

The sticky point is used only to rank candidates from a `0.1` normalized scan grid. Slow continuous drift consequently
becomes long dwell periods followed by discrete cell changes. The `outborder` path bypasses persistent state and
chooses a new independent random point on every refresh.

### Required fix

1. Split target-region feasibility from target-point choice as specified in `PLAN.md`.
2. Build or cache a valid target region after visibility, reach, body range, and sensitivity constraints are applied.
3. Project the persistent normalized point onto that valid region instead of selecting the nearest coarse scan cell.
4. If a discrete feasibility scan is still needed, refine locally around the chosen point and keep the previous valid
   solution until invalidated.
5. Replace `outborder` iid sampling with a persistent, bounded point state or explicitly exclude it from humanized
   modes.

### Acceptance criteria

- Smooth normalized drift does not produce `0.1`-cell target jumps.
- A stationary target does not change target cells without a validity reason.
- Outborder behavior does not select an independent point every tick.
- Every emitted target point remains raycast-valid for the declared request.

### Required tests

- Slowly drifting normalized point at close and long range.
- Visibility region that shrinks or moves over time.
- Outborder selection across 100 refreshed requests.
- Sensitivity quantization near valid-region boundaries.

---

## ROT-HUM-007 — Add purpose-, width-, and deadline-aware timing

**Severity:** P1  
**Affected code:**

- `HumanizationProfile`
- `RotationHumanizer.updateLimits` / `planDuration`
- action-producing modules

### Problem

The current engine uses hard-coded profile cruise speeds and acceleration ratios. `RotationPurpose`, target angular
width, functional reach, action deadline, and validity window do not affect movement timing. Custom uses the same speed
dynamics as Balanced.

Exact placement and fall-saving actions can therefore be slowed by cosmetic planning without an action deadline.

### Required fix

1. Extend planning input with:
   - rotation purpose;
   - angular target width or safe-region width;
   - remaining deadline ticks;
   - current velocity;
   - exact/functional validity requirements.
2. Calculate desired duration from angular distance and target width, then clamp it against hard per-axis limits and the
   available deadline.
3. Use purpose policy only for functional constraints. Do not create server- or anti-cheat-specific presets.
4. Disable nonessential curvature, drift, and overshoot as a hard action deadline approaches.
5. Add deadlines to MLG, Scaffold, and any other action whose opportunity can expire.
6. Ensure actions wait for validity where waiting is possible and choose a documented bounded fallback where it is not.

### Acceptance criteria

- A wider valid target region does not take longer than a narrower region at the same distance without reason.
- Exact actions meet declared deadlines or report an explicit miss/fallback.
- No cosmetic phase causes an MLG/placement opportunity to be missed silently.
- All output retains configured hard angle limits except an explicitly documented direct policy.

### Required tests

- 2°, 15°, 60°, 90°, and 180° moves to narrow and wide target regions.
- Deadlines at 0, 1, 2, and several ticks away.
- MLG and Scaffold integration scenarios.
- Purpose comparison for combat tracking, projectile, placement, reset, and generic movement.

---

## ROT-HUM-008 — Implement configuration migration and compatibility

**Severity:** P1  
**Affected code:** configuration loading, module settings, and release notes

### Problem

Legacy settings were deleted or renamed without migration. Existing configurations silently lose `Legitimize`,
Randomization, Aimbot jitter, short-stop, minimum-difference, and prediction-size behavior. Aimbot's former default
`Legitimize=true` now becomes `Humanization=Off`, changing even fresh default behavior.

### Required fix

1. Determine whether the configuration framework supports one-release aliases or explicit migration hooks.
2. Apply the migration specified in `PLAN.md`:
   - `Legitimize=false` → `Humanization=Off`;
   - `Legitimize=true` → `Humanization=Balanced`;
   - non-`None` randomization pattern → `Humanization=Balanced`;
   - intentionally discarded advanced randomization values are documented.
3. Migrate renamed prediction settings where a meaningful mapping exists.
4. Preserve Aimbot's intended default behavior or explicitly document and justify the breaking default change.
5. Update localization, descriptions, and release/config migration notes.
6. Remove aliases only after the documented compatibility period.

### Acceptance criteria

- Representative legacy configuration files load deterministically without crashes or silent behavior inversion.
- Old Aimbot defaults map to the intended replacement profile.
- Removed settings produce a migration result or an explicit warning, not silent loss.

### Required tests

- Legacy configs for every affected module.
- Missing, malformed, and mixed old/new settings.
- Round-trip save after migration.

---

## ROT-HUM-009 — Remove the committed runtime log

**Severity:** P1  
**Affected code:** `latest.log`, repository ignore rules, commit history

### Problem

`latest.log` contains local filesystem paths, installed mod/launcher information, server addresses, chat history,
timestamps, and runtime debug data. It does not belong in the source tree.

### Required fix

1. Remove `latest.log` from the branch and, if the branch has been shared publicly, remove it from published history.
2. Add an ignore rule for `latest.log` and appropriate runtime-log patterns.
3. Keep future diagnostic logs outside commits or attach a deliberately scrubbed fixture containing only required
   samples.
4. If a trace fixture is needed, use a compact structured file with anonymized metadata and document its provenance.

### Acceptance criteria

- No runtime log or personal path remains in the effective branch diff or history intended for publication.
- New `latest.log` files are ignored by Git.

---

## ROT-HUM-010 — Return a self-consistent projectile intercept

**Severity:** P2  
**Affected code:** `ProjectileInterceptSolver`

### Problem

After the iteration loop, the solver computes an intercept position from the last damped flight time and calls the
static solver again. That static solution may return a different flight time, leaving `relativeIntercept` and
`flightTicks` inconsistent.

### Required fix

1. Iterate until both time and intercept residual are within configured tolerances.
2. Recompute the intercept position from the final returned flight time.
3. After the maximum iteration count, either:
   - return a solution only if the residual is bounded; or
   - return an explicit non-convergence failure and let the caller use its documented fallback.
4. Record convergence/residual in verification diagnostics.
5. Document that drag remains unmodeled and bound the situations in which the approximation is accepted.

### Acceptance criteria

- `relativeIntercept == relativePosition + relativeVelocity * flightTicks` within tolerance.
- Fast lateral and receding targets either converge or fail explicitly.
- No NaN/infinite solution is returned.

### Required tests

- Stationary, lateral, approaching, and receding targets.
- Near-unreachable and non-convergent trajectories.
- Residual property test over bounded randomized inputs.

---

## ROT-HUM-011 — Keep yaw and pitch speed overrides independent

**Severity:** P2  
**Affected code:** `MovementSpeedSampler`

### Problem

When `overridePitch` is absent, pitch falls back to `overrideYaw`. A yaw-only override can therefore silently replace
the configured vertical limit.

### Required fix

Resolve axes independently:

```kotlin
val yaw = overrideYaw ?: sampledYaw ?: abs(baseYaw()).also { sampledYaw = it }
val pitch = overridePitch ?: sampledPitch ?: abs(basePitch()).also { sampledPitch = it }
```

If coupled overrides are needed by a producer, that producer must pass both fields explicitly.

### Acceptance criteria

- Yaw-only and pitch-only overrides affect only their own axes.
- Explicit overrides do not consume the corresponding base sample.
- Removing an override returns to the retained movement-level base sample according to documented semantics.

### Required tests

- No overrides, yaw-only, pitch-only, both, and override removal.

---

## ROT-HUM-012 — Make quantization residual disposal phase-aware

**Severity:** P2  
**Affected code:** `SensitivityQuantizer`

### Problem

Any zero desired delta returns a zero residual. The quantizer cannot distinguish a confirmed settled endpoint from a
temporary plateau, so sub-GCD motion can be discarded in the middle of an active movement.

### Required fix

1. Pass explicit movement completion/settling state into quantization, or expose separate `quantize` and `settle`
   operations.
2. Preserve residual across an active zero-delta sample.
3. Discard residual only on movement handoff, explicit cancellation, sensitivity change, or confirmed completion.
4. Ensure pitch-bound clipping does not retain impossible residual debt.

### Acceptance criteria

- Temporary plateaus preserve sub-GCD motion.
- Settled endpoints do not drift later from stale residual.
- Long monotone motion has no directional quantization bias.

### Required tests

- Sub-GCD movement, one-tick plateau, resumed movement.
- Explicit settle and subsequent idle ticks.
- Pitch bounds and yaw wraparound.
- Sensitivity change during residual accumulation.

---

## ROT-HUM-013 — Replace the ad hoc verification harness with automatic tests

**Severity:** P2  
**Affected code:**

- `RotationHumanizerVerification`
- Gradle verification wiring
- CI configuration

### Problem

The verification harness is a `main()` program invoked only by a custom task. A normal `build`, `test`, or `check`
does not execute its assertions. Current coverage is almost entirely pure-math and does not exercise coordinator or
producer integration.

### Required fix

1. Convert verification cases to the project's test framework or wire the custom task into `check` with clear failure
   reporting.
2. Split tests by component instead of retaining one monolithic executable object.
3. Add coordinator integration tests using a minimal Minecraft adapter abstraction where direct game construction is
   impractical.
4. Add property/scenario tests listed under each issue in this document.
5. Add an opt-in structured trace exporter for runtime investigation.
6. Make CI execute unit tests, integration tests that do not require a game session, and static checks.

### Acceptance criteria

- `check` automatically runs every pure-math invariant.
- A failing invariant fails the normal verification pipeline.
- Coordinator lifecycle, validity, deadlines, reset, and producer handoffs have automated coverage.
- Runtime traces include seed, movement/request ID, owner, purpose, phase, source, target, planned/quantized/server
  output, validity, velocity, acceleration, and jerk.

---

## ROT-HUM-014 — Correct documentation and implementation-status claims

**Severity:** P2  
**Affected code:** `PLAN.md`, settings documentation, release notes

### Problem

`PLAN.md` declares the implementation feature-complete even though several planned exit criteria are absent:

- manual trace corpus and exporter;
- Off-mode acceleration/velocity baseline;
- target-width timing;
- C1-continuous target handoff;
- valid-region/target-choice separation;
- action deadline integration;
- configuration aliases;
- localization updates;
- gameplay/statistical calibration.

### Required fix

1. Change implementation status to accurately distinguish implemented, partial, and unimplemented work.
2. Link every completion claim to code and an automatically executed test or documented manual verification.
3. Do not call the feature complete until every phase exit criterion is satisfied.
4. Document profile response semantics, hard limits, purpose policy, deadlines, and compatibility behavior.

### Acceptance criteria

- Status claims match the source and verification pipeline.
- No acceptance criterion is marked complete without evidence.

---

## ROT-HUM-015 — Calibrate profiles from measurements, not constants

**Severity:** P3  
**Depends on:** ROT-HUM-001 through ROT-HUM-014

### Problem

Subtle, Balanced, and Custom currently use hard-coded speed and acceleration bundles without a manual trace corpus or
scenario-level comparison. Tuning before lifecycle fixes would only conceal defects.

### Required fix

1. Capture opt-in manual and generated traces in equivalent scenario buckets.
2. Measure acquisition time, validity time, velocity, acceleration, jerk, curvature, target dwell, corrections,
   terminal error, and deadline misses.
3. Tune broad purpose-independent profile bundles. Do not add server- or anti-cheat-specific presets.
4. Keep user hard limits separate from desired profile dynamics.
5. Document sample sizes, scenarios, and chosen parameter ranges.

### Acceptance criteria

- Profiles are justified by reproducible comparative measurements.
- Functional regressions and deadline misses remain zero in the acceptance suite.
- No profile relies on fixed repeated movement lengths or iid output jitter.

---

## Merge gate checklist

- [ ] All P1 issues are closed.
- [ ] All P2 issues are closed or explicitly approved with a tracked follow-up and no correctness impact.
- [ ] Runtime logs and personal data are absent from the branch.
- [ ] Legacy configuration migration is tested.
- [ ] Normal verification automatically executes trajectory and coordinator tests.
- [ ] Exact actions are validity- and deadline-safe.
- [ ] Continuous target tracking and request handoffs are kinematically bounded.
- [ ] Off mode has documented compatibility behavior.
- [ ] Representative multiplayer/manual regressions have been completed after automated checks pass.
- [ ] `PLAN.md` accurately reports implementation status.
