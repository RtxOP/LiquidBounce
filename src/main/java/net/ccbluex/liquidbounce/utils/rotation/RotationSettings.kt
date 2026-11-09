/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation

import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.config.Configurable
import net.ccbluex.liquidbounce.config.ListValue
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.utils.extensions.withGCD

// TODO: refactor them all

class AlwaysRotationSettings(owner: Module, generalApply: () -> Boolean = { true }) :
    RotationSettings(owner, generalApply) {
    override val rotationsValue = super.rotationsValue.apply { excludeWithState(true) }
    override val rotationsActive: Boolean = true
}

@Suppress("MemberVisibilityCanBePrivate")
open class RotationSettings(val ownerModule: Module, generalApply: () -> Boolean = { true }) : Configurable("RotationSettings") {

    open val rotationsValue = boolean("Rotations", true) { generalApply() }
    open val applyServerSideValue = boolean("ApplyServerSide", true) { rotationsActive && generalApply() }
    // DEPRECATED NO-OPS (§11.5), superseded by the scheduler's fitted dwells.
    // Registered so old configs load, but EXCLUDED from the GUI: a visible
    // slider that provably cannot change the output is worse than no slider,
    // because the user tunes it and observes nothing. `HumanizeReactionDelay`
    // was the sharpest case - it sits directly above `ReactionDelayMs` with a
    // 0..10 scale against 0..600, both live, and only the latter is read.
    open val simulateShortStopValue =
        boolean("SimulateShortStop", false) { rotationsActive && generalApply() }
            .apply { excludeWithState() }
    open val rotationDiffBuildUpToStopValue =
        float("RotationDiffBuildUpToStop", 180f, 50f..720f) { simulateShortStop }
            .apply { excludeWithState() }
    open val maxThresholdAttemptsToStopValue =
        int("MaxThresholdAttemptsToStop", 1, 0..5) { simulateShortStop }
            .apply { excludeWithState() }
    open val shortStopDurationValue =
        intRange("ShortStopDuration", 1..2, 1..5) { simulateShortStop }
            .apply { excludeWithState() }
    open val strafeValue = boolean("Strafe", false) { rotationsActive && applyServerSide && generalApply() }
    open val strictValue = boolean("Strict", false) { strafeValue.isActive() && generalApply() }
    open val keepRotationValue = boolean("KeepRotation", true) { rotationsActive && applyServerSide && generalApply() }

    open val resetTicksValue = int("ResetTicks", 1, 1..20) {
        rotationsActive && applyServerSide && generalApply()
    }

    open val humanizeValue = boolean("Humanize", false) { rotationsActive && generalApply() }

    // Deprecated no-ops: the 15 raw knobs §11.5 replaced with four fitted
    // ones. Registered for config compat, excluded from the GUI for the
    // reason above. `humanizeReactionDelay` is the dangerous one — see the
    // SimulateShortStop block.
    open val humanizeTremorValue =
        float("HumanizeTremor", 0.08f, 0f..0.3f) { humanize && rotationsActive && generalApply() }
            .apply { excludeWithState() }
    open val humanizeOvershootChanceValue =
        float("HumanizeOvershootChance", 15f, 0f..100f) { humanize && rotationsActive && generalApply() }
            .apply { excludeWithState() }
    open val humanizeOvershootMagnitudeValue =
        float("HumanizeOvershootMagnitude", 1.5f, 0.1f..3f) { humanize && rotationsActive && generalApply() }
            .apply { excludeWithState() }
    open val humanizeCurvatureValue =
        float("HumanizeCurvature", 0.07f, 0f..0.2f) { humanize && rotationsActive && generalApply() }
            .apply { excludeWithState() }
    open val humanizeReactionDelayValue =
        int("HumanizeReactionDelay", 3, 0..10) { humanize && rotationsActive && generalApply() }
            .apply { excludeWithState() }

    // The engine knobs (spec §11.3) — the frozen tuning budget. Every knob
    // exposed here MUST be observable in the emitted rotation.
    // eventRateScaleValue is a DEPRECATED NO-OP, kept registered so saved
    // configs load (§11.5): stage 5's dwell cannot gate motion under I1/I2,
    // so the knob never changed the output. See AimEngine.Knobs.
    // The humanize knobs above are deprecated no-ops for the same reason.
    open val speedScaleValue = float("SpeedScale", 1f, 0.25f..4f) {
        rotationsActive && humanize && generalApply()
    }
    open val noiseScaleValue = float("NoiseScale", 1f, 0f..3f) {
        rotationsActive && humanize && generalApply()
    }
    open val eventRateScaleValue = float("EventRateScale", 1f, 0.25f..4f) {
        rotationsActive && humanize && generalApply()
    }.apply { excludeWithState() }
    // Default is the engine's physiological prior (AimEngine), NOT 0: the
    // flagged session answered 3,330 target switches with a median of exactly
    // 1 tick and stdev 0.31, and a zero-latency acquisition is the single most
    // separable signal in the recording. There is no corpus fit to ship (see
    // human_features.reaction_delay_report), so this is a documented prior;
    // set 0 to disable the delay entirely.
    open val reactionDelayMsValue = int(
        "ReactionDelayMs",
        net.ccbluex.liquidbounce.utils.rotation.aim.AimEngine.REACTION_PRIOR_MEAN_MS,
        0..600,
    ) {
        rotationsActive && humanize && generalApply()
    }

    open val horizontalAngleChangeValue =
        // Default 180 = unconstrained: the humanize path is fitted and its
        // natural speed is p50 1.4 / p99 26.4 deg/tick, so the old 10..20
        // default only acted as a hard ceiling once the engine honoured it.
        floatRange("HorizontalAngleChange", 180f..180f, 1f..180f) { rotationsActive && generalApply() }
    open val verticalAngleChangeValue =
        floatRange("VerticalAngleChange", 180f..180f, 1f..180f) { rotationsActive && generalApply() }

    open val angleResetDifferenceValue = float("AngleResetDifference", 5f.withGCD(), 0.0f..180f) {
        rotationsActive && applyServerSide && generalApply()
    }

    open val minRotationDifferenceValue = float(
        "MinRotationDifference", 2f, 0f..4f
    ) { rotationsActive && generalApply() }

    open val minRotationDifferenceResetTimingValue = choices(
        "MinRotationDifferenceResetTiming", arrayOf("OnStart", "OnSlowDown", "Always"), "OnStart"
    ) { rotationsActive && generalApply() }

    // Variables for easier access
    val rotations by rotationsValue
    val applyServerSide by applyServerSideValue
    val simulateShortStop by simulateShortStopValue
    val rotationDiffBuildUpToStop by rotationDiffBuildUpToStopValue
    val maxThresholdAttemptsToStop by maxThresholdAttemptsToStopValue
    val shortStopDuration by shortStopDurationValue
    val strafe by strafeValue
    val strict by strictValue
    val keepRotation by keepRotationValue
    val resetTicks by resetTicksValue
    val humanize by humanizeValue
    val humanizeTremor by humanizeTremorValue
    val humanizeOvershootChance by humanizeOvershootChanceValue
    val humanizeOvershootMagnitude by humanizeOvershootMagnitudeValue
    val humanizeCurvature by humanizeCurvatureValue
    val humanizeReactionDelay by humanizeReactionDelayValue
    val speedScale by speedScaleValue
    val noiseScale by noiseScaleValue
    val eventRateScale by eventRateScaleValue
    val reactionDelayMs by reactionDelayMsValue

    /**
     * The engine knobs in engine form (§11.1 adapter). `eventRateScale` is
     * deliberately absent: it is a deprecated no-op (see the value above).
     */
    internal fun engineKnobs() = net.ccbluex.liquidbounce.utils.rotation.aim.AimEngine.Knobs(
        speedScale = speedScale,
        noiseScale = noiseScale,
        reactionDelayMs = reactionDelayMs,
        // The configured end of each range is the ceiling: these sliders were
        // previously ignored on the humanize path even though the recorder
        // enforced them, so a session configured at h_max = 20 emitted up to
        // 107.5 deg/tick and self-reported verdict FAIL.
        maxYawStep = horizontalAngleChange.endInclusive,
        maxPitchStep = verticalAngleChange.endInclusive,
    )

    val horizontalAngleChange by horizontalAngleChangeValue
    val verticalAngleChange by verticalAngleChangeValue
    val angleResetDifference by angleResetDifferenceValue
    val minRotationDifference by minRotationDifferenceValue
    val minRotationDifferenceResetTiming by minRotationDifferenceResetTimingValue

    var prioritizeRequest = false
    var immediate = false
    var instant = false

    open val rotationsActive
        get() = rotations

    fun withoutKeepRotation() = apply {
        keepRotationValue.excludeWithState()
    }

    /**
     * Shaping-relevant snapshot for [net.ccbluex.liquidbounce.features.module.modules.misc.RotationRecorder].
     * Only the values that change rotation output. The owning module name comes from [owner].
     */
    fun snapshot(): JsonObject = JsonObject().apply {
        addProperty("humanize", humanize)
        addProperty("tremor", humanizeTremor)
        addProperty("overshoot_chance", humanizeOvershootChance)
        addProperty("overshoot_magnitude", humanizeOvershootMagnitude)
        addProperty("curvature", humanizeCurvature)
        addProperty("reaction_delay", humanizeReactionDelay)
        addProperty("speed_scale", speedScale)
        addProperty("noise_scale", noiseScale)
        // Deprecated no-op: recorded for forensics only, never read by the engine.
        addProperty("event_rate_scale", eventRateScale)
        addProperty("reaction_delay_ms", reactionDelayMs)
        addProperty("h_min", horizontalAngleChange.start)
        addProperty("h_max", horizontalAngleChange.endInclusive)
        addProperty("v_min", verticalAngleChange.start)
        addProperty("v_max", verticalAngleChange.endInclusive)
        addProperty("min_rotation_difference", minRotationDifference)
        addProperty("min_rotation_difference_reset_timing", minRotationDifferenceResetTiming)
        addProperty("simulate_short_stop", simulateShortStop)
        addProperty("short_stop_duration", shortStopDuration.toString())
        addProperty("keep_rotation", keepRotation)
        addProperty("apply_server_side", applyServerSide)
        addProperty("reset_ticks", resetTicks)
        addProperty("angle_reset_difference", angleResetDifference)
    }

    init {
        ownerModule.addValues(this.values)
    }
}

class RotationSettingsWithRotationModes(
    owner: Module, listValue: ListValue, generalApply: () -> Boolean = { true },
) : RotationSettings(owner, generalApply) {

    override val rotationsValue = super.rotationsValue.apply { excludeWithState() }

    val rotationModeValue = listValue.setSupport { generalApply() }

    val rotationMode by +rotationModeValue

    override val rotationsActive: Boolean
        get() = rotationMode != "Off"
}
