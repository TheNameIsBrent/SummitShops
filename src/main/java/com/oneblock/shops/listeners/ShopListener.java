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
    // Placement — shop item chest -> register shop
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!ShopItemFactory.isShopItem(item)) return;

        Player player = event.getPlayer();
        Block block   = event.getBlockPlaced();

        // Resolve island
        java.util.UUID islandId = shopService.resolveIslandId(block.getLocation());
        if (islandId == null) {
            player.sendMessage(msg("not-island-member"));
            event.setCancelled(true);
            return;
        }

        Shop shop = new Shop(player.getUniqueId(), block.getLocation(),
                islandId, plugin.getConfig().getString("default-currency", "vault_money"));
        shopManager.addShop(shop);
        player.sendMessage(msg("shop-created"));

        // Immediately open the editor so the owner can configure it
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                new ShopEditorGUI(plugin, player, shop).open(), 1L);
    }

    // -----------------------------------------------------------------------
    // Break — cancel; owner must use pickup button instead
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) return;

        Optional<Shop> shopOpt = shopManager.getAtLocation(block.getLocation());
        if (shopOpt.isEmpty()) return;

        // Always cancel breaking a shop chest — use the GUI pickup button instead
        event.setCancelled(true);
        event.getPlayer().sendMessage(colorize("&cUse the shop manager to pick up this shop."));
    }

    // -----------------------------------------------------------------------
    // Interaction
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) return;

        Optional<Shop> shopOpt = shopManager.getAtLocation(block.getLocation());
        if (shopOpt.isEmpty()) return;

        Shop shop    = shopOpt.get();
        Player player = event.getPlayer();
        event.setCancelled(true);

        if (!player.hasPermission("oneblockshops.use")) {
            player.sendMessage(msg("no-permission"));
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            // Owner (island member) opens the editor; others get a read-only view via transaction
            if (shopService.isIslandMember(player, shop)) {
                new ShopEditorGUI(plugin, player, shop).open();
            } else {
                player.sendMessage(colorize("&cThis shop belongs to another island."));
            }

        } else if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (!shopService.isIslandMember(player, shop)) {
                player.sendMessage(msg("not-island-member"));
                return;
            }
            TransactionResult result = shop.getMode() == ShopMode.BUY
                    ? shopService.executeBuy(player, shop)
                    : shopService.executeSell(player, shop);
            handleResult(player, shop, result);
        }
    }

    // -----------------------------------------------------------------------
    // Prevent hopper/dropper interaction with shop chests
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
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

    private void handleResult(Player player, Shop shop, TransactionResult result) {
        switch (result) {
            case SUCCESS -> {
                if (shop.getMode() == ShopMode.BUY) {
                    player.sendMessage(msg("buy-success")
                            .replace("{amount}", String.valueOf(shop.getItem().getAmount()))
                            .replace("{item}", getItemName(shop))
                            .replace("{price}", fmt(shop.getPrice()))
                            .replace("{currency}", shop.getCurrencyId()));
                } else {
                    player.sendMessage(msg("sell-success")
                            .replace("{amount}", String.valueOf(shop.getItem().getAmount()))
                            .replace("{item}", getItemName(shop))
                            .replace("{price}", fmt(shop.getPrice()))
                            .replace("{currency}", shop.getCurrencyId()));
                }
            }
            case NOT_CONFIGURED       -> player.sendMessage(msg("shop-not-configured"));
            case NOT_ISLAND_MEMBER    -> player.sendMessage(msg("not-island-member"));
            case OUT_OF_STOCK         -> player.sendMessage(msg("not-enough-stock"));
            case SHOP_FULL            -> player.sendMessage(msg("shop-full"));
            case INSUFFICIENT_FUNDS   -> player.sendMessage(msg("not-enough-funds")
                    .replace("{currency}", shop.getCurrencyId()));
            case PLAYER_INVENTORY_FULL-> player.sendMessage(msg("inventory-full"));
            case CHEST_MISSING        -> player.sendMessage(colorize("&cShop chest is missing!"));
            case CURRENCY_UNAVAILABLE -> player.sendMessage(colorize("&cCurrency system unavailable."));
            case ERROR                -> player.sendMessage(colorize("&cAn internal error occurred."));
        }
    }

    private String msg(String key) {
        return colorize(plugin.getConfig().getString("messages." + key,
                "&c[Missing message: " + key + "]"));
    }

    private static String colorize(String s) { return s.replace("&", "\u00A7"); }

    private static String getItemName(Shop shop) {
        if (shop.getItem() == null) return "Unknown";
        if (shop.getItem().hasItemMeta() && shop.getItem().getItemMeta().hasDisplayName())
            return shop.getItem().getItemMeta().getDisplayName();
        return shop.getItem().getType().name();
    }

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
