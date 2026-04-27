package com.oneblock.shops.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * A one-shot listener that captures the next chat message from a specific
 * player and forwards it to a callback, then unregisters itself.
 *
 * <p>Used by {@link ShopEditorGUI} to read price values without a command.</p>
 */
public class ChatInputListener implements Listener {

    private final Plugin plugin;
    private final UUID playerUuid;
    private final Consumer<String> callback;

    public ChatInputListener(Plugin plugin, Player player, Consumer<String> callback) {
        this.plugin     = plugin;
        this.playerUuid = player.getUniqueId();
        this.callback   = callback;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!event.getPlayer().getUniqueId().equals(playerUuid)) return;
        event.setCancelled(true); // hide the message from chat

        String input = event.getMessage();
        HandlerList.unregisterAll(this);

        // Run callback on the main thread (Bukkit inventory ops)
        plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(input));
    }
}
