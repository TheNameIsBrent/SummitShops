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
 * 6-row chest GUI for managing a shop.
 *
 * Slot layout:
 *   10 — Set shop item (click with item in hand)
 *   13 — Set price (chat input)
 *   16 — Toggle BUY / SELL mode
 *   28 — Cycle currency
 *   31 — Deposit to shop bank (chat input)
 *   34 — Bank balance display
 *   40 — Open chest inventory
 *   49 — Pick up shop
 */
public class ShopEditorGUI implements Listener {

    private static final int SLOT_ITEM     = 10;
    private static final int SLOT_PRICE    = 13;
    private static final int SLOT_MODE     = 16;
    private static final int SLOT_CURRENCY = 28;
    private static final int SLOT_DEPOSIT  = 31;
    private static final int SLOT_BANK     = 34;
    private static final int SLOT_CHEST    = 40;
    private static final int SLOT_PICKUP   = 49;

    private final OneBlockShopsPlugin plugin;
    private final Player player;
    private final Shop shop;
    private Inventory inv;

    // When true the GUI closed because we're waiting for chat — don't unregister
    private boolean awaitingChat = false;

    public ShopEditorGUI(OneBlockShopsPlugin plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop   = shop;
    }

    public void open() {
        awaitingChat = false;
        inv = Bukkit.createInventory(null, 54, color("&8Shop Manager"));
        refresh();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        player.openInventory(inv);
    }

    private void refresh() {
        if (inv == null) return;
        ItemStack filler = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Slot 10 — shop item
        if (shop.getItem() != null) {
            ItemStack display = shop.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(color("&7Hold a new item and click to change"));
            lore.add(color("&7Click empty-handed to clear"));
            meta.setLore(lore);
            display.setItemMeta(meta);
            inv.setItem(SLOT_ITEM, display);
        } else {
            inv.setItem(SLOT_ITEM, makeItem(Material.ITEM_FRAME,
                    color("&eSet Shop Item"),
                    List.of(color("&7Hold an item and click this slot"))));
        }

        // Slot 13 — price
        inv.setItem(SLOT_PRICE, makeItem(Material.GOLD_NUGGET,
                color("&6Price: &f" + fmt(shop.getPrice())),
                List.of(color("&7Click to change"))));

        // Slot 16 — mode
        if (shop.getMode() == ShopMode.BUY) {
            inv.setItem(SLOT_MODE, makeItem(Material.EMERALD,
                    color("&aMode: BUY"),
                    List.of(color("&7Players buy items from this chest"),
                            color("&eClick to switch to SELL"))));
        } else {
            inv.setItem(SLOT_MODE, makeItem(Material.REDSTONE,
                    color("&cMode: SELL"),
                    List.of(color("&7Players sell items into this chest"),
                            color("&eClick to switch to BUY"))));
        }

        // Slot 28 — currency
        String currDisplay = plugin.getCurrencyRegistry()
                .getProvider(shop.getCurrencyId())
                .map(CurrencyProvider::getDisplayName)
                .orElse(shop.getCurrencyId());
        List<String> currLore = new ArrayList<>();
        for (CurrencyProvider cp : plugin.getCurrencyRegistry().getAllProviders()) {
            boolean sel = cp.getCurrencyId().equals(shop.getCurrencyId());
            currLore.add(color((sel ? "&a▶ " : "&7  ") + cp.getDisplayName()));
        }
        currLore.add("");
        currLore.add(color("&eClick to cycle"));
        inv.setItem(SLOT_CURRENCY, makeItem(Material.SUNFLOWER,
                color("&eCurrency: " + currDisplay), currLore));

        // Slot 31 — deposit
        inv.setItem(SLOT_DEPOSIT, makeItem(Material.EMERALD_BLOCK,
                color("&aDeposit to Bank"),
                List.of(color("&7Transfer your funds into the shop bank"),
                        color("&7The bank pays customers in SELL mode"),
                        color("&eClick to deposit"))));

        // Slot 34 — bank balance (info only)
        inv.setItem(SLOT_BANK, makeItem(Material.GOLD_INGOT,
                color("&6Bank Balance: &f" + fmt(shop.getBankBalance())),
                List.of(color("&7Currency: " + currDisplay),
                        color("&7Returned to you on pickup"))));

        // Slot 40 — open chest inventory
        inv.setItem(SLOT_CHEST, makeItem(Material.CHEST,
                color("&eManage Chest Inventory"),
                List.of(color("&7Open the chest to add/remove stock"))));

        // Slot 49 — pickup
        inv.setItem(SLOT_PICKUP, makeItem(Material.BARRIER,
                color("&cPick Up Shop"),
                List.of(color("&7Returns all items + bank balance to you"),
                        color("&7Removes the chest and hologram"),
                        color("&4Click to pick up"))));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getWhoClicked().getUniqueId().equals(player.getUniqueId())) return;
        if (event.getInventory() != inv) return;

        int slot = event.getRawSlot();

        // Only slot 10 has special handling — everything else is cancelled
        if (slot >= 54) return; // clicked in player inventory — allow

        event.setCancelled(true);

        switch (slot) {
            case SLOT_ITEM     -> handleSetItem();
            case SLOT_PRICE    -> handleSetPrice();
            case SLOT_MODE     -> handleToggleMode();
            case SLOT_CURRENCY -> handleCycleCurrency();
            case SLOT_DEPOSIT  -> handleDeposit();
            case SLOT_CHEST    -> handleOpenChest();
            case SLOT_PICKUP   -> handlePickup();
        }
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    private void handleSetItem() {
        // Check what the player is holding in their hand
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR || hand == null) {
            // Return current item if there is one
            if (shop.getItem() != null) {
                player.getInventory().addItem(shop.getItem().clone());
                shop.setItem(null);
                shopDirty();
                player.sendMessage(color("&7Shop item cleared."));
                refresh();
            }
        } else {
            shop.setItem(hand.clone());
            shopDirty();
            player.sendMessage(color("&aShop item set to &f" + itemName(hand) + "&a."));
            refresh();
        }
    }

    private void handleSetPrice() {
        closeForChat("&6Enter the new price:");
        awaitChat(input -> {
            try {
                double price = Double.parseDouble(input.trim());
                if (price < 0) {
                    player.sendMessage(color("&cPrice must be 0 or higher."));
                } else {
                    shop.setPrice(price);
                    shopDirty();
                    player.sendMessage(color("&aPrice set to &f" + fmt(price) + "&a."));
                }
            } catch (NumberFormatException e) {
                player.sendMessage(color("&cInvalid number."));
            }
            reopenGUI();
        });
    }

    private void handleToggleMode() {
        shop.setMode(shop.getMode() == ShopMode.BUY ? ShopMode.SELL : ShopMode.BUY);
        shopDirty();
        refresh();
    }

    private void handleCycleCurrency() {
        List<String> ids = plugin.getCurrencyRegistry().getCurrencyIds();
        if (ids.isEmpty()) return;
        int idx = ids.indexOf(shop.getCurrencyId());
        shop.setCurrencyId(ids.get((idx + 1) % ids.size()));
        shopDirty();
        refresh();
    }

    private void handleDeposit() {
        closeForChat("&6Enter amount to deposit into the shop bank:");
        awaitChat(input -> {
            try {
                double amount = Double.parseDouble(input.trim());
                if (amount <= 0) {
                    player.sendMessage(color("&cAmount must be positive."));
                } else {
                    boolean ok = plugin.getShopService().depositToShopBank(player, shop, amount);
                    if (ok) {
                        player.sendMessage(color("&aDeposited &f" + fmt(amount) + "&a into the shop bank."));
                    } else {
                        player.sendMessage(color("&cNot enough funds to deposit that amount."));
                    }
                }
            } catch (NumberFormatException e) {
                player.sendMessage(color("&cInvalid number."));
            }
            reopenGUI();
        });
    }

    private void handleOpenChest() {
        // Close GUI and open the real chest inventory
        HandlerList.unregisterAll(this);
        player.closeInventory();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            org.bukkit.block.Block block = shop.getBlockLocation() != null
                    ? shop.getBlockLocation().getBlock() : null;
            if (block != null && (block.getType() == Material.CHEST
                    || block.getType() == Material.TRAPPED_CHEST)) {
                org.bukkit.block.Chest chest = (org.bukkit.block.Chest) block.getState();
                player.openInventory(chest.getInventory());
            } else {
                player.sendMessage(color("&cChest not found at shop location."));
            }
        }, 1L);
    }

    private void handlePickup() {
        HandlerList.unregisterAll(this);
        player.closeInventory();
        boolean ok = plugin.getShopService().pickupShop(player, shop);
        if (ok) {
            player.sendMessage(color("&aShop picked up! Items and bank balance returned to you."));
        } else {
            player.sendMessage(color("&cYou don't have permission to pick up this shop."));
        }
    }

    // -----------------------------------------------------------------------
    // Chat input helpers
    // -----------------------------------------------------------------------

    private void closeForChat(String prompt) {
        awaitingChat = true;
        HandlerList.unregisterAll(this);
        player.closeInventory();
        player.sendMessage(color(prompt));
        player.sendMessage(color("&7Type your answer in chat. Type &ccancel &7to abort."));
    }

    private void awaitChat(java.util.function.Consumer<String> callback) {
        ChatInputListener listener = new ChatInputListener(plugin, player, input -> {
            if (input.trim().equalsIgnoreCase("cancel")) {
                player.sendMessage(color("&7Cancelled."));
                reopenGUI();
                return;
            }
            callback.accept(input);
        });
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    private void reopenGUI() {
        // Small delay so inventory close/open doesn't conflict
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            new ShopEditorGUI(plugin, player, shop).open();
        }, 2L);
    }

    // -----------------------------------------------------------------------
    // Inventory close
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

    private void shopDirty() {
        plugin.getShopManager().markDirty(shop);
    }

    private static ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String itemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return item.getItemMeta().getDisplayName();
        return item.getType().name();
    }

    private static String color(String s) { return s.replace("&", "\u00A7"); }

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
