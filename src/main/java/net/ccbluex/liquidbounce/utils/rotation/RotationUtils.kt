/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation

import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.modules.combat.FastBow
import net.ccbluex.liquidbounce.features.module.modules.misc.NoRotateSet
import net.ccbluex.liquidbounce.features.module.modules.render.Rotations
import net.ccbluex.liquidbounce.utils.block.block
import net.ccbluex.liquidbounce.utils.client.ClientUtils.runTimeTicks
import net.ccbluex.liquidbounce.utils.client.MinecraftInstance
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.rotation
import net.ccbluex.liquidbounce.utils.extensions.*
import net.ccbluex.liquidbounce.utils.inventory.InventoryUtils
import net.ccbluex.liquidbounce.utils.kotlin.RandomUtils.nextDouble
import net.ccbluex.liquidbounce.utils.kotlin.RandomUtils.nextFloat
import net.ccbluex.liquidbounce.utils.rotation.RaycastUtils.raycastEntity
import net.ccbluex.liquidbounce.utils.rotation.aim.AimEngine
import net.ccbluex.liquidbounce.utils.timing.WaitTickUtils
import net.minecraft.entity.Entity
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.tileentity.TileEntity
import net.minecraft.util.*
import javax.vecmath.Vector2f
import kotlin.math.*
import kotlin.random.Random

object RotationUtils : MinecraftInstance, Listenable {

    /**
     * Our final rotation point, which [currentRotation] follows.
     */
    internal var targetRotation: Rotation? = null
        private set

    /**
     * Last tick a request arrived through [setTargetRotation]. RotationRecorder
     * uses it to drop stale [targetRotation] values: module-enabled is not the
     * same as "aim was commanded this tick".
     */
    internal var lastTargetSetTick = Int.MIN_VALUE
        private set

    /**
     * Last tick the shaper produced output through [limitAngleChange].
     * Recorder separation of shaper-driven ticks from raw mouse play relies on
     * this instead of `activeSettings != null`, which lingers across holds and
     * GUI-open early returns.
     */
    internal var lastShapedTick = Int.MIN_VALUE
        private set

    /**
     * True when this tick's rotation was produced by the shaper here
     * (within one tick of [lastShapedTick]; call order between motion
     * handlers decides whether the stamp reads 0 or 1 behind).
     */
    internal fun isShapingNow() =
        lastShapedTick != Int.MIN_VALUE && runTimeTicks - lastShapedTick in 0..1

    /**
     * True when [targetRotation] was requested within [maxAge] ticks.
     */
    internal fun hasFreshTarget(maxAge: Int = 2) =
        lastTargetSetTick != Int.MIN_VALUE && runTimeTicks - lastTargetSetTick in 0..maxAge

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
     * Engine channels, one per rotation owner (spec §11.1: one [AimEngine.State]
     * per [RotationSettings], the previous aimPaths pattern). Capped so idle
     * modules cannot grow the map without bound; oldest entry is evicted
     * first. Each channel owns its determinism root (§11.7.5): a fresh seed
     * per stored channel = per-engagement variety, stable within one.
     */
    private class AimChannel(val state: AimEngine.State, val seed: Long)

    private val aimStates = LinkedHashMap<RotationSettings, AimChannel>()

    private fun channelFor(settings: RotationSettings): AimChannel {
        aimStates[settings]?.let { return it }
        if (aimStates.size >= 8) {
            aimStates.keys.firstOrNull()?.let { aimStates.remove(it) }
        }
        return AimChannel(AimEngine.State(), Random.nextLong()).also { aimStates[settings] = it }
    }

    internal fun clearAimStates() {
        aimStates.clear()
    }

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
     * @param predict     predict new enemy position
     * @param predictSize predict size of predict
     * @param gravity     how much gravity does the projectile have, arrow by default
     * @param velocity    with what velocity will the projectile be released, velocity for arrow is calculated when null
     */
    fun faceTrajectory(
        target: Entity,
        predict: Boolean,
        predictSize: Float,
        gravity: Float = 0.05f,
        velocity: Float? = null,
    ): Rotation {
        val player = mc.thePlayer

        val posX =
            target.posX + (if (predict) (target.posX - target.prevPosX) * predictSize else .0) - (player.posX + if (predict) player.posX - player.prevPosX else .0)
        val posY =
            target.entityBoundingBox.minY + (if (predict) (target.entityBoundingBox.minY - target.prevPosY) * predictSize else .0) + target.eyeHeight - 0.15 - (player.entityBoundingBox.minY + (if (predict) player.posY - player.prevPosY else .0)) - player.getEyeHeight()
        val posZ =
            target.posZ + (if (predict) (target.posZ - target.prevPosZ) * predictSize else .0) - (player.posZ + if (predict) player.posZ - player.prevPosZ else .0)
        val posSqrt = sqrt(posX * posX + posZ * posZ)

        var finalVelocity = velocity

        if (finalVelocity == null) {
            finalVelocity = if (FastBow.handleEvents()) 1f else player.itemInUseDuration / 20f
            finalVelocity = ((finalVelocity * finalVelocity + finalVelocity * 2) / 3).coerceAtMost(1f)
        }

        val gravityModifier = 0.12f * gravity

        return Rotation(
            atan2(posZ, posX).toDegreesF() - 90f, -atan(
                (finalVelocity * finalVelocity - sqrt(
                    finalVelocity * finalVelocity * finalVelocity * finalVelocity - gravityModifier * (gravityModifier * posSqrt * posSqrt + 2 * posY * finalVelocity * finalVelocity)
                )) / (gravityModifier * posSqrt)
            ).toDegreesF()
        )
    }

    /**
     * Translate vec to rotation
     *
     * @param vec     target vec
     * @param predict predict new location of your body
     * @return rotation
     */
    fun toRotation(vec: Vec3, predict: Boolean = false, fromEntity: Entity = mc.thePlayer): Rotation {
        val eyesPos = fromEntity.eyes
        if (predict) eyesPos.addVector(fromEntity.motionX, fromEntity.motionY, fromEntity.motionZ)

        val (diffX, diffY, diffZ) = vec - eyesPos
        return Rotation(
            MathHelper.wrapAngleTo180_float(
                atan2(diffZ, diffX).toDegreesF() - 90f
            ), MathHelper.wrapAngleTo180_float(
                -atan2(diffY, sqrt(diffX * diffX + diffZ * diffZ)).toDegreesF()
            )
        )
    }

    /**
     * Search good center
     *
     * @param bb                entity box to search rotation for
     * @param outborder         outborder option
     * @param random            random option
     * @param predict           predict, offsets rotation by player's motion
     * @param lookRange         look range
     * @param attackRange       attack range, rotations in attack range will be prioritized
     * @param throughWallsRange through walls range,
     * @return center
     */
    fun searchCenter(
        bb: AxisAlignedBB, distanceBasedSpot: Boolean = false, outborder: Boolean,
        predict: Boolean,
        lookRange: Float, attackRange: Float, throughWallsRange: Float = 0f,
        bodyPoints: List<String> = listOf("Head", "Feet"), horizontalSearch: ClosedFloatingPointRange<Float> = 0f..1f,
    ): Rotation? {
        val scanRange = lookRange.coerceAtLeast(attackRange)

        val max = BodyPoint.fromString(bodyPoints[0]).range.endInclusive
        val min = BodyPoint.fromString(bodyPoints[1]).range.start

        if (outborder) {
            val vec3 = bb.lerpWith(nextDouble(0.5, 1.3), nextDouble(0.9, 1.3), nextDouble(0.5, 1.3))

            return toRotation(vec3, predict).fixedSensitivity()
        }

        val eyes = mc.thePlayer.eyes

        val preferredRotation = toRotation(getNearestPointBB(eyes, bb), predict).takeIf {
            distanceBasedSpot
        } ?: currentRotation ?: mc.thePlayer.rotation

        val currRotation = Rotation.ZERO.plus(preferredRotation)

        var attackRotation: Pair<Rotation, Float>? = null
        var lookRotation: Pair<Rotation, Float>? = null

        val (hMin, hMax) = horizontalSearch.start.toDouble() to min(horizontalSearch.endInclusive + 0.01, 1.0)

        for (x in hMin..hMax) {
            for (y in min..max) {
                for (z in hMin..hMax) {
                    val vec = bb.lerpWith(x, y, z)

                    val rotation = toRotation(vec, predict).fixedSensitivity()

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

            if (dist <= scanRange && (dist <= throughWallsRange || isVisible(vec))) toRotation(vec, predict)
            else null
        }
    }

    /**
     * Calculate difference between the client rotation and your entity
     *
     * @param entity your entity
     * @return difference between rotation
     */
    fun rotationDifference(entity: Entity) =
        rotationDifference(toRotation(entity.hitBox.center, true), mc.thePlayer.rotation)

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
        currentRotation: Rotation, targetRotation: Rotation, settings: RotationSettings
    ): Rotation {
        // Every call here decides this tick's rotation (flick, track, hold,
        // instant): stamp shaping so RotationRecorder can tell shaper-driven
        // ticks from raw mouse play.
        lastShapedTick = runTimeTicks

        if (settings.humanize) {
            if (settings.instant) {
                aimStates.remove(settings)
                return targetRotation.copy()
            }

            // NoRotateSet keeps its adapter-level hold; SimulateShortStop and
            // friends are deprecated no-ops — superseded by the scheduler's
            // fitted dwells (spec §11.5).
            if (WaitTickUtils.hasScheduled(NoRotateSet)) {
                return currentRotation
            }

            // Stage 6 (grid snap) is applied by every consumer below
            // (release/server branches via fixedSensitivity(), the client
            // branch inside toPlayer()).
            val channel = channelFor(settings)
            return AimEngine.step(
                AimEngine.Input(
                    current = currentRotation,
                    reference = targetRotation,
                    engaged = true,
                    tick = runTimeTicks,
                    gcd = getFixedAngleDelta(),
                    seed = channel.seed,
                    knobs = settings.engineKnobs(),
                ),
                channel.state,
            )
        }

        val (hSpeed, vSpeed) = if (settings.instant) {
            180f to 180f
        } else settings.horizontalAngleChange.random() to settings.verticalAngleChange.random()

        return performAngleChange(
            currentRotation,
            targetRotation,
            hSpeed,
            vSpeed,
            settings.minRotationDifference,
            settings.minRotationDifferenceResetTiming
        )
    }

    fun performAngleChange(
        currentRotation: Rotation,
        targetRotation: Rotation,
        hSpeed: Float,
        vSpeed: Float = hSpeed,
        minRotationDiff: Float,
        minRotationDiffResetTiming: String,
    ): Rotation {
        var (yawDiff, pitchDiff) = angleDifferences(targetRotation, currentRotation)

        val rotationDifference = hypot(yawDiff, pitchDiff)

        // NoRotateSet adapter policy; SimulateShortStop is a deprecated
        // no-op (§11.5) — its slowdown branch is gone with the old engine.
        if (WaitTickUtils.hasScheduled(NoRotateSet)) {
            yawDiff = 0F
            pitchDiff = 0F
        }

        var (straightLineYaw, straightLinePitch) = run {
            val baseYawSpeed = abs(yawDiff safeDiv rotationDifference) * hSpeed
            val basePitchSpeed = abs(pitchDiff safeDiv rotationDifference) * vSpeed

            baseYawSpeed to basePitchSpeed
        }

        straightLineYaw = yawDiff.coerceIn(-straightLineYaw, straightLineYaw)
        straightLinePitch = pitchDiff.coerceIn(-straightLinePitch, straightLinePitch)

        val minYaw = nextFloat(min(minRotationDiff, getFixedAngleDelta()), minRotationDiff).withGCD()
        val minPitch = nextFloat(min(minRotationDiff, getFixedAngleDelta()), minRotationDiff).withGCD()

        applySlowDown(straightLineYaw, minYaw, minRotationDiffResetTiming, true) {
            straightLineYaw = it
        }

        applySlowDown(straightLinePitch, minPitch, minRotationDiffResetTiming, false) {
            straightLinePitch = it
        }

        return currentRotation.plus(Rotation(straightLineYaw, straightLinePitch))
    }

    private fun applySlowDown(
        diff: Float, min: Float, timing: String, yaw: Boolean, action: (Float) -> Unit
    ) {
        if (diff == 0f) {
            action(diff)
            return
        }

        val lastTick1 = angleDifferences(serverRotation, lastRotations[1]).let { diffs ->
            if (yaw) diffs.x else diffs.y
        }

        val diffAbs = abs(diff)
        val isSlowingDown = diffAbs <= abs(lastTick1)

        if (diffAbs.withGCD() <= min && (timing == "Always" || timing == "OnSlowDown" && isSlowingDown || timing == "OnStart" && lastTick1 == 0F)) {
            action(0f)
            return
        }

        action(diff)
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

    /**
     * Set your target rotation
     *
     * @param rotation your target rotation
     */
    fun setTargetRotation(rotation: Rotation, options: RotationSettings, ticks: Int = options.resetTicks) {
        if (rotation.yaw.isNaN() || rotation.pitch.isNaN() || rotation.pitch > 90 || rotation.pitch < -90) {
            return
        }

        if (!options.prioritizeRequest && activeSettings?.prioritizeRequest == true) {
            return
        }

        if (needsReleaseSync(options.applyServerSide, currentRotation)) {
            releaseSync()
        }

        targetRotation = rotation
        lastTargetSetTick = runTimeTicks

        resetTicks = if (!options.applyServerSide || !options.resetTicksValue.isSupported()) 1 else ticks

        activeSettings = options

        if (options.immediate) {
            update()
        }
    }

    /**
     * §11.6 release-sync: the pending spoof is written back into the view,
     * then everything resets (grid-snapped, Phase-1 linger logic intact).
     */
    private fun releaseSync() {
        currentRotation?.let { pending ->
            mc.thePlayer?.let { player ->
                player.rotationYaw = pending.yaw
                player.rotationPitch = pending.pitch
            }
        }
        resetRotation()
    }

    /**
     * §11.6 module-toggle handoff: release-sync when [owner] owns the live
     * request, and always drop the owner's stored engine state — the
     * observable behavior change the handoff test pins (today's Aimbot
     * skipped the release entirely).
     */
    fun releaseOwnedBy(owner: Module) {
        if (activeSettings?.ownerModule == owner) {
            releaseSync()
        }
        aimStates.keys.filter { it.ownerModule == owner }.toList().forEach { aimStates.remove(it) }
    }

    private fun resetRotation() {
        resetTicks = 0
        releaseSettleTicks = 0
        releaseAgeTicks = 0
        activeSettings?.let { aimStates.remove(it) }
        currentRotation?.let { (yaw, _) ->
            mc.thePlayer?.let {
                // Snap to the server grid: an unsnapped release writes fractional
                // counts (see aim recordings, KillAura->manual handoff tick).
                val gcd = getFixedAngleDelta()
                it.rotationYaw = getFixedSensitivityAngle(yaw + angleDifference(it.rotationYaw, yaw), yaw, gcd)
                syncRotations()
            }
        }
        targetRotation = null
        currentRotation = null
        activeSettings = null
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

    /**
     * Release-branch liveness state (findings §8.3 item 2).
     *
     * The shaping path's deadband leaves a released spoof ~1–1.3° behind the
     * player (observed idle trails), and [isDifferenceAcceptableForReset]'s
     * monotonic-convergence condition cannot pass while that floor dominates
     * the spoof's own 2-tick displacement — so release never fired: the
     * spoof followed the player indefinitely (every manual tick of the
     * Sep-22 corpus labeled `source: KillAura`). Three guards keep release
     * bounded: a tolerance floor above the deadband floor, a settle window
     * that bypasses the monotonic check, and a hard timeout.
     */
    private var releaseSettleTicks = 0
    private var releaseAgeTicks = 0

    /** Degrees; must sit above the ~1.3° observed deadband floor. */
    private const val RELEASE_TOLERANCE_FLOOR = 2.0f

    /** Consecutive ticks within tolerance before resetting regardless of monotonic convergence. */
    private const val RELEASE_SETTLE_TICKS = 6

    /** Hard failsafe: never keep spoofing past 20 s of release. */
    private const val RELEASE_TIMEOUT_TICKS = 400

    /**
     * ...and only force-reset when the handoff jump stays human-scale
     * (≤ 10°, below the human per-tick p99 ≈ 17.5°); otherwise wait for the
     * gap to shrink so a force-reset can never emit a huge single-tick jump.
     */
    private const val RELEASE_TIMEOUT_MAX_GAP = 10f

    private fun update() {
        val settings = activeSettings ?: return
        val player = mc.thePlayer ?: return

        val playerRotation = player.rotation

        val shouldUpdate = !InventoryUtils.serverOpenContainer && !InventoryUtils.serverOpenInventory

        if (!shouldUpdate) {
            return
        }

        val serverRotation = currentRotation ?: serverRotation

        if (resetTicks == 0) {
            val gap = rotationDifference(playerRotation, serverRotation)
            val tolerance = maxOf(settings.angleResetDifference, RELEASE_TOLERANCE_FLOOR)
            releaseSettleTicks = if (gap <= tolerance) releaseSettleTicks + 1 else 0
            releaseAgeTicks++

            if (releaseSettleTicks >= RELEASE_SETTLE_TICKS ||
                isDifferenceAcceptableForReset(serverRotation, playerRotation, settings) ||
                (releaseAgeTicks >= RELEASE_TIMEOUT_TICKS && gap <= RELEASE_TIMEOUT_MAX_GAP)
            ) {
                resetRotation()
                return
            }

            currentRotation = limitAngleChange(
                serverRotation, playerRotation, settings
            ).fixedSensitivity()
            return
        }

        releaseSettleTicks = 0
        releaseAgeTicks = 0

        targetRotation?.let {
            limitAngleChange(serverRotation, it, settings).let { rotation ->
                if (!settings.applyServerSide) {
                    rotation.toPlayer(player)
                } else {
                    currentRotation = rotation.fixedSensitivity()
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

        // Floor the configured tolerance: configs may store 0 (tightest), which
        // can never be reached against the deadband residual — see release state.
        if (rotationDifference(target, curr) >
            maxOf(options.angleResetDifference, RELEASE_TOLERANCE_FLOOR)
        ) return false

        // We use the last rotation saved 2 ticks ago because we have not updated the currentRotation yet.
        val diffs = angleDifferences(target, curr).abs
        val lastTickDiffs = angleDifferences(curr, lastRotations[1]).abs

        return diffs.x <= lastTickDiffs.x && diffs.y <= lastTickDiffs.y || !options.humanize
    }

    /**
     * Any module that modifies the server packets without using the [currentRotation] should use on module disable.
     */
    fun syncSpecialModuleRotations() {
        serverRotation.let { (yaw, _) ->
            mc.thePlayer?.let {
                val gcd = getFixedAngleDelta()
                it.rotationYaw = getFixedSensitivityAngle(yaw + angleDifference(it.rotationYaw, yaw), yaw, gcd)
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
        activeSettings?.let {
            // Was the rotation update immediate? Allow updates the next tick.
            if (it.immediate) {
                it.immediate = false
                return@handler
            }
        }

        update()
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

        if (!packet.rotating) {
            return@handler
        }

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

    val onWorld = handler<WorldEvent> {
        clearAimStates()
    }
}

/**
 * §11.6 handoff predicate (top-level so the unit test classloads without
 * touching the [RotationUtils] object / Minecraft): a client-side request
 * must release-sync a pending server-side spoof first — and only then.
 * The pending-spoof guard is what keeps continuous client-side requests
 * from resetting the stored engine state every tick (churn that would
 * break every fitted stage).
 */
internal fun needsReleaseSync(incomingApplyServerSide: Boolean, pendingSpoof: Rotation?) =
    !incomingApplyServerSide && pendingSpoof != null
