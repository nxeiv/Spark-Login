package com.pavitraxd.sparklogin.listener;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.pavitraxd.sparklogin.SparkLoginPlugin;
import com.pavitraxd.sparklogin.database.model.Account;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles early pre-join checks during the AsyncPlayerConnectionConfigureEvent (Paper 1.21.11+).
 * <p>
 * The event runs on an async thread, so it is safe to block here.  After showing the
 * login/register dialog we <b>block the thread</b> on a {@link CompletableFuture} that
 * {@link DialogInputListener} will complete when the player submits or cancels.  Only after
 * the future resolves do we call {@code completeReconfiguration()} to let the player into
 * the server — or disconnect them if auth failed / was cancelled.
 */
public class ConnectionConfigureListener implements Listener {
    private final SparkLoginPlugin plugin;

    public ConnectionConfigureListener(SparkLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerConnectionConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        if (connection == null) return;

        PlayerProfile profile = connection.getProfile();
        if (profile == null) return;

        UUID uuid = profile.getId();
        String name = profile.getName();
        if (uuid == null || name == null) return;

        Audience audience = connection.getAudience();

        // Resolve the player's IP address once at the top.
        String ip = resolveIp(connection);

        // Reset any cached login attempts upon reconnect
        plugin.getAuthService().resetAttempts(uuid);

        // Track the connection for dialog callback use
        plugin.registerActiveJoinConnection(uuid, connection, audience);

        // --- Block on the account lookup ---
        Account account;
        try {
            account = plugin.getAccountDAO().getAccount(uuid).join();
        } catch (Exception e) {
            plugin.getLogger().severe("Exception processing pre-join configs for " + name + ": " + e.getMessage());
            connection.disconnect(Component.text("A database connection error occurred. Please try reconnecting."));
            plugin.deregisterActiveJoinConnection(uuid);
            return;
        }

        if (!connection.isConnected()) return;

        // === NEW PLAYER (account == null) ===
        if (account == null) {
            // --- Max accounts per IP check ---
            int maxAccounts = plugin.getConfig().getInt("registration.max-accounts-per-ip", 3);
            if (maxAccounts > 0 && ip != null) {
                try {
                    int existing = plugin.getAccountDAO().countAccountsForIp(ip).join();
                    if (existing >= maxAccounts) {
                        boolean kick = plugin.getConfig().getBoolean("registration.kick-on-limit", true);
                        Component limitMsg = plugin.getMessages().getComponent("register.error.ip-limit",
                                "<red>Too many accounts are registered from your IP address. Registration is disabled for this IP.</red>");
                        if (kick) {
                            connection.disconnect(limitMsg);
                        } else {
                            audience.sendMessage(limitMsg);
                            plugin.completePendingAuth(uuid, false);
                        }
                        plugin.deregisterActiveJoinConnection(uuid);
                        return;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not check IP limit for " + name + ", proceeding with registration.");
                }
            }

            // Show register dialog and block
            showDialogAndBlock(connection, audience, uuid,
                    plugin.getDialogFactory().buildRegisterDialog(name));
            return;
        }

        // === RETURNING PLAYER ===

        // --- Admin password reset → requires re-registration ---
        if (account.isRequiresRegistration()) {
            showDialogAndBlock(connection, audience, uuid,
                    plugin.getDialogFactory().buildAccountResetDialog(name));
            return;
        }

        // --- Session restore (IP change check) ---
        if (plugin.getSessionManager().isSessionValid(account)) {
            // If require-login-on-ip-change is enabled, check whether the IP changed.
            boolean ipChanged = false;
            if (plugin.getConfig().getBoolean("security.require-login-on-ip-change", true)) {
                String lastIp = account.getLastLoginIp();
                if (lastIp != null && ip != null && !ip.equals(lastIp)) {
                    ipChanged = true;
                }
            }

            if (!ipChanged) {
                // Session is valid AND IP unchanged → auto-login.
                plugin.getAccountDAO().updateLastLogin(uuid, System.currentTimeMillis(), ip);
                plugin.getSessionManager().createSession(uuid).join();
                Component conf = plugin.getMessages().getComponent("session.restored", "<green>Your login session was restored automatically. Welcome back!</green>");
                audience.sendMessage(conf);
                plugin.getAuthService().resetAttempts(uuid);
                plugin.deregisterActiveJoinConnection(uuid);
                connection.completeReconfiguration();
                return;
            }
            // IP changed → fall through to login dialog below.
        }

        // --- Show login dialog and block ---
        showDialogAndBlock(connection, audience, uuid,
                plugin.getDialogFactory().buildLoginDialog(name));
    }

    /**
     * Shows a dialog to the player and blocks the current async thread until the
     * dialog response is processed.  On success the player is let into the server;
     * on failure/cancel they are disconnected.
     */
    private void showDialogAndBlock(PlayerConfigurationConnection connection, Audience audience,
                                   UUID uuid, Dialog dialog) {
        audience.showDialog(dialog);

        CompletableFuture<Boolean> authFuture = plugin.createPendingAuth(uuid);

        boolean authenticated;
        try {
            authenticated = authFuture.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            authenticated = false;
        } finally {
            plugin.cancelPendingAuth(uuid);
            plugin.deregisterActiveJoinConnection(uuid);
        }

        if (authenticated && connection.isConnected()) {
            connection.completeReconfiguration();
        } else if (connection.isConnected()) {
            connection.disconnect(Component.text("Authentication failed or timed out."));
        }
    }

    /**
     * Extracts the player's IP address from the connection, falling back gracefully.
     */
    private String resolveIp(PlayerConfigurationConnection connection) {
        try {
            InetSocketAddress addr = connection.getClientAddress();
            if (addr != null && addr.getAddress() != null) {
                return addr.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {}
        try {
            java.net.SocketAddress raw = connection.getAddress();
            if (raw instanceof InetSocketAddress isa && isa.getAddress() != null) {
                return isa.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
