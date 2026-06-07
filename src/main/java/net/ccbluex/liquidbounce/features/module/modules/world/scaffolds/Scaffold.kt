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
    private val waitForRotsSideMove by boolean("WaitForRotationsSideMove", true) {
        isGodBridgeEnabled && waitForRots
    }
    private val waitForRotsSideOffset by float("WaitForRotationsSideOffset", 0.3f, 0f..0.5f) {
        waitForRotsSideMove
    }
    private val waitForRotsPostAlignTicks by intRange("WaitForRotationsPostAlignTicks", 2..6, 0..50) {
        waitForRotsSideMove
    }
    private val waitForRotsDebug by boolean("WaitForRotationsDebug", false) {
        isGodBridgeEnabled && waitForRots
    }.subjective()
    private val godBridgePlacementLead by boolean("PlacementLead", true) {
        isGodBridgeEnabled && waitForRots
    }
    private val godBridgeRaycastDebug by boolean("GodBridgeRaycastDebug", false) { isGodBridgeEnabled }.subjective()
    private val godBridgeWindowScanDebug by boolean("WindowScanDebug", true) {
        isGodBridgeEnabled && godBridgeRaycastDebug
    }.subjective()
    private val godBridgePhaseDebug by boolean("PhaseDebug", true) {
        isGodBridgeEnabled && godBridgeRaycastDebug
    }.subjective()
    private val godBridgeVanillaOrderDebug by boolean("VanillaOrderDebug", true) {
        isGodBridgeEnabled && godBridgeRaycastDebug
    }.subjective()
    private val useOptimizedPitch by boolean("UseOptimizedPitch", false) { isGodBridgeEnabled }
    private val customGodPitch by float(
        "GodBridgePitch", 73.5f, 0f..90f
    ) { isGodBridgeEnabled && !useOptimizedPitch }

    val jumpAutomatically by boolean("JumpAutomatically", true) { scaffoldMode == "GodBridge" }
    private val blocksToJumpRange by intRange("BlocksToJumpRange", 4..4, 1..8) {  scaffoldMode == "GodBridge" && !jumpAutomatically }

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
    private var godBridgeInjectedStrafe = false
    private var godBridgeUserMoveForward = 0f
    private var godBridgeUserMoveStrafe = 0f
    private var godBridgeReleasedInputKey: GodBridgeInputKey? = null
    private var godBridgeAlignmentDebug = ""
    private var godBridgeLastWaitDebug = ""
    private var godBridgeLastWaitDebugTick = 0
    private var godBridgeWaitPending = false
    private var godBridgeWaitSequenceActive = false
    private var godBridgePlacementWaitPending = false
    private var godBridgePlacementReleased = false
    private var godBridgePostAlignmentWaitTicks = 0
    private var godBridgeFallSneakTicks = 0
    private var godBridgeDiagonalYaw: Float? = null
    private var godBridgeDiagonalNudgeTicks = 0
    private var godBridgeDiagonalStopTicks = 0
    private var godBridgeDiagonalReleased = false
    private var godBridgeDiagonalReleasedYaw: Float? = null
    private var godBridgeDiagonalRotationDone = false
    private var godBridgeMouseOverSample: GodBridgeMouseOverSample? = null

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
        resetGodBridgeJumpCounter()
        resetGodBridgePhaseDebug()
        godBridgeMouseOverSample = null
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
        if (shouldBlockGodBridgePlacement()) {
            return@handler
        }

        val target = placeRotation?.placeInfo

        val raycastProperly = !(scaffoldMode == "Expand" && expandLength > 1 || shouldGoDown) && options.rotationsActive

        /**
         * Calculate block raytracing process once to simulate proper vanilla ray-cast update logic.
         *
         * @see net.minecraft.client.Minecraft.runTick Line 1345
         */
        val raycast = performBlockRaytrace(currRotation, mc.playerController.blockReachDistance)

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
            return@handler
        }

        // Change/Schedule slot once per tick according to vanilla-logic
        if (alreadyPlaced || SilentHotbar.modifiedThisTick) {
            return@handler
        }

        val raycastMatchesTarget = raycast != null &&
            raycast.blockPos == target.blockPos &&
            (!raycastProperly || raycast.sideHit == target.enumFacing)

        debugGodBridgeRaycast(
                "rhythm gate=${if (raycastMatchesTarget) "pass" else "miss"} " +
                "target=${formatGodBridgePlaceInfo(target)} " +
                formatGodBridgeRayWindow(target, raycast, raycastProperly) + " " +
                "rot=${formatGodBridgeRotation(currRotation)} " +
                "pos=${formatGodBridgeCurrentPositionDebug()} " +
                "motion=${formatGodBridgeMotionDebug()} " +
                "jump=$blocksPlacedUntilJump/$blocksToJump proper=$raycastProperly"
        )
        debugGodBridgeVanillaOrder(target, raycast, raycastProperly, raycastMatchesTarget)
        debugGodBridgeWindowScan(target, raycast, raycastProperly)

        raycast.let {
            if (!options.rotationsActive || raycastMatchesTarget) {
                val result = if (raycastProperly && it != null) {
                    PlaceInfo(it.blockPos, it.sideHit, it.hitVec)
                } else {
                    target
                }

                place(result)
            }
        }
    }

    val onMouseOverSample = handler<MouseOverSampleEvent> { event ->
        if (!isGodBridgeEnabled || !godBridgeRaycastDebug) {
            return@handler
        }

        godBridgeMouseOverSample = GodBridgeMouseOverSample(
            event.mouseOver,
            event.posX,
            event.posY,
            event.posZ,
            event.playerRotation,
            event.currentRotation,
            event.usesCurrentRotation,
            event.ticksExisted
        )
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
        val inputKey = getGodBridgeInputKey(event.originalInput)
        godBridgeInjectedStrafe = false

        if (!isGodBridgeEnabled) {
            resetGodBridgeWait()
            return@handler
        }

        if (waitForRots) {
            val targetRotation = godBridgeTargetRotation

            if (targetRotation == null) {
                resetGodBridgeWait()
                debugGodBridgeWait("target=null pos=${formatGodBridgePositionDebug(player.posX, player.posZ)}")
            } else if (inputKey == null) {
                resetGodBridgeWait()
                debugGodBridgeWait(
                    "skip=noInput moveF=${event.originalInput.moveForward} moveS=${event.originalInput.moveStrafe} " +
                        "moving=${player.isMoving} pos=${formatGodBridgePositionDebug(player.posX, player.posZ)}"
                )
            } else if (!player.onGround) {
                resetGodBridgeWait(clearReleasedInput = false)
                debugGodBridgeWait(
                    "skip=airborne moveF=${event.originalInput.moveForward} moveS=${event.originalInput.moveStrafe} " +
                        "moving=${player.isMoving} pos=${formatGodBridgePositionDebug(player.posX, player.posZ)}"
                )
                return@handler
            } else if (godBridgeReleasedInputKey == inputKey) {
                debugGodBridgeWait(
                    "skip=released moveF=${event.originalInput.moveForward} moveS=${event.originalInput.moveStrafe} " +
                        "moving=${player.isMoving} pos=${formatGodBridgePositionDebug(player.posX, player.posZ)}"
                )
            } else {
                if (godBridgeReleasedInputKey != null) {
                    resetGodBridgeWait()
                }

                if (applyGodBridgeWaitForRotations(event.originalInput, targetRotation)) {
                    return@handler
                }
            }
        } else {
            resetGodBridgeWait()
        }

        val fallTicksAhead = predictGodBridgeFallTicks()
        val nextTickFalls = fallTicksAhead == 1

        if (isManualJumpOptionActive && player.onGround) {
            if (godBridgeFallSneakTicks == GOD_BRIDGE_FALL_SNEAK_RELEASE_TICK) {
                godBridgeFallSneakTicks = 0
            } else {
                if (fallTicksAhead != null && godBridgeFallSneakTicks <= 0) {
                    val sneakTicks = calculateGodBridgeFallSneakTicks()

                    if (sneakTicks != null) {
                        godBridgeFallSneakTicks = sneakTicks
                    } else {
                        event.originalInput.sneak = true
                    }
                }

                if (godBridgeFallSneakTicks > 0) {
                    event.originalInput.sneak = true
                    godBridgeFallSneakTicks--

                    if (godBridgeFallSneakTicks == 0) {
                        godBridgeFallSneakTicks = GOD_BRIDGE_FALL_SNEAK_RELEASE_TICK
                    }
                }
            }
        } else {
            godBridgeFallSneakTicks = 0
        }

        if ((nextTickFalls && !isManualJumpOptionActive) || blocksPlacedUntilJump >= blocksToJump) {
            event.originalInput.jump = true

            resetGodBridgeJumpCounter()
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
        godBridgeMouseOverSample = null
        mc.timer.timerSpeed = 1f

        SilentHotbar.resetSlot(this)

        options.instant = false
        resetGodBridgeWait()
        resetGodBridgeJumpCounter()
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
            return@handler
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

        return performBlockRaytraceFromEyes(rotation, maxReach, eyes)
    }

    private fun performBlockRaytraceFromEyes(rotation: Rotation, maxReach: Float, eyes: Vec3): MovingObjectPosition? {
        val world = mc.theWorld ?: return null
        val rotationVec = getVectorForRotation(rotation)

        val reach = eyes + (rotationVec * maxReach.toDouble())

        return world.rayTraceBlocks(eyes, reach, false, false, true)
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
        if (shouldBlockGodBridgePlacement()) {
            return false
        }

        val thePlayer = mc.thePlayer ?: return false

        val prevSize = stack.stackSize

        val clickedSuccessfully = thePlayer.onPlayerRightClick(clickPos, side, hitVec, stack)

        debugGodBridgeRaycast(
            "place=${if (clickedSuccessfully) "success" else "fail"} " +
                "attempt=$attempt click=${formatGodBridgeBlockPos(clickPos)}/${side.name} " +
                "hit=${formatGodBridgeVec(hitVec - Vec3(clickPos))} " +
                "rot=${formatGodBridgeRotation(currRotation)} " +
                "pos=${formatGodBridgePositionDebug(thePlayer.posX, thePlayer.posZ)} " +
                "motion=${formatGodBridgeMotionDebug()}"
        )

        if (clickedSuccessfully) {
            debugGodBridgePlacementPhase(clickPos, side, attempt)

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
        } else {
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

    private fun getGodBridgeMovingYaw(): Float {
        if (godBridgeUserMoveForward != 0f || godBridgeUserMoveStrafe != 0f) {
            return getGodBridgeMovingYaw(godBridgeUserMoveForward, godBridgeUserMoveStrafe)
        }

        val input = mc.thePlayer?.movementInput
        if (input?.isMoving == true && godBridgeInjectedStrafe) {
            return getGodBridgeMovingYaw(godBridgeUserMoveForward, godBridgeUserMoveStrafe)
        }

        return getGodBridgeMovingYaw(input)
    }

    private fun getGodBridgeMovingYaw(input: MovementInput?) =
        getGodBridgeMovingYaw(input?.moveForward ?: 0f, input?.moveStrafe ?: 0f)

    private fun getGodBridgeMovingYaw(moveForward: Float, moveStrafe: Float): Float {
        val player = mc.thePlayer ?: return 0f

        if (!options.applyServerSide) {
            return round(MathHelper.wrapAngleTo180_float(player.rotationYaw) / 45) * 45
        }

        var yaw = player.rotationYaw
        var forward = 1f

        if (moveForward < 0f) {
            yaw += 180f
            forward = -0.5f
        } else if (moveForward > 0f) {
            forward = 0.5f
        }

        if (moveStrafe < 0f) {
            yaw += 90f * forward
        } else if (moveStrafe > 0f) {
            yaw -= 90f * forward
        }

        return round((yaw + 180f) / 45) * 45
    }

    private fun getGodBridgeInputKey(input: MovementInput): GodBridgeInputKey? {
        if (!input.isMoving) {
            return null
        }

        return GodBridgeInputKey(
            input.moveForward.sign,
            input.moveStrafe.sign,
            MathHelper.wrapAngleTo180_float(getGodBridgeMovingYaw(input))
        )
    }

    private fun getGodBridgeUserInputKey(): GodBridgeInputKey? {
        if (godBridgeUserMoveForward == 0f && godBridgeUserMoveStrafe == 0f) {
            return null
        }

        return GodBridgeInputKey(
            godBridgeUserMoveForward.sign,
            godBridgeUserMoveStrafe.sign,
            MathHelper.wrapAngleTo180_float(getGodBridgeMovingYaw(godBridgeUserMoveForward, godBridgeUserMoveStrafe))
        )
    }

    private fun getGodBridgeFacingYaw(): Float {
        val player = mc.thePlayer ?: return 0f
        return round(MathHelper.wrapAngleTo180_float(player.rotationYaw) / 45f) * 45f
    }

    private fun isGodBridgeMovingStraight(movingYaw: Float): Boolean {
        if (options.applyServerSide) {
            return movingYaw % 90f == 0f
        }

        return movingYaw in GOD_BRIDGE_DIAGONAL_YAWS && godBridgeUserMoveForward != 0f && godBridgeUserMoveStrafe != 0f
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

    private fun getGodBridgeDiagonalFacingYaw(facingYaw: Float, input: MovementInput) =
        facingYaw.takeIf { waitForRotsSideMove && input.moveForward > 0f && it in GOD_BRIDGE_DIAGONAL_YAWS }

    private fun applyGodBridgeWaitForRotations(input: MovementInput, targetRotation: Rotation): Boolean {
        val player = mc.thePlayer ?: return false
        val facingYaw = getGodBridgeFacingYaw()
        val diagonalYaw = getGodBridgeDiagonalFacingYaw(facingYaw, input)
        val inputKey = getGodBridgeInputKey(input)
        val rotationDelta = rotationDifference(targetRotation, currRotation)
        val waitingForRotation = rotationDelta > getFixedAngleDelta()

        if (godBridgePlacementReleased) {
            val postAlignPending = applyGodBridgePostAlignmentWait(input, waitSequenceFinished = false)
            godBridgePlacementWaitPending = false
            godBridgeWaitPending = postAlignPending

            if (!postAlignPending) {
                godBridgeAlignmentDebug = "wait=released"
                godBridgeReleasedInputKey = inputKey
            }

            debugGodBridgeWait(
                "sneak=${input.sneak} rotPending=$waitingForRotation " +
                    "rotDiff=${formatGodBridgeDebug(rotationDelta.toDouble())} " +
                    "postAlign=$postAlignPending alignPending=false $godBridgeAlignmentDebug " +
                    "facingYaw=$facingYaw moveF=${input.moveForward} moveS=${input.moveStrafe} " +
                    "moving=${player.isMoving} pos=${formatGodBridgePositionDebug(player.posX, player.posZ)}"
            )

            return postAlignPending
        }

        val alignmentPending = when {
            diagonalYaw != null -> applyGodBridgeDiagonalInput(input, diagonalYaw, waitingForRotation)
            waitingForRotation -> {
                resetGodBridgeAlignment()
                false
            }
            else -> {
                resetGodBridgeDiagonalAlignment()
                applyGodBridgeAlignmentInput(input)
            }
        }

        val rotationPending = waitingForRotation && diagonalYaw == null
        val waitSequencePending = rotationPending || alignmentPending
        val waitSequenceFinished = godBridgeWaitSequenceActive && !waitSequencePending
        godBridgeWaitSequenceActive = waitSequencePending

        val postAlignPending = applyGodBridgePostAlignmentWait(input, waitSequenceFinished)
        val diagonalReleased = diagonalYaw != null && godBridgeDiagonalReleased
        val releaseCandidateStarted = !godBridgePlacementReleased &&
            !waitSequencePending &&
            (waitSequenceFinished || diagonalReleased || godBridgePlacementWaitPending)
        val placementReleaseStarted = releaseCandidateStarted && !postAlignPending

        godBridgePlacementWaitPending = waitSequencePending || postAlignPending
        godBridgePlacementReleased = if (waitSequencePending || postAlignPending) false else {
            placementReleaseStarted || godBridgePlacementReleased
        }
        godBridgeWaitPending = waitSequencePending || postAlignPending

        if (godBridgePlacementReleased && !godBridgeWaitPending) {
            godBridgeReleasedInputKey = inputKey
        }

        input.sneak = input.sneak || rotationPending || alignmentPending

        if (godBridgePlacementLead && placementReleaseStarted && !postAlignPending) {
            input.moveForward = 0f
            input.moveStrafe = 0f
            godBridgeInjectedStrafe = false
            godBridgeAlignmentDebug = "$godBridgeAlignmentDebug lead=hold"
        }

        if (placementReleaseStarted) {
            godBridgeAlignmentDebug = "$godBridgeAlignmentDebug release=ready"
        }

        debugGodBridgeWait(
            "sneak=${input.sneak} rotPending=$waitingForRotation " +
                "rotDiff=${formatGodBridgeDebug(rotationDelta.toDouble())} " +
                "postAlign=$postAlignPending alignPending=$alignmentPending $godBridgeAlignmentDebug " +
                "facingYaw=$facingYaw moveF=${input.moveForward} moveS=${input.moveStrafe} " +
                "moving=${player.isMoving} pos=${formatGodBridgePositionDebug(player.posX, player.posZ)}"
        )

        return godBridgeWaitPending
    }

    private fun applyGodBridgePostAlignmentWait(input: MovementInput, waitSequenceFinished: Boolean): Boolean {
        if (waitSequenceFinished) {
            godBridgePostAlignmentWaitTicks = waitForRotsPostAlignTicks.random()
        }

        if (godBridgePostAlignmentWaitTicks <= 0) {
            return false
        }

        input.moveForward = 0f
        input.moveStrafe = 0f
        godBridgeInjectedStrafe = false
        godBridgePostAlignmentWaitTicks--
        godBridgeAlignmentDebug = "postAlign=wait ticks=$godBridgePostAlignmentWaitTicks"

        return true
    }

    private fun applyGodBridgeDiagonalInput(
        input: MovementInput, diagonalYaw: Float, waitingForRotation: Boolean
    ): Boolean {
        if (Tower.isTowering) {
            resetGodBridgeDiagonalAlignment()
            godBridgeAlignmentDebug = "diag=disabledOrTower"
            return false
        }

        if (godBridgeDiagonalYaw != diagonalYaw) {
            if (godBridgeDiagonalReleasedYaw != diagonalYaw) {
                godBridgeDiagonalReleasedYaw = null
            }

            godBridgeDiagonalYaw = diagonalYaw
            godBridgeDiagonalNudgeTicks = GOD_BRIDGE_DIAGONAL_NUDGE_TICKS
            godBridgeDiagonalStopTicks = 0
            godBridgeDiagonalReleased = godBridgeDiagonalReleasedYaw == diagonalYaw
            godBridgeDiagonalRotationDone = false
        }

        if (godBridgeDiagonalReleased) {
            godBridgeAlignmentDebug = "diag=released yaw=$diagonalYaw"
            return false
        }

        if (!godBridgeDiagonalRotationDone && waitingForRotation) {
            godBridgeDiagonalStopTicks = 0
            godBridgeAlignmentDebug = "diag=waitRot yaw=$diagonalYaw"
            return true
        }

        godBridgeDiagonalRotationDone = true

        val positionDelta = mc.thePlayer?.horizontalPositionDelta ?: 0.0
        if (positionDelta > GOD_BRIDGE_DIAGONAL_STOP_DELTA) {
            godBridgeDiagonalStopTicks = 0
            godBridgeAlignmentDebug =
                "diag=waitStop yaw=$diagonalYaw delta=${formatGodBridgeDebug(positionDelta)}"
            return true
        }

        godBridgeDiagonalStopTicks++
        if (godBridgeDiagonalStopTicks < GOD_BRIDGE_DIAGONAL_STOP_TICKS) {
            godBridgeAlignmentDebug =
                "diag=waitStop yaw=$diagonalYaw delta=${formatGodBridgeDebug(positionDelta)} " +
                    "stable=$godBridgeDiagonalStopTicks/$GOD_BRIDGE_DIAGONAL_STOP_TICKS"
            return true
        }

        if (godBridgeDiagonalNudgeTicks <= 0) {
            godBridgeDiagonalReleased = true
            godBridgeDiagonalReleasedYaw = diagonalYaw
            godBridgeAlignmentDebug = "diag=released yaw=$diagonalYaw"
            return false
        }

        input.moveForward = 0f
        input.moveStrafe = 1f
        godBridgeInjectedStrafe = true
        godBridgeDiagonalNudgeTicks--
        godBridgeAlignmentDebug =
            "diag=nudgeLeft yaw=$diagonalYaw ticks=$godBridgeDiagonalNudgeTicks strafe=${input.moveStrafe}"

        return true
    }

    private fun applyGodBridgeAlignmentInput(input: MovementInput): Boolean {
        if (!waitForRotsSideMove || Tower.isTowering) {
            resetGodBridgeAlignment()
            godBridgeAlignmentDebug = "align=disabledOrTower"
            return false
        }

        val target = getGodBridgeAlignmentTarget() ?: run {
            resetGodBridgeAlignment()
            godBridgeAlignmentDebug = "align=targetNull"
            return false
        }

        if (!isGodBridgeInCenterBand(target)) {
            godBridgeAlignmentDebug =
                "align=clear axis=${target.axis} cur=${formatGodBridgeDebug(target.current)} " +
                    "band=${formatGodBridgeDebug(waitForRotsSideOffset.toDouble())}.." +
                    formatGodBridgeDebug(1.0 - waitForRotsSideOffset.toDouble())
            return false
        }

        val strafe = chooseGodBridgeAlignmentStrafe(input, target) ?: run {
            godBridgeAlignmentDebug =
                "align=noMove axis=${target.axis} cur=${formatGodBridgeDebug(target.current)} " +
                    "target=${formatGodBridgeDebug(target.target)}"
            return false
        }

        input.moveStrafe = strafe
        godBridgeInjectedStrafe = true
        godBridgeAlignmentDebug =
            "align=center axis=${target.axis} cur=${formatGodBridgeDebug(target.current)} " +
                "target=${formatGodBridgeDebug(target.target)} strafe=${input.moveStrafe}"

        return true
    }

    private fun getGodBridgeAlignmentTarget(): GodBridgeAlignmentTarget? {
        val player = mc.thePlayer ?: return null
        val movingYaw = getGodBridgeMovingYaw()

        updateGodBridgeSide(movingYaw)

        val xWeight = abs(cos(movingYaw.toRadians()))
        val zWeight = abs(sin(movingYaw.toRadians()))
        val offset = waitForRotsSideOffset.toDouble()
        val mirroredOffset = 1.0 - offset

        return if (xWeight >= zWeight) {
            val target = if (isOnRightSide == cos(movingYaw.toRadians()).signIsPositive()) {
                mirroredOffset
            } else {
                offset
            }

            GodBridgeAlignmentTarget(
                player.posX - floor(player.posX),
                target,
                EnumFacing.Axis.X
            )
        } else {
            val target = if (isOnRightSide == sin(movingYaw.toRadians()).signIsPositive()) {
                mirroredOffset
            } else {
                offset
            }

            GodBridgeAlignmentTarget(
                player.posZ - floor(player.posZ),
                target,
                EnumFacing.Axis.Z
            )
        }
    }

    private fun isGodBridgeInCenterBand(target: GodBridgeAlignmentTarget): Boolean {
        val offset = waitForRotsSideOffset.toDouble()

        return target.current > offset && target.current < 1.0 - offset
    }

    private fun chooseGodBridgeAlignmentStrafe(input: MovementInput, target: GodBridgeAlignmentTarget): Float? {
        val forward = input.moveForward

        return listOf(-1f, 1f)
            .filter { abs(predictGodBridgeAlignmentDelta(it, forward, target.axis)) > 0.0 }
            .minByOrNull { abs(target.errorAfter(predictGodBridgeAlignmentDelta(it, forward, target.axis))) }
    }

    private fun predictGodBridgeAlignmentDelta(strafe: Float, forward: Float, axis: EnumFacing.Axis): Double {
        val projected = projectGodBridgeInput(strafe, forward) ?: return 0.0

        val yawRad = projected.yaw.toRadians()

        val deltaX = projected.strafe * cos(yawRad) - projected.forward * sin(yawRad)
        val deltaZ = projected.forward * cos(yawRad) + projected.strafe * sin(yawRad)

        return if (axis == EnumFacing.Axis.X) deltaX.toDouble() else deltaZ.toDouble()
    }

    private fun projectGodBridgeInput(strafe: Float, forward: Float): GodBridgeProjectedInput? {
        val player = mc.thePlayer ?: return null
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

        return GodBridgeProjectedInput(calcStrafe, calcForward, yaw)
    }

    private fun resetGodBridgeAlignment() {
        godBridgeInjectedStrafe = false
        godBridgeAlignmentDebug = "align=reset"
        resetGodBridgeDiagonalAlignment()
    }

    private fun resetGodBridgeWait(clearReleasedInput: Boolean = true) {
        resetGodBridgeAlignment()
        godBridgePostAlignmentWaitTicks = 0
        godBridgeWaitPending = false
        godBridgeWaitSequenceActive = false
        godBridgePlacementWaitPending = false
        godBridgePlacementReleased = false
        if (clearReleasedInput) {
            godBridgeReleasedInputKey = null
            godBridgeDiagonalReleasedYaw = null
        }
    }

    private fun resetGodBridgeJumpCounter() {
        blocksPlacedUntilJump = 0
        blocksToJump = blocksToJumpRange.random()
    }

    private fun resetGodBridgeDiagonalAlignment() {
        godBridgeDiagonalYaw = null
        godBridgeDiagonalNudgeTicks = 0
        godBridgeDiagonalStopTicks = 0
        godBridgeDiagonalReleased = false
        godBridgeDiagonalRotationDone = false
    }

    private fun shouldBlockGodBridgePlacement(): Boolean {
        if (!isGodBridgeEnabled || !waitForRots) {
            return false
        }

        if (godBridgePlacementReleased) {
            return false
        }

        if (godBridgeReleasedInputKey == getGodBridgeUserInputKey()) {
            return false
        }

        if (godBridgePlacementWaitPending) {
            return true
        }

        val targetRotation = godBridgeTargetRotation ?: return false
        return rotationDifference(targetRotation, currRotation) > getFixedAngleDelta()
    }

    private fun Float.signIsPositive() = this >= 0f

    private fun debugGodBridgeWait(message: String) {
        if (!waitForRotsDebug) {
            return
        }

        val tick = mc.thePlayer?.ticksExisted ?: 0

        if (message == godBridgeLastWaitDebug && tick - godBridgeLastWaitDebugTick < 10) {
            return
        }

        godBridgeLastWaitDebug = message
        godBridgeLastWaitDebugTick = tick

        chat("§7[Scaffold GB] §f$message")
    }

    private fun debugGodBridgeRaycast(message: String) {
        if (!godBridgeRaycastDebug) {
            return
        }

        chat("§7[Scaffold GB Ray] §f$message")
    }

    private fun debugGodBridgeVanillaOrder(
        target: PlaceInfo,
        scaffoldRaytrace: MovingObjectPosition?,
        requireSide: Boolean,
        scaffoldMatchesTarget: Boolean
    ) {
        if (!godBridgeVanillaOrderDebug) {
            return
        }

        val player = mc.thePlayer ?: return
        val scaffoldState = formatGodBridgeRayWindowState(target, scaffoldRaytrace, requireSide)
        val nextRaytrace = simulateGodBridgeNextRaytrace()
        val nextState = formatGodBridgeRayWindowState(target, nextRaytrace, requireSide)
        val fallRiskMiss = requireSide && !scaffoldMatchesTarget && (
            scaffoldState == "miss" ||
                scaffoldState == "null" ||
                scaffoldState == "face:UP" && nextState != "hit:${target.enumFacing.name}"
            )
        val sample = godBridgeMouseOverSample

        if (sample == null) {
            if (fallRiskMiss) {
                debugGodBridgeRaycast(
                    "order heartbeat target=${formatGodBridgePlaceInfo(target)} " +
                        "sample=missing scaffold=$scaffoldState next=$nextState " +
                        "scaffoldRay=${formatGodBridgeRaytraceDetail(scaffoldRaytrace)} " +
                        "nextRay=${formatGodBridgeRaytraceDetail(nextRaytrace)} " +
                        "scaffoldPos=${formatGodBridgePositionDebug(player.posX, player.posZ)} " +
                        "scaffoldRot=${formatGodBridgeRotation(currRotation)} tick=${player.ticksExisted}"
                )
            }

            return
        }

        val sampleState = formatGodBridgeRayWindowState(target, sample.mouseOver, requireSide)
        val sameTick = sample.ticksExisted == player.ticksExisted
        val sameState = sampleState == scaffoldState
        val moved = abs(sample.posX - player.posX) > GOD_BRIDGE_ORDER_EPSILON ||
            abs(sample.posY - player.posY) > GOD_BRIDGE_ORDER_EPSILON ||
            abs(sample.posZ - player.posZ) > GOD_BRIDGE_ORDER_EPSILON
        val rotationChanged = sample.currentRotation != currRotation
        val mismatch = !sameTick || !sameState || moved || rotationChanged

        if (!mismatch && !fallRiskMiss) {
            return
        }

        val eventName = if (mismatch) "order" else "order heartbeat"

        debugGodBridgeRaycast(
            "$eventName target=${formatGodBridgePlaceInfo(target)} " +
                "sample=$sampleState scaffold=$scaffoldState next=$nextState same=$sameState sameTick=$sameTick " +
                "sampleRay=${formatGodBridgeRaytraceDetail(sample.mouseOver)} " +
                "scaffoldRay=${formatGodBridgeRaytraceDetail(scaffoldRaytrace)} " +
                "nextRay=${formatGodBridgeRaytraceDetail(nextRaytrace)} " +
                "samplePos=${formatGodBridgePositionDebug(sample.posX, sample.posZ)} " +
                "scaffoldPos=${formatGodBridgePositionDebug(player.posX, player.posZ)} moved=$moved " +
                "playerRot=${formatGodBridgeRotation(sample.playerRotation)} " +
                "sampleCurrentRot=${sample.currentRotation?.let(::formatGodBridgeRotation) ?: "null"} " +
                "usesCurrent=${sample.usesCurrentRotation} " +
                "scaffoldRot=${formatGodBridgeRotation(currRotation)} rotChanged=$rotationChanged " +
                "tick=${sample.ticksExisted}/${player.ticksExisted}"
        )
    }

    private fun simulateGodBridgeNextRaytrace(): MovingObjectPosition? {
        val player = mc.thePlayer ?: return null
        val reach = mc.playerController.blockReachDistance

        val nextEyes = SimulatedPlayer.fromClientPlayer(RotationUtils.modifiedInput).let { simPlayer ->
            simPlayer.rotationYaw = currRotation.yaw
            simPlayer.tick()

            Vec3(simPlayer.posX, simPlayer.posY + player.eyeHeight.toDouble(), simPlayer.posZ)
        }

        return performBlockRaytraceFromEyes(currRotation, reach, nextEyes)
    }

    private fun predictGodBridgeFallTicks(): Int? {
        val simPlayer = SimulatedPlayer.fromClientPlayer(RotationUtils.modifiedInput)

        for (tick in 1..GOD_BRIDGE_FALL_LOOKAHEAD_TICKS) {
            simPlayer.rotationYaw = currRotation.yaw
            simPlayer.tick()

            if (!simPlayer.onGround) {
                return tick
            }
        }

        return null
    }

    private fun calculateGodBridgeFallSneakTicks(): Int? {
        val target = placeRotation?.placeInfo ?: return null

        return (1..GOD_BRIDGE_FALL_SNEAK_MAX_TICKS).firstOrNull { holdTicks ->
            isGodBridgeFallSneakReleaseSafe(holdTicks, target)
        }
    }

    private fun isGodBridgeFallSneakReleaseSafe(holdTicks: Int, target: PlaceInfo): Boolean {
        val player = mc.thePlayer ?: return false
        val reach = mc.playerController.blockReachDistance
        val simPlayer = SimulatedPlayer.fromClientPlayer(RotationUtils.modifiedInput)

        repeat(holdTicks) {
            simPlayer.movementInput.sneak = true
            simPlayer.movementInput.jump = false
            simPlayer.rotationYaw = currRotation.yaw
            simPlayer.tick()

            if (!simPlayer.onGround) {
                return false
            }
        }

        repeat(GOD_BRIDGE_FALL_RELEASE_LOOKAHEAD_TICKS) {
            simPlayer.movementInput.sneak = false
            simPlayer.movementInput.jump = false
            simPlayer.rotationYaw = currRotation.yaw
            simPlayer.tick()

            if (!simPlayer.onGround) {
                return false
            }

            val releaseEyes = Vec3(simPlayer.posX, simPlayer.posY + player.eyeHeight.toDouble(), simPlayer.posZ)
            val raytrace = performBlockRaytraceFromEyes(currRotation, reach, releaseEyes)

            if (raytrace != null && raytrace.blockPos == target.blockPos && raytrace.sideHit == target.enumFacing) {
                return true
            }
        }

        return false
    }

    private fun debugGodBridgePlacementPhase(clickPos: BlockPos, side: EnumFacing, attempt: Boolean) {
        if (!godBridgePhaseDebug) {
            return
        }

        val snapshot = createGodBridgePhaseSnapshot(side, getGodBridgeFallbackPhaseAxis()) ?: return

        debugGodBridgeRaycast(
            "phase event=place click=${formatGodBridgeBlockPos(clickPos)}/${side.name} " +
                "${formatGodBridgePhaseSnapshot(snapshot)} " +
                "rot=${formatGodBridgeRotation(currRotation)} attempt=$attempt"
        )
    }

    private fun createGodBridgePhaseSnapshot(
        side: EnumFacing,
        fallbackAxis: EnumFacing.Axis? = null
    ): GodBridgePhaseSnapshot? {
        val player = mc.thePlayer ?: return null
        val axis = getGodBridgePhaseAxis(side, fallbackAxis) ?: return null

        return GodBridgePhaseSnapshot(
            axis = axis,
            side = side,
            phase = getGodBridgeAxisPhase(axis, player.posX, player.posZ),
            axisMotion = if (axis == EnumFacing.Axis.X) player.motionX else player.motionZ,
            tick = player.ticksExisted,
            jumpCount = blocksPlacedUntilJump,
            jumpTarget = blocksToJump,
            posX = player.posX,
            posY = player.posY,
            posZ = player.posZ,
            motionY = player.motionY,
            onGround = player.onGround
        )
    }

    private fun getGodBridgeFallbackPhaseAxis(): EnumFacing.Axis? {
        val player = mc.thePlayer ?: return null

        return if (abs(player.motionX) >= abs(player.motionZ)) {
            EnumFacing.Axis.X
        } else {
            EnumFacing.Axis.Z
        }
    }

    private fun getGodBridgePhaseAxis(side: EnumFacing, fallbackAxis: EnumFacing.Axis?): EnumFacing.Axis? {
        if (side.axis != EnumFacing.Axis.Y) {
            return side.axis
        }

        return fallbackAxis?.takeIf { it != EnumFacing.Axis.Y }
    }

    private fun getGodBridgeAxisPhase(axis: EnumFacing.Axis, posX: Double, posZ: Double): Double {
        return getGodBridgeAxisPhaseCoord(getGodBridgeAxisCoord(axis, posX, posZ))
    }

    private fun getGodBridgeAxisCoord(axis: EnumFacing.Axis, posX: Double, posZ: Double): Double {
        return if (axis == EnumFacing.Axis.X) posX else posZ
    }

    private fun getGodBridgeAxisPhaseCoord(coord: Double): Double {
        return coord - floor(coord)
    }

    private fun formatGodBridgeAxisPhaseCoord(coord: Double) =
        formatGodBridgeDebug(getGodBridgeAxisPhaseCoord(coord))

    private fun interpolateGodBridgeAxisCoord(from: Double, to: Double, fraction: Double) =
        from + (to - from) * fraction

    private fun getGodBridgeDistanceToRange(value: Double, first: Double, second: Double): Double {
        val min = min(first, second)
        val max = max(first, second)

        return when {
            value < min -> min - value
            value > max -> value - max
            else -> 0.0
        }
    }

    private fun formatGodBridgeDebug(value: Double) = "%.3f".format(value)

    private fun formatGodBridgeRayWindow(
        target: PlaceInfo,
        currentRaytrace: MovingObjectPosition?,
        requireSide: Boolean,
    ): String {
        val player = mc.thePlayer ?: return "prev=null now=null nextSim=null"
        val reach = mc.playerController.blockReachDistance
        val previousEyes = Vec3(player.prevPosX, player.prevPosY + player.eyeHeight.toDouble(), player.prevPosZ)
        val previousRaytrace = performBlockRaytraceFromEyes(currRotation, reach, previousEyes)

        val nextRaytrace = SimulatedPlayer.fromClientPlayer(RotationUtils.modifiedInput).let { simPlayer ->
            simPlayer.rotationYaw = currRotation.yaw
            simPlayer.tick()
            val nextEyes = Vec3(simPlayer.posX, simPlayer.posY + player.eyeHeight.toDouble(), simPlayer.posZ)
            performBlockRaytraceFromEyes(currRotation, reach, nextEyes)
        }

        return "prev=${formatGodBridgeRayWindowState(target, previousRaytrace, requireSide)} " +
            "now=${formatGodBridgeRayWindowState(target, currentRaytrace, requireSide)} " +
            "nextSim=${formatGodBridgeRayWindowState(target, nextRaytrace, requireSide)}"
    }

    private fun debugGodBridgeWindowScan(
        target: PlaceInfo,
        currentRaytrace: MovingObjectPosition?,
        requireSide: Boolean,
    ) {
        if (!godBridgeWindowScanDebug || !requireSide || target.enumFacing.axis == EnumFacing.Axis.Y) {
            return
        }

        val player = mc.thePlayer ?: return
        val reach = mc.playerController.blockReachDistance

        if (currentRaytrace == null ||
            !currentRaytrace.typeOfHit.isBlock ||
            currentRaytrace.blockPos != target.blockPos ||
            currentRaytrace.sideHit != EnumFacing.UP
        ) {
            return
        }

        val currentEyes = Vec3(player.posX, player.posY + player.eyeHeight.toDouble(), player.posZ)
        val nextEyes = SimulatedPlayer.fromClientPlayer(RotationUtils.modifiedInput).let { simPlayer ->
            simPlayer.rotationYaw = currRotation.yaw
            simPlayer.tick()

            Vec3(simPlayer.posX, simPlayer.posY + player.eyeHeight.toDouble(), simPlayer.posZ)
        }
        val nextRaytrace = performBlockRaytraceFromEyes(currRotation, reach, nextEyes)

        if (nextRaytrace != null && nextRaytrace.blockPos == target.blockPos && nextRaytrace.sideHit == target.enumFacing) {
            return
        }

        var hitStart: Double? = null
        var hitEnd: Double? = null
        val samples = StringBuilder(GOD_BRIDGE_WINDOW_SCAN_STEPS + 1)

        for (step in 0..GOD_BRIDGE_WINDOW_SCAN_STEPS) {
            val fraction = step.toDouble() / GOD_BRIDGE_WINDOW_SCAN_STEPS
            val eyes = Vec3(
                currentEyes.xCoord + (nextEyes.xCoord - currentEyes.xCoord) * fraction,
                currentEyes.yCoord + (nextEyes.yCoord - currentEyes.yCoord) * fraction,
                currentEyes.zCoord + (nextEyes.zCoord - currentEyes.zCoord) * fraction
            )
            val raytrace = performBlockRaytraceFromEyes(currRotation, reach, eyes)
            val hitTargetSide = raytrace != null && raytrace.blockPos == target.blockPos &&
                raytrace.sideHit == target.enumFacing

            samples.append(formatGodBridgeWindowScanSample(target, raytrace))

            if (hitTargetSide) {
                if (hitStart == null) {
                    hitStart = fraction
                }

                hitEnd = fraction
            }
        }

        val hitRange = if (hitStart == null) {
            "none"
        } else {
            "${formatGodBridgeDebug(hitStart!!)}..${formatGodBridgeDebug(hitEnd!!)}"
        }

        debugGodBridgeRaycast(
            "window scan target=${formatGodBridgePlaceInfo(target)} " +
                "now=${formatGodBridgeRayWindowState(target, currentRaytrace, requireSide = true)} " +
                "next=${formatGodBridgeRayWindowState(target, nextRaytrace, requireSide = true)} " +
                "hitRange=$hitRange samples=$samples " +
                "from=${formatGodBridgePositionDebug(player.posX, player.posZ)} " +
                "to=${formatGodBridgePositionDebug(nextEyes.xCoord, nextEyes.zCoord)} " +
                "motion=${formatGodBridgeMotionDebug()} jump=$blocksPlacedUntilJump/$blocksToJump"
        )

        if (hitStart != null && hitEnd != null) {
            debugGodBridgeSkippedPhaseWindow(target, currentRaytrace, nextRaytrace, currentEyes, nextEyes, hitStart, hitEnd)
        }
    }

    private fun debugGodBridgeSkippedPhaseWindow(
        target: PlaceInfo,
        currentRaytrace: MovingObjectPosition,
        nextRaytrace: MovingObjectPosition?,
        currentEyes: Vec3,
        nextEyes: Vec3,
        hitStart: Double,
        hitEnd: Double
    ) {
        if (!godBridgePhaseDebug) {
            return
        }

        val player = mc.thePlayer ?: return
        val axis = target.enumFacing.axis
        val currentCoord = getGodBridgeAxisCoord(axis, currentEyes.xCoord, currentEyes.zCoord)
        val nextCoord = getGodBridgeAxisCoord(axis, nextEyes.xCoord, nextEyes.zCoord)
        val hitStartCoord = interpolateGodBridgeAxisCoord(currentCoord, nextCoord, hitStart)
        val hitEndCoord = interpolateGodBridgeAxisCoord(currentCoord, nextCoord, hitEnd)

        debugGodBridgeRaycast(
            "phase event=skip target=${formatGodBridgePlaceInfo(target)} " +
                "axis=${axis.name} side=${target.enumFacing.name} " +
                "phaseNow=${formatGodBridgeAxisPhaseCoord(currentCoord)} " +
                "phaseNext=${formatGodBridgeAxisPhaseCoord(nextCoord)} " +
                "hitPhase=${formatGodBridgeAxisPhaseCoord(hitStartCoord)}.." +
                formatGodBridgeAxisPhaseCoord(hitEndCoord) + " " +
                "distNow=${formatGodBridgeDebug(getGodBridgeDistanceToRange(currentCoord, hitStartCoord, hitEndCoord))} " +
                "distNext=${formatGodBridgeDebug(getGodBridgeDistanceToRange(nextCoord, hitStartCoord, hitEndCoord))} " +
                "now=${formatGodBridgeRayWindowState(target, currentRaytrace, requireSide = true)} " +
                "next=${formatGodBridgeRayWindowState(target, nextRaytrace, requireSide = true)} " +
                "y=${formatGodBridgeDebug(player.posY)} motionY=${formatGodBridgeDebug(player.motionY)} " +
                "ground=${player.onGround} axisMotion=${formatGodBridgeDebug(if (axis == EnumFacing.Axis.X) player.motionX else player.motionZ)} " +
                "rot=${formatGodBridgeRotation(currRotation)} jump=$blocksPlacedUntilJump/$blocksToJump"
        )
    }

    private fun formatGodBridgeWindowScanSample(
        target: PlaceInfo,
        raytrace: MovingObjectPosition?,
    ): Char {
        raytrace ?: return 'N'

        if (!raytrace.typeOfHit.isBlock) {
            return 'M'
        }

        if (raytrace.blockPos != target.blockPos) {
            return 'B'
        }

        return when (raytrace.sideHit) {
            target.enumFacing -> 'H'
            EnumFacing.UP -> 'U'
            else -> 'F'
        }
    }

    private fun formatGodBridgeRayWindowState(
        target: PlaceInfo,
        raytrace: MovingObjectPosition?,
        requireSide: Boolean,
    ): String {
        raytrace ?: return "null"

        if (!raytrace.typeOfHit.isBlock) {
            return raytrace.typeOfHit.name.toLowerCase()
        }

        if (raytrace.blockPos != target.blockPos) {
            return "block:${raytrace.sideHit.name}"
        }

        if (requireSide && raytrace.sideHit != target.enumFacing) {
            return "face:${raytrace.sideHit.name}"
        }

        return "hit:${raytrace.sideHit.name}"
    }

    private fun formatGodBridgeRaytraceDetail(raytrace: MovingObjectPosition?): String {
        raytrace ?: return "null"

        if (!raytrace.typeOfHit.isBlock || raytrace.blockPos == null) {
            return raytrace.typeOfHit.name.toLowerCase()
        }

        return "${formatGodBridgeBlockPos(raytrace.blockPos)}/${raytrace.sideHit?.name ?: "null"} " +
            "hit=${formatGodBridgeVec(raytrace.hitVec - Vec3(raytrace.blockPos))}"
    }

    private fun formatGodBridgePositionDebug(posX: Double, posZ: Double) =
        "x=${formatGodBridgeDebug(posX - floor(posX))} z=${formatGodBridgeDebug(posZ - floor(posZ))}"

    private fun formatGodBridgeCurrentPositionDebug(): String {
        val player = mc.thePlayer ?: return "x=0.000 z=0.000"

        return formatGodBridgePositionDebug(player.posX, player.posZ)
    }

    private fun formatGodBridgeMotionDebug(): String {
        val player = mc.thePlayer ?: return "x=0.000 z=0.000"

        return "x=${formatGodBridgeDebug(player.motionX)} z=${formatGodBridgeDebug(player.motionZ)}"
    }

    private fun formatGodBridgePhaseSnapshot(snapshot: GodBridgePhaseSnapshot) =
        "axis=${snapshot.axis.name} side=${snapshot.side.name} " +
            "phase=${formatGodBridgeDebug(snapshot.phase)} " +
            "pos=${formatGodBridgePositionDebug(snapshot.posX, snapshot.posZ)} " +
            "axisMotion=${formatGodBridgeDebug(snapshot.axisMotion)} " +
            "y=${formatGodBridgeDebug(snapshot.posY)} " +
            "motionY=${formatGodBridgeDebug(snapshot.motionY)} " +
            "ground=${snapshot.onGround} " +
            "jump=${snapshot.jumpCount}/${snapshot.jumpTarget}"

    private fun formatGodBridgeRotation(rotation: Rotation) =
        "yaw=${formatGodBridgeDebug(rotation.yaw.toDouble())} pitch=${formatGodBridgeDebug(rotation.pitch.toDouble())}"

    private fun formatGodBridgeBlockPos(pos: BlockPos) =
        "x=${pos.x} y=${pos.y} z=${pos.z}"

    private fun formatGodBridgeVec(vec: Vec3) =
        "x=${formatGodBridgeDebug(vec.xCoord)} y=${formatGodBridgeDebug(vec.yCoord)} z=${formatGodBridgeDebug(vec.zCoord)}"

    private fun formatGodBridgePlaceInfo(placeInfo: PlaceInfo) =
        "${formatGodBridgeBlockPos(placeInfo.blockPos)}/${placeInfo.enumFacing.name} " +
            "hit=${formatGodBridgeVec(placeInfo.vec3 - Vec3(placeInfo.blockPos))}"

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

        setRotation(rotation, ticks)

        godBridgeTargetRotation = rotation
    }

    override val tag
        get() = if (towerMode != "None") ("$scaffoldMode | $towerMode") else scaffoldMode

    data class ExtraClickInfo(val delay: Int, val lastClick: Long, var clicks: Int)

    private data class GodBridgeAlignmentTarget(
        val current: Double,
        val target: Double,
        val axis: EnumFacing.Axis
    ) {
        fun errorAfter(delta: Double) = target - (current + delta)
    }

    private data class GodBridgeProjectedInput(val strafe: Float, val forward: Float, val yaw: Float)

    private data class GodBridgeInputKey(
        val moveForward: Float,
        val moveStrafe: Float,
        val movingYaw: Float
    )

    private data class GodBridgePhaseSnapshot(
        val axis: EnumFacing.Axis,
        val side: EnumFacing,
        val phase: Double,
        val axisMotion: Double,
        val tick: Int,
        val jumpCount: Int,
        val jumpTarget: Int,
        val posX: Double,
        val posY: Double,
        val posZ: Double,
        val motionY: Double,
        val onGround: Boolean
    )

    private data class GodBridgeMouseOverSample(
        val mouseOver: MovingObjectPosition?,
        val posX: Double,
        val posY: Double,
        val posZ: Double,
        val playerRotation: Rotation,
        val currentRotation: Rotation?,
        val usesCurrentRotation: Boolean,
        val ticksExisted: Int
    )

    private val GOD_BRIDGE_DIAGONAL_YAWS = arrayListOf(-135f, -45f, 45f, 135f)
    private const val GOD_BRIDGE_DIAGONAL_STOP_DELTA = 0.005
    private const val GOD_BRIDGE_DIAGONAL_STOP_TICKS = 2
    private const val GOD_BRIDGE_DIAGONAL_NUDGE_TICKS = 6
    private const val GOD_BRIDGE_FALL_SNEAK_MAX_TICKS = 6
    private const val GOD_BRIDGE_FALL_LOOKAHEAD_TICKS = 4
    private const val GOD_BRIDGE_FALL_RELEASE_LOOKAHEAD_TICKS = 4
    private const val GOD_BRIDGE_FALL_SNEAK_RELEASE_TICK = -1
    private const val GOD_BRIDGE_WINDOW_SCAN_STEPS = 20
    private const val GOD_BRIDGE_ORDER_EPSILON = 1.0E-6
}
