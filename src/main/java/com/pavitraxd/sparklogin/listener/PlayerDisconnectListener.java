package com.pavitraxd.sparklogin.listener;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Cancels any pending auth future when a player disconnects,
 * unblocking the config-listener thread that was waiting on it.
 */
public class PlayerDisconnectListener implements Listener {
    private final SparkLoginPlugin plugin;

    public PlayerDisconnectListener(SparkLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.cancelPendingAuth(uuid);
        plugin.deregisterActiveJoinConnection(uuid);
    }
}
