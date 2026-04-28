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

/**
 * 6-row chest GUI (54 slots) for managing a shop.
 *
 * Layout (row 6 = controls):
 *   Slot 10 — Set Item (click with item on cursor to set)
 *   Slot 13 — Set Price (chat input)
 *   Slot 16 — Toggle Mode
 *   Slot 28 — Currency selector
 *   Slot 31 — Deposit currency (chat input)
 *   Slot 34 — Bank balance display
 *   Slot 49 — Pick Up Shop
 */
public class ShopEditorGUI implements Listener {

    private static final int SLOT_ITEM     = 10;
    private static final int SLOT_PRICE    = 13;
    private static final int SLOT_MODE     = 16;
    private static final int SLOT_CURRENCY = 28;
    private static final int SLOT_DEPOSIT  = 31;
    private static final int SLOT_BANK     = 34;
    private static final int SLOT_PICKUP   = 49;

    private final OneBlockShopsPlugin plugin;
    private final Player player;
    private final Shop shop;
    private final Inventory inv;

    private boolean awaitingInput = false;

    public ShopEditorGUI(OneBlockShopsPlugin plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop   = shop;
        this.inv    = Bukkit.createInventory(null, 54, colorize("&8Shop Manager"));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        refresh();
    }

    public void open() {
        player.openInventory(inv);
    }

    private void close() {
        HandlerList.unregisterAll(this);
        plugin.getShopManager().markDirty(shop);
    }

    public void refresh() {
        ItemStack filler = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Slot 10 — current item or set-item prompt
        if (shop.getItem() != null) {
            ItemStack display = shop.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(colorize("&7Click with a new item to change"));
            lore.add(colorize("&7Click empty-handed to clear"));
            meta.setLore(lore);
            display.setItemMeta(meta);
            inv.setItem(SLOT_ITEM, display);
        } else {
            inv.setItem(SLOT_ITEM, makeItem(Material.ITEM_FRAME,
                    colorize("&eSet Shop Item"),
                    List.of(colorize("&7Click while holding an item"))));
        }

        // Slot 13 — price
        inv.setItem(SLOT_PRICE, makeItem(Material.GOLD_NUGGET,
                colorize("&6Price: &f" + fmt(shop.getPrice())),
                List.of(colorize("&7Click to change price"))));

        // Slot 16 — mode
        if (shop.getMode() == ShopMode.BUY) {
            inv.setItem(SLOT_MODE, makeItem(Material.EMERALD,
                    colorize("&aMode: BUY"),
                    List.of(colorize("&7Customers buy items from chest"),
                            colorize("&eClick to switch to SELL"))));
        } else {
            inv.setItem(SLOT_MODE, makeItem(Material.REDSTONE,
                    colorize("&cMode: SELL"),
                    List.of(colorize("&7Customers sell items into chest"),
                            colorize("&eClick to switch to BUY"))));
        }

        // Slot 28 — currency
        String currDisplay = plugin.getCurrencyRegistry()
                .getProvider(shop.getCurrencyId())
                .map(CurrencyProvider::getDisplayName)
                .orElse(shop.getCurrencyId());
        List<String> currLore = new ArrayList<>();
        currLore.add(colorize("&7Click to cycle currencies:"));
        for (CurrencyProvider cp : plugin.getCurrencyRegistry().getAllProviders()) {
            boolean sel = cp.getCurrencyId().equals(shop.getCurrencyId());
            currLore.add(colorize((sel ? "&a▶ " : "&7  ") + cp.getDisplayName()));
        }
        inv.setItem(SLOT_CURRENCY, makeItem(Material.SUNFLOWER,
                colorize("&eCurrency: " + currDisplay), currLore));

        // Slot 31 — deposit button
        inv.setItem(SLOT_DEPOSIT, makeItem(Material.EMERALD,
                colorize("&aDeposit Currency"),
                List.of(colorize("&7Add funds to the shop bank"),
                        colorize("&7Used to pay customers (SELL mode)"),
                        colorize("&eClick to deposit"))));

        // Slot 34 — bank balance display
        inv.setItem(SLOT_BANK, makeItem(Material.GOLD_INGOT,
                colorize("&6Shop Bank: &f" + fmt(shop.getBankBalance())),
                List.of(colorize("&7Currency: " + currDisplay),
                        colorize("&7Used to pay sellers in SELL mode"),
                        colorize("&7Returned to you on pickup"))));

        // Slot 49 — pickup
        inv.setItem(SLOT_PICKUP, makeItem(Material.BARRIER,
                colorize("&cPick Up Shop"),
                List.of(colorize("&7Removes the shop and chest"),
                        colorize("&7Returns all items + bank balance to you"),
                        colorize("&4Click to confirm pickup"))));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getWhoClicked().getUniqueId().equals(player.getUniqueId())) return;
        if (!event.getInventory().equals(inv)) return;

        int slot = event.getRawSlot();

        // Allow item placement only in slot 10
        if (slot == SLOT_ITEM) {
            event.setCancelled(true);
            handleItemSlot(event);
            return;
        }

        // Cancel all other clicks inside the GUI
        if (slot < 54) {
            event.setCancelled(true);
            switch (slot) {
                case SLOT_PRICE    -> handlePriceClick();
                case SLOT_MODE     -> handleModeToggle();
                case SLOT_CURRENCY -> handleCurrencyCycle();
                case SLOT_DEPOSIT  -> handleDepositClick();
                case SLOT_PICKUP   -> handlePickup();
            }
        }
    }

    private void handleItemSlot(InventoryClickEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack cursor = player.getItemOnCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                // Set item from cursor
                shop.setItem(cursor.clone());
                player.setItemOnCursor(new ItemStack(Material.AIR));
                player.sendMessage(colorize("&aShop item set to &f" + cursor.getType().name() + "&a."));
            } else if (shop.getItem() != null) {
                // Return the current item to the player
                player.getInventory().addItem(shop.getItem().clone());
                shop.setItem(null);
                player.sendMessage(colorize("&7Shop item cleared."));
            }
            refresh();
        });
    }

    private void handlePriceClick() {
        player.closeInventory();
        awaitingInput = true;
        player.sendMessage(colorize("&6Enter the new price in chat:"));
        ChatInputListener listener = new ChatInputListener(plugin, player, input -> {
            try {
                double price = Double.parseDouble(input.trim());
                if (price < 0) {
                    player.sendMessage(colorize("&cPrice cannot be negative."));
                } else {
                    shop.setPrice(price);
                    player.sendMessage(colorize("&aPrice set to &f" + fmt(price) + "&a."));
                }
            } catch (NumberFormatException e) {
                player.sendMessage(colorize("&cInvalid number: " + input));
            }
            refresh();
            open();
        });
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    private void handleModeToggle() {
        shop.setMode(shop.getMode() == ShopMode.BUY ? ShopMode.SELL : ShopMode.BUY);
        refresh();
    }

    private void handleCurrencyCycle() {
        List<String> ids = plugin.getCurrencyRegistry().getCurrencyIds();
        if (ids.isEmpty()) return;
        int idx = ids.indexOf(shop.getCurrencyId());
        shop.setCurrencyId(ids.get((idx + 1) % ids.size()));
        refresh();
    }

    private void handleDepositClick() {
        player.closeInventory();
        awaitingInput = true;
        player.sendMessage(colorize("&6Enter amount to deposit into the shop bank:"));
        ChatInputListener listener = new ChatInputListener(plugin, player, input -> {
            try {
                double amount = Double.parseDouble(input.trim());
                if (amount <= 0) {
                    player.sendMessage(colorize("&cAmount must be positive."));
                } else {
                    var provOpt = plugin.getCurrencyRegistry().getProvider(shop.getCurrencyId());
                    if (provOpt.isEmpty()) {
                        player.sendMessage(colorize("&cCurrency unavailable."));
                    } else if (!provOpt.get().has(player, amount)) {
                        player.sendMessage(colorize("&cYou don't have enough funds."));
                    } else {
                        provOpt.get().withdraw(player, amount);
                        shop.depositToBank(amount);
                        player.sendMessage(colorize("&aDeposited &f" + fmt(amount) + "&a into the shop bank."));
                    }
                }
            } catch (NumberFormatException e) {
                player.sendMessage(colorize("&cInvalid number: " + input));
            }
            refresh();
            open();
        });
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    private void handlePickup() {
        player.closeInventory();
        boolean success = plugin.getShopService().pickupShop(player, shop);
        if (success) {
            player.sendMessage(colorize("&aShop picked up! Items and bank balance returned to your inventory."));
        } else {
            player.sendMessage(colorize("&cYou don't have permission to pick up this shop."));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
        if (!event.getInventory().equals(inv)) return;
        if (!awaitingInput) close();
        awaitingInput = false;
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

    private static String colorize(String s) { return s.replace("&", "\u00A7"); }
    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
