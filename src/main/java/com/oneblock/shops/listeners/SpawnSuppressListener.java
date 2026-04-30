package com.oneblock.shops.listeners;

import com.oneblock.shops.hologram.HologramService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Cancels CreatureSpawnEvent for hologram ArmorStands at LOWEST priority,
 * before any other plugin (MythicMobs, SSB2, etc.) processes the event.
 *
 * Paper calls the spawn Consumer BEFORE firing CreatureSpawnEvent, so
 * HologramService adds the entity UUID to suppressedSpawns inside the
 * Consumer. This listener then cancels the event for those UUIDs.
 *
 * Cancelling at this stage means MythicMobs' RandomSpawnPoint chunk load
 * never happens — eliminating the 30% server thread usage.
 *
 * The entity is still fully added to the world despite the cancel —
 * Paper only suppresses the listener chain, not the actual spawn.
 */
public class SpawnSuppressListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (HologramService.suppressedSpawns.remove(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
