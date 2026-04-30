package com.oneblock.shops.hologram;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.economy.CurrencyProvider;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopMode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * ArmorStand hologram service.
 *
 * Uses reflection to call CraftWorld.addEntityToWorld() directly,
 * bypassing the Bukkit spawn path and CreatureSpawnEvent entirely.
 * This means MythicMobs and other plugins never see these spawns.
 *
 * All entities: persistent=false so they are never saved to disk.
 * Single global animation task for all item stands.
 */
public class HologramService {

    public static final String PDC_KEY      = "shop_hologram_id";
    public static final String PDC_ITEM_KEY = "shop_item_display_id";

    private static final double LINE_SPACING  = 0.27;
    private static final double BOB_AMPLITUDE = 0.08;
    private static final int    BOB_PERIOD    = 80;
    private static final float  DEG_PER_TICK  = 2.25f;

    private final OneBlockShopsPlugin plugin;

    private final Map<UUID, UUID> itemStandIds         = new HashMap<>();
    private final Set<UUID>       intentionallyRemoving = new HashSet<>();

    private long globalTick   = 0;
    private int  globalTaskId = -1;

    // Reflection handle for CraftWorld.addEntityToWorld(Entity, SpawnReason)
    private Method addEntityToWorld = null;

    public HologramService(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
        initReflection();
    }

    private void initReflection() {
        try {
            // CraftWorld.addEntityToWorld is package-private — get it via reflection
            Class<?> craftWorldClass = Bukkit.getWorld("world") != null
                    ? Bukkit.getServer().getWorlds().get(0).getClass()
                    : Class.forName("org.bukkit.craftbukkit.CraftWorld");
            addEntityToWorld = craftWorldClass.getDeclaredMethod(
                    "addEntityToWorld",
                    net.minecraft.world.entity.Entity.class,
                    CreatureSpawnEvent.SpawnReason.class);
            addEntityToWorld.setAccessible(true);
            plugin.getLogger().info("[HologramService] NMS bypass ready.");
        } catch (Exception e) {
            plugin.getLogger().warning("[HologramService] NMS bypass unavailable, "
                    + "falling back to standard spawn: " + e.getMessage());
        }
    }

    /**
     * Spawns an ArmorStand without firing CreatureSpawnEvent.
     * Falls back to standard world.spawn() if reflection is unavailable.
     */
    private ArmorStand spawnSilently(Location loc) {
        World world = loc.getWorld();
        if (addEntityToWorld != null) {
            try {
                // Create NMS entity via craftbukkit internals
                org.bukkit.craftbukkit.CraftWorld craftWorld =
                        (org.bukkit.craftbukkit.CraftWorld) world;
                net.minecraft.world.level.Level nmsLevel = craftWorld.getHandle();

                net.minecraft.world.entity.decoration.ArmorStand nmsStand =
                        new net.minecraft.world.entity.decoration.ArmorStand(
                                net.minecraft.world.entity.EntityType.ARMOR_STAND, nmsLevel);
                nmsStand.setPos(loc.getX(), loc.getY(), loc.getZ());
                nmsStand.setYRot(loc.getYaw());

                // Call addEntityToWorld directly — skips CreatureSpawnEvent
                addEntityToWorld.invoke(craftWorld, nmsStand,
                        CreatureSpawnEvent.SpawnReason.CUSTOM);

                return (ArmorStand) nmsStand.getBukkitEntity();
            } catch (Exception e) {
                plugin.getLogger().warning("[HologramService] Silent spawn failed: "
                        + e.getMessage());
            }
        }
        // Fallback
        return world.spawn(loc, ArmorStand.class,
                CreatureSpawnEvent.SpawnReason.CUSTOM, stand -> {});
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
    // Spawning
    // -----------------------------------------------------------------------

    private void spawnTextStands(Shop shop, Location base) {
        List<String> lines = buildLines(shop);
        double textYBase = base.getY()
                + plugin.getConfig().getDouble("hologram.text-y-offset", 2.1);
        double topY = textYBase + (lines.size() - 1) * LINE_SPACING;

        for (int i = 0; i < lines.size(); i++) {
            Location loc = base.clone();
            loc.setY(topY - i * LINE_SPACING);

            ArmorStand as = spawnSilently(loc);
            as.setVisible(false);
            as.setCustomNameVisible(true);
            as.setCustomName(color(lines.get(i)));
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setPersistent(false);
            as.setMarker(true);
            as.setSmall(true);
            as.setCollidable(false);
            as.setCanPickupItems(false);
            pdcSet(as, key(PDC_KEY), shop.getId().toString());
        }
    }

    private void spawnItemStand(Shop shop, Location base) {
        double itemY = base.getY()
                + plugin.getConfig().getDouble("hologram.item-y-offset", 1.35);
        Location loc = base.clone();
        loc.setY(itemY);

        ArmorStand as = spawnSilently(loc);
        as.setVisible(false);
        as.setCustomNameVisible(false);
        as.setGravity(false);
        as.setInvulnerable(true);
        as.setPersistent(false);
        as.setMarker(false);
        as.setSmall(true);
        as.setCollidable(false);
        as.setCanPickupItems(false);
        for (org.bukkit.inventory.EquipmentSlot slot
                : org.bukkit.inventory.EquipmentSlot.values()) {
            as.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
            as.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
        }
        as.getEquipment().setHelmet(shop.getItem().clone());
        pdcSet(as, key(PDC_ITEM_KEY), shop.getId().toString());

        itemStandIds.put(shop.getId(), as.getUniqueId());
        maybeStartGlobalTask();
    }

    // -----------------------------------------------------------------------
    // Global animation task
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
