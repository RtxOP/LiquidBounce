/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import java.util.Random as JdkRandom
import kotlin.random.Random

/**
 * Gaussian-distributed inter-click timing, sampled once per refill.
 *
 * Two frequency bands — `(0, mean=87.88ms, std=13.42ms)` and
 * `(10/110, mean=179.52ms, std=20.42ms)` — split the uniformly-sampled
 * threshold the algorithm draws at the top of each iteration. Most
 * intervals land in the fast band with occasional cooldowns, matching
 * a "human-but-bursty" distribution.
 *
 * Kotlin's `Random.nextGaussian()` is parameterless (standard normal); we
 * apply the affine transform `mean + std * nextGaussian()` and hold a
 * `java.util.Random` directly so we can call its inherited Gaussian sampler.
 */
class NormalDistribution : CycleClickMode("NormalDistribution") {

    private data class Band(val top: Double, val mean: Double, val std: Double)

    private val frequencyBands = arrayOf(
        Band(top = 10.0 / 110.0, mean = 179.5242718446602, std = 20.416937885616676),
        Band(top = 0.0, mean = 87.88, std = 13.420088130563776)
    )

    private val gaussian = JdkRandom()

    override fun refillCycle(clicks: Int) {
        // `t` is the running inter-click time accumulator in 1/20-second
        // tick units; the * 20.0 / 1000.0 multiplier on each sample converts
        // milliseconds to ticks. We stop when we run past 20 ticks (one full
        // second). The click count argument is unused since gaussian draws
        // an unbounded number of ticks until the cycle is filled.
        @Suppress("UNUSED_PARAMETER")
        var t = 0.0
        while (true) {
            val threshold = Random.Default.nextDouble()
            val band = frequencyBands.first { threshold >= it.top }
            t += (band.mean + band.std * gaussian.nextGaussian()) * 20.0 / 1000.0
            if (t > 20.0) break
            cycle[t.toInt()]++
        }
    }
}
