package net.ccbluex.liquidbounce.utils.rotation.humanization

import kotlin.math.abs

/**
 * Dependency-free verification harness for the pure rotation engine.
 *
 * This intentionally uses [check] instead of a test framework so the math core can be verified without Minecraft or
 * additional test dependencies. It can later be converted to property tests when the project's test source set is
 * formalized.
 */
object RotationHumanizerVerification {

    @JvmStatic
    fun main(args: Array<String>) {
        deterministicReplay()
        shortestYawPath()
        respectsLimitsAndPitchBounds()
        movingTargetDoesNotRestartEveryTick()
    }

    private fun deterministicReplay() {
        val first = collect(seed = 1234L)
        val second = collect(seed = 1234L)

        check(first == second) { "The same seed and inputs must produce identical trajectories" }
    }

    private fun shortestYawPath() {
        val engine = RotationHumanizer(1L)
        var current = AnglePoint(179.0, 0.0)

        repeat(8) {
            current = engine.step(
                current,
                AnglePoint(-179.0, 0.0),
                maxYawSpeed = 5.0,
                maxPitchSpeed = 5.0,
                requestedProfile = HumanizationProfile.subtle(),
            ).rotation
        }

        check(current.yaw in 179.0..182.0) { "Yaw must take the short path across the wrap boundary: $current" }
    }

    private fun respectsLimitsAndPitchBounds() {
        val engine = RotationHumanizer(9L)
        var current = AnglePoint(0.0, 80.0)

        repeat(30) {
            val next = engine.step(
                current,
                AnglePoint(170.0, 120.0),
                maxYawSpeed = 7.0,
                maxPitchSpeed = 3.0,
                requestedProfile = HumanizationProfile.balanced(),
            ).rotation

            check(abs(next.yaw - current.yaw) <= 7.0 + 1.0e-9)
            check(abs(next.pitch - current.pitch) <= 3.0 + 1.0e-9)
            check(next.pitch in -90.0..90.0)
            check(next.yaw.isFinite() && next.pitch.isFinite())
            current = next
        }
    }

    private fun movingTargetDoesNotRestartEveryTick() {
        val engine = RotationHumanizer(55L)
        var current = AnglePoint(0.0, 0.0)
        var movementId = -1L

        repeat(8) { tick ->
            val result = engine.step(
                current,
                AnglePoint(30.0 + tick * 0.2, 5.0),
                maxYawSpeed = 8.0,
                maxPitchSpeed = 8.0,
                requestedProfile = HumanizationProfile.balanced(),
            )

            if (movementId == -1L) movementId = result.movementId
            check(result.movementId == movementId) { "Small target updates must retarget the active movement" }
            current = result.rotation
        }
    }

    private fun collect(seed: Long): List<HumanizationStep> {
        val engine = RotationHumanizer(seed)
        var current = AnglePoint(0.0, 0.0)
        return buildList {
            repeat(12) {
                val result = engine.step(
                    current,
                    AnglePoint(60.0, 12.0),
                    maxYawSpeed = 10.0,
                    maxPitchSpeed = 6.0,
                    requestedProfile = HumanizationProfile.balanced(),
                )
                add(result)
                current = result.rotation
            }
        }
    }
}
