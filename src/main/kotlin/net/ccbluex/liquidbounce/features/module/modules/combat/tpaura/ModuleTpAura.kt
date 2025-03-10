/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2025 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.tpaura

import net.ccbluex.liquidbounce.config.types.Choice
import net.ccbluex.liquidbounce.config.types.ChoiceConfigurable
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.tpaura.modes.AStarMode
import net.ccbluex.liquidbounce.features.module.modules.combat.tpaura.modes.ImmediateMode
import net.ccbluex.liquidbounce.render.engine.Color4b
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.clicking.ClickScheduler
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.combat.attack
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.render.WireframePlayer
import net.minecraft.util.math.Vec3d

object ModuleTpAura : ClientModule("TpAura", Category.COMBAT, disableOnQuit = true) {

    private val attackRange by float("AttackRange", 4.2f, 3f..5f)

    val clickScheduler = tree(ClickScheduler(this, true))
    val mode = choices<TpAuraChoice>("Mode", AStarMode, arrayOf(AStarMode, ImmediateMode))
    val targetTracker = tree(TargetTracker())

    val stuckChronometer = Chronometer()
    var desyncPlayerPosition: Vec3d? = null

    @Suppress("unused")
    private val attackRepeatable = tickHandler {
        val position = desyncPlayerPosition ?: player.pos

        clickScheduler.clicks {
            val enemy = targetTracker.enemies()
                .filter { it.squaredBoxedDistanceTo(position) <= attackRange * attackRange }
                .minByOrNull { it.hurtTime } ?: return@clicks false

            enemy.attack(true, keepSprint = true)
            true
        }
    }

    @Suppress("unused")
    val renderHandler = handler<WorldRenderEvent> { event ->
        val (yaw, pitch) = RotationManager.currentRotation ?: player.rotation
        val wireframePlayer = WireframePlayer(desyncPlayerPosition ?: return@handler, yaw, pitch)
        wireframePlayer.render(event, Color4b(36, 32, 147, 87), Color4b(36, 32, 147, 255))
    }

}

open class TpAuraChoice(name: String) : Choice(name) {

    override val parent: ChoiceConfigurable<TpAuraChoice>
        get() = ModuleTpAura.mode

}
