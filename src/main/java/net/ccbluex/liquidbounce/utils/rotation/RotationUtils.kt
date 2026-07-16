/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation

import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.features.module.modules.combat.FastBow
import net.ccbluex.liquidbounce.features.module.modules.render.Rotations
import net.ccbluex.liquidbounce.utils.block.block
import net.ccbluex.liquidbounce.utils.client.MinecraftInstance
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.ClientUtils.runTimeTicks
import net.ccbluex.liquidbounce.utils.client.rotation
import net.ccbluex.liquidbounce.utils.extensions.*
import net.ccbluex.liquidbounce.utils.inventory.InventoryUtils
import net.ccbluex.liquidbounce.utils.kotlin.RandomUtils.nextDouble
import net.ccbluex.liquidbounce.utils.rotation.RaycastUtils.raycastEntity
import net.ccbluex.liquidbounce.utils.rotation.humanization.AnglePoint
import net.ccbluex.liquidbounce.utils.rotation.humanization.NormalizedTargetPoint
import net.ccbluex.liquidbounce.utils.rotation.humanization.RotationHumanizer
import net.ccbluex.liquidbounce.utils.rotation.humanization.SensitivityQuantizer
import net.ccbluex.liquidbounce.utils.rotation.humanization.TargetPointKey
import net.ccbluex.liquidbounce.utils.rotation.humanization.TargetPointTracker
import net.ccbluex.liquidbounce.utils.rotation.prediction.MotionVector
import net.ccbluex.liquidbounce.utils.rotation.prediction.MotionPrediction
import net.ccbluex.liquidbounce.utils.rotation.prediction.ProjectileInterceptSolver
import net.ccbluex.liquidbounce.utils.rotation.prediction.TargetMotionEstimator
import net.minecraft.entity.Entity
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.tileentity.TileEntity
import net.minecraft.util.*
import javax.vecmath.Vector2f
import kotlin.math.*

object RotationUtils : MinecraftInstance, Listenable {

    private val humanizationSeed = java.util.Random().nextLong()
    private val humanizer = RotationHumanizer(humanizationSeed)
    private val sensitivityQuantizer = SensitivityQuantizer()
    private val targetPointTracker = TargetPointTracker(humanizationSeed xor 0x5DEECE66DL)
    private val targetMotionEstimator = TargetMotionEstimator()

    /**
     * Our final rotation point, which [currentRotation] follows.
     */
    private var targetRotation: Rotation? = null

    /** The rich request currently owning the global rotation pipeline. */
    private var activeRequest: RotationRequest? = null

    /** Prevents an immediate request from advancing twice in one logical rotation update. */
    private var skipNextRotationUpdate = false

    /** True after quantization has handed off from target travel to camera reset travel. */
    private var quantizingReset = false

    /**
     * The current rotation that is responsible for aiming at objects, synchronizing movement, etc.
     */
    var currentRotation: Rotation? = null

    /**
     * The last rotation that the server has received.
     */
    var serverRotation: Rotation
        get() = lastRotations[0]
        set(value) {
            lastRotations = lastRotations.toMutableList().apply { set(0, value) }
        }

    private const val MAX_CAPTURE_TICKS = 3

    var modifiedInput = MovementInput()

    /**
     * A list that stores the last rotations captured from 0 up to [MAX_CAPTURE_TICKS] previous ticks.
     */
    var lastRotations = MutableList(MAX_CAPTURE_TICKS) { Rotation.ZERO }
        set(value) {
            val updatedList = MutableList(lastRotations.size) { Rotation.ZERO }

            for (tick in 0 until MAX_CAPTURE_TICKS) {
                updatedList[tick] = if (tick == 0) value[0] else field[tick - 1]
            }

            field = updatedList
        }

    /**
     * The currently in-use rotation settings, which are used to determine how the rotations will move.
     */
    var activeSettings: RotationSettings? = null

    var resetTicks = 0

    /**
     * Face block
     *
     * @param blockPos target block
     */
    fun faceBlock(
        blockPos: BlockPos?,
        throughWalls: Boolean = true,
        targetUpperFace: Boolean = false,
        hRange: ClosedFloatingPointRange<Double> = 0.0..1.0
    ): VecRotation? {
        val world = mc.theWorld ?: return null
        val player = mc.thePlayer ?: return null

        if (blockPos == null) return null

        val block = blockPos.block ?: return null

        val eyesPos = player.eyes
        val startPos = Vec3(blockPos)

        var visibleVec: VecRotation? = null
        var invisibleVec: VecRotation? = null

        val yRange = if (targetUpperFace) 0.0..0.01 else 0.0..1.0

        for (x in hRange) {
            for (y in yRange) {
                for (z in hRange) {
                    val posVec = startPos.add(block.lerpWith(x, y, z))

                    val dist = eyesPos.distanceTo(posVec)

                    val (diffX, diffY, diffZ) = posVec - eyesPos
                    val diffXZ = sqrt(diffX * diffX + diffZ * diffZ)

                    val rotation = Rotation(
                        MathHelper.wrapAngleTo180_float(atan2(diffZ, diffX).toDegreesF() - 90f),
                        MathHelper.wrapAngleTo180_float(-atan2(diffY, diffXZ).toDegreesF())
                    ).fixedSensitivity()

                    val rotationVector = getVectorForRotation(rotation)
                    val vector = eyesPos + (rotationVector * dist)

                    val currentVec = VecRotation(posVec, rotation)
                    val raycast = world.rayTraceBlocks(eyesPos, vector, false, true, false)

                    val currentRotation = currentRotation ?: player.rotation

                    if (raycast != null && raycast.blockPos == blockPos && (!targetUpperFace || raycast.sideHit == EnumFacing.UP)) {
                        if (visibleVec == null || rotationDifference(
                                currentVec.rotation, currentRotation
                            ) < rotationDifference(visibleVec.rotation, currentRotation)
                        ) {
                            visibleVec = currentVec
                        }
                    } else if (throughWalls) {
                        val invisibleRaycast = performRaytrace(blockPos, rotation) ?: continue

                        if (invisibleRaycast.blockPos != blockPos) {
                            continue
                        }

                        if (invisibleVec == null || rotationDifference(
                                currentVec.rotation, currentRotation
                            ) < rotationDifference(invisibleVec.rotation, currentRotation)
                        ) {
                            invisibleVec = currentVec
                        }
                    }
                }
            }
        }

        return visibleVec ?: invisibleVec
    }

    /**
     * Face trajectory of arrow by default, can be used for calculating other trajectories (eggs, snowballs)
     * by specifying `gravity` and `velocity` parameters
     *
     * @param target      your enemy
     * @param predict     account for target and shooter motion during projectile flight
     * @param gravity     how much gravity does the projectile have, arrow by default
     * @param velocity    with what velocity will the projectile be released, velocity for arrow is calculated when null
     */
    fun faceTrajectory(
        target: Entity,
        predict: Boolean,
        gravity: Float = 0.05f,
        velocity: Float? = null,
    ): Rotation {
        val player = mc.thePlayer

        val targetPoint = Vec3(
            target.posX,
            target.entityBoundingBox.minY + target.eyeHeight - 0.15,
            target.posZ,
        )
        val eyes = player.eyes
        val relativePosition = MotionVector(
            targetPoint.xCoord - eyes.xCoord,
            targetPoint.yCoord - eyes.yCoord,
            targetPoint.zCoord - eyes.zCoord,
        )

        val projectileVelocity = velocity ?: run {
            val charge = if (FastBow.handleEvents()) 1f else player.itemInUseDuration / 20f
            ((charge * charge + charge * 2) / 3).coerceAtMost(1f)
        }

        val gravityModifier = 0.12f * gravity
        val targetMotion = estimateEntityMotion(target)
        val targetVelocity = if (predict) targetMotion.velocity * targetMotion.confidence else MotionVector.ZERO
        val shooterVelocity = if (predict) MotionVector(
            player.posX - player.prevPosX,
            player.posY - player.prevPosY,
            player.posZ - player.prevPosZ,
        ) else MotionVector.ZERO

        val solution = ProjectileInterceptSolver.solve(
            relativePosition = relativePosition,
            relativeVelocity = targetVelocity - shooterVelocity,
            projectileSpeed = projectileVelocity.toDouble(),
            gravity = gravityModifier.toDouble(),
        )

        return solution?.let { Rotation(it.yaw.toFloat(), it.pitch.toFloat()) } ?: toRotation(targetPoint)
    }

    /**
     * Translate vec to rotation
     *
     * @param vec     target vec
     * @return rotation
     */
    fun toRotation(vec: Vec3, fromEntity: Entity = mc.thePlayer): Rotation {
        val eyesPos = fromEntity.eyes

        val (diffX, diffY, diffZ) = vec - eyesPos
        return Rotation(
            MathHelper.wrapAngleTo180_float(
                atan2(diffZ, diffX).toDegreesF() - 90f
            ), MathHelper.wrapAngleTo180_float(
                -atan2(diffY, sqrt(diffX * diffX + diffZ * diffZ)).toDegreesF()
            )
        )
    }

    /** Predicts an entity box from bounded motion history without mutating either entity involved. */
    fun predictEntityBox(entity: Entity, horizonTicks: Double): AxisAlignedBB {
        val prediction = estimateEntityMotion(entity, horizonTicks)
        val offset = prediction.offset
        return entity.hitBox.offset(offset.x, offset.y, offset.z)
    }

    fun estimateEntityMotion(entity: Entity, horizonTicks: Double = 0.0): MotionPrediction {
        return targetMotionEstimator.predict(
            entityId = entity.entityId,
            current = MotionVector(entity.posX, entity.posY, entity.posZ),
            previous = MotionVector(entity.prevPosX, entity.prevPosY, entity.prevPosZ),
            tick = runTimeTicks,
            horizonTicks = horizonTicks,
        )
    }

    /**
     * Search good center
     *
     * @param bb                entity box to search rotation for
     * @param outborder         outborder option
     * @param lookRange         look range
     * @param attackRange       attack range, rotations in attack range will be prioritized
     * @param throughWallsRange through walls range,
     * @return center
     */
    fun searchCenter(
        bb: AxisAlignedBB, distanceBasedSpot: Boolean = false, outborder: Boolean,
        lookRange: Float, attackRange: Float, throughWallsRange: Float = 0f,
        bodyPoints: List<String> = listOf("Head", "Feet"), horizontalSearch: ClosedFloatingPointRange<Float> = 0f..1f,
        targetKey: TargetPointKey? = null, targetPointVariation: Double = 0.0,
    ): Rotation? {
        val scanRange = lookRange.coerceAtLeast(attackRange)

        val max = BodyPoint.fromString(bodyPoints[0]).range.endInclusive
        val min = BodyPoint.fromString(bodyPoints[1]).range.start

        if (outborder) {
            val vec3 = bb.lerpWith(nextDouble(0.5, 1.3), nextDouble(0.9, 1.3), nextDouble(0.5, 1.3))

            return toRotation(vec3).fixedSensitivity()
        }

        val eyes = mc.thePlayer.eyes

        val (hMin, hMax) = horizontalSearch.start.toDouble() to min(horizontalSearch.endInclusive + 0.01, 1.0)
        val nearestPoint = getNearestPointBB(eyes, bb)
        val stickyPoint = targetKey?.let {
            targetPointTracker.pointFor(
                key = it,
                fallback = normalizePoint(bb, nearestPoint),
                horizontalRange = hMin..hMax,
                verticalRange = min..max,
                variation = targetPointVariation,
            )
        }?.let { bb.lerpWith(it.x, it.y, it.z) }

        val preferredRotation = stickyPoint?.let { toRotation(it) }
            ?: toRotation(nearestPoint).takeIf { distanceBasedSpot }
            ?: currentRotation
            ?: mc.thePlayer.rotation

        val currRotation = Rotation.ZERO.plus(preferredRotation)

        var attackRotation: Pair<Rotation, Float>? = null
        var lookRotation: Pair<Rotation, Float>? = null

        for (x in hMin..hMax) {
            for (y in min..max) {
                for (z in hMin..hMax) {
                    val vec = bb.lerpWith(x, y, z)

                    val rotation = toRotation(vec).fixedSensitivity()

                    // Calculate actual hit vec after applying fixed sensitivity to rotation
                    val gcdVec = bb.calculateIntercept(
                        eyes, eyes + getVectorForRotation(rotation) * scanRange.toDouble()
                    )?.hitVec ?: continue

                    val distance = eyes.distanceTo(gcdVec)

                    // Check if vec is in range
                    // Skip if a rotation that is in attack range was already found and the vec is out of attack range
                    if (distance > scanRange || (attackRotation != null && distance > attackRange)) continue

                    // Check if vec is reachable through walls
                    if (!isVisible(gcdVec) && distance > throughWallsRange) continue

                    val rotationWithDiff = rotation to rotationDifference(rotation, currRotation)

                    if (distance <= attackRange) {
                        if (attackRotation == null || rotationWithDiff.second < attackRotation.second) attackRotation =
                            rotationWithDiff
                    } else {
                        if (lookRotation == null || rotationWithDiff.second < lookRotation.second) lookRotation =
                            rotationWithDiff
                    }
                }
            }
        }

        return attackRotation?.first ?: lookRotation?.first ?: run {
            val vec = getNearestPointBB(eyes, bb)
            val dist = eyes.distanceTo(vec)

            if (dist <= scanRange && (dist <= throughWallsRange || isVisible(vec))) toRotation(vec)
            else null
        }
    }

    private fun normalizePoint(box: AxisAlignedBB, point: Vec3): NormalizedTargetPoint {
        fun normalize(value: Double, min: Double, max: Double) =
            if (max - min <= 1.0e-9) 0.5 else ((value - min) / (max - min)).coerceIn(0.0, 1.0)

        return NormalizedTargetPoint(
            normalize(point.xCoord, box.minX, box.maxX),
            normalize(point.yCoord, box.minY, box.maxY),
            normalize(point.zCoord, box.minZ, box.maxZ),
        )
    }

    /**
     * Calculate difference between the client rotation and your entity
     *
     * @param entity your entity
     * @return difference between rotation
     */
    fun rotationDifference(entity: Entity) =
        rotationDifference(toRotation(entity.hitBox.center), mc.thePlayer.rotation)

    /**
     * Calculate difference between two rotations
     *
     * @param a rotation
     * @param b rotation
     * @return difference between rotation
     */
    fun rotationDifference(a: Rotation, b: Rotation = serverRotation) =
        hypot(angleDifference(a.yaw, b.yaw), a.pitch - b.pitch)

    private fun limitAngleChange(
        currentRotation: Rotation,
        targetRotation: Rotation,
        settings: RotationSettings,
        request: RotationRequest? = null,
    ): Rotation {
        val instant = request?.instant == true
        val (hSpeed, vSpeed) = if (instant) {
            180f to 180f
        } else {
            (request?.horizontalSpeed ?: settings.horizontalSpeed) to
                (request?.verticalSpeed ?: request?.horizontalSpeed ?: settings.verticalSpeed)
        }

        val profile = settings.humanizationProfile
        if (!instant && profile.enabled) {
            val result = humanizer.step(
                current = AnglePoint(currentRotation.yaw.toDouble(), currentRotation.pitch.toDouble()),
                requestedTarget = AnglePoint(targetRotation.yaw.toDouble(), targetRotation.pitch.toDouble()),
                maxYawSpeed = hSpeed.toDouble(),
                maxPitchSpeed = vSpeed.toDouble(),
                requestedProfile = profile,
            )

            return Rotation(result.rotation.yaw.toFloat(), result.rotation.pitch.toFloat())
        }

        val (yawDiff, pitchDiff) = angleDifferences(targetRotation, currentRotation)
        val difference = hypot(yawDiff, pitchDiff)
        if (difference <= 1.0e-6f) return currentRotation.copy()

        val yawLimit = abs(yawDiff safeDiv difference) * abs(hSpeed)
        val pitchLimit = abs(pitchDiff safeDiv difference) * abs(vSpeed)
        return currentRotation.plus(
            Rotation(
                yawDiff.coerceIn(-yawLimit, yawLimit),
                pitchDiff.coerceIn(-pitchLimit, pitchLimit),
            )
        )
    }

    /**
     * Calculate difference between two angle points
     *
     * @param a angle point
     * @param b angle point
     * @return difference between angle points
     */
    fun angleDifference(a: Float, b: Float) = MathHelper.wrapAngleTo180_float(a - b)

    /**
     * Returns a 2-parameter vector with the calculated angle differences between [target] and [current] rotations
     */
    fun angleDifferences(target: Rotation, current: Rotation) =
        Vector2f(angleDifference(target.yaw, current.yaw), target.pitch - current.pitch)

    /**
     * Calculate rotation to vector
     *
     * @param [yaw] [pitch] your rotation
     * @return target vector
     */
    fun getVectorForRotation(yaw: Float, pitch: Float): Vec3 {
        val yawRad = yaw.toRadians()
        val pitchRad = pitch.toRadians()

        val f = MathHelper.cos(-yawRad - PI.toFloat())
        val f1 = MathHelper.sin(-yawRad - PI.toFloat())
        val f2 = -MathHelper.cos(-pitchRad)
        val f3 = MathHelper.sin(-pitchRad)

        return Vec3((f1 * f2).toDouble(), f3.toDouble(), (f * f2).toDouble())
    }

    fun getVectorForRotation(rotation: Rotation) = getVectorForRotation(rotation.yaw, rotation.pitch)

    /**
     * Returns the inverted yaw angle.
     *
     * @param yaw The original yaw angle in degrees.
     * @return The yaw angle inverted by 180 degrees.
     */
    fun invertYaw(yaw: Float): Float {
        return (yaw + 180) % 360
    }

    /**
     * Allows you to check if your crosshair is over your target entity
     *
     * @param targetEntity       your target entity
     * @param blockReachDistance your reach
     * @return if crosshair is over target
     */
    fun isFaced(targetEntity: Entity, blockReachDistance: Double) =
        raycastEntity(blockReachDistance) { entity: Entity -> targetEntity == entity } != null

    /**
     * Allows you to check if your crosshair is over your target entity
     *
     * @param targetEntity       your target entity
     * @param blockReachDistance your reach
     * @return if crosshair is over target
     */
    fun isRotationFaced(targetEntity: Entity, blockReachDistance: Double, rotation: Rotation) = raycastEntity(
        blockReachDistance, rotation.yaw, rotation.pitch
    ) { entity: Entity -> targetEntity == entity } != null

    /**
     * Allows you to check if your enemy is behind a wall
     */
    fun isVisible(vec3: Vec3) = mc.theWorld.rayTraceBlocks(mc.thePlayer.eyes, vec3) == null

    fun isEntityHeightVisible(entity: Entity) = arrayOf(
        entity.hitBox.center.withY(entity.hitBox.maxY), entity.hitBox.center.withY(entity.hitBox.minY)
    ).any { isVisible(it) }

    fun isEntityHeightVisible(entity: TileEntity) = arrayOf(
        entity.renderBoundingBox.center.withY(entity.renderBoundingBox.maxY),
        entity.renderBoundingBox.center.withY(entity.renderBoundingBox.minY)
    ).any { isVisible(it) }

    /** Set a context-rich rotation request with stable ownership and target metadata. */
    fun setTargetRotation(request: RotationRequest, ticks: Int = request.settings.resetTicks) {
        val rotation = request.desired
        val options = request.settings

        if (rotation.yaw.isNaN() || rotation.pitch.isNaN() || rotation.pitch > 90 || rotation.pitch < -90) {
            return
        }

        val previousRequest = activeRequest
        if (request.priority < (previousRequest?.priority ?: 0)) {
            return
        }

        if (previousRequest != null && (
                previousRequest.owner !== request.owner ||
                    previousRequest.purpose != request.purpose ||
                    previousRequest.changeYaw != request.changeYaw ||
                    previousRequest.changePitch != request.changePitch ||
                    !sameTarget(previousRequest.target, request.target)
                )
        ) {
            humanizer.reset()
            sensitivityQuantizer.reset()
        }

        if (!options.applyServerSide && activeSettings?.applyServerSide != false) {
            currentRotation?.let {
                mc.thePlayer.rotationYaw = it.yaw
                mc.thePlayer.rotationPitch = it.pitch
            }

            resetRotation()
        }

        activeRequest = request.copy(desired = rotation.copy())
        targetRotation = activeRequest?.desired

        resetTicks = if (!options.applyServerSide || !options.resetTicksValue.isSupported()) 1 else ticks

        activeSettings = options

        if (request.immediate) {
            update()
            skipNextRotationUpdate = true
        } else {
            skipNextRotationUpdate = false
        }
    }

    private fun resetRotation() {
        resetTicks = 0
        currentRotation?.let { (yaw, _) ->
            mc.thePlayer?.let {
                it.rotationYaw = yaw + angleDifference(it.rotationYaw, yaw)
                syncRotations()
            }
        }
        targetRotation = null
        activeRequest = null
        currentRotation = null
        activeSettings = null
        skipNextRotationUpdate = false
        quantizingReset = false
        humanizer.reset()
        sensitivityQuantizer.reset()
        targetPointTracker.reset()
    }

    private fun sameTarget(first: RotationTarget?, second: RotationTarget?): Boolean {
        if (first == null || second == null) return first == second

        return when {
            first is RotationTarget.EntityRegion && second is RotationTarget.EntityRegion ->
                first.entityId == second.entityId
            first is RotationTarget.WorldPoint && second is RotationTarget.WorldPoint ->
                if (first.blockPos != null && second.blockPos != null) {
                    first.blockPos == second.blockPos && first.face == second.face
                } else {
                    first.point == second.point
                }
            first is RotationTarget.ExactRotation && second is RotationTarget.ExactRotation ->
                rotationDifference(first.rotation, second.rotation) < getFixedAngleDelta()
            else -> false
        }
    }

    /**
     * Returns the smallest angle difference possible with a specific sensitivity ("gcd")
     */
    fun getFixedAngleDelta(sensitivity: Float = mc.gameSettings.mouseSensitivity) =
        (sensitivity * 0.6f + 0.2f).pow(3) * 1.2f

    /**
     * Returns angle that is legitimately accomplishable with player's current sensitivity
     */
    fun getFixedSensitivityAngle(targetAngle: Float, startAngle: Float = 0f, gcd: Float = getFixedAngleDelta()) =
        startAngle + ((targetAngle - startAngle) / gcd).roundToInt() * gcd

    private fun quantizeRotation(current: Rotation, desired: Rotation): Rotation {
        val quantized = sensitivityQuantizer.quantize(
            current = AnglePoint(current.yaw.toDouble(), current.pitch.toDouble()),
            desired = AnglePoint(desired.yaw.toDouble(), desired.pitch.toDouble()),
            step = getFixedAngleDelta().toDouble(),
        )

        return Rotation(quantized.yaw.toFloat(), quantized.pitch.toFloat())
    }

    /**
     * Creates a raytrace even when the target [blockPos] is not visible
     */
    fun performRaytrace(
        blockPos: BlockPos,
        rotation: Rotation,
        reach: Float = mc.playerController.blockReachDistance,
    ): MovingObjectPosition? {
        val world = mc.theWorld ?: return null
        val player = mc.thePlayer ?: return null

        val eyes = player.eyes

        return blockPos.block?.collisionRayTrace(
            world, blockPos, eyes, eyes + (getVectorForRotation(rotation) * reach.toDouble())
        )
    }

    fun performRayTrace(blockPos: BlockPos, vec: Vec3, eyes: Vec3 = mc.thePlayer.eyes) =
        mc.theWorld?.let { blockPos.block?.collisionRayTrace(it, blockPos, eyes, vec) }

    fun syncRotations() {
        val player = mc.thePlayer ?: return

        player.prevRotationYaw = player.rotationYaw
        player.prevRotationPitch = player.rotationPitch
        player.renderArmYaw = player.rotationYaw
        player.renderArmPitch = player.rotationPitch
        player.prevRenderArmYaw = player.rotationYaw
        player.prevRotationPitch = player.rotationPitch
    }

    private fun update() {
        val settings = activeSettings ?: return
        val player = mc.thePlayer ?: return

        val playerRotation = player.rotation

        val shouldUpdate = !InventoryUtils.serverOpenContainer && !InventoryUtils.serverOpenInventory

        if (!shouldUpdate) {
            return
        }

        val sourceRotation = if (settings.applyServerSide) currentRotation ?: serverRotation else playerRotation

        if (resetTicks == 0) {
            if (isDifferenceAcceptableForReset(sourceRotation, playerRotation, settings)) {
                resetRotation()
                return
            }

            if (!quantizingReset) {
                sensitivityQuantizer.reset()
                quantizingReset = true
            }

            val limitedRotation = limitAngleChange(
                sourceRotation, playerRotation, settings
            )
            currentRotation = quantizeRotation(sourceRotation, limitedRotation)
            return
        }

        quantizingReset = false

        targetRotation?.let { target ->
            val request = activeRequest
            val effectiveTarget = Rotation(
                if (request?.changeYaw != false) target.yaw else sourceRotation.yaw,
                if (request?.changePitch != false) target.pitch else sourceRotation.pitch,
            )

            limitAngleChange(sourceRotation, effectiveTarget, settings, request).let { rotation ->
                val quantizedRotation = quantizeRotation(sourceRotation, rotation)

                if (!settings.applyServerSide) {
                    if (request?.changeYaw != false) player.rotationYaw = quantizedRotation.yaw
                    if (request?.changePitch != false) player.rotationPitch = quantizedRotation.pitch
                } else {
                    currentRotation = quantizedRotation
                }
            }
        }

        if (resetTicks > 0) {
            resetTicks--
        }
    }

    private fun isDifferenceAcceptableForReset(
        curr: Rotation, target: Rotation, options: RotationSettings
    ): Boolean {
        if (!options.applyServerSide) return true

        if (rotationDifference(target, curr) > options.angleResetDifference) return false

        return true
    }

    /**
     * Any module that modifies the server packets without using the [currentRotation] should use on module disable.
     */
    fun syncSpecialModuleRotations() {
        serverRotation.let { (yaw, _) ->
            mc.thePlayer?.let {
                it.rotationYaw = yaw + angleDifference(it.rotationYaw, yaw)
                syncRotations()
            }
        }
    }

    /**
     * Checks if the rotation difference is not the same as the smallest GCD angle possible.
     */
    fun canUpdateRotation(current: Rotation, target: Rotation, multiplier: Int = 1): Boolean {
        if (current == target) return true

        val smallestAnglePossible = getFixedAngleDelta()

        return rotationDifference(target, current).withGCD() > smallestAnglePossible * multiplier
    }

    /**
     * Handle rotation update
     */
    val onRotationUpdate = handler<RotationUpdateEvent>(priority = -1) {
        if (skipNextRotationUpdate) {
            skipNextRotationUpdate = false
            return@handler
        }

        update()
    }

    val onWorld = handler<WorldEvent> {
        resetTicks = 0
        targetRotation = null
        activeRequest = null
        currentRotation = null
        activeSettings = null
        skipNextRotationUpdate = false
        quantizingReset = false
        targetMotionEstimator.clear()
        targetPointTracker.reset()
        humanizer.reset()
        sensitivityQuantizer.reset()
    }

    /**
     * Handle strafing
     */
    val onStrafe = handler<StrafeEvent> { event ->
        val data = activeSettings ?: return@handler

        if (!data.strafe) {
            return@handler
        }

        currentRotation?.let {
            it.applyStrafeToPlayer(event, data.strict)
            event.cancelEvent()
        }
    }

    /**
     * Handle rotation-packet modification
     */
    val onPacket = handler<PacketEvent> { event ->
        val packet = event.packet

        if (packet !is C03PacketPlayer) {
            return@handler
        }

        if (!packet.rotating) return@handler

        currentRotation?.let { packet.rotation = it }

        val diffs = angleDifferences(packet.rotation, serverRotation)

        if (Rotations.debugRotations && currentRotation != null) {
            chat("PREV YAW: ${diffs.x}, PREV PITCH: ${diffs.y}")
        }

    }

    enum class BodyPoint(val rank: Int, val range: ClosedFloatingPointRange<Double>, val displayName: String) {
        HEAD(1, 0.75..0.9, "Head"), BODY(0, 0.5..0.75, "Body"), FEET(-1, 0.1..0.4, "Feet"), UNKNOWN(
            -2, 0.0..0.0, "Unknown"
        );

        companion object {
            fun fromString(point: String): BodyPoint {
                return entries.find { it.name.equals(point, ignoreCase = true) } ?: UNKNOWN
            }
        }
    }

    fun coerceBodyPoint(point: BodyPoint, minPoint: BodyPoint, maxPoint: BodyPoint): BodyPoint {
        return when {
            point.rank < minPoint.rank -> minPoint
            point.rank > maxPoint.rank -> maxPoint
            else -> point
        }
    }
}
