package com.oneblock.shops.commands;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.gui.ShopEditorGUI;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopMode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * A paginated chest GUI listing all shops owned by a target player.
 * Clicking a shop entry opens the {@link ShopEditorGUI} for that shop.
 *
 * <p>Each page shows up to 45 entries (5 rows); row 6 contains navigation.</p>
 */
public class ShopListGUI implements Listener {

    private static final int PAGE_SIZE = 45;

    private final OneBlockShopsPlugin plugin;
    private final Player viewer;
    private final List<Shop> shops;

    private Inventory inv;
    private int page = 0;

    public ShopListGUI(OneBlockShopsPlugin plugin, Player viewer, List<Shop> shops) {
        this.plugin  = plugin;
        this.viewer  = viewer;
        this.shops   = shops;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // -----------------------------------------------------------------------
    // Open / render
    // -----------------------------------------------------------------------

    public void open() {
        inv = Bukkit.createInventory(null, 54,
                colorize("&8Shop List (&f" + shops.size() + " shops&8)"));
        render();
        viewer.openInventory(inv);
    }

    private void render() {
        // Clear
        for (int i = 0; i < 54; i++) inv.setItem(i, null);

        // Fill filler in nav row
        ItemStack filler = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        // Previous page
        if (page > 0) {
            inv.setItem(45, makeItem(Material.ARROW,
                    colorize("&ePrevious Page"), List.of(colorize("&7Page " + page))));
        }
        // Next page
        int totalPages = (int) Math.ceil((double) shops.size() / PAGE_SIZE);
        if (page < totalPages - 1) {
            inv.setItem(53, makeItem(Material.ARROW,
                    colorize("&eNext Page"), List.of(colorize("&7Page " + (page + 2)))));
        }
        // Page indicator
        inv.setItem(49, makeItem(Material.PAPER,
                colorize("&7Page &f" + (page + 1) + "&7/&f" + Math.max(1, totalPages)), null));

        // Shop entries
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, shops.size());
        for (int i = start; i < end; i++) {
            Shop shop = shops.get(i);
            inv.setItem(i - start, buildShopEntry(shop));
        }
    }

    private ItemStack buildShopEntry(Shop shop) {
        Material icon = shop.getItem() != null ? shop.getItem().getType() : Material.CHEST;
        String name = shop.getItem() != null && shop.getItem().hasItemMeta()
                && shop.getItem().getItemMeta().hasDisplayName()
                ? shop.getItem().getItemMeta().getDisplayName()
                : (shop.getItem() != null ? shop.getItem().getType().name() : "Not configured");

        List<String> lore = new ArrayList<>();
        lore.add(colorize("&7World: &f" + shop.getWorldName()));
        lore.add(colorize("&7Location: &f" + (int) shop.getX() + ", " +
                (int) shop.getY() + ", " + (int) shop.getZ()));
        lore.add(colorize("&7Mode: " + (shop.getMode() == ShopMode.BUY ? "&aBUY" : "&cSELL")));
        lore.add(colorize("&7Price: &e" + shop.getPrice()));
        lore.add(colorize("&7Currency: &f" + shop.getCurrencyId()));
        lore.add(colorize(""));
        lore.add(colorize("&eClick to edit this shop"));

        return makeItem(icon, colorize("&6" + name), lore);
    }

    // -----------------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getWhoClicked().getUniqueId().equals(viewer.getUniqueId())) return;
        if (!event.getInventory().equals(inv)) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();

        // Navigation
        if (slot == 45 && page > 0) {
            page--;
            render();
            return;
        }
        int totalPages = (int) Math.ceil((double) shops.size() / PAGE_SIZE);
        if (slot == 53 && page < totalPages - 1) {
            page++;
            render();
            return;
        }

        // Shop entry click
        int shopIndex = page * PAGE_SIZE + slot;
        if (shopIndex < 0 || shopIndex >= shops.size()) return;
        if (slot >= PAGE_SIZE) return;

        Shop shop = shops.get(shopIndex);
        viewer.closeInventory(); // triggers onInventoryClose which unregisters this listener
        new ShopEditorGUI(plugin, viewer, shop).open();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getPlayer().getUniqueId().equals(viewer.getUniqueId())) return;
        if (!event.getInventory().equals(inv)) return;
        HandlerList.unregisterAll(this);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String colorize(String s) {
        return s.replace("&", "\u00A7");
    }
}
