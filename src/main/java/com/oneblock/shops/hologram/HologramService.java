package com.oneblock.shops.hologram;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.economy.CurrencyProvider;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.Collections;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * ArmorStand hologram service.
 *
 * KEY DESIGN: all entities use setPersistent(false).
 * They are NEVER written to disk, eliminating the oversized chunk problem
 * entirely. The ChunkLoadListener recreates them when chunks load.
 *
 * PDC tags are still written so we can find and remove them in memory,
 * but since persistent=false the tags never reach disk.
 *
 * Text stands: setMarker(true) — no hitbox, no CreatureSpawnEvent issues
 * because we use SpawnReason.CUSTOM.
 *
 * Item stand: setMarker(false) so the helmet renders. Equipment locks
 * prevent item theft. Protected by HologramProtectionListener.
 *
 * Animation: ONE global task for all shops — no per-shop tasks.
 */
public class HologramService {

    public static final String PDC_KEY      = "shop_hologram_id";
    public static final String PDC_ITEM_KEY = "shop_item_display_id";

    private static final double LINE_SPACING  = 0.27;
    private static final double BOB_AMPLITUDE = 0.08;
    private static final int    BOB_PERIOD    = 80;
    private static final float  DEG_PER_TICK  = 2.25f; // degrees per 2-tick step

    private final OneBlockShopsPlugin plugin;

    /** shopId → UUID of the animated item stand */
    private final Map<UUID, UUID> itemStandIds        = new HashMap<>();
    /** Entity UUIDs currently being spawned by us — suppresses CreatureSpawnEvent */
    static final Set<UUID> suppressedSpawns = Collections.synchronizedSet(new HashSet<>());
    /** Shop IDs being intentionally removed — suppresses respawn loop */
    private final Set<UUID>       intentionallyRemoving = new HashSet<>();

    private long globalTick   = 0;
    private int  globalTaskId = -1;

    public HologramService(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void createOrUpdate(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                worldScanRemove(shop.getId());
                Location base = shop.getLocation();
                if (base == null || base.getWorld() == null) return;
                spawnTextStands(shop, base);
                if (shop.getItem() != null) spawnItemStand(shop, base);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[HologramService] createOrUpdate failed for " + shop.getId(), e);
            }
        });
    }

    public void remove(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            itemStandIds.remove(shop.getId());
            worldScanRemove(shop.getId());
            maybeStopGlobalTask();
        });
    }

    public void cleanupOrphans(World world) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Set<UUID> known = plugin.getShopManager().getAllShops()
                    .stream().map(Shop::getId).collect(Collectors.toSet());
            NamespacedKey textKey = key(PDC_KEY);
            NamespacedKey itemKey = key(PDC_ITEM_KEY);
            for (Entity e : world.getEntities()) {
                if (!(e instanceof ArmorStand)) continue;
                String tag = pdcGet(e, textKey);
                if (tag == null) tag = pdcGet(e, itemKey);
                if (tag == null) continue;
                try {
                    if (!known.contains(UUID.fromString(tag))) e.remove();
                } catch (IllegalArgumentException ignored) { e.remove(); }
            }
        });
    }

    public boolean isIntentionallyRemoving(UUID shopId) {
        return intentionallyRemoving.contains(shopId);
    }

    public void shutdown() {
        if (globalTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(globalTaskId);
            globalTaskId = -1;
        }
        itemStandIds.clear();
    }

    // -----------------------------------------------------------------------
    // Spawning — text stands (persistent=false → never saved to disk)
    // -----------------------------------------------------------------------

    private void spawnTextStands(Shop shop, Location base) {
        List<String> lines = buildLines(shop);
        World world = base.getWorld();
        double textYBase = base.getY()
                + plugin.getConfig().getDouble("hologram.text-y-offset", 2.1);
        double topY = textYBase + (lines.size() - 1) * LINE_SPACING;

        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            Location loc = base.clone();
            loc.setY(topY - i * LINE_SPACING);

            world.spawn(loc, ArmorStand.class, as -> {
                suppressedSpawns.add(as.getUniqueId());
                as.setVisible(false);
                as.setCustomNameVisible(true);
                as.setCustomName(color(line));
                as.setGravity(false);
                as.setInvulnerable(true);
                as.setPersistent(false);   // ← never written to disk
                as.setMarker(true);
                as.setSmall(true);
                as.setCollidable(false);
                as.setCanPickupItems(false);
                pdcSet(as, key(PDC_KEY), shop.getId().toString());
            });
        }
    }

    // -----------------------------------------------------------------------
    // Spawning — item stand (persistent=false → never saved to disk)
    // -----------------------------------------------------------------------

    private void spawnItemStand(Shop shop, Location base) {
        World world = base.getWorld();
        if (world == null) return;

        double itemY = base.getY()
                + plugin.getConfig().getDouble("hologram.item-y-offset", 1.35);
        Location loc = base.clone();
        loc.setY(itemY);

        ArmorStand as = world.spawn(loc, ArmorStand.class, stand -> {
            suppressedSpawns.add(stand.getUniqueId());
            stand.setVisible(false);
            stand.setCustomNameVisible(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setPersistent(false);    // ← never written to disk
            stand.setMarker(false);        // must be false for helmet to render
            stand.setSmall(true);
            stand.setCollidable(false);
            stand.setCanPickupItems(false);
            // Lock all slots so the item cannot be taken
            for (org.bukkit.inventory.EquipmentSlot slot
                    : org.bukkit.inventory.EquipmentSlot.values()) {
                stand.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
                stand.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
            }
            stand.getEquipment().setHelmet(shop.getItem().clone());
            pdcSet(stand, key(PDC_ITEM_KEY), shop.getId().toString());
        });

        itemStandIds.put(shop.getId(), as.getUniqueId());
        maybeStartGlobalTask();
    }

    // -----------------------------------------------------------------------
    // Global animation task — ONE task for ALL shops
    // -----------------------------------------------------------------------

    private void maybeStartGlobalTask() {
        if (globalTaskId != -1) return;
        globalTaskId = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            globalTick++;
            double bobY = BOB_AMPLITUDE
                    * Math.sin(2 * Math.PI * globalTick / (double) BOB_PERIOD);
            float yaw = (globalTick * DEG_PER_TICK) % 360f;

            for (Map.Entry<UUID, UUID> entry : new ArrayList<>(itemStandIds.entrySet())) {
                UUID shopId  = entry.getKey();
                UUID standId = entry.getValue();

                Entity entity = plugin.getServer().getEntity(standId);
                if (!(entity instanceof ArmorStand as)) {
                    itemStandIds.remove(shopId);
                    continue;
                }

                Shop shop = plugin.getShopManager().getById(shopId).orElse(null);
                if (shop == null) { itemStandIds.remove(shopId); continue; }

                double baseY = shop.getLocation() != null
                        ? shop.getLocation().getY()
                          + plugin.getConfig().getDouble("hologram.item-y-offset", 1.35)
                        : as.getLocation().getY();

                Location newLoc = as.getLocation();
                newLoc.setY(baseY + bobY);
                newLoc.setYaw(yaw);
                as.teleport(newLoc);
            }

            if (itemStandIds.isEmpty()) maybeStopGlobalTask();
        }, 2L, 2L).getTaskId();
    }

    private void maybeStopGlobalTask() {
        if (!itemStandIds.isEmpty() || globalTaskId == -1) return;
        plugin.getServer().getScheduler().cancelTask(globalTaskId);
        globalTaskId = -1;
    }

    // -----------------------------------------------------------------------
    // Removal
    // -----------------------------------------------------------------------

    private void worldScanRemove(UUID shopId) {
        itemStandIds.remove(shopId);
        intentionallyRemoving.add(shopId);
        try {
            String idStr = shopId.toString();
            NamespacedKey textKey = key(PDC_KEY);
            NamespacedKey itemKey = key(PDC_ITEM_KEY);
            for (World world : plugin.getServer().getWorlds()) {
                for (Entity e : world.getEntities()) {
                    if (!(e instanceof ArmorStand)) continue;
                    String tag = pdcGet(e, textKey);
                    if (tag == null) tag = pdcGet(e, itemKey);
                    if (idStr.equals(tag)) e.remove();
                }
            }
        } finally {
            intentionallyRemoving.remove(shopId);
        }
    }

    // -----------------------------------------------------------------------
    // Content
    // -----------------------------------------------------------------------

    private List<String> buildLines(Shop shop) {
        String itemName;
        if (shop.getItem() != null && shop.getItem().hasItemMeta()
                && shop.getItem().getItemMeta().hasDisplayName()) {
            itemName = shop.getItem().getItemMeta().getDisplayName();
        } else if (shop.getItem() != null) {
            itemName = prettify(shop.getItem().getType().name());
        } else {
            itemName = "&7Not configured";
        }

        Optional<CurrencyProvider> provOpt = plugin.getCurrencyRegistry()
                .getProvider(shop.getCurrencyId());
        String currName = provOpt.map(CurrencyProvider::getDisplayName)
                .orElse(shop.getCurrencyId());
        String priceStr = shop.isConfigured() ? fmt(shop.getPrice()) : "&cnot set";
        String modeStr  = shop.getMode() == ShopMode.BUY ? "&a\u25b6 BUY" : "&c\u25c0 SELL";
        int    stock    = getStockSafe(shop);
        String stockStr = stock < 0 ? "?" : String.valueOf(stock);
        String bankStr  = fmt(shop.getBankBalance());

        List<String> template = plugin.getConfig().getStringList("hologram.lines");
        if (template.isEmpty()) {
            template = List.of(
                    "&6&l\u2726 Shop \u2726", "&f{item}", "&e{price} {currency}",
                    "{mode}", "&7Stock: &f{stock}", "&7Bank: &f{bank} {currency}");
        }

        List<String> result = new ArrayList<>();
        for (String tpl : template) {
            result.add(tpl
                    .replace("{item}",     itemName)
                    .replace("{price}",    priceStr)
                    .replace("{currency}", currName)
                    .replace("{mode}",     modeStr)
                    .replace("{stock}",    stockStr)
                    .replace("{bank}",     bankStr));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int getStockSafe(Shop shop) {
        try {
            Location loc = shop.getBlockLocation();
            if (loc == null || !loc.isChunkLoaded()) return -1;
            return plugin.getShopService().getStockCount(shop);
        } catch (Exception e) { return -1; }
    }

    private NamespacedKey key(String k) { return new NamespacedKey(plugin, k); }

    private static String pdcGet(Entity e, NamespacedKey key) {
        if (!e.getPersistentDataContainer().has(key, PersistentDataType.STRING)) return null;
        return e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private static void pdcSet(Entity e, NamespacedKey key, String value) {
        e.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
    }

    private static String prettify(String enumName) {
        String lower = enumName.replace("_", " ").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String color(String s) { return s.replace("&", "\u00A7"); }

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
