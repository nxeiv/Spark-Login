package com.pavitraxd.sparklogin.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Verifies AuthMe password hashes.
 * Supports SHA256 and BCRYPT formats used by AuthMeReloaded.
 */
public class AuthMeHashVerifier {

    /**
     * Detects the hash type from a stored password hash.
     *
     * @param hash The stored password hash
     * @return The detected hash type, or null if unsupported
     */
    public static AuthMeHashType detectHashType(String hash) {
        if (hash == null || hash.isEmpty()) {
            return null;
        }

        if (hash.startsWith("$SHA$")) {
            return AuthMeHashType.AUTHME_SHA256;
        }

        if (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$")) {
            return AuthMeHashType.AUTHME_BCRYPT;
        }

        // Native SparkLogin BCrypt hashes also start with $2a$, $2b$, or $2y$
        // We treat them as BCRYPT
        if (hash.startsWith("$2")) {
            return AuthMeHashType.BCRYPT;
        }

        return null;
    }

    /**
     * Verifies a password against an AuthMe SHA256 hash.
     * AuthMe format: $SHA$salt$hash
     * Verification: sha256(sha256(password) + salt)
     *
     * @param password The raw password
     * @param storedHash The stored hash in format $SHA$salt$hash
     * @return true if password matches
     */
    public static boolean verifySha256(String password, String storedHash) {
        if (password == null || storedHash == null) {
            return false;
        }

        if (!storedHash.startsWith("$SHA$")) {
            return false;
        }

        String[] parts = storedHash.split("\\$");
        if (parts.length < 4) {
            return false;
        }

        String salt = parts[2];
        String hash = parts[3];

        try {
            // First SHA256: sha256(password) - returns hex string
            String firstHash = sha256(password);

            // Second SHA256: sha256(sha256(password) + salt) - salt as string
            String combined = firstHash + salt;
            String computedHash = sha256(combined);

            return computedHash.equalsIgnoreCase(hash);
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    /**
     * SHA256 hash function matching AuthMe's implementation.
     */
    private static String sha256(String message) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.reset();
        md.update(message.getBytes(StandardCharsets.UTF_8));
        byte[] digest = md.digest();
        return String.format("%064x", new java.math.BigInteger(1, digest));
    }
}
