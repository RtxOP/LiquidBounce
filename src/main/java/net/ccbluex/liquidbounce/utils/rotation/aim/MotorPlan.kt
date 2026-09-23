package net.ccbluex.liquidbounce.utils.rotation.aim

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

/**
 * Stage 2 — motor plan (spec §11.2):
 *  - duration power law  `T = PLAN_DURATION_C_T · (A + gcd)^PLAN_DURATION_EXP_B`
 *    (fitted log-log OLS on the human corpus), divided by the SpeedScale knob;
 *  - speed profile `v(u) = PROFILE[bin(u)] · rateScale` where PROFILE is the
 *    fitted **absolute** human speed profile (`fitted.epochs.profile_8bin`,
 *    mean ≈ motion-conditional mean speed, peak ≈ human peak p50) and
 *    `rateScale = (A/T) / PLAN_SPEED_NORM` makes the p50 epoch reproduce the
 *    profile exactly (rateScale = 1 at the corpus 9.12° / 9-tick anchor);
 *  - acceleration bound derived from the profile peak per bin — see
 *    [maxAccel].
 *
 * Mid-epoch replanning happens in [AimEngine] (expiry or error growth);
 * velocity continuity across replans is preserved by the servo's state.
 */
internal object MotorPlan {

    /** One planned motion epoch. */
    data class Plan(val amplitude: Float, val ticks: Int, val flickAmp: Float) {
        /**
         * Profile → absolute speed scaling (1.0 at corpus p50): the median
         * duration-law rate, **floored by the flick envelope**. The flick
         * floor is evaluated on `flickAmp` — the reference's displacement
         * within its current motion run, i.e. the human-equivalent epoch
         * amplitude the OLS was fitted on (the trailing error always lags
         * the full displacement and would under-shoot peaks 1.4x). The
         * r = 0.565 duration law under-predicts re-acquisition peaks 2–3x;
         * lag-1's numerator is 64% chained big pairs, and large run
         * amplitudes only arise when the reference JUMPS (human flicks).
         */
        val rateScale: Float = maxOf(
            (amplitude / ticks) / FittedAimParams.PLAN_SPEED_NORM,
            flickPeak(flickAmp) / PEAK,
        )
    }

    /** Flick envelope: fitted OLS peak speed (deg/tick) vs net amplitude (A ≥ 15). */
    private fun flickPeak(a: Float): Float =
        (FittedAimParams.PLAN_FLICK_BASE + FittedAimParams.PLAN_FLICK_SLOPE * a)
            .coerceAtLeast(0f)

    private val PROFILE = FittedAimParams.PLAN_PROFILE_8BIN
    private const val BINS = 8
    private val PEAK = PROFILE.fold(-Float.MAX_VALUE) { a, b -> max(a, b) }
    private const val MIN_SCALE = 0.05f // knobs are range-guarded; belt & braces

    fun create(amplitude: Float, gcd: Float, speedScale: Float, flickAmp: Float): Plan {
        val a = max(amplitude, gcd)
        val t = FittedAimParams.PLAN_DURATION_C_T *
            (a + gcd).pow(FittedAimParams.PLAN_DURATION_EXP_B) /
            max(speedScale, MIN_SCALE)
        return Plan(a, max(1f, round(t)).toInt(), max(flickAmp, 0f))
    }

    /** Profile bin for [age] within `[0, ticks)` (bin centers at (i+0.5)/8). */
    fun bin(plan: Plan, age: Int): Int {
        val u = (age + 0.5f) / plan.ticks
        val b = (u * BINS).toInt()
        return b.coerceIn(0, BINS - 1)
    }

    /** Target speed magnitude (deg/tick) at this phase of the plan. */
    fun speedAt(plan: Plan, age: Int): Float = PROFILE[bin(plan, age)] * plan.rateScale

    /**
     * Per-tick acceleration bound: profile peak span per bin, floored so a
     * plan can always reach its target speed within [RAMP_TICKS] — and so
     * the servo can acquire the reference's current velocity within
     * [REF_RAMP_TICKS] (feedforward, §11.2-A: human flicks ramp their own
     * velocity ~20–40 deg/tick², and the FF term is useless if accel
     * cannot track it). Steady tracking barely touches this bound — the
     * target itself evolves only ± one bin delta per bin; it exists to
     * bound cold starts after holds, forced brakes, and plan-boundary
     * target jumps — the jerk-limiting half of spec §11.2.
     */
    fun maxAccel(plan: Plan, refSpeed: Float): Float {
        val peak = PEAK * plan.rateScale
        return maxOf(peak * BINS / plan.ticks, peak / RAMP_TICKS, refSpeed / REF_RAMP_TICKS)
    }

    /** Largest speed gain per tick toward a fresh plan target. */
    private const val RAMP_TICKS = 3f

    /** Ticks in which the servo may acquire the reference's current velocity. */
    private const val REF_RAMP_TICKS = 2f
}
