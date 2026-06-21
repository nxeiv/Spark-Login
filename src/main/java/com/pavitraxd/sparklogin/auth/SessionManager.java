package com.pavitraxd.sparklogin.auth;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import com.pavitraxd.sparklogin.database.model.Account;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages player authentication sessions allowing players who re-join quickly to bypass prompts.
 */
public class SessionManager {
    private final SparkLoginPlugin plugin;

    public SessionManager(SparkLoginPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Checks if session bypass is enabled, and if a player's session is active and valid.
     */
    public boolean isSessionValid(Account account) {
        if (!plugin.getConfig().getBoolean("session.enabled", true)) {
            return false;
        }

        if (account.getSessionToken() == null || account.getSessionToken().isEmpty()) {
            return false;
        }

        long lastSessionTime = account.getSessionLast();
        long durationHours = plugin.getConfig().getLong("session.duration-hours", 12);
        long durationMillis = durationHours * 60 * 60 * 1000L;

        long elapsed = System.currentTimeMillis() - lastSessionTime;
        return elapsed >= 0 && elapsed <= durationMillis;
    }

    /**
     * Creates or updates a session for a player.
     * Generates a new session token, sets the session time, and saves it asynchronously to the database.
     */
    public CompletableFuture<String> createSession(UUID playerUuid) {
        if (!plugin.getConfig().getBoolean("session.enabled", true)) {
            return CompletableFuture.completedFuture(null);
        }

        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        return plugin.getAccountDAO().updateSession(playerUuid, token, now)
                .thenApply(v -> token);
    }

    /**
     * Invalidates any active sessions for security (e.g., password change or sign out command).
     */
    public CompletableFuture<Void> invalidateSession(UUID playerUuid) {
        return plugin.getAccountDAO().updateSession(playerUuid, null, 0L);
    }
}
