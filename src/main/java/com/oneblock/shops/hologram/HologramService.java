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

public class HologramService {

    private static final double LINE_GAP = 0.25;
    public static final String PDC_KEY = "shop_hologram_id";

    private final OneBlockShopsPlugin plugin;
    private final Map<UUID, List<UUID>> holoStands = new HashMap<>();

    public HologramService(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
    }

    public void createOrUpdate(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                removeStands(shop.getId());
                Location baseLoc = hologramBase(shop);
                if (baseLoc == null || baseLoc.getWorld() == null) return;

                List<String> lines = buildLines(shop);
                List<UUID> standIds = new ArrayList<>();
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
                        "[HologramService] createOrUpdate failed for " + shop.getId(), e);
            }
        });
    }

    public void remove(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> removeStands(shop.getId()));
    }

    public void cleanupOrphans(World world) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Set<UUID> knownIds = plugin.getShopManager().getAllShops()
                    .stream().map(Shop::getId)
                    .collect(java.util.stream.Collectors.toSet());
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof ArmorStand stand)) continue;
                String tag = getShopTag(stand);
                if (tag == null) continue;
                try {
                    UUID shopId = UUID.fromString(tag);
                    if (!knownIds.contains(shopId)) stand.remove();
                } catch (IllegalArgumentException ignored) {
                    stand.remove();
                }
            }
        });
    }

    public void shutdown() { holoStands.clear(); }

    private void removeStands(UUID shopId) {
        List<UUID> ids = holoStands.remove(shopId);
        if (ids == null) return;
        for (UUID uid : ids) {
            for (World world : plugin.getServer().getWorlds()) {
                Entity entity = world.getEntity(uid);
                if (entity != null) { entity.remove(); break; }
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
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setCollidable(false);
        stand.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, PDC_KEY),
                org.bukkit.persistence.PersistentDataType.STRING,
                shopId.toString());
        return stand;
    }

    private String getShopTag(ArmorStand stand) {
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, PDC_KEY);
        if (!stand.getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.STRING))
            return null;
        return stand.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
    }

    private List<String> buildLines(Shop shop) {
        List<String> lines = new ArrayList<>();

        lines.add("&6&l✦ Shop ✦");

        // Item name
        String itemName;
        if (shop.getItem() != null && shop.getItem().hasItemMeta()
                && shop.getItem().getItemMeta().hasDisplayName()) {
            itemName = shop.getItem().getItemMeta().getDisplayName();
        } else if (shop.getItem() != null) {
            itemName = prettify(shop.getItem().getType().name());
        } else {
            itemName = "&7Not configured";
        }
        lines.add("&f" + itemName);

        // Price + currency
        Optional<CurrencyProvider> provOpt = plugin.getCurrencyRegistry().getProvider(shop.getCurrencyId());
        String currName = provOpt.map(CurrencyProvider::getDisplayName).orElse("&7" + shop.getCurrencyId());
        lines.add(shop.isConfigured() ? "&e" + fmt(shop.getPrice()) + " " + currName : "&7Price: &cnot set");

        // Mode
        lines.add(shop.getMode() == ShopMode.BUY ? "&a▶ BUY" : "&c◀ SELL");

        // Stock / space
        int stock = getStockSafe(shop);
        String stockStr = stock < 0 ? "?" : String.valueOf(stock);
        lines.add(shop.getMode() == ShopMode.BUY ? "&7Stock: &f" + stockStr : "&7Space: &f" + stockStr);

        // Bank balance
        lines.add("&7Bank: &f" + fmt(shop.getBankBalance()) + " " + currName);

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
