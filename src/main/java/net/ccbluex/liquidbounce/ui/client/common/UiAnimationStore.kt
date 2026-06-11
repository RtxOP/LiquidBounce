/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.ui.client.common

import kotlin.math.abs
import kotlin.math.exp

class UiAnimationStore {
    private data class AnimatedFloat(
        var value: Float,
        var lastUpdateNanos: Long,
        var touchedFrame: Long
    )

    private val floats = mutableMapOf<String, AnimatedFloat>()
    private var frame = 0L

    fun beginFrame() {
        frame++
    }

    fun float(key: String, target: Float, speed: Float, animated: Boolean = true): Float {
        val now = System.nanoTime()
        val entry = floats.getOrPut(key) {
            AnimatedFloat(target, now, frame)
        }

        entry.touchedFrame = frame

        if (!animated || speed <= 0F) {
            entry.value = target
            entry.lastUpdateNanos = now
            return target
        }

        val deltaSeconds = ((now - entry.lastUpdateNanos) / 1_000_000_000F).coerceIn(0F, 0.05F)
        val factor = (1F - exp((-speed * deltaSeconds).toDouble()).toFloat()).coerceIn(0F, 1F)

        entry.value += (target - entry.value) * factor
        entry.lastUpdateNanos = now

        if (abs(entry.value - target) < 0.001F) {
            entry.value = target
        }

        return entry.value
    }

    fun snap(key: String, value: Float) {
        floats[key] = AnimatedFloat(value, System.nanoTime(), frame)
    }

    fun prune(maxUntouchedFrames: Long = 8L) {
        floats.entries.removeIf { frame - it.value.touchedFrame > maxUntouchedFrames }
    }
}
