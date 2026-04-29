package com.oneblock.shops.listeners;

import com.bgsoftware.superiorskyblock.api.events.IslandTransferEvent;
import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.shop.Shop;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * Listens for SSB2 island ownership transfers and updates the ownerUUID
 * on every shop tied to that island so /shop tp <player> keeps working.
 */
public class IslandOwnerListener implements Listener {

    private final OneBlockShopsPlugin plugin;

    public IslandOwnerListener(OneBlockShopsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandTransfer(IslandTransferEvent event) {
        UUID islandId   = event.getIsland().getUniqueId();
        UUID newOwnerID = event.getNewOwner().getUniqueId();

        int updated = 0;
        for (Shop shop : plugin.getShopManager().getAllShops()) {
            if (islandId.equals(shop.getIslandId())) {
                shop.setOwnerUUID(newOwnerID);
                plugin.getShopManager().markDirty(shop);
                updated++;
            }
        }
        if (updated > 0) {
            plugin.getLogger().info("[IslandOwnerListener] Island " + islandId
                    + " transferred → updated " + updated + " shop(s) to new owner "
                    + newOwnerID);
        }
    }
}
