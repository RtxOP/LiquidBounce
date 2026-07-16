/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation.humanization

enum class HumanizationMode {
    OFF,
    SUBTLE,
    BALANCED,
    CUSTOM;

    companion object {
        fun fromName(name: String) = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFF
    }
}

/**
 * Immutable parameters sampled by the trajectory engine.
 *
 * Values are deliberately expressed in angle/tick space rather than desktop pixels or milliseconds.
 */
data class HumanizationProfile(
    val mode: HumanizationMode,
    val responseScale: Double,
    val pathVariation: Double,
    val driftScale: Double,
    val minimumCurveTicks: Int,
    val correctionTendency: Double,
    val overshootScale: Double,
) {
    val enabled: Boolean
        get() = mode != HumanizationMode.OFF

    companion object {
        val OFF = HumanizationProfile(HumanizationMode.OFF, 1.0, 0.0, 0.0, 0, 0.0, 0.0)

        fun subtle(responseScale: Double = 1.0) = HumanizationProfile(
            HumanizationMode.SUBTLE,
            responseScale.coerceIn(0.5, 1.5),
            pathVariation = 0.025,
            driftScale = 0.002,
            minimumCurveTicks = 3,
            correctionTendency = 0.12,
            overshootScale = 0.012,
        )

        fun balanced(responseScale: Double = 1.0) = HumanizationProfile(
            HumanizationMode.BALANCED,
            responseScale.coerceIn(0.5, 1.5),
            pathVariation = 0.06,
            driftScale = 0.004,
            minimumCurveTicks = 3,
            correctionTendency = 0.28,
            overshootScale = 0.025,
        )

        fun custom(responseScale: Double, pathVariation: Double) = HumanizationProfile(
            HumanizationMode.CUSTOM,
            responseScale.coerceIn(0.5, 1.5),
            pathVariation = pathVariation.coerceIn(0.0, 0.2),
            driftScale = (pathVariation * 0.07).coerceAtMost(0.01),
            minimumCurveTicks = 3,
            correctionTendency = (pathVariation * 4.0).coerceIn(0.0, 0.65),
            overshootScale = (pathVariation * 0.4).coerceIn(0.0, 0.06),
        )
    }
}
