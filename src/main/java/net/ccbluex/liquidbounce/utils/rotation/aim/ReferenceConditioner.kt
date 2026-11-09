package net.ccbluex.liquidbounce.utils.rotation.aim

import net.ccbluex.liquidbounce.utils.rotation.Rotation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Stage 1 — reference conditioner (spec §11.2, invariant I1):
 *  - **branch continuity:** the reference is kept on the current 360° branch
 *    of the output (structural port of the old wrap logic);
 *  - **acquisition edge:** arms the reaction-delay knob (UNFITTED, default 0)
 *    on the tick a NEW target is acquired — never mid-pursuit. Driven by
 *    [AimEngine.Input.freshTarget] (target identity from the adapter), not by
 *    an `engaged` transition: the adapter holds `engaged = true` on every
 *    requested tick, so a transition edge could only ever fire once per
 *    channel and re-armed on lag spikes.
 *  - **no other thresholds live here** — error gating is exactly the
 *    `>= 1 gcd` quantization floor enforced in [AimEngine].
 */
internal object ReferenceConditioner {

    /** Conditions the reference, records the acquisition edge, and tracks the
     *  reference's current motion run (epoch-equivalent displacement). */
    fun condition(
        state: AimEngine.State,
        current: Rotation,
        reference: Rotation,
        delayMs: Int,
        reactRng: Random,
        freshTarget: Boolean,
    ): Rotation {
        if (freshTarget && delayMs > 0) {
            // One lognormal draw per acquisition. A constant delay would be
            // as machine-like as no delay at all - the flagged session
            // answered every one of 3,330 switches in exactly 1 tick
            // (stdev 0.31), which is a signature on its own. A delay already
            // in flight is not extended by a later acquisition.
            val ticks = sampleDelayTicks(delayMs, reactRng)
            state.reactRemaining = maxOf(state.reactRemaining, ticks)
        }
        val conditioned = Rotation(current.yaw + wrap180(reference.yaw - current.yaw), reference.pitch)

        // Motion-run tracking: reset on a full reference pause (both axes
        // exactly still = a human dwell, features "epoch" boundary),
        // otherwise accumulate the wrapped deltas.
        if (!state.prevRefValid) {
            state.prevRefValid = true
            state.refRunYaw = 0f
            state.refRunPitch = 0f
        } else {
            val dy = wrap180(conditioned.yaw - state.prevRefYaw)
            val dp = conditioned.pitch - state.prevRefPitch
            state.refVelYaw = dy
            state.refVelPitch = dp
            if (dy == 0f && dp == 0f) {
                state.refRunYaw = 0f
                state.refRunPitch = 0f
            } else {
                state.refRunYaw += dy
                state.refRunPitch += dp
            }
        }
        state.prevRefYaw = conditioned.yaw
        state.prevRefPitch = conditioned.pitch
        return conditioned
    }

    /**
     * Lognormal acquisition latency in ticks (20 TPS), mean [meanMs].
     *
     * `exp(mu + sigma*z)` with mu/sigma chosen so the MEAN equals meanMs, so
     * the knob reads as a human-facing "typical reaction time" rather than a
     * median that silently sits below it. Clamped to [MAX_DELAY_MS] because
     * the curve's right tail is unbounded and a multi-second aim freeze is a
     * worse artefact than a slightly late correction.
     */
    fun sampleDelayTicks(meanMs: Int, rng: Random): Int {
        val mean = meanMs.coerceAtLeast(1).toDouble()
        val cv = AimEngine.REACTION_PRIOR_CV.toDouble()
        val sigma2 = ln(1.0 + cv * cv)
        val sigma = sqrt(sigma2)
        val mu = ln(mean) - 0.5 * sigma2
        // Box-Muller from the seeded stream; the +/- pair is discarded so the
        // draw count stays one normal per acquisition.
        val u1 = (rng.nextDouble()).coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
        val ms = exp(mu + sigma * z).coerceIn(1.0, MAX_DELAY_MS.toDouble())
        return ((ms + 24.999) / 50.0).toInt().coerceAtLeast(1)
    }

    private const val MAX_DELAY_MS = 600

    /** False while the (optional, default-off) reaction delay is pending. */
    fun motionAllowed(state: AimEngine.State): Boolean {
        if (state.reactRemaining > 0) {
            state.reactRemaining--
            return false
        }
        return true
    }

    /** Signed shortest delta to [target] from [current] on the yaw circle. */
    fun wrap180(delta: Float): Float {
        var d = (delta + 180f) % 360f
        if (d < 0f) d += 360f
        return d - 180f
    }
}
