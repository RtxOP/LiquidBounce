package net.ccbluex.liquidbounce.utils.rotation.aim

import net.ccbluex.liquidbounce.utils.rotation.Rotation
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Six-stage human aim engine — spec: tools/human_corpus_findings.md §11.
 *
 * Stage 1 reference conditioner → stage 2 motor plan → stage 3 servo →
 * stage 4 colored noise → stage 5 event scheduler are implemented here;
 * stage 6 (GCD quantizer) is applied by the adapter on the returned
 * rotation (spec §11.1) — or by the headless replay test, which snaps the
 * same way.
 *
 * Invariants (spec §11.2):
 *  I1 — no threshold gates: the only threshold is the >= 1 gcd quantization
 *       floor (the grid cannot act below it — physics, not a gate);
 *  I2 — holds never survive accumulated error >= 1 gcd: motion resumes the
 *       same tick (the anti-snap guarantee for the user's strafe issue).
 */
object AimEngine {

    /** Everything the engine needs from the outside world (spec §11.1). */
    data class Input(
        val current: Rotation,     // last emitted rotation (branch + grid anchor)
        val reference: Rotation,   // destination from the targeting layer
        val engaged: Boolean,      // a live aim request exists this tick
        val tick: Int,             // absolute tick; a gap resets state
        val gcd: Float,            // runtime sensitivity gcd — NOT the fitted constant
        val seed: Long = 0L,       // determinism root (stages 4–5 draw from here)
        val knobs: Knobs = Knobs(),
        /**
         * True on the tick a NEW target is acquired (the adapter compares
         * target identity, not rotation distance — a big destination jump is
         * equally a same-target flick, so rotation cannot tell them apart).
         * Arms the reaction delay exactly once per acquisition.
         *
         * This is deliberately NOT derived from `engaged` transitions: the
         * adapter passes `engaged = true` on every requested tick (there is no
         * per-tick "no target" signal), so an engage-transition edge fired at
         * most once per channel and only re-armed after a tick gap — which
         * made the knob delay output after lag spikes instead of delaying
         * target acquisition, i.e. the opposite of its meaning.
         */
        val freshTarget: Boolean = false,
    )

    /**
     * The tuning knobs; everything else is fitted.
     *
     * INVARIANT: every knob here MUST be observable in the emitted rotation.
     * `eventRateScale` was removed because it provably was not — stage 5's
     * dwell cannot gate motion (I1 already freezes output inside the 1-gcd
     * floor, and I2 preempts any dwell the tick error reaches 1 gcd), so the
     * drawn remainder was write-only bookkeeping and the knob was a slider
     * that did nothing. `KnobsObservabilityTest` enforces the invariant.
     *
     * `reactionDelayMs` is UNFITTED: the corpus has 1 clean rest->acquire
     * transition against a floor of 6, so it keeps its default 0 until a
     * corpus supports the fit (see human_features.reaction_delay_report).
     */
    data class Knobs(
        val speedScale: Float = 1f,     // planned durations ÷ s
        val noiseScale: Float = 1f,     // multiplies fitted σ (stage 4)
        // 0 disables the delay; otherwise the mean of a lognormal draw.
        val reactionDelayMs: Int = REACTION_PRIOR_MEAN_MS,
        /**
         * Optional per-axis ceiling on the emitted per-tick displacement
         * (deg/tick), from `HorizontalAngleChange` / `VerticalAngleChange`.
         *
         * These two sliders used to be silently IGNORED on the humanize path:
         * the engine ran on fitted parameters while the recorder still
         * enforced the configured caps, so a live KillAura session with
         * h_max = 20 emitted up to 107.5 deg/tick and the recorder reported
         * `verdict: FAIL` with 538 cap violations. Default is unconstrained
         * (Float.MAX_VALUE) so the fitted engine keeps its natural speed
         * (~p50 1.4 / p90 5.7 / p99 26.4 deg/tick); a tight cap is a real
         * constraint, not a safety rail.
         */
        val maxYawStep: Float = UNCONSTRAINED,
        val maxPitchStep: Float = UNCONSTRAINED,
    )

    /** Sentinel meaning "no ceiling configured". */
    const val UNCONSTRAINED = Float.MAX_VALUE

    /**
     * Default acquisition latency, in ms — a PHYSIOLOGICAL PRIOR, not a
     * corpus fit. `human_features.reaction_delay_report()` refuses to fit it
     * (the manual corpus holds 2 clean rest->acquire transitions at a strict
     * filter and 12 at a filter loose enough to be contaminated by motion
     * already underway, against a floor of 6), so there is no measured value
     * to ship. 230 ms sits inside the documented range for noticing a
     * peripheral target and initiating a saccade to it.
     *
     * What actually matters here is the SPREAD, not the mean: the flagged
     * session answered 3,330 target switches with a median of exactly 1 tick,
     * 98% on the very next tick, 6 distinct values and stdev 0.31. A latency
     * distribution that degenerate is machine-signature on its own, so the
     * draw is lognormal with [REACTION_PRIOR_CV] and never a constant.
     *
     * Set `reactionDelayMs = 0` to disable the delay outright.
     */
    const val REACTION_PRIOR_MEAN_MS = 230

    /** Lognormal CV of the acquisition latency; ~55 ms sd at the 230 ms mean. */
    const val REACTION_PRIOR_CV = 0.24f

    /** Engine state; one instance per [net.ccbluex.liquidbounce.utils.rotation.RotationSettings]. */
    class State {
        var lastTick: Int = Int.MIN_VALUE
        var reactRemaining = 0
        internal var plan: MotorPlan.Plan? = null
        var age = 0
        var velYaw = 0f
        var velPitch = 0f

        // Clean pre-noise command — the control loop (hold gate, servo,
        // plan) runs on this; the emitted rotation = cmd + stage-4 noise
        // (§11.2-B): jitter must never feed back into err, or the engine
        // chases its own noise and holds never engage.
        var cmdYaw = 0f
        var cmdPitch = 0f
        var cmdValid = false

        // Reference motion-run tracking (features "epoch" semantics): net
        // displacement of the reference since its last full pause — kept
        // for epoch parity diagnostics; the servo feedforward consumes the
        // per-tick reference velocity below.
        var prevRefValid = false
        var prevRefYaw = 0f
        var prevRefPitch = 0f
        var refRunYaw = 0f
        var refRunPitch = 0f
        /** Reference velocity (conditioned) on the current tick — servo FF input. */
        var refVelYaw = 0f
        var refVelPitch = 0f

        /** Stage-4 AR(1) noise memory + its seeded draw stream. */
        internal val noise = ColoredNoise.State()
        internal var rng: Random? = null
        /** Stage-1 latency draw stream, separate so stage-4 bytes are stable. */
        internal var reactRng: Random? = null

        /**
         * Last rotation this engine actually emitted. The configured per-axis
         * ceiling has to hold on the EMITTED step, and stage-4 noise is added
         * after the servo clamp — so the cap is enforced against this, not
         * against `Input.current` (which the adapter supplies as the last
         * SENT rotation, a different thing).
         */
        internal var lastEmittedYaw = 0f
        internal var lastEmittedPitch = 0f
        internal var emittedValid = false

        // Stage-5 event scheduler (spec §11.2 stage 5): geometric dwell
        // bookkeeping + its own seed-derived draw stream (§11.7.5) — the
        // separate stream keeps stage-4 noise bytes scheduler-independent.
        internal val scheduler = EventScheduler.State()
        internal var schedRng: Random? = null
        /** True while inside a converged hold — detects epoch completion. */
        internal var holding = false

        fun resetMotion() {
            plan = null
            age = 0
            velYaw = 0f
            velPitch = 0f
            cmdValid = false
            holding = false
        }

        fun reset() {
            resetMotion()
            reactRemaining = 0
            prevRefValid = false
            refRunYaw = 0f
            refRunPitch = 0f
            refVelYaw = 0f
            refVelPitch = 0f
            noise.yaw = 0f
            noise.pitch = 0f
            rng = null
            reactRng = null
            emittedValid = false
            scheduler.dwellRemaining = 0
            schedRng = null
        }
    }

    fun step(input: Input, state: State): Rotation {
        // Tick continuity: a gap resets plan/velocity memory (spec §11.1).
        if (state.lastTick != Int.MIN_VALUE && input.tick != state.lastTick + 1) {
            state.reset()
        }
        state.lastTick = input.tick

        if (!input.engaged) {
            state.reset()
            state.emittedValid = false
            return input.current.copy()
        }
        if (!input.reference.yaw.isFinite() || !input.reference.pitch.isFinite()) {
            state.lastEmittedYaw = input.current.yaw
            state.lastEmittedPitch = input.current.pitch
            return input.current.copy()
        }

        val gcd = if (input.gcd.isFinite() && abs(input.gcd) > MIN_GCD) abs(input.gcd) else MIN_GCD

        if (!state.emittedValid) {
            state.lastEmittedYaw = input.current.yaw
            state.lastEmittedPitch = input.current.pitch
            state.emittedValid = true
        }

        // Stage 1: conditioner — branch continuity + engage/reaction edge.
        val reference = ReferenceConditioner.condition(
            state = state,
            current = input.current,
            reference = input.reference,
            delayMs = input.knobs.reactionDelayMs,
            reactRng = state.reactRng
                ?: Random(input.seed + REACT_SEED_SALT).also { state.reactRng = it },
            freshTarget = input.freshTarget,
        )
        if (!ReferenceConditioner.motionAllowed(state)) {
            state.velYaw = 0f
            state.velPitch = 0f
            state.lastEmittedYaw = input.current.yaw
            state.lastEmittedPitch = input.current.pitch
            return input.current.copy()
        }

        // I1: >= 1 gcd on either axis is the only motion threshold. The
        // epsilon compensates float-lattice noise in the subtraction
        // (positions drift into the hundreds of degrees, so a 1-gcd err can
        // land ulp-below the floor — that froze the engine exactly one step
        // behind the reference mid-run: single-tick holds splitting human
        // runs, breaking lag-1 chains and inflating the epoch rate). It is
        // ~1% of one step — below any quantizable motion, so I1 stands.
        // Control runs on the CLEAN command (§11.2-B): stage-4 noise lives
        // only in the emitted rotation. Feeding the jitter back through
        // input.current made the engine chase its own noise during reference
        // pauses — holds never engaged (dwell 0.206→0.083, epochs merged).
        if (!state.cmdValid) {
            state.cmdYaw = input.current.yaw
            state.cmdPitch = input.current.pitch
            state.cmdValid = true
        }
        val errYaw = reference.yaw - state.cmdYaw
        val errPitch = reference.pitch - state.cmdPitch
        if (abs(errYaw) < gcd - GCD_EPS && abs(errPitch) < gcd - GCD_EPS) {
            // Natural hold: inside the quantization floor. Stopped = stopped.
            // Stage 5: entering a hold = a completed (converged) epoch — draw
            // the fitted geometric dwell. By I1 the output stays frozen beyond
            // any draw (the floor forbids motion) and by I2 error ≥ 1 gcd
            // preempts it the same tick, so the draw can never stretch a hold
            // past reference quiescence — cadence bookkeeping + the
            // EventRateScale mount (see EventScheduler).
            if (!state.holding) {
                state.holding = true
                val schedRng = state.schedRng
                    ?: Random(input.seed + SCHED_SEED_SALT).also { state.schedRng = it }
                EventScheduler.begin(state.scheduler, schedRng, EventScheduler.FITTED_SCALE)
            }
            if (state.scheduler.dwellRemaining > 0) {
                state.scheduler.dwellRemaining--
            }
            state.velYaw = 0f
            state.velPitch = 0f
            return input.current.copy()
        }
        // I2: error ≥ 1 gcd preempts any active dwell the same tick — motion
        // resumes below regardless of the drawn remainder.
        state.holding = false
        EventScheduler.preempt(state.scheduler)
        val amp = hypot(errYaw, errPitch)

        // Stage 2: motor plan — create/refresh on expiry or error growth ≥ 1.5×.
        var plan = state.plan
        if (plan == null || state.age >= plan.ticks || amp >= plan.amplitude * 1.5f) {
            plan = MotorPlan.create(amp, gcd, input.knobs.speedScale, amp)
            state.plan = plan
            state.age = 0
        }

        // Stage 3: servo — profile-shaped + reference-velocity feedforward
        // (§11.2-A), acceleration-limited, no overshoot.
        val out = Servo.tick(
            current = Rotation(state.cmdYaw, state.cmdPitch),
            plan = plan,
            age = state.age,
            errYaw = errYaw,
            errPitch = errPitch,
            velYaw = state.velYaw,
            velPitch = state.velPitch,
            refVelYaw = state.refVelYaw,
            refVelPitch = state.refVelPitch,
            maxYawStep = input.knobs.maxYawStep,
            maxPitchStep = input.knobs.maxPitchStep,
        )
        state.cmdYaw = out.rotation.yaw
        state.cmdPitch = out.rotation.pitch
        state.velYaw = out.velYaw
        state.velPitch = out.velPitch
        state.age += 1

        // Stage 4: colored noise — motion path only; a hold freezes output
        // AND noise (bit-exact dwells, §11.2 gating). σ(speed) is the
        // binned refit; NoiseScale multiplies the offset. Pitch stays one
        // step inside the pole so stage-6 snapping cannot overshoot ±90.
        val rng = state.rng ?: Random(input.seed).also { state.rng = it }
        ColoredNoise.advance(
            state = state.noise,
            rng = rng,
            speed = hypot(out.velYaw, out.velPitch),
        )
        val scale = input.knobs.noiseScale
        val maxYaw = input.knobs.maxYawStep
        val maxPitch = input.knobs.maxPitchStep
        // The configured ceiling must hold on the EMITTED step, and stage-4
        // noise is added after Servo.tick already clamped the clean command.
        // Clamping the *total* is wrong: it silently drops the command's own
        // remaining travel, and because the hold gate freezes the output once
        // the command converges, any shortfall becomes a PERMANENT offset
        // (measured: 0.62 deg, ~7 gcd, frozen forever).
        //
        // So the ceiling is a per-tick BUDGET: the command spends it first and
        // the noise gets whatever is left. When the command is inside budget
        // the emitted tracks it exactly (so it still converges onto the
        // reference), and only a command that genuinely wants more than the
        // ceiling is slowed — which is what a cap is for.
        val noisedYaw = stage6(
            emitted = state.lastEmittedYaw,
            command = out.rotation.yaw,
            noise = scale * state.noise.yaw,
            budget = maxYaw,
        )
        val noisedPitch = stage6(
            emitted = state.lastEmittedPitch,
            command = out.rotation.pitch,
            noise = scale * state.noise.pitch,
            budget = maxPitch,
        ).coerceIn(-90f + gcd, 90f - gcd)
        state.lastEmittedYaw = noisedYaw
        state.lastEmittedPitch = noisedPitch

        // Stage 5 (event scheduler) already gated the path above: a dwell
        // froze output AND noise pre-quant (bit-exact holds); here the
        // motion path emits the noised rotation for stage-6 snapping.
        return Rotation(noisedYaw, noisedPitch)
    }

    /**
     * One tick of stage 6: the emitted rotation, given the clean command, the
     * stage-4 noise offset and the per-axis per-tick budget.
     *
     * `emitted + clamp(command - emitted, +/-budget) + clamp(noise, +/-room)`
     * where room is what the command did not spend. Guarantees
     * `|result - emitted| <= budget` while leaving the command in charge of
     * convergence whenever it fits the budget.
     */
    private fun stage6(emitted: Float, command: Float, noise: Float, budget: Float): Float {
        if (budget == UNCONSTRAINED) return command + noise
        val step = (command - emitted).coerceIn(-budget, budget)
        val room = (budget - abs(step)).coerceAtLeast(0f)
        return emitted + step + noise.coerceIn(-room, room)
    }

    /** Below this an input gcd is nonsense — fall back to a tiny floor. */
    private const val MIN_GCD = 1e-4f

    /** Float-lattice hygiene for the 1-gcd floor (see the hold gate). */
    private const val GCD_EPS = 1e-3f

    /**
     * Stage-5 stream separation: sched draws derive from Input.seed + this
     * salt so the stage-4 noise stream stays byte-identical (§11.7.5).
     */
    private const val SCHED_SEED_SALT = 0x9E3779B9L

    /** Stage-1 stream separation, same policy as §11.7.5. */
    private const val REACT_SEED_SALT = 0x85EBCA6BL
}
