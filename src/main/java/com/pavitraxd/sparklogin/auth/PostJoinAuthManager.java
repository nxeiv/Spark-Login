package com.pavitraxd.sparklogin.auth;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks players who are pending post-join authentication.
 * While a player is in this set they are fully locked down.
 */
public class PostJoinAuthManager {

    private final Set<UUID> pendingAuth = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void addPending(UUID uuid) {
        pendingAuth.add(uuid);
    }

    public void removePending(UUID uuid) {
        pendingAuth.remove(uuid);
    }

    public boolean isPending(UUID uuid) {
        return pendingAuth.contains(uuid);
    }

    /** Unlocks the player and removes them from the pending set. */
    public void unlock(Player player) {
        removePending(player.getUniqueId());
    }
}
