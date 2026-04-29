package com.oneblock.shops.listeners;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.gui.ShopEditorGUI;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.economy.CurrencyProvider;
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
    // Place shop item → register shop, open editor
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!ShopItemFactory.isShopItem(item)) return;

        Player player = event.getPlayer();
        Shop shop = shopService.createShop(player, event.getBlockPlaced().getLocation());
        player.sendMessage(msg("shop-created"));

        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> new ShopEditorGUI(plugin, player, shop).open(), 1L);
    }

    // -----------------------------------------------------------------------
    // Break — block it, tell player to use pickup
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isShopBlock(block)) return;
        if (shopManager.getAtLocation(block.getLocation()).isEmpty()) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(color("&cRight-click the shop to manage it, then use &4Pick Up Shop&c."));
    }

    // -----------------------------------------------------------------------
    // Interact
    //   RIGHT-CLICK: owner → editor; others → transaction
    //   LEFT-CLICK:  transaction for everyone
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || !isShopBlock(block)) return;

        Optional<Shop> shopOpt = shopManager.getAtLocation(block.getLocation());
        if (shopOpt.isEmpty()) return;

        event.setCancelled(true);

        Shop shop   = shopOpt.get();
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (!player.hasPermission("oneblockshops.use")) {
            player.sendMessage(msg("no-permission"));
            return;
        }

        if (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) {
            if (shopService.isIslandMember(player, shop)) {
                // Island members (owner + co-members) always open the editor
                new ShopEditorGUI(plugin, player, shop).open();
            } else {
                // Everyone else transacts
                doTransaction(player, shop);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Block hoppers/droppers
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (isShopInventory(event.getSource()) || isShopInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void doTransaction(Player player, Shop shop) {
        TransactionResult result = shop.getMode() == ShopMode.BUY
                ? shopService.executeBuy(player, shop)
                : shopService.executeSell(player, shop);
        handleResult(player, shop, result);
    }

    private void handleResult(Player player, Shop shop, TransactionResult result) {
        switch (result) {
            case SUCCESS -> {
                String tpl = shop.getMode() == ShopMode.BUY ? msg("buy-success") : msg("sell-success");
                String currDisplay = plugin.getCurrencyRegistry()
                        .getProvider(shop.getCurrencyId())
                        .map(p -> color(p.getDisplayName()))
                        .orElse(shop.getCurrencyId());
                player.sendMessage(tpl
                        .replace("{amount}", String.valueOf(shop.getItem() != null ? shop.getItem().getAmount() : 1))
                        .replace("{item}", itemName(shop))
                        .replace("{price}", fmt(shop.getPrice()))
                        .replace("{currency}", currDisplay));
            }
            case NOT_CONFIGURED        -> player.sendMessage(msg("shop-not-configured"));
            case OUT_OF_STOCK          -> player.sendMessage(msg("not-enough-stock"));
            case SHOP_FULL             -> player.sendMessage(msg("shop-full"));
            case INSUFFICIENT_FUNDS    -> player.sendMessage(msg("not-enough-money"));
            case SHOP_NO_FUNDS         -> player.sendMessage(msg("shop-no-funds"));
            case PLAYER_NO_STOCK       -> player.sendMessage(msg("no-items-to-sell"));
            case PLAYER_INVENTORY_FULL -> player.sendMessage(msg("inventory-full"));
            case CHEST_MISSING         -> player.sendMessage(color("&cShop chest is missing."));
            case CURRENCY_UNAVAILABLE  -> player.sendMessage(color("&cCurrency system unavailable."));
            case NOT_ISLAND_MEMBER     -> player.sendMessage(msg("not-island-member"));
            case ERROR                 -> player.sendMessage(color("&cAn internal error occurred."));
        }
    }

    private boolean isShopBlock(Block block) {
        return block.getType() == Material.END_PORTAL_FRAME;
    }

    private boolean isShopInventory(org.bukkit.inventory.Inventory inv) {
        return inv.getLocation() != null && shopManager.isShopAt(inv.getLocation());
    }

    private String msg(String key) {
        return color(plugin.getConfig().getString("messages." + key, "&c[" + key + "]"));
    }

    private static String color(String s) { return s.replace("&", "\u00A7"); }

    private static String itemName(Shop shop) {
        if (shop.getItem() == null) return "?";
        if (shop.getItem().hasItemMeta() && shop.getItem().getItemMeta().hasDisplayName())
            return shop.getItem().getItemMeta().getDisplayName();
        return shop.getItem().getType().name();
    }

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
