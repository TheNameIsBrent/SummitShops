package com.oneblock.shops.listeners;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;
import java.util.UUID;

/**
 * Catches hologram entities being removed from the world for any reason
 * (including /killall, lag-purge plugins, entity-clearing commands) and
 * schedules a respawn if the owning shop still exists.
 *
 * Uses EntityRemoveFromWorldEvent which fires for ALL removal causes.
 */
public class HologramProtectionListener implements Listener {

    private final OneBlockShopsPlugin plugin;
    private final ShopManager shopManager;
    private final HologramService hologramService;

    private final NamespacedKey textKey;
    private final NamespacedKey itemKey;

    public HologramProtectionListener(OneBlockShopsPlugin plugin,
                                      ShopManager shopManager,
                                      HologramService hologramService) {
        this.plugin           = plugin;
        this.shopManager      = shopManager;
        this.hologramService  = hologramService;
        this.textKey          = new NamespacedKey(plugin, HologramService.PDC_KEY);
        this.itemKey          = new NamespacedKey(plugin, HologramService.PDC_ITEM_KEY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        Entity entity = event.getEntity();

        // Only care about our hologram entities
        if (!(entity instanceof TextDisplay) && !(entity instanceof ItemDisplay)) return;

        String shopIdStr = getShopTag(entity);
        if (shopIdStr == null) return;

        UUID shopId;
        try {
            shopId = UUID.fromString(shopIdStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        // Check if the shop still exists — if so, respawn the hologram
        Optional<Shop> shopOpt = shopManager.getById(shopId);
        if (shopOpt.isEmpty()) return;

        Shop shop = shopOpt.get();

        // Reschedule respawn on next tick — the entity is being removed right now
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Confirm shop still exists (could have been picked up in the meantime)
            if (shopManager.getById(shopId).isPresent()) {
                hologramService.createOrUpdate(shop);
            }
        }, 5L);
    }

    private String getShopTag(Entity entity) {
        if (entity.getPersistentDataContainer().has(textKey, PersistentDataType.STRING)) {
            return entity.getPersistentDataContainer().get(textKey, PersistentDataType.STRING);
        }
        if (entity.getPersistentDataContainer().has(itemKey, PersistentDataType.STRING)) {
            return entity.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        }
        return null;
    }
}
