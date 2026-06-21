package com.pavitraxd.sparklogin.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.pavitraxd.sparklogin.SparkLoginPlugin;
import com.pavitraxd.sparklogin.database.model.Account;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Caches Account objects to reduce database queries on SMP servers.
 * Uses Caffeine for high-performance caching with TTL-based expiration.
 */
public class AccountCache {
    private final SparkLoginPlugin plugin;
    private final Cache<UUID, Account> cache;

    public AccountCache(SparkLoginPlugin plugin) {
        this.plugin = plugin;
        
        // Load cache settings from config
        long ttlMinutes = plugin.getConfig().getLong("cache.account-ttl-minutes", 5);
        long maxSize = plugin.getConfig().getLong("cache.max-size", 1000);
        
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(new Expiry<UUID, Account>() {
                    @Override
                    public long expireAfterCreate(UUID key, Account value, long currentTime) {
                        return TimeUnit.MINUTES.toNanos(ttlMinutes);
                    }

                    @Override
                    public long expireAfterUpdate(UUID key, Account value, long currentTime, long currentDuration) {
                        return TimeUnit.MINUTES.toNanos(ttlMinutes);
                    }

                    @Override
                    public long expireAfterRead(UUID key, Account value, long currentTime, long currentDuration) {
                        // Extend TTL on read (sliding expiration)
                        return TimeUnit.MINUTES.toNanos(ttlMinutes);
                    }
                })
                .build();
        
        plugin.getLogger().info("Account cache initialized with TTL: " + ttlMinutes + " minutes, max size: " + maxSize);
    }

    /**
     * Gets an account from cache or returns null if not cached.
     */
    public Account get(UUID uuid) {
        return cache.getIfPresent(uuid);
    }

    /**
     * Puts an account into the cache.
     */
    public void put(UUID uuid, Account account) {
        cache.put(uuid, account);
    }

    /**
     * Invalidates (removes) an account from the cache.
     */
    public void invalidate(UUID uuid) {
        cache.invalidate(uuid);
    }

    /**
     * Clears all entries from the cache.
     */
    public void clear() {
        cache.invalidateAll();
    }

    /**
     * Returns the current cache size.
     */
    public long size() {
        return cache.estimatedSize();
    }

    /**
     * Returns cache statistics for monitoring.
     */
    public String getStats() {
        return String.format("Cache size: %d, hit rate: %.2f%%",
                cache.estimatedSize(),
                cache.stats().hitRate() * 100);
    }
}
