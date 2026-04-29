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
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.logging.Level;

public class HologramService {

    private static final double LINE_GAP  = 0.27;
    private static final double ITEM_OFFSET = -0.3; // y offset of floating item below hologram base
    public  static final String PDC_KEY   = "shop_hologram_id";

    private final OneBlockShopsPlugin plugin;
    /** shopId → list of armorstand UUIDs (runtime cache only; rebuilt on reload) */
    private final Map<UUID, List<UUID>> holoStands = new HashMap<>();
    /** shopId → floating item entity UUID */
    private final Map<UUID, UUID> floatItems = new HashMap<>();

    public HologramService(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void createOrUpdate(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                // Always do a world-scan remove first so reloads don't duplicate stands
                worldScanRemove(shop.getId());

                Location baseLoc = hologramBase(shop);
                if (baseLoc == null || baseLoc.getWorld() == null) return;

                List<String> lines   = buildLines(shop);
                List<UUID>   standIds = new ArrayList<>();
                double topY = baseLoc.getY() + (lines.size() - 1) * LINE_GAP;

                for (int i = 0; i < lines.size(); i++) {
                    Location spawnLoc = baseLoc.clone();
                    spawnLoc.setY(topY - i * LINE_GAP);
                    ArmorStand stand = spawnStand(spawnLoc, lines.get(i), shop.getId());
                    if (stand != null) standIds.add(stand.getUniqueId());
                }
                holoStands.put(shop.getId(), standIds);

                // Floating item between frame and hologram
                spawnFloatingItem(shop, baseLoc);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[HologramService] createOrUpdate failed for " + shop.getId(), e);
            }
        });
    }

    public void remove(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> worldScanRemove(shop.getId()));
    }

    /** Called on chunk load to clean up orphaned stands with no matching shop. */
    public void cleanupOrphans(World world) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Set<UUID> knownIds = plugin.getShopManager().getAllShops()
                    .stream().map(Shop::getId)
                    .collect(java.util.stream.Collectors.toSet());
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ArmorStand stand) {
                    String tag = getShopTag(stand);
                    if (tag == null) continue;
                    try {
                        UUID shopId = UUID.fromString(tag);
                        if (!knownIds.contains(shopId)) stand.remove();
                    } catch (IllegalArgumentException ignored) { stand.remove(); }
                } else if (entity instanceof Item item) {
                    String tag = getItemTag(item);
                    if (tag == null) continue;
                    try {
                        UUID shopId = UUID.fromString(tag);
                        if (!knownIds.contains(shopId)) item.remove();
                    } catch (IllegalArgumentException ignored) { item.remove(); }
                }
            }
        });
    }

    public void shutdown() {
        holoStands.clear();
        floatItems.clear();
    }

    // -----------------------------------------------------------------------
    // Internal — stand management
    // -----------------------------------------------------------------------

    /**
     * Removes ALL entities in any loaded world that are tagged with this shopId.
     * This is robust to server restarts (stands re-persist), unlike the in-memory map.
     */
    private void worldScanRemove(UUID shopId) {
        String idStr = shopId.toString();
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ArmorStand stand) {
                    if (idStr.equals(getShopTag(stand))) stand.remove();
                } else if (entity instanceof Item item) {
                    if (idStr.equals(getItemTag(item))) item.remove();
                }
            }
        }
        holoStands.remove(shopId);
        floatItems.remove(shopId);
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
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setCollidable(false);
        // Tag with shop ID so we can find & remove it after restarts
        tagEntity(stand, shopId);
        return stand;
    }

    private void spawnFloatingItem(Shop shop, Location baseLoc) {
        if (shop.getItem() == null) return;
        World world = baseLoc.getWorld();
        if (world == null) return;

        // Place item halfway between portal frame top and hologram bottom
        Location itemLoc = baseLoc.clone();
        itemLoc.setY(baseLoc.getY() + ITEM_OFFSET);

        Item floatItem = world.dropItem(itemLoc, shop.getItem().clone());
        floatItem.setPickupDelay(Integer.MAX_VALUE);
        floatItem.setGravity(false);
        floatItem.setInvulnerable(true);
        floatItem.setPersistent(true);
        floatItem.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        // Tag so we can find it on restart
        floatItem.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, "shop_float_id"),
                org.bukkit.persistence.PersistentDataType.STRING,
                shop.getId().toString());
        floatItems.put(shop.getId(), floatItem.getUniqueId());
    }

    // -----------------------------------------------------------------------
    // Tag helpers
    // -----------------------------------------------------------------------

    private void tagEntity(ArmorStand stand, UUID shopId) {
        stand.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, PDC_KEY),
                org.bukkit.persistence.PersistentDataType.STRING,
                shopId.toString());
    }

    private String getShopTag(ArmorStand stand) {
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, PDC_KEY);
        if (!stand.getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.STRING))
            return null;
        return stand.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
    }

    private String getItemTag(Item item) {
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, "shop_float_id");
        if (!item.getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.STRING))
            return null;
        return item.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
    }

    // -----------------------------------------------------------------------
    // Hologram content
    // -----------------------------------------------------------------------

    private List<String> buildLines(Shop shop) {
        // Resolve variables once
        String itemName;
        if (shop.getItem() != null && shop.getItem().hasItemMeta()
                && shop.getItem().getItemMeta().hasDisplayName()) {
            itemName = shop.getItem().getItemMeta().getDisplayName();
        } else if (shop.getItem() != null) {
            itemName = prettify(shop.getItem().getType().name());
        } else {
            itemName = "&7Not configured";
        }

        Optional<CurrencyProvider> provOpt = plugin.getCurrencyRegistry().getProvider(shop.getCurrencyId());
        String currName = provOpt.map(CurrencyProvider::getDisplayName).orElse(shop.getCurrencyId());
        String priceStr = shop.isConfigured() ? fmt(shop.getPrice()) : "&cnot set";
        String modeStr  = shop.getMode() == ShopMode.BUY ? "&a▶ BUY" : "&c◀ SELL";
        int stock = getStockSafe(shop);
        String stockStr = stock < 0 ? "?" : String.valueOf(stock);
        String bankStr  = fmt(shop.getBankBalance());

        // Load template lines from config (admins can reshape the hologram by editing config.yml)
        List<String> template = plugin.getConfig().getStringList("hologram.lines");
        if (template.isEmpty()) {
            // Sensible defaults if config section missing
            template = java.util.Arrays.asList(
                "&6&l✦ Shop ✦", "&f{item}", "&e{price} {currency}",
                "{mode}", "&7Stock/Space: &f{stock}", "&7Bank: &f{bank} {currency}");
        }

        List<String> lines = new ArrayList<>();
        for (String tpl : template) {
            lines.add(tpl
                .replace("{item}",     itemName)
                .replace("{price}",    priceStr)
                .replace("{currency}", currName)
                .replace("{mode}",     modeStr)
                .replace("{stock}",    stockStr)
                .replace("{bank}",     bankStr));
        }
        return lines;
    }

    private int getStockSafe(Shop shop) {
        try {
            Location loc = shop.getBlockLocation();
            if (loc == null || !loc.isChunkLoaded()) return -1;
            return plugin.getShopService().getStockCount(shop);
        } catch (Exception e) { return -1; }
    }

    private Location hologramBase(Shop shop) {
        Location loc = shop.getLocation();
        if (loc == null) return null;
        double yOffset = plugin.getConfig().getDouble("hologram.y-offset", 1.5);
        return loc.clone().add(0, yOffset, 0);
    }

    private static String colorize(String s) { return s.replace("&", "\u00A7"); }

    private static String prettify(String enumName) {
        String lower = enumName.replace("_", " ").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
