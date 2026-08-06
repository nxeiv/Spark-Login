package com.pavitraxd.sparklogin.listener;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import com.pavitraxd.sparklogin.dialog.DialogFactory;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles dialog button clicks during the authentication phase.
 * <p>
 * On success we complete the pending auth future ({@code true}), which unblocks the
 * config-listener thread so it can call {@code completeReconfiguration()}.
 * On cancel, too many attempts, or disconnect we complete the future ({@code false}),
 * which causes the config-listener to disconnect the player.
 */
public class DialogInputListener implements Listener {
    private final SparkLoginPlugin plugin;

    public DialogInputListener(SparkLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerCustomClick(PlayerCustomClickEvent event) {
        Key clickId = event.getIdentifier();
        if (clickId == null || !clickId.namespace().equals("sparklogin")) return;

        // --- Pre-join path (1.21.11+) ---
        if (event.getCommonConnection() instanceof PlayerConfigurationConnection connection) {
            UUID uuid = connection.getProfile().getId();
            String playerName = connection.getProfile().getName();
            if (uuid == null || playerName == null) return;

            DialogResponseView responseView = event.getDialogResponseView();
            Audience audience = connection.getAudience();

            if (DialogFactory.LOGIN_CANCEL.equals(clickId) || DialogFactory.REGISTER_CANCEL.equals(clickId)) {
                audience.sendMessage(plugin.getMessages().getComponent("general.canceled", "<red>Authentication process canceled. Connection terminated.</red>"));
                plugin.deregisterActiveJoinConnection(uuid);
                plugin.completePendingAuth(uuid, false);
                return;
            }
            if (DialogFactory.RESET_CONTINUE.equals(clickId)) {
                audience.showDialog(plugin.getDialogFactory().buildRegisterDialog(playerName));
                return;
            }
            if (DialogFactory.LOGIN_SUBMIT.equals(clickId)) {
                handleLoginSubmit(connection, audience, responseView, uuid, playerName);
            } else if (DialogFactory.REGISTER_SUBMIT.equals(clickId)) {
                handleRegisterSubmit(connection, audience, responseView, uuid, playerName);
            }
            return;
        }

        // --- Post-join path (older versions / in-game dialog) ---
        if (!(event.getCommonConnection() instanceof PlayerGameConnection gameConnection)) return;
        Player player = gameConnection.getPlayer();
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();

        // Only process if this player is actually pending post-join auth
        if (!plugin.getPostJoinAuthManager().isPending(uuid)) return;

        DialogResponseView responseView = event.getDialogResponseView();

        if (DialogFactory.LOGIN_CANCEL.equals(clickId) || DialogFactory.REGISTER_CANCEL.equals(clickId)) {
            plugin.getPostJoinAuthManager().removePending(uuid);
            player.kick(plugin.getMessages().getComponent("general.canceled", "<red>Authentication process canceled. Connection terminated.</red>"));
            return;
        }
        if (DialogFactory.RESET_CONTINUE.equals(clickId)) {
            player.showDialog(plugin.getDialogFactory().buildRegisterDialog(playerName));
            return;
        }
        if (DialogFactory.LOGIN_SUBMIT.equals(clickId)) {
            handlePostJoinLoginSubmit(player, responseView, uuid, playerName);
        } else if (DialogFactory.REGISTER_SUBMIT.equals(clickId)) {
            handlePostJoinRegisterSubmit(player, responseView, uuid, playerName);
        }
    }

    private String resolvePlayerIp(Player player) {
        try {
            if (player.getAddress() != null && player.getAddress().getAddress() != null) {
                return player.getAddress().getAddress().getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // -------------------------------------------------------------------------
    // Post-join handlers
    // -------------------------------------------------------------------------

    private void handlePostJoinLoginSubmit(Player player, DialogResponseView responseView, UUID uuid, String playerName) {
        String password = responseView == null ? null : responseView.getText("password");
        if (password == null || password.isEmpty()) {
            player.sendMessage(Component.text("Password input cannot be empty."));
            player.showDialog(plugin.getDialogFactory().buildLoginDialog(playerName));
            return;
        }

        plugin.getAccountDAO().getAccount(uuid).thenAccept(account -> {
            if (!player.isOnline()) return;
            if (account == null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.showDialog(plugin.getDialogFactory().buildRegisterDialog(playerName)));
                return;
            }
            plugin.getAuthService().verifyPassword(password, account).thenAccept(matches -> {
                if (!player.isOnline()) return;
                if (matches) {
                    plugin.getAuthService().resetAttempts(uuid);
                    String ip = resolvePlayerIp(player);
                    plugin.getAccountDAO().updateLastLogin(uuid, System.currentTimeMillis(), ip);
                    plugin.getAuthService().autoUpgradeHash(account, password).thenAccept(v ->
                            plugin.getSessionManager().createSession(uuid).thenAccept(token -> {
                                if (!player.isOnline()) return;
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    plugin.getPostJoinAuthManager().unlock(player);
                                    player.sendMessage(plugin.getMessages().getComponent(
                                            "login.status.success", "<green>Successfully authenticated. Welcome to the server!</green>"));
                                });
                            }));
                } else {
                    int attempts = plugin.getAuthService().incrementAttempts(uuid);
                    int maxAttempts = plugin.getConfig().getInt("security.max-login-attempts", 3);
                    if (attempts >= maxAttempts) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            plugin.getPostJoinAuthManager().removePending(uuid);
                            player.kick(plugin.getMessages().getComponent(
                                    "login.status.too-many-attempts", "<red>Too many incorrect login attempts! Please reconnect.</red>"));
                            plugin.getAuthService().resetAttempts(uuid);
                        });
                    } else {
                        Map<String, String> ph = new HashMap<>();
                        ph.put("attempts", String.valueOf(attempts));
                        ph.put("max_attempts", String.valueOf(maxAttempts));
                        Component errMsg = plugin.getMessages().getComponent(
                                "login.status.incorrect-password", "<red>Incorrect password! ({attempts}/{max_attempts})</red>", ph);
                        long delay = plugin.getConfig().getLong("security.brute-force-delay-ms", 1500);
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            if (!player.isOnline() || !plugin.getPostJoinAuthManager().isPending(uuid)) return;
                            player.sendMessage(errMsg);
                            player.showDialog(plugin.getDialogFactory().buildLoginDialog(playerName));
                        }, delay / 50L);
                    }
                }
            });
        }).exceptionally(err -> {
            plugin.getLogger().severe("DB failure on post-join login for " + uuid + ": " + err.getMessage());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getPostJoinAuthManager().removePending(uuid);
                player.kick(Component.text("Data loading failed. Try connecting again."));
            });
            return null;
        });
    }

    private void handlePostJoinRegisterSubmit(Player player, DialogResponseView responseView, UUID uuid, String playerName) {
        String password = responseView == null ? null : responseView.getText("password");
        String confirm = responseView == null ? null : responseView.getText("confirm_password");

        if (password == null || password.isEmpty()) {
            player.sendMessage(Component.text("Password must not be blank."));
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    player.showDialog(plugin.getDialogFactory().buildRegisterDialog(playerName)));
            return;
        }
        if (!plugin.getAuthService().isPasswordLengthValid(password)) {
            Map<String, String> limits = new HashMap<>();
            limits.put("min", String.valueOf(plugin.getConfig().getInt("registration.min-password-length", 6)));
            limits.put("max", String.valueOf(plugin.getConfig().getInt("registration.max-password-length", 64)));
            player.sendMessage(plugin.getMessages().getComponent(
                    "register.status.password-too-short", "<red>Password too short! Min {min} characters.</red>", limits));
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    player.showDialog(plugin.getDialogFactory().buildRegisterDialog(playerName)));
            return;
        }
        boolean reqConfirm = plugin.getConfig().getBoolean("registration.require-confirmation-field", true);
        if (reqConfirm && (confirm == null || !confirm.equals(password))) {
            player.sendMessage(plugin.getMessages().getComponent(
                    "register.status.passwords-dont-match", "<red>Passwords do not match!</red>"));
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    player.showDialog(plugin.getDialogFactory().buildRegisterDialog(playerName)));
            return;
        }

        String ip = resolvePlayerIp(player);
        plugin.getAuthService().registerNewAccount(uuid, playerName, password, ip).thenAccept(newAccount ->
                plugin.getSessionManager().createSession(uuid).thenAccept(token -> {
                    if (!player.isOnline()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getPostJoinAuthManager().unlock(player);
                        player.sendMessage(plugin.getMessages().getComponent(
                                "register.status.success", "<green>Registration complete! Enjoy your play!</green>"));
                    });
                })).exceptionally(err -> {
            plugin.getLogger().severe("DB registration failure for " + uuid + ": " + err.getMessage());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getPostJoinAuthManager().removePending(uuid);
                player.kick(Component.text("Registration failed. Try again."));
            });
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Pre-join helpers
    // -------------------------------------------------------------------------

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

    /**
     * Resolves submitted password against BCrypt records.
     */
    private void handleLoginSubmit(PlayerConfigurationConnection connection, Audience audience, DialogResponseView responseView, UUID uuid, String playerName) {
        String password = responseView == null ? null : responseView.getText("password");

        if (password == null || password.isEmpty()) {
            audience.sendMessage(Component.text("Password input cannot be empty."));
            Dialog loginDialog = plugin.getDialogFactory().buildLoginDialog(playerName);
            audience.showDialog(loginDialog);
            return;
        }

        plugin.getAccountDAO().getAccount(uuid).thenAccept(account -> {
            if (!connection.isConnected()) return;

            if (account == null) {
                // Account missing? Re-prompt registering
                Dialog reg = plugin.getDialogFactory().buildRegisterDialog(playerName);
                audience.showDialog(reg);
                return;
            }

            plugin.getAuthService().verifyPassword(password, account).thenAccept(matches -> {
                if (!connection.isConnected()) return;

                if (matches) {
                    // Password correct — create session and signal success
                    plugin.getAuthService().resetAttempts(uuid);
                    String ip = resolveIp(connection);
                    plugin.getAccountDAO().updateLastLogin(uuid, System.currentTimeMillis(), ip);
                    
                    // Auto-upgrade AuthMe hashes to BCrypt after successful login
                    plugin.getAuthService().autoUpgradeHash(account, password).thenAccept(v -> {
                        plugin.getSessionManager().createSession(uuid).thenAccept(token -> {
                            if (!connection.isConnected()) return;
                            Component welcome = plugin.getMessages().getComponent("login.status.success", "<green>Successfully authenticated. Welcome to the server!</green>");
                            audience.sendMessage(welcome);
                            plugin.deregisterActiveJoinConnection(uuid);
                            plugin.completePendingAuth(uuid, true);  // unblocks config listener
                            
                            // Send chat message to player after dialog closes
                            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                                org.bukkit.entity.Player player = plugin.getServer().getPlayer(uuid);
                                if (player != null && player.isOnline()) {
                                    player.sendMessage(Component.text("§aLogged In. Enjoy!"));
                                }
                            }, 1L);
                        });
                    });
                } else {
                    // Wrong password — increment attempts
                    int attempts = plugin.getAuthService().incrementAttempts(uuid);
                    int maxAttempts = plugin.getConfig().getInt("security.max-login-attempts", 3);

                    if (attempts >= maxAttempts) {
                        Component kick = plugin.getMessages().getComponent("login.status.too-many-attempts", "<red>Too many incorrect login attempts! Please reconnect.</red>");
                        connection.disconnect(kick);
                        plugin.deregisterActiveJoinConnection(uuid);
                        plugin.completePendingAuth(uuid, false);  // unblocks config listener (will disconnect)
                        plugin.getAuthService().resetAttempts(uuid);
                    } else {
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("attempts", String.valueOf(attempts));
                        placeholders.put("max_attempts", String.valueOf(maxAttempts));
                        Component errMsg = plugin.getMessages().getComponent("login.status.incorrect-password", "<red>Incorrect password! Please try again ({attempts}/{max_attempts}).</red>", placeholders);
                        audience.sendMessage(errMsg);

                        // Rate-limit re-prompt to slow brute-force
                        long delay = plugin.getConfig().getLong("security.brute-force-delay-ms", 1500);
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            if (plugin.isPlayerConnecting(uuid) && connection.isConnected()) {
                                Dialog loginDialog = plugin.getDialogFactory().buildLoginDialog(playerName);
                                audience.showDialog(loginDialog);
                            }
                        }, delay / 50L);
                    }
                }
            });
        }).exceptionally(err -> {
            plugin.getLogger().severe("Database failure checking login for " + uuid + ": " + err.getMessage());
            connection.disconnect(Component.text("Data loading failed. Try connecting again."));
            plugin.deregisterActiveJoinConnection(uuid);
            plugin.completePendingAuth(uuid, false);
            return null;
        });
    }

    /**
     * Resolves and registers a new account.
     */
    private void handleRegisterSubmit(PlayerConfigurationConnection connection, Audience audience, DialogResponseView responseView, UUID uuid, String playerName) {
        String password = responseView == null ? null : responseView.getText("password");
        String confirm = responseView == null ? null : responseView.getText("confirm_password");

        if (password == null || password.isEmpty()) {
            audience.sendMessage(Component.text("Password must not be blank."));
            audience.showDialog(plugin.getDialogFactory().buildRegisterDialog(playerName));
            return;
        }

        // Validate length
        if (!plugin.getAuthService().isPasswordLengthValid(password)) {
            Map<String, String> limits = new HashMap<>();
            limits.put("min", String.valueOf(plugin.getConfig().getInt("registration.min-password-length", 6)));
            limits.put("max", String.valueOf(plugin.getConfig().getInt("registration.max-password-length", 64)));
            Component lenErr = plugin.getMessages().getComponent("register.status.password-too-short", "<red>Your password is too short! Minimum length is {min} characters.</red>", limits);
            audience.sendMessage(lenErr);
            audience.showDialog(plugin.getDialogFactory().buildRegisterDialog(playerName));
            return;
        }

        // Validate confirmation if enabled
        boolean reqConfirm = plugin.getConfig().getBoolean("registration.require-confirmation-field", true);
        if (reqConfirm && (confirm == null || !confirm.equals(password))) {
            Component matchErr = plugin.getMessages().getComponent("register.status.passwords-dont-match", "<red>Passwords do not match! Please verify your spelling.</red>");
            audience.sendMessage(matchErr);
            audience.showDialog(plugin.getDialogFactory().buildRegisterDialog(playerName));
            return;
        }

        // All inputs valid — register asynchronously
        String ip = resolveIp(connection);
        plugin.getAuthService().registerNewAccount(uuid, playerName, password, ip).thenAccept(newAccount -> {
            if (!connection.isConnected()) return;

            plugin.getSessionManager().createSession(uuid).thenAccept(token -> {
                if (!connection.isConnected()) return;
                Component success = plugin.getMessages().getComponent("register.status.success", "<green>Registration complete! Your profile is safe. Enjoy your play!</green>");
                audience.sendMessage(success);
                plugin.deregisterActiveJoinConnection(uuid);
                plugin.completePendingAuth(uuid, true);  // unblocks config listener
                
                // Send chat message to player after dialog closes
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    org.bukkit.entity.Player player = plugin.getServer().getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        player.sendMessage(Component.text("§aSuccessfully registered. Enjoy!"));
                    }
                }, 1L);
            });
        }).exceptionally(err -> {
            plugin.getLogger().severe("Database registration failure for " + uuid + ": " + err.getMessage());
            connection.disconnect(Component.text("Registration failed due to database load issues. Try again."));
            plugin.deregisterActiveJoinConnection(uuid);
            plugin.completePendingAuth(uuid, false);
            return null;
        });
    }
}
