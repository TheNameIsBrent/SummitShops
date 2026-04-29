package com.oneblock.shops.hologram;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.economy.CurrencyProvider;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Manages shop holograms using Paper 1.19.4+ Display entities.
 *
 * NO server-side spin tasks or teleports. The ItemDisplay uses
 * Billboard.VERTICAL so the Minecraft client handles rotation
 * automatically — zero server overhead.
 */
public class HologramService {

    public static final String PDC_KEY      = "shop_hologram_id";
    public static final String PDC_ITEM_KEY = "shop_item_display_id";

    private final OneBlockShopsPlugin plugin;

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
                Location base = shopBase(shop);
                if (base == null || base.getWorld() == null) return;
                spawnText(shop, base);
                spawnItemDisplay(shop, base);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[HologramService] createOrUpdate failed for " + shop.getId(), e);
            }
        });
    }

    public void remove(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> worldScanRemove(shop.getId()));
    }

    public void cleanupOrphans(World world) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Set<UUID> known = plugin.getShopManager().getAllShops()
                    .stream().map(Shop::getId).collect(Collectors.toSet());

            org.bukkit.NamespacedKey textKey = new org.bukkit.NamespacedKey(plugin, PDC_KEY);
            org.bukkit.NamespacedKey itemKey = new org.bukkit.NamespacedKey(plugin, PDC_ITEM_KEY);

            for (Entity e : world.getEntities()) {
                String tag = null;
                if (e instanceof TextDisplay td) tag = pdc(td, textKey);
                else if (e instanceof ItemDisplay id) tag = pdc(id, itemKey);
                if (tag == null) continue;
                try {
                    if (!known.contains(UUID.fromString(tag))) e.remove();
                } catch (IllegalArgumentException ignored) {
                    e.remove();
                }
            }
        });
    }

    /** Nothing to shut down — no scheduled tasks exist. */
    public void shutdown() {}

    // -----------------------------------------------------------------------
    // Spawning
    // -----------------------------------------------------------------------

    private void spawnText(Shop shop, Location base) {
        World world = base.getWorld();
        double yOffset = plugin.getConfig().getDouble("hologram.text-y-offset", 1.5);
        Location loc = base.clone();
        loc.setY(base.getY() + yOffset);

        TextDisplay td = (TextDisplay) world.spawnEntity(loc, EntityType.TEXT_DISPLAY);
        td.text(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(buildText(shop)));
        td.setAlignment(TextDisplay.TextAlignment.CENTER);
        td.setBillboard(Display.Billboard.CENTER);
        td.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
        td.setSeeThrough(false);
        td.setDefaultBackground(false);
        td.setGravity(false);
        td.setInvulnerable(true);
        td.setPersistent(true);
        td.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, PDC_KEY),
                org.bukkit.persistence.PersistentDataType.STRING,
                shop.getId().toString());
    }

    private void spawnItemDisplay(Shop shop, Location base) {
        if (shop.getItem() == null) return;
        World world = base.getWorld();
        if (world == null) return;

        double yOffset = plugin.getConfig().getDouble("hologram.item-y-offset", 0.75);
        Location loc = base.clone();
        loc.setY(base.getY() + yOffset);

        ItemDisplay id = (ItemDisplay) world.spawnEntity(loc, EntityType.ITEM_DISPLAY);
        id.setItemStack(shop.getItem().clone());

        // VERTICAL billboard = client auto-rotates on Y axis. No server tasks needed.
        id.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
        id.setBillboard(Display.Billboard.VERTICAL);
        id.setTransformation(new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 1, 0),
                new Vector3f(0.6f, 0.6f, 0.6f),
                new AxisAngle4f(0, 0, 1, 0)));
        id.setGravity(false);
        id.setInvulnerable(true);
        id.setPersistent(true);
        id.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, PDC_ITEM_KEY),
                org.bukkit.persistence.PersistentDataType.STRING,
                shop.getId().toString());
    }

    // -----------------------------------------------------------------------
    // Removal
    // -----------------------------------------------------------------------

    private void worldScanRemove(UUID shopId) {
        String idStr = shopId.toString();
        org.bukkit.NamespacedKey textKey = new org.bukkit.NamespacedKey(plugin, PDC_KEY);
        org.bukkit.NamespacedKey itemKey = new org.bukkit.NamespacedKey(plugin, PDC_ITEM_KEY);

        for (World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e instanceof TextDisplay td && idStr.equals(pdc(td, textKey))) {
                    td.remove();
                } else if (e instanceof ItemDisplay disp && idStr.equals(pdc(disp, itemKey))) {
                    disp.remove();
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Text content
    // -----------------------------------------------------------------------

    private String buildText(Shop shop) {
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
        String currName = provOpt.map(CurrencyProvider::getDisplayName).orElse(shop.getCurrencyId());
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

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < template.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(template.get(i)
                    .replace("{item}",     itemName)
                    .replace("{price}",    priceStr)
                    .replace("{currency}", currName)
                    .replace("{mode}",     modeStr)
                    .replace("{stock}",    stockStr)
                    .replace("{bank}",     bankStr));
        }
        return sb.toString();
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

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
