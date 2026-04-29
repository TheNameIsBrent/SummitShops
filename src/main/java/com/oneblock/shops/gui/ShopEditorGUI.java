package com.oneblock.shops.gui;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.economy.CurrencyProvider;
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
import java.util.Optional;

/**
 * 6-row editor GUI.
 *
 * Slot layout:
 *   10 — Shop item  (click WITH an item on cursor to set; click empty to clear)
 *   13 — Set price  (chat input)
 *   16 — Toggle mode
 *   31 — Deposit to bank (chat input)
 *   34 — Bank balance (info)
 *   40 — Open chest inventory
 *   49 — Pick up shop
 */
public class ShopEditorGUI implements Listener {

    private static final int SLOT_ITEM    = 10;
    private static final int SLOT_PRICE   = 13;
    private static final int SLOT_MODE    = 16;
    private static final int SLOT_DEPOSIT  = 31;
    private static final int SLOT_WITHDRAW = 33;
    private static final int SLOT_BANK     = 34;
    private static final int SLOT_CHEST    = 40;
    private static final int SLOT_PICKUP   = 49;

    private final OneBlockShopsPlugin plugin;
    private final Player player;
    private final Shop shop;
    private Inventory inv;

    /** True while we are waiting for a chat response — suppresses close cleanup. */
    private boolean awaitingChat = false;

    public ShopEditorGUI(OneBlockShopsPlugin plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop   = shop;
    }

    public void open() {
        awaitingChat = false;
        inv = Bukkit.createInventory(null, 54, color("&8Shop Manager"));
        fill();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        player.openInventory(inv);
    }

    // -----------------------------------------------------------------------
    // Build / refresh
    // -----------------------------------------------------------------------

    private void fill() {
        ItemStack glass = make(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 54; i++) inv.setItem(i, glass);

        // Slot 10 — item
        if (shop.getItem() != null) {
            ItemStack display = shop.getItem().clone();
            ItemMeta m = display.getItemMeta();
            List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(color("&7Click with a new item on cursor to change"));
            lore.add(color("&7Click with empty cursor to clear & return item"));
            m.setLore(lore);
            display.setItemMeta(m);
            inv.setItem(SLOT_ITEM, display);
        } else {
            inv.setItem(SLOT_ITEM, make(Material.ITEM_FRAME,
                    color("&eSet Shop Item"),
                    List.of(color("&7Pick up an item in your inventory,"),
                            color("&7then click this slot to set it."))));
        }

        // Slot 13 — price
        inv.setItem(SLOT_PRICE, make(Material.GOLD_NUGGET,
                color("&6Price: &f" + fmt(shop.getPrice())),
                List.of(color("&7Click to change"))));

        // Slot 16 — mode
        if (shop.getMode() == ShopMode.BUY) {
            inv.setItem(SLOT_MODE, make(Material.EMERALD,
                    color("&aMode: BUY"),
                    List.of(color("&7Players buy items from your chest"),
                            color("&eClick to switch to SELL"))));
        } else {
            inv.setItem(SLOT_MODE, make(Material.REDSTONE,
                    color("&cMode: SELL"),
                    List.of(color("&7Players sell items into your chest"),
                            color("&eClick to switch to BUY"))));
        }

        // Slot 31 — deposit
        String currName = getCurrencyName();
        inv.setItem(SLOT_DEPOSIT, make(Material.EMERALD_BLOCK,
                color("&aDeposit " + currName),
                List.of(color("&7Add money to the shop bank"),
                        color("&7Needed to pay sellers (SELL mode)"),
                        color("&eClick to deposit"))));

        // Slot 33 — withdraw
        inv.setItem(SLOT_WITHDRAW, make(Material.ORANGE_DYE,
                color("&6Withdraw " + currName),
                List.of(color("&7Take money from the shop bank"),
                        color("&7Balance: &f" + fmt(shop.getBankBalance())),
                        color("&eClick to withdraw"))));

        // Slot 34 — bank balance
        inv.setItem(SLOT_BANK, make(Material.GOLD_INGOT,
                color("&6Bank: &f" + fmt(shop.getBankBalance()) + " " + currName),
                List.of(color("&7Funds held in this shop"),
                        color("&7Returned to you on pickup"))));

        // Slot 40 — chest
        inv.setItem(SLOT_CHEST, make(Material.CHEST,
                color("&eManage Stock"),
                List.of(color("&7Opens the chest to add or remove items"))));

        // Slot 49 — pickup
        inv.setItem(SLOT_PICKUP, make(Material.BARRIER,
                color("&cPick Up Shop"),
                List.of(color("&7Returns all items + bank balance to you"),
                        color("&7Removes chest and hologram"),
                        color("&4Click to pick up"))));
    }

    // -----------------------------------------------------------------------
    // Click handling
    // -----------------------------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getWhoClicked().getUniqueId().equals(player.getUniqueId())) return;
        if (event.getInventory() != inv) return;

        int slot = event.getRawSlot();

        // Clicks in player's own inventory (slots 54+) are allowed normally
        if (slot >= 54) return;

        event.setCancelled(true);

        if (slot == SLOT_ITEM) {
            handleSetItem(event.getCursor());
        } else {
            switch (slot) {
                case SLOT_PRICE    -> handleSetPrice();
                case SLOT_MODE     -> handleToggleMode();
                case SLOT_DEPOSIT  -> handleDeposit();
                case SLOT_WITHDRAW -> handleWithdraw();
                case SLOT_CHEST    -> handleOpenChest();
                case SLOT_PICKUP   -> handlePickup();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Slot handlers
    // -----------------------------------------------------------------------

    /**
     * Item slot — the player clicks with an item on their cursor.
     * We COPY the item (type + all NBT) without consuming it from the cursor,
     * so keys or items with special NBT data can be set as the shop item freely.
     * The shop always trades in qty-1 copies; the cursor is left untouched.
     */
    private void handleSetItem(ItemStack cursor) {
        if (cursor != null && cursor.getType() != Material.AIR) {
            // Clone the cursor item — preserves all NBT, enchants, PDC, display name, etc.
            // Force amount = 1 so the shop trades one-at-a-time.
            ItemStack toSet = cursor.clone();
            toSet.setAmount(1);
            shop.setItem(toSet);
            // Do NOT clear the cursor — the player keeps their item.
            dirty();
            plugin.getShopManager().refreshHologram(shop);
            player.sendMessage(color("&aShop item set to &f" + itemName(toSet) + "&a."));
        } else {
            // Empty cursor — just clear the shop's template (it was only a copy)
            if (shop.getItem() != null) {
                shop.setItem(null);
                dirty();
                player.sendMessage(color("&7Shop item cleared."));
            }
        }
        // Refresh on next tick so inventory state is stable
        plugin.getServer().getScheduler().runTaskLater(plugin, this::fill, 1L);
    }

    private void handleSetPrice() {
        awaitChat("&6Enter the new price:", input -> {
            try {
                double price = Double.parseDouble(input.trim());
                if (price < 0) { player.sendMessage(color("&cPrice must be 0 or higher.")); return; }
                shop.setPrice(price);
                dirty();
                player.sendMessage(color("&aPrice set to &f" + fmt(price) + "&a."));
            } catch (NumberFormatException e) {
                player.sendMessage(color("&cInvalid number. Price not changed."));
            }
        });
    }

    private void handleToggleMode() {
        shop.setMode(shop.getMode() == ShopMode.BUY ? ShopMode.SELL : ShopMode.BUY);
        dirty();
        plugin.getShopManager().refreshHologram(shop);
        fill();
    }

    private void handleDeposit() {
        awaitChat("&6Enter amount to deposit into the shop bank:", input -> {
            try {
                double amount = Double.parseDouble(input.trim());
                if (amount <= 0) { player.sendMessage(color("&cAmount must be positive.")); return; }
                boolean ok = plugin.getShopService().depositToShopBank(player, shop, amount);
                if (ok) {
                    player.sendMessage(color("&aDeposited &f" + fmt(amount) + "&a into the shop bank."));
                } else {
                    // Show actual balance for debugging
                    Optional<CurrencyProvider> prov = plugin.getCurrencyRegistry()
                            .getProvider(shop.getCurrencyId());
                    String balStr = prov.map(p -> fmt(p.getBalance(player))).orElse("?");
                    player.sendMessage(color("&cFailed to deposit. Your balance: &f" + balStr
                            + "&c, requested: &f" + fmt(amount)));
                }
            } catch (NumberFormatException e) {
                player.sendMessage(color("&cInvalid number."));
            }
        });
    }

    private void handleWithdraw() {
        awaitChat("&6Enter amount to withdraw from the shop bank:", input -> {
            try {
                double amount = Double.parseDouble(input.trim());
                if (amount <= 0) { player.sendMessage(color("&cAmount must be positive.")); return; }
                boolean ok = plugin.getShopService().withdrawFromShopBank(player, shop, amount);
                if (ok) {
                    player.sendMessage(color("&aWithdrew &f" + fmt(amount) + "&a from the shop bank."));
                } else {
                    player.sendMessage(color("&cNot enough in the shop bank. Balance: &f"
                            + fmt(shop.getBankBalance())));
                }
            } catch (NumberFormatException e) {
                player.sendMessage(color("&cInvalid number."));
            }
        });
    }

    private void handleOpenChest() {
        HandlerList.unregisterAll(this);
        player.closeInventory();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Stock is stored virtually in the Shop object (6 rows = 54 slots)
            Inventory virtualInv = Bukkit.createInventory(null, 54, color("&8Shop Stock"));
            ItemStack[] stock = shop.getStockContents();
            if (stock != null) virtualInv.setContents(stock);

            // Sync virtual → shop on close, then reopen editor
            Listener closeSync = new Listener() {
                @EventHandler
                public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
                    if (!e.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
                    if (e.getInventory() != virtualInv) return;
                    HandlerList.unregisterAll(this);
                    org.bukkit.inventory.ItemStack[] before = shop.getStockContents();
                    org.bukkit.inventory.ItemStack[] after  = e.getInventory().getContents().clone();
                    logStockDiff(before, after);
                    shop.setStockContents(after);
                    plugin.getShopManager().markDirty(shop);
                    plugin.getShopManager().refreshHologram(shop);
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                            () -> new ShopEditorGUI(plugin, player, shop).open(), 1L);
                }
            };
            plugin.getServer().getPluginManager().registerEvents(closeSync, plugin);
            player.openInventory(virtualInv);
        }, 1L);
    }

    private void handlePickup() {
        HandlerList.unregisterAll(this);
        player.closeInventory();
        boolean ok = plugin.getShopService().pickupShop(player, shop);
        player.sendMessage(ok
                ? color("&aShop picked up! Items and bank balance returned to you.")
                : color("&cYou don't have permission to pick up this shop."));
    }

    // -----------------------------------------------------------------------
    // Chat input helper
    // -----------------------------------------------------------------------

    private void awaitChat(String prompt, java.util.function.Consumer<String> callback) {
        awaitingChat = true;
        HandlerList.unregisterAll(this);
        player.closeInventory();
        player.sendMessage(color(prompt));
        player.sendMessage(color("&7Type in chat, or type &ccancel &7to abort."));

        ChatInputListener listener = new ChatInputListener(plugin, player, input -> {
            if (input.trim().equalsIgnoreCase("cancel")) {
                player.sendMessage(color("&7Cancelled."));
            } else {
                callback.accept(input);
            }
            // Reopen the GUI after chat
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> new ShopEditorGUI(plugin, player, shop).open(), 1L);
        });
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    // -----------------------------------------------------------------------
    // Close
    // -----------------------------------------------------------------------

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
        if (event.getInventory() != inv) return;
        if (!awaitingChat) {
            HandlerList.unregisterAll(this);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void logStockDiff(org.bukkit.inventory.ItemStack[] before,
                              org.bukkit.inventory.ItemStack[] after) {
        // Count items added and removed by type
        java.util.Map<String, Integer> delta = new java.util.LinkedHashMap<>();
        int len = Math.max(before != null ? before.length : 0, after != null ? after.length : 0);
        for (int i = 0; i < len; i++) {
            org.bukkit.inventory.ItemStack b = (before != null && i < before.length) ? before[i] : null;
            org.bukkit.inventory.ItemStack a = (after  != null && i < after.length)  ? after[i]  : null;
            int bAmt = (b != null && b.getType() != org.bukkit.Material.AIR) ? b.getAmount() : 0;
            int aAmt = (a != null && a.getType() != org.bukkit.Material.AIR) ? a.getAmount() : 0;
            if (bAmt == aAmt) continue;
            String key = itemLabel(a != null ? a : b);
            delta.merge(key, aAmt - bAmt, Integer::sum);
        }
        for (java.util.Map.Entry<String, Integer> e : delta.entrySet()) {
            int diff = e.getValue();
            if (diff > 0) plugin.getTransactionLogger().logStockAdd(player, shop, e.getKey(), diff);
            else if (diff < 0) plugin.getTransactionLogger().logStockRemove(player, shop, e.getKey(), -diff);
        }
    }

    private static String itemLabel(org.bukkit.inventory.ItemStack item) {
        if (item == null) return "AIR";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return item.getItemMeta().getDisplayName().replaceAll("§.", "");
        return item.getType().name();
    }

    private void dirty() { plugin.getShopManager().markDirty(shop); }

    private String getCurrencyName() {
        return plugin.getCurrencyRegistry()
                .getProvider(shop.getCurrencyId())
                .map(CurrencyProvider::getDisplayName)
                .orElse(shop.getCurrencyId());
    }

    private static ItemStack make(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String itemName(ItemStack item) {
        if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return item.getItemMeta().getDisplayName();
        return item != null ? item.getType().name() : "none";
    }

    private static String color(String s) { return s.replace("&", "\u00A7"); }

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }

}
