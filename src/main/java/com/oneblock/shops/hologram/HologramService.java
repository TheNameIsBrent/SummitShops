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
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * ArmorStand-based hologram service.
 *
 * ANIMATION ARCHITECTURE — one global task, not one per shop:
 *   A single BukkitRunnable fires every 2 ticks and iterates over all tracked
 *   item stands. This replaces the old per-shop runTaskTimer approach which
 *   created O(n) scheduler tasks and consumed 50 %+ of server threads with
 *   even a modest number of shops.
 *
 * ITEM PROTECTION:
 *   The item stand has setMarker(false) so the helmet renders, but we block
 *   ALL player interaction with it via PlayerArmorStandManipulateEvent and
 *   EntityDamageByEntityEvent. The stand is also locked via equipment locks.
 */
public class HologramService {

    public static final String PDC_KEY      = "shop_hologram_id";
    public static final String PDC_ITEM_KEY = "shop_item_display_id";

    private static final double LINE_SPACING  = 0.27;
    private static final double BOB_AMPLITUDE = 0.08;
    private static final int    BOB_PERIOD    = 80;    // ticks per bob cycle
    private static final float  DEG_PER_TICK  = 2.25f; // yaw degrees per 2-tick step

    private final OneBlockShopsPlugin plugin;

    /** shopId → UUID of the animated item ArmorStand (for the global task to look up) */
    private final Map<UUID, UUID> itemStandIds = new HashMap<>();

    /** Tick counter shared by the global animation task */
    private long globalTick = 0;

    /** Task ID of the single global animation task (-1 = not running) */
    private int globalTaskId = -1;

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

            org.bukkit.NamespacedKey textKey = key(PDC_KEY);
            org.bukkit.NamespacedKey itemKey = key(PDC_ITEM_KEY);

            for (Entity e : world.getEntities()) {
                if (!(e instanceof ArmorStand)) continue;
                String tag = pdc(e, textKey);
                if (tag == null) tag = pdc(e, itemKey);
                if (tag == null) continue;
                try {
                    if (!known.contains(UUID.fromString(tag))) e.remove();
                } catch (IllegalArgumentException ignored) { e.remove(); }
            }
        });
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
        World world = base.getWorld();
        double textYBase = plugin.getConfig().getDouble("hologram.text-y-offset", 2.1);
        double topY = base.getY() + textYBase + (lines.size() - 1) * LINE_SPACING;

        for (int i = 0; i < lines.size(); i++) {
            Location loc = base.clone();
            loc.setY(topY - i * LINE_SPACING);

            ArmorStand as = world.spawn(loc, ArmorStand.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, stand -> {});
            as.setVisible(false);
            as.setCustomNameVisible(true);
            as.setCustomName(color(lines.get(i)));
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setPersistent(true);
            as.setMarker(true);
            as.setSmall(true);
            as.setCollidable(false);
            as.setCanPickupItems(false);
            pdc(as, key(PDC_KEY), shop.getId().toString());
        }
    }

    private void spawnItemStand(Shop shop, Location base) {
        World world = base.getWorld();
        if (world == null) return;

        double itemY = base.getY() + plugin.getConfig().getDouble("hologram.item-y-offset", 1.35);
        Location loc = base.clone();
        loc.setY(itemY);

        ArmorStand as = world.spawn(loc, ArmorStand.class,
                CreatureSpawnEvent.SpawnReason.CUSTOM, stand -> {});
        as.setVisible(false);
        as.setCustomNameVisible(false);
        as.setGravity(false);
        as.setInvulnerable(true);
        as.setPersistent(true);
        as.setMarker(false);   // must be false for helmet to render
        as.setSmall(true);
        as.setCollidable(false);
        as.setCanPickupItems(false);

        // Lock all equipment slots so nothing can be taken or swapped
        for (org.bukkit.inventory.EquipmentSlot slot : org.bukkit.inventory.EquipmentSlot.values()) {
            as.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
            as.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
        }

        as.getEquipment().setHelmet(shop.getItem().clone());
        pdc(as, key(PDC_ITEM_KEY), shop.getId().toString());

        // Register with global task
        itemStandIds.put(shop.getId(), as.getUniqueId());
        maybeStartGlobalTask();
    }

    // -----------------------------------------------------------------------
    // Global animation task — ONE task for ALL shops
    // -----------------------------------------------------------------------

    private void maybeStartGlobalTask() {
        if (globalTaskId != -1) return; // already running

        globalTaskId = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            globalTick++;
            double bobY = BOB_AMPLITUDE * Math.sin(2 * Math.PI * globalTick / (double) BOB_PERIOD);
            float  yaw  = (globalTick * DEG_PER_TICK) % 360f;

            // Iterate over a snapshot to avoid ConcurrentModificationException
            for (Map.Entry<UUID, UUID> entry : new ArrayList<>(itemStandIds.entrySet())) {
                UUID shopId  = entry.getKey();
                UUID standId = entry.getValue();

                Entity entity = plugin.getServer().getEntity(standId);
                if (!(entity instanceof ArmorStand as)) {
                    // Stand gone — remove from map, hologram protection listener will respawn
                    itemStandIds.remove(shopId);
                    continue;
                }

                // Resolve base Y from config each cycle so /shop reload is live
                Shop shop = plugin.getShopManager().getById(shopId).orElse(null);
                if (shop == null) {
                    itemStandIds.remove(shopId);
                    continue;
                }
                double baseY = shop.getLocation() != null
                        ? shop.getLocation().getY()
                          + plugin.getConfig().getDouble("hologram.item-y-offset", 1.35)
                        : as.getLocation().getY();

                Location newLoc = as.getLocation();
                newLoc.setY(baseY + bobY);
                newLoc.setYaw(yaw);
                as.teleport(newLoc);
            }

            // Stop the task if there's nothing left to animate
            if (itemStandIds.isEmpty()) maybeStopGlobalTask();

        }, 2L, 2L).getTaskId();
    }

    private void maybeStopGlobalTask() {
        if (!itemStandIds.isEmpty()) return;
        if (globalTaskId == -1) return;
        plugin.getServer().getScheduler().cancelTask(globalTaskId);
        globalTaskId = -1;
    }

    // -----------------------------------------------------------------------
    // Removal
    // -----------------------------------------------------------------------

    private void worldScanRemove(UUID shopId) {
        itemStandIds.remove(shopId);
        String idStr = shopId.toString();
        org.bukkit.NamespacedKey textKey = key(PDC_KEY);
        org.bukkit.NamespacedKey itemKey = key(PDC_ITEM_KEY);

        for (World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!(e instanceof ArmorStand)) continue;
                String tag = pdc(e, textKey);
                if (tag == null) tag = pdc(e, itemKey);
                if (idStr.equals(tag)) e.remove();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Text content
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
        String modeStr  = shop.getMode() == ShopMode.BUY ? "&a▶ BUY" : "&c◀ SELL";
        int    stock    = getStockSafe(shop);
        String stockStr = stock < 0 ? "?" : String.valueOf(stock);
        String bankStr  = fmt(shop.getBankBalance());

        List<String> template = plugin.getConfig().getStringList("hologram.lines");
        if (template.isEmpty()) {
            template = List.of(
                    "&6&l✦ Shop ✦", "&f{item}", "&e{price} {currency}",
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

    private org.bukkit.NamespacedKey key(String k) {
        return new org.bukkit.NamespacedKey(plugin, k);
    }

    /** Read PDC string tag from entity. */
    private static String pdc(Entity e, org.bukkit.NamespacedKey key) {
        if (!e.getPersistentDataContainer().has(key,
                org.bukkit.persistence.PersistentDataType.STRING)) return null;
        return e.getPersistentDataContainer().get(key,
                org.bukkit.persistence.PersistentDataType.STRING);
    }

    /** Write PDC string tag onto ArmorStand. */
    private static void pdc(ArmorStand as, org.bukkit.NamespacedKey key, String value) {
        as.getPersistentDataContainer().set(key,
                org.bukkit.persistence.PersistentDataType.STRING, value);
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
