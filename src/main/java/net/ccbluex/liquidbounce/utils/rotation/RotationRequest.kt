/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation

import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.Vec3

/**
 * Describes why a rotation is requested. Humanization may adapt its path, but functional constraints always win.
 */
enum class RotationPurpose {
    GENERIC,
    COMBAT_TRACK,
    PROJECTILE,
    BLOCK_INTERACT,
    PLACE,
    RESET,
}

/**
 * Determines how the Minecraft adapter validates a generated rotation.
 */
enum class RotationValidity {
    NONE,
    RAYCAST,
    EXACT,
}

/**
 * Optional target metadata retained across refreshed requests.
 */
sealed class RotationTarget {
    data class EntityRegion(
        val entityId: Int,
        val box: AxisAlignedBB,
        val bodyRange: ClosedFloatingPointRange<Double>,
        val horizontalRange: ClosedFloatingPointRange<Double>,
    ) : RotationTarget()

    data class WorldPoint(val point: Vec3) : RotationTarget()

    data class ExactRotation(val rotation: Rotation) : RotationTarget()
}

/**
 * A rotation request with stable ownership and enough context for stateful processing.
 */
data class RotationRequest(
    val owner: Any,
    val desired: Rotation,
    val settings: RotationSettings,
    val target: RotationTarget? = null,
    val purpose: RotationPurpose = RotationPurpose.GENERIC,
    val deadlineTick: Int? = null,
    val validity: RotationValidity = RotationValidity.NONE,
    val priority: Int = 0,
    val horizontalSpeed: Float? = null,
    val verticalSpeed: Float? = null,
    val changeYaw: Boolean = true,
    val changePitch: Boolean = true,
)
