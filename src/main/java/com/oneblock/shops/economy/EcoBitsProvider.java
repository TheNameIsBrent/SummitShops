package com.oneblock.shops.economy;

import com.willfp.eco.core.Eco;
import com.willfp.eco.core.data.PlayerProfile;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.eco.core.data.keys.PersistentDataKeyType;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * CurrencyProvider backed by EcoBits via eco's {@link PersistentDataKey} API.
 *
 * <h3>How EcoBits works</h3>
 * EcoBits stores each currency as a {@code double} in the player's eco
 * {@link PlayerProfile} under a {@link NamespacedKey} of the form
 * {@code namespace:key} (e.g. {@code ecobits:island_crystals}).
 * We read and write that value directly using the eco Profile API.
 *
 * <p>Offline players are supported because eco loads profile data from its
 * own storage and does not require the player to be online.</p>
 */
public class EcoBitsProvider implements CurrencyProvider {

    private final String currencyId;
    private final String displayName;

    /** The eco PersistentDataKey that EcoBits uses for this currency. */
    private final PersistentDataKey<Double> dataKey;

    /**
     * @param currencyId  plugin-local identifier (e.g. {@code "island_crystals"})
     * @param displayName coloured display name (e.g. {@code "&bCrystals"})
     * @param ecoBitsKey  the NamespacedKey string EcoBits registered
     *                    (e.g. {@code "ecobits:island_crystals"})
     */
    public EcoBitsProvider(String currencyId, String displayName, String ecoBitsKey) {
        this.currencyId  = currencyId;
        this.displayName = displayName;

        // Parse "namespace:key" from the config string
        String[] parts = ecoBitsKey.split(":", 2);
        String namespace = parts.length == 2 ? parts[0] : "ecobits";
        String key       = parts.length == 2 ? parts[1] : ecoBitsKey;

        NamespacedKey namespacedKey = new NamespacedKey(namespace, key);

        // Build a PersistentDataKey that matches EcoBits' registration.
        // EcoBits registers its keys with PersistentDataKeyType.DOUBLE and default 0.0.
        this.dataKey = new PersistentDataKey<>(namespacedKey, PersistentDataKeyType.DOUBLE, 0.0);
    }

    @Override
    public String getCurrencyId() { return currencyId; }

    @Override
    public String getDisplayName() { return displayName; }

    // -----------------------------------------------------------------------
    // Online players
    // -----------------------------------------------------------------------

    @Override
    public double getBalance(Player player) {
        return getProfileBalance(player);
    }

    @Override
    public boolean has(Player player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean withdraw(Player player, double amount) {
        double current = getBalance(player);
        if (current < amount) return false;
        setBalance(player, current - amount);
        return true;
    }

    @Override
    public boolean deposit(Player player, double amount) {
        double current = getBalance(player);
        setBalance(player, current + amount);
        return true;
    }

    // -----------------------------------------------------------------------
    // Offline players
    // -----------------------------------------------------------------------

    @Override
    public double getBalanceOffline(OfflinePlayer player) {
        return getProfileBalance(player);
    }

    @Override
    public boolean hasOffline(OfflinePlayer player, double amount) {
        return getBalanceOffline(player) >= amount;
    }

    @Override
    public boolean withdrawOffline(OfflinePlayer player, double amount) {
        double current = getBalanceOffline(player);
        if (current < amount) return false;
        setBalanceOffline(player, current - amount);
        return true;
    }

    @Override
    public boolean depositOffline(OfflinePlayer player, double amount) {
        double current = getBalanceOffline(player);
        setBalanceOffline(player, current + amount);
        return true;
    }

    // -----------------------------------------------------------------------
    // Profile helpers
    // -----------------------------------------------------------------------

    private double getProfileBalance(OfflinePlayer player) {
        try {
            PlayerProfile profile = PlayerProfile.load(player);
            Double val = profile.read(dataKey);
            return val != null ? val : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void setBalance(OfflinePlayer player, double value) {
        try {
            PlayerProfile profile = PlayerProfile.load(player);
            profile.write(dataKey, Math.max(0.0, value));
        } catch (Exception e) {
            // Log silently; callers check return values
        }
    }

    private void setBalanceOffline(OfflinePlayer player, double value) {
        setBalance(player, value);
    }
}
