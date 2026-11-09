package net.ccbluex.liquidbounce.utils.rotation

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.utils.rotation.aim.AimEngine
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * Phase-2 spikes A+B (findings §8.3 item 5).
 *
 * A) JUnit 5 runs against this build's MC-free code.
 * B) Headless replay gauge (spec §11.7): drive [AimEngine] (MC-free) over the
 *    whole manual corpus (Sep-22 calm play + Oct-02 target-server play) — the
 *    player stream is exactly what the release-branch linger chased during the
 *    Sep-22 sessions — and emit a schema-9 JSONL candidate for:
 *
 *        python3 tools/aim_harness.py --candidate build/replay-spike/replay_candidate.jsonl
 *
 * Baseline (old HumanAimPath engine, 2026-09-23, spec §11.8): the candidate
 * FAILED lag-1 (0.231) / min-jerk (0.310) / epoch rate (2.607) / end-flip
 * (0.176) with the TRAIL signature — two independent paths detecting the
 * same stop-and-go defect. Phase-3 goal: this command exits 0.
 *
 * Determinism: one [AimEngine.State] per source file, corpus-baseline knobs
 * (SpeedScale 1, NoiseScale 1, ReactionDelay 0), seeded input — reruns
 * produce byte-identical candidates.
 *
 * The replay drives a continuous reference (the player's own view), so it
 * never presents the engine with an acquisition edge; ReactionDelayMs stays
 * at its unfitted default 0 here exactly as it does in the corpus.
 */
class HeadlessReplaySpikeTest {

    @Test
    fun junitPlatformIsWired() {
        assertTrue(true, "trivial spike-A probe")
    }

    @Test
    fun headlessReplayEmitsHarnessCandidate() {
        val corpusDir = File("tools")
        // BOTH manual sub-corpora. The session row carries `source` = the
        // recording it came from, so aim_harness.py can score the candidate
        // per sub-corpus and report a held-out breakdown instead of one
        // blended number over two different play styles.
        //
        // MANUAL ONLY. Once you record a bot session on the same server the
        // date prefix no longer identifies the group - `aim-2026-10-02_20-*`
        // is KillAura and also matches. Replaying one of those feeds the
        // player's own mouse through the engine while a shaper was live and
        // silently contaminates the candidate, so the same discriminator
        // human_features.classify_path uses is applied here:
        //   schema 9 -> aimbot_engaged, else rotation_active share;
        //   schema 8 -> rotation_active LINGERS on manual ticks, so only fresh
        //                targets are sound.
        val names = (corpusDir.list() ?: emptyArray<String>())
            .filter {
                (it.startsWith("aim-2026-09-22_") || it.startsWith("aim-2026-10-02_")) &&
                    it.endsWith(".jsonl") && isManualRecording(File(corpusDir, it))
            }
            .sorted()
        // The corpus is untracked: builds without it must stay green — the
        // spike simply doesn't run (Assumptions, not assertions).
        assumeTrue(names.isNotEmpty()) {
            "corpus absent from ${File(".").absolutePath} - replay spike skipped"
        }

        // Parse first, write later: one session header needs the shared gcd.
        var gcd = Float.NaN
        var sensitivity = Float.NaN
        val parsed = ArrayList<Pair<String, List<JsonObject>>>()
        for (name in names) {
            var fileGcd = Float.NaN
            var fileSens = Float.NaN
            val ticks = ArrayList<JsonObject>()
            File(corpusDir, name).forEachLine { raw ->
                if (raw.isBlank()) return@forEachLine
                val record = try {
                    JsonParser().parse(raw).asJsonObject
                } catch (ignored: Exception) {
                    return@forEachLine
                }
                when (record.get("type")?.asString) {
                    "session" -> {
                        fileGcd = record.get("gcd")?.asFloat ?: fileGcd
                        fileSens = record.get("mouse_sensitivity")?.asFloat ?: fileSens
                    }
                    "tick" -> ticks.add(record)
                }
            }
            assertTrue(ticks.isNotEmpty()) { "no ticks parsed from $name" }
            if (gcd.isNaN()) {
                gcd = fileGcd
                sensitivity = fileSens
            } else {
                assertTrue(abs(fileGcd - gcd) < 1e-9f) { "gcd differs in $name ($fileGcd vs $gcd)" }
            }
            parsed.add(name to ticks)
        }
        assertTrue(gcd.isFinite() && gcd > 0f) { "no gcd found in corpus session lines" }

        val outDir = File("build/replay-spike").apply { mkdirs() }
        val outFile = File(outDir, "replay_candidate.jsonl")
        val gson = Gson()
        var totalEmitted = 0

        outFile.bufferedWriter().use { writer ->
            for ((index, entry) in parsed.withIndex()) {
                val (name, ticks) = entry
                // One session row per source recording: the harness judges
                // the human group per file, so the candidate must split at
                // the same seams — cross-session teleports are not engine
                // motion (they inflate variance and crush lag-1).
                gson.toJson(
                    JsonObject().apply {
                        addProperty("type", "session")
                        addProperty("schema", 9)
                        addProperty("source", name)
                        addProperty("tick_rate", 20)
                        addProperty("gcd", gcd)
                        addProperty("mouse_sensitivity", sensitivity)
                    },
                    writer,
                )
                writer.newLine()

                val engineState = AimEngine.State()
                // Corpus-baseline knobs (spec §11.3): speed 1, noise 1,
                // events 1, no reaction delay.
                val knobs = AimEngine.Knobs()
                // Release-branch drive (findings §8.3 item 2): the shaper
                // chases the PLAYER's view, not a combat target.
                var out: Rotation? = null
                var emitted = 0

                for (t in ticks) {
                    val py = t.get("player_yaw")?.takeIf { !it.isJsonNull }?.asFloat ?: continue
                    val pp = t.get("player_pitch")?.takeIf { !it.isJsonNull }?.asFloat ?: continue
                    val tickNo = t.get("tick")?.asInt ?: continue

                    val current = out ?: Rotation(snap(py, gcd), snap(pp, gcd))
                    val next = AimEngine.step(
                        AimEngine.Input(
                            current = current,
                            reference = Rotation(py, pp),
                            engaged = true,
                            tick = tickNo,
                            gcd = gcd,
                            seed = index.toLong(),
                            knobs = knobs,
                        ),
                        engineState,
                    )
                    val snapped = Rotation(snap(next.yaw, gcd), snap(next.pitch, gcd))

                    gson.toJson(
                        JsonObject().apply {
                            addProperty("type", "tick")
                            addProperty("tick", tickNo)
                            addProperty("server_yaw", snapped.yaw)
                            addProperty("server_pitch", snapped.pitch)
                            addProperty("player_yaw", py)
                            addProperty("player_pitch", pp)
                            // Clean pre-noise command (forensics: end-flip
                            // diagnosis needs the intended, not emitted, tail)
                            addProperty("cmd_yaw", engineState.cmdYaw)
                            addProperty("cmd_pitch", engineState.cmdPitch)
                            addProperty("source", "replay_spike")
                        },
                        writer,
                    )
                    writer.newLine()
                    out = snapped
                    emitted++
                }
                totalEmitted += emitted
                println("replay $name: $emitted ticks")
            }

            gson.toJson(
                JsonObject().apply {
                    addProperty("type", "end")
                    addProperty("reason", "replay_spike")
                    addProperty("tick_records", totalEmitted)
                },
                writer,
            )
            writer.newLine()
        }

        assertTrue(totalEmitted > 1_000) { "only $totalEmitted ticks replayed" }
        assertTrue(outFile.length() > 100_000) { "candidate too small: ${outFile.length()} bytes" }

        // Output forensics: finite, clamped, and on the gcd grid (the same
        // tolerance RotationRecorder/grid checks use).
        var prev: Pair<Float, Float>? = null
        var checked = 0
        outFile.forEachLine { raw ->
            if (!raw.contains("\"server_yaw\"")) return@forEachLine
            val r = JsonParser().parse(raw).asJsonObject
            val y = r.get("server_yaw").asFloat
            val p = r.get("server_pitch").asFloat
            assertTrue(y.isFinite() && p.isFinite() && p in -90f..90f) { "bad rotation $y/$p" }
            prev?.let { (py, pp) ->
                assertTrue(fracSteps(y - py, gcd) <= GRID_TOL && fracSteps(p - pp, gcd) <= GRID_TOL) {
                    "off-grid delta ${(y - py) / gcd}/${(p - pp) / gcd} steps at tick ${r.get("tick").asInt}"
                }
            }
            prev = y to p
            checked++
        }
        assertTrue(checked > 1_000) { "grid re-check saw only $checked records" }
        println("replay candidate OK: $checked ticks -> ${outFile.path}")
    }

    /** Mirrors human_features.classify_path: is this recording pure manual play? */
    private fun isManualRecording(file: File): Boolean {
        var schema = 0
        var ticks = 0
        var engaged = 0
        var active = 0
        var fresh = 0
        file.forEachLine { raw ->
            if (raw.isBlank()) return@forEachLine
            if (!raw.contains("\"tick\"") && !raw.contains("\"session\"")) return@forEachLine
            val r = try {
                JsonParser().parse(raw).asJsonObject
            } catch (ignored: Exception) {
                return@forEachLine
            }
            when (r.get("type")?.asString) {
                "session" -> if (schema == 0) schema = r.get("schema")?.asInt ?: 0
                "tick" -> {
                    ticks++
                    if (r.get("aimbot_engaged")?.takeIf { !it.isJsonNull }?.asBoolean == true) engaged++
                    if (r.get("rotation_active")?.takeIf { !it.isJsonNull }?.asBoolean == true) active++
                    if (r.get("target_yaw")?.takeIf { !it.isJsonNull } != null) fresh++
                }
            }
        }
        if (ticks == 0) return true
        if (engaged > 0) return false
        if (schema <= 8) return fresh == 0
        return active.toDouble() / ticks <= 0.25
    }

    private fun snap(value: Float, gcd: Float) = Math.round(value / gcd) * gcd

    private fun fracSteps(delta: Float, gcd: Float): Float {
        val k = delta / gcd
        return abs(k - kotlin.math.round(k))
    }

    companion object {
        /** Same fractional-step tolerance as RotationRecorder and the harness. */
        private const val GRID_TOL = 0.02f
    }
}
