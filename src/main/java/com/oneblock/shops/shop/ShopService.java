package com.oneblock.shops.shop;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.economy.CurrencyProvider;
import com.oneblock.shops.economy.CurrencyRegistry;
import com.oneblock.shops.util.ItemUtils;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.Material;
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
        if (shop.getIslandId() == null) return true; // no island attached, allow all
        try {
            // Look up the island the shop belongs to, then check if this player is a member.
            Island shopIsland = SuperiorSkyblockAPI.getGrid().getIslandByUUID(shop.getIslandId());
            if (shopIsland == null) return true; // island gone, allow access
            SuperiorPlayer sp = SuperiorSkyblockAPI.getPlayer(player);
            return shopIsland.isMember(sp);
        } catch (Exception e) {
            plugin.getLogger().warning("SSB2 island member check failed: " + e.getMessage());
            return true; // fail open so players aren't silently locked out
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

        // Use virtual stock inventory stored in the shop object
        org.bukkit.inventory.Inventory stockInv = getStockInventory(shop);

        ItemStack shopItem = shop.getItem();
        if (ItemUtils.countMatching(stockInv, shopItem) < shopItem.getAmount())
            return TransactionResult.OUT_OF_STOCK;

        if (!ItemUtils.hasSpace(player.getInventory(), shopItem))
            return TransactionResult.PLAYER_INVENTORY_FULL;

        Optional<CurrencyProvider> provOpt = currencyRegistry.getProvider(shop.getCurrencyId());
        if (provOpt.isEmpty()) return TransactionResult.CURRENCY_UNAVAILABLE;
        CurrencyProvider prov = provOpt.get();

        if (!prov.has(player, shop.getPrice())) return TransactionResult.INSUFFICIENT_FUNDS;
        if (!prov.withdraw(player, shop.getPrice())) return TransactionResult.ERROR;

        ItemUtils.removeOne(stockInv, shopItem);
        shop.setStockContents(stockInv.getContents().clone());
        player.getInventory().addItem(shopItem.clone());
        shop.depositToBank(shop.getPrice());
        shopManager.markDirty(shop);
        plugin.getTransactionLogger().logBuy(player, shop);

        return TransactionResult.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // SELL — player gives item, shop bank pays them
    // -----------------------------------------------------------------------

    public TransactionResult executeSell(Player player, Shop shop) {
        if (!shop.isConfigured()) return TransactionResult.NOT_CONFIGURED;

        // Use virtual stock inventory stored in the shop object
        org.bukkit.inventory.Inventory stockInv = getStockInventory(shop);

        ItemStack shopItem = shop.getItem();
        if (!ItemUtils.hasSpace(stockInv, shopItem)) return TransactionResult.SHOP_FULL;

        if (ItemUtils.countMatching(player.getInventory(), shopItem) < shopItem.getAmount())
            return TransactionResult.PLAYER_NO_STOCK;

        Optional<CurrencyProvider> provOpt = currencyRegistry.getProvider(shop.getCurrencyId());
        if (provOpt.isEmpty()) return TransactionResult.CURRENCY_UNAVAILABLE;
        CurrencyProvider prov = provOpt.get();

        if (shop.getBankBalance() < shop.getPrice()) return TransactionResult.SHOP_NO_FUNDS;
        if (!shop.withdrawFromBank(shop.getPrice())) return TransactionResult.ERROR;

        ItemUtils.removeOne(player.getInventory(), shopItem);
        stockInv.addItem(shopItem.clone());
        shop.setStockContents(stockInv.getContents().clone());
        prov.deposit(player, shop.getPrice());
        shopManager.markDirty(shop);
        plugin.getTransactionLogger().logSell(player, shop);

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
        plugin.getTransactionLogger().logBankDeposit(player, shop, amount);
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

        // Return all stocked items to the player; drop leftovers on the ground
        ItemStack[] stock = shop.getStockContents();
        Location dropLoc = shop.getBlockLocation() != null
                ? shop.getBlockLocation().add(0.5, 0.5, 0.5) : player.getLocation();
        if (stock != null) {
            for (ItemStack stack : stock) {
                if (stack == null || stack.getType() == Material.AIR) continue;
                java.util.Map<Integer, ItemStack> leftovers =
                        player.getInventory().addItem(stack.clone());
                for (ItemStack leftover : leftovers.values()) {
                    dropLoc.getWorld().dropItemNaturally(dropLoc, leftover);
                }
            }
        }
        // Remove the END_PORTAL_FRAME block and give back the tagged shop item
        Location blockLoc = shop.getBlockLocation();
        if (blockLoc != null) {
            Block shopBlock = blockLoc.getBlock();
            if (shopBlock.getType() == Material.END_PORTAL_FRAME) {
                shopBlock.setType(Material.AIR);
            }
        }
        ItemStack shopBlockItem = com.oneblock.shops.util.ShopItemFactory.createShopItem(plugin);
        java.util.Map<Integer, ItemStack> leftoverShop = player.getInventory().addItem(shopBlockItem);
        for (ItemStack lo : leftoverShop.values()) {
            dropLoc.getWorld().dropItemNaturally(dropLoc, lo);
        }

        plugin.getTransactionLogger().logPickup(player, shop);
        shopManager.removeShop(shop.getId());
        return true;
    }

    // -----------------------------------------------------------------------
    // Shop creation
    // -----------------------------------------------------------------------

    public Shop createShop(Player player, Location loc) {
        UUID islandId = resolveIslandId(loc);
        // Owner is tied to the island owner, not the placing player,
        // so that /shop tp <islandOwner> always works regardless of who placed it.
        UUID ownerUUID = resolveIslandOwner(islandId, player.getUniqueId());
        String defaultCurrency = plugin.getConfig().getString("default-currency", "vault_money");
        Shop shop = new Shop(ownerUUID, loc, islandId, defaultCurrency);
        shopManager.addShop(shop);
        plugin.getTransactionLogger().logPlace(player, shop);
        return shop;
    }

    /** Returns the island owner's UUID, or fallback if unavailable. */
    private UUID resolveIslandOwner(UUID islandId, UUID fallback) {
        if (islandId == null) return fallback;
        try {
            Island island = SuperiorSkyblockAPI.getGrid().getIslandByUUID(islandId);
            if (island == null) return fallback;
            return island.getOwner().getUniqueId();
        } catch (Exception e) {
            return fallback;
        }
    }

    // -----------------------------------------------------------------------
    // Withdraw from shop bank (owner)
    // -----------------------------------------------------------------------

    public boolean withdrawFromShopBank(Player player, Shop shop, double amount) {
        if (amount <= 0) return false;
        if (shop.getBankBalance() < amount) return false;
        Optional<CurrencyProvider> provOpt = currencyRegistry.getProvider(shop.getCurrencyId());
        if (provOpt.isEmpty()) return false;
        shop.withdrawFromBank(amount);
        provOpt.get().deposit(player, amount);
        shopManager.markDirty(shop);
        plugin.getTransactionLogger().logBankWithdraw(player, shop, amount);
        return true;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a Bukkit Inventory backed by the shop's virtual stock array (54 slots).
     * Changes to this inventory are NOT automatically saved — callers must call
     * shop.setStockContents(inv.getContents()) and shopManager.markDirty(shop).
     */
    public org.bukkit.inventory.Inventory getStockInventory(Shop shop) {
        org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(null, 54);
        ItemStack[] stock = shop.getStockContents();
        if (stock != null && stock.length > 0) {
            // Pad or trim to exactly 54 slots
            ItemStack[] padded = new ItemStack[54];
            System.arraycopy(stock, 0, padded, 0, Math.min(stock.length, 54));
            inv.setContents(padded);
        }
        return inv;
    }

    public int getStockCount(Shop shop) {
        if (!shop.isConfigured()) return 0;
        org.bukkit.inventory.Inventory inv = getStockInventory(shop);
        return shop.getMode() == ShopMode.BUY
                ? ItemUtils.countMatching(inv, shop.getItem())
                : ItemUtils.countAvailableSpace(inv, shop.getItem());
    }
}
