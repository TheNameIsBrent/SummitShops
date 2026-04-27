package com.oneblock.shops.util;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Utility methods for safe, NBT-aware inventory operations.
 */
public final class ItemUtils {

    private ItemUtils() {}

    /**
     * Counts how many items in {@code inv} are
     * {@link ItemStack#isSimilar similar} to {@code template}.
     * Respects enchantments, custom model data, PDC tags, etc.
     */
    public static int countMatching(Inventory inv, ItemStack template) {
        int count = 0;
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack != null && stack.isSimilar(template)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /**
     * Removes exactly {@code template.getAmount()} items matching
     * {@code template} from the inventory.
     *
     * @return {@code true} if the items were removed successfully
     */
    public static boolean removeOne(Inventory inv, ItemStack template) {
        int toRemove = template.getAmount();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && toRemove > 0; i++) {
            ItemStack stack = contents[i];
            if (stack != null && stack.isSimilar(template)) {
                if (stack.getAmount() <= toRemove) {
                    toRemove -= stack.getAmount();
                    contents[i] = null;
                } else {
                    stack.setAmount(stack.getAmount() - toRemove);
                    toRemove = 0;
                }
            }
        }
        inv.setStorageContents(contents);
        return toRemove == 0;
    }

    /**
     * Returns {@code true} if the inventory has room to accept
     * {@code item.getAmount()} items.
     */
    public static boolean hasSpace(Inventory inv, ItemStack item) {
        return countAvailableSpace(inv, item) >= item.getAmount();
    }

    /**
     * How many more of {@code item} can fit in {@code inv}.
     */
    public static int countAvailableSpace(Inventory inv, ItemStack item) {
        int space = 0;
        int maxStack = item.getMaxStackSize();
        for (ItemStack slot : inv.getStorageContents()) {
            if (slot == null) {
                space += maxStack;
            } else if (slot.isSimilar(item) && slot.getAmount() < maxStack) {
                space += maxStack - slot.getAmount();
            }
        }
        return space;
    }
}
