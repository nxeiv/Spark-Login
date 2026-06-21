package com.pavitraxd.sparklogin.command;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import com.pavitraxd.sparklogin.migration.MigrationService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;

/**
 * Command to migrate AuthMe accounts to SparkLogin.
 * Usage: /sparklogin migrate authme
 */
public class MigrateAuthMeCommand {
    private final SparkLoginPlugin plugin;

    public MigrateAuthMeCommand(SparkLoginPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sparklogin.admin")) {
            sender.sendMessage(Component.text("§cYou do not have permission to execute this command."));
            return true;
        }

        if (args.length < 2 || !args[1].equalsIgnoreCase("authme")) {
            sender.sendMessage(Component.text("§cUsage: /sparklogin migrate authme"));
            return true;
        }

        sender.sendMessage(Component.text("§6[SparkLogin] §eStarting AuthMe migration..."));

        MigrationService migrationService = new MigrationService(
                plugin,
                plugin.getDatabaseManager(),
                plugin.getAccountDAO()
        );

        migrationService.migrateAuthMe((percentage, message) -> {
            // Show progress in action bar if sender is a player
            if (sender instanceof Player) {
                Player player = (Player) sender;
                showActionBarProgress(player, percentage, message);
            } else {
                // Console gets regular messages
                sender.sendMessage(Component.text("§e" + message));
            }
        }).thenAccept(result -> {
            // Show final summary
            showSummary(sender, result);
        }).exceptionally(ex -> {
            sender.sendMessage(Component.text("§cMigration failed: " + ex.getMessage()));
            ex.printStackTrace();
            return null;
        });

        return true;
    }

    /**
     * Shows a Minecraft-style progress bar in the action bar.
     * Format: AuthMe -> nLogin XX.XX% [=====>] -> X.XXX users.
     */
    private void showActionBarProgress(Player player, int percentage, String message) {
        DecimalFormat df = new DecimalFormat("0.00");
        String percentageStr = df.format(percentage);
        
        // Create progress bar (20 characters total)
        int filled = (int) (percentage / 5.0);
        StringBuilder bar = new StringBuilder("§a");
        for (int i = 0; i < filled; i++) {
            bar.append("=");
        }
        bar.append("§7");
        for (int i = filled; i < 20; i++) {
            bar.append("=");
        }
        
        // Format: AuthMe -> nLogin XX.XX% [bar] -> X.XXX users.
        String progressText = "§6AuthMe §7-> §enLogin §e" + percentageStr + "% §7( " + bar.toString() + " §7) §7-> §e" + df.format(percentage * 0.04) + " users.";
        
        player.sendActionBar(Component.text(progressText));
    }

    /**
     * Shows the migration summary to the sender.
     */
    private void showSummary(CommandSender sender, MigrationService.MigrationResult result) {
        sender.sendMessage(Component.text("§eReading AuthMe database..."));
        sender.sendMessage(Component.text("§aImported: " + result.imported));
        sender.sendMessage(Component.text("§eSkipped Existing: " + result.skippedExisting));
        sender.sendMessage(Component.text("§cUnsupported Hashes: " + result.unsupportedHashes));
        sender.sendMessage(Component.text("§cFailed Imports: " + result.failedImports));
        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("§6======= Migration Summary ======="));
        sender.sendMessage(Component.text("§7Total AuthMe Accounts: §f" + result.totalAuthMeAccounts));
        sender.sendMessage(Component.text("§7Imported: §a" + result.imported));
        sender.sendMessage(Component.text("§7Skipped (Already Exists): §e" + result.skippedExisting));
        sender.sendMessage(Component.text("§7Unsupported Hashes: §c" + result.unsupportedHashes));
        sender.sendMessage(Component.text("§7Failed Imports: §c" + result.failedImports));
        sender.sendMessage(Component.text("§7Duration: §f" + (result.durationMs / 1000.0) + "s"));
        sender.sendMessage(Component.text("§6================================="));
        sender.sendMessage(Component.text("§aMigration completed successfully."));

        if (sender instanceof Player) {
            Player player = (Player) sender;
            player.sendActionBar(Component.text("§aMigration completed successfully."));
        }
    }
}
