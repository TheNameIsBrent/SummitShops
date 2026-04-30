package com.oneblock.shops.listeners;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.hologram.HologramService;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;
import java.util.UUID;

/**
 * Protects hologram ArmorStands.
 *
 * Since stands are non-persistent (never saved to disk), EntityRemoveEvent
 * only needs to handle external removal causes like /killall — chunk unloads
 * don't fire it for non-persistent entities, and the ChunkLoadListener
 * handles recreation on chunk load anyway.
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

    /** Block players from taking the helmet off the item stand. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (isShopStand(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    /** Block players from punching the item stand. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ArmorStand as)) return;
        if (!(event.getDamager() instanceof Player)) return;
        if (isShopStand(as)) event.setCancelled(true);
    }

    /**
     * If a hologram stand is removed externally (e.g. /killall) and its shop
     * still exists, respawn the hologram. Non-persistent entities don't fire
     * this on normal chunk unload, so no loop risk here.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof ArmorStand)) return;

        String shopIdStr = getShopTag(entity);
        if (shopIdStr == null) return;

        UUID shopId;
        try { shopId = UUID.fromString(shopIdStr); }
        catch (IllegalArgumentException e) { return; }

        if (hologramService.isIntentionallyRemoving(shopId)) return;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Optional<Shop> shopOpt = shopManager.getById(shopId);
            if (shopOpt.isPresent()) hologramService.createOrUpdate(shopOpt.get());
        }, 5L);
    }

    private boolean isShopStand(ArmorStand as) {
        return as.getPersistentDataContainer().has(textKey, PersistentDataType.STRING)
            || as.getPersistentDataContainer().has(itemKey, PersistentDataType.STRING);
    }

    private String getShopTag(Entity entity) {
        if (entity.getPersistentDataContainer().has(textKey, PersistentDataType.STRING))
            return entity.getPersistentDataContainer().get(textKey, PersistentDataType.STRING);
        if (entity.getPersistentDataContainer().has(itemKey, PersistentDataType.STRING))
            return entity.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return null;
    }
}
