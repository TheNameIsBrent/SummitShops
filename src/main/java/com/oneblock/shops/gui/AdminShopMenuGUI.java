package com.oneblock.shops.gui;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Admin shop browser — /shop menu
 *
 * Shows all shops across all owners in a paged 6-row GUI.
 *   Rows 0-4 (45 slots) = shop entries
 *   Row 5: [←Prev] [Page x/y] [→Next]
 *
 * LEFT-CLICK  a shop entry → teleport to it
 * RIGHT-CLICK a shop entry → open its ShopEditorGUI as the admin
 * SHIFT+CLICK a shop entry → force-delete the shop
 */
public class AdminShopMenuGUI implements Listener {

    private static final int PAGE_SIZE   = 45;
    private static final int SLOT_PREV   = 48;
    private static final int SLOT_INFO   = 49;
    private static final int SLOT_NEXT   = 50;

    private final OneBlockShopsPlugin plugin;
    private final Player admin;
    private final List<Shop> shops;
    private int page;
    private Inventory inv;

    public AdminShopMenuGUI(OneBlockShopsPlugin plugin, Player admin) {
        this.plugin = plugin;
        this.admin  = admin;
        Collection<Shop> all = plugin.getShopManager().getAllShops();
        this.shops  = new ArrayList<>(all);
        // Sort by owner name for easy browsing
        this.shops.sort((a, b) -> {
            String na = ownerName(a), nb = ownerName(b);
            return na.compareToIgnoreCase(nb);
        });
        this.page = 0;
    }

    public void open() {
        inv = Bukkit.createInventory(null, 54, color("&8Admin Shop Browser"));
        fill();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        admin.openInventory(inv);
    }

    private void fill() {
        ItemStack glass = make(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 54; i++) inv.setItem(i, glass);

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && (start + i) < shops.size(); i++) {
            Shop shop = shops.get(start + i);
            inv.setItem(i, shopIcon(shop));
        }

        int totalPages = Math.max(1, (int) Math.ceil(shops.size() / (double) PAGE_SIZE));

        inv.setItem(SLOT_PREV, make(Material.ARROW, color("&7← Previous"), null));
        inv.setItem(SLOT_INFO, make(Material.BOOK,
                color("&fPage &e" + (page + 1) + " &fof &e" + totalPages),
                List.of(color("&7Total shops: &f" + shops.size()))));
        inv.setItem(SLOT_NEXT, make(Material.ARROW, color("&7→ Next"), null));
    }

    private ItemStack shopIcon(Shop shop) {
        Material mat = shop.getItem() != null ? shop.getItem().getType() : Material.END_PORTAL_FRAME;
        String owner = ownerName(shop);
        org.bukkit.Location loc = shop.getBlockLocation();
        String locStr = loc != null
                ? loc.getWorld().getName() + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                : "unknown";
        return make(mat,
                color("&6[" + shop.getShortId() + "] &f" + owner + "'s shop"),
                List.of(
                    color("&7Mode: &f" + shop.getMode().name()),
                    color("&7Item: &f" + (shop.getItem() != null ? shop.getItem().getType().name() : "not set")),
                    color("&7Price: &f" + fmt(shop.getPrice()) + " &7Bank: &f" + fmt(shop.getBankBalance())),
                    color("&7Location: &f" + locStr),
                    color("&7ID: &8" + shop.getId()),
                    color(""),
                    color("&aLeft-click &7to teleport"),
                    color("&eRight-click &7to open editor"),
                    color("&cShift+click &7to force delete")
                ));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getWhoClicked().getUniqueId().equals(admin.getUniqueId())) return;
        if (event.getInventory() != inv) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot >= 54 || slot < 0) return;

        if (slot == SLOT_PREV) {
            if (page > 0) { page--; fill(); }
            return;
        }
        if (slot == SLOT_NEXT) {
            int totalPages = Math.max(1, (int) Math.ceil(shops.size() / (double) PAGE_SIZE));
            if (page < totalPages - 1) { page++; fill(); }
            return;
        }
        if (slot == SLOT_INFO) return;

        // Shop slot
        int idx = page * PAGE_SIZE + slot;
        if (idx < 0 || idx >= shops.size()) return;
        Shop shop = shops.get(idx);

        ClickType click = event.getClick();

        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            // Force delete
            HandlerList.unregisterAll(this);
            admin.closeInventory();
            plugin.getShopManager().removeShop(shop.getId());
            admin.sendMessage(color("&cForce-deleted shop &7[" + shop.getShortId() + "] &cowned by &f" + ownerName(shop)));
        } else if (click == ClickType.RIGHT) {
            // Open editor as admin
            HandlerList.unregisterAll(this);
            admin.closeInventory();
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> new ShopEditorGUI(plugin, admin, shop).open(), 1L);
        } else {
            // Teleport
            org.bukkit.Location dest = shop.getBlockLocation();
            if (dest == null) {
                admin.sendMessage(color("&cShop location unavailable."));
                return;
            }
            dest.add(0.5, 1, 0.5); // stand on top of the block
            HandlerList.unregisterAll(this);
            admin.closeInventory();
            admin.teleport(dest);
            admin.sendMessage(color("&aTeleported to shop &7[" + shop.getShortId() + "] &aowned by &f" + ownerName(shop)));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getPlayer().getUniqueId().equals(admin.getUniqueId())) return;
        if (event.getInventory() != inv) return;
        HandlerList.unregisterAll(this);
    }

    private String ownerName(Shop shop) {
        try {
            org.bukkit.OfflinePlayer op = plugin.getServer().getOfflinePlayer(shop.getOwnerUUID());
            String name = op.getName();
            return name != null ? name : shop.getOwnerUUID().toString().substring(0, 8);
        } catch (Exception e) { return "?"; }
    }

    private static ItemStack make(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String color(String s) { return s.replace("&", "\u00A7"); }

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
