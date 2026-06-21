package com.pavitraxd.sparklogin.auth;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import com.pavitraxd.sparklogin.database.model.Account;
import com.pavitraxd.sparklogin.migration.AuthMeHashType;
import com.pavitraxd.sparklogin.migration.AuthMeHashVerifier;
import net.kyori.adventure.text.Component;
import org.mindrot.jbcrypt.BCrypt;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes authentication security logic such as password verification, BCrypt hashing, and login rate-limiting.
 */
public class AuthService {
    private final SparkLoginPlugin plugin;
    private final Map<UUID, Integer> loginAttempts = new ConcurrentHashMap<>();

    public AuthService(SparkLoginPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Hashes a raw password string using BCrypt with configured cost factor.
     */
    public String hashPassword(String password) {
        int cost = plugin.getConfig().getInt("security.bcrypt-cost-factor", 12);
        // Ensure cost is between 4 and 31 (BCrypt limits)
        if (cost < 4) cost = 4;
        if (cost > 31) cost = 31;
        return BCrypt.hashpw(password, BCrypt.gensalt(cost));
    }

    /**
     * Verifies a raw password against account password hash.
     * Supports BCRYPT, AUTHME_SHA256, and AUTHME_BCRYPT hash types.
     */
    public CompletableFuture<Boolean> verifyPassword(String rawPassword, Account account) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                AuthMeHashType hashType = account.getHashType();
                if (hashType == null) {
                    hashType = AuthMeHashType.BCRYPT;
                }

                switch (hashType) {
                    case BCRYPT:
                    case AUTHME_BCRYPT:
                        return BCrypt.checkpw(rawPassword, account.getPasswordHash());
                    case AUTHME_SHA256:
                        return AuthMeHashVerifier.verifySha256(rawPassword, account.getPasswordHash());
                    default:
                        plugin.getLogger().warning("Unknown hash type for " + account.getUuid() + ": " + hashType);
                        return false;
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error verifying password hash for " + account.getUuid() + ": " + e.getMessage());
                return false;
            }
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Registers a profile into records with BCrypt encryption.
     * Also records the registration IP into the IP index (for max-accounts-per-ip).
     */
    public CompletableFuture<Account> registerNewAccount(UUID uuid, String username, String rawPassword, String registrationIp) {
        long now = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> hashPassword(rawPassword))
                .thenCompose(hash -> {
                    Account newAcc = new Account(uuid, username, hash, now, now, null, 0L, false, registrationIp, registrationIp);
                    return plugin.getAccountDAO().saveAccount(newAcc)
                            .thenCompose(v -> plugin.getAccountDAO().addIpIndex(registrationIp, username))
                            .thenApply(v -> newAcc);
                });
    }

    /**
     * Legacy entry point kept for compatibility — registers without IP tracking.
     */
    public CompletableFuture<Account> registerNewAccount(UUID uuid, String username, String rawPassword) {
        return registerNewAccount(uuid, username, rawPassword, null);
    }

    /**
     * Changes a player's password.
     * Validates the old password against the stored BCrypt hash, ensures the new
     * password passes the configured length rules and differs from the old one,
     * then persists the new hash and invalidates all active sessions.
     *
     * @return a future resolving to {@code true} on success, {@code false} on any
     *         validation failure (incorrect old password, weak new password, etc.)
     */
    public CompletableFuture<Boolean> changePassword(Account account, String oldRaw, String newRaw) {
        if (oldRaw == null || newRaw == null) return CompletableFuture.completedFuture(false);
        if (newRaw.equals(oldRaw)) return CompletableFuture.completedFuture(false);
        if (!isPasswordLengthValid(newRaw)) return CompletableFuture.completedFuture(false);

        return verifyPassword(oldRaw, account).thenCompose(oldMatches -> {
            if (!oldMatches) return CompletableFuture.completedFuture(false);
            return CompletableFuture.supplyAsync(() -> hashPassword(newRaw))
                    .thenCompose(newHash -> plugin.getAccountDAO().updatePasswordHash(account.getUuid(), newHash)
                            .thenCompose(v -> plugin.getSessionManager().invalidateSession(account.getUuid()))
                            .thenApply(v -> true));
        });
    }

    /**
     * Gets failed attempts of uuid.
     */
    public int getLoginAttempts(UUID uuid) {
        return loginAttempts.getOrDefault(uuid, 0);
    }

    /**
     * Increments login attempts.
     */
    public int incrementAttempts(UUID uuid) {
        int current = getLoginAttempts(uuid) + 1;
        loginAttempts.put(uuid, current);
        return current;
    }

    /**
     * Resets login attempt history for a player uuid on successful login or reconnect.
     */
    public void resetAttempts(UUID uuid) {
        loginAttempts.remove(uuid);
    }

    /**
     * Asserts whether password length meets standards.
     */
    public boolean isPasswordLengthValid(String password) {
        int min = plugin.getConfig().getInt("registration.min-password-length", 6);
        int max = plugin.getConfig().getInt("registration.max-password-length", 64);
        int len = password.length();
        return len >= min && len <= max;
    }

    /**
     * Auto-upgrades an account's password hash to BCrypt after successful login.
     * This is called when a user with an AuthMe hash type successfully authenticates.
     * The upgrade happens transparently - the user doesn't notice.
     *
     * @param account The account to upgrade
     * @param rawPassword The raw password that was just verified
     * @return CompletableFuture that completes when upgrade is done
     */
    public CompletableFuture<Void> autoUpgradeHash(Account account, String rawPassword) {
        if (account.getHashType() == AuthMeHashType.BCRYPT) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            String newHash = hashPassword(rawPassword);
            account.setPasswordHash(newHash);
            account.setHashType(AuthMeHashType.BCRYPT);
            return newHash;
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable))
        .thenCompose(newHash -> plugin.getAccountDAO().updatePasswordHashAndType(account.getUuid(), newHash, AuthMeHashType.BCRYPT))
        .thenRun(() -> plugin.getLogger().info("Auto-upgraded hash for " + account.getUsername() + " to BCrypt"));
    }
}
