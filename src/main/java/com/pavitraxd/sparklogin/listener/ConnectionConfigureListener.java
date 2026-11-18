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
 * Handles pre-join authentication during Paper's asynchronous connection configuration stage.
 * The connection remains in configuration until the authentication dialog is completed.
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
        String ip = resolveIp(connection);

        plugin.getAuthService().resetAttempts(uuid);
        plugin.registerActiveJoinConnection(uuid, connection, audience);
        plugin.markPreJoinHandled(uuid);

        Account account;
        try {
            account = plugin.getAccountDAO().getAccount(uuid).join();
        } catch (Exception e) {
            plugin.getLogger().severe("Exception processing pre-join authentication for " + name + ": " + e.getMessage());
            connection.disconnect(Component.text("A database connection error occurred. Please try reconnecting."));
            plugin.deregisterActiveJoinConnection(uuid);
            return;
        }

        if (!connection.isConnected()) {
            plugin.deregisterActiveJoinConnection(uuid);
            return;
        }

        if (account == null) {
            int maxAccounts = plugin.getConfig().getInt("registration.max-accounts-per-ip", 3);
            if (maxAccounts > 0 && ip != null) {
                try {
                    int existing = plugin.getAccountDAO().countAccountsForIp(ip).join();
                    if (existing >= maxAccounts) {
                        boolean kick = plugin.getConfig().getBoolean("registration.kick-on-limit", true);
                        Component limitMsg = plugin.getMessages().getComponent(
                                "register.error.ip-limit",
                                "<red>Too many accounts are registered from your IP address. Registration is disabled for this IP.</red>"
                        );

                        if (kick) {
                            connection.disconnect(limitMsg);
                            plugin.deregisterActiveJoinConnection(uuid);
                            return;
                        }

                        // The configured non-kick mode warns the player but still lets them register.
                        audience.sendMessage(limitMsg);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not check IP account limit for " + name + "; proceeding with registration.");
                }
            }

            showDialogAndBlock(connection, audience, uuid, plugin.getDialogFactory().buildRegisterDialog(name));
            return;
        }

        if (account.isRequiresRegistration()) {
            showDialogAndBlock(connection, audience, uuid, plugin.getDialogFactory().buildAccountResetDialog(name));
            return;
        }

        if (plugin.getSessionManager().isSessionValid(account)) {
            boolean ipChanged = false;
            if (plugin.getConfig().getBoolean("security.require-login-on-ip-change", true)) {
                String lastIp = account.getLastLoginIp();
                if (lastIp != null && ip != null && !ip.equals(lastIp)) {
                    ipChanged = true;
                }
            }

            if (!ipChanged) {
                plugin.getAccountDAO().updateLastLogin(uuid, System.currentTimeMillis(), ip);
                plugin.getSessionManager().createSession(uuid).join();
                Component restored = plugin.getMessages().getComponent(
                        "session.restored",
                        "<green>Your login session was restored automatically. Welcome back!</green>"
                );
                audience.sendMessage(restored);
                plugin.getAuthService().resetAttempts(uuid);
                plugin.deregisterActiveJoinConnection(uuid);
                connection.completeReconfiguration();
                return;
            }
        }

        showDialogAndBlock(connection, audience, uuid, plugin.getDialogFactory().buildLoginDialog(name));
    }

    /**
     * Registers the response future before displaying the dialog so a fast response
     * cannot arrive before the listener is ready to receive it.
     */
    private void showDialogAndBlock(
            PlayerConfigurationConnection connection,
            Audience audience,
            UUID uuid,
            Dialog dialog
    ) {
        CompletableFuture<Boolean> authFuture = plugin.createPendingAuth(uuid);
        boolean authenticated = false;

        try {
            audience.showDialog(dialog);
            authenticated = authFuture.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            plugin.getLogger().fine("Pre-join authentication did not complete for " + uuid + ": " + e.getMessage());
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

    private String resolveIp(PlayerConfigurationConnection connection) {
        try {
            InetSocketAddress address = connection.getClientAddress();
            if (address != null && address.getAddress() != null) {
                return address.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }

        try {
            java.net.SocketAddress raw = connection.getAddress();
            if (raw instanceof InetSocketAddress address && address.getAddress() != null) {
                return address.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }

        return null;
    }
}
