package com.oneblock.shops.storage;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopMode;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/**
 * Logs every shop transaction (buy/sell) to:
 *   1. plugins/OneBlockShops/transactions.log  — always, human-readable
 *   2. MariaDB table shop_transactions          — when MariaDB backend is active
 *
 * All writes happen asynchronously so the main thread is never blocked.
 */
public class TransactionLogger {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final OneBlockShopsPlugin plugin;
    private final File logFile;

    public TransactionLogger(OneBlockShopsPlugin plugin) {
        this.plugin  = plugin;
        this.logFile = new File(plugin.getDataFolder(), "transactions.log");
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Called after every successful transaction.
     *
     * @param player   the player who bought/sold
     * @param shop     the shop involved
     * @param mode     BUY (player received item) or SELL (player gave item)
     */
    public void logSuccess(Player player, Shop shop, ShopMode mode) {
        String itemName = shop.getItem() != null ? shop.getItem().getType().name() : "UNKNOWN";
        int    amount   = shop.getItem() != null ? shop.getItem().getAmount() : 1;
        logEntry(player.getUniqueId().toString(), player.getName(),
                shop, mode, itemName, amount, shop.getPrice(), "SUCCESS");
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void logEntry(String playerUuid, String playerName, Shop shop,
                          ShopMode mode, String itemName, int amount,
                          double price, String result) {

        long now = Instant.now().toEpochMilli();
        String timestamp = FMT.format(Instant.ofEpochMilli(now));

        String line = String.format("[%s] %s (%s) %s %dx %s for %.2f %s | shop=%s owner=%s",
                timestamp, playerName, playerUuid,
                mode == ShopMode.BUY ? "BOUGHT" : "SOLD",
                amount, itemName, price, shop.getCurrencyId(),
                shop.getId(), shop.getOwnerUUID());

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            writeToFile(line);
            writeToDatabase(now, playerUuid, playerName, shop, mode, itemName, amount, price, result);
        });
    }

    private void writeToFile(String line) {
        try {
            // Append mode — creates file if absent
            try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
                pw.println(line);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[TransactionLogger] Could not write to log file", e);
        }
    }

    private void writeToDatabase(long timestampMs, String playerUuid, String playerName,
                                 Shop shop, ShopMode mode, String itemName,
                                 int amount, double price, String result) {

        StorageProvider sp = plugin.getStorageProvider();
        if (!(sp instanceof MariaDBStorage mariaDB)) return; // only log to DB if using MariaDB

        javax.sql.DataSource ds = mariaDB.getDataSource();
        if (ds == null) return;

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO shop_transactions
                    (timestamp_ms, player_uuid, player_name, shop_id, owner_uuid,
                     world, x, y, z, mode, item, amount, price, currency, result)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
             """)) {
            ps.setLong  (1,  timestampMs);
            ps.setString(2,  playerUuid);
            ps.setString(3,  playerName);
            ps.setString(4,  shop.getId().toString());
            ps.setString(5,  shop.getOwnerUUID().toString());
            ps.setString(6,  shop.getWorldName());
            ps.setDouble(7,  shop.getX());
            ps.setDouble(8,  shop.getY());
            ps.setDouble(9,  shop.getZ());
            ps.setString(10, mode.name());
            ps.setString(11, itemName);
            ps.setInt   (12, amount);
            ps.setDouble(13, price);
            ps.setString(14, shop.getCurrencyId());
            ps.setString(15, result);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[TransactionLogger] DB insert failed", e);
        }
    }

    // -----------------------------------------------------------------------
    // Schema init — called by MariaDBStorage.createTable()
    // -----------------------------------------------------------------------

    public static void createTableIfNeeded(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS shop_transactions (
                    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    timestamp_ms  BIGINT       NOT NULL,
                    player_uuid   VARCHAR(36)  NOT NULL,
                    player_name   VARCHAR(64)  NOT NULL,
                    shop_id       VARCHAR(36)  NOT NULL,
                    owner_uuid    VARCHAR(36)  NOT NULL,
                    world         VARCHAR(64)  NOT NULL,
                    x             DOUBLE       NOT NULL,
                    y             DOUBLE       NOT NULL,
                    z             DOUBLE       NOT NULL,
                    mode          VARCHAR(8)   NOT NULL,
                    item          VARCHAR(128) NOT NULL,
                    amount        INT          NOT NULL,
                    price         DOUBLE       NOT NULL,
                    currency      VARCHAR(64)  NOT NULL,
                    result        VARCHAR(32)  NOT NULL,
                    INDEX idx_player  (player_uuid),
                    INDEX idx_shop    (shop_id),
                    INDEX idx_owner   (owner_uuid),
                    INDEX idx_ts      (timestamp_ms)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        }
    }
}
