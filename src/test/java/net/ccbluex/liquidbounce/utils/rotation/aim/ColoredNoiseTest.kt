package net.ccbluex.liquidbounce.utils.rotation.aim

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Stage-4 unit contract (spec §11.7.1): over long draws the AR(1) output
 * keeps its stationary sigma (within 5%) and lag-1 phi (within 0.03), the
 * sigma(speed) lookup walks the fitted sub-bins (signal-dependent noise:
 * sigma decays toward rest so dwells stay exact and run-closing ticks keep
 * their sign, §11.2-B), and a fixed seed is byte-deterministic (§11.7.5).
 */
class ColoredNoiseTest {

    private fun drawYaw(speed: Float, seed: Int = 42, n: Int = 200_000): FloatArray {
        val state = ColoredNoise.State()
        val rng = Random(seed)
        val out = FloatArray(n)
        for (i in 0 until n) {
            ColoredNoise.advance(state, rng, speed)
            out[i] = state.yaw
        }
        return out
    }

    /** Independent index walk: first edge strictly greater than the speed. */
    private fun idx(speed: Float): Int {
        val edges = FittedAimParams.NOISE_BIN_MAX_DEG
        var i = 0
        while (i < edges.size - 1 && speed >= edges[i]) i++
        return i
    }

    @Test
    fun stationarySigmaAndPhiHoldInSlowBin() {
        val speed = 0.05f // inside the first fitted bin (near-rest)
        val ys = drawYaw(speed = speed)
        val target = ColoredNoise.sigmaFor(speed, yaw = true)
        val phi = FittedAimParams.NOISE_PHI_YAW
        assertTrue(target > 0f) { "near-rest sigma must stay positive" }
        val m = ys.sum() / ys.size
        var den = 0f
        var num = 0f
        for (i in ys.indices) {
            val d = ys[i] - m
            den += d * d
            if (i > 0) num += d * (ys[i - 1] - m)
        }
        val sd = sqrt(den / ys.size)
        assertTrue(abs(sd - target) <= target * 0.05f) {
            "stationary sigma drifted: $sd vs fitted $target"
        }
        val acf = num / den
        assertTrue(abs(acf - phi) <= 0.03f) {
            "lag-1 phi drifted: $acf vs fitted $phi"
        }
    }

    @Test
    fun sigmaLookupWalksFittedBins() {
        val edges = FittedAimParams.NOISE_BIN_MAX_DEG
        assertTrue(edges.size >= 4) { "expected multiple speed bins from the refit" }
        for (speed in floatArrayOf(0.05f, 0.3f, 0.7f, 1.2f, 3f, 7f, 40f, 1e6f)) {
            val i = idx(speed)
            assertTrue(ColoredNoise.sigmaFor(speed, yaw = true) ==
                FittedAimParams.NOISE_BIN_SIGMA_YAW_DEG[i]) {
                "yaw lookup must read fitted bin $i at speed $speed"
            }
            assertTrue(ColoredNoise.sigmaFor(speed, yaw = false) ==
                FittedAimParams.NOISE_BIN_SIGMA_PITCH_DEG[i]) {
                "pitch lookup must read fitted bin $i at speed $speed"
            }
        }
        // near-rest sigma must be below the fast bin (signal-dependent noise:
        // dwells exact, tail signs protected, §11.2-B)
        assertTrue(
            FittedAimParams.NOISE_BIN_SIGMA_YAW_DEG[0] <
                FittedAimParams.NOISE_BIN_SIGMA_YAW_DEG.last(),
        ) { "sigma must grow with speed" }
    }

    @Test
    fun fixedSeedIsDeterministic() {
        val a = drawYaw(1f, seed = 7, n = 100)
        val b = drawYaw(1f, seed = 7, n = 100)
        assertTrue(a.contentEquals(b)) { "same seed must draw identically" }
        val c = drawYaw(1f, seed = 8, n = 100)
        assertTrue(!a.contentEquals(c)) { "different seeds must diverge" }
    }
}
