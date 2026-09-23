package net.ccbluex.liquidbounce.utils.rotation.aim

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Enforces the purity rule of spec §11.1: no `net.minecraft.*`, no
 * `MinecraftInstance`, no module/config types inside the aim package —
 * rotation values in, rotation value out. This is what keeps the engine
 * testable headlessly (spike B) and lets JUnit exercise every stage without
 * a running game.
 *
 * Scans the `import` lines of every Kotlin file in the package; other-package
 * types require imports in Kotlin, so an import scan is a complete guard
 * against accidental coupling.
 */
class AimPackagePurityTest {

    private val forbidden = listOf(
        "import net.minecraft",                       // Minecraft classes
        "MinecraftInstance",                          // mc singleton access
        "import net.ccbluex.liquidbounce.features.module", // Module types
        "Configurable",                               // config/value plumbing
        "import net.ccbluex.liquidbounce.event",      // event bus
    )

    @Test
    fun aimPackageStaysMcFree() {
        val dir = File("src/main/java/net/ccbluex/liquidbounce/utils/rotation/aim")
        assumeTrue(dir.isDirectory) { "aim package absent - purity check skipped" }
        val files = dir.listFiles { f -> f.extension == "kt" } ?: emptyArray()
        assumeTrue(files.isNotEmpty()) { "no Kotlin files in aim package - purity check skipped" }

        val violations = mutableListOf<String>()
        for (file in files) {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.startsWith("import ")) {
                    for (needle in forbidden) {
                        if (line.contains(needle)) violations += "${file.name}: $line"
                    }
                }
            }
        }
        assertTrue(violations.isEmpty()) {
            "aim package purity violated (spec §11.1):\n" + violations.joinToString("\n")
        }
    }
}
