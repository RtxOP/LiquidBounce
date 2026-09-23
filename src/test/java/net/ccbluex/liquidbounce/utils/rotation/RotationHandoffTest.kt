package net.ccbluex.liquidbounce.utils.rotation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §11.6 module-toggle handoff contract (the headless-testable part): a
 * client-side request (ApplyServerSide = false — the unified Aimbot path)
 * release-syncs a pending server-side spoof first, and ONLY then. Without
 * the pending-spoof guard, continuous client-side requests would reset the
 * stored AimEngine state on every tick — the churn that would break every
 * fitted stage. The mc-side write-back itself is pinned by the Phase-1
 * corpus evidence (KillAura→manual handoff tick grid-snapped, §8.3 item 2).
 */
class RotationHandoffTest {

    @Test
    fun clientSideRequestReleasesOnlyPendingSpoof() {
        val spoof = Rotation(10f, 5f)
        assertTrue(needsReleaseSync(incomingApplyServerSide = false, pendingSpoof = spoof)) {
            "client-side request over a pending spoof must release-sync first"
        }
        assertFalse(needsReleaseSync(incomingApplyServerSide = false, pendingSpoof = null)) {
            "continuous client-side requests must not churn engine state"
        }
        assertFalse(needsReleaseSync(incomingApplyServerSide = true, pendingSpoof = spoof)) {
            "server-side requests adopt the live spoof without release"
        }
        assertFalse(needsReleaseSync(incomingApplyServerSide = true, pendingSpoof = null))
    }
}
