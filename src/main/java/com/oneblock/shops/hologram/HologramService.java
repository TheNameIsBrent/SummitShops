package com.oneblock.shops.hologram;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.economy.CurrencyProvider;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.*;
import java.util.logging.Level;

/**
 * Hologram system built on invisible ArmorStand entities.
 *
 * No external dependencies — works on any Paper/Spigot 1.21 server.
 *
 * Each shop gets up to 5 stacked name-tag lines, spaced 0.25 blocks apart.
 * All ArmorStands are:
 *   - Invisible
 *   - Invulnerable
 *   - Persistent (so they survive chunk saves)
 *   - Marker (no hitbox, not selectable)
 *   - Gravity disabled
 *
 * On server restart ArmorStands persist via normal world saves.
 * We track them in memory by shopId; on startup we scan loaded chunks
 * to re-attach existing stands to their shops (handled by ChunkLoadListener).
 *
 * PDC tag "oneblockshops:shop_id" on each ArmorStand allows re-identification
 * after restart.
 */
public class HologramService {

    /** Vertical gap between hologram lines (blocks). */
    private static final double LINE_GAP = 0.25;

    /** PDC key name used to tag each ArmorStand with its shop UUID. */
    public static final String PDC_KEY = "shop_hologram_id";

    private final OneBlockShopsPlugin plugin;

    /** shopId -> list of ArmorStand UUIDs (top line first). */
    private final Map<UUID, List<UUID>> holoStands = new HashMap<>();

    public HologramService(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Creates or fully rebuilds the hologram for the given shop.
     * Always runs on the main thread.
     */
    public void createOrUpdate(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                // Remove old stands first
                removeStands(shop.getId());

                Location baseLoc = hologramBase(shop);
                if (baseLoc == null || baseLoc.getWorld() == null) return;

                List<String> lines = buildLines(shop);
                List<UUID> standIds = new ArrayList<>();

                // Spawn from top line down; top line is at baseLoc + (lines-1) * GAP
                double topY = baseLoc.getY() + (lines.size() - 1) * LINE_GAP;

                for (int i = 0; i < lines.size(); i++) {
                    Location spawnLoc = baseLoc.clone();
                    spawnLoc.setY(topY - i * LINE_GAP);

                    ArmorStand stand = spawnStand(spawnLoc, lines.get(i), shop.getId());
                    if (stand != null) standIds.add(stand.getUniqueId());
                }

                holoStands.put(shop.getId(), standIds);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[HologramService] createOrUpdate failed for " + shop.getId()
                        + ": " + e.getMessage(), e);
            }
        });
    }

    /**
     * Removes the hologram for a shop (e.g. when the shop is deleted).
     */
    public void remove(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin,
                () -> removeStands(shop.getId()));
    }

    /**
     * Scans a world for any orphaned hologram ArmorStands belonging to
     * shops that are no longer tracked in memory, and removes them.
     * Called on chunk load to clean up stale stands.
     */
    public void cleanupOrphans(World world) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Set<UUID> knownShopIds = plugin.getShopManager().getAllShops()
                    .stream()
                    .map(Shop::getId)
                    .collect(java.util.stream.Collectors.toSet());

            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof ArmorStand stand)) continue;
                String tag = getShopTag(stand);
                if (tag == null) continue; // not one of ours

                try {
                    UUID shopId = UUID.fromString(tag);
                    if (!knownShopIds.contains(shopId)) {
                        // Orphaned — no shop owns this stand anymore
                        stand.remove();
                    }
                } catch (IllegalArgumentException ignored) {
                    stand.remove(); // corrupt tag
                }
            }
        });
    }

    /** Called during plugin disable — nothing to flush (stands are world-persistent). */
    public void shutdown() {
        // ArmorStands persist automatically with the world save.
        // We just clear the in-memory map.
        holoStands.clear();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void removeStands(UUID shopId) {
        List<UUID> ids = holoStands.remove(shopId);
        if (ids == null) return;
        for (UUID uid : ids) {
            // Search all worlds for this entity UUID
            for (World world : plugin.getServer().getWorlds()) {
                Entity entity = world.getEntity(uid);
                if (entity != null) {
                    entity.remove();
                    break;
                }
            }
        }
    }

    private ArmorStand spawnStand(Location loc, String displayName, UUID shopId) {
        World world = loc.getWorld();
        if (world == null) return null;

        ArmorStand stand = (ArmorStand) world.spawnEntity(loc, EntityType.ARMOR_STAND);

        stand.setVisible(false);
        stand.setCustomNameVisible(true);
        stand.setCustomName(colorize(displayName));
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setPersistent(true);
        stand.setMarker(true);          // no hitbox
        stand.setSmall(true);
        stand.setCollidable(false);

        // Tag with shopId so we can identify it after restart
        stand.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, PDC_KEY),
                org.bukkit.persistence.PersistentDataType.STRING,
                shopId.toString()
        );

        return stand;
    }

    private String getShopTag(ArmorStand stand) {
        org.bukkit.NamespacedKey key =
                new org.bukkit.NamespacedKey(plugin, PDC_KEY);
        if (!stand.getPersistentDataContainer().has(
                key, org.bukkit.persistence.PersistentDataType.STRING)) {
            return null;
        }
        return stand.getPersistentDataContainer().get(
                key, org.bukkit.persistence.PersistentDataType.STRING);
    }

    private List<String> buildLines(Shop shop) {
        List<String> lines = new ArrayList<>();

        // Line 1 — title
        lines.add("&6&l✦ Shop ✦");

        // Line 2 — item name
        String itemName;
        if (shop.getItem() != null
                && shop.getItem().hasItemMeta()
                && shop.getItem().getItemMeta().hasDisplayName()) {
            itemName = shop.getItem().getItemMeta().getDisplayName();
        } else if (shop.getItem() != null) {
            itemName = prettify(shop.getItem().getType().name());
        } else {
            itemName = "&7Not configured";
        }
        lines.add("&f" + itemName);

        // Line 3 — price + currency
        Optional<CurrencyProvider> providerOpt =
                plugin.getCurrencyRegistry().getProvider(shop.getCurrencyId());
        String currencyName = providerOpt
                .map(CurrencyProvider::getDisplayName)
                .orElse("&7" + shop.getCurrencyId());

        lines.add(shop.isConfigured()
                ? "&e" + formatPrice(shop.getPrice()) + " " + currencyName
                : "&7Price: &cnot set");

        // Line 4 — mode
        lines.add(shop.getMode() == ShopMode.BUY ? "&a▶ BUY" : "&c◀ SELL");

        // Line 5 — stock / space
        int stock = getStockSafe(shop);
        String stockStr = stock < 0 ? "?" : String.valueOf(stock);
        lines.add(shop.getMode() == ShopMode.BUY
                ? "&7Stock: &f" + stockStr
                : "&7Space: &f" + stockStr);

        return lines;
    }

    private int getStockSafe(Shop shop) {
        try {
            Location loc = shop.getBlockLocation();
            if (loc == null || !loc.isChunkLoaded()) return -1;
            return plugin.getShopService().getStockCount(shop);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Base location for the hologram — centred on the chest, Y-offset above it.
     */
    private Location hologramBase(Shop shop) {
        Location loc = shop.getLocation();
        if (loc == null) return null;
        double yOffset = plugin.getConfig().getDouble("hologram.y-offset", 1.5);
        return loc.clone().add(0, yOffset, 0);
    }

    private static String colorize(String s) {
        return s.replace("&", "\u00A7");
    }

    private static String prettify(String enumName) {
        String lower = enumName.replace("_", " ").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String formatPrice(double price) {
        if (price == (long) price) return String.valueOf((long) price);
        return String.format("%.2f", price);
    }
}
