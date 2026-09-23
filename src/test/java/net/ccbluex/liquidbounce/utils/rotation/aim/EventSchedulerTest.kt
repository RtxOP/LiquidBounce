package net.ccbluex.liquidbounce.utils.rotation.aim

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Stage-5 unit contract (spec §11.7.1): seeded geometric dwell draws
 * reproduce the fitted mean (±3%) and p90 (±2 ticks), EventRateScale maps
 * to p_eff = clamp(p·s, 0.01, 0.95) and shifts the draw mean accordingly,
 * a fixed seed is byte-deterministic (§11.7.5), and I2 preempt clears an
 * active dwell the same tick.
 */
class EventSchedulerTest {

    private fun draws(n: Int, scale: Float = 1f, seed: Int = 42): IntArray {
        val rng = Random(seed)
        return IntArray(n) { EventScheduler.drawDwell(rng, scale) }
    }

    @Test
    fun drawMeanAndP90MatchFitted() {
        val ds = draws(50_000).sorted()
        val mean = ds.average()
        val p90 = ds[(ds.size * 0.9).toInt()]
        val fittedMean = FittedAimParams.SCHED_DWELL_MEAN.toDouble()
        val fittedP90 = FittedAimParams.SCHED_DWELL_P90
        assertTrue(abs(mean - fittedMean) <= maxOf(0.1, fittedMean * 0.03)) {
            "draw mean $mean drifted from fitted $fittedMean"
        }
        assertTrue(abs(p90 - fittedP90) <= 2.0) {
            "draw p90 $p90 drifted from fitted $fittedP90"
        }
        assertTrue(ds.first() >= 1) { "dwell support must be {1, 2, ...}" }
    }

    @Test
    fun eventRateScaleShiftsDwellMean() {
        val base = FittedAimParams.SCHED_DWELL_MEAN
        val fast = draws(30_000, scale = 2f).average()
        val slow = draws(30_000, scale = 0.5f).average()
        assertTrue(fast < base * 0.7) {
            "p_eff up must shorten dwells, got mean $fast"
        }
        assertTrue(slow > base * 1.4) {
            "p_eff down must lengthen dwells, got mean $slow"
        }
        assertTrue(EventScheduler.pEff(0f) == 0.01f) {
            "p_eff must clamp at the 0.01 floor"
        }
        assertTrue(EventScheduler.pEff(100f) == 0.95f) {
            "p_eff must clamp at the 0.95 ceiling"
        }
    }

    @Test
    fun seededDrawsAreDeterministicAndPreemptClears() {
        assertTrue(draws(100, seed = 7).contentEquals(draws(100, seed = 7))) {
            "same seed must draw identically"
        }
        assertTrue(!draws(100, seed = 7).contentEquals(draws(100, seed = 8))) {
            "different seeds must diverge"
        }
        val st = EventScheduler.State()
        EventScheduler.begin(st, Random(1), eventRateScale = 1f)
        assertTrue(st.dwellRemaining >= 1) { "begin must store a live dwell" }
        EventScheduler.preempt(st)
        assertTrue(st.dwellRemaining == 0) { "I2 preempt must clear the dwell same tick" }
    }
}
