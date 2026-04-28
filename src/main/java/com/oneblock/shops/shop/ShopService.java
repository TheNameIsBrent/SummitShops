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
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.currencyRegistry = currencyRegistry;
    }

    // -----------------------------------------------------------------------
    // Island checks — only used for owner actions (manage, pickup)
    // -----------------------------------------------------------------------

    public boolean isShopOwner(Player player, Shop shop) {
        return player.getUniqueId().equals(shop.getOwnerUUID());
    }

    public boolean isIslandMember(Player player, Shop shop) {
        // Always allow if player is the shop owner
        if (isShopOwner(player, shop)) return true;
        if (shop.getIslandId() == null) return false;
        try {
            SuperiorPlayer sp = SuperiorSkyblockAPI.getPlayer(player);
            Island island = sp.getIsland();
            if (island == null) return false;
            return shop.getIslandId().equals(island.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().warning("SSB2 island check failed: " + e.getMessage());
            // If SSB2 fails, fall back to owner-only check
            return isShopOwner(player, shop);
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
    // BUY — any player pays from their own wallet, gets item from chest
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
        CurrencyProvider provider = provOpt.get();

        if (!provider.has(player, shop.getPrice()))
            return TransactionResult.INSUFFICIENT_FUNDS;

        // Withdraw from buyer
        if (!provider.withdraw(player, shop.getPrice())) return TransactionResult.ERROR;

        // Remove item from chest, give to buyer
        ItemUtils.removeOne(chestInv, shopItem);
        player.getInventory().addItem(shopItem.clone());

        // Deposit into shop bank
        shop.depositToBank(shop.getPrice());
        shopManager.markDirty(shop);

        return TransactionResult.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // SELL — any player gives item to chest, shop bank pays them
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
        CurrencyProvider provider = provOpt.get();

        // Shop bank must have enough to pay the seller
        if (shop.getBankBalance() < shop.getPrice())
            return TransactionResult.INSUFFICIENT_FUNDS;

        if (!shop.withdrawFromBank(shop.getPrice())) return TransactionResult.ERROR;

        // Move item from seller to chest
        ItemUtils.removeOne(player.getInventory(), shopItem);
        chestInv.addItem(shopItem.clone());

        // Pay the seller
        provider.deposit(player, shop.getPrice());

        shopManager.markDirty(shop);
        return TransactionResult.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Pickup — returns items + bank balance to owner, removes shop
    // -----------------------------------------------------------------------

    public boolean pickupShop(Player player, Shop shop) {
        if (!isIslandMember(player, shop)) return false;

        Optional<CurrencyProvider> provOpt = currencyRegistry.getProvider(shop.getCurrencyId());

        // Return bank balance to owner
        if (shop.getBankBalance() > 0 && provOpt.isPresent()) {
            provOpt.get().deposit(player, shop.getBankBalance());
            shop.setBankBalance(0);
        }

        // Return chest contents to player
        Chest chest = getChest(shop);
        if (chest != null) {
            Inventory inv = chest.getInventory();
            for (ItemStack stack : inv.getStorageContents()) {
                if (stack != null && stack.getType() != Material.AIR) {
                    player.getInventory().addItem(stack.clone());
                }
            }
            inv.clear();
            chest.getBlock().setType(Material.AIR);
        }

        // Remove shop and hologram
        shopManager.removeShop(shop.getId());
        return true;
    }

    // -----------------------------------------------------------------------
    // Deposit into shop bank — called from GUI
    // -----------------------------------------------------------------------

    public boolean depositToShopBank(Player player, Shop shop, double amount) {
        Optional<CurrencyProvider> provOpt = currencyRegistry.getProvider(shop.getCurrencyId());
        if (provOpt.isEmpty()) return false;
        CurrencyProvider provider = provOpt.get();
        if (!provider.has(player, amount)) return false;
        provider.withdraw(player, amount);
        shop.depositToBank(amount);
        shopManager.markDirty(shop);
        return true;
    }

    // -----------------------------------------------------------------------
    // Shop creation
    // -----------------------------------------------------------------------

    public Shop createShop(Player player, Location loc) {
        UUID islandId = resolveIslandId(loc);
        // islandId may be null if SSB2 fails — shop still gets created
        String defaultCurrency = plugin.getConfig().getString("default-currency", "money");
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
        if (shop.getMode() == ShopMode.BUY) {
            return ItemUtils.countMatching(inv, shop.getItem());
        } else {
            return ItemUtils.countAvailableSpace(inv, shop.getItem());
        }
    }
}
