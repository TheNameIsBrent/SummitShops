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
import java.util.UUID;

/**
 * A 3-row chest GUI (27 slots) for editing a shop's configuration.
 *
 * <pre>
 *  Slot layout (27 total):
 *  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┐
 *  │  │  │11│  │13│  │15│  │  │  row 1 (slots 0–8)
 *  ├──┼──┼──┼──┼──┼──┼──┼──┼──┤
 *  │  │  │  │  │  │  │  │  │  │  row 2 (slots 9–17) — padding
 *  ├──┼──┼──┼──┼──┼──┼──┼──┼──┤
 *  │  │  │  │22│  │  │  │  │  │  row 3 (slots 18–26)
 *  └──┴──┴──┴──┴──┴──┴──┴──┴──┘
 *
 *  11 → Item selector
 *  13 → Price editor (chat input after clicking)
 *  15 → Mode toggle
 *  22 → Currency selector (cycles through registered currencies)
 * </pre>
 */
public class ShopEditorGUI implements Listener {

    private static final int SLOT_ITEM     = 11;
    private static final int SLOT_PRICE    = 13;
    private static final int SLOT_MODE     = 15;
    private static final int SLOT_CURRENCY = 22;

    private final OneBlockShopsPlugin plugin;
    private final Player player;
    private final Shop shop;
    private final Inventory inv;

    /** Whether we are waiting for the player to type a price in chat. */
    private boolean awaitingPrice = false;

    public ShopEditorGUI(OneBlockShopsPlugin plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop   = shop;
        this.inv    = Bukkit.createInventory(null, 27,
                colorize("&8Shop Editor"));

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        refresh();
    }

    // -----------------------------------------------------------------------
    // Open / Close
    // -----------------------------------------------------------------------

    /** Opens the GUI for the player. */
    public void open() {
        player.openInventory(inv);
    }

    /** Saves the shop and unregisters this listener. */
    private void close() {
        HandlerList.unregisterAll(this);
        plugin.getShopManager().markDirty(shop);
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    /** Rebuilds the entire GUI from the current shop state. */
    public void refresh() {
        // Fill background with gray glass
        ItemStack filler = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }

        // Slot 11: Current item or placeholder
        if (shop.getItem() != null) {
            inv.setItem(SLOT_ITEM, shop.getItem().clone());
        } else {
            inv.setItem(SLOT_ITEM, makeItem(Material.PAPER,
                    colorize("&eClick to set item"),
                    List.of(colorize("&7Put an item in this slot"))));
        }

        // Slot 13: Price
        inv.setItem(SLOT_PRICE, makeItem(Material.GOLD_NUGGET,
                colorize("&6Price: &f" + formatPrice(shop.getPrice())),
                List.of(colorize("&7Click to change price"),
                        colorize("&7Current: &e" + formatPrice(shop.getPrice())))));

        // Slot 15: Mode toggle
        if (shop.getMode() == ShopMode.BUY) {
            inv.setItem(SLOT_MODE, makeItem(Material.EMERALD,
                    colorize("&aMode: BUY"),
                    List.of(colorize("&7Customers buy items FROM chest"),
                            colorize("&eClick to switch to SELL"))));
        } else {
            inv.setItem(SLOT_MODE, makeItem(Material.REDSTONE,
                    colorize("&cMode: SELL"),
                    List.of(colorize("&7Customers sell items INTO chest"),
                            colorize("&eClick to switch to BUY"))));
        }

        // Slot 22: Currency selector
        List<CurrencyProvider> currencies =
                new ArrayList<>(plugin.getCurrencyRegistry().getAllProviders());
        String currentDisplay = plugin.getCurrencyRegistry()
                .getProvider(shop.getCurrencyId())
                .map(CurrencyProvider::getDisplayName)
                .orElse(shop.getCurrencyId());

        inv.setItem(SLOT_CURRENCY, makeItem(Material.SUNFLOWER,
                colorize("&eCurrency: " + currentDisplay),
                buildCurrencyLore(currencies)));
    }

    private List<String> buildCurrencyLore(List<CurrencyProvider> currencies) {
        List<String> lore = new ArrayList<>();
        lore.add(colorize("&7Click to cycle currencies:"));
        for (CurrencyProvider cp : currencies) {
            boolean selected = cp.getCurrencyId().equals(shop.getCurrencyId());
            lore.add(colorize((selected ? "&a▶ " : "&7  ") + cp.getDisplayName()));
        }
        return lore;
    }

    // -----------------------------------------------------------------------
    // Click handling
    // -----------------------------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getWhoClicked().getUniqueId().equals(player.getUniqueId())) return;
        if (!event.getInventory().equals(inv)) return;

        event.setCancelled(true); // prevent item theft by default

        int slot = event.getRawSlot();

        // Allow player to place an item into slot 11 (the item selector)
        if (slot == SLOT_ITEM) {
            handleItemSlotClick(event);
            return;
        }
        if (slot == SLOT_PRICE) {
            handlePriceClick();
            return;
        }
        if (slot == SLOT_MODE) {
            handleModeToggle();
            return;
        }
        if (slot == SLOT_CURRENCY) {
            handleCurrencyCycle();
            return;
        }
    }

    /** Item slot: if player has an item on cursor, set it as the shop item. */
    private void handleItemSlotClick(InventoryClickEvent event) {
        event.setCancelled(false); // allow the click
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack cursor = event.getCursor();
            ItemStack current = inv.getItem(SLOT_ITEM);

            if (cursor != null && cursor.getType() != Material.AIR) {
                // Player is placing an item — use it as the shop item
                shop.setItem(cursor.clone());
                cursor.setAmount(0);
                event.setCursor(new ItemStack(Material.AIR));
            } else if (current != null && current.getType() != Material.AIR
                    && !current.equals(makeItem(Material.PAPER,
                        colorize("&eClick to set item"), null))) {
                // Return the old item to the player
                if (shop.getItem() != null) {
                    player.getInventory().addItem(shop.getItem().clone());
                    shop.setItem(null);
                }
            }
            refresh();
        });
    }

    /** Opens an anvil-like chat input for price entry. */
    private void handlePriceClick() {
        player.closeInventory();
        awaitingPrice = true;
        player.sendMessage(colorize("&6Enter the new price in chat (e.g. &f100.0&6):"));

        // Register a one-shot chat listener
        ChatInputListener chatListener =
                new ChatInputListener(plugin, player, this::onPriceInput);
        plugin.getServer().getPluginManager()
                .registerEvents(chatListener, plugin);
    }

    void onPriceInput(String input) {
        try {
            double price = Double.parseDouble(input.trim());
            if (price < 0) {
                player.sendMessage(colorize("&cPrice cannot be negative."));
            } else {
                shop.setPrice(price);
                player.sendMessage(colorize("&aPrice set to &f" + formatPrice(price) + "&a."));
            }
        } catch (NumberFormatException e) {
            player.sendMessage(colorize("&cInvalid price: " + input));
        }
        refresh();
        open();
    }

    private void handleModeToggle() {
        shop.setMode(shop.getMode() == ShopMode.BUY ? ShopMode.SELL : ShopMode.BUY);
        refresh();
    }

    private void handleCurrencyCycle() {
        List<String> ids = plugin.getCurrencyRegistry().getCurrencyIds();
        if (ids.isEmpty()) return;
        int current = ids.indexOf(shop.getCurrencyId());
        int next = (current + 1) % ids.size();
        shop.setCurrencyId(ids.get(next));
        refresh();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
        if (!event.getInventory().equals(inv)) return;
        if (!awaitingPrice) {
            // Only close/save if not transitioning to chat input
            close();
        }
        awaitingPrice = false;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String colorize(String s) {
        return s.replace("&", "\u00A7");
    }

    private static String formatPrice(double price) {
        if (price == (long) price) return String.valueOf((long) price);
        return String.format("%.2f", price);
    }
}
