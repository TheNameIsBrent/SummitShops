package com.oneblock.shops.hologram;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.economy.CurrencyProvider;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * ArmorStand-based hologram service.
 *
 * Why ArmorStands instead of Display entities:
 *   - Entity-clearing plugins (ClearLagg, EssentialsX /killall, etc.) already
 *     whitelist ArmorStands by default — they survive clears automatically.
 *   - Display entities are newer and often caught by clearers that don't
 *     recognise their type.
 *
 * Layout (above the chest block):
 *   One invisible ArmorStand per text line  — stacked 0.25 blocks apart
 *   One small ArmorStand holding a head     — the floating item, bob+spin animated
 *
 * Animation: a single BukkitRunnable per shop drives the bob+spin.
 * The old task is ALWAYS cancelled before a new one is started, preventing stacking.
 */
public class HologramService {

    public static final String PDC_KEY      = "shop_hologram_id";
    public static final String PDC_ITEM_KEY = "shop_item_display_id";

    private static final double LINE_SPACING  = 0.27;
    private static final double TEXT_Y_BASE   = 2.1;   // above the block
    private static final double ITEM_Y_BASE   = 1.35;  // floating item height
    private static final double BOB_AMPLITUDE = 0.08;  // blocks up/down
    private static final int    BOB_PERIOD    = 80;    // ticks for one bob cycle

    private final OneBlockShopsPlugin plugin;

    /** shopId → task id of the bob/spin animation */
    private final Map<UUID, Integer> animTasks = new HashMap<>();

    public HologramService(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void createOrUpdate(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                // Always cancel existing animation FIRST before removing entities
                cancelAnim(shop.getId());
                worldScanRemove(shop.getId());

                Location base = shopBase(shop);
                if (base == null || base.getWorld() == null) return;

                List<String> lines = buildLines(shop);
                spawnTextStands(shop, base, lines);

                if (shop.getItem() != null) {
                    spawnItemStand(shop, base);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[HologramService] createOrUpdate failed for " + shop.getId(), e);
            }
        });
    }

    public void remove(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            cancelAnim(shop.getId());
            worldScanRemove(shop.getId());
        });
    }

    public void cleanupOrphans(World world) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Set<UUID> known = plugin.getShopManager().getAllShops()
                    .stream().map(Shop::getId).collect(Collectors.toSet());

            org.bukkit.NamespacedKey textKey = pdcKey(PDC_KEY);
            org.bukkit.NamespacedKey itemKey = pdcKey(PDC_ITEM_KEY);

            for (Entity e : world.getEntities()) {
                if (!(e instanceof ArmorStand)) continue;
                String tag = pdc(e, textKey);
                if (tag == null) tag = pdc(e, itemKey);
                if (tag == null) continue;
                try {
                    if (!known.contains(UUID.fromString(tag))) e.remove();
                } catch (IllegalArgumentException ignored) {
                    e.remove();
                }
            }
        });
    }

    public void shutdown() {
        animTasks.values().forEach(id ->
                plugin.getServer().getScheduler().cancelTask(id));
        animTasks.clear();
    }

    // -----------------------------------------------------------------------
    // Spawning — text lines
    // -----------------------------------------------------------------------

    private void spawnTextStands(Shop shop, Location base, List<String> lines) {
        World world = base.getWorld();
        double textYBase = plugin.getConfig().getDouble("hologram.text-y-offset", 2.1);
        double topY = base.getY() + textYBase + (lines.size() - 1) * LINE_SPACING;

        for (int i = 0; i < lines.size(); i++) {
            Location loc = base.clone();
            loc.setY(topY - i * LINE_SPACING);

            ArmorStand as = (ArmorStand) world.spawnEntity(loc, EntityType.ARMOR_STAND);
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

            as.getPersistentDataContainer().set(
                    pdcKey(PDC_KEY),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    shop.getId().toString());
        }
    }

    // -----------------------------------------------------------------------
    // Spawning — item stand with bob+spin animation
    // -----------------------------------------------------------------------

    private void spawnItemStand(Shop shop, Location base) {
        World world = base.getWorld();
        if (world == null) return;

        double itemYBase = plugin.getConfig().getDouble("hologram.item-y-offset", 1.35);
        Location loc = base.clone();
        loc.setY(base.getY() + itemYBase);

        ArmorStand as = (ArmorStand) world.spawnEntity(loc, EntityType.ARMOR_STAND);
        as.setVisible(false);
        as.setCustomNameVisible(false);
        as.setGravity(false);
        as.setInvulnerable(true);
        as.setPersistent(true);
        as.setMarker(false); // needs to be false for equipment to render
        as.setSmall(true);
        as.setCollidable(false);
        as.setCanPickupItems(false);
        as.getEquipment().setHelmet(shop.getItem().clone());

        as.getPersistentDataContainer().set(
                pdcKey(PDC_ITEM_KEY),
                org.bukkit.persistence.PersistentDataType.STRING,
                shop.getId().toString());

        // Bob + spin animation
        // Single task per shop; self-terminates if entity becomes invalid.
        // cancelAnim() is always called before this, so no stacking possible.
        final double baseY = base.getY() + plugin.getConfig().getDouble("hologram.item-y-offset", 1.35);
        final long[] tick  = {0L};

        int taskId = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!as.isValid()) {
                cancelAnim(shop.getId());
                return;
            }
            tick[0]++;

            // Bob
            double bobY = baseY + BOB_AMPLITUDE * Math.sin(2 * Math.PI * tick[0] / (double) BOB_PERIOD);

            // Spin — rotate the armor stand's yaw
            float yaw = (tick[0] * 4.5f) % 360f;

            Location newLoc = as.getLocation();
            newLoc.setY(bobY);
            newLoc.setYaw(yaw);
            as.teleport(newLoc);

        }, 2L, 2L).getTaskId();

        animTasks.put(shop.getId(), taskId);
    }

    // -----------------------------------------------------------------------
    // Removal
    // -----------------------------------------------------------------------

    private void worldScanRemove(UUID shopId) {
        String idStr = shopId.toString();
        org.bukkit.NamespacedKey textKey = pdcKey(PDC_KEY);
        org.bukkit.NamespacedKey itemKey = pdcKey(PDC_ITEM_KEY);

        for (World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!(e instanceof ArmorStand)) continue;
                String tag = pdc(e, textKey);
                if (tag == null) tag = pdc(e, itemKey);
                if (idStr.equals(tag)) e.remove();
            }
        }
    }

    private void cancelAnim(UUID shopId) {
        Integer taskId = animTasks.remove(shopId);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
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
                    "&6&l✦ Shop ✦",
                    "&f{item}",
                    "&e{price} {currency}",
                    "{mode}",
                    "&7Stock: &f{stock}",
                    "&7Bank: &f{bank} {currency}");
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

    private Location shopBase(Shop shop) {
        return shop.getLocation();
    }

    private int getStockSafe(Shop shop) {
        try {
            Location loc = shop.getBlockLocation();
            if (loc == null || !loc.isChunkLoaded()) return -1;
            return plugin.getShopService().getStockCount(shop);
        } catch (Exception e) { return -1; }
    }

    private org.bukkit.NamespacedKey pdcKey(String key) {
        return new org.bukkit.NamespacedKey(plugin, key);
    }

    private static String pdc(Entity e, org.bukkit.NamespacedKey key) {
        if (!e.getPersistentDataContainer().has(key,
                org.bukkit.persistence.PersistentDataType.STRING)) return null;
        return e.getPersistentDataContainer().get(key,
                org.bukkit.persistence.PersistentDataType.STRING);
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
