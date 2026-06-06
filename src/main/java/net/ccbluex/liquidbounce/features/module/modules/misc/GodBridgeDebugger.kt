/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.GameTickEvent
import net.ccbluex.liquidbounce.event.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.modules.world.scaffolds.Scaffold
import net.ccbluex.liquidbounce.file.FileManager
import net.ccbluex.liquidbounce.utils.client.ClientUtils.runTimeTicks
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils
import net.minecraft.client.settings.GameSettings
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumFacing
import net.minecraft.util.MovingObjectPosition
import net.minecraft.util.Vec3
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

object GodBridgeDebugger : Module("GodBridgeDebugger", Category.MISC, gameDetecting = false) {

    private val writeFile by boolean("WriteFile", true)
    private val chatPackets by boolean("ChatPackets", true)
    private val logUseHeldTicks by boolean("LogUseHeldTicks", true)
    private val logRayChanges by boolean("LogRayChanges", false)
    private val logItemUsePackets by boolean("LogItemUsePackets", false)
    private val ignoreWhileScaffold by boolean("IgnoreWhileScaffold", true)

    private var logFile: File? = null
    private var lastRayKey: String? = null
    private var placementIndex = 0

    override fun onEnable() {
        placementIndex = 0
        lastRayKey = null

        if (writeFile) {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            val fileName = "godbridge_debug_${LocalDateTime.now().format(formatter)}.log"
            logFile = File(FileManager.dir, fileName)

            writeLine("# GodBridgeDebugger")
            writeLine("# Use this with Scaffold disabled for a manual vanilla-style GodBridge baseline.")
            writeLine("# packet = outgoing C08 block placement; tick = right-click held or ray-state change sample.")
        }

        chat("GodBridgeDebugger enabled${logFile?.let { " -> ${it.name}" } ?: ""}.")
    }

    override fun onDisable() {
        logFile?.let { chat("GodBridgeDebugger saved ${it.absolutePath}") }
        logFile = null
        lastRayKey = null
    }

    val onTick = handler<GameTickEvent> {
        val player = mc.thePlayer ?: return@handler
        if (ignoreWhileScaffold && Scaffold.handleEvents()) return@handler

        val ray = mc.objectMouseOver
        val rayKey = rayKey(ray)
        val useHeld = GameSettings.isKeyDown(mc.gameSettings.keyBindUseItem)
        val shouldLogTick = logUseHeldTicks && useHeld || logRayChanges && rayKey != lastRayKey

        if (shouldLogTick) {
            writeLine(
                "tick ${baseState()} useHeld=$useHeld ${formatRay("mouse", ray)} " +
                    "item=${player.heldItem?.displayName ?: "none"}"
            )
        }

        lastRayKey = rayKey
    }

    val onPacket = handler<PacketEvent> { event ->
        if (event.eventType != EventState.SEND) return@handler
        if (ignoreWhileScaffold && Scaffold.handleEvents()) return@handler

        val packet = event.packet as? C08PacketPlayerBlockPlacement ?: return@handler
        val direction = packet.placedBlockDirection

        if (direction !in 0..5 && !logItemUsePackets) return@handler

        val side = if (direction in 0..5) EnumFacing.getFront(direction).name else "ITEM"
        val placementLine = buildString {
            append("packet #${++placementIndex} ")
            append(baseState())
            append(" click=${formatBlockPos(packet.position)}/$side")
            append(" hit=x=${fmt(packet.placedBlockOffsetX.toDouble())}")
            append(" y=${fmt(packet.placedBlockOffsetY.toDouble())}")
            append(" z=${fmt(packet.placedBlockOffsetZ.toDouble())}")
            append(" ${formatRay("mouse", mc.objectMouseOver)}")
            append(" item=${mc.thePlayer?.heldItem?.displayName ?: "none"}")
            append(" cancelled=${event.isCancelled}")
        }

        writeLine(placementLine)

        if (chatPackets && direction in 0..5) {
            chat("§7[§9GodBridgeDebug§7] §f${stripForChat(placementLine)}")
        }
    }

    private fun baseState(): String {
        val player = mc.thePlayer ?: return "noPlayer"
        val input = player.movementInput
        val currentRotation = RotationUtils.currentRotation
        val axis = if (abs(player.motionX) >= abs(player.motionZ)) "X" else "Z"

        return "rt=$runTimeTicks tick=${player.ticksExisted} " +
            "pos=x=${fmt(player.posX)} y=${fmt(player.posY)} z=${fmt(player.posZ)} " +
            "frac=x=${fmt(frac(player.posX))} z=${fmt(frac(player.posZ))} " +
            "motion=x=${fmt(player.motionX)} y=${fmt(player.motionY)} z=${fmt(player.motionZ)} axis=$axis " +
            "rot=yaw=${fmt(player.rotationYaw)} pitch=${fmt(player.rotationPitch)} " +
            "curr=${currentRotation?.let { "yaw=${fmt(it.yaw)} pitch=${fmt(it.pitch)}" } ?: "none"} " +
            "ground=${player.onGround} sprint=${player.isSprinting} fall=${fmt(player.fallDistance.toDouble())} " +
            "input=f=${fmt(input.moveForward.toDouble())} s=${fmt(input.moveStrafe.toDouble())} " +
            "jump=${input.jump} sneak=${input.sneak}"
    }

    private fun formatRay(label: String, ray: MovingObjectPosition?): String {
        ray ?: return "$label=null"

        return when (ray.typeOfHit) {
            MovingObjectPosition.MovingObjectType.BLOCK -> {
                val pos = ray.blockPos
                "$label=block:${formatBlockPos(pos)}/${ray.sideHit?.name ?: "NONE"} " +
                    "rayHit=${formatLocalHit(ray.hitVec, pos)}"
            }

            MovingObjectPosition.MovingObjectType.MISS -> {
                "$label=miss side=${ray.sideHit?.name ?: "NONE"} block=${formatBlockPos(ray.blockPos)}"
            }

            MovingObjectPosition.MovingObjectType.ENTITY -> {
                "$label=entity:${ray.entityHit?.name ?: ray.entityHit?.javaClass?.simpleName ?: "unknown"}"
            }
        }
    }

    private fun rayKey(ray: MovingObjectPosition?): String {
        ray ?: return "null"

        return when (ray.typeOfHit) {
            MovingObjectPosition.MovingObjectType.BLOCK -> "block:${ray.blockPos}:${ray.sideHit}"
            MovingObjectPosition.MovingObjectType.MISS -> "miss"
            MovingObjectPosition.MovingObjectType.ENTITY -> "entity:${ray.entityHit?.entityId}"
        }
    }

    private fun formatLocalHit(hitVec: Vec3?, blockPos: BlockPos): String {
        hitVec ?: return "none"

        return "x=${fmt(hitVec.xCoord - blockPos.x)} y=${fmt(hitVec.yCoord - blockPos.y)} " +
            "z=${fmt(hitVec.zCoord - blockPos.z)}"
    }

    private fun formatBlockPos(blockPos: BlockPos?) =
        blockPos?.let { "x=${it.x} y=${it.y} z=${it.z}" } ?: "null"

    private fun writeLine(line: String) {
        if (writeFile) {
            logFile?.appendText(line + System.lineSeparator())
        }
    }

    private fun stripForChat(line: String): String {
        val maxLength = 230
        return if (line.length <= maxLength) line else line.take(maxLength) + "..."
    }

    private fun frac(value: Double): Double {
        val result = value - floor(value)
        return if (result < 0.0) result + 1.0 else result
    }

    private fun fmt(value: Float) = fmt(value.toDouble())

    private fun fmt(value: Double) = String.format(Locale.US, "%.3f", value)
}
