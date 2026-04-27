package com.oneblock.shops.storage;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopMode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * MariaDB/MySQL storage backend using HikariCP for connection pooling.
 *
 * <p>All blocking SQL operations run off the main thread via the async
 * scheduler or are called during plugin load/shutdown where blocking is safe.</p>
 *
 * <h3>Schema</h3>
 * <pre>{@code
 * CREATE TABLE IF NOT EXISTS shops (
 *   id         VARCHAR(36)  PRIMARY KEY,
 *   owner_uuid VARCHAR(36)  NOT NULL,
 *   world      VARCHAR(64)  NOT NULL,
 *   x          DOUBLE       NOT NULL,
 *   y          DOUBLE       NOT NULL,
 *   z          DOUBLE       NOT NULL,
 *   island_id  VARCHAR(36),
 *   item       MEDIUMTEXT,
 *   price      DOUBLE       NOT NULL DEFAULT 0,
 *   mode       VARCHAR(8)   NOT NULL DEFAULT 'BUY',
 *   currency   VARCHAR(64)  NOT NULL DEFAULT 'vault_money',
 *   created_at BIGINT       NOT NULL
 * );
 * }</pre>
 */
public class MariaDBStorage implements StorageProvider {

    private final OneBlockShopsPlugin plugin;
    private HikariDataSource dataSource;

    public MariaDBStorage(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void initialize() {
        HikariConfig config = new HikariConfig();

        String host     = plugin.getConfig().getString("mysql.host", "localhost");
        int    port     = plugin.getConfig().getInt("mysql.port", 3306);
        String db       = plugin.getConfig().getString("mysql.database", "oneblock_shops");
        String user     = plugin.getConfig().getString("mysql.username", "root");
        String password = plugin.getConfig().getString("mysql.password", "password");

        config.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + db +
                "?useSSL=false&autoReconnect=true&characterEncoding=utf8");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(plugin.getConfig().getInt("mysql.pool-size", 10));
        config.setConnectionTimeout(plugin.getConfig().getLong("mysql.connection-timeout", 30000));
        config.setIdleTimeout(plugin.getConfig().getLong("mysql.idle-timeout", 600000));
        config.setMaxLifetime(plugin.getConfig().getLong("mysql.max-lifetime", 1800000));
        config.setPoolName("OneBlockShops-Pool");

        // MariaDB-specific optimisations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        try {
            dataSource = new HikariDataSource(config);
            createTable();
            plugin.getLogger().info("[Storage] Using MariaDB backend.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "[MariaDB] Failed to connect. Falling back gracefully.", e);
            dataSource = null;
        }
    }

    private void createTable() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS shops (
                    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
                    owner_uuid VARCHAR(36)  NOT NULL,
                    world      VARCHAR(64)  NOT NULL,
                    x          DOUBLE       NOT NULL,
                    y          DOUBLE       NOT NULL,
                    z          DOUBLE       NOT NULL,
                    island_id  VARCHAR(36),
                    item       MEDIUMTEXT,
                    price      DOUBLE       NOT NULL DEFAULT 0,
                    mode       VARCHAR(8)   NOT NULL DEFAULT 'BUY',
                    currency   VARCHAR(64)  NOT NULL DEFAULT 'vault_money',
                    created_at BIGINT       NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        }
    }

    @Override
    public List<Shop> loadAll() {
        List<Shop> shops = new ArrayList<>();
        if (dataSource == null) return shops;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM shops");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                try {
                    UUID id        = UUID.fromString(rs.getString("id"));
                    UUID owner     = UUID.fromString(rs.getString("owner_uuid"));
                    String world   = rs.getString("world");
                    double x       = rs.getDouble("x");
                    double y       = rs.getDouble("y");
                    double z       = rs.getDouble("z");
                    String islandStr = rs.getString("island_id");
                    UUID islandId  = islandStr != null ? UUID.fromString(islandStr) : null;
                    String itemB64 = rs.getString("item");
                    ItemStack item = itemB64 != null ? YamlStorage.fromBase64(itemB64) : null;
                    double price   = rs.getDouble("price");
                    ShopMode mode  = ShopMode.valueOf(rs.getString("mode"));
                    String currency = rs.getString("currency");
                    long createdAt = rs.getLong("created_at");

                    shops.add(new Shop(id, owner, world, x, y, z,
                            islandId, item, price, mode, currency, createdAt));
                } catch (Exception e) {
                    plugin.getLogger().warning("[MariaDB] Skipping corrupt row: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[MariaDB] loadAll failed", e);
        }
        return shops;
    }

    @Override
    public void save(Shop shop) {
        if (dataSource == null) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO shops
                    (id, owner_uuid, world, x, y, z, island_id, item, price, mode, currency, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    owner_uuid=VALUES(owner_uuid),
                    world=VALUES(world),
                    x=VALUES(x), y=VALUES(y), z=VALUES(z),
                    island_id=VALUES(island_id),
                    item=VALUES(item),
                    price=VALUES(price),
                    mode=VALUES(mode),
                    currency=VALUES(currency)
             """)) {

            ps.setString(1, shop.getId().toString());
            ps.setString(2, shop.getOwnerUUID().toString());
            ps.setString(3, shop.getWorldName());
            ps.setDouble(4, shop.getX());
            ps.setDouble(5, shop.getY());
            ps.setDouble(6, shop.getZ());
            ps.setString(7, shop.getIslandId() != null ? shop.getIslandId().toString() : null);
            ps.setString(8, shop.getItem() != null ? YamlStorage.toBase64(shop.getItem()) : null);
            ps.setDouble(9, shop.getPrice());
            ps.setString(10, shop.getMode().name());
            ps.setString(11, shop.getCurrencyId());
            ps.setLong(12, shop.getCreatedAt());
            ps.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[MariaDB] save failed for " + shop.getId(), e);
        }
    }

    @Override
    public void saveAll(List<Shop> shops) {
        shops.forEach(this::save);
    }

    @Override
    public void deleteAsync(UUID shopId) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (dataSource == null) return;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM shops WHERE id = ?")) {
                ps.setString(1, shopId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[MariaDB] delete failed for " + shopId, e);
            }
        });
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("[MariaDB] Connection pool closed.");
        }
    }
}
