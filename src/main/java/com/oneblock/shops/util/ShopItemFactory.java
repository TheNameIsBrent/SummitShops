package com.oneblock.shops.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates and identifies the custom shop-placement item.
 *
 * <p>The item carries a {@link PersistentDataType#BYTE} flag in its
 * PersistentDataContainer so that it can be reliably detected on
 * placement regardless of display name changes.</p>
 */
public final class ShopItemFactory {

    /** The PDC key used to mark a shop placement item. */
    private static NamespacedKey SHOP_ITEM_KEY;

    private ShopItemFactory() {}

    public static void init(Plugin plugin) {
        SHOP_ITEM_KEY = new NamespacedKey(plugin, "shop_item");
    }

    public static NamespacedKey getShopItemKey() {
        return SHOP_ITEM_KEY;
    }

    /**
     * Builds the shop block item from config, tagged with the PDC marker.
     */
    public static ItemStack createShopItem(Plugin plugin) {
        // Always use END_PORTAL_FRAME regardless of config
        Material material = Material.END_PORTAL_FRAME;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("shop-item.name", "&6&lShop Block");
        meta.setDisplayName(colorize(name));

        List<String> lore = plugin.getConfig().getStringList("shop-item.lore");
        List<String> coloredLore = new ArrayList<>();
        for (String line : lore) coloredLore.add(colorize(line));
        meta.setLore(coloredLore);

        // Mark this as a shop item
        meta.getPersistentDataContainer().set(SHOP_ITEM_KEY,
                PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Returns {@code true} when the given ItemStack is the shop placement item.
     */
    public static boolean isShopItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta()
                .getPersistentDataContainer()
                .has(SHOP_ITEM_KEY, PersistentDataType.BYTE);
    }

    private static String colorize(String s) {
        return s.replace("&", "\u00A7");
    }
}
