package com.pavitraxd.sparklogin.command;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import com.pavitraxd.sparklogin.database.model.Account;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Handles the /changepassword command for in-game password changes.
 * Player-only command with permission sparklogin.player.
 */
public class ChangePasswordCommand implements CommandExecutor {
    private final SparkLoginPlugin plugin;

    public ChangePasswordCommand(SparkLoginPlugin plugin) {
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

        // /changepassword <oldPassword> <newPassword> <confirmPassword>
        if (args.length < 3) {
            player.sendMessage(Component.text("§cUsage: /changepassword <old_password> <new_password> <confirm_password>"));
            return true;
        }

        String oldRaw = args[0];
        String newRaw = args[1];
        String confirmRaw = args[2];

        // Check new password matches confirmation
        if (!newRaw.equals(confirmRaw)) {
            player.sendMessage(Component.text("§cNew password and confirmation do not match."));
            return true;
        }

        UUID uuid = player.getUniqueId();

        plugin.getAccountDAO().getAccount(uuid).thenAccept(account -> {
            if (account == null) {
                player.sendMessage(Component.text("§cYou do not have an account registered."));
                return;
            }

            if (account.getPasswordHash() == null) {
                player.sendMessage(Component.text("§cYour account requires re-registration. Please disconnect and rejoin."));
                return;
            }

            plugin.getAuthService().changePassword(account, oldRaw, newRaw).thenAccept(success -> {
                if (success) {
                    player.sendMessage(Component.text("§aYour password has been changed successfully."));
                } else {
                    // Determine the specific failure reason
                    if (newRaw.equals(oldRaw)) {
                        player.sendMessage(Component.text("§cNew password must be different from your current password."));
                    } else if (!plugin.getAuthService().isPasswordLengthValid(newRaw)) {
                        int min = plugin.getConfig().getInt("registration.min-password-length", 6);
                        int max = plugin.getConfig().getInt("registration.max-password-length", 64);
                        player.sendMessage(Component.text("§cPassword must be between " + min + " and " + max + " characters."));
                    } else {
                        player.sendMessage(Component.text("§cIncorrect current password. Please try again."));
                    }
                }
            });
        }).exceptionally(err -> {
            player.sendMessage(Component.text("§cDatabase error while changing password: " + err.getMessage()));
            return null;
        });

        return true;
    }
}
