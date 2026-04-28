package com.oneblock.shops.shop;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.storage.StorageProvider;
import org.bukkit.Location;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * In-memory cache and lifecycle manager for all {@link Shop} instances.
 *
 * <p>Shops are indexed both by their UUID and by a location key so that
 * block-interaction lookups are O(1).</p>
 */
public class ShopManager {

    private final OneBlockShopsPlugin plugin;
    private final StorageProvider storage;
    private final HologramService hologramService;

    /** Primary map: shopId → Shop */
    private final Map<UUID, Shop> shopsById = new ConcurrentHashMap<>();

    /**
     * Location-key map: "world:x:y:z" → shopId
     * Allows fast lookup when a player right/left-clicks a chest.
     */
    private final Map<String, UUID> shopsByLocation = new ConcurrentHashMap<>();

    public ShopManager(OneBlockShopsPlugin plugin,
                       StorageProvider storage,
                       HologramService hologramService) {
        this.plugin = plugin;
        this.storage = storage;
        this.hologramService = hologramService;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Loads all shops from storage and spawns their holograms. */
    public void loadAll() {
        List<Shop> loaded = storage.loadAll();
        for (Shop shop : loaded) {
            cache(shop);
            hologramService.createOrUpdate(shop);
        }
        plugin.getLogger().info("Loaded " + shopsById.size() + " shop(s).");
    }

    /** Persists all in-memory shops to storage. */
    public void saveAll() {
        storage.saveAll(new ArrayList<>(shopsById.values()));
    }

    /** Saves a single shop asynchronously. */
    public void saveAsync(Shop shop) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> storage.save(shop));
    }

    /** Reloads shops from storage (called on /shop reload). */
    public void reload() {
        // Remove all holograms first
        shopsById.values().forEach(s -> hologramService.remove(s));
        shopsById.clear();
        shopsByLocation.clear();
        loadAll();
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    /**
     * Registers a newly created shop in memory and storage,
     * then spawns its hologram.
     */
    public void addShop(Shop shop) {
        cache(shop);
        saveAsync(shop);
        hologramService.createOrUpdate(shop);
    }

    /**
     * Removes a shop by ID, deletes from storage, and removes its hologram.
     *
     * @return {@code true} if the shop existed and was removed
     */
    public boolean removeShop(UUID shopId) {
        Shop shop = shopsById.remove(shopId);
        if (shop == null) return false;
        shopsByLocation.remove(locationKey(shop));
        storage.deleteAsync(shopId);
        hologramService.remove(shop);
        return true;
    }

    /**
     * Removes the shop at the given block location, if any.
     *
     * @return the removed shop, or {@code null}
     */
    public Shop removeShopAt(Location loc) {
        String key = locationKey(loc);
        UUID id = shopsByLocation.remove(key);
        if (id == null) return null;
        Shop shop = shopsById.remove(id);
        if (shop != null) {
            storage.deleteAsync(id);
            hologramService.remove(shop);
        }
        return shop;
    }

    /**
     * Notifies the manager that a shop's data has changed.
     * Updates hologram and saves asynchronously.
     */
    public void markDirty(Shop shop) {
        hologramService.createOrUpdate(shop);
        saveAsync(shop);
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    public Optional<Shop> getById(UUID id) {
        return Optional.ofNullable(shopsById.get(id));
    }

    public Optional<Shop> getAtLocation(Location loc) {
        UUID id = shopsByLocation.get(locationKey(loc));
        if (id == null) return Optional.empty();
        return Optional.ofNullable(shopsById.get(id));
    }

    public boolean isShopAt(Location loc) {
        return shopsByLocation.containsKey(locationKey(loc));
    }

    /** Returns all shops owned by a specific player UUID. */
    public List<Shop> getShopsByOwner(UUID ownerUUID) {
        return shopsById.values().stream()
                .filter(s -> ownerUUID.equals(s.getOwnerUUID()))
                .collect(Collectors.toList());
    }

    /** Returns all shops on a specific island. */
    public List<Shop> getShopsByIsland(UUID islandId) {
        return shopsById.values().stream()
                .filter(s -> islandId.equals(s.getIslandId()))
                .collect(Collectors.toList());
    }

    public Collection<Shop> getAllShops() {
        return Collections.unmodifiableCollection(shopsById.values());
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void cache(Shop shop) {
        shopsById.put(shop.getId(), shop);
        shopsByLocation.put(locationKey(shop), shop.getId());
    }

    /** Canonical location key from a Shop. */
    private String locationKey(Shop shop) {
        return shop.getWorldName() + ":" + (int) Math.floor(shop.getX()) + ":" +
               (int) Math.floor(shop.getY()) + ":" + (int) Math.floor(shop.getZ());
    }

    /** Canonical location key from a Bukkit Location (block coords). */
    public static String locationKey(Location loc) {
        return loc.getWorld().getName() + ":" +
               loc.getBlockX() + ":" +
               loc.getBlockY() + ":" +
               loc.getBlockZ();
    }
}
