package com.oneblock.shops.listeners;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.gui.ShopEditorGUI;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.shop.*;
import com.oneblock.shops.util.ShopItemFactory;
import org.bukkit.Material;
import org.bukkit.block.Block;
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
    // Placement — placing a shop item creates a shop and opens the editor
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!ShopItemFactory.isShopItem(item)) return;

        Player player = event.getPlayer();
        Block block   = event.getBlockPlaced();

        Shop shop = shopService.createShop(player, block.getLocation());
        player.sendMessage(msg("shop-created"));

        // Open editor on the next tick so the block is placed first
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                new ShopEditorGUI(plugin, player, shop).open(), 1L);
    }

    // -----------------------------------------------------------------------
    // Break — prevent breaking shop chests; must use pickup button
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) return;

        Optional<Shop> shopOpt = shopManager.getAtLocation(block.getLocation());
        if (shopOpt.isEmpty()) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(color("&cRight-click the shop to manage it, then use &4Pick Up Shop&c to remove it."));
    }

    // -----------------------------------------------------------------------
    // Interaction — right-click opens manager (owner), left-click transacts
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) return;

        Optional<Shop> shopOpt = shopManager.getAtLocation(block.getLocation());
        if (shopOpt.isEmpty()) return;

        event.setCancelled(true);

        Shop shop     = shopOpt.get();
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (!player.hasPermission("oneblockshops.use")) {
            player.sendMessage(msg("no-permission"));
            return;
        }

        if (action == Action.RIGHT_CLICK_BLOCK) {
            // Owner/island member opens the editor
            if (shopService.isIslandMember(player, shop)) {
                new ShopEditorGUI(plugin, player, shop).open();
            } else {
                // Non-member right-click = attempt to buy
                if (shop.getMode() == ShopMode.BUY) {
                    handleResult(player, shop, shopService.executeBuy(player, shop));
                } else {
                    handleResult(player, shop, shopService.executeSell(player, shop));
                }
            }
        } else if (action == Action.LEFT_CLICK_BLOCK) {
            // Left-click = transaction for everyone
            if (shop.getMode() == ShopMode.BUY) {
                handleResult(player, shop, shopService.executeBuy(player, shop));
            } else {
                handleResult(player, shop, shopService.executeSell(player, shop));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Prevent hoppers/droppers from moving items in/out of shop chests
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
    // Transaction result messages
    // -----------------------------------------------------------------------

    private void handleResult(Player player, Shop shop, TransactionResult result) {
        switch (result) {
            case SUCCESS -> {
                String template = shop.getMode() == ShopMode.BUY
                        ? msg("buy-success") : msg("sell-success");
                player.sendMessage(template
                        .replace("{amount}", String.valueOf(shop.getItem().getAmount()))
                        .replace("{item}", getItemName(shop))
                        .replace("{price}", fmt(shop.getPrice()))
                        .replace("{currency}", shop.getCurrencyId()));
            }
            case NOT_CONFIGURED        -> player.sendMessage(msg("shop-not-configured"));
            case OUT_OF_STOCK          -> player.sendMessage(msg("not-enough-stock"));
            case SHOP_FULL             -> player.sendMessage(msg("shop-full"));
            case INSUFFICIENT_FUNDS    -> player.sendMessage(msg("not-enough-funds")
                    .replace("{currency}", shop.getCurrencyId()));
            case PLAYER_INVENTORY_FULL -> player.sendMessage(msg("inventory-full"));
            case CHEST_MISSING         -> player.sendMessage(color("&cShop chest is missing!"));
            case CURRENCY_UNAVAILABLE  -> player.sendMessage(color("&cCurrency system unavailable."));
            case NOT_ISLAND_MEMBER     -> player.sendMessage(msg("not-island-member"));
            case ERROR                 -> player.sendMessage(color("&cAn internal error occurred."));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String msg(String key) {
        return color(plugin.getConfig().getString("messages." + key,
                "&c[Missing message: " + key + "]"));
    }

    private static String color(String s) { return s.replace("&", "\u00A7"); }

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
