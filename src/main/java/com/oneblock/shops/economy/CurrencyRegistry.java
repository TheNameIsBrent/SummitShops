package com.oneblock.shops.economy;

import com.oneblock.shops.OneBlockShopsPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.logging.Level;

/**
 * Registry of all configured currencies.
 *
 * <p>Currencies are defined in {@code config.yml} under the {@code currencies}
 * list. Each entry specifies the provider (VAULT or ECOBITS) and any
 * provider-specific parameters.</p>
 */
public class CurrencyRegistry {

    private final OneBlockShopsPlugin plugin;

    /** currencyId → CurrencyProvider */
    private final Map<String, CurrencyProvider> providers = new LinkedHashMap<>();

    public CurrencyRegistry(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------

    /**
     * Reads all currency definitions from config and registers their providers.
     * Can be called again on reload.
     */
    public void loadFromConfig() {
        providers.clear();

        List<Map<?, ?>> currencyList = plugin.getConfig().getMapList("currencies");
        for (Map<?, ?> entry : currencyList) {
            try {
                String id          = (String) entry.get("id");
                String providerStr = ((String) entry.get("provider")).toUpperCase();
                String displayName = (String) entry.get("display-name");

                if (id == null || providerStr == null || displayName == null) {
                    plugin.getLogger().warning("[CurrencyRegistry] Skipping invalid currency entry.");
                    continue;
                }

                CurrencyProvider provider = switch (providerStr) {
                    case "VAULT" -> buildVaultProvider(id, displayName);
                    case "ECOBITS" -> buildEcoBitsProvider(id, displayName, entry);
                    default -> {
                        plugin.getLogger().warning(
                                "[CurrencyRegistry] Unknown provider '" + providerStr +
                                "' for currency '" + id + "'. Skipping.");
                        yield null;
                    }
                };

                if (provider != null) {
                    providers.put(id, provider);
                    plugin.getLogger().info("[CurrencyRegistry] Registered currency: " + id +
                            " (" + providerStr + ")");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[CurrencyRegistry] Failed to load currency entry: " + e.getMessage(), e);
            }
        }

        if (providers.isEmpty()) {
            plugin.getLogger().warning("[CurrencyRegistry] No currencies loaded! " +
                    "Check your config.yml.");
        }
    }

    // -----------------------------------------------------------------------
    // Builders
    // -----------------------------------------------------------------------

    private CurrencyProvider buildVaultProvider(String id, String displayName) {
        if (!plugin.hasVault()) {
            plugin.getLogger().warning(
                    "[CurrencyRegistry] Currency '" + id + "' uses VAULT but Vault is not " +
                    "available. This currency will be non-functional.");
            return null;
        }
        return new VaultProvider(id, displayName, plugin.getVaultEconomy());
    }

    private CurrencyProvider buildEcoBitsProvider(String id, String displayName,
                                                   Map<?, ?> entry) {
        String ecoBitsKey = (String) entry.get("ecobits-key");
        if (ecoBitsKey == null) {
            plugin.getLogger().warning(
                    "[CurrencyRegistry] Currency '" + id + "' is ECOBITS but has no " +
                    "'ecobits-key'. Skipping.");
            return null;
        }
        // Check eco is on the server
        if (plugin.getServer().getPluginManager().getPlugin("eco") == null) {
            plugin.getLogger().warning(
                    "[CurrencyRegistry] Currency '" + id + "' uses ECOBITS but the 'eco' " +
                    "plugin is not present. Skipping.");
            return null;
        }
        return new EcoBitsProvider(id, displayName, ecoBitsKey);
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    public Optional<CurrencyProvider> getProvider(String currencyId) {
        return Optional.ofNullable(providers.get(currencyId));
    }

    public Collection<CurrencyProvider> getAllProviders() {
        return Collections.unmodifiableCollection(providers.values());
    }

    public List<String> getCurrencyIds() {
        return new ArrayList<>(providers.keySet());
    }

    public boolean isRegistered(String currencyId) {
        return providers.containsKey(currencyId);
    }
}
