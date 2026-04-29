package com.oneblock.shops.listeners;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopManager;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.Collection;

public class ChunkLoadListener implements Listener {

    private final OneBlockShopsPlugin plugin;
    private final ShopManager shopManager;
    private final HologramService hologramService;

    public ChunkLoadListener(OneBlockShopsPlugin plugin,
                             ShopManager shopManager,
                             HologramService hologramService) {
        this.plugin          = plugin;
        this.shopManager     = shopManager;
        this.hologramService = hologramService;
    }

    /**
     * When a chunk loads, recreate holograms for any shops inside it.
     * worldScanRemove inside createOrUpdate prevents duplicates.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Collection<Shop> allShops = shopManager.getAllShops();
            for (Shop shop : allShops) {
                if (!shop.getWorldName().equals(chunk.getWorld().getName())) continue;
                if (isInChunk(shop, chunk)) {
                    hologramService.createOrUpdate(shop);
                }
            }
        }, 2L);
    }

    /**
     * When a world fully loads, run a one-time orphan scan.
     * This is far cheaper than scanning on every chunk load.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> hologramService.cleanupOrphans(event.getWorld()), 40L);
    }

    private boolean isInChunk(Shop shop, Chunk chunk) {
        int shopChunkX = ((int) Math.floor(shop.getX())) >> 4;
        int shopChunkZ = ((int) Math.floor(shop.getZ())) >> 4;
        return shopChunkX == chunk.getX() && shopChunkZ == chunk.getZ();
    }
}
