/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.rotation

import net.ccbluex.liquidbounce.config.Configurable
import net.ccbluex.liquidbounce.config.ListValue
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.utils.extensions.random
import net.ccbluex.liquidbounce.utils.extensions.withGCD
import net.ccbluex.liquidbounce.utils.rotation.humanization.HumanizationMode
import net.ccbluex.liquidbounce.utils.rotation.humanization.HumanizationProfile

// TODO: refactor them all

class AlwaysRotationSettings(owner: Module, generalApply: () -> Boolean = { true }) :
    RotationSettings(owner, generalApply) {
    override val rotationsValue = super.rotationsValue.apply { excludeWithState(true) }
    override val rotationsActive: Boolean = true
}

@Suppress("MemberVisibilityCanBePrivate")
open class RotationSettings(val moduleOwner: Module, generalApply: () -> Boolean = { true }) : Configurable("RotationSettings") {

    open val rotationsValue = boolean("Rotations", true) { generalApply() }
    open val applyServerSideValue = boolean("ApplyServerSide", true) { rotationsActive && generalApply() }
    open val strafeValue = boolean("Strafe", false) { rotationsActive && applyServerSide && generalApply() }
    open val strictValue = boolean("Strict", false) { strafeValue.isActive() && generalApply() }
    open val keepRotationValue = boolean("KeepRotation", true) { rotationsActive && applyServerSide && generalApply() }

    open val resetTicksValue = int("ResetTicks", 1, 1..20) {
        rotationsActive && applyServerSide && generalApply()
    }

    open val humanizationModeValue = choices(
        "Humanization", arrayOf("Off", "Subtle", "Balanced", "Custom"), "Off"
    ) { rotationsActive && generalApply() }
    open val humanizationResponseValue = int(
        "HumanizationResponse", 100, 50..150, suffix = "%"
    ) { rotationsActive && humanizationMode != "Off" && generalApply() }
    open val humanizationPathVariationValue = int(
        "HumanizationPathVariation", 6, 0..20, suffix = "%"
    ) { rotationsActive && humanizationMode == "Custom" && generalApply() }
    open val humanizationCorrectionValue = choices(
        "HumanizationCorrection", arrayOf("Off", "Low", "Medium", "High"), "Medium"
    ) { rotationsActive && humanizationMode == "Custom" && generalApply() }
    open val humanizationTargetDriftValue = int(
        "HumanizationTargetDrift", 4, 0..20, suffix = "%"
    ) { rotationsActive && humanizationMode == "Custom" && generalApply() }

    open val horizontalAngleChangeValue =
        floatRange("HorizontalAngleChange", 180f..180f, 1f..180f) { rotationsActive && generalApply() }
    open val verticalAngleChangeValue =
        floatRange("VerticalAngleChange", 180f..180f, 1f..180f) { rotationsActive && generalApply() }

    open val angleResetDifferenceValue = float("AngleResetDifference", 5f.withGCD(), 0.0f..180f) {
        rotationsActive && applyServerSide && generalApply()
    }

    // Variables for easier access
    val rotations by rotationsValue
    val applyServerSide by applyServerSideValue
    val strafe by strafeValue
    val strict by strictValue
    val keepRotation by keepRotationValue
    val resetTicks by resetTicksValue
    val humanizationMode by humanizationModeValue
    val humanizationResponse by humanizationResponseValue
    val humanizationPathVariation by humanizationPathVariationValue
    val humanizationCorrection by humanizationCorrectionValue
    val humanizationTargetDrift by humanizationTargetDriftValue
    val horizontalAngleChange by horizontalAngleChangeValue
    val verticalAngleChange by verticalAngleChangeValue
    val angleResetDifference by angleResetDifferenceValue

    open val rotationsActive
        get() = rotations

    val horizontalSpeed
        get() = horizontalAngleChange.random()

    val verticalSpeed
        get() = verticalAngleChange.random()

    val humanizationProfile: HumanizationProfile
        get() {
            val response = humanizationResponse / 100.0
            val correctionTendency = when (humanizationCorrection) {
                "Low" -> 0.15
                "Medium" -> 0.35
                "High" -> 0.65
                else -> 0.0
            }
            return when (HumanizationMode.fromName(humanizationMode)) {
                HumanizationMode.OFF -> HumanizationProfile.OFF
                HumanizationMode.SUBTLE -> HumanizationProfile.subtle(response)
                HumanizationMode.BALANCED -> HumanizationProfile.balanced(response)
                HumanizationMode.CUSTOM -> HumanizationProfile.custom(
                    response,
                    humanizationPathVariation / 100.0,
                    correctionTendency,
                    humanizationTargetDrift / 100.0,
                )
            }
        }

    fun withoutKeepRotation() = apply {
        keepRotationValue.excludeWithState()
    }

    init {
        moduleOwner.addValues(this.values)
    }
}

class RotationSettingsWithRotationModes(
    owner: Module, listValue: ListValue, generalApply: () -> Boolean = { true },
) : RotationSettings(owner, generalApply) {

    override val rotationsValue = super.rotationsValue.apply { excludeWithState() }

    val rotationModeValue = listValue.setSupport { generalApply() }

    val rotationMode by +rotationModeValue

    override val rotationsActive: Boolean
        get() = rotationMode != "Off"
}
