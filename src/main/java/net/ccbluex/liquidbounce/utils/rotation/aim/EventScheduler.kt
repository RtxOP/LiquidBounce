package net.ccbluex.liquidbounce.utils.rotation.aim

import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random

/**
 * Stage 5 — event scheduler (spec §11.2 stage 5 + invariant I2).
 *
 * NOT A SHAPING STAGE. After each completed epoch (the servo converging into
 * a hold) this draws a geometric dwell with p_eff = clamp(p · scale, 0.01,
 * 0.95), p = 1 / SCHED_DWELL_MEAN — the population mean of the draw equals
 * the fitted mean, p90 tracks the fitted p90 (§11.4).
 *
 * It is deliberately NOT mounted as a knob. By construction the draw cannot
 * gate motion: I1 already freezes the output inside the 1-gcd floor, and I2
 * preempts any dwell on the very tick the error reaches 1 gcd. The remainder
 * is therefore write-only cadence bookkeeping, and exposing it as
 * `EventRateScale` shipped a config slider that provably did not change a
 * single emitted byte. Kept as the fitted descriptor of human dwell cadence
 * (recorder + tests read it); see [AimEngine.Knobs] and
 * `KnobsObservabilityTest`.
 *
 * Observable-behavior contract (why this stage is nearly free):
 *  - I2 preempts any active dwell the same tick error reaches ≥ 1 gcd on
 *    either axis ([preempt]) — a draw can never delay motion, so catch-up
 *    snaps stay structurally impossible;
 *  - I1 keeps output frozen inside the 1-gcd floor anyway, so a draw can
 *    never extend a hold past reference quiescence either.
 * The drawn remainder therefore exists as cadence bookkeeping + the
 * EventRateScale mount + the seeded unit contract (§11.7.1); long idle
 * stretches (> p90) come from unengaged/converged holds, not the sampler.
 *
 * Draws come from a stream of their own, derived from the same
 * `Input.seed`, so stage-4 noise bytes stay scheduler-independent
 * (determinism policy §11.7.5: byte-identical output per seed).
 */
internal object EventScheduler {

    /**
     * The only scale the engine uses: 1.0, i.e. the fitted cadence exactly.
     * Present so the sampler stays reusable in tests without implying a
     * user-tunable behaviour the invariants cannot deliver.
     */
    const val FITTED_SCALE = 1f

    /** Mutable sampler memory; lives in AimEngine.State, cleared on reset. */
    class State {
        /** Ticks left in the drawn dwell (bookkeeping — see contract above). */
        var dwellRemaining = 0
    }

    /** Dwell exit probability: p_eff = clamp(p · scale, 0.01, 0.95). */
    fun pEff(eventRateScale: Float): Float {
        val p = 1f / FittedAimParams.SCHED_DWELL_MEAN
        return (p * eventRateScale).coerceIn(0.01f, 0.95f)
    }

    /**
     * Inverse-CDF geometric draw on support {1, 2, ...}:
     * k = floor(ln(1−u) / ln(1−p_eff)) + 1, u ~ U[0, 1). Population
     * mean = 1/p_eff; bounded by float precision (≤ ~1650 at the clamp).
     */
    fun drawDwell(rng: Random, eventRateScale: Float): Int {
        val p = pEff(eventRateScale)
        val u = rng.nextFloat()
        val k = floor(ln(1f - u) / ln(1f - p)) + 1f
        return k.toInt().coerceAtLeast(1)
    }

    /** Start a dwell on epoch completion (hold entry): store the draw. */
    fun begin(state: State, rng: Random, eventRateScale: Float) {
        state.dwellRemaining = drawDwell(rng, eventRateScale)
    }

    /** I2: any error ≥ 1 gcd preempts the dwell the same tick. */
    fun preempt(state: State) {
        state.dwellRemaining = 0
    }
}
