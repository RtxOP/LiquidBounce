/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.MotionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.modules.player.Reach
import net.ccbluex.liquidbounce.utils.attack.EntityUtils.isSelected
import net.ccbluex.liquidbounce.utils.extensions.*
import net.ccbluex.liquidbounce.utils.rotation.AlwaysRotationSettings
import net.ccbluex.liquidbounce.utils.rotation.RotationPurpose
import net.ccbluex.liquidbounce.utils.rotation.RotationRequest
import net.ccbluex.liquidbounce.utils.rotation.RotationTarget
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.currentRotation
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.isFaced
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.predictEntityBox
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.rotationDifference
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.searchCenter
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.setTargetRotation
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.toRotation
import net.ccbluex.liquidbounce.utils.rotation.RotationValidity
import net.ccbluex.liquidbounce.utils.rotation.humanization.TargetPointKey
import net.ccbluex.liquidbounce.utils.rotation.humanization.TargetPointEpoch
import net.ccbluex.liquidbounce.utils.simulation.SimulatedPlayer
import net.ccbluex.liquidbounce.utils.timing.MSTimer
import net.minecraft.entity.Entity
import kotlin.math.atan

object Aimbot : Module("Aimbot", Category.COMBAT) {

    private val range by float("Range", 4.4F, 1F..8F)
    private val horizontalAim by boolean("HorizontalAim", true)
    private val verticalAim by boolean("VerticalAim", true)
    private val maxAngleChange by float("MaxAngleChange", 10f, 1F..180F) { horizontalAim || verticalAim }
    private val inViewMaxAngleChange by float("InViewMaxAngleChange", 35f, 1f..180f) { horizontalAim || verticalAim }
    private val generateSpotBasedOnDistance by boolean(
        "GenerateSpotBasedOnDistance", false
    ) { horizontalAim || verticalAim }
    private val predictClientMovement by int("PredictClientMovement", 2, 0..5)
    private val predictionHorizon by float("PredictionHorizon", 2f, 0f..5f)

    private val highestBodyPointToTargetValue = choices(
        "HighestBodyPointToTarget", arrayOf("Head", "Body", "Feet"), "Head"
    ) {
        verticalAim
    }.onChange { _, new ->
        val newPoint = RotationUtils.BodyPoint.fromString(new)
        val lowestPoint = RotationUtils.BodyPoint.fromString(lowestBodyPointToTarget)
        val coercedPoint = RotationUtils.coerceBodyPoint(newPoint, lowestPoint, RotationUtils.BodyPoint.HEAD)
        coercedPoint.displayName
    }

    private val highestBodyPointToTarget: String by highestBodyPointToTargetValue

    private val lowestBodyPointToTargetValue = choices(
        "LowestBodyPointToTarget", arrayOf("Head", "Body", "Feet"), "Feet"
    ) {
        verticalAim
    }.onChange { _, new ->
        val newPoint = RotationUtils.BodyPoint.fromString(new)
        val highestPoint = RotationUtils.BodyPoint.fromString(highestBodyPointToTarget)
        val coercedPoint = RotationUtils.coerceBodyPoint(newPoint, RotationUtils.BodyPoint.FEET, highestPoint)
        coercedPoint.displayName
    }

    private val lowestBodyPointToTarget: String by lowestBodyPointToTargetValue

    private val horizontalBodySearchRange by floatRange("HorizontalBodySearchRange", 0f..1f, 0f..1f) { horizontalAim }

    private val fov by float("FOV", 180F, 1F..180F)
    private val lock by boolean("Lock", true) { horizontalAim || verticalAim }
    private val onClick by boolean("OnClick", false) { horizontalAim || verticalAim }
    private val center by boolean("Center", false)
    private val headLock by boolean("Headlock", false) { center && lock }
    private val headLockBlockHeight by float("HeadBlockHeight", -1f, -2f..0f) { headLock && center && lock }
    private val breakBlocks by boolean("BreakBlocks", true)

    private val clickTimer = MSTimer()

    private val options = AlwaysRotationSettings(this, defaultHumanization = "Balanced") {
        horizontalAim || verticalAim
    }.apply {
        withoutKeepRotation()
        applyServerSideValue.excludeWithState(false)
        strafeValue.excludeWithState(false)
        horizontalAngleChangeValue.excludeWithState(180f..180f)
        verticalAngleChangeValue.excludeWithState(180f..180f)
    }

    val onMotion = handler<MotionEvent> { event ->
        if (event.eventState != EventState.POST) return@handler

        val player = mc.thePlayer ?: return@handler
        val world = mc.theWorld ?: return@handler

        // Clicking delay
        if (mc.gameSettings.keyBindAttack.isKeyDown) clickTimer.reset()

        if (onClick && (clickTimer.hasTimePassed(150) || !mc.gameSettings.keyBindAttack.isKeyDown && AutoClicker.handleEvents())) {
            return@handler
        }

        // Search for the best enemy to target
        val entity = world.loadedEntityList.filter {
            Backtrack.runWithNearestTrackedDistance(it) {
                isSelected(
                    it,
                    true
                ) && player.canEntityBeSeen(it) && player.getDistanceToEntityBox(it) <= range && rotationDifference(it) <= fov
            }
        }.minByOrNull { player.getDistanceToEntityBox(it) } ?: return@handler

        // Should it always keep trying to lock on the enemy or just try to assist you?
        if (!lock && isFaced(entity, range.toDouble())) return@handler

        Backtrack.runWithNearestTrackedDistance(entity) { findRotation(entity) }
    }

    private fun findRotation(entity: Entity): Boolean {
        val player = mc.thePlayer ?: return false

        if (mc.playerController.isHittingBlock && breakBlocks) {
            return false
        }

        val boundingBox = predictEntityBox(entity, predictionHorizon.toDouble())

        val simPlayer = SimulatedPlayer.fromClientPlayer(RotationUtils.modifiedInput)

        simPlayer.rotationYaw = (currentRotation ?: player.rotation).yaw

        repeat(predictClientMovement) {
            simPlayer.tick()
        }

        val playerRotation = player.rotation
        val observerEyes = simPlayer.pos.addVector(0.0, player.eyeHeight.toDouble(), 0.0)

        var destinationRotation = if (center) {
            toRotation(boundingBox.center, observerEyes)
        } else {
            searchCenter(
                boundingBox,
                generateSpotBasedOnDistance,
                outborder = false,
                lookRange = range,
                attackRange = if (Reach.handleEvents()) Reach.combatReach else 3f,
                bodyPoints = listOf(highestBodyPointToTarget, lowestBodyPointToTarget),
                horizontalSearch = horizontalBodySearchRange,
                targetKey = TargetPointKey(this, entity.entityId),
                targetPointVariation = options.humanizationProfile.targetDrift,
                persistentTargetPoint = options.humanizationProfile.enabled,
                targetPointEpoch = TargetPointEpoch(options.humanizationProfile),
                observerEyes = observerEyes,
            )
        }

        if (destinationRotation == null) {
            return false
        }

        // look headLockBlockHeight higher
        if (headLock && center && lock) {
            val distance = observerEyes.distanceTo(getNearestPointBB(observerEyes, entity.hitBox))
            val playerEyeHeight = player.eyeHeight
            val blockHeight = headLockBlockHeight

            // Calculate the pitch offset needed to shift the view one block up
            val pitchOffset = Math.toDegrees(atan((blockHeight + playerEyeHeight) / distance)).toFloat()

            destinationRotation = destinationRotation.copy(pitch = destinationRotation.pitch - pitchOffset)
        }

        // Figure out the best turn speed suitable for the distance and configured turn speed
        val rotationDiff = rotationDifference(playerRotation, destinationRotation)

        // is enemy visible to player on screen. Fov is about to be right with that you can actually see on the screen. Still not 100% accurate, but it is fast check.
        val supposedTurnSpeed = if (rotationDiff < mc.gameSettings.fovSetting) {
            inViewMaxAngleChange
        } else {
            maxAngleChange
        }

        val turnSpeed = (rotationDiff * supposedTurnSpeed / 180f).coerceIn(0.1f, supposedTurnSpeed)

        val maxBodyPoint = RotationUtils.BodyPoint.fromString(highestBodyPointToTarget).range.endInclusive
        val minBodyPoint = RotationUtils.BodyPoint.fromString(lowestBodyPointToTarget).range.start

        setTargetRotation(
            RotationRequest(
                owner = this,
                desired = destinationRotation,
                settings = options,
                target = RotationTarget.EntityRegion(
                    entityId = entity.entityId,
                    box = boundingBox,
                    bodyRange = minBodyPoint..maxBodyPoint,
                    horizontalRange = horizontalBodySearchRange.start.toDouble()..
                        horizontalBodySearchRange.endInclusive.toDouble(),
                ),
                purpose = RotationPurpose.COMBAT_TRACK,
                validity = RotationValidity.RAYCAST,
                immediate = true,
                horizontalSpeed = turnSpeed,
                verticalSpeed = turnSpeed,
                changeYaw = horizontalAim,
                changePitch = verticalAim,
                reach = range.toDouble(),
                observerOrigin = observerEyes,
            )
        )

        return true
    }
}
