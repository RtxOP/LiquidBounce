/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** One-release compatibility migration for rotation settings removed by the humanization refactor. */
object RotationConfigMigration {

    fun migrate(moduleName: String, module: JsonObject) {
        migrateHumanization(module)
        migratePrediction(moduleName, module)
    }

    private fun migrateHumanization(module: JsonObject) {
        if (module.entry("Humanization") != null) return

        val nestedSettings = module.entry("RotationSettings")?.value as? JsonObject
        nestedSettings?.entry("Humanization")?.value?.let {
            module.add("Humanization", it)
            return
        }

        val legacyLegitimize = module.entry("Legitimize")?.value?.booleanOrNull()
            ?: nestedSettings?.entry("Legitimize")?.value?.booleanOrNull()
        val legacyPattern = module.entry("RandomizationPattern")?.value?.stringOrNull()
            ?: (module.entry("Randomization")?.value as? JsonObject)
                ?.entry("RandomizationPattern")?.value?.stringOrNull()
        val randomized = legacyPattern?.equals("None", ignoreCase = true) == false

        if (legacyLegitimize == null && legacyPattern == null) return

        // RotationSettings registers its child values directly on the module, so the persisted schema is flat.
        module.addProperty("Humanization", if (legacyLegitimize == true || randomized) "Balanced" else "Off")
    }

    private fun migratePrediction(moduleName: String, module: JsonObject) {
        if (module.entry("PredictionHorizon") != null) return
        if (moduleName !in setOf("Aimbot", "KillAura", "TimerRange")) return

        val legacy = module.entry("PredictEnemyPosition")?.value?.doubleOrNull() ?: return
        module.addProperty("PredictionHorizon", (2.0 + legacy).coerceIn(0.0, 5.0))
    }

    private fun JsonObject.entry(name: String) = entrySet().firstOrNull { it.key.equals(name, ignoreCase = true) }

    private fun JsonElement.booleanOrNull() = runCatching { asBoolean }.getOrNull()

    private fun JsonElement.stringOrNull() = runCatching { asString }.getOrNull()

    private fun JsonElement.doubleOrNull() = runCatching { asDouble }.getOrNull()
}
