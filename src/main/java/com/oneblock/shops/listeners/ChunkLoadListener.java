package com.oneblock.shops.listeners;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopManager;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Collection;

/**
 * Re-creates shop holograms when chunks are loaded, and cleans up
 * cached location data when chunks are unloaded.
 *
 * <p>ArmorStand holograms persist with the world save, but we re-sync
 * on load to ensure stock counts in hologram text are current.</p>
 */
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        // Delay one tick so block states are fully initialised
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Collection<Shop> allShops = shopManager.getAllShops();
            for (Shop shop : allShops) {
                if (!shop.getWorldName().equals(chunk.getWorld().getName())) continue;
                if (isInChunk(shop, chunk)) {
                    hologramService.createOrUpdate(shop);
                }
            }
        }, 2L);

        // Clean up any orphaned hologram stands in this chunk's world
        hologramService.cleanupOrphans(chunk.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        // ArmorStands are removed with the chunk automatically.
        // Orphan cleanup runs on next chunk load.
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private boolean isInChunk(Shop shop, Chunk chunk) {
        int shopChunkX = ((int) shop.getX()) >> 4;
        int shopChunkZ = ((int) shop.getZ()) >> 4;
        return shopChunkX == chunk.getX() && shopChunkZ == chunk.getZ();
    }
}
