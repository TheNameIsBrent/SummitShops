package com.oneblock.shops.listeners;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.gui.ShopEditorGUI;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.economy.CurrencyProvider;
import com.oneblock.shops.shop.*;
import com.oneblock.shops.util.ShopItemFactory;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
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
        Location loc  = event.getBlockPlaced().getLocation();

        // Must be in the configured island world
        String islandWorld = plugin.getConfig().getString("island-world", "SummitWorld");
        if (!loc.getWorld().getName().equals(islandWorld)) {
            event.setCancelled(true);
            player.sendMessage(msg("wrong-world"));
            return;
        }

        // Must be on an island the player is a member of (SSB2 island protection normally
        // handles this, but we double-check here so the shop is never registered off-island)
        try {
            com.bgsoftware.superiorskyblock.api.island.Island island =
                    com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI.getGrid().getIslandAt(loc);
            if (island == null) {
                event.setCancelled(true);
                player.sendMessage(msg("not-your-island"));
                return;
            }
            com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer sp =
                    com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI.getPlayer(player);
            if (!island.isMember(sp)) {
                event.setCancelled(true);
                player.sendMessage(msg("not-your-island"));
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[ShopListener] SSB2 island check failed on place: " + e.getMessage());
        }

        Shop shop = shopService.createShop(player, loc);
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
        event.getPlayer().sendMessage(msg("shop-break-hint"));
    }

    /** Prevent TNT / other block explosions from destroying shop blocks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> isShopBlock(b) && shopManager.getAtLocation(b.getLocation()).isPresent());
    }

    /** Prevent entity explosions (creepers, TNT entities, etc.) from destroying shop blocks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> isShopBlock(b) && shopManager.getAtLocation(b.getLocation()).isPresent());
    }

    /**
     * When entities are unloaded/cleared (e.g. /kill, entity-clear plugin),
     * re-spawn holograms for any shops in the affected chunk area.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        // Check if any of the unloaded entities are our display entities (tagged with PDC)
        org.bukkit.NamespacedKey textKey = new org.bukkit.NamespacedKey(plugin, "shop_hologram_id");
        org.bukkit.NamespacedKey itemKey = new org.bukkit.NamespacedKey(plugin, "shop_item_display_id");
        boolean anyShopEntity = event.getEntities().stream().anyMatch(e ->
                (e instanceof org.bukkit.entity.TextDisplay || e instanceof org.bukkit.entity.ItemDisplay)
                && (e.getPersistentDataContainer().has(textKey, org.bukkit.persistence.PersistentDataType.STRING)
                 || e.getPersistentDataContainer().has(itemKey, org.bukkit.persistence.PersistentDataType.STRING)));
        if (!anyShopEntity) return;
        // Reschedule hologram recreation for all shops in all loaded chunks
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Shop shop : shopManager.getAllShops()) {
                hologramService.createOrUpdate(shop);
            }
        }, 5L);
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
        String prefix = plugin.getConfig().getString("prefix", "&8[&6Shop&8] &r");
        String text   = plugin.getConfig().getString("messages." + key, "&c[" + key + "]");
        return color(prefix + text);
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
