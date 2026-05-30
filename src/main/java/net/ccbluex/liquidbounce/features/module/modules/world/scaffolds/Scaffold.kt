/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.world.scaffolds

import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.event.async.loopSequence
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.utils.attack.CPSCounter
import net.ccbluex.liquidbounce.utils.block.*
import net.ccbluex.liquidbounce.utils.client.ClientUtils
import net.ccbluex.liquidbounce.utils.client.PacketUtils.sendPacket
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.extensions.*
import net.ccbluex.liquidbounce.utils.inventory.InventoryUtils
import net.ccbluex.liquidbounce.utils.inventory.InventoryUtils.blocksAmount
import net.ccbluex.liquidbounce.utils.inventory.SilentHotbar
import net.ccbluex.liquidbounce.utils.inventory.hotBarSlot
import net.ccbluex.liquidbounce.utils.kotlin.RandomUtils
import net.ccbluex.liquidbounce.utils.movement.MovementUtils
import net.ccbluex.liquidbounce.utils.render.RenderUtils
import net.ccbluex.liquidbounce.utils.rotation.PlaceRotation
import net.ccbluex.liquidbounce.utils.rotation.Rotation
import net.ccbluex.liquidbounce.utils.rotation.RotationSettingsWithRotationModes
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.canUpdateRotation
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.getFixedAngleDelta
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.getVectorForRotation
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.rotationDifference
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.setTargetRotation
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.toRotation
import net.ccbluex.liquidbounce.utils.simulation.SimulatedPlayer
import net.ccbluex.liquidbounce.utils.timing.*
import net.minecraft.block.BlockBush
import net.minecraft.client.settings.GameSettings
import net.minecraft.init.Blocks.air
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.C0APacketAnimation
import net.minecraft.network.play.client.C0BPacketEntityAction
import net.minecraft.util.*
import net.minecraft.world.WorldSettings
import net.minecraftforge.event.ForgeEventFactory
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.*

object Scaffold : Module("Scaffold", Category.WORLD, Keyboard.KEY_I) {

    /**
     * TOWER MODES & SETTINGS
     */

    // -->

    private val towerMode by Tower.towerModeValues

    init {
        addValues(Tower.values)
    }

    // <--

    /**
     * SCAFFOLD MODES & SETTINGS
     */

    // -->

    val scaffoldMode by choices(
        "ScaffoldMode", arrayOf("Normal", "Rewinside", "Expand", "Telly", "GodBridge"), "Normal"
    )

    // Expand
    private val omniDirectionalExpand by boolean("OmniDirectionalExpand", false) { scaffoldMode == "Expand" }
    private val expandLength by int("ExpandLength", 1, 1..6) { scaffoldMode == "Expand" }

    // Placeable delay
    private val placeDelayValue = boolean("PlaceDelay", true) { scaffoldMode != "GodBridge" }
    private val delay by intRange("Delay", 0..0, 0..1000) { placeDelayValue.isActive() }

    // Extra clicks
    private val extraClicks by boolean("DoExtraClicks", false)
    private val simulateDoubleClicking by boolean("SimulateDoubleClicking", false) { extraClicks }
    private val extraClickCPS by intRange("ExtraClickCPS", 3..7, 0..50) { extraClicks }
    private val placementAttempt by choices(
        "PlacementAttempt", arrayOf("Fail", "Independent"), "Fail"
    ) { extraClicks }

    // Autoblock
    private val autoBlock by choices("AutoBlock", arrayOf("Off", "Pick", "Spoof", "Switch"), "Spoof")
    private val sortByHighestAmount by boolean("SortByHighestAmount", false) { autoBlock != "Off" }
    private val earlySwitch by boolean("EarlySwitch", false) { autoBlock != "Off" && !sortByHighestAmount }
    private val amountBeforeSwitch by int(
        "SlotAmountBeforeSwitch", 3, 1..10
    ) { earlySwitch && !sortByHighestAmount }

    // Settings
    private val autoF5 by boolean("AutoF5", false).subjective()

    // Basic stuff
    val sprint by boolean("Sprint", false)
    private val swing by boolean("Swing", true).subjective()
    private val down by boolean("Down", true) { !sameY && scaffoldMode !in arrayOf("GodBridge", "Telly") }

    private val ticksUntilRotation by intRange("TicksUntilRotation", 3..3, 1..8) {
        scaffoldMode == "Telly"
    }

    // GodBridge mode sub-values
    private val waitForRots by boolean("WaitForRotations", false) { isGodBridgeEnabled }
    private val useOptimizedPitch by boolean("UseOptimizedPitch", false) { isGodBridgeEnabled }
    private val customGodPitch by float(
        "GodBridgePitch", 73.5f, 0f..90f
    ) { isGodBridgeEnabled && !useOptimizedPitch }

    val jumpAutomatically by boolean("JumpAutomatically", true) { scaffoldMode == "GodBridge" }
    private val blocksToJumpRange by intRange("BlocksToJumpRange", 4..4, 1..8) {  scaffoldMode == "GodBridge" && !jumpAutomatically }
    private val godBridgeDebug by boolean("GodBridgeDebug", false) { isGodBridgeEnabled }
    private val godBridgeRaytraceTracer by boolean("GodBridgeRaytraceTracer", true) { isGodBridgeEnabled }

    // Telly mode sub-values
    private val startHorizontally by boolean("StartHorizontally", true) { scaffoldMode == "Telly" }
    private val horizontalPlacementsRange by intRange("HorizontalPlacementsRange", 1..1, 1..10) { scaffoldMode == "Telly" }
    private val verticalPlacementsRange by intRange("VerticalPlacementsRange", 1..1, 1..10) { scaffoldMode == "Telly" }

    private val jumpTicksRange by intRange("JumpTicksRange", 0..0, 0..10) { scaffoldMode == "Telly" }

    private val allowClutching by boolean("AllowClutching", true) { scaffoldMode !in arrayOf("Telly", "Expand") }
    private val horizontalClutchBlocks by int("HorizontalClutchBlocks", 3, 1..5) {
        allowClutching && scaffoldMode !in arrayOf("Telly", "Expand")
    }
    private val verticalClutchBlocks by int("VerticalClutchBlocks", 2, 1..3) {
        allowClutching && scaffoldMode !in arrayOf("Telly", "Expand")
    }
    private val blockSafe by boolean("BlockSafe", false) { !isGodBridgeEnabled }

    // Eagle
    private val eagleValue =
        choices("Eagle", arrayOf("Normal", "Silent", "Off"), "Normal") { scaffoldMode != "GodBridge" }
    val eagle by eagleValue
    private val eagleMode by choices("EagleMode", arrayOf("Both", "OnGround", "InAir"), "Both")
    { eagle != "Off" && scaffoldMode != "GodBridge" }
    private val adjustedSneakSpeed by boolean("AdjustedSneakSpeed", true)
    { eagle == "Silent" && scaffoldMode != "GodBridge" }
    private val eagleSpeed by float("EagleSpeed", 0.3f, 0.3f..1.0f) { eagle != "Off" && scaffoldMode != "GodBridge" }
    val eagleSprint by boolean("EagleSprint", false) { eagle == "Normal" && scaffoldMode != "GodBridge" }
    private val blocksToEagle by intRange("BlocksToEagle", 0..0, 0..10) { eagle != "Off" && scaffoldMode != "GodBridge" }
    private val edgeDistance by float("EagleEdgeDistance", 0f, 0f..0.5f)
    { eagle != "Off" && scaffoldMode != "GodBridge" }
    private val useMaxSneakTime by boolean("UseMaxSneakTime", true) { eagle != "Off" && scaffoldMode != "GodBridge" }
    private val maxSneakTicks by intRange("MaxSneakTicks", 3..3, 0..10) { useMaxSneakTime }
    private val blockSneakingAgainUntilOnGround by boolean("BlockSneakingAgainUntilOnGround", true)
    { useMaxSneakTime && eagleMode != "OnGround" }

    // Rotation Options
    private val modeList =
        choices("Rotations", arrayOf("Off", "Normal", "Stabilized", "ReverseYaw", "GodBridge"), "Normal")

    private val options = RotationSettingsWithRotationModes(this, modeList).apply {
        strictValue.excludeWithState()
        resetTicksValue.setSupport { it && scaffoldMode != "Telly" }
    }

    // Search options
    val searchMode by choices("SearchMode", arrayOf("Area", "Center"), "Area") { scaffoldMode != "GodBridge" }
    private val minDist by float("MinDist", 0f, 0f..0.2f) { scaffoldMode !in arrayOf("GodBridge", "Telly") }

    // Zitter
    private val zitterMode by choices("Zitter", arrayOf("Off", "Teleport", "Smooth"), "Off")
    private val zitterSpeed by float("ZitterSpeed", 0.13f, 0.1f..0.3f) { zitterMode == "Teleport" }
    private val zitterStrength by float("ZitterStrength", 0.05f, 0f..0.2f) { zitterMode == "Teleport" }
    private val zitterTicks by intRange("ZitterTicks", 2..3, 0..6) { zitterMode == "Smooth" }

    private val useSneakMidAir by boolean("UseSneakMidAir", false) { zitterMode == "Smooth" }

    // Game
    val timer by float("Timer", 1f, 0.1f..10f)
    private val speedModifier by float("SpeedModifier", 1f, 0f..2f)
    private val speedLimiter by boolean("SpeedLimiter", false) { !slow }
    private val speedLimit by float("SpeedLimit", 0.11f, 0.01f..0.12f) { !slow && speedLimiter }
    private val slow by boolean("Slow", false)
    private val slowGround by boolean("SlowOnlyGround", false) { slow }
    private val slowSpeed by float("SlowSpeed", 0.6f, 0.2f..0.8f) { slow }

    // Jump Strafe
    private val jumpStrafe by boolean("JumpStrafe", false)
    private val jumpStraightStrafe by floatRange("JumpStraightStrafe", 0.4f..0.45f, 0.1f..1f) { jumpStrafe }
    private val jumpDiagonalStrafe by floatRange("JumpDiagonalStrafe", 0.4f..0.45f, 0.1f..1f) { jumpStrafe }

    // Safety
    private val sameY by boolean("SameY", false) { scaffoldMode != "GodBridge" }
    private val jumpOnUserInput by boolean("JumpOnUserInput", true) { sameY && scaffoldMode != "GodBridge" }

    private val safeWalkValue = boolean("SafeWalk", true) { scaffoldMode != "GodBridge" }
    private val airSafe by boolean("AirSafe", false) { safeWalkValue.isActive() }

    // Visuals
    private val mark by boolean("Mark", false).subjective()
    private val trackCPS by boolean("TrackCPS", false).subjective()

    // Target placement
    var placeRotation: PlaceRotation? = null

    // Launch position
    private var launchY = -999

    val shouldJumpOnInput
        get() = !jumpOnUserInput || !mc.gameSettings.keyBindJump.isKeyDown && mc.thePlayer.posY >= launchY && !mc.thePlayer.onGround

    private val shouldKeepLaunchPosition
        get() = sameY && shouldJumpOnInput && scaffoldMode != "GodBridge"

    // Zitter
    private var zitterDirection = false

    // Delay
    private val delayTimer = object : DelayTimer(delay.first, delay.last, MSTimer()) {
        override fun hasTimePassed() = !placeDelayValue.isActive() || super.hasTimePassed()
    }

    private val zitterTickTimer = TickDelayTimer(zitterTicks.first, zitterTicks.last)

    // Eagle
    private var placedBlocksWithoutEagle = 0

    var eagleSneaking = false

    private var requestedStopSneak = false

    private val isEagleEnabled
        get() = eagle != "Off" && !shouldGoDown && scaffoldMode != "GodBridge"

    // Downwards
    val shouldGoDown
        get() = down && !sameY && GameSettings.isKeyDown(mc.gameSettings.keyBindSneak) && scaffoldMode !in arrayOf(
            "GodBridge", "Telly"
        ) && blocksAmount() > 1

    // Current rotation
    private val currRotation
        get() = RotationUtils.currentRotation ?: mc.thePlayer.rotation

    // Extra clicks
    private var extraClick = ExtraClickInfo(TimeUtils.randomClickDelay(extraClickCPS.first, extraClickCPS.last), 0L, 0)

    // GodBridge
    private var blocksPlacedUntilJump = 0

    private val isManualJumpOptionActive
        get() = scaffoldMode == "GodBridge" && !jumpAutomatically

    private var blocksToJump = blocksToJumpRange.random()

    private val isGodBridgeEnabled
        get() = scaffoldMode == "GodBridge" || scaffoldMode == "Normal" && options.rotationMode == "GodBridge"

    private var godBridgeTargetRotation: Rotation? = null

    private var godBridgeAlignmentTicks = 0
    private var godBridgeInjectedStrafe = false
    private var godBridgeUserMoveForward = 0f
    private var godBridgeUserMoveStrafe = 0f
    private var godBridgeDebugUntilTick = 0
    private var godBridgeWasWaiting = false
    private var raytraceTracerStart: Vec3? = null
    private var raytraceTracerEnd: Vec3? = null
    private var raytraceTracerHit = false

    private val isLookingDiagonally: Boolean
        get() {
            val player = mc.thePlayer ?: return false

            val directionDegree = MovementUtils.direction.toDegreesF()

            // Round the direction rotation to the nearest multiple of 45 degrees so that way we check if the player faces diagonally
            val yaw = round(abs(MathHelper.wrapAngleTo180_float(directionDegree)) / 45f) * 45f

            val isYawDiagonal = yaw % 90 != 0f
            val isMovingDiagonal = player.movementInput.moveForward != 0f && player.movementInput.moveStrafe == 0f
            val isStrafing = mc.gameSettings.keyBindRight.isKeyDown || mc.gameSettings.keyBindLeft.isKeyDown

            return isYawDiagonal && (isMovingDiagonal || isStrafing)
        }

    // Telly
    private var ticksUntilJump = 0
    private var blocksUntilAxisChange = 0
    private var jumpTicks = jumpTicksRange.random()
    private var horizontalPlacements = horizontalPlacementsRange.random()
    private var verticalPlacements = verticalPlacementsRange.random()
    private val shouldPlaceHorizontally
        get() = scaffoldMode == "Telly" && mc.thePlayer.isMoving && (startHorizontally && blocksUntilAxisChange <= horizontalPlacements || !startHorizontally && blocksUntilAxisChange > verticalPlacements)

    // <--

    // Enabling module
    override fun onEnable() {
        val player = mc.thePlayer ?: return

        launchY = player.posY.roundToInt()
        blocksUntilAxisChange = 0
        resetGodBridgeAlignment()
        godBridgeWasWaiting = false
        godBridgeDebugUntilTick = if (godBridgeDebug) ClientUtils.runTimeTicks + 120 else 0

        debugGodBridge("ENABLE pos=${formatVec(player.posX, player.posY, player.posZ)} yaw=${player.rotationYaw.format()} mode=$scaffoldMode")
    }

    // Events
    val onUpdate = loopSequence {
        val player = mc.thePlayer ?: return@loopSequence

        if (mc.playerController.currentGameType == WorldSettings.GameType.SPECTATOR) return@loopSequence

        mc.timer.timerSpeed = timer

        // Telly
        if (player.onGround) ticksUntilJump++

        if (shouldGoDown) {
            mc.gameSettings.keyBindSneak.pressed = false
        }

        if (slow) {
            if (!slowGround || slowGround && player.onGround) {
                player.motionX *= slowSpeed
                player.motionZ *= slowSpeed
            }
        }

        // Eagle
        if (isEagleEnabled) {
            var dif = 0.5
            val blockPos = BlockPos(player).down()

            for (side in EnumFacing.entries) {
                if (side.axis == EnumFacing.Axis.Y) {
                    continue
                }

                val neighbor = blockPos.offset(side)

                if (neighbor.isReplaceable) {
                    val calcDif = (if (side.axis == EnumFacing.Axis.Z) {
                        abs(neighbor.z + 0.5 - player.posZ)
                    } else {
                        abs(neighbor.x + 0.5 - player.posX)
                    }) - 0.5

                    if (calcDif < dif) {
                        dif = calcDif
                    }
                }
            }

            val blockSneaking = WaitTickUtils.hasScheduled("block")
            val alreadySneaking = WaitTickUtils.hasScheduled("sneak")

            val options = mc.gameSettings

            run {
                if (placedBlocksWithoutEagle < blocksToEagle.random() && !alreadySneaking && !blockSneaking && !eagleSneaking && !requestedStopSneak) {
                    return@run
                }

                val eagleCondition = when (eagleMode) {
                    "OnGround" -> player.onGround
                    "InAir" -> !player.onGround
                    else -> true
                }

                // For better sneak support we could move this to MovementInputEvent
                val pressedOnKeyboard = Keyboard.isKeyDown(options.keyBindSneak.keyCode)

                var shouldEagle =
                    eagleCondition && (blockPos.isReplaceable || dif < edgeDistance) || pressedOnKeyboard

                val shouldSchedule = !requestedStopSneak

                if (requestedStopSneak) {
                    requestedStopSneak = false

                    if (!player.onGround) {
                        shouldEagle = pressedOnKeyboard
                    }
                } else if (blockSneaking || alreadySneaking) {
                    return@run
                }

                if (eagle == "Silent") {
                    if (eagleSneaking != shouldEagle) {
                        sendPacket(
                            C0BPacketEntityAction(
                                player, if (shouldEagle) {
                                    C0BPacketEntityAction.Action.START_SNEAKING
                                } else {
                                    C0BPacketEntityAction.Action.STOP_SNEAKING
                                }
                            )
                        )

                        // Adjust speed when silent sneaking
                        if (adjustedSneakSpeed && shouldEagle) {
                            player.motionX *= eagleSpeed
                            player.motionZ *= eagleSpeed
                        }
                    }

                    eagleSneaking = shouldEagle
                } else {
                    options.keyBindSneak.pressed = shouldEagle
                    eagleSneaking = shouldEagle
                }

                if (eagleSneaking && shouldSchedule) {
                    if (useMaxSneakTime) {
                        WaitTickUtils.conditionalSchedule("sneak") { elapsed ->
                            (elapsed >= maxSneakTicks.random() + 1).also { requestedStopSneak = it }
                        }
                    }

                    if (blockSneakingAgainUntilOnGround && !player.onGround) {
                        WaitTickUtils.conditionalSchedule("block") {
                            mc.thePlayer?.onGround.also { if (it != false) requestedStopSneak = true } ?: true
                        }
                    }
                }

                placedBlocksWithoutEagle = 0
            }
        }

        if (player.onGround) {
            // Still a thing?
            if (scaffoldMode == "Rewinside") {
                MovementUtils.strafe(0.2F)
                player.motionY = 0.0
            }
        }
    }

    val onStrafe = handler<StrafeEvent> {
        val player = mc.thePlayer ?: return@handler

        // Jumping needs to be done here, so it doesn't get detected by movement-sensitive anti-cheats.
        if (scaffoldMode == "Telly" && player.onGround && player.isMoving && currRotation == player.rotation && ticksUntilJump >= jumpTicks) {
            player.tryJump()

            ticksUntilJump = 0
            jumpTicks = jumpTicksRange.random()
        }
    }

    val onRotationUpdate = handler<RotationUpdateEvent> {
        val player = mc.thePlayer ?: return@handler

        if (player.ticksExisted == 1) {
            launchY = player.posY.roundToInt()
        }

        val rotation = RotationUtils.currentRotation

        update()

        val ticks = if (options.keepRotation) {
            if (scaffoldMode == "Telly") 1 else options.resetTicks
        } else {
            if (isGodBridgeEnabled) options.resetTicks else RotationUtils.resetTicks
        }

        if (!Tower.isTowering && isGodBridgeEnabled && options.rotationsActive) {
            generateGodBridgeRotations(ticks)

            return@handler
        }

        if (options.rotationsActive && rotation != null) {
            val placeRotation = this.placeRotation?.rotation ?: rotation

            if (RotationUtils.resetTicks != 0 || options.keepRotation) {
                setRotation(placeRotation, ticks)
            }
        }
    }

    val onTick = handler<GameTickEvent> {
        val target = placeRotation?.placeInfo

        val raycastProperly = !(scaffoldMode == "Expand" && expandLength > 1 || shouldGoDown) && options.rotationsActive

        /**
         * Calculate block raytracing process once to simulate proper vanilla ray-cast update logic.
         *
         * @see net.minecraft.client.Minecraft.runTick Line 1345
         */
        val raycast = performBlockRaytrace(currRotation, mc.playerController.blockReachDistance)
        captureGodBridgeRaytraceTracer(currRotation, raycast)

        debugGodBridge(
            "TICK target=${target?.blockPos?.format()}/${target?.enumFacing} ray=${raycast?.blockPos?.format()}/${raycast?.sideHit} proper=$raycastProperly"
        )

        var alreadyPlaced = false

        if (extraClicks) {
            val doubleClick = if (simulateDoubleClicking) RandomUtils.nextInt(-1, 1) else 0

            val clicks = extraClick.clicks + doubleClick

            repeat(clicks) {
                extraClick.clicks--

                doPlaceAttempt(raycast, it + 1 == clicks) { alreadyPlaced = true }
            }
        }

        if (target == null) {
            if (placeDelayValue.isActive()) {
                delayTimer.reset()
            }
            debugGodBridge("TICK_NO_TARGET ray=${raycast?.blockPos?.format()}/${raycast?.sideHit}")
            return@handler
        }

        // Change/Schedule slot once per tick according to vanilla-logic
        if (alreadyPlaced || SilentHotbar.modifiedThisTick) {
            debugGodBridge("TICK_SKIP alreadyPlaced=$alreadyPlaced hotbarModified=${SilentHotbar.modifiedThisTick}")
            return@handler
        }

        raycast.let {
            if (!options.rotationsActive || it != null && it.blockPos == target.blockPos && (!raycastProperly || it.sideHit == target.enumFacing)) {
                val result = if (raycastProperly && it != null) {
                    PlaceInfo(it.blockPos, it.sideHit, it.hitVec)
                } else {
                    target
                }

                place(result)
            } else {
                debugGodBridge("TICK_MISMATCH target=${target.blockPos.format()}/${target.enumFacing} ray=${it?.blockPos?.format()}/${it?.sideHit}")
            }
        }
    }

    val onSneakSlowDown = handler<SneakSlowDownEvent> { event ->
        if (!isEagleEnabled || eagle != "Normal") {
            return@handler
        }

        event.forward *= eagleSpeed / 0.3f
        event.strafe *= eagleSpeed / 0.3f
    }

    val onMovementInput = handler<MovementInputEvent> { event ->
        val player = mc.thePlayer ?: return@handler

        godBridgeUserMoveForward = event.originalInput.moveForward
        godBridgeUserMoveStrafe = event.originalInput.moveStrafe
        godBridgeInjectedStrafe = false

        if (!isGodBridgeEnabled || !player.onGround) {
            resetGodBridgeAlignment()
            return@handler
        }

        if (!waitForRots) {
            resetGodBridgeAlignment()
            godBridgeWasWaiting = false
        } else {
            val alignmentPending = applyGodBridgeAlignmentInput(event.originalInput)
            val rotationDiff = godBridgeTargetRotation?.let { rotationDifference(it, currRotation) }
            val rotationPending = rotationDiff?.let { it > getFixedAngleDelta() } ?: false

            val waitPending = alignmentPending || rotationPending

            event.originalInput.sneak = event.originalInput.sneak || waitPending

            debugGodBridgeInput(
                "MI",
                event.originalInput,
                waitPending,
                alignmentPending,
                rotationPending,
                rotationDiff
            )

            if (godBridgeWasWaiting && !waitPending) {
                godBridgeDebugUntilTick = ClientUtils.runTimeTicks + 40
                debugGodBridge("WAIT_RELEASE place=${placeRotation != null} ray=${debugRaytraceSummary()}")
            }

            godBridgeWasWaiting = waitPending

            if (waitPending) {
                return@handler
            }
        }

        val simPlayer = SimulatedPlayer.fromClientPlayer(RotationUtils.modifiedInput)

        simPlayer.rotationYaw = currRotation.yaw

        simPlayer.tick()

        if (!simPlayer.onGround && !isManualJumpOptionActive || blocksPlacedUntilJump > blocksToJump) {
            event.originalInput.jump = true
            debugGodBridge(
                "JUMP simGround=${simPlayer.onGround} manual=$isManualJumpOptionActive placedUntilJump=$blocksPlacedUntilJump/$blocksToJump"
            )

            blocksPlacedUntilJump = 0

            blocksToJump = blocksToJumpRange.random()
        }
    }

    fun update() {
        val player = mc.thePlayer ?: return
        val holdingItem = player.heldItem?.item is ItemBlock

        if (!holdingItem && (autoBlock == "Off" || InventoryUtils.findBlockInHotbar() == null)) {
            return
        }

        findBlock(scaffoldMode == "Expand" && expandLength > 1, searchMode == "Area")
    }

    private fun setRotation(rotation: Rotation, ticks: Int) {
        val player = mc.thePlayer ?: return

        if (scaffoldMode == "Telly" && player.isMoving) {
            if (player.airTicks < ticksUntilRotation.random() && ticksUntilJump >= jumpTicks) {
                return
            }
        }

        setTargetRotation(rotation, options, ticks)
    }

    // Search for new target block
    private fun findBlock(expand: Boolean, area: Boolean) {
        val player = mc.thePlayer ?: return

        if (!shouldKeepLaunchPosition) launchY = player.posY.roundToInt()

        val blockPosition = if (shouldGoDown) {
            if (player.posY == player.posY.roundToInt() + 0.5) {
                BlockPos(player.posX, player.posY - 0.6, player.posZ)
            } else {
                BlockPos(player.posX, player.posY - 0.6, player.posZ).down()
            }
        } else if (shouldKeepLaunchPosition && launchY <= player.posY) {
            BlockPos(player.posX, launchY - 1.0, player.posZ)
        } else if (player.posY == player.posY.roundToInt() + 0.5) {
            BlockPos(player)
        } else {
            BlockPos(player).down()
        }

        if (!expand && (!blockPosition.isReplaceable || search(
                blockPosition, !shouldGoDown, area, shouldPlaceHorizontally
            ))
        ) {
            return
        }

        if (expand) {
            val yaw = player.rotationYaw.toRadiansD()
            val x = if (omniDirectionalExpand) -sin(yaw).roundToInt() else player.horizontalFacing.directionVec.x
            val z = if (omniDirectionalExpand) cos(yaw).roundToInt() else player.horizontalFacing.directionVec.z

            repeat(expandLength) {
                if (search(blockPosition.add(x * it, 0, z * it), false, area)) return
            }
            return
        }

        val (horizontal, vertical) = if (scaffoldMode == "Telly") {
            5 to 3
        } else if (allowClutching) {
            horizontalClutchBlocks to verticalClutchBlocks
        } else {
            1 to 1
        }

        BlockPos.getAllInBox(
            blockPosition.add(-horizontal, 0, -horizontal), blockPosition.add(horizontal, -vertical, horizontal)
        ).sortedBy {
            BlockUtils.getCenterDistance(it)
        }.forEach {
            if (it.canBeClicked() || search(it, !shouldGoDown, area, shouldPlaceHorizontally)) {
                return
            }
        }
    }

    private fun place(placeInfo: PlaceInfo) {
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        if (!delayTimer.hasTimePassed() || shouldKeepLaunchPosition && launchY - 1 != placeInfo.vec3.yCoord.toInt() && scaffoldMode != "Expand") return

        val currentSlot = SilentHotbar.currentSlot

        var stack = player.hotBarSlot(currentSlot).stack

        //TODO: blacklist more blocks than only bushes
        if (stack == null || stack.item !is ItemBlock || (stack.item as ItemBlock).block is BlockBush || stack.stackSize <= 0 || sortByHighestAmount || earlySwitch) {
            val blockSlot = if (sortByHighestAmount) {
                InventoryUtils.findLargestBlockStackInHotbar() ?: return
            } else if (earlySwitch) {
                InventoryUtils.findBlockStackInHotbarGreaterThan(amountBeforeSwitch)
                    ?: InventoryUtils.findBlockInHotbar() ?: return
            } else {
                InventoryUtils.findBlockInHotbar() ?: return
            }

            stack = player.hotBarSlot(blockSlot).stack

            // Check if block is placeable on target side before switching slots
            if ((stack.item as? ItemBlock)?.canPlaceBlockOnSide(
                    world, placeInfo.blockPos, placeInfo.enumFacing, player, stack
                ) == false
            ) {
                return
            }

            if (autoBlock != "Off") {
                SilentHotbar.selectSlotSilently(this, blockSlot, render = autoBlock == "Pick", resetManually = true)
            }
        }

        tryToPlaceBlock(stack, placeInfo.blockPos, placeInfo.enumFacing, placeInfo.vec3)

        if (autoBlock == "Switch") SilentHotbar.resetSlot(this, true)

        // Since we violate vanilla slot switch logic if we send the packets now, we arrange them for the next tick
        findBlockToSwitchNextTick(stack)

        if (trackCPS) {
            CPSCounter.registerClick(CPSCounter.MouseButton.RIGHT)
        }
    }

    private fun doPlaceAttempt(raytrace: MovingObjectPosition?, lastClick: Boolean, onSuccess: () -> Unit = { }) {
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        val stack = player.hotBarSlot(SilentHotbar.currentSlot).stack ?: return

        if (stack.item !is ItemBlock || InventoryUtils.BLOCK_BLACKLIST.contains((stack.item as ItemBlock).block)) {
            return
        }

        raytrace ?: return

        val block = stack.item as ItemBlock

        val canPlaceOnUpperFace = block.canPlaceBlockOnSide(
            world, raytrace.blockPos, EnumFacing.UP, player, stack
        )

        val shouldPlace = if (placementAttempt == "Fail") {
            !block.canPlaceBlockOnSide(world, raytrace.blockPos, raytrace.sideHit, player, stack)
        } else {
            if (shouldKeepLaunchPosition) {
                raytrace.blockPos.y == launchY - 1 && !canPlaceOnUpperFace
            } else if (shouldPlaceHorizontally) {
                !canPlaceOnUpperFace
            } else {
                raytrace.blockPos.y <= player.posY.toInt() - 1 && !(raytrace.blockPos.y == player.posY.toInt() - 1 && canPlaceOnUpperFace && raytrace.sideHit == EnumFacing.UP)
            }
        }

        if (!raytrace.typeOfHit.isBlock || !shouldPlace) {
            return
        }

        tryToPlaceBlock(stack, raytrace.blockPos, raytrace.sideHit, raytrace.hitVec, attempt = true) { onSuccess() }

        // Since we violate vanilla slot switch logic if we send the packets now, we arrange them for the next tick
        if (lastClick) {
            findBlockToSwitchNextTick(stack)
        }

        if (trackCPS) {
            CPSCounter.registerClick(CPSCounter.MouseButton.RIGHT)
        }
    }

    // Disabling module
    override fun onDisable() {
        val player = mc.thePlayer ?: return

        if (!GameSettings.isKeyDown(mc.gameSettings.keyBindSneak)) {
            mc.gameSettings.keyBindSneak.pressed = false
            if (eagleSneaking && player.isSneaking) {
                //sendPacket(C0BPacketEntityAction(player, C0BPacketEntityAction.Action.STOP_SNEAKING))

                /**
                 * Should prevent false flag by some AntiCheat (Ex: Verus)
                 */
                player.isSneaking = false
            }
        }

        if (!GameSettings.isKeyDown(mc.gameSettings.keyBindRight)) {
            mc.gameSettings.keyBindRight.pressed = false
        }
        if (!GameSettings.isKeyDown(mc.gameSettings.keyBindLeft)) {
            mc.gameSettings.keyBindLeft.pressed = false
        }

        if (autoF5) {
            mc.gameSettings.thirdPersonView = 0
        }

        placeRotation = null
        raytraceTracerStart = null
        raytraceTracerEnd = null
        mc.timer.timerSpeed = 1f

        SilentHotbar.resetSlot(this)

        options.instant = false
    }

    // Entity movement event
    val onMove = handler<MoveEvent> { event ->
        val player = mc.thePlayer ?: return@handler

        if (!safeWalkValue.isActive() || shouldGoDown) {
            return@handler
        }

        if (airSafe || player.onGround) {
            event.isSafeWalk = true
        }
    }

    val jumpHandler = handler<JumpEvent> { event ->
        if (!jumpStrafe) return@handler

        if (event.eventState == EventState.POST) {
            MovementUtils.strafe(
                (if (!isLookingDiagonally) jumpStraightStrafe else jumpDiagonalStrafe).random()
            )
        }
    }

    // Visuals
    val onRender3D = handler<Render3DEvent> {
        val player = mc.thePlayer ?: return@handler

        val shouldBother =
            !(shouldGoDown || scaffoldMode == "Expand" && expandLength > 1) && extraClicks && (player.isMoving || MovementUtils.speed > 0.03)

        if (shouldBother) {
            currRotation.let {
                performBlockRaytrace(it, mc.playerController.blockReachDistance)?.let { raytrace ->
                    val timePassed = System.currentTimeMillis() - extraClick.lastClick >= extraClick.delay

                    if (raytrace.typeOfHit.isBlock && timePassed) {
                        extraClick = ExtraClickInfo(
                            TimeUtils.randomClickDelay(extraClickCPS.first, extraClickCPS.last),
                            System.currentTimeMillis(),
                            extraClick.clicks + 1
                        )
                    }
                }
            }
        }

        if (!mark) {
            if (godBridgeRaytraceTracer && isGodBridgeEnabled) {
                drawGodBridgeRaytraceTracer()
            }

            return@handler
        }

        if (godBridgeRaytraceTracer && isGodBridgeEnabled) {
            drawGodBridgeRaytraceTracer()
        }

        repeat(if (scaffoldMode == "Expand") expandLength + 1 else 2) {
            val yaw = player.rotationYaw.toRadiansD()
            val x = if (omniDirectionalExpand) -sin(yaw).roundToInt() else player.horizontalFacing.directionVec.x
            val z = if (omniDirectionalExpand) cos(yaw).roundToInt() else player.horizontalFacing.directionVec.z
            val blockPos = BlockPos(
                player.posX + x * it,
                if (shouldKeepLaunchPosition && launchY <= player.posY) launchY - 1.0 else player.posY - (if (player.posY == player.posY + 0.5) 0.0 else 1.0) - if (shouldGoDown) 1.0 else 0.0,
                player.posZ + z * it
            )
            val placeInfo = PlaceInfo.get(blockPos)

            if (blockPos.isReplaceable && placeInfo != null) {
                RenderUtils.drawBlockBox(blockPos, Color(68, 117, 255, 100), false)
                return@handler
            }
        }
    }

    /**
     * Search for placeable block
     *
     * @param blockPosition pos
     * @param raycast visible
     * @param area spot
     * @return
     */

    fun search(
        blockPosition: BlockPos,
        raycast: Boolean,
        area: Boolean,
        horizontalOnly: Boolean = false,
    ): Boolean {
        val player = mc.thePlayer ?: return false

        options.instant = false

        if (!blockPosition.isReplaceable) {
            if (autoF5) mc.gameSettings.thirdPersonView = 0
            return false
        } else {
            if (autoF5 && mc.gameSettings.thirdPersonView != 1) mc.gameSettings.thirdPersonView = 1
        }

        val maxReach = mc.playerController.blockReachDistance

        val eyes = player.eyes
        var placeRotation: PlaceRotation? = null

        var currPlaceRotation: PlaceRotation?

        for (side in EnumFacing.entries) {
            if (horizontalOnly && side.axis == EnumFacing.Axis.Y) {
                continue
            }

            val neighbor = blockPosition.offset(side)

            if (!neighbor.canBeClicked()) {
                continue
            }

            if (!area || isGodBridgeEnabled) {
                currPlaceRotation =
                    findTargetPlace(blockPosition, neighbor, Vec3(0.5, 0.5, 0.5), side, eyes, maxReach, raycast)
                        ?: continue

                placeRotation = compareDifferences(currPlaceRotation, placeRotation)
            } else {
                for (x in 0.1..0.9) {
                    for (y in 0.1..0.9) {
                        for (z in 0.1..0.9) {
                            currPlaceRotation =
                                findTargetPlace(blockPosition, neighbor, Vec3(x, y, z), side, eyes, maxReach, raycast)
                                    ?: continue

                            placeRotation = compareDifferences(currPlaceRotation, placeRotation)
                        }
                    }
                }
            }
        }

        placeRotation ?: return false

        if (options.rotationsActive && !isGodBridgeEnabled) {
            val rotationDifference = rotationDifference(placeRotation.rotation, currRotation)
            val rotationDifference2 = rotationDifference(placeRotation.rotation / 90F, currRotation / 90F)

            val simPlayer = SimulatedPlayer.fromClientPlayer(player.movementInput)
            simPlayer.tick()

            // We don't want to use block safe all the time, so we check if it's not needed.
            options.instant =
                blockSafe && simPlayer.fallDistance > player.fallDistance + 0.05 && rotationDifference > rotationDifference2 / 2

            setRotation(placeRotation.rotation, if (scaffoldMode == "Telly") 1 else options.resetTicks)
        }

        this.placeRotation = placeRotation
        return true
    }

    /**
     * For expand scaffold, fixes vector values that should match according to direction vector
     */
    private fun modifyVec(original: Vec3, direction: EnumFacing, pos: Vec3, shouldModify: Boolean): Vec3 {
        if (!shouldModify) {
            return original
        }

        val x = original.xCoord
        val y = original.yCoord
        val z = original.zCoord

        val side = direction.opposite

        return when (side.axis ?: return original) {
            EnumFacing.Axis.Y -> Vec3(x, pos.yCoord + side.directionVec.y.coerceAtLeast(0), z)
            EnumFacing.Axis.X -> Vec3(pos.xCoord + side.directionVec.x.coerceAtLeast(0), y, z)
            EnumFacing.Axis.Z -> Vec3(x, y, pos.zCoord + side.directionVec.z.coerceAtLeast(0))
        }

    }

    private fun findTargetPlace(
        pos: BlockPos, offsetPos: BlockPos, vec3: Vec3, side: EnumFacing, eyes: Vec3, maxReach: Float, raycast: Boolean,
    ): PlaceRotation? {
        val world = mc.theWorld ?: return null

        val vec = (Vec3(pos) + vec3).addVector(
            side.directionVec.x * vec3.xCoord, side.directionVec.y * vec3.yCoord, side.directionVec.z * vec3.zCoord
        )

        val distance = eyes.distanceTo(vec)

        if (raycast && (distance > maxReach || world.rayTraceBlocks(eyes, vec, false, true, false) != null)) {
            return null
        }

        val diff = vec - eyes

        if (side.axis != EnumFacing.Axis.Y) {
            val dist = abs(if (side.axis == EnumFacing.Axis.Z) diff.zCoord else diff.xCoord)

            if (dist < minDist && scaffoldMode != "Telly") {
                return null
            }
        }

        var rotation = toRotation(vec, false)

        val roundYaw90 = round(rotation.yaw / 90f) * 90f
        val roundYaw45 = round(rotation.yaw / 45f) * 45f

        rotation = when (options.rotationMode) {
            "Stabilized" -> Rotation(roundYaw45, rotation.pitch)
            "ReverseYaw" -> Rotation(if (!isLookingDiagonally) roundYaw90 else roundYaw45, rotation.pitch)
            else -> rotation
        }.fixedSensitivity()

        // If the current rotation already looks at the target block and side, then return right here
        performBlockRaytrace(currRotation, maxReach)?.let { raytrace ->
            if (raytrace.blockPos == offsetPos && (!raycast || raytrace.sideHit == side.opposite)) {
                return PlaceRotation(
                    PlaceInfo(
                        raytrace.blockPos, side.opposite, modifyVec(raytrace.hitVec, side, Vec3(offsetPos), !raycast)
                    ), currRotation
                )
            }
        }

        val raytrace = performBlockRaytrace(rotation, maxReach) ?: return null

        val multiplier = if (options.legitimize) 3 else 1

        if (raytrace.blockPos == offsetPos && (!raycast || raytrace.sideHit == side.opposite) && canUpdateRotation(
                currRotation, rotation, multiplier
            )
        ) {
            return PlaceRotation(
                PlaceInfo(
                    raytrace.blockPos, side.opposite, modifyVec(raytrace.hitVec, side, Vec3(offsetPos), !raycast)
                ), rotation
            )
        }

        return null
    }

    private fun performBlockRaytrace(rotation: Rotation, maxReach: Float): MovingObjectPosition? {
        val player = mc.thePlayer ?: return null
        val world = mc.theWorld ?: return null

        val eyes = player.eyes
        val rotationVec = getVectorForRotation(rotation)

        val reach = eyes + (rotationVec * maxReach.toDouble())

        return world.rayTraceBlocks(eyes, reach, false, false, true)
    }

    private fun captureGodBridgeRaytraceTracer(rotation: Rotation, raytrace: MovingObjectPosition?) {
        if (!godBridgeRaytraceTracer || !isGodBridgeEnabled) {
            return
        }

        val player = mc.thePlayer ?: return
        val eyes = player.eyes
        val reach = eyes + (getVectorForRotation(rotation) * mc.playerController.blockReachDistance.toDouble())

        raytraceTracerStart = eyes
        raytraceTracerEnd = raytrace?.hitVec ?: reach
        raytraceTracerHit = raytrace?.hitVec != null
    }

    private fun drawGodBridgeRaytraceTracer() {
        val start = raytraceTracerStart ?: return
        val end = raytraceTracerEnd ?: return
        val renderPos = mc.renderManager.renderPos
        val startRender = start - renderPos
        val endRender = end - renderPos

        glPushMatrix()
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_BLEND)
        glEnable(GL_LINE_SMOOTH)
        glLineWidth(2.5f)
        glDisable(GL_TEXTURE_2D)
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)

        if (raytraceTracerHit) {
            glColor4f(0.1f, 1.0f, 0.2f, 0.9f)
        } else {
            glColor4f(1.0f, 0.1f, 0.1f, 0.9f)
        }

        glBegin(GL_LINES)
        glVertex3d(startRender.xCoord, startRender.yCoord, startRender.zCoord)
        glVertex3d(endRender.xCoord, endRender.yCoord, endRender.zCoord)
        glEnd()

        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_LINE_SMOOTH)
        glDisable(GL_BLEND)
        glColor4f(1f, 1f, 1f, 1f)
        glPopMatrix()
    }

    private fun compareDifferences(
        new: PlaceRotation, old: PlaceRotation?, rotation: Rotation = currRotation,
    ): PlaceRotation {
        if (old == null || rotationDifference(new.rotation, rotation) < rotationDifference(old.rotation, rotation)) {
            return new
        }

        return old
    }

    private fun findBlockToSwitchNextTick(stack: ItemStack) {
        if (autoBlock in arrayOf("Off", "Switch")) return

        val switchAmount = if (earlySwitch) amountBeforeSwitch else 0

        if (stack.stackSize > switchAmount) return

        val switchSlot = if (earlySwitch) {
            InventoryUtils.findBlockStackInHotbarGreaterThan(amountBeforeSwitch) ?: InventoryUtils.findBlockInHotbar()
            ?: return
        } else {
            InventoryUtils.findBlockInHotbar()
        } ?: return

        SilentHotbar.selectSlotSilently(this, switchSlot, render = autoBlock == "Pick", resetManually = true)
    }

    private fun updatePlacedBlocksForTelly() {
        if (blocksUntilAxisChange > horizontalPlacements + verticalPlacements) {
            blocksUntilAxisChange = 0

            horizontalPlacements = horizontalPlacementsRange.random()
            verticalPlacements = verticalPlacementsRange.random()
            return
        }

        blocksUntilAxisChange++
    }

    private fun tryToPlaceBlock(
        stack: ItemStack, clickPos: BlockPos, side: EnumFacing, hitVec: Vec3, attempt: Boolean = false,
        onSuccess: () -> Unit = { }
    ): Boolean {
        val thePlayer = mc.thePlayer ?: return false

        val prevSize = stack.stackSize

        val clickedSuccessfully = thePlayer.onPlayerRightClick(clickPos, side, hitVec, stack)

        debugGodBridge(
            "PLACE_TRY pos=${clickPos.format()} side=$side hit=${formatVec(hitVec.xCoord, hitVec.yCoord, hitVec.zCoord)} attempt=$attempt stack=${stack.displayName}"
        )

        if (clickedSuccessfully) {
            if (!attempt) {
                delayTimer.reset()

                if (thePlayer.onGround) {
                    thePlayer.motionX *= speedModifier
                    thePlayer.motionZ *= speedModifier
                }
            }

            if (swing) thePlayer.swingItem()
            else sendPacket(C0APacketAnimation())

            if (isManualJumpOptionActive) blocksPlacedUntilJump++

            updatePlacedBlocksForTelly()

            if (stack.stackSize <= 0) {
                thePlayer.inventory.mainInventory[SilentHotbar.currentSlot] = null
                ForgeEventFactory.onPlayerDestroyItem(thePlayer, stack)
            } else if (stack.stackSize != prevSize || mc.playerController.isInCreativeMode) mc.entityRenderer.itemRenderer.resetEquippedProgress()

            placeRotation = null

            placedBlocksWithoutEagle++

            onSuccess()
            debugGodBridge("PLACE_OK pos=${clickPos.format()} side=$side size=$prevSize->${stack.stackSize}")
        } else {
            debugGodBridge("PLACE_FAIL pos=${clickPos.format()} side=$side stackSize=${stack.stackSize}")
            if (thePlayer.sendUseItem(stack)) mc.entityRenderer.itemRenderer.resetEquippedProgress2()
        }

        return clickedSuccessfully
    }

    fun handleMovementOptions(input: MovementInput) {
        val player = mc.thePlayer ?: return

        if (!state) {
            return
        }

        if (!slow && speedLimiter && MovementUtils.speed > speedLimit) {
            input.moveStrafe = 0f
            input.moveForward = 0f
            return
        }

        when (zitterMode.lowercase()) {
            "off" -> {
                return
            }

            "smooth" -> {
                val notOnGround = !player.onGround || !player.isCollidedVertically

                if (player.onGround) {
                    input.sneak = eagleSneaking || GameSettings.isKeyDown(mc.gameSettings.keyBindSneak)
                }

                if (input.jump || mc.gameSettings.keyBindJump.isKeyDown || notOnGround) {
                    zitterTickTimer.reset()

                    if (useSneakMidAir) {
                        input.sneak = true
                    }

                    if (!notOnGround && !input.jump) {
                        // Attempt to move against the direction
                        input.moveStrafe = if (zitterDirection) 1f else -1f
                    } else {
                        input.moveStrafe = 0f
                    }

                    zitterDirection = !zitterDirection

                    // Recreate input in case the user was indeed pressing inputs
                    if (mc.gameSettings.keyBindLeft.isKeyDown) {
                        input.moveStrafe++
                    }

                    if (mc.gameSettings.keyBindRight.isKeyDown) {
                        input.moveStrafe--
                    }
                    return
                }

                if (zitterTickTimer.hasTimePassed()) {
                    zitterDirection = !zitterDirection
                    zitterTickTimer.reset()
                } else {
                    zitterTickTimer.update()
                }

                if (zitterDirection) {
                    input.moveStrafe = -1f
                } else {
                    input.moveStrafe = 1f
                }
            }

            "teleport" -> {
                MovementUtils.strafe(zitterSpeed)
                val yaw = (player.rotationYaw + if (zitterDirection) 90.0 else -90.0).toRadians()
                player.motionX -= sin(yaw) * zitterStrength
                player.motionZ += cos(yaw) * zitterStrength
                zitterDirection = !zitterDirection
            }
        }
    }

    private var isOnRightSide = false

    /**
     * God-bridge rotation generation method from Nextgen
     *
     * Credits to @Ell1ott
     */
    private fun generateGodBridgeRotations(ticks: Int) {
        val player = mc.thePlayer ?: return

        val movingYaw = getGodBridgeMovingYaw()
        val isMovingStraight = isGodBridgeMovingStraight(movingYaw)

        if (!player.isNearEdge(2.5f)) return

        if (!player.isMoving) {
            placeRotation?.run {
                val axisMovement = floor(this.rotation.yaw / 90) * 90

                val yaw = axisMovement + 45f
                val pitch = 75f

                setRotation(Rotation(yaw, pitch), ticks)
                return
            }

            if (!options.keepRotation) return
        }

        val rotation = if (isMovingStraight) {
            if (player.onGround) {
                updateGodBridgeSide(movingYaw)
            }

            val side = if (options.applyServerSide) {
                if (isOnRightSide) 45f else -45f
            } else 0f

            Rotation(movingYaw + side, if (useOptimizedPitch) 73.5f else customGodPitch)
        } else {
            Rotation(movingYaw, 75.6f)
        }.fixedSensitivity()

        godBridgeTargetRotation = rotation

        debugGodBridge(
            "ROT movingYaw=${movingYaw.format()} straight=$isMovingStraight right=$isOnRightSide rot=${rotation.yaw.format()}/${rotation.pitch.format()} keep=${options.keepRotation} applyServer=${options.applyServerSide} strafe=${options.strafe}"
        )

        setRotation(rotation, ticks)
    }

    private fun getGodBridgeMovingYaw(): Float {
        val player = mc.thePlayer ?: return 0f

        val direction = if (options.applyServerSide) {
            MovementUtils.direction.toDegreesF() + 180f
        } else {
            MathHelper.wrapAngleTo180_float(player.rotationYaw)
        }

        return round(direction / 45f) * 45f
    }

    private fun isGodBridgeMovingStraight(movingYaw: Float): Boolean {
        if (options.applyServerSide) {
            return movingYaw % 90f == 0f
        }

        val moveForward = if (godBridgeInjectedStrafe) godBridgeUserMoveForward else mc.thePlayer.movementInput.moveForward
        val moveStrafe = if (godBridgeInjectedStrafe) godBridgeUserMoveStrafe else mc.thePlayer.movementInput.moveStrafe

        return movingYaw in GOD_BRIDGE_DIAGONAL_YAWS && moveForward != 0f && moveStrafe != 0f
    }

    private fun updateGodBridgeSide(movingYaw: Float) {
        val player = mc.thePlayer ?: return

        isOnRightSide = floor(player.posX + cos(movingYaw.toRadians()) * 0.5) != floor(player.posX) || floor(
            player.posZ + sin(movingYaw.toRadians()) * 0.5
        ) != floor(player.posZ)

        val posInDirection = BlockPos(player.positionVector.offset(EnumFacing.fromAngle(movingYaw.toDouble()), 0.6))

        val isLeaningOffBlock = player.position.down().block == air
        val nextBlockIsAir = posInDirection.down().block == air

        if (isLeaningOffBlock && nextBlockIsAir) {
            isOnRightSide = !isOnRightSide
        }
    }

    private fun applyGodBridgeAlignmentInput(input: MovementInput): Boolean {
        if (!waitForRots || Tower.isTowering) {
            resetGodBridgeAlignment()
            return false
        }

        val target = getGodBridgeAlignmentTarget() ?: run {
            resetGodBridgeAlignment()
            debugGodBridge("ALIGN_NO_TARGET")
            return false
        }

        val error = target.error()

        if (abs(error) <= GOD_BRIDGE_ALIGNMENT_TOLERANCE) {
            godBridgeAlignmentTicks++
            debugGodBridge(
                "ALIGN_OK axis=${target.axis} curr=${target.current.format()} target=${target.target.format()} stable=$godBridgeAlignmentTicks"
            )
            return godBridgeAlignmentTicks < GOD_BRIDGE_ALIGNMENT_STABLE_TICKS
        }

        godBridgeAlignmentTicks = 0

        input.moveStrafe = chooseGodBridgeAlignmentStrafe(input, target)
        godBridgeInjectedStrafe = true

        debugGodBridge(
            "ALIGN_MOVE axis=${target.axis} curr=${target.current.format()} target=${target.target.format()} err=${error.format()} strafe=${input.moveStrafe.format()} f=${input.moveForward.format()}"
        )

        return true
    }

    private fun getGodBridgeAlignmentTarget(): GodBridgeAlignmentTarget? {
        val player = mc.thePlayer ?: return null
        val movingYaw = getGodBridgeMovingYaw()

        updateGodBridgeSide(movingYaw)

        val xWeight = abs(cos(movingYaw.toRadians()))
        val zWeight = abs(sin(movingYaw.toRadians()))

        return if (xWeight >= zWeight) {
            val target = if (isOnRightSide == cos(movingYaw.toRadians()).signIsPositive()) 0.85 else 0.15

            GodBridgeAlignmentTarget(
                player.posX - floor(player.posX),
                target,
                EnumFacing.Axis.X
            )
        } else {
            val target = if (isOnRightSide == sin(movingYaw.toRadians()).signIsPositive()) 0.85 else 0.15

            GodBridgeAlignmentTarget(
                player.posZ - floor(player.posZ),
                target,
                EnumFacing.Axis.Z
            )
        }
    }

    private fun chooseGodBridgeAlignmentStrafe(input: MovementInput, target: GodBridgeAlignmentTarget): Float {
        val forward = input.moveForward

        return listOf(-1f, 1f).minByOrNull {
            abs(target.errorAfter(predictGodBridgeAlignmentDelta(it, forward, target.axis)))
        } ?: input.moveStrafe
    }

    private fun predictGodBridgeAlignmentDelta(strafe: Float, forward: Float, axis: EnumFacing.Axis): Double {
        val player = mc.thePlayer ?: return 0.0
        val activeSettings = RotationUtils.activeSettings
        val rotation = RotationUtils.currentRotation

        val (calcStrafe, calcForward, yaw) = if (activeSettings?.strafe == true && rotation != null) {
            val diff = (player.rotationYaw - rotation.yaw).toRadians()

            if (activeSettings.strict) {
                Triple(strafe, forward, rotation.yaw)
            } else {
                val modifiedForward = ceil(abs(forward)) * forward.sign
                val modifiedStrafe = ceil(abs(strafe)) * strafe.sign

                Triple(
                    round(modifiedStrafe * cos(diff) - modifiedForward * sin(diff)),
                    round(modifiedForward * cos(diff) + modifiedStrafe * sin(diff)),
                    rotation.yaw
                )
            }
        } else {
            Triple(strafe, forward, player.rotationYaw)
        }

        val yawRad = yaw.toRadians()

        val deltaX = calcStrafe * cos(yawRad) - calcForward * sin(yawRad)
        val deltaZ = calcForward * cos(yawRad) + calcStrafe * sin(yawRad)

        return if (axis == EnumFacing.Axis.X) deltaX.toDouble() else deltaZ.toDouble()
    }

    private fun resetGodBridgeAlignment() {
        godBridgeAlignmentTicks = 0
        godBridgeInjectedStrafe = false
    }

    private fun Float.signIsPositive() = this >= 0f

    private fun debugGodBridgeInput(
        stage: String,
        input: MovementInput,
        waitPending: Boolean,
        alignmentPending: Boolean,
        rotationPending: Boolean,
        rotationDiff: Float?
    ) {
        val player = mc.thePlayer ?: return

        debugGodBridge(
            "$stage wait=$waitPending align=$alignmentPending rot=$rotationPending diff=${rotationDiff?.format()} " +
                "in=${input.moveForward.format()}/${input.moveStrafe.format()}/s${input.sneak} " +
                "pos=${player.posX.frac().format()}/${player.posZ.frac().format()} place=${placeRotation != null} ray=${debugRaytraceSummary()}"
        )
    }

    private fun debugGodBridge(message: String) {
        if (!godBridgeDebug || !isGodBridgeEnabled || ClientUtils.runTimeTicks > godBridgeDebugUntilTick) {
            return
        }

        val text = "[GBDBG ${ClientUtils.runTimeTicks}] $message"

        chat("§7$text")
        ClientUtils.LOGGER.info(text)
    }

    private fun debugRaytraceSummary(): String {
        val raycast = performBlockRaytrace(currRotation, mc.playerController.blockReachDistance)

        return "${raycast?.blockPos?.format()}/${raycast?.sideHit}"
    }

    private fun BlockPos.format() = "$x,$y,$z"

    private fun Double.frac() = this - floor(this)

    private fun Float.format() = "%.3f".format(this)

    private fun Double.format() = "%.3f".format(this)

    private fun formatVec(x: Double, y: Double, z: Double) = "${x.format()},${y.format()},${z.format()}"

    override val tag
        get() = if (towerMode != "None") ("$scaffoldMode | $towerMode") else scaffoldMode

    data class ExtraClickInfo(val delay: Int, val lastClick: Long, var clicks: Int)

    private data class GodBridgeAlignmentTarget(
        val current: Double,
        val target: Double,
        val axis: EnumFacing.Axis
    ) {
        fun error() = target - current

        fun errorAfter(delta: Double) = target - (current + delta)
    }

    private const val GOD_BRIDGE_ALIGNMENT_TOLERANCE = 0.03
    private const val GOD_BRIDGE_ALIGNMENT_STABLE_TICKS = 2
    private val GOD_BRIDGE_DIAGONAL_YAWS = arrayListOf(-135f, -45f, 45f, 135f)
}
