package de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge;

import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class PlayerSyncLockManager {
    private static final ConcurrentHashMap<UUID, ReentrantLock> playerLocks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PlayerSyncState> playerStates = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> lockTimestamps = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> pendingAsyncOperations = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> lastSaveTimestamp = new ConcurrentHashMap<>();

    private static final long LOCK_TIMEOUT_MS = 30000;
    private static final long MAX_WAIT_TIME_MS = 15000;
    public static final long MIN_SAVE_INTERVAL_MS = 100;

    public static ReentrantLock getLock(UUID uuid) {
        return playerLocks.computeIfAbsent(uuid, k -> new ReentrantLock(true));
    }

    public static boolean tryLock(UUID uuid) {
        return tryLock(uuid, MAX_WAIT_TIME_MS);
    }

    public static boolean tryLock(UUID uuid, long timeoutMs) {
        ReentrantLock lock = getLock(uuid);
        try {
            if (lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                lockTimestamps.put(uuid, System.currentTimeMillis());
                if (Main.DEBUG) {
                    System.out.println("[SyncLock] Player " + uuid + " locked successfully");
                }
                return true;
            } else {
                if (Main.DEBUG) {
                    System.out.println("[SyncLock] Failed to acquire lock for " + uuid + " within timeout");
                }
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (Main.DEBUG) {
                System.out.println("[SyncLock] Lock interrupted for " + uuid);
            }
            return false;
        }
    }

    public static void unlock(UUID uuid) {
        ReentrantLock lock = getLock(uuid);
        if (lock.isHeldByCurrentThread()) {
            lockTimestamps.remove(uuid);
            lock.unlock();
            if (Main.DEBUG) {
                System.out.println("[SyncLock] Player " + uuid + " unlocked");
            }
        }
    }

    public static void setState(UUID uuid, PlayerSyncState state) {
        PlayerSyncState oldState = playerStates.get(uuid);
        playerStates.put(uuid, state);
        if (state == PlayerSyncState.CONFIRMED) {
            lastSaveTimestamp.put(uuid, System.currentTimeMillis());
        }
        if (Main.DEBUG) {
            System.out.println("[SyncState] Player " + uuid + " state: " + oldState + " → " + state);
        }
    }

    public static void incrementAsyncOperation(UUID uuid) {
        int newCount = pendingAsyncOperations.compute(uuid, (k, v) -> (v == null ? 0 : v) + 1);
        if (Main.DEBUG) {
            System.out.println("[SyncLock] Async operations for " + uuid + ": " + newCount);
        }
    }

    public static void decrementAsyncOperation(UUID uuid) {
        int newCount = pendingAsyncOperations.compute(uuid, (k, v) -> {
            if (v == null || v <= 1)
                return 0;
            return v - 1;
        });
        if (Main.DEBUG) {
            System.out.println("[SyncLock] Async operations for " + uuid + ": " + newCount);
        }
    }

    public static boolean hasIncompleteAsyncOperations(UUID uuid) {
        return pendingAsyncOperations.getOrDefault(uuid, 0) > 0;
    }

    public static long getTimeSinceLastSave(UUID uuid) {
        Long lastSave = lastSaveTimestamp.get(uuid);
        if (lastSave == null)
            return Long.MAX_VALUE;
        return System.currentTimeMillis() - lastSave;
    }

    public static PlayerSyncState getState(UUID uuid) {
        return playerStates.getOrDefault(uuid, PlayerSyncState.IDLE);
    }

    public static boolean isLocked(UUID uuid) {
        return getLock(uuid).isLocked();
    }

    public static boolean isInSync(UUID uuid) {
        PlayerSyncState state = getState(uuid);
        return state != PlayerSyncState.IDLE && state != PlayerSyncState.COMPLETED;
    }

    public static void waitForCompletion(UUID uuid, long timeoutMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (isInSync(uuid) || hasIncompleteAsyncOperations(uuid)) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                if (Main.DEBUG) {
                    System.out.println("[SyncLock] Timeout waiting for completion: " + uuid + " (state: "
                            + getState(uuid) + ", pending ops: " + pendingAsyncOperations.getOrDefault(uuid, 0) + ")");
                }
                break;
            }
            Thread.sleep(50);
        }
        if (Main.DEBUG && !hasIncompleteAsyncOperations(uuid)) {
            System.out.println("[SyncLock] Completion confirmed for " + uuid);
        }
    }

    public static boolean waitForSaveConfirmation(UUID uuid, long timeoutMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (hasIncompleteAsyncOperations(uuid)) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                if (Main.DEBUG) {
                    System.out.println("[SyncLock] Save confirmation timeout for " + uuid + " (pending: "
                            + pendingAsyncOperations.getOrDefault(uuid, 0) + ")");
                }
                return false;
            }
            Thread.sleep(25);
        }
        return true;
    }

    public static void forceUnlock(UUID uuid) {
        ReentrantLock lock = getLock(uuid);
        while (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
        playerStates.put(uuid, PlayerSyncState.IDLE);
        lockTimestamps.remove(uuid);
        if (Main.DEBUG) {
            System.out.println("[SyncLock] Force unlocked " + uuid);
        }
    }

    public static void cleanup(UUID uuid) {
        unlock(uuid);
        playerStates.remove(uuid);
        lockTimestamps.remove(uuid);
        playerLocks.remove(uuid);
        pendingAsyncOperations.remove(uuid);
        lastSaveTimestamp.remove(uuid);
        if (Main.DEBUG) {
            System.out.println("[SyncLock] Cleaned up resources for " + uuid);
        }
    }

    public static void checkAndCleanStaleLocksHolder() {
        long currentTime = System.currentTimeMillis();
        lockTimestamps.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            long lockTime = entry.getValue();
            if (currentTime - lockTime > LOCK_TIMEOUT_MS) {
                if (Main.DEBUG) {
                    System.out.println("[SyncLock] Detected stale lock for " + uuid + ", force cleaning");
                }
                forceUnlock(uuid);
                return true;
            }
            return false;
        });
    }

    public static boolean canPerformAction(Player player, String action) {
        UUID uuid = player.getUniqueId();
        PlayerSyncState state = getState(uuid);

        if (state == PlayerSyncState.SAVING || state == PlayerSyncState.LOADING) {
            if (Main.DEBUG) {
                System.out.println(
                        "[SyncLock] Action '" + action + "' blocked for " + player.getName() + " (state: "
                                + state + ")");
            }
            return false;
        }
        return true;
    }
}
