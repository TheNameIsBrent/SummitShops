package com.oneblock.shops.shop;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.economy.CurrencyProvider;
import com.oneblock.shops.economy.CurrencyRegistry;
import com.oneblock.shops.util.ItemUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

public class ShopService {

    private final OneBlockShopsPlugin plugin;
    private final ShopManager shopManager;
    private final CurrencyRegistry currencyRegistry;

    public ShopService(OneBlockShopsPlugin plugin, ShopManager shopManager, CurrencyRegistry currencyRegistry) {
        this.plugin           = plugin;
        this.shopManager      = shopManager;
        this.currencyRegistry = currencyRegistry;
    }

    // -----------------------------------------------------------------------
    // Ownership / island checks
    // -----------------------------------------------------------------------

    public boolean isShopOwner(Player player, Shop shop) {
        return player.getUniqueId().equals(shop.getOwnerUUID());
    }

    /**
     * Returns true if the player is the shop owner OR an island member.
     * Falls back to owner-only if SSB2 throws.
     */
    public boolean isIslandMember(Player player, Shop shop) {
        if (isShopOwner(player, shop)) return true;
        try {
            SuperiorPlayer sp = SuperiorSkyblockAPI.getPlayer(player);
            Island island = sp.getIsland();
            if (island == null) return false;
            if (shop.getIslandId() == null) return false;
            return shop.getIslandId().equals(island.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().warning("SSB2 check failed: " + e.getMessage());
            return false;
        }
    }

    public UUID resolveIslandId(Location loc) {
        try {
            Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(loc);
            return island != null ? island.getUniqueId() : null;
        } catch (Exception e) {
            plugin.getLogger().warning("SSB2 getIslandAt failed: " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // BUY — player pays, receives item from chest
    // -----------------------------------------------------------------------

    public TransactionResult executeBuy(Player player, Shop shop) {
        if (!shop.isConfigured()) return TransactionResult.NOT_CONFIGURED;

        Chest chest = getChest(shop);
        if (chest == null) return TransactionResult.CHEST_MISSING;
        Inventory chestInv = chest.getInventory();

        ItemStack shopItem = shop.getItem();
        if (ItemUtils.countMatching(chestInv, shopItem) < shopItem.getAmount())
            return TransactionResult.OUT_OF_STOCK;

        if (!ItemUtils.hasSpace(player.getInventory(), shopItem))
            return TransactionResult.PLAYER_INVENTORY_FULL;

        Optional<CurrencyProvider> provOpt = currencyRegistry.getProvider(shop.getCurrencyId());
        if (provOpt.isEmpty()) return TransactionResult.CURRENCY_UNAVAILABLE;
        CurrencyProvider prov = provOpt.get();

        if (!prov.has(player, shop.getPrice())) return TransactionResult.INSUFFICIENT_FUNDS;
        if (!prov.withdraw(player, shop.getPrice())) return TransactionResult.ERROR;

        ItemUtils.removeOne(chestInv, shopItem);
        player.getInventory().addItem(shopItem.clone());
        shop.depositToBank(shop.getPrice());
        shopManager.markDirty(shop);

        return TransactionResult.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // SELL — player gives item, shop bank pays them
    // -----------------------------------------------------------------------

    public TransactionResult executeSell(Player player, Shop shop) {
        if (!shop.isConfigured()) return TransactionResult.NOT_CONFIGURED;

        Chest chest = getChest(shop);
        if (chest == null) return TransactionResult.CHEST_MISSING;
        Inventory chestInv = chest.getInventory();

        ItemStack shopItem = shop.getItem();
        if (!ItemUtils.hasSpace(chestInv, shopItem)) return TransactionResult.SHOP_FULL;

        if (ItemUtils.countMatching(player.getInventory(), shopItem) < shopItem.getAmount())
            return TransactionResult.OUT_OF_STOCK;

        Optional<CurrencyProvider> provOpt = currencyRegistry.getProvider(shop.getCurrencyId());
        if (provOpt.isEmpty()) return TransactionResult.CURRENCY_UNAVAILABLE;
        CurrencyProvider prov = provOpt.get();

        if (shop.getBankBalance() < shop.getPrice()) return TransactionResult.INSUFFICIENT_FUNDS;
        if (!shop.withdrawFromBank(shop.getPrice())) return TransactionResult.ERROR;

        ItemUtils.removeOne(player.getInventory(), shopItem);
        chestInv.addItem(shopItem.clone());
        prov.deposit(player, shop.getPrice());
        shopManager.markDirty(shop);

        return TransactionResult.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Deposit into shop bank
    // -----------------------------------------------------------------------

    public boolean depositToShopBank(Player player, Shop shop, double amount) {
        if (amount <= 0) return false;
        Optional<CurrencyProvider> provOpt = currencyRegistry.getProvider(shop.getCurrencyId());
        if (provOpt.isEmpty()) {
            plugin.getLogger().warning("No provider for currency: " + shop.getCurrencyId());
            return false;
        }
        CurrencyProvider prov = provOpt.get();
        double balance = prov.getBalance(player);
        plugin.getLogger().info("Deposit attempt: player=" + player.getName()
                + " balance=" + balance + " amount=" + amount);
        if (!prov.has(player, amount)) return false;
        if (!prov.withdraw(player, amount)) return false;
        shop.depositToBank(amount);
        shopManager.markDirty(shop);
        return true;
    }

    // -----------------------------------------------------------------------
    // Pickup
    // -----------------------------------------------------------------------

    public boolean pickupShop(Player player, Shop shop) {
        if (!isIslandMember(player, shop)) return false;

        Optional<CurrencyProvider> provOpt = currencyRegistry.getProvider(shop.getCurrencyId());
        if (shop.getBankBalance() > 0 && provOpt.isPresent()) {
            provOpt.get().deposit(player, shop.getBankBalance());
            shop.setBankBalance(0);
        }

        Chest chest = getChest(shop);
        if (chest != null) {
            Inventory inv = chest.getInventory();
            // Return all stocked items to the player
            for (ItemStack stack : inv.getStorageContents()) {
                if (stack != null && stack.getType() != Material.AIR)
                    player.getInventory().addItem(stack.clone());
            }
            inv.clear();
            // Remove the physical chest block and give back the tagged shop item
            chest.getBlock().setType(Material.AIR);
            player.getInventory().addItem(
                    com.oneblock.shops.util.ShopItemFactory.createShopItem(plugin));
        }

        shopManager.removeShop(shop.getId());
        return true;
    }

    // -----------------------------------------------------------------------
    // Shop creation
    // -----------------------------------------------------------------------

    public Shop createShop(Player player, Location loc) {
        UUID islandId = resolveIslandId(loc);
        String defaultCurrency = plugin.getConfig().getString("default-currency", "vault_money");
        Shop shop = new Shop(player.getUniqueId(), loc, islandId, defaultCurrency);
        shopManager.addShop(shop);
        return shop;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public Chest getChest(Shop shop) {
        Location loc = shop.getBlockLocation();
        if (loc == null) return null;
        Block block = loc.getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) return null;
        return (Chest) block.getState();
    }

    public int getStockCount(Shop shop) {
        if (!shop.isConfigured()) return 0;
        Chest chest = getChest(shop);
        if (chest == null) return 0;
        Inventory inv = chest.getInventory();
        return shop.getMode() == ShopMode.BUY
                ? ItemUtils.countMatching(inv, shop.getItem())
                : ItemUtils.countAvailableSpace(inv, shop.getItem());
    }
}
