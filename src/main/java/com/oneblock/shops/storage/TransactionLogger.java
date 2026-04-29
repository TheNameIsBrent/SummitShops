package com.oneblock.shops.storage;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.shop.Shop;
import org.bukkit.Location;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/**
 * Logs every shop event to a daily rotating flat file:
 *   plugins/OneBlockShops/logs/transactions-YYYY-MM-DD.log
 *
 * A new file is created automatically each UTC day. Old files are never
 * deleted by the plugin — archive or compress them yourself on a schedule.
 *
 * When the MariaDB backend is active, events are also written to the
 * shop_transactions table for queryable history.
 *
 * All file and DB writes are asynchronous — no main-thread blocking.
 *
 * Event types:
 *   BUY          - customer bought an item from the shop
 *   SELL         - customer sold an item to the shop
 *   BANK_DEPOSIT - owner deposited money into the shop bank
 *   BANK_WITHDRAW- owner withdrew money from the shop bank
 *   STOCK_ADD    - items added to shop stock via the manager
 *   STOCK_REMOVE - items removed from shop stock via the manager
 *   PLACE        - shop block placed (shop created)
 *   PICKUP       - shop picked up (shop destroyed)
 *
 * Log format:
 *   [2026-04-29 14:32:01 UTC] [SHOP-A1B2C3D4] BUY CUSTOMER=Steve(a1b2c3d4) ... | OWNER=Alice(e5f6a7b8)
 *
 * The [SHOP-XXXXXXXX] prefix makes it trivial to grep for all events on one shop:
 *   grep "SHOP-A1B2C3D4" logs/transactions-2026-04-29.log
 */
public class TransactionLogger {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss UTC").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    public enum EventType {
        BUY, SELL, BANK_DEPOSIT, BANK_WITHDRAW, STOCK_ADD, STOCK_REMOVE, PLACE, PICKUP
    }

    private final OneBlockShopsPlugin plugin;
    /** Folder where daily log files are written. */
    private final File logsDir;

    public TransactionLogger(OneBlockShopsPlugin plugin) {
        this.plugin  = plugin;
        this.logsDir = new File(plugin.getDataFolder(), "logs");
        logsDir.mkdirs();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void logBuy(Player player, Shop shop) {
        log(shop, EventType.BUY,
            "CUSTOMER=" + name(player) + " ITEM=" + itemLabel(shop) +
            " QTY=" + qty(shop) + " PRICE=" + fmt(shop.getPrice()) +
            " CURRENCY=" + shop.getCurrencyId());
    }

    public void logSell(Player player, Shop shop) {
        log(shop, EventType.SELL,
            "CUSTOMER=" + name(player) + " ITEM=" + itemLabel(shop) +
            " QTY=" + qty(shop) + " PRICE=" + fmt(shop.getPrice()) +
            " CURRENCY=" + shop.getCurrencyId());
    }

    public void logBankDeposit(Player player, Shop shop, double amount) {
        log(shop, EventType.BANK_DEPOSIT,
            "BY=" + name(player) + " AMOUNT=" + fmt(amount) +
            " CURRENCY=" + shop.getCurrencyId() +
            " BANK_AFTER=" + fmt(shop.getBankBalance()));
    }

    public void logBankWithdraw(Player player, Shop shop, double amount) {
        log(shop, EventType.BANK_WITHDRAW,
            "BY=" + name(player) + " AMOUNT=" + fmt(amount) +
            " CURRENCY=" + shop.getCurrencyId() +
            " BANK_AFTER=" + fmt(shop.getBankBalance()));
    }

    public void logStockAdd(Player player, Shop shop, String itemDesc, int qty) {
        log(shop, EventType.STOCK_ADD,
            "BY=" + name(player) + " ITEM=" + itemDesc + " QTY=" + qty);
    }

    public void logStockRemove(Player player, Shop shop, String itemDesc, int qty) {
        log(shop, EventType.STOCK_REMOVE,
            "BY=" + name(player) + " ITEM=" + itemDesc + " QTY=" + qty);
    }

    public void logPlace(Player player, Shop shop) {
        Location loc = shop.getBlockLocation();
        String locStr = loc != null
                ? loc.getWorld().getName() + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                : "unknown";
        log(shop, EventType.PLACE,
            "BY=" + name(player) + " LOCATION=" + locStr);
    }

    public void logPickup(Player player, Shop shop) {
        log(shop, EventType.PICKUP,
            "BY=" + name(player) +
            " BANK_RETURNED=" + fmt(shop.getBankBalance()) +
            " CURRENCY=" + shop.getCurrencyId());
    }

    // -----------------------------------------------------------------------
    // Core
    // -----------------------------------------------------------------------

    private void log(Shop shop, EventType type, String detail) {
        long now = Instant.now().toEpochMilli();
        String timestamp = TIMESTAMP_FMT.format(Instant.ofEpochMilli(now));
        String dateStr   = DATE_FMT.format(Instant.ofEpochMilli(now));

        String line = "[" + timestamp + "] [SHOP-" + shop.getShortId() + "] "
                    + type.name() + " " + detail
                    + " | OWNER=" + ownerLabel(shop);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            writeToFile(dateStr, line);
            writeToDatabase(now, shop, type, detail);
        });
    }

    /**
     * Writes to plugins/OneBlockShops/logs/transactions-YYYY-MM-DD.log.
     * The file is opened in append mode; a new file starts automatically each day.
     */
    private void writeToFile(String dateStr, String line) {
        File logFile = new File(logsDir, "transactions-" + dateStr + ".log");
        try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
            pw.println(line);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                "[TransactionLogger] Could not write to " + logFile.getName(), e);
        }
    }

    private void writeToDatabase(long tsMs, Shop shop, EventType type, String detail) {
        StorageProvider sp = plugin.getStorageProvider();
        if (!(sp instanceof MariaDBStorage mariaDB)) return;
        javax.sql.DataSource ds = mariaDB.getDataSource();
        if (ds == null) return;

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO shop_transactions
                    (timestamp_ms, shop_id, shop_short_id, owner_uuid,
                     world, x, y, z, event_type, detail)
                VALUES (?,?,?,?,?,?,?,?,?,?)
             """)) {
            Location loc = shop.getBlockLocation();
            ps.setLong  (1,  tsMs);
            ps.setString(2,  shop.getId().toString());
            ps.setString(3,  shop.getShortId());
            ps.setString(4,  shop.getOwnerUUID().toString());
            ps.setString(5,  shop.getWorldName());
            ps.setDouble(6,  loc != null ? loc.getX() : shop.getX());
            ps.setDouble(7,  loc != null ? loc.getY() : shop.getY());
            ps.setDouble(8,  loc != null ? loc.getZ() : shop.getZ());
            ps.setString(9,  type.name());
            ps.setString(10, detail);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                "[TransactionLogger] DB insert failed", e);
        }
    }

    // -----------------------------------------------------------------------
    // Schema — called by MariaDBStorage on startup
    // -----------------------------------------------------------------------

    public static void createTableIfNeeded(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS shop_transactions (
                    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    timestamp_ms  BIGINT        NOT NULL,
                    shop_id       VARCHAR(36)   NOT NULL,
                    shop_short_id VARCHAR(8)    NOT NULL,
                    owner_uuid    VARCHAR(36)   NOT NULL,
                    world         VARCHAR(64)   NOT NULL,
                    x             DOUBLE        NOT NULL,
                    y             DOUBLE        NOT NULL,
                    z             DOUBLE        NOT NULL,
                    event_type    VARCHAR(16)   NOT NULL,
                    detail        VARCHAR(512)  NOT NULL,
                    INDEX idx_shop    (shop_id),
                    INDEX idx_short   (shop_short_id),
                    INDEX idx_owner   (owner_uuid),
                    INDEX idx_ts      (timestamp_ms),
                    INDEX idx_type    (event_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** "PlayerName(uuid8)" */
    private static String name(Player p) {
        return p.getName() + "(" + p.getUniqueId().toString().substring(0, 8) + ")";
    }

    /** "OwnerName(uuid8)" resolved from offline player cache. */
    private String ownerLabel(Shop shop) {
        try {
            org.bukkit.OfflinePlayer op =
                    plugin.getServer().getOfflinePlayer(shop.getOwnerUUID());
            String n = op.getName();
            return (n != null ? n : "?") + "(" + shop.getOwnerUUID().toString().substring(0, 8) + ")";
        } catch (Exception e) {
            return "?(" + shop.getOwnerUUID().toString().substring(0, 8) + ")";
        }
    }

    private static String itemLabel(Shop shop) {
        if (shop.getItem() == null) return "NONE";
        if (shop.getItem().hasItemMeta() && shop.getItem().getItemMeta().hasDisplayName())
            return shop.getItem().getItemMeta().getDisplayName().replaceAll("§.", "");
        return shop.getItem().getType().name();
    }

    private static int qty(Shop shop) {
        return shop.getItem() != null ? shop.getItem().getAmount() : 1;
    }

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
