package com.pavitraxd.sparklogin.listener;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import com.pavitraxd.sparklogin.database.model.Account;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;

import java.util.UUID;

/**
 * Handles post-join authentication for clients that do NOT support the pre-join
 * Dialog API (i.e. versions below 1.21.11 / servers without AsyncPlayerConnectionConfigureEvent).
 *
 * On join: locks the player (no movement, chat, commands, damage, interaction),
 * then shows the login/register dialog via Player#showDialog().
 *
 * All restriction events are cancelled while the player is in the pending-auth set.
 */
public class PostJoinAuthListener implements Listener {

    private final SparkLoginPlugin plugin;

    public PostJoinAuthListener(SparkLoginPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Join handling
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Pre-join dialog path already handled this player — skip
        if (plugin.isPreJoinSupported() && plugin.wasHandledByPreJoin(player.getUniqueId())) {
            plugin.clearPreJoinHandled(player.getUniqueId());
            return;
        }

        // Only run post-join flow when pre-join is NOT supported
        if (plugin.isPreJoinSupported()) return;

        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress() : null;

        plugin.getAuthService().resetAttempts(uuid);

        plugin.getAccountDAO().getAccount(uuid).thenAccept(account -> {
            if (!player.isOnline()) return;

            // Session restore check
            if (account != null && !account.isRequiresRegistration()
                    && plugin.getSessionManager().isSessionValid(account)) {
                boolean ipChanged = false;
                if (plugin.getConfig().getBoolean("security.require-login-on-ip-change", true)) {
                    String lastIp = account.getLastLoginIp();
                    if (lastIp != null && ip != null && !ip.equals(lastIp)) {
                        ipChanged = true;
                    }
                }
                if (!ipChanged) {
                    plugin.getAccountDAO().updateLastLogin(uuid, System.currentTimeMillis(), ip);
                    plugin.getSessionManager().createSession(uuid);
                    player.sendMessage(plugin.getMessages().getComponent(
                            "session.restored", "<green>Your login session was restored automatically. Welcome back!</green>"));
                    return;
                }
            }

            // Lock the player and show the appropriate dialog
            plugin.getPostJoinAuthManager().addPending(uuid);
            lockPlayer(player);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    plugin.getPostJoinAuthManager().removePending(uuid);
                    return;
                }
                showAppropriateDialog(player, account, name);
            });

        }).exceptionally(err -> {
            plugin.getLogger().severe("DB error on post-join auth for " + name + ": " + err.getMessage());
            player.kick(Component.text("A database error occurred. Please reconnect."));
            return null;
        });
    }

    private void showAppropriateDialog(Player player, Account account, String name) {
        if (account == null) {
            // IP limit check
            String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                    ? player.getAddress().getAddress().getHostAddress() : null;
            int maxAccounts = plugin.getConfig().getInt("registration.max-accounts-per-ip", 3);
            if (maxAccounts > 0 && ip != null) {
                final String finalIp = ip;
                plugin.getAccountDAO().countAccountsForIp(finalIp).thenAccept(count -> {
                    if (!player.isOnline()) return;
                    if (count >= maxAccounts) {
                        plugin.getPostJoinAuthManager().removePending(player.getUniqueId());
                        Component msg = plugin.getMessages().getComponent("register.error.ip-limit",
                                "<red>Too many accounts are registered from your IP address.</red>");
                        if (plugin.getConfig().getBoolean("registration.kick-on-limit", true)) {
                            player.kick(msg);
                        } else {
                            player.sendMessage(msg);
                        }
                        return;
                    }
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            player.showDialog(plugin.getDialogFactory().buildRegisterDialog(name)));
                });
            } else {
                player.showDialog(plugin.getDialogFactory().buildRegisterDialog(name));
            }
        } else if (account.isRequiresRegistration()) {
            player.showDialog(plugin.getDialogFactory().buildAccountResetDialog(name));
        } else {
            player.showDialog(plugin.getDialogFactory().buildLoginDialog(name));
        }
    }

    // -------------------------------------------------------------------------
    // Lock enforcement — cancel everything while pending auth
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        if (!isPending(event.getPlayer())) return;
        // Allow head rotation, block positional movement
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!isPending(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isPending(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!isPending(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isPending(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!isPending(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isPending(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (!isPending(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isPending(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isPending(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        if (!isPending(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getPostJoinAuthManager().removePending(event.getPlayer().getUniqueId());
    }

    // -------------------------------------------------------------------------

    private boolean isPending(Player player) {
        return plugin.getPostJoinAuthManager().isPending(player.getUniqueId());
    }

    /** Freezes the player in place using velocity zero — purely cosmetic lock, events do the real blocking. */
    private void lockPlayer(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        });
    }
}
