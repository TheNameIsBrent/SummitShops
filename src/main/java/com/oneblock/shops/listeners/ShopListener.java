package com.oneblock.shops.listeners;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.gui.ShopEditorGUI;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.shop.*;
import com.oneblock.shops.util.ShopItemFactory;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * Central event listener.
 *
 * <p>Handles:</p>
 * <ul>
 *   <li>Shop block placement (places the chest, creates shop)</li>
 *   <li>Shop block break (removes shop + hologram)</li>
 *   <li>Right-click on shop chest → opens GUI editor</li>
 *   <li>Left-click on shop chest → executes BUY/SELL transaction</li>
 *   <li>Inventory move prevention (hoppers, etc.)</li>
 * </ul>
 */
public class ShopListener implements Listener {

    private final OneBlockShopsPlugin plugin;
    private final ShopManager shopManager;
    private final ShopService shopService;
    private final HologramService hologramService;

    public ShopListener(OneBlockShopsPlugin plugin, ShopManager shopManager,
                        ShopService shopService, HologramService hologramService) {
        this.plugin          = plugin;
        this.shopManager     = shopManager;
        this.shopService     = shopService;
        this.hologramService = hologramService;
    }

    // -----------------------------------------------------------------------
    // Placement — convert shop item into a real shop
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!ShopItemFactory.isShopItem(item)) return;

        Player player = event.getPlayer();
        Block block   = event.getBlockPlaced();

        // Validate island membership at placement location
        UUID islandId = shopService.resolveIslandId(block.getLocation());
        if (islandId == null) {
            player.sendMessage(msg("not-island-member"));
            event.setCancelled(true);
            return;
        }

        // Create the shop
        Shop shop = new Shop(player.getUniqueId(), block.getLocation(),
                islandId, plugin.getConfig().getString("default-currency", "vault_money"));
        shopManager.addShop(shop);
        player.sendMessage(msg("shop-created"));
    }

    // -----------------------------------------------------------------------
    // Break — remove shop when chest is broken
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST)
            return;

        Optional<Shop> shopOpt = shopManager.getAtLocation(block.getLocation());
        if (shopOpt.isEmpty()) return;

        Shop shop    = shopOpt.get();
        Player player = event.getPlayer();

        // Only island members may break it
        if (!shopService.isIslandMember(player, shop)) {
            player.sendMessage(msg("not-island-member"));
            event.setCancelled(true);
            return;
        }

        shopManager.removeShop(shop.getId());
        player.sendMessage(msg("shop-removed"));
    }

    // -----------------------------------------------------------------------
    // Interaction — right-click = GUI, left-click = transaction
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Ignore off-hand clicks to prevent double-firing
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST)
            return;

        Optional<Shop> shopOpt = shopManager.getAtLocation(block.getLocation());
        if (shopOpt.isEmpty()) return;

        Shop shop    = shopOpt.get();
        Player player = event.getPlayer();

        event.setCancelled(true); // prevent normal chest open

        // Check permission to use shops
        if (!player.hasPermission("oneblockshops.use")) {
            player.sendMessage(msg("no-permission"));
            return;
        }

        // Check island membership
        if (!shopService.isIslandMember(player, shop)) {
            player.sendMessage(msg("not-island-member"));
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            // Open editor GUI (only island members — already checked)
            new ShopEditorGUI(plugin, player, shop).open();

        } else if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            // Execute transaction
            TransactionResult result;
            if (shop.getMode() == ShopMode.BUY) {
                result = shopService.executeBuy(player, shop);
            } else {
                result = shopService.executeSell(player, shop);
            }
            handleTransactionResult(player, shop, result);
        }
    }

    // -----------------------------------------------------------------------
    // Prevent hoppers / droppers from interacting with shop chests
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        // If source or destination is a shop chest, cancel
        if (event.getSource().getLocation() != null &&
                shopManager.isShopAt(event.getSource().getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (event.getDestination().getLocation() != null &&
                shopManager.isShopAt(event.getDestination().getLocation())) {
            event.setCancelled(true);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void handleTransactionResult(Player player, Shop shop,
                                          TransactionResult result) {
        switch (result) {
            case SUCCESS -> {
                // Message is sent by ShopService caller; nothing extra needed.
                // The hologram is already updated via markDirty inside ShopService.
                if (shop.getMode() == ShopMode.BUY) {
                    player.sendMessage(msg("buy-success")
                            .replace("{amount}", String.valueOf(shop.getItem().getAmount()))
                            .replace("{item}", getItemName(shop))
                            .replace("{price}", formatPrice(shop.getPrice()))
                            .replace("{currency}", shop.getCurrencyId()));
                } else {
                    player.sendMessage(msg("sell-success")
                            .replace("{amount}", String.valueOf(shop.getItem().getAmount()))
                            .replace("{item}", getItemName(shop))
                            .replace("{price}", formatPrice(shop.getPrice()))
                            .replace("{currency}", shop.getCurrencyId()));
                }
            }
            case NOT_CONFIGURED    -> player.sendMessage(msg("shop-not-configured"));
            case NOT_ISLAND_MEMBER -> player.sendMessage(msg("not-island-member"));
            case OUT_OF_STOCK      -> player.sendMessage(msg("not-enough-stock"));
            case SHOP_FULL         -> player.sendMessage(msg("shop-full"));
            case INSUFFICIENT_FUNDS-> player.sendMessage(msg("not-enough-funds")
                    .replace("{currency}", shop.getCurrencyId()));
            case PLAYER_INVENTORY_FULL -> player.sendMessage(msg("inventory-full"));
            case CHEST_MISSING     -> player.sendMessage(colorize("&cThe shop chest is missing!"));
            case CURRENCY_UNAVAILABLE -> player.sendMessage(colorize("&cCurrency system unavailable."));
            case ERROR             -> player.sendMessage(colorize("&cAn internal error occurred."));
        }
    }

    private String msg(String key) {
        String raw = plugin.getConfig().getString("messages." + key, "&c[Missing message: " + key + "]");
        return colorize(raw);
    }

    private static String colorize(String s) {
        return s.replace("&", "\u00A7");
    }

    private static String getItemName(Shop shop) {
        if (shop.getItem() == null) return "Unknown";
        if (shop.getItem().hasItemMeta() && shop.getItem().getItemMeta().hasDisplayName()) {
            return shop.getItem().getItemMeta().getDisplayName();
        }
        return shop.getItem().getType().name();
    }

    private static String formatPrice(double price) {
        if (price == (long) price) return String.valueOf((long) price);
        return String.format("%.2f", price);
    }
}
