package net.ccbluex.liquidbounce.utils.rotation.humanization

import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.utils.rotation.RotationConfigMigration
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
        customProfileControlsAreIndependent()
        shortestYawPath()
        respectsLimitsAndPitchBounds()
        sensitivityQuantizationConservesMotion()
        deadlinePolicyHonorsLimits()
        movementSpeedSamplesOncePerHandoff()
        movementSpeedOverridesAreIndependent()
        telemetryIsBoundedAndWrapAware()
        overshootCorrectionIsBoundedAndContextual()
        movingTargetDoesNotRestartEveryTick()
        balancedAcquisitionIsDistanceAware()
        completedMovementTransitionsToTracking()
        retargetingKeepsAccelerationBounded()
        requestHandoffPreservesVelocity()
        instantTransitionRebasesVelocity()
        continuouslyMovingTargetEntersTracking()
        targetPointPersistsAcrossMovingBoxes()
        disablingTargetPersistenceReleasesState()
        profileEpochReinitializesTargetPoint()
        predictionBuildsConfidenceAndRejectsTeleports()
        projectileInterceptionHandlesMotionAndFailure()
        temporaryQuantizationPlateauPreservesResidual()
        legacyRotationSettingsMigrate()
    }

    private fun deterministicReplay() {
        val first = collect(seed = 1234L)
        val second = collect(seed = 1234L)

        check(first == second) { "The same seed and inputs must produce identical trajectories" }
    }

    private fun customProfileControlsAreIndependent() {
        val profile = HumanizationProfile.custom(
            responseScale = 2.0,
            pathVariation = 0.08,
            correctionTendency = 0.6,
            targetDrift = 0.03,
        )

        check(profile.responseScale == 1.5)
        check(profile.pathVariation == 0.08)
        check(profile.correctionTendency == 0.6)
        check(profile.targetDrift == 0.03)
        check(abs(profile.overshootScale - 0.032) <= 1.0e-12)
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
            val settled = quantizer.quantize(current, current, step = 1.0, settled = true)
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

        check(RotationDeadlinePolicy.retain(100, 105, sameMovement = true) == 100)
        check(RotationDeadlinePolicy.retain(100, 95, sameMovement = true) == 95)
        check(RotationDeadlinePolicy.retain(100, null, sameMovement = true) == 100)
        check(RotationDeadlinePolicy.retain(100, 105, sameMovement = false) == 105)
    }

    private fun movementSpeedSamplesOncePerHandoff() {
        val sampler = MovementSpeedSampler()
        var yawSamples = 0
        var pitchSamples = 0

        fun resolve(override: Double? = null) = sampler.resolve(
            baseYaw = { (++yawSamples).toDouble() * 10.0 },
            basePitch = { (++pitchSamples).toDouble() * 20.0 },
            overrideYaw = override,
            overridePitch = override,
        )

        val first = resolve()
        val refreshed = resolve()
        check(first == refreshed)
        check(yawSamples == 1 && pitchSamples == 1) { "A request refresh must retain movement-level speed samples" }

        sampler.reset()
        val switched = resolve()
        check(switched != first)
        check(yawSamples == 2 && pitchSamples == 2) { "A target handoff must sample a new movement bundle" }

        val overridden = resolve(37.0)
        check(overridden == AngularSpeedLimits(37.0, 37.0))
        check(yawSamples == 2 && pitchSamples == 2) { "Explicit request speeds must not consume range samples" }
    }

    private fun movementSpeedOverridesAreIndependent() {
        val sampler = MovementSpeedSampler()
        var yawSamples = 0
        var pitchSamples = 0

        val yawOnly = sampler.resolve(
            baseYaw = { (++yawSamples * 10).toDouble() },
            basePitch = { (++pitchSamples * 20).toDouble() },
            overrideYaw = 37.0,
        )
        check(yawOnly == AngularSpeedLimits(37.0, 20.0))
        check(yawSamples == 0 && pitchSamples == 1)

        sampler.reset()
        val pitchOnly = sampler.resolve(
            baseYaw = { (++yawSamples * 10).toDouble() },
            basePitch = { (++pitchSamples * 20).toDouble() },
            overridePitch = 13.0,
        )
        check(pitchOnly == AngularSpeedLimits(10.0, 13.0))
        check(yawSamples == 1 && pitchSamples == 1)
    }

    private fun telemetryIsBoundedAndWrapAware() {
        val telemetry = RotationTelemetry(capacity = 3)

        listOf(179.0, -179.0, -178.0, -177.0).forEachIndexed { tick, yaw ->
            val point = AnglePoint(yaw, tick.toDouble())
            telemetry.record(
                tick = tick,
                seed = 7L,
                owner = "verification",
                purpose = "GENERIC",
                movementId = 1L,
                phase = MovementPhase.PRIMARY,
                deadlineStrategy = DeadlineStrategy.NORMAL,
                source = point,
                target = point,
                planned = point,
                quantized = point,
                valid = true,
            )
        }

        val samples = telemetry.snapshot()
        check(samples.size == 3 && samples.first().tick == 1) { "Telemetry must evict its oldest bounded sample" }
        check(samples.first().yawVelocity == 2.0) { "Telemetry yaw velocity must use wrapped differences" }
        check(samples.last().yawVelocity == 1.0 && samples.last().yawAcceleration == 0.0)
        check(samples.last().yawJerk == 1.0)
        check(telemetry.latest() == samples.last())
        val csv = telemetry.toCsv()
        check(csv.lineSequence().count { it.isNotEmpty() } == 4)
        check(csv.startsWith("tick,seed,owner,purpose,movementId"))

        telemetry.clear()
        check(telemetry.snapshot().isEmpty() && telemetry.latest() == null)
    }

    private fun overshootCorrectionIsBoundedAndContextual() {
        val profile = HumanizationProfile.balanced().copy(
            pathVariation = 0.0,
            driftScale = 0.0,
            correctionTendency = 1.0,
            overshootScale = 0.04,
        )
        val engine = RotationHumanizer(17L)
        val phases = mutableSetOf<MovementPhase>()
        val movementIds = mutableSetOf<Long>()
        var current = AnglePoint(0.0, 0.0)

        repeat(24) {
            val next = engine.step(
                current,
                AnglePoint(60.0, 6.0),
                maxYawSpeed = 8.0,
                maxPitchSpeed = 4.0,
                requestedProfile = profile,
                allowCorrections = true,
            )

            check(abs(next.rotation.yaw - current.yaw) <= 8.0 + 1.0e-9)
            check(abs(next.rotation.pitch - current.pitch) <= 4.0 + 1.0e-9)
            phases += next.phase
            movementIds += next.movementId
            current = next.rotation
        }

        check(MovementPhase.OVERSHOOT in phases && MovementPhase.CORRECTION in phases)
        check(MovementPhase.COMPLETE in phases)
        check(movementIds.size == 1) { "Correction must remain part of the original movement" }
        check(abs(current.yaw - 60.0) <= 1.0e-6 && abs(current.pitch - 6.0) <= 1.0e-6)

        val exactActionEngine = RotationHumanizer(17L)
        var exactCurrent = AnglePoint(0.0, 0.0)
        repeat(16) {
            val next = exactActionEngine.step(
                exactCurrent,
                AnglePoint(60.0, 6.0),
                maxYawSpeed = 8.0,
                maxPitchSpeed = 4.0,
                requestedProfile = profile,
                allowCorrections = false,
            )
            check(next.phase != MovementPhase.OVERSHOOT && next.phase != MovementPhase.CORRECTION)
            exactCurrent = next.rotation
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

    private fun balancedAcquisitionIsDistanceAware() {
        val engine = RotationHumanizer(71L)
        var current = AnglePoint(0.0, 0.0)

        repeat(3) {
            val result = engine.step(
                current,
                AnglePoint(60.0, 0.0),
                maxYawSpeed = 180.0,
                maxPitchSpeed = 180.0,
                requestedProfile = HumanizationProfile.balanced(),
            )
            check(!result.complete) { "A 60-degree Balanced acquisition must not use the old three-tick template" }
            current = result.rotation
        }
    }

    private fun completedMovementTransitionsToTracking() {
        val engine = RotationHumanizer(72L)
        var current = AnglePoint(0.0, 0.0)
        var completed: HumanizationStep? = null

        repeat(40) {
            val result = engine.step(
                current,
                AnglePoint(30.0, 4.0),
                maxYawSpeed = 180.0,
                maxPitchSpeed = 180.0,
                requestedProfile = HumanizationProfile.balanced(),
            )
            current = result.rotation
            if (result.complete) {
                completed = result
                return@repeat
            }
        }

        val settled = completed ?: error("Stationary acquisition did not complete")
        val tracked = engine.step(
            current,
            AnglePoint(50.0, 4.0),
            maxYawSpeed = 180.0,
            maxPitchSpeed = 180.0,
            requestedProfile = HumanizationProfile.balanced(),
        )

        check(tracked.movementId == settled.movementId)
        check(tracked.phase == MovementPhase.TRACKING && !tracked.complete)
        check(tracked.rotation.yaw > current.yaw && tracked.rotation.yaw < 50.0) {
            "A refreshed moving endpoint must be tracked continuously instead of snapped to: $tracked"
        }
    }

    private fun retargetingKeepsAccelerationBounded() {
        val engine = RotationHumanizer(73L)
        var current = AnglePoint(0.0, 0.0)
        var previousVelocity = AnglePoint(0.0, 0.0)

        repeat(30) { tick ->
            val target = if (tick % 2 == 0) AnglePoint(80.0, 35.0) else AnglePoint(-80.0, -35.0)
            val result = engine.step(
                current,
                target,
                maxYawSpeed = 180.0,
                maxPitchSpeed = 180.0,
                requestedProfile = HumanizationProfile.balanced(),
            )
            val velocity = result.rotation - current
            val acceleration = velocity - previousVelocity

            check(abs(acceleration.yaw) <= 7.3) { "Yaw acceleration was not bounded: $acceleration" }
            check(abs(acceleration.pitch) <= 5.3) { "Pitch acceleration was not bounded: $acceleration" }

            previousVelocity = velocity
            current = result.rotation
        }
    }

    private fun requestHandoffPreservesVelocity() {
        val engine = RotationHumanizer(74L)
        var current = AnglePoint(0.0, 0.0)
        var previousVelocity = AnglePoint(0.0, 0.0)
        var previousMovementId = -1L

        repeat(5) {
            val result = engine.step(
                current,
                AnglePoint(90.0, 10.0),
                maxYawSpeed = 180.0,
                maxPitchSpeed = 180.0,
                requestedProfile = HumanizationProfile.balanced(),
            )
            previousVelocity = result.rotation - current
            current = result.rotation
            previousMovementId = result.movementId
        }

        engine.handoff()

        val handedOff = engine.step(
            current,
            AnglePoint(-90.0, -10.0),
            maxYawSpeed = 180.0,
            maxPitchSpeed = 180.0,
            requestedProfile = HumanizationProfile.balanced(),
        )
        val velocity = handedOff.rotation - current

        check(handedOff.movementId == previousMovementId + 1)
        check(abs(velocity.yaw - previousVelocity.yaw) <= 7.3)
        check(abs(velocity.pitch - previousVelocity.pitch) <= 5.3)
    }

    private fun continuouslyMovingTargetEntersTracking() {
        val engine = RotationHumanizer(75L)
        var current = AnglePoint(0.0, 0.0)
        var enteredTracking = false

        repeat(40) { tick ->
            val result = engine.step(
                current,
                AnglePoint(30.0 + tick * 0.5, 5.0 + tick * 0.05),
                maxYawSpeed = 30.0,
                maxPitchSpeed = 20.0,
                requestedProfile = HumanizationProfile.balanced(),
            )
            check(result.complete == (result.phase == MovementPhase.COMPLETE))
            enteredTracking = enteredTracking || result.phase == MovementPhase.TRACKING
            current = result.rotation
        }

        check(enteredTracking) { "A continuously moving target must leave acquisition and enter TRACKING" }
    }

    private fun instantTransitionRebasesVelocity() {
        val engine = RotationHumanizer(76L)
        var current = AnglePoint(0.0, 0.0)
        var movementId = -1L

        repeat(4) {
            val result = engine.step(
                current,
                AnglePoint(-90.0, 0.0),
                maxYawSpeed = 20.0,
                maxPitchSpeed = 20.0,
                requestedProfile = HumanizationProfile.balanced(),
            )
            current = result.rotation
            movementId = result.movementId
        }

        val snapped = AnglePoint(45.0, 0.0)
        engine.interrupt(snapped)
        engine.handoff()
        val resumed = engine.step(
            snapped,
            AnglePoint(30.0, 0.0),
            maxYawSpeed = 20.0,
            maxPitchSpeed = 20.0,
            requestedProfile = HumanizationProfile.balanced(),
        )

        check(resumed.movementId == movementId + 1)
        check(resumed.rotation.yaw < snapped.yaw) {
            "Normal motion after an instant snap must move toward the new target, not inherit the snap velocity"
        }
        check(abs(resumed.rotation.yaw - snapped.yaw) <= 20.0)
    }

    private fun targetPointPersistsAcrossMovingBoxes() {
        val tracker = TargetPointTracker(91L)
        val key = TargetPointKey("combat", 12)
        val fallback = NormalizedTargetPoint(0.2, 0.8, 0.7)

        val first = tracker.pointFor(key, fallback, 0.1..0.9, 0.5..0.9, variation = 0.04, tick = 0)
        val refreshed = tracker.pointFor(
            key,
            NormalizedTargetPoint(0.8, 0.5, 0.1),
            0.1..0.9,
            0.5..0.9,
            variation = 0.04,
            tick = 0,
        )

        check(first == refreshed) { "Repeated resolution in one tick must retain the normalized target point" }
        check(first.x in 0.125..0.875 && first.y in 0.525..0.875 && first.z in 0.125..0.875) {
            "Humanized acquisition must stay inside the inset safe region"
        }

        val drifted = (1..100).fold(first) { _, tick ->
            tracker.pointFor(key, fallback, 0.1..0.9, 0.5..0.9, variation = 0.04, tick = tick)
        }
        check(drifted != first) { "An active humanized target should drift slowly instead of remaining frozen" }
        check(drifted.x in 0.1..0.9 && drifted.y in 0.5..0.9 && drifted.z in 0.1..0.9)

        val replay = TargetPointTracker(91L)
        var replayed = replay.pointFor(key, fallback, 0.1..0.9, 0.5..0.9, variation = 0.04, tick = 0)
        repeat(100) { tick ->
            replayed = replay.pointFor(key, fallback, 0.1..0.9, 0.5..0.9, variation = 0.04, tick = tick + 1)
        }
        check(replayed == drifted) { "Target drift must replay deterministically from its session seed" }

        val switched = tracker.pointFor(
            TargetPointKey("combat", 13),
            NormalizedTargetPoint(0.5, 0.6, 0.5),
            0.2..0.8,
            0.5..0.7,
            variation = 0.0,
            tick = 100,
        )
        check(switched == NormalizedTargetPoint(0.5, 0.6, 0.5))

        val retained = tracker.pointFor(key, fallback, 0.1..0.9, 0.5..0.9, variation = 0.04, tick = 100)
        check(retained == drifted) { "Alternating producers must retain independent target-point state" }
    }

    private fun disablingTargetPersistenceReleasesState() {
        val tracker = TargetPointTracker(92L)
        val key = TargetPointKey("combat", 4)
        tracker.pointFor(
            key,
            NormalizedTargetPoint(0.2, 0.8, 0.2),
            0.0..1.0,
            0.0..1.0,
            variation = 0.04,
            tick = 1,
        )
        tracker.release(key)

        val fallback = NormalizedTargetPoint(0.8, 0.4, 0.8)
        val disabled = tracker.pointFor(key, fallback, 0.0..1.0, 0.0..1.0, variation = 0.0, tick = 2)
        check(disabled == fallback) { "Disabling persistence must not retain a randomized point" }
    }

    private fun profileEpochReinitializesTargetPoint() {
        val tracker = TargetPointTracker(93L)
        val key = TargetPointKey("combat", 5)
        val first = tracker.pointFor(
            key,
            NormalizedTargetPoint(0.2, 0.8, 0.2),
            0.0..1.0,
            0.0..1.0,
            variation = 0.0,
            tick = 1,
            epoch = TargetPointEpoch(HumanizationProfile.subtle()),
        )
        val changed = tracker.pointFor(
            key,
            NormalizedTargetPoint(0.8, 0.4, 0.8),
            0.0..1.0,
            0.0..1.0,
            variation = 0.0,
            tick = 2,
            epoch = TargetPointEpoch(HumanizationProfile.balanced()),
        )

        check(first == NormalizedTargetPoint(0.2, 0.8, 0.2))
        check(changed == NormalizedTargetPoint(0.8, 0.4, 0.8))

        val changedRange = tracker.pointFor(
            key,
            NormalizedTargetPoint(0.55, 0.65, 0.55),
            0.5..0.6,
            0.6..0.7,
            variation = 0.0,
            tick = 3,
            epoch = TargetPointEpoch(HumanizationProfile.balanced()),
        )
        check(changedRange == NormalizedTargetPoint(0.55, 0.65, 0.55))
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
        val expectedIntercept = MotionVector(10.0, 0.0, 0.0) + MotionVector(0.1, 0.0, 0.0) * movingAway.flightTicks
        check((movingAway.relativeIntercept - expectedIntercept).length <= 0.01)

        val unreachable = ProjectileInterceptSolver.solve(
            relativePosition = MotionVector(10.0, 100.0, 0.0),
            relativeVelocity = MotionVector.ZERO,
            projectileSpeed = 1.0,
            gravity = 0.006,
        )
        check(unreachable == null)
    }

    private fun temporaryQuantizationPlateauPreservesResidual() {
        val quantizer = SensitivityQuantizer()
        val current = AnglePoint(0.0, 0.0)
        val first = quantizer.quantize(current, AnglePoint(0.4, 0.0), step = 1.0)
        check(first == current)

        val plateau = quantizer.quantize(first, AnglePoint(0.4, 0.0), step = 1.0)
        check(plateau == current)

        val resumed = quantizer.quantize(plateau, AnglePoint(0.7, 0.0), step = 1.0)
        check(resumed.yaw == 1.0) { "An active plateau must retain sub-GCD residual motion" }

        quantizer.quantize(resumed, AnglePoint(0.7, 0.0), step = 1.0, settled = true)

        quantizer.reset()
        val settledSubStep = quantizer.quantize(current, AnglePoint(0.4, 0.0), step = 1.0, settled = true)
        val afterSettle = quantizer.quantize(settledSubStep, AnglePoint(0.7, 0.0), step = 1.0)
        check(afterSettle == settledSubStep) { "A confirmed endpoint must discard residual immediately" }
    }

    private fun legacyRotationSettingsMigrate() {
        val aimbot = JsonObject().apply {
            addProperty("Legitimize", true)
            addProperty("PredictEnemyPosition", 1.5)
        }
        RotationConfigMigration.migrate("Aimbot", aimbot)
        check(aimbot["Humanization"].asString == "Balanced")
        check(aimbot["PredictionHorizon"].asDouble == 3.5)

        val killAura = JsonObject().apply {
            addProperty("RandomizationPattern", "Zig-Zag")
        }
        RotationConfigMigration.migrate("KillAura", killAura)
        check(killAura["Humanization"].asString == "Balanced")

        val disabled = JsonObject().apply { addProperty("Legitimize", false) }
        RotationConfigMigration.migrate("Aimbot", disabled)
        check(disabled["Humanization"].asString == "Off")

        val current = JsonObject().apply {
            addProperty("Humanization", "Custom")
            addProperty("Legitimize", true)
        }
        RotationConfigMigration.migrate("Aimbot", current)
        check(current["Humanization"].asString == "Custom")

        val nested = JsonObject().apply {
            add("RotationSettings", JsonObject().apply { addProperty("Legitimize", true) })
        }
        RotationConfigMigration.migrate("KillAura", nested)
        check(nested["Humanization"].asString == "Balanced")
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
