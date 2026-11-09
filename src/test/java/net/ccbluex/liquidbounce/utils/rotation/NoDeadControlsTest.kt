package net.ccbluex.liquidbounce.utils.rotation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No dead control may be user-visible.
 *
 * `HumanizeReactionDelay` (0..10) sat directly above `ReactionDelayMs`
 * (0..600), both rendered, both gated on `humanize` — and only the second is
 * read. Ten sliders in total were in that state: the §11.5 deprecated set plus
 * `EventRateScale`, which this branch proved inert by replaying the corpus at
 * 0.25 and 4.0 and getting byte-identical output.
 *
 * A visible control that cannot change behaviour is not a cosmetic problem: the
 * user tunes it, observes nothing, and concludes the humanizer is broken.
 * Registering a value so old configs still load is necessary; RENDERING it is
 * not. `Value.excludeWithState()` is the codebase's existing idiom for that
 * (`resetTicksValue`, `strictValue`, `rotationsValue` all use it).
 *
 * This is a source-level scan rather than a runtime assertion because building
 * a `RotationSettings` needs a `Module`, which is `MinecraftInstance` — and the
 * aim package's purity rule (§11.1) plus this test's own package make that an
 * MC dependency we do not want in a headless suite.
 */
class NoDeadControlsTest {

    /** Values the engine actually reads, via engineKnobs() and the fast path. */
    private val live = setOf(
        // adapter / lifecycle
        "Rotations", "ApplyServerSide", "Strafe", "Strict", "KeepRotation",
        "ResetTicks", "AngleResetDifference",
        // humanize gate + the four engine knobs
        "Humanize", "SpeedScale", "NoiseScale", "ReactionDelayMs",
        // shaping ranges (now the per-axis ceiling on the humanize path)
        "HorizontalAngleChange", "VerticalAngleChange",
        // legacy non-humanize path
        "MinRotationDifference", "MinRotationDifferenceResetTiming",
    )

    private val mustBeHidden = setOf(
        // §11.5 deprecated: superseded by the scheduler's fitted dwells
        "SimulateShortStop", "RotationDiffBuildUpToStop",
        "MaxThresholdAttemptsToStop", "ShortStopDuration",
        // §11.5 deprecated: the 15 raw knobs replaced by four fitted ones
        "HumanizeTremor", "HumanizeOvershootChance",
        "HumanizeOvershootMagnitude", "HumanizeCurvature",
        "HumanizeReactionDelay",
        // proven inert this branch (KnobsObservabilityTest)
        "EventRateScale",
    )

    private fun source(): String {
        val f = File("src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationSettings.kt")
        assertTrue(f.exists()) { "RotationSettings.kt not found - scan skipped" }
        return f.readText()
    }

    @Test
    fun everyDeclaredValueIsAccountedFor() {
        val src = source()
        val declared = Regex("""(?:open val|val)\s+(\w+Value)\s*=\s*(?:boolean|int|float|intRange|floatRange|choices)\(\s*"([^"]+)"""")
            .findAll(src)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertTrue(declared.size >= 20) { "scan found only ${declared.size} values" }

        val byKey = declared.associate { it.second to it.first }
        val unknown = declared.map { it.second }.filter { it !in live && it !in mustBeHidden }
        assertTrue(unknown.isEmpty()) {
            "RotationSettings declares values this test does not classify: $unknown.\n" +
                "Add each to `live` (the engine reads it) or `mustBeHidden` (deprecated)."
        }
        // And the reverse: nothing we claim is dead may be missing.
        val missing = mustBeHidden.filter { it !in byKey }
        assertTrue(missing.isEmpty()) {
            "these values are declared dead but no longer exist: $missing - " +
                "remove them from mustBeHidden"
        }
    }

    @Test
    fun deadValuesAreExcludedFromTheGui() {
        val src = source()
        val problems = mutableListOf<String>()
        for (key in mustBeHidden) {
            val i = src.indexOf("\"$key\"")
            assertTrue(i >= 0) { "\"$key\" not declared in RotationSettings.kt" }
            // The declaration ends at the next top-level `open val`/`val` or the
            // end of the class body; the exclusion must appear inside it.
            val rest = src.substring(i)
            val next = Regex("""\n    (?:open )?val \w+""").find(rest)?.range?.first ?: rest.length
            val block = rest.substring(0, next)
            if (!block.contains("excludeWithState()")) {
                problems += "$key: still rendered in the click GUI (no excludeWithState())"
            }
        }
        assertTrue(problems.isEmpty()) {
            "dead controls must stay hidden from the GUI:\n" + problems.joinToString("\n") +
                "\n\nA visible control that cannot change the output is worse than none."
        }
    }
}
