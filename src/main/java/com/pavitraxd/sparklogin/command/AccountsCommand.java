package com.pavitraxd.sparklogin.command;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Handles the /accounts command which lists all usernames registered from
 * the caller's IP address. Player-only command with permission sparklogin.player.
 */
public class AccountsCommand implements CommandExecutor {
    private final SparkLoginPlugin plugin;

    public AccountsCommand(SparkLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§cThis command can only be used by players."));
            return true;
        }

        if (!player.hasPermission("sparklogin.player")) {
            Component noperm = plugin.getMessages().getComponent("general.no-permission",
                    "<red>You do not have permission to execute this command.</red>");
            sender.sendMessage(noperm);
            return true;
        }

        String ip = player.getAddress().getAddress().getHostAddress();

        plugin.getAccountDAO().getAccountsForIp(ip).thenAccept(names -> {
            if (names.isEmpty()) {
                player.sendMessage(Component.text("§eNo accounts found registered from your IP address."));
                return;
            }

            int maxAccounts = plugin.getConfig().getInt("registration.max-accounts-per-ip", 3);
            player.sendMessage(Component.text("§6======= Accounts on your IP ======="));
            player.sendMessage(Component.text("§7(" + names.size() + "/" + maxAccounts + " slots used)"));
            for (String name : names) {
                player.sendMessage(Component.text("§7- §f" + name));
            }
            player.sendMessage(Component.text("§6====================================="));
        }).exceptionally(err -> {
            player.sendMessage(Component.text("§cDatabase error listing accounts: " + err.getMessage()));
            return null;
        });

        return true;
    }
}
