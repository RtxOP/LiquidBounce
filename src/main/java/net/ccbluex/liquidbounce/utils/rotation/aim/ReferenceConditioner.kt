package net.ccbluex.liquidbounce.utils.rotation.aim

import net.ccbluex.liquidbounce.utils.rotation.Rotation

/**
 * Stage 1 — reference conditioner (spec §11.2, invariant I1):
 *  - **branch continuity:** the reference is kept on the current 360° branch
 *    of the output (structural port of the old wrap logic);
 *  - **engage edge:** arms the reaction-delay knob (UNFITTED, default 0) for
 *    the first plan after an edge only — never mid-pursuit;
 *  - **no other thresholds live here** — error gating is exactly the
 *    `>= 1 gcd` quantization floor enforced in [AimEngine].
 */
internal object ReferenceConditioner {

    /** Conditions the reference, records the engage edge, and tracks the
     *  reference's current motion run (epoch-equivalent displacement). */
    fun condition(
        state: AimEngine.State,
        current: Rotation,
        reference: Rotation,
        delayTicks: Int,
    ): Rotation {
        if (!state.prevEngaged) {
            // Engage edge (the driver resets state on disengage).
            state.reactRemaining = delayTicks
            state.prevEngaged = true
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
