package com.pavitraxd.sparklogin.database.model;

import com.pavitraxd.sparklogin.migration.AuthMeHashType;

import java.util.UUID;

/**
 * Represents a registered player profile in SparkLogin.
 */
public class Account {
    private final UUID uuid;
    private final String username;
    private String passwordHash;
    private final long registeredAt;
    private long lastLogin;
    private String sessionToken;
    private long sessionLast;
    // --- Extended account state (additive) ---
    private boolean requiresRegistration;
    private String lastLoginIp;
    private String registrationIp;
    private AuthMeHashType hashType;

    public Account(UUID uuid, String username, String passwordHash, long registeredAt, long lastLogin, String sessionToken, long sessionLast) {
        this(uuid, username, passwordHash, registeredAt, lastLogin, sessionToken, sessionLast, false, null, null, AuthMeHashType.BCRYPT);
    }

    public Account(UUID uuid, String username, String passwordHash, long registeredAt, long lastLogin,
                   String sessionToken, long sessionLast, boolean requiresRegistration,
                   String lastLoginIp, String registrationIp) {
        this(uuid, username, passwordHash, registeredAt, lastLogin, sessionToken, sessionLast, requiresRegistration, lastLoginIp, registrationIp, AuthMeHashType.BCRYPT);
    }

    public Account(UUID uuid, String username, String passwordHash, long registeredAt, long lastLogin,
                   String sessionToken, long sessionLast, boolean requiresRegistration,
                   String lastLoginIp, String registrationIp, AuthMeHashType hashType) {
        this.uuid = uuid;
        this.username = username;
        this.passwordHash = passwordHash;
        this.registeredAt = registeredAt;
        this.lastLogin = lastLogin;
        this.sessionToken = sessionToken;
        this.sessionLast = sessionLast;
        this.requiresRegistration = requiresRegistration;
        this.lastLoginIp = lastLoginIp;
        this.registrationIp = registrationIp;
        this.hashType = hashType;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public long getRegisteredAt() {
        return registeredAt;
    }

    public long getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(long lastLogin) {
        this.lastLogin = lastLogin;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public long getSessionLast() {
        return sessionLast;
    }

    public void setSessionLast(long sessionLast) {
        this.sessionLast = sessionLast;
    }

    public boolean isRequiresRegistration() {
        return requiresRegistration;
    }

    public void setRequiresRegistration(boolean requiresRegistration) {
        this.requiresRegistration = requiresRegistration;
    }

    public String getLastLoginIp() {
        return lastLoginIp;
    }

    public void setLastLoginIp(String lastLoginIp) {
        this.lastLoginIp = lastLoginIp;
    }

    public String getRegistrationIp() {
        return registrationIp;
    }

    public void setRegistrationIp(String registrationIp) {
        this.registrationIp = registrationIp;
    }

    public AuthMeHashType getHashType() {
        return hashType;
    }

    public void setHashType(AuthMeHashType hashType) {
        this.hashType = hashType;
    }
}
