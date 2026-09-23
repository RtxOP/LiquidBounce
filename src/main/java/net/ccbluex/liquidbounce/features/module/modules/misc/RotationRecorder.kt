/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.misc

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.event.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.MotionEvent
import net.ccbluex.liquidbounce.event.PacketEvent
import net.ccbluex.liquidbounce.event.WorldEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.modules.combat.Aimbot
import net.ccbluex.liquidbounce.file.FileManager
import net.ccbluex.liquidbounce.utils.client.ClientUtils.runTimeTicks
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.rotation
import net.ccbluex.liquidbounce.utils.rotation.RaycastUtils
import net.ccbluex.liquidbounce.utils.rotation.Rotation
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.angleDifference
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.getFixedAngleDelta
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.lastRotations
import net.ccbluex.liquidbounce.utils.rotation.RotationUtils.serverRotation
import net.ccbluex.liquidbounce.utils.timing.TickedActions.nextTick
import net.ccbluex.liquidbounce.utils.timing.WaitTickUtils
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.XYChart
import org.knowm.xchart.XYSeries
import org.lwjgl.opengl.Display
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.hypot
import kotlin.math.round

/**
 * Verifies live that humanization works as intended, logging proof to a JSONL file.
 *
 * One session = one `aim-<timestamp>.jsonl` file in `aim-recordings/` with:
 * - a `session` line (gcd, sensitivity, server ip, ping, recorder settings),
 * - a `tick` line per tick (server/player/target/output rotations, signed diffs,
 *   speed, gcd steps, driving module + its shaping settings, hold flags,
 *   crosshair-raycast entity marker, and per-tick check results
 *   `grid_ok` / `caps_ok` / `valid`),
 * - a `packet` line per sent look packet when LogMode records packets (the
 *   exact yaw/pitch leaving the client with per-packet grid forensics),
 * - an `end` line (reason, totals, `summary` with fail counts and verdict).
 *
 * Checks mirror `tools/human_aim_test.py` but run on real server rotations:
 * - grid: each tick delta is an integer number of gcd steps from the previous
 *   server rotation (a pitch delta touching exactly +-90 is exempt: vanilla
 *   clamps pitch after the delta, so leaving a clamped value is legit too),
 * - caps: each tick delta stays within the configured ceiling
 *   (plus gcd/2 snap rounding),
 * - valid: finite rotations, pitch clamped to +-90.
 *
 * Only ticks with an active rotation driver count toward the verdict; a
 * driver is active when its shaper actually produced that tick's rotation
 * (not merely that the module is enabled), so idle-enable windows show up
 * as `source: manual` with null checks instead of polluting the corpus.
 * The chart PNG is just a quick visual of the same diffs.
 */
object RotationRecorder : Module("RotationRecorder", Category.MISC) {

    private val saveChart by boolean("SaveChart", true)
    private val captureNegativeNumbers by boolean("CaptureNegativeNumbers", false)
    private val logMode by choices("LogMode", arrayOf("Tick", "Packets", "TickAndPackets"), "Tick")

    private val ticks = mutableListOf<Double>()
    private val yawDiffs = mutableListOf<Double>()
    private val pitchDiffs = mutableListOf<Double>()

    private var chart: XYChart? = null
    private var failed = false

    private var logWriter: BufferedWriter? = null
    private var logFile: File? = null
    private var logStartedAt = 0L
    private var logRecords = 0
    private var logTicks = 0
    private var recordsSinceFlush = 0

    // Per-packet log state (LogMode Packets / TickAndPackets only).
    private var packetRecords = 0
    private var packetGridFails = 0
    private var lastPacketYaw: Float? = null
    private var lastPacketPitch: Float? = null

    // Server-teleport exemption: respawns / arena teleports force exact
    // rotations that no grid or cap check may judge. Ticks at or before this
    // tick (runTimeTicks) report null checks instead of false verdicts.
    private var teleportExemptUntil = Int.MIN_VALUE

    // Last attack tick (C02 ATTACK sends). Lets analysis correlate snaps with
    // attacks — the correlation genuine snap checks look for.
    private var lastAttackTick = Int.MIN_VALUE

    // Live verification counters (active-driver ticks only).
    private var activeTicks = 0
    private var gridFails = 0
    private var capsFails = 0
    private var teleportJumps = 0
    private var invalidTicks = 0
    private var maxGridErr = 0f
    private var maxSpeed = 0f

    private val checkFails
        get() = gridFails + capsFails + teleportJumps + invalidTicks

    override val tag
        get() = logWriter?.let { if (checkFails > 0) "FAIL $checkFails" else "$logTicks" }

    override fun onEnable() {
        failed = false
        ticks.clear()
        yawDiffs.clear()
        pitchDiffs.clear()
        updateRecordInfo(true)

        if (saveChart) {
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
                exception.printStackTrace()
                chat("Failed to start chart, continuing with file log only")
                chart = null
            }
        }

        if (!startLogRecording()) {
            return
        }

        chat("Started recording rotations.")
    }

    override fun onDisable() {
        if (saveChart && !failed && chart != null) {
            saveChart()
        }

        finishLogRecording("module_disabled", notify = !failed)

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

        updateRecordInfo()

        chart?.updateXYSeries("Yaw Differences", ticks.toDoubleArray(), yawDiffs.toDoubleArray(), null)
        chart?.updateXYSeries("Pitch Differences", ticks.toDoubleArray(), pitchDiffs.toDoubleArray(), null)

        recordLogTick()
    }

    val onWorld = handler<WorldEvent> {
        if (logWriter != null) {
            finishLogRecording("world_change", notify = false)
        }
    }

    /**
     * Records every look packet leaving the client (LogMode Packets /
     * TickAndPackets). Runs after RotationUtils (priority -1 vs default 0),
     * so the logged yaw/pitch are the post-spoof values the server sees.
     * Cancelled packets are skipped: they never leave the client (Blink /
     * FakeLag buffers release them later through a fresh SEND event).
     */
    val onPacket = handler<PacketEvent>(priority = -1) { event ->
        if (failed || logMode == "Tick") {
            return@handler
        }
        if (event.eventType != EventState.SEND || event.isCancelled) {
            return@handler
        }
        val packet = event.packet as? C03PacketPlayer ?: return@handler
        if (!packet.rotating) {
            return@handler
        }

        recordLogPacket(packet.rotation)
    }

    /**
     * Marks the next ticks exempt after the server forces a position/rotation
     * (respawn, arena teleport, lagback). The client accepts those angles
     * verbatim like vanilla — flagging them would be a false positive.
     * Also stamps attack packets so snaps can be correlated with attacks.
     */
    val onTeleport = handler<PacketEvent> { event ->
        if (failed || event.isCancelled) {
            return@handler
        }
        if (event.eventType == EventState.RECEIVE && event.packet is S08PacketPlayerPosLook) {
            teleportExemptUntil = runTimeTicks + TELEPORT_EXEMPT_TICKS
        }
        if (event.eventType == EventState.SEND) {
            val packet = event.packet
            if (packet is C02PacketUseEntity && packet.action == C02PacketUseEntity.Action.ATTACK) {
                lastAttackTick = runTimeTicks
            }
        }
    }

    val onShutdown = handler<ClientShutdownEvent>(always = true) {
        if (logWriter != null) {
            finishLogRecording("client_shutdown", notify = false)
        }
    }

    private fun saveChart() {
        val chart = this.chart ?: return
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

    private fun startLogRecording(): Boolean {
        if (logWriter != null) {
            return true
        }

        try {
            if (!LOG_DIR.exists() && !LOG_DIR.mkdirs()) {
                throw IOException("Could not create ${LOG_DIR.absolutePath}")
            }

            val timestamp = LOG_TIME_FORMAT.format(LocalDateTime.now())
            var file = File(LOG_DIR, "aim-$timestamp.jsonl")
            var suffix = 1

            while (file.exists()) {
                file = File(LOG_DIR, "aim-$timestamp-${suffix++}.jsonl")
            }

            logFile = file
            logWriter = file.bufferedWriter()
            logStartedAt = System.nanoTime()
            logRecords = 0
            logTicks = 0
            recordsSinceFlush = 0
            packetRecords = 0
            packetGridFails = 0
            lastPacketYaw = null
            lastPacketPitch = null
            teleportExemptUntil = Int.MIN_VALUE
            lastAttackTick = Int.MIN_VALUE
            activeTicks = 0
            gridFails = 0
            capsFails = 0
            teleportJumps = 0
            invalidTicks = 0
            maxGridErr = 0f
            maxSpeed = 0f

            val session = baseRecord("session")
            session.addProperty("schema", LOG_SCHEMA)
            session.addProperty("created_at", LocalDateTime.now().toString())
            session.addProperty("tick_rate", 20)
            session.addProperty("mouse_sensitivity", mc.gameSettings.mouseSensitivity)
            session.addProperty("gcd", getFixedAngleDelta())
            // Corpus-v2 metadata: which server the baseline was taken on and
            // its latency at session start (null in singleplayer / before the
            // first ping reply — analysis treats missing as unknown).
            session.addProperty("server_ip", mc.currentServerData?.serverIP)
            session.addProperty("ping_ms", mc.currentServerData?.pingToServer)
            session.addProperty("save_chart", saveChart)
            session.addProperty("capture_negative_numbers", captureNegativeNumbers)
            session.addProperty("log_mode", logMode)
            writeLogRecord(session)

            chat("Started rotation log: ${file.name}")
            return true
        } catch (exception: Exception) {
            logWriter?.runCatching { close() }
            logWriter = null
            logFile = null
            failRecording("Failed to start rotation log", exception)
            return false
        }
    }

    private fun ensureLogWriter() = logWriter != null || startLogRecording()

    private fun finishLogRecording(reason: String, notify: Boolean) {
        val writer = logWriter ?: return
        val file = logFile

        val summary = JsonObject().apply {
            addProperty("active_ticks", activeTicks)
            addProperty("grid_fails", gridFails)
            addProperty("caps_fails", capsFails)
            addProperty("teleport_jumps", teleportJumps)
            addProperty("invalid_ticks", invalidTicks)
            addProperty("max_grid_err_steps", maxGridErr)
            addProperty("max_speed", maxSpeed)
            addProperty("packet_records", packetRecords)
            addProperty("packet_grid_fails", packetGridFails)
            addProperty("verdict", verdict())
        }

        try {
            val end = baseRecord("end")
            end.addProperty("reason", reason)
            end.addProperty("duration_nanos", System.nanoTime() - logStartedAt)
            end.addProperty("tick_records", logTicks)
            end.addProperty("packet_records", packetRecords)
            end.addProperty("records", logRecords + 1)
            end.add("summary", summary)
            writeLogRecord(end)
            writer.flush()
        } catch (exception: Exception) {
            exception.printStackTrace()
        } finally {
            writer.runCatching { close() }
            logWriter = null
            logFile = null
            recordsSinceFlush = 0
        }

        if (notify && file != null) {
            chat("Saved rotation log as ${file.name} in ${file.parentFile}")
            chat("Humanization check: ${verdict()} ($activeTicks active ticks)")
        }
    }

    private fun verdict(): String = when {
        activeTicks == 0 -> "NO_DATA"
        checkFails == 0 -> "PASS"
        else -> "FAIL"
    }

    private fun recordLogTick() {
        if (!ensureLogWriter()) {
            return
        }

        val player = mc.thePlayer
        val settings = RotationUtils.activeSettings
        // Crosshair entity marker: what the dot rests on this tick, in manual
        // play as well as while engaged — null->id transitions are engagement
        // onsets, the reference signal for fitting reaction time / attack lead.
        val crosshairEntity = player?.let {
            RaycastUtils.raycastEntity(CROSSHAIR_REACH, it.rotationYaw, it.rotationPitch) { true }
        }
        // Engagement = a shaper actually produced this tick's rotation.
        // `settings != null` / `handleEvents()` linger across holds, GUI-open
        // early returns and idle-enable windows; counting them diluted the
        // active corpus with manual play (and hid dual-driver ticks).
        // Post-§11.6 merge: Aimbot is an activeSettings owner like any other,
        // so the shared stamp covers it — no private Aimbot stamp remains.
        val engagedShaper = RotationUtils.isShapingNow()
        val engagedAimbot = engagedShaper && settings?.ownerModule === Aimbot

        val source = if (engagedShaper) {
            settings?.ownerModule?.name ?: "RotationUtils"
        } else {
            "manual"
        }

        val yawDiff = angleDifference(serverRotation.yaw, lastRotations[1].yaw)
        val pitchDiff = angleDifference(serverRotation.pitch, lastRotations[1].pitch)
        // Raw (unwrapped) yaw delta: this is what real GCD checks compare.
        // 360 deg is not a multiple of any gcd, so a wrapped check false-fails
        // every +-360 branch crossing even when the raw packets are on-grid.
        val rawYawDiff = serverRotation.yaw - lastRotations[1].yaw
        val speed = hypot(yawDiff, pitchDiff)
        val gcd = getFixedAngleDelta()
        val rotationActive = engagedShaper

        val settingsJson: JsonObject? = settings?.snapshot()

        // Effective ceilings for this tick: the owner's configured maxima
        // (plus gcd/2 snap rounding) — forensics, not engine bounds.
        val hCap = settingsJson?.floatOr("h_max", 180f)
        val vCap = settingsJson?.floatOr("v_max", 180f)

        val valid = serverRotation.yaw.isFinite() && serverRotation.pitch.isFinite() &&
            serverRotation.pitch in -90f..90f
        val prevPitch = lastRotations[1].pitch
        // Server-forced rotations (respawn / arena teleport / lagback) are
        // accepted verbatim like vanilla: report neutral checks, not fails.
        val exempt = runTimeTicks <= teleportExemptUntil
        val gridOk = if (!rotationActive || exempt) {
            null
        } else {
            isOnGrid(rawYawDiff, pitchDiff, serverRotation.pitch, prevPitch, gcd)
        }
        // A raw absolute jump over half a turn in one tick is never legitimate
        // shaper output (caps are <= 20 deg). Branch crossings from external
        // edits land here instead of polluting the grid check. Respawn / world
        // changes can trip this once; read it as forensics, not a bare ban.
        // Wrap crossings (|raw| > 180 with a small wrapped remainder) are
        // vanilla behavior, not teleports; genuine snaps still fail caps.
        val teleportOk = if (!rotationActive || exempt) {
            null
        } else {
            rawYawDiff.isFinite() && (abs(rawYawDiff) <= 180f || abs(wrapDelta(rawYawDiff)) <= 90f)
        }
        val capsOk = if (!rotationActive || exempt || hCap == null || vCap == null) {
            null
        } else {
            abs(yawDiff) <= hCap + gcd / 2 + 1e-6f && abs(pitchDiff) <= vCap + gcd / 2 + 1e-6f
        }

        if (rotationActive) {
            activeTicks++
            if (speed.isFinite()) {
                maxSpeed = maxOf(maxSpeed, speed)
            }
            val gridErr = gridErrSteps(rawYawDiff, pitchDiff, serverRotation.pitch, prevPitch, gcd)
            if (gridErr.isFinite()) {
                maxGridErr = maxOf(maxGridErr, gridErr)
            }
            if (!valid) invalidTicks++
            if (gridOk == false) gridFails++
            if (capsOk == false) capsFails++
            if (teleportOk == false) teleportJumps++
        }

        val record = baseRecord("tick")
        record.addProperty("server_yaw", serverRotation.yaw)
        record.addProperty("server_pitch", serverRotation.pitch)
        record.addRotation("player", player?.let { Rotation(it.rotationYaw, it.rotationPitch) })
        // Aim point for this tick, only when someone actually commanded aim
        // recently. Stale leftovers (module stopped requesting but resetTicks
        // still counts down / never cleared) used to make aim-error garbage.
        // Post-§11.6 every owner — Aimbot included — sets targetRotation, so
        // one stamp serves all shapers.
        val targetRot = if (engagedShaper && RotationUtils.hasFreshTarget()) {
            RotationUtils.targetRotation?.copy()
        } else {
            null
        }
        record.addRotation("target", targetRot)
        record.addRotation("output", RotationUtils.currentRotation?.copy())
        // True when Aimbot owns this tick's shaper (<=1 covers either POST
        // ordering). Aimbot-source ticks without this are raw mouse play.
        record.addProperty("aimbot_engaged", engagedAimbot)
        record.add(
            "crosshair_entity_id",
            crosshairEntity?.entityId?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE
        )
        record.add(
            "crosshair_entity_name",
            crosshairEntity?.name?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE
        )
        record.add("ticks_since_attack", ticksSinceAttackJson())
        record.addProperty("yaw_diff", yawDiff)
        record.addProperty("pitch_diff", pitchDiff)
        record.addProperty("yaw_diff_raw", rawYawDiff)
        record.addProperty("speed", speed)
        record.addProperty("gcd", gcd)
        record.addProperty("gcd_steps_yaw", yawDiff / gcd)
        record.addProperty("gcd_steps_pitch", pitchDiff / gcd)
        record.addProperty("gcd_steps_yaw_raw", rawYawDiff / gcd)
        record.addProperty("rotation_active", rotationActive)
        record.addProperty("source", source)
        record.add("settings", settingsJson ?: JsonNull.INSTANCE)
        record.add("h_cap", hCap?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE)
        record.add("v_cap", vCap?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE)
        record.add("grid_ok", gridOk?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE)
        record.add("caps_ok", capsOk?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE)
        record.add("teleport_ok", teleportOk?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE)
        record.addProperty("valid", valid)
        record.addProperty("exempt", exempt)
        record.addProperty("no_rotate_hold", WaitTickUtils.hasScheduled(NoRotateSet))
        record.addProperty("short_stop_hold", WaitTickUtils.hasScheduled(RotationUtils))

        writeLogRecord(record)
        logTicks++
    }

    /**
     * One `packet` line per sent look packet: the exact yaw/pitch leaving the
     * client. Complements `tick` lines (shaper-state view) with the wire view
     * an anticheat actually checks. Per-packet `grid_ok` is forensics only:
     * the PASS/FAIL verdict stays tick-based so a downstream packet edit by
     * another module shows up here without misattributing it to the shaper.
     */
    private fun recordLogPacket(sent: Rotation) {
        if (!ensureLogWriter()) {
            return
        }

        val settings = RotationUtils.activeSettings
        // Same engagement semantics as tick lines: a shaper produced the
        // packet this tick, not merely "module is toggled on".
        val engagedShaper = RotationUtils.isShapingNow()
        val engagedAimbot = engagedShaper && settings?.ownerModule === Aimbot
        val rotationActive = engagedShaper

        val source = if (engagedShaper) {
            settings?.ownerModule?.name ?: "RotationUtils"
        } else {
            "manual"
        }

        val prevYaw = lastPacketYaw
        val prevPitch = lastPacketPitch
        val gcd = getFixedAngleDelta()

        val yawDiffRaw = prevYaw?.let { sent.yaw - it }
        val pitchDiff = prevPitch?.let { sent.pitch - it }

        val valid = sent.yaw.isFinite() && sent.pitch.isFinite() &&
            sent.pitch in -90f..90f
        val exempt = runTimeTicks <= teleportExemptUntil
        val gridOk = if (!rotationActive || exempt || yawDiffRaw == null || pitchDiff == null) {
            null
        } else {
            isOnGrid(yawDiffRaw, pitchDiff, sent.pitch, prevPitch, gcd)
        }
        val teleportOk = yawDiffRaw?.let {
            if (!rotationActive || exempt) null
            else it.isFinite() && (abs(it) <= 180f || abs(wrapDelta(it)) <= 90f)
        }

        if (gridOk == false) packetGridFails++

        val record = baseRecord("packet")
        record.addProperty("yaw", sent.yaw)
        record.addProperty("pitch", sent.pitch)
        record.add("yaw_diff_raw", yawDiffRaw?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE)
        record.add("pitch_diff", pitchDiff?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE)
        record.add("gcd_steps_yaw_raw", yawDiffRaw?.let { COMPACT_GSON.toJsonTree(it / gcd) } ?: JsonNull.INSTANCE)
        record.add("gcd_steps_pitch", pitchDiff?.let { COMPACT_GSON.toJsonTree(it / gcd) } ?: JsonNull.INSTANCE)
        record.addProperty("gcd", gcd)
        record.addProperty("rotation_active", rotationActive)
        record.addProperty("source", source)
        record.addProperty("aimbot_engaged", engagedAimbot)
        record.add("ticks_since_attack", ticksSinceAttackJson())
        record.add("grid_ok", gridOk?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE)
        record.add("teleport_ok", teleportOk?.let { COMPACT_GSON.toJsonTree(it) } ?: JsonNull.INSTANCE)
        record.addProperty("valid", valid)
        record.addProperty("exempt", exempt)

        writeLogRecord(record)
        packetRecords++
        lastPacketYaw = sent.yaw
        lastPacketPitch = sent.pitch
    }

    /**
     * Vanilla grid rule: each raw (unwrapped) delta must be an integer
     * number of gcd steps, matching how real sensitivity solvers compare
     * consecutive packets. A pitch delta touching exactly +-90 is exempt:
     * vanilla clamps pitch after applying the delta, so leaving a clamped
     * value legitimately produces a fractional step (same as vanilla
     * high-pitch flicks). A yaw wrap crossing (|raw| > 180) is evaluated on
     * the wrapped remainder instead: vanilla folds yaw at +-180, so the wire
     * shows raw +-360 for a small real move on every legit client.
     */
    private fun isOnGrid(
        yawDiff: Float, pitchDiff: Float, pitch: Float, prevPitch: Float, gcd: Float
    ): Boolean {
        if (!gcd.isFinite() || gcd <= 0f) return false
        val yawForGrid = if (abs(yawDiff) > 180f) wrapDelta(yawDiff) else yawDiff
        if (isClampedPitch(pitch) || isClampedPitch(prevPitch)) {
            return gridErr(yawForGrid, gcd) <= GRID_TOLERANCE_STEPS
        }
        return gridErr(yawForGrid, gcd) <= GRID_TOLERANCE_STEPS &&
            gridErr(pitchDiff, gcd) <= GRID_TOLERANCE_STEPS
    }

    /** Folds a raw yaw delta into -180..180 (see [isOnGrid]). */
    private fun wrapDelta(d: Float) = ((d + 540f) % 360f) - 180f

    /** Ticks since the last attack packet, or null when unknown (session start). */
    private fun ticksSinceAttackJson() =
        if (lastAttackTick == Int.MIN_VALUE) JsonNull.INSTANCE
        else COMPACT_GSON.toJsonTree(runTimeTicks - lastAttackTick)

    private fun gridErrSteps(
        yawDiff: Float, pitchDiff: Float, pitch: Float, prevPitch: Float, gcd: Float
    ): Float {
        if (!gcd.isFinite() || gcd <= 0f) return Float.POSITIVE_INFINITY
        val yawForGrid = if (abs(yawDiff) > 180f) wrapDelta(yawDiff) else yawDiff
        return if (isClampedPitch(pitch) || isClampedPitch(prevPitch)) {
            gridErr(yawForGrid, gcd)
        } else {
            maxOf(gridErr(yawForGrid, gcd), gridErr(pitchDiff, gcd))
        }
    }

    private fun isClampedPitch(pitch: Float) = abs(abs(pitch) - 90f) < 1e-6f

    private fun gridErr(diff: Float, gcd: Float): Float {
        val k = diff / gcd
        return abs(k - round(k))
    }

    private fun JsonObject.floatOr(name: String, fallback: Float): Float? {
        val element = get(name) ?: return fallback
        return try {
            element.asFloat
        } catch (exception: Exception) {
            fallback
        }
    }

    private fun baseRecord(type: String) = JsonObject().apply {
        addProperty("type", type)
        addProperty("tick", runTimeTicks)
        addProperty("time_nanos", System.nanoTime())
    }

    private fun writeLogRecord(record: JsonObject) {
        val writer = logWriter ?: return

        try {
            COMPACT_GSON.toJson(record, writer)
            writer.newLine()
            logRecords++
            recordsSinceFlush++

            if (recordsSinceFlush >= FLUSH_INTERVAL_RECORDS) {
                writer.flush()
                recordsSinceFlush = 0
            }
        } catch (exception: Exception) {
            writer.runCatching { close() }
            logWriter = null
            failRecording("Failed to write rotation log", exception)
        }
    }

    private fun failRecording(message: String, exception: Exception) {
        exception.printStackTrace()
        chat("$message, disabling module")
        failed = true
        nextTick { state = false }
    }

    private fun JsonObject.addRotation(prefix: String, rotation: Rotation?) {
        if (rotation == null) {
            add("${prefix}_yaw", JsonNull.INSTANCE)
            add("${prefix}_pitch", JsonNull.INSTANCE)
        } else {
            addProperty("${prefix}_yaw", rotation.yaw)
            addProperty("${prefix}_pitch", rotation.pitch)
        }
    }

    private val LOG_DIR
        get() = File(FileManager.dir, "aim-recordings")

    private val FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
    private val COMPACT_GSON = Gson()

    /** 9 = crosshair entity marker on ticks + server_ip/ping_ms session fields (additive). */
    private const val LOG_SCHEMA = 9
    private const val FLUSH_INTERVAL_RECORDS = 100

    /** Vanilla crosshair reach — the marker is what the player's dot rests on. */
    private const val CROSSHAIR_REACH = 4.5

    /** Ticks after an S08 during which rotation checks stay neutral. */
    private const val TELEPORT_EXEMPT_TICKS = 5

    /** Same fractional-step tolerance as tools/human_aim_test.py. */
    private const val GRID_TOLERANCE_STEPS = 0.02f
}
