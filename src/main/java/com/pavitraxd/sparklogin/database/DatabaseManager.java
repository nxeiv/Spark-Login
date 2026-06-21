package com.pavitraxd.sparklogin.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.pavitraxd.sparklogin.SparkLoginPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages database initialization, HikariCP connection pooling, and SQLite optimizations.
 */
public class DatabaseManager {
    private final SparkLoginPlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(SparkLoginPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Initializes the HikariCP DataSource using configured parameters and runs setup migrations.
     */
    public void initialize() {
        ConfigurationSection dbConfig = plugin.getConfig().getConfigurationSection("database");
        if (dbConfig == null) {
            plugin.getLogger().severe("Database configuration section is missing!");
            return;
        }

        String dbFileName = dbConfig.getString("file", "database.db");
        File dbFile = new File(plugin.getDataFolder(), dbFileName);
        
        // Ensure parent directories exist
        if (!dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("SparkLogin-Pool");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        // Load Hikari configurations from config.yml
        ConfigurationSection poolConfig = dbConfig.getConfigurationSection("pool");
        if (poolConfig != null) {
            config.setMaxLifetime(poolConfig.getLong("max-lifetime-ms", 1800000));
            config.setIdleTimeout(poolConfig.getLong("idle-timeout-ms", 600000));
            config.setConnectionTimeout(poolConfig.getLong("connection-timeout-ms", 10000));
            config.setMaximumPoolSize(poolConfig.getInt("maximum-pool-size", 10));
            config.setMinimumIdle(poolConfig.getInt("minimum-idle", 5));
        } else {
            config.setMaxLifetime(1800000);
            config.setIdleTimeout(600000);
            config.setConnectionTimeout(10000);
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(5);
        }

        // SQLite connection tuning for SMP performance
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");

        this.dataSource = new HikariDataSource(config);

        // Apply fast WAL (Write-Ahead Logging) and normal synchronous modes to SQLite for concurrency
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA cache_size = -64000;"); // 64MB cache
            stmt.execute("PRAGMA temp_store = MEMORY;");
            stmt.execute("PRAGMA mmap_size = 30000000000;"); // 30GB mmap for large databases
            stmt.execute("PRAGMA page_size = 4096;");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to apply SQLite optimization pragmas: " + e.getMessage());
        }

        // Initialize schema tables
        createTables();
    }

    /**
     * Creates the core database schema under SQLite asynchronously or on startup.
     * New columns are added via idempotent ALTER TABLE migrations so existing
     * databases keep working without data loss.
     */
    private void createTables() {
        String sql = "CREATE TABLE IF NOT EXISTS spark_accounts (" +
                "uuid TEXT PRIMARY KEY, " +
                "username TEXT NOT NULL, " +
                "password_hash TEXT NOT NULL, " +
                "registered_at INTEGER NOT NULL, " +
                "last_login INTEGER NOT NULL, " +
                "session_token TEXT, " +
                "session_last INTEGER, " +
                "requires_registration INTEGER DEFAULT 0, " +
                "last_login_ip TEXT, " +
                "registration_ip TEXT, " +
                "hash_type TEXT DEFAULT 'BCRYPT'" +
                ");";

        // Index used for fast "max accounts per IP" counting
        String ipIndexSql = "CREATE TABLE IF NOT EXISTS spark_ip_index (" +
                "ip TEXT NOT NULL, " +
                "username TEXT NOT NULL, " +
                "registered_at INTEGER NOT NULL, " +
                "PRIMARY KEY (ip, username)" +
                ");";
        String ipIndexIdxSql = "CREATE INDEX IF NOT EXISTS idx_spark_ip ON spark_ip_index(ip);";

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            stmt.execute(ipIndexSql);
            stmt.execute(ipIndexIdxSql);

            // Idempotent column migrations for databases created before these columns existed.
            // ALTER TABLE ... ADD COLUMN fails once the column already exists, which we ignore.
            addColumnIfMissing(stmt, "spark_accounts", "requires_registration", "INTEGER DEFAULT 0");
            addColumnIfMissing(stmt, "spark_accounts", "last_login_ip", "TEXT");
            addColumnIfMissing(stmt, "spark_accounts", "registration_ip", "TEXT");
            addColumnIfMissing(stmt, "spark_accounts", "hash_type", "TEXT DEFAULT 'BCRYPT'");
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not establish schema tables: " + e.getMessage());
        }
    }

    /**
     * Adds a column to a table if it does not already exist. SQLite has no
     * "ADD COLUMN IF NOT EXISTS", so the exception thrown when the column is
     * present is caught and ignored.
     */
    private void addColumnIfMissing(Statement stmt, String table, String column, String definition) {
        try {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition + ";");
            plugin.getLogger().info("Applied migration: added column " + column + " to " + table + ".");
        } catch (SQLException ignored) {
            // Column already exists - nothing to do.
        }
    }

    /**
     * Gets a pooled connection.
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("SQL Database connection has been closed or is uninitialized.");
        }
        return dataSource.getConnection();
    }

    /**
     * Terminate connection pool.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool safely shutdown.");
        }
    }
}
