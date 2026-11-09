package net.ccbluex.liquidbounce.utils.rotation.aim

import net.ccbluex.liquidbounce.utils.rotation.Rotation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The humanize path must honour the configured per-axis ceiling.
 *
 * The bug this pins: `HorizontalAngleChange` / `VerticalAngleChange` were read
 * only by the legacy `performAngleChange` path. With `humanize = true` the
 * engine ran on fitted parameters and ignored them entirely — while
 * RotationRecorder kept enforcing them, so a live KillAura session configured
 * at h_max = 20 emitted up to 107.5 deg/tick and the recorder correctly
 * self-reported `verdict: FAIL` with 538 cap violations.
 */
class SpeedCapHonouredTest {

    private val gcd = 0.09143358f

    /** Drives a large acquisition and returns the largest per-tick step seen. */
    private fun maxStep(knobs: AimEngine.Knobs, amplitude: Float = 120f): Float {
        val state = AimEngine.State()
        var cur = Rotation(0f, 0f)
        var worst = 0f
        repeat(400) { t ->
            val next = AimEngine.step(
                AimEngine.Input(
                    current = cur,
                    reference = Rotation(amplitude, 0f),
                    engaged = true,
                    tick = t,
                    gcd = gcd,
                    seed = 3L,
                    knobs = knobs,
                    freshTarget = t == 0,
                ),
                state,
            )
            worst = maxOf(worst, abs(next.yaw - cur.yaw), abs(next.pitch - cur.pitch))
            cur = next
        }
        return worst
    }

    @Test
    fun unconstrainedByDefault() {
        assertTrue(maxStep(AimEngine.Knobs()) > 20f) {
            "with no ceiling configured the engine must be free to exceed " +
                "20 deg/tick (its fitted p99 is 26.4)"
        }
    }

    @Test
    fun configuredCeilingIsHonouredOnBothAxes() {
        for (cap in floatArrayOf(20f, 8f, 3f, 1f)) {
            val worst = maxStep(AimEngine.Knobs(maxYawStep = cap, maxPitchStep = cap))
            // One gcd of slack for the stage-6 quantiser, same tolerance the
            // recorder's caps check allows.
            assertTrue(worst <= cap + gcd) {
                "configured ceiling $cap deg/tick exceeded: emitted $worst deg/tick"
            }
        }
    }

    @Test
    fun ceilingAppliesPerAxisNotAsAMagnitude() {
        // A tight yaw ceiling with a loose pitch ceiling must not be satisfied
        // by clamping the combined magnitude: the pitch axis keeps its budget.
        val knobs = AimEngine.Knobs(maxYawStep = 2f, maxPitchStep = 60f)
        val state = AimEngine.State()
        var cur = Rotation(0f, 0f)
        var worstYaw = 0f
        var worstPitch = 0f
        repeat(300) { t ->
            val next = AimEngine.step(
                AimEngine.Input(cur, Rotation(90f, 40f), true, t, gcd, 5L, knobs,
                    freshTarget = t == 0),
                state,
            )
            worstYaw = maxOf(worstYaw, abs(next.yaw - cur.yaw))
            worstPitch = maxOf(worstPitch, abs(next.pitch - cur.pitch))
            cur = next
        }
        assertTrue(worstYaw <= 2f + gcd) { "yaw exceeded its 2 deg/tick ceiling: $worstYaw" }
        assertTrue(worstPitch > 2f) {
            "pitch was clamped to the yaw ceiling ($worstPitch) - the limit " +
                "must be per axis, not a combined magnitude"
        }
    }

    @Test
    fun ceilingDoesNotBreakConvergence() {
        // A tight cap must still reach the reference (slower, never stuck).
        val state = AimEngine.State()
        var cur = Rotation(0f, 0f)
        repeat(2000) { t ->
            cur = AimEngine.step(
                AimEngine.Input(cur, Rotation(60f, 0f), true, t, gcd, 9L,
                    AimEngine.Knobs(maxYawStep = 4f, maxPitchStep = 4f),
                    freshTarget = t == 0),
                state,
            )
        }
        assertTrue(abs(cur.yaw - 60f) <= gcd * 2f) {
            "engine stalled ${abs(cur.yaw - 60f)} deg from the reference under a " +
                "4 deg/tick ceiling"
        }
    }
}
