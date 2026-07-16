package net.ccbluex.liquidbounce.utils.rotation.humanization

import net.ccbluex.liquidbounce.utils.rotation.prediction.MotionVector
import net.ccbluex.liquidbounce.utils.rotation.prediction.ProjectileInterceptSolver
import net.ccbluex.liquidbounce.utils.rotation.prediction.TargetMotionEstimator
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
        sensitivityQuantizationConservesMotion()
        deadlinePolicyHonorsLimits()
        movingTargetDoesNotRestartEveryTick()
        targetPointPersistsAcrossMovingBoxes()
        predictionBuildsConfidenceAndRejectsTeleports()
        projectileInterceptionHandlesMotionAndFailure()
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

    private fun sensitivityQuantizationConservesMotion() {
        val quantizer = SensitivityQuantizer()
        var current = AnglePoint(0.0, 0.0)

        repeat(20) { tick ->
            val next = quantizer.quantize(
                current = current,
                desired = AnglePoint((tick + 1) * 0.2, (tick + 1) * 0.1),
                step = 1.0,
            )

            check(abs(next.yaw - current.yaw - (next.yaw - current.yaw).toInt()) <= 1.0e-9)
            check(abs(next.pitch - current.pitch - (next.pitch - current.pitch).toInt()) <= 1.0e-9)
            check(next.yaw >= current.yaw && next.pitch >= current.pitch) {
                "Monotone planned motion must not oscillate after quantization"
            }
            current = next
        }

        check(current.yaw in 3.5..4.5) { "Sub-step yaw motion must not be rounded away forever: $current" }
        check(current.pitch in 1.5..2.5) { "Sub-step pitch motion must not be rounded away forever: $current" }

        repeat(5) {
            val settled = quantizer.quantize(current, current, step = 1.0)
            check(settled == current) { "A settled endpoint must not drift from stale quantization error" }
        }

        quantizer.reset()
        val wrapped = quantizer.quantize(AnglePoint(179.0, 0.0), AnglePoint(-179.0, 0.0), step = 1.0)
        check(wrapped.yaw == 181.0) { "Quantization must retain the shortest wrapped yaw path: $wrapped" }

        quantizer.reset()
        val pitchLimited = quantizer.quantize(AnglePoint(0.0, 89.4), AnglePoint(0.0, 100.0), step = 1.0)
        check(pitchLimited.pitch == 89.4) { "Pitch bounds must not create a fractional mouse step: $pitchLimited" }
    }

    private fun deadlinePolicyHonorsLimits() {
        val current = AnglePoint(179.0, 0.0)

        check(
            RotationDeadlinePolicy.choose(
                current,
                AnglePoint(-179.0, 1.0),
                maxYawStep = 1.0,
                maxPitchStep = 1.0,
                sensitivityStep = 1.0,
                deadlineReached = false,
            ) == DeadlineStrategy.NORMAL
        )
        check(
            RotationDeadlinePolicy.choose(
                current,
                AnglePoint(-179.0, 1.0),
                maxYawStep = 1.0,
                maxPitchStep = 1.0,
                sensitivityStep = 1.0,
                deadlineReached = true,
            ) == DeadlineStrategy.DIRECT
        ) { "A deadline may snap only when the wrapped target is within the hard limits plus one GCD" }
        check(
            RotationDeadlinePolicy.choose(
                current,
                AnglePoint(-120.0, 20.0),
                maxYawStep = 10.0,
                maxPitchStep = 5.0,
                sensitivityStep = 1.0,
                deadlineReached = true,
            ) == DeadlineStrategy.DETERMINISTIC
        ) { "An unreachable deadline must fall back to bounded deterministic travel" }
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

    private fun targetPointPersistsAcrossMovingBoxes() {
        val tracker = TargetPointTracker(91L)
        val key = TargetPointKey("combat", 12)
        val fallback = NormalizedTargetPoint(0.2, 0.8, 0.7)

        val first = tracker.pointFor(key, fallback, 0.1..0.9, 0.5..0.9, variation = 0.04)
        val refreshed = tracker.pointFor(
            key,
            NormalizedTargetPoint(0.8, 0.5, 0.1),
            0.1..0.9,
            0.5..0.9,
            variation = 0.04,
        )

        check(first == refreshed) { "A refreshed box must retain the same normalized target point" }
        check(first.x in 0.1..0.9 && first.y in 0.5..0.9 && first.z in 0.1..0.9)

        val switched = tracker.pointFor(
            TargetPointKey("combat", 13),
            NormalizedTargetPoint(0.5, 0.6, 0.5),
            0.2..0.8,
            0.5..0.7,
            variation = 0.0,
        )
        check(switched == NormalizedTargetPoint(0.5, 0.6, 0.5))
    }

    private fun predictionBuildsConfidenceAndRejectsTeleports() {
        val estimator = TargetMotionEstimator()
        var prediction = estimator.predict(
            entityId = 4,
            current = MotionVector(1.0, 0.0, 0.0),
            previous = MotionVector(0.0, 0.0, 0.0),
            tick = 1,
            horizonTicks = 2.0,
        )
        check(prediction.confidence == 0.25)

        for (tick in 2..4) {
            prediction = estimator.predict(
                entityId = 4,
                current = MotionVector(tick.toDouble(), 0.0, 0.0),
                previous = MotionVector((tick - 1).toDouble(), 0.0, 0.0),
                tick = tick,
                horizonTicks = 2.0,
            )
        }

        check(prediction.confidence == 1.0)
        check(prediction.offset.x in 1.99..2.01)

        val afterTeleport = estimator.predict(
            entityId = 4,
            current = MotionVector(100.0, 0.0, 0.0),
            previous = MotionVector(4.0, 0.0, 0.0),
            tick = 5,
            horizonTicks = 2.0,
        )
        check(afterTeleport.confidence == 0.0)
        check(afterTeleport.offset == MotionVector.ZERO)
    }

    private fun projectileInterceptionHandlesMotionAndFailure() {
        val stationary = ProjectileInterceptSolver.solve(
            relativePosition = MotionVector(10.0, 0.0, 0.0),
            relativeVelocity = MotionVector.ZERO,
            projectileSpeed = 1.0,
            gravity = 0.006,
        ) ?: error("A nearby stationary target must have a ballistic solution")

        check(stationary.yaw in -90.01..-89.99)
        check(stationary.pitch < 0.0)
        check(stationary.flightTicks > 10.0)

        val movingAway = ProjectileInterceptSolver.solve(
            relativePosition = MotionVector(10.0, 0.0, 0.0),
            relativeVelocity = MotionVector(0.1, 0.0, 0.0),
            projectileSpeed = 1.0,
            gravity = 0.006,
        ) ?: error("A slowly moving target must have an intercept solution")

        check(movingAway.relativeIntercept.x > stationary.relativeIntercept.x)
        check(movingAway.flightTicks > stationary.flightTicks)

        val unreachable = ProjectileInterceptSolver.solve(
            relativePosition = MotionVector(10.0, 100.0, 0.0),
            relativeVelocity = MotionVector.ZERO,
            projectileSpeed = 1.0,
            gravity = 0.006,
        )
        check(unreachable == null)
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
