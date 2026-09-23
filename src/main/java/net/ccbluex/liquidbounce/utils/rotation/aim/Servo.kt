package net.ccbluex.liquidbounce.utils.rotation.aim

import net.ccbluex.liquidbounce.utils.rotation.Rotation
import kotlin.math.hypot

/**
 * Stage 3 — jerk-limited servo (spec §11.2, §11.2-A amendment):
 *  - moves **every** tick while the error is `>= 1 gcd` — this stage has no
 *    hold states at all (invariant I1);
 *  - target velocity = plan profile bin along the **current** error vector
 *    (reactive err-chase) **+ feedforward of the reference's own tick
 *    velocity**: in steady state the error self-stabilises at ≈refVel and
 *    the clamp binds, so the engine transmits the reference's velocity
 *    structure — human profile shape (min-jerk), lag-1, and flick chains —
 *    instead of merely reacting to position error; after a reference pause
 *    the FF term vanishes and pure err-chase closes the residual;
 *  - per-tick acceleration bounded by [MotorPlan.maxAccel], which also
 *    allows acquiring the reference velocity within a fixed ramp;
 *  - displacement clamped to the error: motion can never overshoot the
 *    reference (end-flip stays near the human 0.024).
 *
 * Returns the next **pre-quant** rotation plus the velocity actually
 * achieved (post-clamp) so ramps always restart from reality.
 */
internal object Servo {

    data class Output(val rotation: Rotation, val velYaw: Float, val velPitch: Float)

    /**
     * Reference-velocity feedforward gain. 1.0 = transmit the reference's
     * tick velocity verbatim; the profile term only shapes ramps and
     * residual closing. Architectural, not fitted (§11.2-A).
     */
    private const val FEEDFORWARD = 1f

    fun tick(
        current: Rotation,
        plan: MotorPlan.Plan,
        age: Int,
        errYaw: Float,
        errPitch: Float,
        velYaw: Float,
        velPitch: Float,
        refVelYaw: Float,
        refVelPitch: Float,
    ): Output {
        val amp = hypot(errYaw, errPitch)
        val moving = amp > 1e-9f
        val target = if (moving) MotorPlan.speedAt(plan, age) else 0f
        val wantYaw = (if (moving) errYaw / amp * target else 0f) +
            FEEDFORWARD * refVelYaw
        val wantPitch = (if (moving) errPitch / amp * target else 0f) +
            FEEDFORWARD * refVelPitch

        val maxDelta = MotorPlan.maxAccel(plan, hypot(refVelYaw, refVelPitch))
        var nextYaw = velYaw + (wantYaw - velYaw).coerceIn(-maxDelta, maxDelta)
        var nextPitch = velPitch + (wantPitch - velPitch).coerceIn(-maxDelta, maxDelta)

        // Hard backstop: the fitted human maximum per-tick speed.
        val mag = hypot(nextYaw, nextPitch)
        val cap = FittedAimParams.SERVO_SPEED_MAX
        if (mag > cap && mag > 0f) {
            val k = cap / mag
            nextYaw *= k
            nextPitch *= k
        }

        // Never travel past the reference: toward(err), clamped to |err|.
        val dispYaw = toward(nextYaw, errYaw)
        val dispPitch = toward(nextPitch, errPitch)

        return Output(
            rotation = Rotation(current.yaw + dispYaw, current.pitch + dispPitch),
            velYaw = dispYaw,
            velPitch = dispPitch,
        )
    }

    /** Clamp a velocity to a displacement that moves toward, and stops at, the error. */
    private fun toward(vel: Float, err: Float): Float = when {
        err > 0f -> vel.coerceIn(0f, err)
        err < 0f -> vel.coerceIn(err, 0f)
        else -> 0f
    }
}
