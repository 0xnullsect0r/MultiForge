/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.neoforge.world;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Inventory changes aimed at a player another region is ticking. The real change runs on
 * the server thread after the tick barrier, so what the caller gets differs from Vanilla:
 *
 * <ul>
 * <li><b>Adding</b> ({@code add}, {@code placeItemBackInInventory}): the caller's stack is
 * emptied and {@code add} returns {@code true}. What does not fit at the barrier drops
 * at the player, so an item is never lost or duplicated.</li>
 * <li><b>Taking out</b> ({@code removeItem}, {@code removeItemNoUpdate},
 * {@code removeFromSelected}, a removing {@code clearOrCountMatchingItems}): nothing
 * moves and the caller gets {@code ItemStack.EMPTY} or {@code 0}, as for an empty slot.
 * The result cannot be known now, and returning a guess would duplicate the item.</li>
 * <li><b>Everything else</b> (returning nothing): runs at the barrier, on a copy of the
 * stack as it was when called. {@code setSelectedItem} returns the current item.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class CrossRegionInventory {
    private CrossRegionInventory() {}

    public static boolean isForeign(Inventory inventory) {
        return CrossRegionEffects.isForeign(inventory.player);
    }

    public static boolean add(Inventory inventory, int slot, ItemStack stack) {
        if (stack.isEmpty()) return false;
        ItemStack moved = stack.copyAndClear();
        CrossRegionEffects.deferAlways("Inventory.add", () -> {
            if (!inventory.player.isRemoved()) inventory.add(slot, moved);
            dropRemainder(inventory, moved);
        });
        return true;
    }

    public static boolean deferPlaceBack(Inventory inventory, ItemStack stack, boolean sendPacket) {
        if (!isForeign(inventory)) return false;
        ItemStack moved = stack.copyAndClear();
        CrossRegionEffects.deferAlways("Inventory.placeItemBackInInventory", () -> {
            if (!inventory.player.isRemoved()) inventory.placeItemBackInInventory(moved, sendPacket);
            dropRemainder(inventory, moved);
        });
        return true;
    }

    /** Whatever could not be stored: at the player, or where a player who left last stood. */
    private static void dropRemainder(Inventory inventory, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!inventory.player.isRemoved()) {
            inventory.player.drop(stack, false);
            return;
        }
        Level level = inventory.player.level();
        level.addFreshEntity(new ItemEntity(level, inventory.player.getX(), inventory.player.getY(), inventory.player.getZ(), stack));
    }

    /** {@code true}: the caller must report that nothing was taken. */
    public static boolean refuseRemoval(Inventory inventory, String site) {
        if (!isForeign(inventory)) return false;
        ViolationLogger.warn(site, "taking items from a player in another region: nothing was taken");
        return true;
    }

    public static ItemStack setSelectedItem(Inventory inventory, ItemStack stack) {
        ItemStack current = inventory.getSelectedItem();
        ItemStack copy = stack.copy();
        CrossRegionEffects.defer("Inventory.setSelectedItem", inventory.player, () -> inventory.setSelectedItem(copy));
        return current;
    }

    public static boolean deferSelect(Inventory inventory, int slot) {
        if (!isForeign(inventory)) return false;
        CrossRegionEffects.defer("Inventory.setSelectedSlot", inventory.player, () -> inventory.setSelectedSlot(slot));
        return true;
    }

    public static boolean deferAddAndPick(Inventory inventory, ItemStack stack) {
        if (!isForeign(inventory)) return false;
        ItemStack copy = stack.copy();
        CrossRegionEffects.defer("Inventory.addAndPickItem", inventory.player, () -> inventory.addAndPickItem(copy));
        return true;
    }

    public static boolean deferPickSlot(Inventory inventory, int slot) {
        if (!isForeign(inventory)) return false;
        CrossRegionEffects.defer("Inventory.pickSlot", inventory.player, () -> inventory.pickSlot(slot));
        return true;
    }

    public static boolean deferRemoveStack(Inventory inventory, ItemStack stack) {
        if (!isForeign(inventory)) return false;
        CrossRegionEffects.defer("Inventory.removeItem", inventory.player, () -> inventory.removeItem(stack));
        return true;
    }

    public static boolean deferSetItem(Inventory inventory, int slot, ItemStack stack) {
        if (!isForeign(inventory)) return false;
        ItemStack copy = stack.copy();
        CrossRegionEffects.defer("Inventory.setItem", inventory.player, () -> inventory.setItem(slot, copy));
        return true;
    }

    public static boolean deferDropAll(Inventory inventory) {
        if (!isForeign(inventory)) return false;
        CrossRegionEffects.defer("Inventory.dropAll", inventory.player, inventory::dropAll);
        return true;
    }

    public static boolean deferReplaceWith(Inventory inventory, Inventory other) {
        if (!isForeign(inventory)) return false;
        CrossRegionEffects.defer("Inventory.replaceWith", inventory.player, () -> inventory.replaceWith(other));
        return true;
    }

    public static boolean deferClear(Inventory inventory) {
        if (!isForeign(inventory)) return false;
        CrossRegionEffects.defer("Inventory.clearContent", inventory.player, inventory::clearContent);
        return true;
    }
}
