/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.misc

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.modules.combat.KillAura
import net.ccbluex.liquidbounce.file.FileManager
import net.ccbluex.liquidbounce.utils.attack.EntityUtils
import net.ccbluex.liquidbounce.utils.client.ClientUtils.runTimeTicks
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.extensions.center
import net.ccbluex.liquidbounce.utils.extensions.hitBox
import net.ccbluex.liquidbounce.utils.rotation.Rotation
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.angleDifference
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.getFixedAngleDelta
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.isVisible
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.lastRotations
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.rotationDifference
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.serverRotation
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.toRotation
import net.ccbluex.liquidbounce.utils.timing.TickedActions.nextTick
import net.ccbluex.liquidbounce.utils.timing.WaitTickUtils
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.potion.Potion
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.XYChart
import org.knowm.xchart.XYSeries
import org.lwjgl.opengl.Display
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

object RotationRecorder : Module("RotationRecorder", Category.MISC) {

    private val mode by choices("Mode", arrayOf("Chart", "Analysis"), "Chart")
    private val captureNegativeNumbers by boolean("CaptureNegativeNumbers", false) { mode == "Chart" }

    private val ticks = mutableListOf<Double>()
    private val yawDiffs = mutableListOf<Double>()
    private val pitchDiffs = mutableListOf<Double>()

    private var activeMode = "Chart"
    private var chart: XYChart? = null
    private var failed = false

    private var analysisWriter: BufferedWriter? = null
    private var analysisFile: File? = null
    private var analysisStartedAt = 0L
    private var analysisRecords = 0
    private var analysisTicks = 0
    private var recordsSinceFlush = 0

    override val tag
        get() = activeMode

    override fun onEnable() {
        activeMode = mode
        failed = false

        if (isAnalysisMode) {
            startAnalysisRecording()
        } else {
            startChartRecording()
        }
    }

    override fun onDisable() {
        if (isAnalysisMode) {
            finishAnalysisRecording("module_disabled", notify = !failed)
        } else if (!failed) {
            saveChart()
        }

        chart = null
        failed = false
        ticks.clear()
        yawDiffs.clear()
        pitchDiffs.clear()
    }

    val onMotion = handler<MotionEvent> { event ->
        if (event.eventState != EventState.POST || failed) {
            return@handler
        }

        if (isAnalysisMode) {
            recordAnalysisTick()
            return@handler
        }

        updateRecordInfo()

        chart?.updateXYSeries("Yaw Differences", ticks.toDoubleArray(), yawDiffs.toDoubleArray(), null)
        chart?.updateXYSeries("Pitch Differences", ticks.toDoubleArray(), pitchDiffs.toDoubleArray(), null)
    }

    val onAttack = handler<AttackEvent> { event ->
        if (!isAnalysisMode || failed || !ensureAnalysisWriter()) {
            return@handler
        }

        val record = baseRecord("attack")
        val target = event.targetEntity

        record.addNullableProperty("target_id", target?.entityId)
        record.addNullableProperty("target_x", target?.posX)
        record.addNullableProperty("target_y", target?.posY)
        record.addNullableProperty("target_z", target?.posZ)
        record.addProperty("server_yaw", serverRotation.yaw)
        record.addProperty("server_pitch", serverRotation.pitch)

        writeAnalysisRecord(record)
    }

    val onWorld = handler<WorldEvent> {
        if (isAnalysisMode && analysisWriter != null) {
            finishAnalysisRecording("world_change", notify = false)
        }
    }

    val onShutdown = handler<ClientShutdownEvent>(always = true) {
        if (analysisWriter != null) {
            finishAnalysisRecording("client_shutdown", notify = false)
        }
    }

    private fun startChartRecording() {
        updateRecordInfo(true)

        try {
            chart = XYChart(Display.getWidth(), Display.getHeight()).apply {
                title = "Yaw and Pitch Differences Over Time"
                xAxisTitle = "Time (ticks)"
                yAxisTitle = "Differences (degrees)"

                addSeries("Yaw Differences", ticks.toDoubleArray(), yawDiffs.toDoubleArray()).apply {
                    xySeriesRenderStyle = XYSeries.XYSeriesRenderStyle.Line
                    lineColor = java.awt.Color.BLUE
                    isSmooth = true
                }

                addSeries("Pitch Differences", ticks.toDoubleArray(), pitchDiffs.toDoubleArray()).apply {
                    xySeriesRenderStyle = XYSeries.XYSeriesRenderStyle.Line
                    lineColor = java.awt.Color.RED
                    isSmooth = true
                }
            }
        } catch (exception: Exception) {
            failRecording("Failed to start recording rotations", exception)
            return
        }

        chat("Started recording rotations.")
    }

    private fun saveChart() {
        val fileName = "rotations_${FILE_TIME_FORMAT.format(LocalDateTime.now())}.png"
        val file = File(FileManager.dir, fileName)

        try {
            BitmapEncoder.saveBitmap(chart, file.absolutePath, BitmapEncoder.BitmapFormat.PNG)
        } catch (exception: IOException) {
            exception.printStackTrace()
        } finally {
            chat("Saved as $fileName in ${FileManager.dir}")
        }
    }

    private fun updateRecordInfo(wasPreviousTick: Boolean = false) {
        var yawDiff = angleDifference(serverRotation.yaw, lastRotations[1].yaw)
        var pitchDiff = angleDifference(serverRotation.pitch, lastRotations[1].pitch)

        if (!captureNegativeNumbers) {
            yawDiff = yawDiff.absoluteValue
            pitchDiff = pitchDiff.absoluteValue
        }

        ticks.add(runTimeTicks.toDouble() - if (wasPreviousTick) 1 else 0)
        yawDiffs.add(yawDiff.toDouble())
        pitchDiffs.add(pitchDiff.toDouble())
    }

    private fun startAnalysisRecording(): Boolean {
        if (analysisWriter != null) {
            return true
        }

        try {
            if (!ANALYSIS_DIR.exists() && !ANALYSIS_DIR.mkdirs()) {
                throw IOException("Could not create ${ANALYSIS_DIR.absolutePath}")
            }

            val timestamp = ANALYSIS_TIME_FORMAT.format(LocalDateTime.now())
            var file = File(ANALYSIS_DIR, "aim-$timestamp.jsonl")
            var suffix = 1

            while (file.exists()) {
                file = File(ANALYSIS_DIR, "aim-$timestamp-${suffix++}.jsonl")
            }

            analysisFile = file
            analysisWriter = file.bufferedWriter()
            analysisStartedAt = System.nanoTime()
            analysisRecords = 0
            analysisTicks = 0
            recordsSinceFlush = 0

            val session = baseRecord("session")
            session.addProperty("schema", ANALYSIS_SCHEMA)
            session.addProperty("created_at", LocalDateTime.now().toString())
            session.addProperty("tick_rate", 20)
            session.addProperty("mouse_sensitivity", mc.gameSettings.mouseSensitivity)
            session.addProperty("gcd", getFixedAngleDelta())
            session.addProperty("target_capture_range", TARGET_CAPTURE_RANGE)
            writeAnalysisRecord(session)

            chat("Started aim analysis recording: ${file.name}")
            return true
        } catch (exception: Exception) {
            analysisWriter?.runCatching { close() }
            analysisWriter = null
            analysisFile = null
            failRecording("Failed to start aim analysis recording", exception)
            return false
        }
    }

    private fun ensureAnalysisWriter() = analysisWriter != null || startAnalysisRecording()

    private fun finishAnalysisRecording(reason: String, notify: Boolean) {
        val writer = analysisWriter ?: return
        val file = analysisFile

        try {
            val end = baseRecord("end")
            end.addProperty("reason", reason)
            end.addProperty("duration_nanos", System.nanoTime() - analysisStartedAt)
            end.addProperty("tick_records", analysisTicks)
            end.addProperty("records", analysisRecords + 1)
            writeAnalysisRecord(end)
            writer.flush()
        } catch (exception: Exception) {
            exception.printStackTrace()
        } finally {
            writer.runCatching { close() }
            analysisWriter = null
            analysisFile = null
            recordsSinceFlush = 0
        }

        if (notify && file != null) {
            chat("Saved aim analysis recording as ${file.name} in ${file.parentFile}")
        }
    }

    private fun recordAnalysisTick() {
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        if (!ensureAnalysisWriter()) {
            return
        }

        val telemetry = RotationUtils.telemetrySnapshot()
        val auraTarget = KillAura.target
        val record = baseRecord("tick")

        record.addProperty("server_yaw", serverRotation.yaw)
        record.addProperty("server_pitch", serverRotation.pitch)
        record.addProperty("player_yaw", player.rotationYaw)
        record.addProperty("player_pitch", player.rotationPitch)
        record.addRotation("requested", telemetry.requestedRotation)
        record.addRotation("output", telemetry.outputRotation)
        record.addProperty("rotation_active", telemetry.rotationActive)
        record.addNullableProperty("humanize", telemetry.humanize)
        record.addNullableProperty("phase", telemetry.phase)
        record.addProperty("has_overshoot", telemetry.hasOvershoot)
        record.addProperty("source", when {
            KillAura.handleEvents() && auraTarget != null -> "killaura"
            telemetry.rotationActive -> "rotation_utils"
            else -> "manual"
        })
        record.addNullableProperty("kill_aura_target_id", auraTarget?.entityId)
        record.addProperty("no_rotate_hold", WaitTickUtils.hasScheduled(NoRotateSet))
        record.addProperty("short_stop_hold", WaitTickUtils.hasScheduled(RotationUtils))

        record.addProperty("player_x", player.posX)
        record.addProperty("player_y", player.posY)
        record.addProperty("player_z", player.posZ)
        record.addProperty("player_motion_x", player.motionX)
        record.addProperty("player_motion_y", player.motionY)
        record.addProperty("player_motion_z", player.motionZ)
        record.addProperty("player_on_ground", player.onGround)
        record.addProperty("player_speed_amplifier", player.getSpeedAmplifier())

        val targets = JsonArray()

        for (entity in world.playerEntities) {
            if (entity === player || !entity.isEntityAlive || player.getDistanceToEntity(entity) > TARGET_CAPTURE_RANGE) {
                continue
            }

            targets.add(createTargetRecord(player, entity))
        }

        record.add("targets", targets)
        writeAnalysisRecord(record)
        analysisTicks++
    }

    private fun createTargetRecord(player: EntityPlayer, entity: EntityPlayer): JsonObject {
        val box = entity.hitBox
        val center = box.center
        val referenceRotation = toRotation(center, false, player)
        val record = JsonObject()

        record.addProperty("id", entity.entityId)
        record.addProperty("x", entity.posX)
        record.addProperty("y", entity.posY)
        record.addProperty("z", entity.posZ)
        record.addProperty("prev_x", entity.prevPosX)
        record.addProperty("prev_y", entity.prevPosY)
        record.addProperty("prev_z", entity.prevPosZ)
        record.addProperty("motion_x", entity.motionX)
        record.addProperty("motion_y", entity.motionY)
        record.addProperty("motion_z", entity.motionZ)
        record.addProperty("box_min_x", box.minX)
        record.addProperty("box_min_y", box.minY)
        record.addProperty("box_min_z", box.minZ)
        record.addProperty("box_max_x", box.maxX)
        record.addProperty("box_max_y", box.maxY)
        record.addProperty("box_max_z", box.maxZ)
        record.addProperty("reference_yaw", referenceRotation.yaw)
        record.addProperty("reference_pitch", referenceRotation.pitch)
        record.addProperty("angular_distance", rotationDifference(referenceRotation, serverRotation))
        record.addProperty("distance", player.getDistanceToEntity(entity))
        record.addProperty("visible", isVisible(center))
        record.addProperty("selected", EntityUtils.isSelected(entity, true))
        record.addProperty("on_ground", entity.onGround)
        record.addProperty("speed_amplifier", entity.getSpeedAmplifier())

        return record
    }

    private fun baseRecord(type: String) = JsonObject().apply {
        addProperty("type", type)
        addProperty("tick", runTimeTicks)
        addProperty("time_nanos", System.nanoTime())
    }

    private fun writeAnalysisRecord(record: JsonObject) {
        val writer = analysisWriter ?: return

        try {
            COMPACT_GSON.toJson(record, writer)
            writer.newLine()
            analysisRecords++
            recordsSinceFlush++

            if (recordsSinceFlush >= FLUSH_INTERVAL_RECORDS) {
                writer.flush()
                recordsSinceFlush = 0
            }
        } catch (exception: Exception) {
            writer.runCatching { close() }
            analysisWriter = null
            failRecording("Failed to write aim analysis recording", exception)
        }
    }

    private fun failRecording(message: String, exception: Exception) {
        exception.printStackTrace()
        chat("$message, disabling module")
        failed = true
        nextTick { state = false }
    }

    private fun JsonObject.addRotation(prefix: String, rotation: Rotation?) {
        addNullableProperty("${prefix}_yaw", rotation?.yaw)
        addNullableProperty("${prefix}_pitch", rotation?.pitch)
    }

    private fun JsonObject.addNullableProperty(name: String, value: Number?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun JsonObject.addNullableProperty(name: String, value: Boolean?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun JsonObject.addNullableProperty(name: String, value: String?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun EntityPlayer.getSpeedAmplifier() = getActivePotionEffect(Potion.moveSpeed)?.amplifier ?: -1

    private val isAnalysisMode
        get() = activeMode.equals("Analysis", ignoreCase = true)

    private val ANALYSIS_DIR
        get() = File(FileManager.dir, "aim-recordings")

    private val FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val ANALYSIS_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
    private val COMPACT_GSON = Gson()

    private const val ANALYSIS_SCHEMA = 1
    private const val TARGET_CAPTURE_RANGE = 16f
    private const val FLUSH_INTERVAL_RECORDS = 100
}
