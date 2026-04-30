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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Collect shops in this chunk that need holograms
            List<Shop> needsHologram = new ArrayList<>();
            for (Shop shop : shopManager.getAllShops()) {
                if (!shop.getWorldName().equals(chunk.getWorld().getName())) continue;
                if (!isInChunk(shop, chunk)) continue;
                // Skip if this shop already has live holograms — don't respawn unnecessarily
                if (hologramService.isLive(shop.getId())) continue;
                needsHologram.add(shop);
            }
            // Stagger spawns by 2 ticks each to spread the CreatureSpawnEvent load
            for (int i = 0; i < needsHologram.size(); i++) {
                final Shop shop = needsHologram.get(i);
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> hologramService.createOrUpdate(shop), i * 2L);
            }
        }, 2L);
    }

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
