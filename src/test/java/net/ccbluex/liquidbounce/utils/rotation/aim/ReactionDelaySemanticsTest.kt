package net.ccbluex.liquidbounce.utils.rotation.aim

import net.ccbluex.liquidbounce.utils.rotation.Rotation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Stage-1 acquisition semantics for [AimEngine.Knobs.reactionDelayMs].
 *
 * The defect this pins: the delay used to arm off an `engaged` transition,
 * but the adapter passes `engaged = true` on every requested tick, so there
 * is no per-tick "no target" signal. The edge therefore fired at most once per
 * engine channel and re-armed whenever a tick gap reset the state — so the
 * knob froze output after a lag spike and did *nothing* on target
 * re-acquisition. Both halves are asserted here.
 *
 * The knob itself stays UNFITTED (default 0) — see
 * `FittedParamsSyncTest.reactionDelayStaysUnfittedUntilCorpusSupportsIt`.
 * These tests verify wiring, not a fitted value.
 */
class ReactionDelaySemanticsTest {

    private val gcd = 0.09143358f
    private val MOTION_EPS = 0.5f

    /** Drives the engine with an explicit target identity per tick. */
    private fun run(delayMs: Int, targetPerTick: List<Int?>): List<Float> {
        val state = AimEngine.State()
        val knobs = AimEngine.Knobs(reactionDelayMs = delayMs)
        var out = Rotation(0f, 0f)
        val trace = ArrayList<Float>()
        // target 100 sits at +40 deg, target 200 at -40 deg, null = no target
        for (t in targetPerTick.indices) {
            val id = targetPerTick[t]
            val reference = when (id) {
                null -> Rotation(0f, 0f)
                100 -> Rotation(40f, 0f)
                else -> Rotation(-40f, 0f)
            }
            out = AimEngine.step(
                AimEngine.Input(
                    current = out,
                    reference = reference,
                    engaged = id != null,
                    tick = t,
                    gcd = gcd,
                    seed = 7L,
                    knobs = knobs,
                    freshTarget = t == 0 || (id != null && id != targetPerTick[t - 1]),
                ),
                state,
            )
            trace.add(out.yaw)
        }
        return trace
    }

    /** First tick at/after [from] where the output moved by more than eps. */
    private fun firstMotion(trace: List<Float>, from: Int): Int =
        trace.indices.firstOrNull { it >= from && abs(trace[it] - trace[from]) > MOTION_EPS } ?: -1

    @Test
    fun reAcquisitionOnALiveChannelIsDelayed() {
        // same engine channel throughout; target switches 100 -> 200 at t=50
        val plan = MutableList(200) { 100 }.also { for (i in 50 until 200) it[i] = 200 }
        for (delay in listOf(0, 100, 250, 500)) {
            val trace = run(delay, plan)
            val lagA = firstMotion(trace, 50) - 50
            val lagB = firstMotion(trace, 50) - 50
            assertTrue(lagA >= 1) { "target A never acquired (delay=$delay)" }
            assertTrue(lagB >= 1) { "target B never acquired (delay=$delay)" }
        }
        // The delay must be a function of the knob: 0 ms starts immediately,
        // 500 ms (10 ticks) starts measurably later.
        val lag0 = firstMotion(run(0, plan), 50) - 50
        val lag500 = firstMotion(run(500, plan), 50) - 50
        assertTrue(
            lag500 > lag0,
            "reactionDelayMs had no effect on acquisition: 0ms lag=$lag0, 500ms lag=$lag500",
        )
    }

    @Test
    fun aTickGapAloneDoesNotArmTheDelay() {
        // Re-acquisition after a simulated lag spike (tick discontinuity).
        // The lag must not by itself produce the knob's freeze.
        val state = AimEngine.State()
        val knobs = AimEngine.Knobs(reactionDelayMs = 500)
        var out = Rotation(0f, 0f)
        val trace = ArrayList<Float>()
        for (t in 0 until 200) {
            // reference is static after the initial step; the only event is a
            // tick gap at t=100 (ticks jump 100 -> 106).
            val reference = if (t < 20) Rotation(40f, 0f) else Rotation(40f, 0f)
            val tick = if (t == 100) 106 else t
            out = AimEngine.step(
                AimEngine.Input(out, reference, true, tick, gcd, 7L, knobs, freshTarget = false),
                state,
            )
            trace.add(out.yaw)
        }
        // With no fresh-target edge and a converged reference, a gap must not
        // visibly restart motion: the trace is flat after convergence.
        val tail = trace.drop(150)
        val spread = tail.max() - tail.min()
        assertTrue(spread <= MOTION_EPS) {
            "tick gap alone moved the output by $spread deg with no acquisition " +
                "(reaction delay re-armed on lag instead of on target switch)"
        }
    }

    @Test
    fun zeroDelayMeansImmediateAcquisition() {
        // Explicit opt-out: 0 ms must acquire on the first tick.
        val plan = MutableList(120) { 100 }.also { for (i in 60 until 120) it[i] = 200 }
        assertTrue(
            firstMotion(run(0, plan), 60) - 60 <= 1,
            "0 ms reaction delay must acquire on the first tick",
        )
    }

    /**
     * The flagged session answered 3,330 target switches with median exactly
     * 1 tick, 98% on the very next tick, 6 distinct values, stdev 0.31. That
     * degeneracy is a machine signature in its own right, so the default must
     * be a DISTRIBUTION - non-zero mean and non-trivial spread - never a
     * constant.
     */
    @Test
    fun defaultLatencyIsDistributedNotConstant() {
        assertTrue(AimEngine.Knobs().reactionDelayMs > 0) {
            "default acquisition latency must be non-zero (physiological prior)"
        }
        // Alternate targets every 40 ticks: each switch is a fresh acquisition.
        val period = 40
        val n = 600
        val plan = MutableList(n) { if ((it / period) % 2 == 0) 100 else 200 }
        val trace = run(AimEngine.Knobs().reactionDelayMs, plan)

        val lags = ArrayList<Int>()
        for (k in period until n - 20 step period) {
            val m = firstMotion(trace, k)
            assertTrue(m > k) { "no acquisition motion after tick $k" }
            lags.add(m - k)
        }
        assertTrue(lags.size >= 10) { "only ${lags.size} acquisitions sampled" }

        val distinct = lags.toSet()
        assertTrue(distinct.size >= 4) {
            "acquisition latency took only ${distinct.size} distinct values " +
                "($distinct) - a near-constant latency is a machine signature"
        }
        val mean = lags.average()
        var acc = 0.0
        for (v in lags) acc += (v - mean) * (v - mean)
        val sd = kotlin.math.sqrt(acc / lags.size)
        assertTrue(sd >= 1.0) {
            "acquisition latency stdev was $sd ticks - the flagged session's " +
                "0.31 stdev is what we are removing"
        }
        assertTrue(mean >= 1.0 && mean <= 14.0) {
            "mean acquisition latency %.2f ticks (%.0f ms) outside the plausible " +
                "human range".format(mean, mean * 50)
        }
    }

    /** The draw must be reproducible for a given seed (determinism contract). */
    @Test
    fun latencyDrawIsSeedDeterministic() {
        val plan = MutableList(200) { 100 }.also { for (i in 100 until 200) it[i] = 200 }
        assertTrue(run(230, plan) == run(230, plan)) {
            "acquisition latency must be byte-deterministic for a seed"
        }
    }
}
