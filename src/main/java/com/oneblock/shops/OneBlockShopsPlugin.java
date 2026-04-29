package com.oneblock.shops;

import com.oneblock.shops.commands.ShopCommand;
import com.oneblock.shops.economy.CurrencyRegistry;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.listeners.ChunkLoadListener;
import com.oneblock.shops.listeners.IslandOwnerListener;
import com.oneblock.shops.listeners.ShopListener;
import com.oneblock.shops.shop.ShopManager;
import com.oneblock.shops.shop.ShopService;
import com.oneblock.shops.storage.MariaDBStorage;
import com.oneblock.shops.storage.TransactionLogger;
import com.oneblock.shops.storage.StorageProvider;
import com.oneblock.shops.storage.YamlStorage;
import com.oneblock.shops.util.ShopItemFactory;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class OneBlockShopsPlugin extends JavaPlugin {

    private static OneBlockShopsPlugin instance;

    private StorageProvider storageProvider;
    private ShopManager shopManager;
    private ShopService shopService;
    private HologramService hologramService;
    private CurrencyRegistry currencyRegistry;
    private Economy vaultEconomy;
    private TransactionLogger transactionLogger;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResource("shops.yml", false);

        ShopItemFactory.init(this);

        // Vault — attempt now, but also lazily on first use
        setupVault();
        if (vaultEconomy == null) {
            getLogger().warning("Vault economy not found yet — will retry on first use.");
        }

        // Currency registry (single Vault currency)
        currencyRegistry = new CurrencyRegistry(this);
        currencyRegistry.loadFromConfig();

        // Storage
        String storageType = getConfig().getString("storage", "YAML").toUpperCase();
        storageProvider = "MYSQL".equals(storageType) ? new MariaDBStorage(this) : new YamlStorage(this);
        storageProvider.initialize();

        // Transaction logger
        transactionLogger = new TransactionLogger(this);

        // Services
        hologramService = new HologramService(this);
        shopManager     = new ShopManager(this, storageProvider, hologramService);
        shopService     = new ShopService(this, shopManager, currencyRegistry);

        shopManager.loadAll();

        getServer().getPluginManager().registerEvents(
                new ShopListener(this, shopManager, shopService, hologramService), this);
        getServer().getPluginManager().registerEvents(
                new ChunkLoadListener(this, shopManager, hologramService), this);
        getServer().getPluginManager().registerEvents(
                new IslandOwnerListener(this), this);

        ShopCommand shopCommand = new ShopCommand(this, shopManager, shopService);
        getCommand("shop").setExecutor(shopCommand);
        getCommand("shop").setTabCompleter(shopCommand);

        getLogger().info("OneBlockShops enabled.");
    }

    @Override
    public void onDisable() {
        if (shopManager   != null) shopManager.saveAll();
        if (storageProvider != null) storageProvider.shutdown();
        if (hologramService != null) hologramService.shutdown();
        getLogger().info("OneBlockShops disabled.");
    }

    public void reload() {
        reloadConfig();
        currencyRegistry.loadFromConfig();
        shopManager.reload();
        getLogger().info("OneBlockShops reloaded.");
    }

    // -----------------------------------------------------------------------
    // Vault — lazy so it works even if Vault loads after us
    // -----------------------------------------------------------------------

    private boolean setupVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        vaultEconomy = rsp.getProvider();
        return true;
    }

    /** Returns the Vault economy, attempting lazy init if not yet available. */
    public Economy getVaultEconomy() {
        if (vaultEconomy == null) setupVault();
        return vaultEconomy;
    }

    public boolean hasVault() {
        if (vaultEconomy == null) setupVault();
        return vaultEconomy != null;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public static OneBlockShopsPlugin getInstance() { return instance; }
    public ShopManager getShopManager()             { return shopManager; }
    public ShopService getShopService()             { return shopService; }
    public HologramService getHologramService()     { return hologramService; }
    public CurrencyRegistry getCurrencyRegistry()   { return currencyRegistry; }
    public StorageProvider getStorageProvider()     { return storageProvider; }
    public TransactionLogger getTransactionLogger()  { return transactionLogger; }
}
