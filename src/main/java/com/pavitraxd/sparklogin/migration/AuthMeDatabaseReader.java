package com.pavitraxd.sparklogin.migration;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads AuthMe database configuration and provides connection to AuthMe database.
 * Supports SQLite, MySQL, and MariaDB backends.
 */
public class AuthMeDatabaseReader {
    private final SparkLoginPlugin plugin;
    private String backend;
    private String tableName;
    private Connection connection;

    public AuthMeDatabaseReader(SparkLoginPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reads AuthMe configuration and establishes database connection.
     *
     * @return true if successful, false otherwise
     */
    public boolean initialize() {
        File authMeConfigFile = new File(plugin.getServer().getPluginsFolder(), "AuthMe/config.yml");
        if (!authMeConfigFile.exists()) {
            plugin.getLogger().severe("AuthMe configuration file not found at: " + authMeConfigFile.getAbsolutePath());
            return false;
        }

        FileConfiguration authMeConfig = YamlConfiguration.loadConfiguration(authMeConfigFile);

        backend = authMeConfig.getString("DataSource.backend", "SQLITE").toUpperCase();
        tableName = authMeConfig.getString("DataSource.mySQLTableName", "authme");

        plugin.getLogger().info("Detected AuthMe backend: " + backend);
        plugin.getLogger().info("AuthMe table name: " + tableName);

        try {
            if (backend.equals("SQLITE")) {
                return initializeSQLite(authMeConfig);
            } else if (backend.equals("MYSQL") || backend.equals("MARIADB")) {
                return initializeMySQL(authMeConfig);
            } else {
                plugin.getLogger().severe("Unsupported AuthMe backend: " + backend);
                return false;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to connect to AuthMe database: " + e.getMessage());
            return false;
        }
    }

    /**
     * Initializes SQLite connection for AuthMe.
     */
    private boolean initializeSQLite(FileConfiguration authMeConfig) throws SQLException {
        String dbFile = authMeConfig.getString("DataSource.sqliteSQLITEFileName", "authme.db");
        File authMeFolder = new File(plugin.getServer().getPluginsFolder(), "AuthMe");
        File databaseFile = new File(authMeFolder, dbFile);

        if (!databaseFile.exists()) {
            plugin.getLogger().severe("AuthMe SQLite database not found at: " + databaseFile.getAbsolutePath());
            return false;
        }

        String url = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
        connection = DriverManager.getConnection(url);
        plugin.getLogger().info("Connected to AuthMe SQLite database: " + databaseFile.getAbsolutePath());
        return true;
    }

    /**
     * Initializes MySQL/MariaDB connection for AuthMe.
     */
    private boolean initializeMySQL(FileConfiguration authMeConfig) throws SQLException {
        String host = authMeConfig.getString("DataSource.mySQLHost", "localhost");
        int port = authMeConfig.getInt("DataSource.mySQLPort", 3306);
        String database = authMeConfig.getString("DataSource.mySQLDatabaseName", "authme");
        String username = authMeConfig.getString("DataSource.mySQLUsername", "root");
        String password = authMeConfig.getString("DataSource.mySQLPassword", "");

        String url;
        if (backend.equals("MARIADB")) {
            url = "jdbc:mariadb://" + host + ":" + port + "/" + database;
        } else {
            url = "jdbc:mysql://" + host + ":" + port + "/" + database;
        }

        connection = DriverManager.getConnection(url, username, password);
        plugin.getLogger().info("Connected to AuthMe MySQL/MariaDB database: " + database);
        return true;
    }

    /**
     * Gets the list of columns that exist in the AuthMe table.
     */
    public List<String> getAvailableColumns() {
        List<String> columns = new ArrayList<>();
        if (connection == null) {
            return columns;
        }

        String sql = "PRAGMA table_info(" + tableName + ");";
        if (backend.equals("MYSQL") || backend.equals("MARIADB")) {
            sql = "SHOW COLUMNS FROM " + tableName + ";";
        }

        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String columnName;
                if (backend.equals("SQLITE")) {
                    columnName = rs.getString("name");
                } else {
                    columnName = rs.getString("Field");
                }
                columns.add(columnName.toLowerCase());
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read AuthMe table columns: " + e.getMessage());
        }

        return columns;
    }

    /**
     * Reads all AuthMe accounts from the database.
     *
     * @return List of AuthMe account data
     */
    public List<AuthMeAccount> readAllAccounts() {
        List<AuthMeAccount> accounts = new ArrayList<>();
        if (connection == null) {
            return accounts;
        }

        List<String> availableColumns = getAvailableColumns();

        StringBuilder sql = new StringBuilder("SELECT ");
        if (availableColumns.contains("id")) sql.append("id, ");
        if (availableColumns.contains("username")) sql.append("username, ");
        if (availableColumns.contains("realname")) sql.append("realname, ");
        if (availableColumns.contains("password")) sql.append("password, ");
        if (availableColumns.contains("ip")) sql.append("ip, ");
        if (availableColumns.contains("lastlogin")) sql.append("lastlogin, ");
        if (availableColumns.contains("regdate")) sql.append("regdate, ");
        if (availableColumns.contains("email")) sql.append("email, ");
        
        // Remove trailing comma and space
        if (sql.toString().endsWith(", ")) {
            sql.setLength(sql.length() - 2);
        }
        sql.append(" FROM ").append(tableName);

        try (PreparedStatement ps = connection.prepareStatement(sql.toString());
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                AuthMeAccount account = new AuthMeAccount();
                
                if (availableColumns.contains("id")) account.id = rs.getInt("id");
                if (availableColumns.contains("username")) account.username = rs.getString("username");
                if (availableColumns.contains("realname")) account.realname = rs.getString("realname");
                if (availableColumns.contains("password")) account.password = rs.getString("password");
                if (availableColumns.contains("ip")) account.ip = rs.getString("ip");
                if (availableColumns.contains("lastlogin")) account.lastLogin = rs.getLong("lastlogin");
                if (availableColumns.contains("regdate")) account.regDate = rs.getLong("regdate");
                if (availableColumns.contains("email")) account.email = rs.getString("email");

                accounts.add(account);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read AuthMe accounts: " + e.getMessage());
        }

        return accounts;
    }

    /**
     * Closes the database connection.
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to close AuthMe database connection: " + e.getMessage());
            }
        }
    }

    /**
     * Data class representing an AuthMe account.
     */
    public static class AuthMeAccount {
        public int id;
        public String username;
        public String realname;
        public String password;
        public String ip;
        public long lastLogin;
        public long regDate;
        public String email;
    }
}
