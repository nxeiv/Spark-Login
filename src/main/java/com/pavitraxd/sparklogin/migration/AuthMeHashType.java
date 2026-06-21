package com.pavitraxd.sparklogin.migration;

/**
 * Enum representing the hash type used for password storage.
 * Supports native SparkLogin BCrypt and AuthMe migrated hashes.
 */
public enum AuthMeHashType {
    BCRYPT,
    AUTHME_SHA256,
    AUTHME_BCRYPT;

    /**
     * Converts string value from database to enum.
     */
    public static AuthMeHashType fromString(String value) {
        if (value == null) return BCRYPT;
        try {
            return AuthMeHashType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BCRYPT;
        }
    }

    /**
     * Converts enum to string for database storage.
     */
    public String toDatabaseString() {
        return this.name();
    }
}
