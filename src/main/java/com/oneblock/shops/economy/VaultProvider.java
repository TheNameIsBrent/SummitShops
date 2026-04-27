package com.oneblock.shops.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * CurrencyProvider backed by the Vault economy API.
 *
 * <p>Vault works the same for online and offline players, so both
 * code paths delegate to the same {@link Economy} instance.</p>
 */
public class VaultProvider implements CurrencyProvider {

    private final String currencyId;
    private final String displayName;
    private final Economy economy;

    public VaultProvider(String currencyId, String displayName, Economy economy) {
        this.currencyId  = currencyId;
        this.displayName = displayName;
        this.economy     = economy;
    }

    @Override
    public String getCurrencyId() { return currencyId; }

    @Override
    public String getDisplayName() { return displayName; }

    // -----------------------------------------------------------------------
    // Online
    // -----------------------------------------------------------------------

    @Override
    public double getBalance(Player player) {
        return economy.getBalance(player);
    }

    @Override
    public boolean has(Player player, double amount) {
        return economy.has(player, amount);
    }

    @Override
    public boolean withdraw(Player player, double amount) {
        EconomyResponse resp = economy.withdrawPlayer(player, amount);
        return resp.transactionSuccess();
    }

    @Override
    public boolean deposit(Player player, double amount) {
        EconomyResponse resp = economy.depositPlayer(player, amount);
        return resp.transactionSuccess();
    }

    // -----------------------------------------------------------------------
    // Offline (Vault supports OfflinePlayer natively)
    // -----------------------------------------------------------------------

    @Override
    public double getBalanceOffline(OfflinePlayer player) {
        return economy.getBalance(player);
    }

    @Override
    public boolean hasOffline(OfflinePlayer player, double amount) {
        return economy.has(player, amount);
    }

    @Override
    public boolean withdrawOffline(OfflinePlayer player, double amount) {
        EconomyResponse resp = economy.withdrawPlayer(player, amount);
        return resp.transactionSuccess();
    }

    @Override
    public boolean depositOffline(OfflinePlayer player, double amount) {
        EconomyResponse resp = economy.depositPlayer(player, amount);
        return resp.transactionSuccess();
    }
}
