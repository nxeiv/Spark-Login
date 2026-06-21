package com.pavitraxd.sparklogin.database;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import com.pavitraxd.sparklogin.cache.AccountCache;
import com.pavitraxd.sparklogin.database.model.Account;
import com.pavitraxd.sparklogin.migration.AuthMeHashType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles non-blocking database queries asynchronously using SQLite.
 */
public class AccountDAO {
    private final SparkLoginPlugin plugin;
    private final DatabaseManager dbManager;
    private final AccountCache cache;

    public AccountDAO(SparkLoginPlugin plugin, DatabaseManager dbManager) {
        this.plugin = plugin;
        this.dbManager = dbManager;
        this.cache = new AccountCache(plugin);
    }

    public AccountCache getCache() {
        return cache;
    }

    /**
     * Retrieves an account profile of a player based on their uuid.
     * Uses cache to reduce database queries on SMP servers.
     */
    public CompletableFuture<Account> getAccount(UUID uuid) {
        // Check cache first
        Account cached = cache.get(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM spark_accounts WHERE uuid = ? LIMIT 1;";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Account account = mapRow(rs);
                        cache.put(uuid, account); // Cache the result
                        return account;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error reading account " + uuid + ": " + e.getMessage());
            }
            return null;
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Saves a newly registered account in the database.
     * Uses INSERT OR REPLACE so that a re-registration after an admin password
     * reset (which flips requires_registration = 1) clears that flag cleanly.
     */
    public CompletableFuture<Void> saveAccount(Account account) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO spark_accounts " +
                    "(uuid, username, password_hash, registered_at, last_login, session_token, session_last, requires_registration, last_login_ip, registration_ip, hash_type) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, account.getUuid().toString());
                ps.setString(2, account.getUsername());
                ps.setString(3, account.getPasswordHash());
                ps.setLong(4, account.getRegisteredAt());
                ps.setLong(5, account.getLastLogin());
                ps.setString(6, account.getSessionToken());
                ps.setLong(7, account.getSessionLast());
                ps.setInt(8, account.isRequiresRegistration() ? 1 : 0);
                ps.setString(9, account.getLastLoginIp());
                ps.setString(10, account.getRegistrationIp());
                ps.setString(11, account.getHashType() != null ? account.getHashType().toDatabaseString() : AuthMeHashType.BCRYPT.toDatabaseString());
                ps.executeUpdate();
                cache.put(account.getUuid(), account); // Update cache
            } catch (SQLException e) {
                plugin.getLogger().severe("Error saving account " + account.getUuid() + ": " + e.getMessage());
            }
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Updates the last successful login timestamp and IP for a player.
     */
    public CompletableFuture<Void> updateLastLogin(UUID uuid, long timestamp, String ip) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE spark_accounts SET last_login = ?, last_login_ip = ? WHERE uuid = ?;";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, timestamp);
                ps.setString(2, ip);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
                cache.invalidate(uuid); // Invalidate cache on update
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating login timestamp for " + uuid + ": " + e.getMessage());
            }
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Updates session token details of a player profile.
     */
    public CompletableFuture<Void> updateSession(UUID uuid, String token, long sessionLast) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE spark_accounts SET session_token = ?, session_last = ? WHERE uuid = ?;";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, token);
                ps.setLong(2, sessionLast);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
                cache.invalidate(uuid); // Invalidate cache on update
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating session for " + uuid + ": " + e.getMessage());
            }
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Updates only the password hash for a player (used by /changepassword).
     */
    public CompletableFuture<Void> updatePasswordHash(UUID uuid, String hash) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE spark_accounts SET password_hash = ? WHERE uuid = ?;";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, hash);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
                cache.invalidate(uuid); // Invalidate cache on update
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating password hash for " + uuid + ": " + e.getMessage());
            }
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Updates both password hash and hash type for a player (used for auto-upgrade).
     */
    public CompletableFuture<Void> updatePasswordHashAndType(UUID uuid, String hash, AuthMeHashType hashType) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE spark_accounts SET password_hash = ?, hash_type = ? WHERE uuid = ?;";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, hash);
                ps.setString(2, hashType.toDatabaseString());
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
                cache.invalidate(uuid); // Invalidate cache on update
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating password hash and type for " + uuid + ": " + e.getMessage());
            }
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Resets a player's password and forces a complete re-registration on next join.
     * Clears the password hash, session token and marks requires_registration = 1.
     */
    public CompletableFuture<Void> resetPassword(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE spark_accounts SET password_hash = '', session_token = NULL, session_last = 0, requires_registration = 1 WHERE uuid = ?;";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
                cache.invalidate(uuid); // Invalidate cache on update
            } catch (SQLException e) {
                plugin.getLogger().severe("Error resetting password for " + uuid + ": " + e.getMessage());
            }
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Deletes a player account from records (Admin Command feature support).
     * Also removes any IP-index entries that referenced this username.
     */
    public CompletableFuture<Boolean> deleteAccount(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            // Best-effort removal of IP index entries using the stored username.
            String username = null;
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT username FROM spark_accounts WHERE uuid = ?;")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) username = rs.getString("username");
                }
            } catch (SQLException ignored) {}

            if (username != null) {
                removeAllIpIndexForUsername(username);
            }

            String sql = "DELETE FROM spark_accounts WHERE uuid = ?;";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                boolean deleted = ps.executeUpdate() > 0;
                if (deleted) {
                    cache.invalidate(uuid); // Invalidate cache on delete
                }
                return deleted;
            } catch (SQLException e) {
                plugin.getLogger().severe("Error removing account " + uuid + ": " + e.getMessage());
            }
            return false;
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    // =========================
    //  IP Index management
    // =========================

    /**
     * Records that an account was registered from a given IP.
     */
    public CompletableFuture<Void> addIpIndex(String ip, String username) {
        if (ip == null || username == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO spark_ip_index (ip, username, registered_at) VALUES (?, ?, ?);";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ip);
                ps.setString(2, username);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error adding IP index for " + username + ": " + e.getMessage());
            }
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Removes all IP index entries for a given username.
     */
    public void removeAllIpIndexForUsername(String username) {
        if (username == null) return;
        String sql = "DELETE FROM spark_ip_index WHERE username = ?;";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Error removing IP index for " + username + ": " + e.getMessage());
        }
    }

    /**
     * Returns the number of accounts registered from the given IP.
     */
    public CompletableFuture<Integer> countAccountsForIp(String ip) {
        if (ip == null) return CompletableFuture.completedFuture(0);
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM spark_ip_index WHERE ip = ?;";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error counting accounts for ip " + ip + ": " + e.getMessage());
            }
            return 0;
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Returns the list of usernames registered from the given IP.
     */
    public CompletableFuture<List<String>> getAccountsForIp(String ip) {
        if (ip == null) return CompletableFuture.completedFuture(new ArrayList<>());
        return CompletableFuture.supplyAsync(() -> {
            List<String> names = new ArrayList<>();
            String sql = "SELECT username FROM spark_ip_index WHERE ip = ? ORDER BY registered_at ASC;";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString("username"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error listing accounts for ip " + ip + ": " + e.getMessage());
            }
            return names;
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    // =========================
    //  Helpers
    // =========================

    /**
     * Resolves a name or UUID string to a UUID, consulting the server's
     * offline player cache when the input is a username. Returns null when
     * the target cannot be identified.
     */
    public UUID resolveUuid(CommandSender sender, String target) {
        UUID uuid = null;
        try {
            uuid = UUID.fromString(target);
        } catch (IllegalArgumentException ignored) {}

        if (uuid == null) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(target);
            if (player != null && (player.hasPlayedBefore() || player.getUniqueId() != null)) {
                uuid = player.getUniqueId();
            } else {
                if (sender != null) {
                    sender.sendMessage(Component.text("§cCould not identify player by the name '" + target + "'. Please specify UUID directly."));
                }
                return null;
            }
        }
        return uuid;
    }

    /**
     * Maps a ResultSet row to an Account object, reading both legacy columns
     * and the new extended columns.
     */
    private Account mapRow(ResultSet rs) throws SQLException {
        String hashTypeString = "BCRYPT";
        try {
            hashTypeString = rs.getString("hash_type");
            if (hashTypeString == null) hashTypeString = "BCRYPT";
        } catch (SQLException e) {
            hashTypeString = "BCRYPT";
        }

        return new Account(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getLong("registered_at"),
                rs.getLong("last_login"),
                rs.getString("session_token"),
                rs.getLong("session_last"),
                rs.getInt("requires_registration") == 1,
                rs.getString("last_login_ip"),
                rs.getString("registration_ip"),
                AuthMeHashType.fromString(hashTypeString)
        );
    }

}
