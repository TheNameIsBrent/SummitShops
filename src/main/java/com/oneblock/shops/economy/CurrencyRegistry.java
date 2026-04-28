package com.oneblock.shops.economy;

import com.oneblock.shops.OneBlockShopsPlugin;

import java.util.*;

public class CurrencyRegistry {

    private static final String VAULT_ID = "vault_money";

    private final OneBlockShopsPlugin plugin;
    private final Map<String, CurrencyProvider> providers = new LinkedHashMap<>();

    public CurrencyRegistry(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadFromConfig() {
        providers.clear();

        if (!plugin.hasVault()) {
            plugin.getLogger().warning("[CurrencyRegistry] Vault not available — no currencies loaded.");
            return;
        }

        String displayName = plugin.getConfig().getString("currency.display-name", "&6Money");
        VaultProvider vaultProvider = new VaultProvider(VAULT_ID, displayName, plugin.getVaultEconomy());
        providers.put(VAULT_ID, vaultProvider);
        plugin.getLogger().info("[CurrencyRegistry] Registered Vault currency: " + VAULT_ID);
    }

    public Optional<CurrencyProvider> getProvider(String currencyId) {
        // If the stored currency id doesn't match (e.g. old data with "money"), fall back to vault
        CurrencyProvider p = providers.get(currencyId);
        if (p == null && !providers.isEmpty()) {
            p = providers.values().iterator().next();
        }
        return Optional.ofNullable(p);
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
