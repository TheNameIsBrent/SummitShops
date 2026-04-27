package com.oneblock.shops.shop;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single chest shop.
 *
 * <p>A shop is tied to a physical chest location on an island.
 * The chest inventory IS the stock — no separate storage.</p>
 */
public class Shop {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Unique shop identifier. */
    private final UUID id;

    /** UUID of the player who owns this shop. */
    private UUID ownerUUID;

    /** World name for serialization-safe location storage. */
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;

    /** The SuperiorSkyblock2 island UUID this shop belongs to. */
    private UUID islandId;

    /** The item being bought or sold (full NBT). */
    private ItemStack item;

    /** Price per transaction. */
    private double price;

    /** BUY = players buy FROM chest; SELL = players sell INTO chest. */
    private ShopMode mode;

    /** Currency identifier (references CurrencyRegistry). */
    private String currencyId;

    /** Unix epoch milliseconds when this shop was first created. */
    private final long createdAt;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Full constructor used when loading from storage. */
    public Shop(UUID id, UUID ownerUUID, String worldName, double x, double y, double z,
                UUID islandId, ItemStack item, double price, ShopMode mode,
                String currencyId, long createdAt) {
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
    }

    /** Convenience constructor for freshly created shops. */
    public Shop(UUID ownerUUID, Location location, UUID islandId, String defaultCurrencyId) {
        this.id = UUID.randomUUID();
        this.ownerUUID = ownerUUID;
        this.worldName = location.getWorld().getName();
        this.x = location.getBlockX() + 0.5;
        this.y = location.getBlockY();
        this.z = location.getBlockZ() + 0.5;
        this.islandId = islandId;
        this.item = null;           // not yet configured
        this.price = 0.0;
        this.mode = ShopMode.BUY;
        this.currencyId = defaultCurrencyId;
        this.createdAt = Instant.now().toEpochMilli();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the Bukkit {@link Location} of the chest block (block-centre X/Z).
     * Returns {@code null} if the world is not loaded.
     */
    public Location getLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x, y, z);
    }

    /**
     * Returns the block-integer location (for chest access).
     */
    public Location getBlockLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, (int) x, (int) y, (int) z);
    }

    /**
     * Returns {@code true} if the shop has been fully configured
     * (item set and price > 0).
     */
    public boolean isConfigured() {
        return item != null && price > 0.0;
    }

    // -----------------------------------------------------------------------
    // Getters / Setters
    // -----------------------------------------------------------------------

    public UUID getId() { return id; }

    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID ownerUUID) { this.ownerUUID = ownerUUID; }

    public String getWorldName() { return worldName; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }

    public UUID getIslandId() { return islandId; }
    public void setIslandId(UUID islandId) { this.islandId = islandId; }

    public ItemStack getItem() { return item; }
    public void setItem(ItemStack item) { this.item = item != null ? item.clone() : null; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = Math.max(0.0, price); }

    public ShopMode getMode() { return mode; }
    public void setMode(ShopMode mode) { this.mode = mode; }

    public String getCurrencyId() { return currencyId; }
    public void setCurrencyId(String currencyId) { this.currencyId = currencyId; }

    public long getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "Shop{id=" + id + ", owner=" + ownerUUID +
                ", world=" + worldName + ", x=" + (int) x + ", y=" + (int) y + ", z=" + (int) z +
                ", mode=" + mode + ", price=" + price + ", currency=" + currencyId + "}";
    }
}
