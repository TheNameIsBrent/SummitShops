package com.oneblock.shops.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Abstraction over a currency backend (Vault or EcoBits).
 *
 * <p>All balance operations target a specific named currency identified by
 * the implementation. Each registered currency has exactly one provider.</p>
 */
public interface CurrencyProvider {

    /** The config ID of this currency (e.g. {@code "vault_money"}). */
    String getCurrencyId();

    /** Human-readable display name with colour codes (e.g. {@code "&aCoins"}). */
    String getDisplayName();

    // -----------------------------------------------------------------------
    // Online players
    // -----------------------------------------------------------------------

    /** Returns the balance of an online player. */
    double getBalance(Player player);

    /** Returns {@code true} if the player has at least {@code amount}. */
    boolean has(Player player, double amount);

    /**
     * Withdraws {@code amount} from the player.
     *
     * @return {@code true} on success
     */
    boolean withdraw(Player player, double amount);

    /**
     * Deposits {@code amount} to the player.
     *
     * @return {@code true} on success
     */
    boolean deposit(Player player, double amount);

    // -----------------------------------------------------------------------
    // Offline players (for shop-owner transactions)
    // -----------------------------------------------------------------------

    /** Returns the balance of an offline player. */
    double getBalanceOffline(OfflinePlayer player);

    /** Returns {@code true} if the offline player has at least {@code amount}. */
    boolean hasOffline(OfflinePlayer player, double amount);

    /**
     * Withdraws {@code amount} from an offline player.
     *
     * @return {@code true} on success
     */
    boolean withdrawOffline(OfflinePlayer player, double amount);

    /**
     * Deposits {@code amount} to an offline player.
     *
     * @return {@code true} on success
     */
    boolean depositOffline(OfflinePlayer player, double amount);
}
