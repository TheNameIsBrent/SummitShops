package com.oneblock.shops.shop;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.UUID;

public class Shop {

    private final UUID id;
    private UUID ownerUUID;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private UUID islandId;
    private ItemStack item;
    private double price;
    private ShopMode mode;
    private String currencyId;
    private final long createdAt;
    /** Short human-readable identifier (first 8 chars of UUID). */
    private final String shortId;
    private double bankBalance;
    /** Virtual 6-row (54-slot) stock inventory for this shop. */
    private ItemStack[] stockContents;

    /** Full constructor used when loading from storage. */
    public Shop(UUID id, UUID ownerUUID, String worldName, double x, double y, double z,
                UUID islandId, ItemStack item, double price, ShopMode mode,
                String currencyId, long createdAt, double bankBalance) {
        this.id = id;
        this.ownerUUID = ownerUUID;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.islandId = islandId;
        this.item = item;
        this.price = price;
        this.mode = mode;
        this.currencyId = currencyId;
        this.createdAt = createdAt;
        this.bankBalance = bankBalance;
        this.stockContents = new ItemStack[54];
        this.shortId = id.toString().substring(0, 8).toUpperCase();
    }

    /** Legacy constructor without bankBalance — defaults to 0. */
    public Shop(UUID id, UUID ownerUUID, String worldName, double x, double y, double z,
                UUID islandId, ItemStack item, double price, ShopMode mode,
                String currencyId, long createdAt) {
        this(id, ownerUUID, worldName, x, y, z, islandId, item, price, mode, currencyId, createdAt, 0.0);
    }

    /** Constructor for freshly created shops. */
    public Shop(UUID ownerUUID, Location location, UUID islandId, String defaultCurrencyId) {
        this.id = UUID.randomUUID();
        this.ownerUUID = ownerUUID;
        this.worldName = location.getWorld().getName();
        this.x = location.getBlockX();
        this.y = location.getBlockY();
        this.z = location.getBlockZ();
        this.islandId = islandId;
        this.item = null;
        this.price = 0.0;
        this.mode = ShopMode.BUY;
        this.currencyId = defaultCurrencyId;
        this.createdAt = Instant.now().toEpochMilli();
        this.bankBalance = 0.0;
    }

    public Location getLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        // +0.5 centers the hologram over the block
        return new Location(world, Math.floor(x) + 0.5, y, Math.floor(z) + 0.5);
    }

    public Location getBlockLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public boolean isConfigured() {
        return item != null && price > 0.0;
    }

    public UUID getId() { return id; }
    public String getShortId() { return shortId; }
    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID v) { this.ownerUUID = v; }
    public String getWorldName() { return worldName; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public UUID getIslandId() { return islandId; }
    public void setIslandId(UUID v) { this.islandId = v; }
    public ItemStack getItem() { return item; }
    public void setItem(ItemStack item) { this.item = item != null ? item.clone() : null; }
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = Math.max(0.0, price); }
    public ShopMode getMode() { return mode; }
    public void setMode(ShopMode mode) { this.mode = mode; }
    public String getCurrencyId() { return currencyId; }
    public void setCurrencyId(String currencyId) { this.currencyId = currencyId; }
    public long getCreatedAt() { return createdAt; }

    public ItemStack[] getStockContents() { return stockContents; }
    public void setStockContents(ItemStack[] contents) { this.stockContents = contents; }

    public double getBankBalance() { return bankBalance; }
    public void setBankBalance(double v) { this.bankBalance = Math.max(0.0, v); }
    public void depositToBank(double amount) { this.bankBalance += Math.max(0.0, amount); }
    public boolean withdrawFromBank(double amount) {
        if (bankBalance < amount) return false;
        bankBalance -= amount;
        return true;
    }
}
