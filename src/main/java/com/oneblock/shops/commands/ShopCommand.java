package com.oneblock.shops.commands;

import com.oneblock.shops.OneBlockShopsPlugin;
import com.oneblock.shops.gui.ShopEditorGUI;
import com.oneblock.shops.shop.Shop;
import com.oneblock.shops.shop.ShopManager;
import com.oneblock.shops.shop.ShopService;
import com.oneblock.shops.util.ShopItemFactory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles the {@code /shop} command tree.
 *
 * <pre>
 *   /shop give <player>      — Give the shop placement item
 *   /shop edit <player>      — Open shop list GUI for a player
 *   /shop tp <player>        — Teleport to the player's first shop
 *   /shop delete <shopId>    — Force-remove a shop by UUID
 *   /shop reload             — Reload config and currencies
 * </pre>
 *
 * <p>All sub-commands require the {@code oneblockshops.admin} permission.</p>
 */
public class ShopCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS =
            Arrays.asList("give", "edit", "tp", "delete", "reload");

    private final OneBlockShopsPlugin plugin;
    private final ShopManager shopManager;
    private final ShopService shopService;

    public ShopCommand(OneBlockShopsPlugin plugin,
                       ShopManager shopManager,
                       ShopService shopService) {
        this.plugin      = plugin;
        this.shopManager = shopManager;
        this.shopService = shopService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!sender.hasPermission("oneblockshops.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give"   -> cmdGive(sender, args);
            case "edit"   -> cmdEdit(sender, args);
            case "tp"     -> cmdTp(sender, args);
            case "delete" -> cmdDelete(sender, args);
            case "reload" -> cmdReload(sender);
            default       -> sendUsage(sender);
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Sub-commands
    // -----------------------------------------------------------------------

    /** /shop give <player> */
    private void cmdGive(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(colorize("&cUsage: /shop give <player>")); return; }
        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) { sender.sendMessage(msg("player-not-found")); return; }

        ItemStack shopItem = ShopItemFactory.createShopItem(plugin);
        target.getInventory().addItem(shopItem);
        sender.sendMessage(colorize("&aGave shop item to &f" + target.getName() + "&a."));
        target.sendMessage(colorize("&aYou received a &6Shop Block&a! Place it to create a shop."));
    }

    /** /shop edit <player> — opens the editor for the player's first shop */
    private void cmdEdit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(colorize("&cOnly players can use this command."));
            return;
        }
        if (args.length < 2) { sender.sendMessage(colorize("&cUsage: /shop edit <player>")); return; }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) { sender.sendMessage(msg("player-not-found")); return; }

        List<Shop> shops = shopManager.getShopsByOwner(target.getUniqueId());
        if (shops.isEmpty()) {
            sender.sendMessage(colorize("&c" + target.getName() + " has no shops."));
            return;
        }

        // Open the list GUI showing all of the player's shops
        new ShopListGUI(plugin, admin, shops).open();
    }

    /** /shop tp <player> */
    private void cmdTp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(colorize("&cOnly players can use this command."));
            return;
        }
        if (args.length < 2) { sender.sendMessage(colorize("&cUsage: /shop tp <player>")); return; }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) { sender.sendMessage(msg("player-not-found")); return; }

        List<Shop> shops = shopManager.getShopsByOwner(target.getUniqueId());
        if (shops.isEmpty()) {
            sender.sendMessage(colorize("&c" + target.getName() + " has no shops."));
            return;
        }

        // Teleport to first valid shop
        for (Shop shop : shops) {
            if (shop.getLocation() != null) {
                admin.teleport(shop.getLocation().add(0, 1, 0));
                sender.sendMessage(colorize("&aTeleported to &f" + target.getName() +
                        "&a's shop at &f" + formatLoc(shop) + "&a."));
                return;
            }
        }
        sender.sendMessage(colorize("&cNo accessible shop locations found."));
    }

    /** /shop delete <shopId> */
    private void cmdDelete(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(colorize("&cUsage: /shop delete <shopId>")); return; }

        UUID shopId;
        try {
            shopId = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(colorize("&cInvalid shop ID."));
            return;
        }

        boolean removed = shopManager.removeShop(shopId);
        if (removed) {
            sender.sendMessage(msg("shop-removed"));
        } else {
            sender.sendMessage(msg("shop-not-found"));
        }
    }

    /** /shop reload */
    private void cmdReload(CommandSender sender) {
        plugin.reload();
        sender.sendMessage(msg("reloaded"));
    }

    // -----------------------------------------------------------------------
    // Tab completion
    // -----------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (!sender.hasPermission("oneblockshops.admin")) return List.of();

        if (args.length == 1) {
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if ("give".equals(sub) || "edit".equals(sub) || "tp".equals(sub)) {
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
            }
        }
        return List.of();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String msg(String key) {
        String raw = plugin.getConfig().getString("messages." + key,
                "&c[Missing message: " + key + "]");
        return colorize(raw);
    }

    private static String colorize(String s) {
        return s.replace("&", "\u00A7");
    }

    private static String formatLoc(Shop shop) {
        return shop.getWorldName() + " " +
               (int) shop.getX() + ", " +
               (int) shop.getY() + ", " +
               (int) shop.getZ();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(colorize("&6OneBlockShops Admin Commands:"));
        sender.sendMessage(colorize("  &f/shop give <player>    &7- Give shop block item"));
        sender.sendMessage(colorize("  &f/shop edit <player>    &7- Open shop editor"));
        sender.sendMessage(colorize("  &f/shop tp <player>      &7- Teleport to player's shop"));
        sender.sendMessage(colorize("  &f/shop delete <shopId>  &7- Force-delete a shop"));
        sender.sendMessage(colorize("  &f/shop reload           &7- Reload config"));
    }
}
