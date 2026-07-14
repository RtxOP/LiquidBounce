/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.clickmodes

import java.util.Random as JdkRandom
import kotlin.random.Random

/**
 * Gaussian-distributed inter-click timing. Each iteration samples a fresh
 * inter-click interval from a band-picked Gaussian, advances a "time"
 * accumulator (in 1/20ths-of-a-second ticks), and emits a click at the
 * current tick slot until the accumulator rolls past 20 (one second).
 *
 * The two frequency bands — `(0, mean=87.88ms, std=13.42ms)` and
 * `(10/110, mean=179.52ms, std=20.42ms)` — partition the uniformly-sampled
 * threshold the algorithm draws at the top of each iteration. This biases
 * most intervals into a fast-click band with occasional cooldowns, matching
 * "human-but-bursty" click distributions.
 *
 * Ported from nextgen's
 * `src/main/kotlin/.../utils/clicking/pattern/patterns/NormalDistributionPattern.kt`.
 *
 * nextgen used `RNG.nextGaussian(mean, std)` — kotlin's stdlib `nextGaussian()`
 * is parameterless (standard normal), so we apply the affine transform
 * `mean + std * nextGaussian()` here.
 */
class NormalDistribution : ClickMode("NormalDistribution") {

    private val patternLength = 20
    private val cycle = IntArray(patternLength)
    private var cycleCursor = 0
    private var cycleUpdateTime = 0L
    private var cachedClicks = 0

    private data class Band(val top: Double, val mean: Double, val std: Double)

    private val frequencyBands = arrayOf(
        Band(top = 10.0 / 110.0, mean = 179.5242718446602, std = 20.416937885616676),
        Band(top = 0.0, mean = 87.88, std = 13.420088130563776)
    )

    /**
     * `kotlin.random.Random` keeps the door closed on `nextGaussian()` even
     * though the JVM default wraps `java.util.Random` underneath — the
     * compiler resolves through the abstract Kotlin type, not the Java one.
     * Hold a JDK `java.util.Random` directly so we can call its inherited
     * Gaussian sampler.
     */
    private val gaussian = JdkRandom()

    override fun cacheClick(active: Boolean, cps: IntRange) {
        if (!active) {
            cachedClicks = 0
            return
        }

        val now = System.currentTimeMillis()
        if (cycleCursor == 0 || now - cycleUpdateTime >= 1000L) {
            refillCycle()
            cycleUpdateTime = now
        }

        val pending = cycle[cycleCursor]
        cachedClicks = pending
        if (pending > 0) cycle[cycleCursor] = 0
        cycleCursor = (cycleCursor + 1) % patternLength
    }

    override fun consumeClicks(): Int {
        val clicks = cachedClicks
        cachedClicks = 0
        return clicks
    }

    override fun reset() {
        cycle.fill(0)
        cycleCursor = 0
        cycleUpdateTime = 0L
        cachedClicks = 0
    }

    private fun refillCycle() {
        cycle.fill(0)
        cycleCursor = 0

        /**
         * `t` is the running inter-click time accumulator in 1/20-second
         * tick units; the * 20.0 / 1000.0 multiplier on each sample
         * converts milliseconds to ticks. We stop when we run past 20 ticks
         * (one full second).
         */
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
