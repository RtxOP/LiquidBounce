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
import net.ccbluex.liquidbounce.utils.rotation.Rotation
import net.ccbluex.liquidbounce.utils.rotation.RotationSettings
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.currentRotation
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.isFaced
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.rotationDifference
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.searchCenter
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.setTargetRotation
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.toRotation
import net.ccbluex.liquidbounce.utils.simulation.SimulatedPlayer
import net.ccbluex.liquidbounce.utils.timing.MSTimer
import net.minecraft.entity.Entity
import kotlin.math.atan

/**
 * Spec §11.6 (unification): this module owns ONLY target selection and
 * destination policy (aim point, axis masks, lock/FOV/headlock). Shaping
 * goes through the shared pipeline — `setTargetRotation(destination,
 * options)` with `ApplyServerSide = false` (it drives the view, not the
 * server spoof) — so the engine, release/reset and recorder stamps are one
 * path for KillAura and Aimbot alike. The private aim state, duplicate
 * humanize knobs and inline fallbacks are gone (§9.2/§11.5).
 */
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
    private val predictEnemyPosition by float("PredictEnemyPosition", 1.5f, -1f..2f)

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

    /**
     * §11.6 handoff settings: destination/target policy stays in this
     * module, shaping goes through the shared pipeline on the client-side
     * (view) path. Aimbot's historical defaults are applied here; saved
     * configs load by value name afterwards (same module value bag).
     */
    private val options = RotationSettings(this).apply {
        applyServerSideValue.set(false, saveImmediately = false)
        humanizeValue.set(true, saveImmediately = false)
    }

    override fun onDisable() {
        RotationUtils.releaseOwnedBy(this)
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
        if (!lock && isFaced(entity, range.toDouble())) {
            return@handler
        }

        if (Backtrack.runWithNearestTrackedDistance(entity) { !findRotation(entity) }) {
            return@handler
        }
    }

    private fun findRotation(entity: Entity): Boolean {
        val player = mc.thePlayer ?: return false

        if (mc.playerController.isHittingBlock && breakBlocks) {
            return false
        }

        val prediction = entity.currPos.subtract(entity.prevPos).times(2 + predictEnemyPosition.toDouble())

        val boundingBox = entity.hitBox.offset(prediction)
        val (currPos, oldPos) = player.currPos to player.prevPos

        val simPlayer = SimulatedPlayer.fromClientPlayer(RotationUtils.modifiedInput)

        simPlayer.rotationYaw = (currentRotation ?: player.rotation).yaw

        repeat(predictClientMovement) {
            simPlayer.tick()
        }

        player.setPosAndPrevPos(simPlayer.pos)

        val destinationRotation = if (center) {
            toRotation(boundingBox.center, true)
        } else {
            searchCenter(
                boundingBox,
                generateSpotBasedOnDistance,
                outborder = false,
                predict = true,
                lookRange = range,
                attackRange = if (Reach.handleEvents()) Reach.combatReach else 3f,
                bodyPoints = listOf(highestBodyPointToTarget, lowestBodyPointToTarget),
                horizontalSearch = horizontalBodySearchRange
            )
        }

        if (destinationRotation == null) {
            player.setPosAndPrevPos(currPos, oldPos)
            return false
        }

        // look headLockBlockHeight higher
        if (headLock && center && lock) {
            val distance = player.getDistanceToEntityBox(entity)
            val playerEyeHeight = player.eyeHeight
            val blockHeight = headLockBlockHeight

            // Calculate the pitch offset needed to shift the view one block up
            val pitchOffset = Math.toDegrees(atan((blockHeight + playerEyeHeight) / distance)).toFloat()

            destinationRotation.pitch -= pitchOffset
        }

        // §11.6 handoff: turn-speed caps feed the shared shaping ranges the
        // recorder's cap forensics read; axis masks are destination policy —
        // pin disabled axes at the current view so the engine's error there
        // is exactly 0 (the 1-gcd floor then keeps them still).
        val lo = minOf(maxAngleChange, inViewMaxAngleChange)
        val hi = maxOf(maxAngleChange, inViewMaxAngleChange)
        if (options.horizontalAngleChange.start != lo ||
            options.horizontalAngleChange.endInclusive != hi
        ) {
            options.horizontalAngleChangeValue.set(lo..hi, saveImmediately = false)
            options.verticalAngleChangeValue.set(lo..hi, saveImmediately = false)
        }
        if (!horizontalAim || !verticalAim) {
            val view: Rotation = currentRotation ?: player.rotation
            if (!horizontalAim) destinationRotation.yaw = view.yaw
            if (!verticalAim) destinationRotation.pitch = view.pitch
        }

        // targetId so the engine can arm its reaction delay once per
        // acquisition instead of once per channel (see AimEngine.Input).
        setTargetRotation(destinationRotation, options, targetId = entity.entityId)

        player.setPosAndPrevPos(currPos, oldPos)
        return true
    }
}
