package net.ccbluex.liquidbounce.utils.rotation.aim

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.utils.rotation.Rotation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * INVARIANT: every knob on [AimEngine.Knobs] must be observable in the
 * emitted rotation.
 *
 * Why this test exists: `eventRateScale` shipped as a user-facing slider for
 * months of work and changed literally nothing. Stage 5's dwell draw is
 * write-only — I1 already freezes the output inside the 1-gcd floor and I2
 * preempts the dwell on the tick error reaches 1 gcd — so the knob was
 * inert by construction, not by accident. `EventSchedulerTest` tested the
 * sampler's arithmetic in isolation and stayed green the whole time.
 *
 * This test drives the real engine over a recorded human stream and asserts
 * a non-baseline value of each knob changes the emitted bytes. It also pins
 * the knob set by reflection, so a new inert knob cannot be added without
 * this test being updated to justify it.
 */
class KnobsObservabilityTest {

    private val gcd = 0.09143358f

    /** Recorded human input; the reference stream the engine has to track. */
    private fun corpusTicks(): List<JsonObject> {
        val dir = File("tools")
        val names = (dir.list() ?: emptyArray<String>())
            .filter { (it.startsWith("aim-2026-09-22_") || it.startsWith("aim-2026-10-02_")) && it.endsWith(".jsonl") }
            .sorted()
        assumeTrue(names.isNotEmpty()) { "corpus absent - observability probe skipped" }
        val out = ArrayList<JsonObject>()
        for (name in names) {
            File(dir, name).forEachLine { raw ->
                if (raw.isBlank()) return@forEachLine
                val r = try { JsonParser().parse(raw).asJsonObject } catch (e: Exception) { return@forEachLine }
                if (r.get("type")?.asString == "tick") out.add(r)
            }
        }
        assumeTrue(out.size > 1000) { "corpus too small - probe skipped" }
        return out
    }

    /**
     * Replays the recorded human stream with a realistic acquisition pattern:
     * `freshTarget` on the first tick of every motion run in the reference.
     * A targeting module re-acquires when it locks a new aim point, and a
     * continuous reference (the player's own view) has no acquisition edges of
     * its own — without this the reaction-delay knob would look inert for the
     * same reason it was nearly shipped as one.
     */
    private fun emit(ticks: List<JsonObject>, knobs: AimEngine.Knobs): String {
        val gson = Gson()
        val state = AimEngine.State()
        var out: Rotation? = null
        val sb = StringBuilder()
        var prev: Pair<Float, Float>? = null
        var moving = false
        ticks.forEachIndexed { i, t ->
            val py = t.get("player_yaw")?.takeIf { !it.isJsonNull }?.asFloat ?: return@forEachIndexed
            val pp = t.get("player_pitch")?.takeIf { !it.isJsonNull }?.asFloat ?: return@forEachIndexed
            val tickNo = t.get("tick")?.asInt ?: return@forEachIndexed
            val fresh = prev != null && !moving && referenceMoved(prev!!, py, pp)
            moving = referenceMoved(prev ?: (py to pp), py, pp)
            prev = py to pp
            val current = out ?: Rotation(py, pp)
            val next = AimEngine.step(
                AimEngine.Input(
                    current = current,
                    reference = Rotation(py, pp),
                    engaged = true,
                    tick = tickNo,
                    gcd = gcd,
                    seed = 1L,
                    knobs = knobs,
                    freshTarget = fresh,
                ),
                state,
            )
            sb.append(gson.toJson(next)).append('\n')
            out = next
        }
        return sb.toString()
    }

    /** Reference moved more than a fifth of a gcd step this tick. */
    private fun referenceMoved(prev: Pair<Float, Float>, y: Float, p: Float): Boolean =
        abs(wrap180(y - prev.first)) > gcd / 5f || abs(p - prev.second) > gcd / 5f

    /** Local wrap so this probe never touches the Minecraft-bound object. */
    private fun wrap180(d: Float): Float {
        var x = (d + 180f) % 360f
        if (x < 0f) x += 360f
        return x - 180f
    }

    @Test
    fun everyKnobChangesTheEmittedOutput() {
        val ticks = corpusTicks()
        val baseline = emit(ticks, AimEngine.Knobs())
        val probes = mapOf(
            "speedScale" to AimEngine.Knobs(speedScale = 3f),
            "noiseScale" to AimEngine.Knobs(noiseScale = 0f),
            "reactionDelayMs" to AimEngine.Knobs(reactionDelayMs = 0),
            "maxYawStep" to AimEngine.Knobs(maxYawStep = 2f),
            "maxPitchStep" to AimEngine.Knobs(maxPitchStep = 2f),
        )
        for ((name, knobs) in probes) {
            assertTrue(emit(ticks, knobs) != baseline) {
                "$name is INERT: a non-default value produced byte-identical output. " +
                    "Every knob on AimEngine.Knobs must be observable in the emitted " +
                    "rotation, or it must be removed."
            }
        }
    }

    @Test
    fun knobSetMatchesTheObservableSurface() {
        // Reflection pin: adding a Knobs field breaks this test on purpose, so
        // a new knob has to come with an observability argument.
        val declared = AimEngine.Knobs::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name }
            .sorted()
        assertTrue(
            declared == listOf(
                "maxPitchStep", "maxYawStep", "noiseScale", "reactionDelayMs", "speedScale"),
        ) {
            "AimEngine.Knobs surface changed to $declared. Each field must be " +
                "observable in the emitted rotation (see everyKnobChangesTheEmittedOutput) " +
                "or be removed. eventRateScale was removed for exactly this reason."
        }
        assertTrue(AimEngine.Knobs().maxYawStep == AimEngine.UNCONSTRAINED &&
            AimEngine.Knobs().maxPitchStep == AimEngine.UNCONSTRAINED
        ) {
            "the per-axis speed ceiling must default to unconstrained, or the " +
                "fitted engine's natural speed is silently capped"
        }
    }
}
