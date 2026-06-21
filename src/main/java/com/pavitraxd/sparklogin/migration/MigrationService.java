package com.pavitraxd.sparklogin.migration;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import com.pavitraxd.sparklogin.database.AccountDAO;
import com.pavitraxd.sparklogin.database.DatabaseManager;
import com.pavitraxd.sparklogin.database.model.Account;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for migrating AuthMe accounts to SparkLogin.
 * Handles backup, batch imports, and logging.
 */
public class MigrationService {
    private final SparkLoginPlugin plugin;
    private final DatabaseManager dbManager;
    private final AccountDAO accountDAO;

    public MigrationService(SparkLoginPlugin plugin, DatabaseManager dbManager, AccountDAO accountDAO) {
        this.plugin = plugin;
        this.dbManager = dbManager;
        this.accountDAO = accountDAO;
    }

    /**
     * Migration result statistics.
     */
    public static class MigrationResult {
        public int totalAuthMeAccounts;
        public int imported;
        public int skippedExisting;
        public int unsupportedHashes;
        public int failedImports;
        public long durationMs;
    }

    /**
     * Performs the AuthMe migration asynchronously.
     *
     * @param progressCallback Callback for progress updates (percentage, message)
     * @return CompletableFuture with migration result
     */
    public CompletableFuture<MigrationResult> migrateAuthMe(ProgressCallback progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            MigrationResult result = new MigrationResult();

            try {
                progressCallback.onProgress(0, "Creating backup...");

                // Create backup
                if (!createBackup()) {
                    progressCallback.onProgress(0, "Backup failed - aborting migration");
                    return result;
                }

                progressCallback.onProgress(5, "Reading AuthMe database...");

                // Read AuthMe database
                AuthMeDatabaseReader reader = new AuthMeDatabaseReader(plugin);
                if (!reader.initialize()) {
                    progressCallback.onProgress(5, "Failed to connect to AuthMe database");
                    return result;
                }

                List<AuthMeDatabaseReader.AuthMeAccount> authMeAccounts = reader.readAllAccounts();
                reader.close();

                result.totalAuthMeAccounts = authMeAccounts.size();
                progressCallback.onProgress(10, "Found " + authMeAccounts.size() + " AuthMe accounts");

                // Create log file
                File logFile = createLogFile();
                FileWriter logWriter = new FileWriter(logFile);

                // Process accounts in batches
                int batchSize = 100;
                int processed = 0;
                AtomicInteger importedCount = new AtomicInteger(0);
                AtomicInteger skippedCount = new AtomicInteger(0);
                AtomicInteger unsupportedCount = new AtomicInteger(0);
                AtomicInteger failedCount = new AtomicInteger(0);

                for (int i = 0; i < authMeAccounts.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, authMeAccounts.size());
                    List<AuthMeDatabaseReader.AuthMeAccount> batch = authMeAccounts.subList(i, end);

                    // Process batch on main thread for database operations
                    CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                        for (AuthMeDatabaseReader.AuthMeAccount authMeAccount : batch) {
                            try {
                                processAccount(authMeAccount, importedCount, skippedCount, unsupportedCount, failedCount, logWriter);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Error processing account " + authMeAccount.username + ": " + e.getMessage());
                            }
                        }
                    }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));

                    batchFuture.join();

                    processed = end;
                    double percentage = 10 + (processed * 80.0 / authMeAccounts.size());
                    progressCallback.onProgress((int) percentage, "Processing accounts: " + processed + "/" + authMeAccounts.size());
                }

                logWriter.close();

                result.imported = importedCount.get();
                result.skippedExisting = skippedCount.get();
                result.unsupportedHashes = unsupportedCount.get();
                result.failedImports = failedCount.get();
                result.durationMs = System.currentTimeMillis() - startTime;

                progressCallback.onProgress(100, "Migration completed");

            } catch (Exception e) {
                plugin.getLogger().severe("Migration failed: " + e.getMessage());
                e.printStackTrace();
            }

            return result;
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Processes a single AuthMe account for migration.
     */
    private void processAccount(AuthMeDatabaseReader.AuthMeAccount authMeAccount,
                                AtomicInteger importedCount,
                                AtomicInteger skippedCount,
                                AtomicInteger unsupportedCount,
                                AtomicInteger failedCount,
                                FileWriter logWriter) throws IOException {
        String username = authMeAccount.realname != null ? authMeAccount.realname : authMeAccount.username;
        if (username == null || username.isEmpty()) {
            failedCount.incrementAndGet();
            logWriter.write(username + " -> FAILED_NO_USERNAME\n");
            return;
        }

        // Resolve UUID
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(username);
        if (offlinePlayer == null || offlinePlayer.getUniqueId() == null) {
            failedCount.incrementAndGet();
            logWriter.write(username + " -> FAILED_NO_UUID\n");
            return;
        }
        UUID uuid = offlinePlayer.getUniqueId();

        // Check if account already exists
        Account existingAccount = accountDAO.getAccount(uuid).join();
        if (existingAccount != null) {
            skippedCount.incrementAndGet();
            logWriter.write(username + " -> SKIPPED_ALREADY_EXISTS\n");
            return;
        }

        // Detect hash type
        String passwordHash = authMeAccount.password;
        if (passwordHash == null || passwordHash.isEmpty()) {
            failedCount.incrementAndGet();
            logWriter.write(username + " -> FAILED_NO_PASSWORD\n");
            return;
        }

        AuthMeHashType hashType = AuthMeHashVerifier.detectHashType(passwordHash);
        if (hashType == null) {
            unsupportedCount.incrementAndGet();
            logWriter.write(username + " -> UNSUPPORTED_HASH\n");
            return;
        }

        // Convert AuthMe hash type to SparkLogin hash type
        AuthMeHashType sparkHashType;
        if (hashType == AuthMeHashType.AUTHME_SHA256) {
            sparkHashType = AuthMeHashType.AUTHME_SHA256;
        } else if (hashType == AuthMeHashType.AUTHME_BCRYPT) {
            sparkHashType = AuthMeHashType.AUTHME_BCRYPT;
        } else {
            sparkHashType = AuthMeHashType.BCRYPT;
        }

        // Create account
        long registeredAt = authMeAccount.regDate > 0 ? authMeAccount.regDate : System.currentTimeMillis();
        long lastLogin = authMeAccount.lastLogin > 0 ? authMeAccount.lastLogin : System.currentTimeMillis();
        String ip = authMeAccount.ip;

        Account newAccount = new Account(
                uuid,
                username,
                passwordHash,
                registeredAt,
                lastLogin,
                null,
                0L,
                false,
                ip,
                ip,
                sparkHashType
        );

        // Save account
        accountDAO.saveAccount(newAccount).join();

        importedCount.incrementAndGet();
        logWriter.write(username + " -> IMPORTED\n");
    }

    /**
     * Creates a backup of the SparkLogin database.
     */
    private boolean createBackup() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "database.db");
            if (!dbFile.exists()) {
                plugin.getLogger().warning("No database file found to backup");
                return true;
            }

            File backupsDir = new File(plugin.getDataFolder(), "backups");
            if (!backupsDir.exists()) {
                backupsDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File backupFile = new File(backupsDir, "pre-authme-migration-" + timestamp + ".db");

            Files.copy(dbFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Backup created: " + backupFile.getAbsolutePath());
            return true;

        } catch (IOException e) {
            plugin.getLogger().severe("Failed to create backup: " + e.getMessage());
            return false;
        }
    }

    /**
     * Creates the migration log file.
     */
    private File createLogFile() throws IOException {
        File logsDir = new File(plugin.getDataFolder(), "logs");
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }

        File logFile = new File(logsDir, "authme-migration.log");
        if (logFile.exists()) {
            logFile.delete();
        }
        logFile.createNewFile();

        return logFile;
    }

    /**
     * Callback interface for progress updates.
     */
    public interface ProgressCallback {
        void onProgress(int percentage, String message);
    }
}
