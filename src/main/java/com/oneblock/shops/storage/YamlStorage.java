package com.oneblock.shops.storage;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.*;
import java.util.logging.Level;

public class YamlStorage implements StorageProvider {

    private final OneBlockShopsPlugin plugin;
    private File shopsFile;
    private FileConfiguration shopsConfig;

    public YamlStorage(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void initialize() {
        shopsFile = new File(plugin.getDataFolder(), "shops.yml");
        if (!shopsFile.exists()) plugin.saveResource("shops.yml", false);
        shopsConfig = YamlConfiguration.loadConfiguration(shopsFile);
        plugin.getLogger().info("[Storage] Using YAML backend (shops.yml).");
    }

    @Override
    public List<Shop> loadAll() {
        List<Shop> shops = new ArrayList<>();
        ConfigurationSection section = shopsConfig.getConfigurationSection("shops");
        if (section == null) return shops;

        for (String key : section.getKeys(false)) {
            try {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) continue;

                UUID id        = UUID.fromString(key);
                UUID ownerUUID = UUID.fromString(Objects.requireNonNull(s.getString("owner")));
                String world   = Objects.requireNonNull(s.getString("world"));
                double x       = s.getDouble("x");
                double y       = s.getDouble("y");
                double z       = s.getDouble("z");
                String islandStr = s.getString("island_id");
                UUID islandId  = islandStr != null ? UUID.fromString(islandStr) : null;
                String itemB64 = s.getString("item");
                ItemStack item = itemB64 != null ? fromBase64(itemB64) : null;
                double price   = s.getDouble("price");
                ShopMode mode  = ShopMode.valueOf(s.getString("mode", "BUY"));
                String currency = s.getString("currency", "vault_money");
                long createdAt = s.getLong("created_at", System.currentTimeMillis());
                double bank    = s.getDouble("bank_balance", 0.0);

                shops.add(new Shop(id, ownerUUID, world, x, y, z,
                        islandId, item, price, mode, currency, createdAt, bank));

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[YamlStorage] Failed to load shop '" + key + "': " + e.getMessage(), e);
            }
        }
        return shops;
    }

    @Override
    public void save(Shop shop) {
        String path = "shops." + shop.getId();
        shopsConfig.set(path + ".owner",        shop.getOwnerUUID().toString());
        shopsConfig.set(path + ".world",         shop.getWorldName());
        shopsConfig.set(path + ".x",             shop.getX());
        shopsConfig.set(path + ".y",             shop.getY());
        shopsConfig.set(path + ".z",             shop.getZ());
        shopsConfig.set(path + ".island_id",     shop.getIslandId() != null ? shop.getIslandId().toString() : null);
        shopsConfig.set(path + ".item",          shop.getItem() != null ? toBase64(shop.getItem()) : null);
        shopsConfig.set(path + ".price",         shop.getPrice());
        shopsConfig.set(path + ".mode",          shop.getMode().name());
        shopsConfig.set(path + ".currency",      shop.getCurrencyId());
        shopsConfig.set(path + ".created_at",    shop.getCreatedAt());
        shopsConfig.set(path + ".bank_balance",  shop.getBankBalance());
        flushToDisk();
    }

    @Override
    public void saveAll(List<Shop> shops) {
        shopsConfig.set("shops", null);
        for (Shop shop : shops) save(shop);
    }

    @Override
    public void deleteAsync(UUID shopId) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            shopsConfig.set("shops." + shopId, null);
            flushToDisk();
        });
    }

    @Override
    public void shutdown() {
        flushToDisk();
    }

    private synchronized void flushToDisk() {
        try {
            shopsConfig.save(shopsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "[YamlStorage] Failed to save shops.yml", e);
        }
    }

    public static String toBase64(ItemStack item) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
            oos.writeObject(item);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) { return null; }
    }

    public static ItemStack fromBase64(String base64) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bis)) {
            return (ItemStack) ois.readObject();
        } catch (Exception e) { return null; }
    }
}
