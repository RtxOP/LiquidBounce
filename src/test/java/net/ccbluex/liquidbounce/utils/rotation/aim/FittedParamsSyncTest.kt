package net.ccbluex.liquidbounce.utils.rotation.aim

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Freshness gate for the generated [FittedAimParams] (spec §11.4): re-derives
 * every constant from `tools/human_corpus_features.json` using the same
 * formulas as `tools/gen_fitted_params.py` and compares against the compiled
 * object. A stale `FittedAimParams.kt` (corpus regenerated without rerunning
 * the generator) fails here — acceptance values cannot drift silently.
 *
 * Skips (does not fail) when the untracked corpus tooling is absent, so
 * builds without `tools/` stay green.
 */
class FittedParamsSyncTest {

    private val json: JsonObject by lazy {
        val file = File("tools/human_corpus_features.json")
        assumeTrue(file.exists()) { "fitted JSON absent - sync check skipped" }
        JsonParser().parse(file.readText()).asJsonObject
    }

    private fun JsonObject.d(vararg path: String): Double {
        var cur = this
        for (key in path) cur = cur.getAsJsonObject(key)
        return cur.asDouble
    }

    private fun assertClose(name: String, expected: Double, actual: Float) {
        val delta = maxOf(1e-6, abs(expected) * 1e-5)
        assertTrue(abs(expected - actual) <= delta) {
            "$name drifted: fitted JSON says $expected, FittedAimParams has $actual " +
                "(rerun tools/gen_fitted_params.py)"
        }
    }

    @Test
    fun generatedConstantsMatchCorpusJson() {
        val floor = json.getAsJsonObject("fitted").getAsJsonObject("noise").getAsJsonObject("floor")
        val fitted = json.getAsJsonObject("fitted")
        val epochs = fitted.getAsJsonObject("epochs")
        val pooled = json.getAsJsonObject("human").getAsJsonObject("pooled")

        // stage 4 - noise, incl. derived AR(1) innovation sigma*sqrt(1-phi^2)
        val sigY = floor.get("position_resid_rms_yaw_deg").asDouble
        val sigP = floor.get("position_resid_rms_pitch_deg").asDouble
        val phiY = floor.get("residual_lag1_yaw").asDouble
        val phiP = floor.get("residual_lag1_pitch").asDouble
        assertClose("NOISE_SIGMA_YAW_DEG", sigY, FittedAimParams.NOISE_SIGMA_YAW_DEG)
        assertClose("NOISE_SIGMA_PITCH_DEG", sigP, FittedAimParams.NOISE_SIGMA_PITCH_DEG)
        assertClose("NOISE_PHI_YAW", phiY, FittedAimParams.NOISE_PHI_YAW)
        assertClose("NOISE_PHI_PITCH", phiP, FittedAimParams.NOISE_PHI_PITCH)
        assertClose("NOISE_INNOV_YAW_DEG", sigY * sqrt(1.0 - phiY * phiY), FittedAimParams.NOISE_INNOV_YAW_DEG)
        assertClose("NOISE_INNOV_PITCH_DEG", sigP * sqrt(1.0 - phiP * phiP), FittedAimParams.NOISE_INNOV_PITCH_DEG)
        // sigma(v) refit bins (Phase-3 step 2): NOISE_BIN_* arrays
        val noiseBins = fitted.getAsJsonObject("noise").getAsJsonObject("by_speed").getAsJsonArray("bins")
        assertTrue(
            noiseBins.size() == FittedAimParams.NOISE_BIN_MAX_DEG.size &&
                FittedAimParams.NOISE_BIN_SIGMA_YAW_DEG.size == noiseBins.size() &&
                FittedAimParams.NOISE_BIN_SIGMA_PITCH_DEG.size == noiseBins.size(),
        ) {
            "NOISE_BIN arrays length drifted: json=${noiseBins.size()} " +
                "max=${FittedAimParams.NOISE_BIN_MAX_DEG.size} " +
                "(rerun tools/gen_fitted_params.py)"
        }
        for (i in 0 until noiseBins.size()) {
            val bin = noiseBins[i].asJsonObject
            assertClose("NOISE_BIN_MAX_DEG[$i]", bin.get("max_speed").asDouble, FittedAimParams.NOISE_BIN_MAX_DEG[i])
            assertClose(
                "NOISE_BIN_SIGMA_YAW_DEG[$i]",
                bin.get("sigma_yaw").asDouble,
                FittedAimParams.NOISE_BIN_SIGMA_YAW_DEG[i],
            )
            assertClose(
                "NOISE_BIN_SIGMA_PITCH_DEG[$i]",
                bin.get("sigma_pitch").asDouble,
                FittedAimParams.NOISE_BIN_SIGMA_PITCH_DEG[i],
            )
        }

        // stage 2 - plan, incl. derived anchors
        val b = epochs.getAsJsonObject("duration_vs_amplitude_exponent").get("slope").asDouble
        val tP50 = epochs.getAsJsonObject("duration").get("p50").asDouble
        val aP50 = epochs.getAsJsonObject("amplitude_net").get("p50").asDouble
        val gcd = fitted.getAsJsonObject("mc").get("gcd").asDouble
        assertClose("PLAN_DURATION_EXP_B", b, FittedAimParams.PLAN_DURATION_EXP_B)
        assertClose("PLAN_DURATION_C_T", tP50 / (aP50 + gcd).pow(b), FittedAimParams.PLAN_DURATION_C_T)
        assertClose("PLAN_SPEED_NORM", aP50 / tP50, FittedAimParams.PLAN_SPEED_NORM)
        val profile = epochs.getAsJsonArray("profile_8bin")
        assertTrue(profile.size() == FittedAimParams.PLAN_PROFILE_8BIN.size) {
            "PLAN_PROFILE_8BIN length drifted: ${profile.size()} vs ${FittedAimParams.PLAN_PROFILE_8BIN.size}"
        }
        for (i in 0 until profile.size()) {
            assertClose("PLAN_PROFILE_8BIN[$i]", profile[i].asDouble, FittedAimParams.PLAN_PROFILE_8BIN[i])
        }
        assertClose(
            "PLAN_FITTS_SLOPE",
            epochs.getAsJsonObject("fitts_peak_vs_log2_amp").get("slope").asDouble,
            FittedAimParams.PLAN_FITTS_SLOPE,
        )
        val flick = epochs.getAsJsonObject("peak_vs_amplitude_A15")
        assertClose("PLAN_FLICK_SLOPE", flick.get("slope").asDouble, FittedAimParams.PLAN_FLICK_SLOPE)
        assertClose("PLAN_FLICK_BASE", flick.get("intercept").asDouble, FittedAimParams.PLAN_FLICK_BASE)
        assertClose(
            "PLAN_TAU_PEAK_DIAG",
            epochs.getAsJsonObject("time_to_peak_tau").get("mean").asDouble,
            FittedAimParams.PLAN_TAU_PEAK_DIAG,
        )

        // stage 5 - scheduler
        val dwell = fitted.getAsJsonObject("dwell")
        assertClose("SCHED_DWELL_MEAN", dwell.get("mean_ticks").asDouble, FittedAimParams.SCHED_DWELL_MEAN)
        assertClose("SCHED_DWELL_P90", dwell.get("p90_ticks").asDouble, FittedAimParams.SCHED_DWELL_P90)
        assertClose("SCHED_EPOCH_RATE", epochs.get("rate_per_s").asDouble, FittedAimParams.SCHED_EPOCH_RATE)

        // stage 3 - servo limits
        val speed = pooled.getAsJsonObject("speed")
        assertClose("SERVO_SPEED_P50", speed.get("p50").asDouble, FittedAimParams.SERVO_SPEED_P50)
        assertClose("SERVO_SPEED_P90", speed.get("p90").asDouble, FittedAimParams.SERVO_SPEED_P90)
        assertClose("SERVO_SPEED_P99", speed.get("p99").asDouble, FittedAimParams.SERVO_SPEED_P99)
        // SERVO_SPEED_MAX is an upper BOUND over every sub-corpus' observed
        // maximum (times headroom), NOT the pooled sample max. Phase 3 fitted
        // it to the sample max, which left zero headroom and clipped real
        // player flicks on the target-server corpus.
        val bounds = fitted.getAsJsonObject("servo_speed_bounds")
        assertClose(
            "SERVO_SPEED_OBSERVED_MAX",
            bounds.get("observed_max").asDouble,
            FittedAimParams.SERVO_SPEED_OBSERVED_MAX,
        )
        val headroom = bounds.getAsJsonObject("by_subcorpus")
        for ((_, v) in headroom.entrySet()) {
            val sub = v.asJsonObject.get("max").asDouble
            // Float storage can round the constant a hair below the double the
            // generator saw; allow one float ulp at this magnitude.
            assertTrue(FittedAimParams.SERVO_SPEED_OBSERVED_MAX + 1e-3 >= sub) {
                "SERVO_SPEED_OBSERVED_MAX ${FittedAimParams.SERVO_SPEED_OBSERVED_MAX} " +
                    "must cover sub-corpus max $sub"
            }
        }
        assertTrue(
            FittedAimParams.SERVO_SPEED_MAX >
                FittedAimParams.SERVO_SPEED_OBSERVED_MAX
        ) {
            "SERVO_SPEED_MAX (${FittedAimParams.SERVO_SPEED_MAX}) must sit ABOVE the " +
                "observed corpus max (${FittedAimParams.SERVO_SPEED_OBSERVED_MAX}); " +
                "a backstop at or below it truncates real aim"
        }
        assertClose(
            "SERVO_SPEED_MAX",
            FittedAimParams.SERVO_SPEED_OBSERVED_MAX.toDouble() *
                FittedAimParams.SERVO_SPEED_MAX_HEADROOM.toDouble(),
            FittedAimParams.SERVO_SPEED_MAX,
        )
        assertClose(
            "SERVO_PEAK_P90",
            pooled.getAsJsonObject("epochs").getAsJsonObject("peak_speed").get("p90").asDouble,
            FittedAimParams.SERVO_PEAK_P90,
        )
        val win = fitted.getAsJsonObject("mc").getAsJsonObject("windowed")
        assertClose(
            "SERVO_WIN_STD_P90_5",
            win.getAsJsonObject("5").getAsJsonObject("std").get("p90").asDouble,
            FittedAimParams.SERVO_WIN_STD_P90_5,
        )
        assertClose(
            "SERVO_WIN_STD_P90_10",
            win.getAsJsonObject("10").getAsJsonObject("std").get("p90").asDouble,
            FittedAimParams.SERVO_WIN_STD_P90_10,
        )
        assertClose(
            "SERVO_WIN_STD_P90_20",
            win.getAsJsonObject("20").getAsJsonObject("std").get("p90").asDouble,
            FittedAimParams.SERVO_WIN_STD_P90_20,
        )

        // stage 1 - corrections
        val correction = fitted.getAsJsonObject("correction")
        assertClose(
            "CORRECTION_MICRO_FRAC",
            correction.get("micro_frac_nonzero").asDouble,
            FittedAimParams.CORRECTION_MICRO_FRAC,
        )
        assertClose(
            "CORRECTION_REVERSALS_MEAN",
            correction.getAsJsonObject("reversals_per_epoch").get("mean").asDouble,
            FittedAimParams.CORRECTION_REVERSALS_MEAN,
        )

        // stage 6 - quantizer target
        assertClose(
            "QUANT_TARGET_STEPS",
            pooled.getAsJsonObject("grid_residual_steps").get("p99").asDouble,
            FittedAimParams.QUANT_TARGET_STEPS,
        )

        // acceptance baselines
        assertClose("ACCEPT_LAG1_YAW", pooled.get("lag1_yaw_delta").asDouble, FittedAimParams.ACCEPT_LAG1_YAW)
        assertClose(
            "ACCEPT_LAG1_PITCH",
            pooled.get("lag1_pitch_delta").asDouble,
            FittedAimParams.ACCEPT_LAG1_PITCH,
        )
        assertClose(
            "ACCEPT_MINJERK_R2",
            epochs.get("minjerk_r2").asDouble,
            FittedAimParams.ACCEPT_MINJERK_R2,
        )
        assertClose("ACCEPT_END_FLIP", correction.get("end_flip_rate").asDouble, FittedAimParams.ACCEPT_END_FLIP)
    }

    /**
     * The reaction-delay knob stays unfitted and must not silently acquire a
     * default. `human_features.reaction_delay_report` refuses to fit below
     * MIN_SAMPLES clean rest->acquire transitions; this pins the decision so
     * a future corpus either supplies the samples or keeps the default.
     */
    @Test
    fun reactionDelayStaysUnfittedUntilCorpusSupportsIt() {
        val rd = json.getAsJsonObject("fitted").getAsJsonObject("reaction_delay")
        val restful = rd.get("restful_onsets").asInt
        val required = rd.get("min_samples_required").asInt
        val fittable = rd.get("fittable").asBoolean
        assertTrue(fittable == (restful >= required)) {
            "reaction-delay report is incoherent: restful_onsets=$restful, " +
                "required=$required, fittable=$fittable (fittable must be " +
                "restful_onsets >= required)"
        }
        if (!fittable) {
            assertTrue(restful < required) {
                "reactionDelayMs is declared unfittable with $restful clean onsets " +
                    "(need $required) - rerun human_features.py and gen_fitted_params.py"
            }
        }
    }
}
