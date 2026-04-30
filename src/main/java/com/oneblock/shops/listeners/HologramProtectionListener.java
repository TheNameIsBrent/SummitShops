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
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;
import java.util.UUID;

/**
 * Watches for hologram Display entities being removed externally
 * (entity-clear plugins, /killall, etc.) and respawns them.
 *
 * Display entities (TextDisplay, ItemDisplay) cannot have items stolen
 * and have no hitbox, so no player-interaction protection is needed.
 * The intentionallyRemoving guard in HologramService prevents respawn
 * loops when we remove entities ourselves.
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
        this.plugin          = plugin;
        this.shopManager     = shopManager;
        this.hologramService = hologramService;
        this.textKey         = new NamespacedKey(plugin, HologramService.PDC_KEY);
        this.itemKey         = new NamespacedKey(plugin, HologramService.PDC_ITEM_KEY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof TextDisplay) && !(entity instanceof ItemDisplay)) return;

        String shopIdStr = getShopTag(entity);
        if (shopIdStr == null) return;

        UUID shopId;
        try {
            shopId = UUID.fromString(shopIdStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        // Don't respawn if WE removed it (would cause an infinite loop)
        if (hologramService.isIntentionallyRemoving(shopId)) return;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Optional<Shop> shopOpt = shopManager.getById(shopId);
            if (shopOpt.isPresent()) {
                hologramService.createOrUpdate(shopOpt.get());
            }
        }, 5L);
    }

    private String getShopTag(Entity entity) {
        if (entity.getPersistentDataContainer().has(textKey, PersistentDataType.STRING))
            return entity.getPersistentDataContainer().get(textKey, PersistentDataType.STRING);
        if (entity.getPersistentDataContainer().has(itemKey, PersistentDataType.STRING))
            return entity.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return null;
    }
}
