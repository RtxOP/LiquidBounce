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
package net.ccbluex.liquidbounce.features.module.modules.player.invcleaner

import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.player.invcleaner.items.ItemFacet
import net.ccbluex.liquidbounce.features.module.modules.player.offhand.ModuleOffhand
import net.ccbluex.liquidbounce.utils.inventory.*
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.screen.slot.SlotActionType

/**
 * InventoryCleaner module
 *
 * Automatically throws away useless items and sorts them.
 */
object ModuleInventoryCleaner : ClientModule("InventoryCleaner", Category.PLAYER) {

    private val inventoryConstraints = tree(PlayerInventoryConstraints())

    private val maxBlocks by int("MaximumBlocks", 512, 0..2500)
    private val maxArrows by int("MaximumArrows", 128, 0..2500)
    private val maxThrowables by int("MaximumThrowables", 64, 0..600)
    private val maxFoods by int("MaximumFoodPoints", 200, 0..2000)

    private val isGreedy by boolean("Greedy", true)

    private val offHandItem by enumChoice("OffHandItem", ItemSortChoice.SHIELD)
    private val slotItem1 by enumChoice("SlotItem-1", ItemSortChoice.WEAPON)
    private val slotItem2 by enumChoice("SlotItem-2", ItemSortChoice.BOW)
    private val slotItem3 by enumChoice("SlotItem-3", ItemSortChoice.PICKAXE)
    private val slotItem4 by enumChoice("SlotItem-4", ItemSortChoice.AXE)
    private val slotItem5 by enumChoice("SlotItem-5", ItemSortChoice.NONE)
    private val slotItem6 by enumChoice("SlotItem-6", ItemSortChoice.POTION)
    private val slotItem7 by enumChoice("SlotItem-7", ItemSortChoice.FOOD)
    private val slotItem8 by enumChoice("SlotItem-8", ItemSortChoice.BLOCK)
    private val slotItem9 by enumChoice("SlotItem-9", ItemSortChoice.BLOCK)

    val cleanupTemplateFromSettings: CleanupPlanPlacementTemplate
        get() {
            val slotTargets: HashMap<ItemSlot, ItemSortChoice> = hashMapOf(
                Pair(OffHandSlot, offHandItem),
                Pair(HotbarItemSlot(0), slotItem1),
                Pair(HotbarItemSlot(1), slotItem2),
                Pair(HotbarItemSlot(2), slotItem3),
                Pair(HotbarItemSlot(3), slotItem4),
                Pair(HotbarItemSlot(4), slotItem5),
                Pair(HotbarItemSlot(5), slotItem6),
                Pair(HotbarItemSlot(6), slotItem7),
                Pair(HotbarItemSlot(7), slotItem8),
                Pair(HotbarItemSlot(8), slotItem9),
            )

            val forbiddenSlots = slotTargets
                .filter { it.value == ItemSortChoice.IGNORE }
                .map { (slot, _) -> slot }
                .toHashSet()

            // Disallow tampering with armor slots since auto armor already handles them
            for (armorSlot in 0 until 4) {
                forbiddenSlots.add(ArmorItemSlot(armorSlot))
            }

            if (ModuleOffhand.isOperating()) {
                // Disallow tampering with off-hand slot when AutoTotem is active
                forbiddenSlots.add(OffHandSlot)
            }

            val forbiddenSlotsToFill = setOfNotNull(
                // Disallow tampering with off-hand slot when AutoTotem is active
                if (ModuleOffhand.isOperating()) OffHandSlot else null
            )

            val constraintProvider = AmountConstraintProvider(
                desiredItemsPerCategory = hashMapOf(
                    Pair(ItemSortChoice.BLOCK.category!!, maxBlocks),
                    Pair(ItemSortChoice.THROWABLES.category!!, maxThrowables),
                    Pair(ItemCategory(ItemType.ARROW, 0), maxArrows),
                ),
                desiredValuePerFunction = hashMapOf(
                    Pair(ItemFunction.FOOD, maxFoods),
                    Pair(ItemFunction.WEAPON_LIKE, 1),
                )
            )

            return CleanupPlanPlacementTemplate(
                slotTargets,
                itemAmountConstraintProvider = constraintProvider::getConstraints,
                forbiddenSlots = forbiddenSlots,
                forbiddenSlotsToFill = forbiddenSlotsToFill,
                isGreedy = isGreedy,
            )
        }

    @Suppress("unused")
    private val handleInventorySchedule = handler<ScheduleInventoryActionEvent> { event ->
        val cleanupPlan = CleanupPlanGenerator(cleanupTemplateFromSettings, findNonEmptySlotsInInventory())
            .generatePlan()

        // Step 1: Move items to the correct slots
        for (hotbarSwap in cleanupPlan.swaps) {
            check(hotbarSwap.to is HotbarItemSlot) { "Cannot swap to non-hotbar-slot" }

            event.schedule(
                inventoryConstraints,
                ClickInventoryAction.performSwap(null, hotbarSwap.from, hotbarSwap.to)
            )

            // todo: run when successful or do not care?
            cleanupPlan.remapSlots(
                hashMapOf(
                    Pair(hotbarSwap.from, hotbarSwap.to),
                    Pair(hotbarSwap.to, hotbarSwap.from),
                )
            )
        }

        // Step 2: Merge stacks
        val stacksToMerge = ItemMerge.findStacksToMerge(cleanupPlan)
        for (slot in stacksToMerge) {
            event.schedule(
                inventoryConstraints,
                ClickInventoryAction.click(null, slot, 0, SlotActionType.PICKUP),
                ClickInventoryAction.click(null, slot, 0, SlotActionType.PICKUP_ALL),
                ClickInventoryAction.click(null, slot, 0, SlotActionType.PICKUP),
            )
        }

        // It is important that we call findItemSlotsInInventory() here again, because the inventory has changed.
        val itemsToThrowOut = findItemsToThrowOut(cleanupPlan, findNonEmptySlotsInInventory())

        for (slot in itemsToThrowOut) {
            event.schedule(
                inventoryConstraints,
                ClickInventoryAction.performThrow(screen = null, slot),
                Priority.NOT_IMPORTANT
            )
        }
    }

    fun findItemsToThrowOut(
        cleanupPlan: InventoryCleanupPlan,
        itemsInInv: List<ItemSlot>,
    ) = itemsInInv.filter { it !in cleanupPlan.usefulItems }

    private class AmountConstraintProvider(
        val desiredItemsPerCategory: HashMap<ItemCategory, Int>,
        val desiredValuePerFunction: HashMap<ItemFunction, Int>,
    ) {
        fun getConstraints(facet: ItemFacet): ArrayList<ItemConstraintInfo> {
            val constraints = ArrayList<ItemConstraintInfo>()

            if (facet.providedItemFunctions.isEmpty()) {
                val defaultDesiredAmount = if (facet.category.type.oneIsSufficient) 1 else Integer.MAX_VALUE
                val desiredAmount = this.desiredItemsPerCategory[facet.category] ?: defaultDesiredAmount

                val info = ItemConstraintInfo(
                    group = ItemCategoryConstraintGroup(
                        desiredAmount..Integer.MAX_VALUE,
                        10,
                        facet.category
                    ),
                    amountAddedByItem = facet.itemStack.count
                )

                constraints.add(info)
            } else {
                for ((function, amountAdded) in facet.providedItemFunctions) {
                    val info = ItemConstraintInfo(
                        group = ItemFunctionCategoryConstraintGroup(
                            desiredValuePerFunction.getOrDefault(function, 1)..Integer.MAX_VALUE,
                            10,
                            function
                        ),
                        amountAddedByItem = amountAdded
                    )

                    constraints.add(info)
                }
            }

            return constraints
        }
    }

}
