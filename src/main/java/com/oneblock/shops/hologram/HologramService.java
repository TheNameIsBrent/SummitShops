package com.oneblock.shops.hologram;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.economy.CurrencyProvider;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Hologram service using Display entities ONLY (TextDisplay + ItemDisplay).
 *
 * WHY Display entities:
 *   - NOT LivingEntity — CreatureSpawnEvent never fires. MythicMobs, SSB2
 *     entity limits, RoseStacker — none intercept Display spawns.
 *   - No ArmorStand = nothing to steal, no manipulation events needed.
 *
 * ARCHITECTURE:
 *   - One TextDisplay per shop: all lines as a single multi-line Component,
 *     Billboard.CENTER so it always faces the player.
 *   - One ItemDisplay per shop: bob + spin animation.
 *   - ONE global scheduler task for all ItemDisplay animations.
 *   - intentionallyRemoving prevents respawn loops.
 */
public class HologramService {

    public static final String PDC_KEY      = "shop_hologram_id";
    public static final String PDC_ITEM_KEY = "shop_item_display_id";

    private static final double BOB_AMPLITUDE  = 0.08;
    private static final int    BOB_PERIOD     = 80;
    private static final int    ANIM_INTERVAL  = 3;
    private static final float  DEG_PER_UPDATE = 6.75f;

    private final OneBlockShopsPlugin plugin;

    private final Map<UUID, UUID> itemDisplayIds       = new HashMap<>();
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
                spawnTextDisplay(shop, base);
                if (shop.getItem() != null) spawnItemDisplay(shop, base);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[HologramService] createOrUpdate failed for " + shop.getId(), e);
            }
        });
    }

    public void remove(Shop shop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            itemDisplayIds.remove(shop.getId());
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
                if (!(e instanceof TextDisplay) && !(e instanceof ItemDisplay)) continue;
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
        itemDisplayIds.clear();
    }

    // -----------------------------------------------------------------------
    // Spawning
    // -----------------------------------------------------------------------

    private void spawnTextDisplay(Shop shop, Location base) {
        World world = base.getWorld();
        double textYBase = base.getY() + plugin.getConfig().getDouble("hologram.text-y-offset", 2.1);
        List<String> lines = buildLines(shop);

        // One TextDisplay per line — each stores a tiny plain Component in NBT
        // instead of one massive nested legacy component tree.
        // Lines are stacked 0.27 blocks apart, top line first.
        for (int i = 0; i < lines.size(); i++) {
            final int lineIndex = i;
            final String lineText = lines.get(i);
            Location loc = base.clone();
            loc.setY(textYBase + (lines.size() - 1 - i) * 0.27);

            world.spawn(loc, TextDisplay.class, display -> {
                // Parse &-codes into a Component without the heavy legacy serializer
                display.text(parseColors(lineText));
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setBillboard(Display.Billboard.CENTER);
                display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                display.setDefaultBackground(false);
                display.setSeeThrough(false);
                display.setGravity(false);
                display.setInvulnerable(true);
                display.setPersistent(true);
                display.setLineWidth(200);
                pdcSet(display, key(PDC_KEY), shop.getId().toString());
            });
        }
    }

    /**
     * Converts a simple &-color-coded string to an Adventure Component
     * WITHOUT using LegacyComponentSerializer (which causes a deep
     * recursive NBT decode on every SynchedEntityData equality check).
     *
     * Only handles &0-9, &a-f, &l, &o, &n, &m, &r — sufficient for hologram lines.
     * The resulting component is a flat list of styled text nodes, which
     * serializes to tiny NBT compared to the legacy serializer's nested tree.
     */
    private static Component parseColors(String text) {
        net.kyori.adventure.text.TextComponent.Builder builder =
                Component.text();
        StringBuilder current = new StringBuilder();
        net.kyori.adventure.text.format.Style.Builder style =
                net.kyori.adventure.text.format.Style.style();

        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if ((ch == '&' || ch == '§') && i + 1 < text.length()) {
                // Flush current text with current style
                if (current.length() > 0) {
                    builder.append(Component.text(current.toString(), style.build()));
                    current.setLength(0);
                }
                char code = Character.toLowerCase(text.charAt(i + 1));
                style = applyCode(style, code);
                i += 2;
            } else {
                current.append(ch);
                i++;
            }
        }
        if (current.length() > 0) {
            builder.append(Component.text(current.toString(), style.build()));
        }
        return builder.build();
    }

    private static net.kyori.adventure.text.format.Style.Builder applyCode(
            net.kyori.adventure.text.format.Style.Builder style, char code) {
        switch (code) {
            case '0' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.BLACK); }
            case '1' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.DARK_BLUE); }
            case '2' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.DARK_GREEN); }
            case '3' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.DARK_AQUA); }
            case '4' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.DARK_RED); }
            case '5' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.DARK_PURPLE); }
            case '6' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.GOLD); }
            case '7' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.GRAY); }
            case '8' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.DARK_GRAY); }
            case '9' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.BLUE); }
            case 'a' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.GREEN); }
            case 'b' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.AQUA); }
            case 'c' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.RED); }
            case 'd' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.LIGHT_PURPLE); }
            case 'e' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.YELLOW); }
            case 'f' -> { return net.kyori.adventure.text.format.Style.style().color(NamedTextColor.WHITE); }
            case 'l' -> { return style.decoration(TextDecoration.BOLD, true); }
            case 'o' -> { return style.decoration(TextDecoration.ITALIC, true); }
            case 'n' -> { return style.decoration(TextDecoration.UNDERLINED, true); }
            case 'm' -> { return style.decoration(TextDecoration.STRIKETHROUGH, true); }
            case 'r' -> { return net.kyori.adventure.text.format.Style.style(); }
            default  -> { return style; }
        }
    }

    private void spawnItemDisplay(Shop shop, Location base) {
        World world = base.getWorld();
        if (world == null) return;

        double itemY = base.getY() + plugin.getConfig().getDouble("hologram.item-y-offset", 1.35);
        Location loc = base.clone();
        loc.setY(itemY);

        ItemDisplay id = world.spawn(loc, ItemDisplay.class, display -> {
            display.setItemStack(shop.getItem().clone());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            display.setBillboard(Display.Billboard.FIXED);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setPersistent(true);
            display.setInterpolationDuration(ANIM_INTERVAL);
            display.setInterpolationDelay(0);
            display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 1, 0),
                    new Vector3f(0.65f, 0.65f, 0.65f),
                    new AxisAngle4f(0, 0, 1, 0)));
            pdcSet(display, key(PDC_ITEM_KEY), shop.getId().toString());
        });

        itemDisplayIds.put(shop.getId(), id.getUniqueId());
        maybeStartGlobalTask();
    }

    // -----------------------------------------------------------------------
    // Global animation task
    // -----------------------------------------------------------------------

    private void maybeStartGlobalTask() {
        if (globalTaskId != -1) return;
        globalTaskId = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            globalTick++;
            double bobY = BOB_AMPLITUDE * Math.sin(2 * Math.PI * globalTick / (double) BOB_PERIOD);
            float  rad  = (float) Math.toRadians((globalTick * DEG_PER_UPDATE) % 360f);

            for (Map.Entry<UUID, UUID> entry : new ArrayList<>(itemDisplayIds.entrySet())) {
                UUID shopId   = entry.getKey();
                UUID entityId = entry.getValue();

                Entity entity = plugin.getServer().getEntity(entityId);
                if (!(entity instanceof ItemDisplay display)) {
                    itemDisplayIds.remove(shopId);
                    continue;
                }

                Shop shop = plugin.getShopManager().getById(shopId).orElse(null);
                if (shop == null) { itemDisplayIds.remove(shopId); continue; }

                double baseY = shop.getLocation() != null
                        ? shop.getLocation().getY()
                          + plugin.getConfig().getDouble("hologram.item-y-offset", 1.35)
                        : display.getLocation().getY();

                Location newLoc = display.getLocation();
                newLoc.setY(baseY + bobY);
                display.teleport(newLoc);

                display.setInterpolationDelay(0);
                display.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(0, 0, 1, 0),
                        new Vector3f(0.65f, 0.65f, 0.65f),
                        new AxisAngle4f(rad, 0, 1, 0)));
            }

            if (itemDisplayIds.isEmpty()) maybeStopGlobalTask();
        }, 2L, ANIM_INTERVAL).getTaskId();
    }

    private void maybeStopGlobalTask() {
        if (!itemDisplayIds.isEmpty() || globalTaskId == -1) return;
        plugin.getServer().getScheduler().cancelTask(globalTaskId);
        globalTaskId = -1;
    }

    // -----------------------------------------------------------------------
    // Removal
    // -----------------------------------------------------------------------

    private void worldScanRemove(UUID shopId) {
        itemDisplayIds.remove(shopId);
        intentionallyRemoving.add(shopId);
        try {
            String idStr = shopId.toString();
            NamespacedKey textKey = key(PDC_KEY);
            NamespacedKey itemKey = key(PDC_ITEM_KEY);
            for (World world : plugin.getServer().getWorlds()) {
                for (Entity e : world.getEntities()) {
                    if (!(e instanceof TextDisplay) && !(e instanceof ItemDisplay)) continue;
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

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
