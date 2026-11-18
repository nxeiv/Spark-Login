package com.pavitraxd.sparklogin;

import com.pavitraxd.sparklogin.auth.AuthService;
import com.pavitraxd.sparklogin.auth.PostJoinAuthManager;
import com.pavitraxd.sparklogin.auth.SessionManager;
import com.pavitraxd.sparklogin.command.ChangePasswordCommand;
import com.pavitraxd.sparklogin.command.MigrateAuthMeCommand;
import com.pavitraxd.sparklogin.database.AccountDAO;
import com.pavitraxd.sparklogin.database.DatabaseManager;
import com.pavitraxd.sparklogin.database.model.Account;
import com.pavitraxd.sparklogin.dialog.DialogFactory;
import com.pavitraxd.sparklogin.dialog.Messages;
import com.pavitraxd.sparklogin.listener.ConnectionConfigureListener;
import com.pavitraxd.sparklogin.listener.DialogInputListener;
import com.pavitraxd.sparklogin.listener.PlayerDisconnectListener;
import com.pavitraxd.sparklogin.listener.PostJoinAuthListener;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SparkLogin Main Plugin Class.
 * Handles plugin startup, shutdown, reloading, admin commands, and shared service registration.
 */
public class SparkLoginPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private DatabaseManager databaseManager;
    private AccountDAO accountDAO;
    private Messages messages;
    private DialogFactory dialogFactory;
    private AuthService authService;
    private SessionManager sessionManager;

    // Tracks connections during the pre-join authentication phase
    private final Map<UUID, PlayerConfigurationConnection> activeConnections = new ConcurrentHashMap<>();
    private final Map<UUID, Audience> activeAudiences = new ConcurrentHashMap<>();
    // Pending auth futures: the config listener blocks on these until DialogInputListener completes them
    private final Map<UUID, CompletableFuture<Boolean>> pendingAuthFutures = new ConcurrentHashMap<>();
    private MigrateAuthMeCommand migrateAuthMeCommand;

    // Post-join auth manager (for compatibility paths without the pre-join Dialog API)
    private PostJoinAuthManager postJoinAuthManager;
    private boolean preJoinSupported;
    private final Set<UUID> preJoinHandled = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void onEnable() {
        getLogger().info("=================================================");
        getLogger().info("Initializing SparkLogin designed by PavitraXD...");
        getLogger().info("=================================================");

        saveDefaultConfig();

        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        this.accountDAO = new AccountDAO(this, databaseManager);
        this.messages = new Messages(this);
        this.dialogFactory = new DialogFactory(this);
        this.authService = new AuthService(this);
        this.sessionManager = new SessionManager(this);
        this.migrateAuthMeCommand = new MigrateAuthMeCommand(this);
        this.postJoinAuthManager = new PostJoinAuthManager();

        // Paper's pre-join configuration event is used when available.
        this.preJoinSupported = isClassPresent("io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent");
        getLogger().info("Pre-join Dialog API supported: " + preJoinSupported);

        if (preJoinSupported) {
            registerListener(new ConnectionConfigureListener(this));
        }
        registerListener(new DialogInputListener(this));
        registerListener(new PlayerDisconnectListener(this));
        registerListener(new PostJoinAuthListener(this));

        Objects.requireNonNull(getCommand("sparklogin")).setExecutor(this);
        Objects.requireNonNull(getCommand("sparklogin")).setTabCompleter(this);

        PluginCommand changePassCmd = getCommand("changepassword");
        if (changePassCmd != null) {
            changePassCmd.setExecutor(new ChangePasswordCommand(this));
        }

        PluginCommand accountsCmd = getCommand("accounts");
        if (accountsCmd != null) {
            accountsCmd.setExecutor(new com.pavitraxd.sparklogin.command.AccountsCommand(this));
        }

        getLogger().info("SparkLogin fully activated successfully!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Shutting down SparkLogin...");

        Component shutdownKick = Component.text("Server is performing a reload or restarting. Please reconnect shortly.");
        for (UUID uuid : activeConnections.keySet()) {
            PlayerConfigurationConnection conn = activeConnections.get(uuid);
            if (conn != null) {
                conn.disconnect(shutdownKick);
            }
        }
        activeConnections.clear();
        activeAudiences.clear();

        for (CompletableFuture<Boolean> future : pendingAuthFutures.values()) {
            future.cancel(false);
        }
        pendingAuthFutures.clear();

        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("SparkLogin disabled successfully.");
    }

    private void registerListener(Listener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    public AccountDAO getAccountDAO() {
        return accountDAO;
    }

    public Messages getMessages() {
        return messages;
    }

    public DialogFactory getDialogFactory() {
        return dialogFactory;
    }

    public AuthService getAuthService() {
        return authService;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public PostJoinAuthManager getPostJoinAuthManager() {
        return postJoinAuthManager;
    }

    public boolean isPreJoinSupported() {
        return preJoinSupported;
    }

    public void markPreJoinHandled(UUID uuid) {
        preJoinHandled.add(uuid);
    }

    public boolean wasHandledByPreJoin(UUID uuid) {
        return preJoinHandled.contains(uuid);
    }

    public void clearPreJoinHandled(UUID uuid) {
        preJoinHandled.remove(uuid);
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public void registerActiveJoinConnection(UUID uuid, PlayerConfigurationConnection connection, Audience audience) {
        activeConnections.put(uuid, connection);
        activeAudiences.put(uuid, audience);
    }

    public void deregisterActiveJoinConnection(UUID uuid) {
        activeConnections.remove(uuid);
        activeAudiences.remove(uuid);
    }

    public boolean isPlayerConnecting(UUID uuid) {
        return activeConnections.containsKey(uuid);
    }

    public PlayerConfigurationConnection getActiveConnection(UUID uuid) {
        return activeConnections.get(uuid);
    }

    public Audience getActiveAudience(UUID uuid) {
        return activeAudiences.get(uuid);
    }

    public CompletableFuture<Boolean> createPendingAuth(UUID uuid) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingAuthFutures.put(uuid, future);
        return future;
    }

    public void completePendingAuth(UUID uuid, boolean success) {
        CompletableFuture<Boolean> future = pendingAuthFutures.remove(uuid);
        if (future != null) {
            future.complete(success);
        }
    }

    public void cancelPendingAuth(UUID uuid) {
        CompletableFuture<Boolean> future = pendingAuthFutures.remove(uuid);
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("sparklogin.admin")) {
            Component noperm = messages.getComponent("general.no-permission", "<red>You do not have permission to execute this command.</red>");
            sender.sendMessage(noperm);
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("§6[SparkLogin] §eCommands:"));
            sender.sendMessage(Component.text("§7- §f/sparklogin reload §7| §eReload configurations"));
            sender.sendMessage(Component.text("§7- §f/sparklogin unregister <name/uuid> §7| §eDeletes user account"));
            sender.sendMessage(Component.text("§7- §f/sparklogin resetpassword <name/uuid> §7| §eForces re-registration"));
            sender.sendMessage(Component.text("§7- §f/sparklogin info <name/uuid> §7| §eShows account details"));
            sender.sendMessage(Component.text("§7- §f/sparklogin migrate authme §7| §eMigrate AuthMe accounts"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            this.messages.loadLanguageFile();
            Component reloaded = messages.getComponent("general.reload-success", "<green>Configuration and localization properties successfully reloaded!</green>");
            sender.sendMessage(reloaded);
            return true;
        }

        if (args[0].equalsIgnoreCase("unregister")) {
            if (args.length < 2) {
                sender.sendMessage(Component.text("§cUsage: /sparklogin unregister <player_name_or_uuid>"));
                return true;
            }

            String target = args[1];
            UUID targetUuid = accountDAO.resolveUuid(sender, target);
            if (targetUuid == null) return true;

            sender.sendMessage(Component.text("§eQuerying database for record deletion..."));

            this.accountDAO.deleteAccount(targetUuid).thenAccept(deleted -> {
                if (deleted) {
                    sender.sendMessage(Component.text("§aSuccessfully unregistered profile uuid: " + targetUuid));

                    PlayerConfigurationConnection activeConn = activeConnections.get(targetUuid);
                    if (activeConn != null) {
                        activeConn.disconnect(Component.text("§cYour account has been removed by an administrator. Please register again."));
                        deregisterActiveJoinConnection(targetUuid);
                    }

                    Player onlinePlayer = Bukkit.getPlayer(targetUuid);
                    if (onlinePlayer != null && onlinePlayer.isOnline()) {
                        onlinePlayer.kick(Component.text("§cYour account has been removed by an administrator. Please register again."));
                    }
                } else {
                    sender.sendMessage(Component.text("§cNo profile registration record found for uuid: " + targetUuid));
                }
            }).exceptionally(err -> {
                sender.sendMessage(Component.text("§cDatabase error deleting account: " + err.getMessage()));
                return null;
            });

            return true;
        }

        if (args[0].equalsIgnoreCase("resetpassword")) {
            if (args.length < 2) {
                sender.sendMessage(Component.text("§cUsage: /sparklogin resetpassword <player_name_or_uuid>"));
                return true;
            }

            String target = args[1];
            UUID targetUuid = accountDAO.resolveUuid(sender, target);
            if (targetUuid == null) return true;

            sender.sendMessage(Component.text("§eResetting password for " + target + "..."));

            this.accountDAO.resetPassword(targetUuid).thenAccept(v -> {
                sender.sendMessage(Component.text("§aPassword for " + target + " has been reset. They will be prompted to re-register on next join."));

                Player onlinePlayer = Bukkit.getPlayer(targetUuid);
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    onlinePlayer.kick(Component.text("§cYour password has been reset by an administrator. Please reconnect to register again."));
                }

                PlayerConfigurationConnection activeConn = activeConnections.get(targetUuid);
                if (activeConn != null) {
                    activeConn.disconnect(Component.text("§cYour password has been reset by an administrator. Please reconnect to register again."));
                    deregisterActiveJoinConnection(targetUuid);
                }
            }).exceptionally(err -> {
                sender.sendMessage(Component.text("§cDatabase error resetting password: " + err.getMessage()));
                return null;
            });

            return true;
        }

        if (args[0].equalsIgnoreCase("info")) {
            if (args.length < 2) {
                sender.sendMessage(Component.text("§cUsage: /sparklogin info <player_name_or_uuid>"));
                return true;
            }

            String target = args[1];
            UUID targetUuid = accountDAO.resolveUuid(sender, target);
            if (targetUuid == null) return true;

            sender.sendMessage(Component.text("§eLooking up account information..."));

            this.accountDAO.getAccount(targetUuid).thenAccept(account -> {
                if (account == null) {
                    sender.sendMessage(Component.text("§cNo account found for uuid: " + targetUuid));
                    return;
                }

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                sender.sendMessage(Component.text("§6======= SparkLogin Account Info ======="));
                sender.sendMessage(Component.text("§7UUID: §f" + account.getUuid()));
                sender.sendMessage(Component.text("§7Username: §f" + account.getUsername()));
                sender.sendMessage(Component.text("§7Registered: §f" + sdf.format(new Date(account.getRegisteredAt()))));
                sender.sendMessage(Component.text("§7Last Login: §f" + sdf.format(new Date(account.getLastLogin()))));
                sender.sendMessage(Component.text("§7Registration IP: §f" + (account.getRegistrationIp() != null ? account.getRegistrationIp() : "N/A")));
                sender.sendMessage(Component.text("§7Last Login IP: §f" + (account.getLastLoginIp() != null ? account.getLastLoginIp() : "N/A")));
                sender.sendMessage(Component.text("§7Has Password: §f" + (account.getPasswordHash() != null)));
                sender.sendMessage(Component.text("§7Session Active: §f" + (account.getSessionToken() != null)));
                sender.sendMessage(Component.text("§7Requires Re-registration: §f" + account.isRequiresRegistration()));
                sender.sendMessage(Component.text("§6========================================="));
            }).exceptionally(err -> {
                sender.sendMessage(Component.text("§cDatabase error looking up account: " + err.getMessage()));
                return null;
            });

            return true;
        }

        if (args[0].equalsIgnoreCase("migrate")) {
            return migrateAuthMeCommand.execute(sender, args);
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("sparklogin.admin")) {
            return Collections.emptyList();
        }

        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            if ("reload".startsWith(input)) list.add("reload");
            if ("unregister".startsWith(input)) list.add("unregister");
            if ("resetpassword".startsWith(input)) list.add("resetpassword");
            if ("info".startsWith(input)) list.add("info");
            if ("migrate".startsWith(input)) list.add("migrate");
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("migrate")) {
                String input = args[1].toLowerCase();
                if ("authme".startsWith(input)) list.add("authme");
            } else if (sub.equals("unregister") || sub.equals("resetpassword") || sub.equals("info")) {
                String input = args[1].toLowerCase();
                for (OfflinePlayer op : getServer().getOfflinePlayers()) {
                    String name = op.getName();
                    if (name != null && name.toLowerCase().startsWith(input)) {
                        list.add(name);
                    }
                }
            }
        }

        return list;
    }
}