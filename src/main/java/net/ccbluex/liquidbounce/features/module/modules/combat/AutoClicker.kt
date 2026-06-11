/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.AttackEvent
import net.ccbluex.liquidbounce.event.GameTickEvent
import net.ccbluex.liquidbounce.event.UpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.utils.attack.EntityUtils.isLookingOnEntities
import net.ccbluex.liquidbounce.utils.attack.EntityUtils.isSelected
import net.ccbluex.liquidbounce.utils.client.EntityLookup
import net.ccbluex.liquidbounce.utils.extensions.fixedSensitivityPitch
import net.ccbluex.liquidbounce.utils.extensions.fixedSensitivityYaw
import net.ccbluex.liquidbounce.utils.extensions.getDistanceToEntityBox
import net.ccbluex.liquidbounce.utils.extensions.isBlock
import net.ccbluex.liquidbounce.utils.kotlin.RandomUtils
import net.ccbluex.liquidbounce.utils.kotlin.RandomUtils.nextFloat
import net.minecraft.client.settings.KeyBinding
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.EnumAction
import net.minecraft.item.ItemBlock
import kotlin.random.Random.Default.nextBoolean

object AutoClicker : Module("AutoClicker", Category.COMBAT) {

    private val simulateDoubleClicking by boolean("SimulateDoubleClicking", false)
    private val cps by intRange("CPS", 5..8, 1..50)

    private val hurtTime by int("HurtTime", 10, 0..10) { left }

    private val right by boolean("Right", true)
    private val left by boolean("Left", true)
    private val jitter by boolean("Jitter", false)
    private val block by boolean("AutoBlock", false) { left }
    private val blockDelay by int("BlockDelay", 50, 0..100) { block }

    private val requiresNoInput by boolean("RequiresNoInput", false) { left }
    private val maxAngleDifference by float("MaxAngleDifference", 30f, 10f..180f) { left && requiresNoInput }
    private val range by float("Range", 3f, 0.1f..5f) { left && requiresNoInput }

    private val onlyBlocks by boolean("OnlyBlocks", true) { right }

    private val leftClickPattern = ClickPattern()
    private val rightClickPattern = ClickPattern()

    private var lastBlocking = 0L

    private val shouldAutoClick
        get() = mc.thePlayer.capabilities.isCreativeMode || !mc.objectMouseOver.typeOfHit.isBlock

    private var shouldJitter = false

    private var target: EntityLivingBase? = null

    override fun onDisable() {
        leftClickPattern.reset()
        rightClickPattern.reset()
        lastBlocking = 0L
        target = null
    }

    val onAttack = handler<AttackEvent> { event ->
        if (!left) return@handler
        val targetEntity = event.targetEntity as EntityLivingBase

        target = targetEntity
    }

    val onGameTick = handler<GameTickEvent> {
        mc.thePlayer?.let { thePlayer ->
            val time = System.currentTimeMillis()

            if (block && thePlayer.swingProgress > 0 && !mc.gameSettings.keyBindUseItem.isKeyDown) {
                mc.gameSettings.keyBindUseItem.pressTime = 0
            }

            rightClickPattern.cacheClick(
                right && mc.gameSettings.keyBindUseItem.isKeyDown && (!onlyBlocks || thePlayer.heldItem?.item is ItemBlock),
                cps
            )

            val rightClicks = rightClickPattern.consumeCachedClicks()

            if (rightClicks > 0) {
                handleRightClick(rightClicks)
            }

            if (requiresNoInput) {
                val nearbyEntity = getNearestEntityInRange()
                val canLeftClick = nearbyEntity != null &&
                        isLookingOnEntities(nearbyEntity, maxAngleDifference.toDouble()) &&
                        left && shouldAutoClick

                leftClickPattern.cacheClick(canLeftClick, cps)
                val leftClicks = leftClickPattern.consumeCachedClicks()

                if (leftClicks > 0) {
                    handleLeftClick(leftClicks)
                } else if (block && !mc.gameSettings.keyBindUseItem.isKeyDown && shouldAutoClick && shouldAutoRightClick() && mc.gameSettings.keyBindAttack.pressTime != 0) {
                    handleBlock(time)
                }
            } else {
                leftClickPattern.cacheClick(
                    left && mc.gameSettings.keyBindAttack.isKeyDown && !mc.gameSettings.keyBindUseItem.isKeyDown && shouldAutoClick,
                    cps
                )

                val leftClicks = leftClickPattern.consumeCachedClicks()

                if (leftClicks > 0) {
                    handleLeftClick(leftClicks)
                } else if (block && mc.gameSettings.keyBindAttack.isKeyDown && !mc.gameSettings.keyBindUseItem.isKeyDown && shouldAutoClick && shouldAutoRightClick() && mc.gameSettings.keyBindAttack.pressTime != 0) {
                    handleBlock(time)
                }
            }
        }
    }

    val onTick = handler<UpdateEvent> {
        mc.thePlayer?.let { thePlayer ->

            shouldJitter = !mc.objectMouseOver.typeOfHit.isBlock &&
                    (thePlayer.isSwingInProgress || mc.gameSettings.keyBindAttack.pressTime != 0)

            if (jitter && ((left && shouldAutoClick && shouldJitter)
                        || (right && !thePlayer.isUsingItem && mc.gameSettings.keyBindUseItem.isKeyDown
                        && ((onlyBlocks && thePlayer.heldItem.item is ItemBlock) || !onlyBlocks)))
            ) {

                if (nextBoolean()) thePlayer.fixedSensitivityYaw += nextFloat(-1F, 1F)
                if (nextBoolean()) thePlayer.fixedSensitivityPitch += nextFloat(-1F, 1F)
            }
        }
    }

    private val entities by EntityLookup<EntityLivingBase> {
        isSelected(it, true) && mc.thePlayer.getDistanceToEntityBox(it) <= range
    }

    private fun getNearestEntityInRange(): Entity? {
        val player = mc.thePlayer ?: return null

        return entities.minByOrNull { player.getDistanceToEntityBox(it) }
    }

    private fun shouldAutoRightClick() = mc.thePlayer.heldItem?.itemUseAction in arrayOf(EnumAction.BLOCK)

    private fun handleLeftClick(clicks: Int) {
        if (target != null && target!!.hurtTime > hurtTime) return

        repeat(clicks) {
            repeat(1 + extraClicks()) {
                KeyBinding.onTick(mc.gameSettings.keyBindAttack.keyCode)
            }
        }
    }

    private fun handleRightClick(clicks: Int) {
        repeat(clicks) {
            repeat(1 + extraClicks()) {
                KeyBinding.onTick(mc.gameSettings.keyBindUseItem.keyCode)
            }
        }
    }

    private fun handleBlock(time: Long) {
        if (time - lastBlocking >= blockDelay) {
            KeyBinding.onTick(mc.gameSettings.keyBindUseItem.keyCode)

            lastBlocking = time
        }
    }

    private fun extraClicks() = if (simulateDoubleClicking) RandomUtils.nextInt(-1, 1) else 0

    private class ClickPattern {

        private val clickPattern = ArrayDeque<Int>()
        private var patternUpdateTime = 0L
        private var cachedClicks = 0

        fun cacheClick(active: Boolean, cps: IntRange) {
            if (!active) {
                cachedClicks = 0
                return
            }

            if (shouldClickThisTick(cps)) {
                cachedClicks++
            }
        }

        fun consumeCachedClicks(): Int {
            val clicks = cachedClicks
            cachedClicks = 0
            return clicks
        }

        fun reset() {
            clickPattern.clear()
            patternUpdateTime = 0L
            cachedClicks = 0
        }

        private fun shouldClickThisTick(cps: IntRange): Boolean {
            if (clickPattern.isEmpty() || System.currentTimeMillis() - patternUpdateTime >= 1000L) {
                generateClickPattern(cps)
            }

            return clickPattern.removeFirst() == 1
        }

        private fun generateClickPattern(cps: IntRange) {
            clickPattern.clear()

            val clicksPerSecond = Math.round(RandomUtils.nextDouble(cps.first.toDouble(), cps.last + 1.0)).toInt()
            var clicksToDistribute = clicksPerSecond
            val totalTicks = 20

            for (tick in 0 until totalTicks) {
                val probability = clicksToDistribute.toDouble() / (totalTicks - tick)

                if (RandomUtils.nextDouble() < probability) {
                    clickPattern.addLast(1)
                    clicksToDistribute--
                    continue
                }

                clickPattern.addLast(0)
            }

            patternUpdateTime = System.currentTimeMillis()
        }
    }
}
