package com.oneblock.shops;

import com.oneblock.shops.commands.ShopCommand;
import com.oneblock.shops.economy.CurrencyRegistry;
import com.oneblock.shops.economy.EcoBitsProvider;
import com.oneblock.shops.economy.VaultProvider;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.listeners.ChunkLoadListener;
import com.oneblock.shops.listeners.ShopListener;
import com.oneblock.shops.shop.ShopManager;
import com.oneblock.shops.shop.ShopService;
import com.oneblock.shops.storage.MariaDBStorage;
import com.oneblock.shops.storage.StorageProvider;
import com.oneblock.shops.storage.YamlStorage;
import com.oneblock.shops.util.ShopItemFactory;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Main plugin entry point for OneBlock Advanced Chest Shops.
 */
public class OneBlockShopsPlugin extends JavaPlugin {

    private static OneBlockShopsPlugin instance;

    private StorageProvider storageProvider;
    private ShopManager shopManager;
    private ShopService shopService;
    private HologramService hologramService;
    private CurrencyRegistry currencyRegistry;
    private Economy vaultEconomy;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config files
        saveDefaultConfig();
        saveResource("shops.yml", false);

        // Initialize the shop item PDC key (must be done before any shop items are checked)
        ShopItemFactory.init(this);

        // Set up Vault economy (optional)
        if (!setupVault()) {
            getLogger().warning("Vault not found or no economy plugin registered. " +
                    "VAULT currency provider will be unavailable.");
        }

        // Initialize currency registry
        currencyRegistry = new CurrencyRegistry(this);
        currencyRegistry.loadFromConfig();

        // Initialize storage
        String storageType = getConfig().getString("storage", "YAML").toUpperCase();
        if ("MYSQL".equals(storageType)) {
            storageProvider = new MariaDBStorage(this);
        } else {
            storageProvider = new YamlStorage(this);
        }
        storageProvider.initialize();

        // Initialize hologram service
        hologramService = new HologramService(this);

        // Initialize shop manager and service
        shopManager = new ShopManager(this, storageProvider, hologramService);
        shopService = new ShopService(this, shopManager, currencyRegistry);

        // Load all shops into memory
        shopManager.loadAll();

        // Register listeners
        getServer().getPluginManager().registerEvents(
                new ShopListener(this, shopManager, shopService, hologramService), this);
        getServer().getPluginManager().registerEvents(
                new ChunkLoadListener(this, shopManager, hologramService), this);

        // Register commands
        ShopCommand shopCommand = new ShopCommand(this, shopManager, shopService);
        getCommand("shop").setExecutor(shopCommand);
        getCommand("shop").setTabCompleter(shopCommand);

        getLogger().info("OneBlockShops enabled successfully.");
    }

    @Override
    public void onDisable() {
        if (shopManager != null) {
            shopManager.saveAll();
        }
        if (storageProvider != null) {
            storageProvider.shutdown();
        }
        if (hologramService != null) {
            hologramService.shutdown();
        }
        getLogger().info("OneBlockShops disabled.");
    }

    /**
     * Reload the plugin: config, currencies, and shops.
     */
    public void reload() {
        reloadConfig();
        currencyRegistry.loadFromConfig();
        shopManager.reload();
        getLogger().info("OneBlockShops reloaded.");
    }

    // -----------------------------------------------------------------------
    // Vault setup
    // -----------------------------------------------------------------------

    private boolean setupVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        vaultEconomy = rsp.getProvider();
        return true;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public static OneBlockShopsPlugin getInstance() {
        return instance;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public ShopService getShopService() {
        return shopService;
    }

    public HologramService getHologramService() {
        return hologramService;
    }

    public CurrencyRegistry getCurrencyRegistry() {
        return currencyRegistry;
    }

    public StorageProvider getStorageProvider() {
        return storageProvider;
    }

    public Economy getVaultEconomy() {
        return vaultEconomy;
    }

    public boolean hasVault() {
        return vaultEconomy != null;
    }
}
