package com.oneblock.shops.shop;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.economy.CurrencyProvider;
import com.oneblock.shops.economy.CurrencyRegistry;
import com.oneblock.shops.util.ItemUtils;
import com.oneblock.shops.util.ShopItemFactory;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Handles all shop transaction logic:
 * <ul>
 *   <li>Island membership validation</li>
 *   <li>Stock checks</li>
 *   <li>Currency transfers</li>
 *   <li>Item transfers</li>
 * </ul>
 *
 * <p>All methods must be called on the main thread because they interact
 * with Bukkit inventories. Economy calls are synchronous here but wrapped
 * asynchronously where possible in the listener.</p>
 */
public class ShopService {

    private final OneBlockShopsPlugin plugin;
    private final ShopManager shopManager;
    private final CurrencyRegistry currencyRegistry;

    public ShopService(OneBlockShopsPlugin plugin,
                       ShopManager shopManager,
                       CurrencyRegistry currencyRegistry) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.currencyRegistry = currencyRegistry;
    }

    // -----------------------------------------------------------------------
    // Island access check
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the given player is a member (or owner)
     * of the island that owns this shop.
     */
    public boolean isIslandMember(Player player, Shop shop) {
        if (shop.getIslandId() == null) return false;
        try {
            SuperiorPlayer superiorPlayer =
                    SuperiorSkyblockAPI.getPlayer(player);
            Island playerIsland = superiorPlayer.getIsland();
            if (playerIsland == null) return false;
            return shop.getIslandId().equals(playerIsland.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().warning("SSB2 island check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Resolves the SuperiorSkyblock2 island UUID for the given location.
     * Returns {@code null} if the location is not on any island.
     */
    public java.util.UUID resolveIslandId(Location loc) {
        try {
            Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(loc);
            return island != null ? island.getUniqueId() : null;
        } catch (Exception e) {
            plugin.getLogger().warning("SSB2 getIslandAt failed: " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Transaction: BUY  (player pays → receives item from chest)
    // -----------------------------------------------------------------------

    /**
     * Executes a BUY transaction: player spends currency and receives one unit
     * of the shop item from the chest.
     *
     * @param player the buyer
     * @param shop   the shop
     * @return the result code
     */
    public TransactionResult executeBuy(Player player, Shop shop) {
        if (!shop.isConfigured()) return TransactionResult.NOT_CONFIGURED;
        if (!isIslandMember(player, shop)) return TransactionResult.NOT_ISLAND_MEMBER;

        // Get chest inventory
        Chest chest = getChest(shop);
        if (chest == null) return TransactionResult.CHEST_MISSING;
        Inventory chestInv = chest.getInventory();

        // Check stock
        ItemStack shopItem = shop.getItem();
        int available = ItemUtils.countMatching(chestInv, shopItem);
        if (available < shopItem.getAmount()) return TransactionResult.OUT_OF_STOCK;

        // Check player inventory space
        if (!ItemUtils.hasSpace(player.getInventory(), shopItem)) {
            return TransactionResult.PLAYER_INVENTORY_FULL;
        }

        // Resolve currency
        Optional<CurrencyProvider> providerOpt =
                currencyRegistry.getProvider(shop.getCurrencyId());
        if (providerOpt.isEmpty()) return TransactionResult.CURRENCY_UNAVAILABLE;
        CurrencyProvider provider = providerOpt.get();

        // Check player balance
        if (!provider.has(player, shop.getPrice())) {
            return TransactionResult.INSUFFICIENT_FUNDS;
        }

        // ─── Atomic-ish transfer ─────────────────────────────────────────
        // 1. Withdraw from buyer
        if (!provider.withdraw(player, shop.getPrice())) {
            return TransactionResult.ERROR;
        }

        // 2. Remove item from chest
        ItemUtils.removeOne(chestInv, shopItem);

        // 3. Give item to player
        player.getInventory().addItem(shopItem.clone());

        // 4. Credit owner (best-effort; owner may be offline)
        depositToOwner(shop, provider);

        // 5. Update hologram
        shopManager.markDirty(shop);

        return TransactionResult.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Transaction: SELL  (player receives currency → gives item to chest)
    // -----------------------------------------------------------------------

    /**
     * Executes a SELL transaction: player delivers one unit of the shop item
     * to the chest and receives currency.
     *
     * @param player the seller
     * @param shop   the shop
     * @return the result code
     */
    public TransactionResult executeSell(Player player, Shop shop) {
        if (!shop.isConfigured()) return TransactionResult.NOT_CONFIGURED;
        if (!isIslandMember(player, shop)) return TransactionResult.NOT_ISLAND_MEMBER;

        // Get chest
        Chest chest = getChest(shop);
        if (chest == null) return TransactionResult.CHEST_MISSING;
        Inventory chestInv = chest.getInventory();

        // Check chest space
        ItemStack shopItem = shop.getItem();
        if (!ItemUtils.hasSpace(chestInv, shopItem)) {
            return TransactionResult.SHOP_FULL;
        }

        // Check player has the item
        if (ItemUtils.countMatching(player.getInventory(), shopItem) < shopItem.getAmount()) {
            return TransactionResult.OUT_OF_STOCK; // reused: player has no matching item
        }

        // Resolve currency
        Optional<CurrencyProvider> providerOpt =
                currencyRegistry.getProvider(shop.getCurrencyId());
        if (providerOpt.isEmpty()) return TransactionResult.CURRENCY_UNAVAILABLE;
        CurrencyProvider provider = providerOpt.get();

        // Withdraw from island owner / shop (the shop pays the seller)
        // In a SELL shop the chest owner wants items, so they pre-fund the shop.
        // We withdraw from the owner. If the owner can't pay, deny.
        java.util.UUID ownerUuid = shop.getOwnerUUID();
        org.bukkit.OfflinePlayer ownerOffline =
                plugin.getServer().getOfflinePlayer(ownerUuid);
        if (!provider.hasOffline(ownerOffline, shop.getPrice())) {
            return TransactionResult.INSUFFICIENT_FUNDS; // shop owner can't pay
        }

        // ─── Atomic-ish transfer ─────────────────────────────────────────
        // 1. Deduct from owner
        if (!provider.withdrawOffline(ownerOffline, shop.getPrice())) {
            return TransactionResult.ERROR;
        }

        // 2. Remove item from player
        ItemUtils.removeOne(player.getInventory(), shopItem);

        // 3. Add item to chest
        chestInv.addItem(shopItem.clone());

        // 4. Credit seller
        provider.deposit(player, shop.getPrice());

        // 5. Update hologram
        shopManager.markDirty(shop);

        return TransactionResult.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Shop creation
    // -----------------------------------------------------------------------

    /**
     * Creates a shop at the given location for the given player.
     * The island ID is resolved automatically from the location.
     *
     * @return the new Shop, or {@code null} if not on an island
     */
    public Shop createShop(Player player, Location loc) {
        java.util.UUID islandId = resolveIslandId(loc);
        if (islandId == null) return null;

        String defaultCurrency = plugin.getConfig().getString("default-currency", "vault_money");
        Shop shop = new Shop(player.getUniqueId(), loc, islandId, defaultCurrency);
        shopManager.addShop(shop);
        return shop;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Chest getChest(Shop shop) {
        Location loc = shop.getBlockLocation();
        if (loc == null) return null;
        Block block = loc.getBlock();
        if (block.getType() != Material.CHEST &&
                block.getType() != Material.TRAPPED_CHEST) {
            return null;
        }
        return (Chest) block.getState();
    }

    /** Credits the shop owner; handles both online and offline players. */
    private void depositToOwner(Shop shop, CurrencyProvider provider) {
        Player onlineOwner = plugin.getServer().getPlayer(shop.getOwnerUUID());
        if (onlineOwner != null && onlineOwner.isOnline()) {
            provider.deposit(onlineOwner, shop.getPrice());
        } else {
            org.bukkit.OfflinePlayer offlineOwner =
                    plugin.getServer().getOfflinePlayer(shop.getOwnerUUID());
            provider.depositOffline(offlineOwner, shop.getPrice());
        }
    }

    /**
     * Returns the stock count for a shop:
     * how many matching items are in the chest (BUY mode) or
     * how many more can fit (SELL mode).
     */
    public int getStockCount(Shop shop) {
        if (!shop.isConfigured()) return 0;
        Chest chest = getChest(shop);
        if (chest == null) return 0;
        Inventory inv = chest.getInventory();

        if (shop.getMode() == ShopMode.BUY) {
            return ItemUtils.countMatching(inv, shop.getItem());
        } else {
            return ItemUtils.countAvailableSpace(inv, shop.getItem());
        }
    }
}
