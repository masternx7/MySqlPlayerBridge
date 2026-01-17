package de.lostesburger.mySqlPlayerBridge.Managers.MySqlData;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge.PlayerSyncLockManager;
import de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge.PlayerSyncState;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public class MySqlDataManager {
    public final MySqlManager mySqlManager;
    public final boolean DEBUG = false;

    public MySqlDataManager(MySqlManager manager) {
        mySqlManager = manager;
    }

    public boolean hasData(Player player) {
        UUID uuid = player.getUniqueId();
        try {
            if (Main.DEBUG) {
                System.out.println("Checking if player has data! Player: " + player.getName());
            }
            return this.mySqlManager.entryExists(Main.TABLE_NAME_REGISTERED_PLAYERS, Map.of("uuid", uuid.toString()));
        } catch (MySqlError e) {
            new MySqlErrorHandler().hasPlayerData(player);
            throw new RuntimeException(e);
        }
    }

    public void savePlayerData(Player player, boolean async) {
        savePlayerData(player, async, false);
    }

    public void savePlayerData(Player player, boolean async, boolean criticalSave) {
        UUID uuid = player.getUniqueId();

        long lockTimeout = async ? 3000 : (criticalSave ? 10000 : 5000);
        if (!PlayerSyncLockManager.tryLock(uuid, lockTimeout)) {
            if (Main.DEBUG) {
                System.out.println("[MySqlDataManager] Failed to acquire lock for save: " + player.getName());
            }
            if (criticalSave) {
                System.err.println("[MySqlDataManager] CRITICAL: Failed to save on disconnect for " + player.getName());
            }
            return;
        }

        try {
            if (!this.hasData(player) && Main.config.getBoolean("settings.no-entry-protection")) {
                return;
            }

            long timeSinceLastSave = PlayerSyncLockManager.getTimeSinceLastSave(uuid);
            if (timeSinceLastSave < PlayerSyncLockManager.MIN_SAVE_INTERVAL_MS) {
                if (Main.DEBUG) {
                    System.out.println("[MySqlDataManager] Skipping save (too soon): " + player.getName());
                }
                return;
            }

            if (async) {
                PlayerSyncLockManager.incrementAsyncOperation(uuid);
            }

            PlayerSyncLockManager.setState(uuid, PlayerSyncState.SAVING);

            if (Main.DEBUG) {
                System.out.println("[MySqlDataManager] Starting " + (async ? "ASYNC" : "SYNC") + " save for player: "
                        + player.getName());
            }

            if (async) {
                Scheduler.runAsync(() -> {
                    try {
                        performSaveOperations(player);
                        PlayerSyncLockManager.setState(uuid, PlayerSyncState.SAVED);
                        PlayerSyncLockManager.setState(uuid, PlayerSyncState.CONFIRMED);
                        if (Main.DEBUG) {
                            System.out.println("[MySqlDataManager] ASYNC save confirmed for: " + player.getName());
                        }
                    } catch (Exception e) {
                        System.err.println("[MySqlDataManager] ASYNC save error for " + player.getName());
                        e.printStackTrace();
                        PlayerSyncLockManager.setState(uuid, PlayerSyncState.IDLE);
                    } finally {
                        PlayerSyncLockManager.decrementAsyncOperation(uuid);
                        PlayerSyncLockManager.unlock(uuid);
                    }
                }, Main.getInstance());
            } else {
                try {
                    performSaveOperations(player);
                    PlayerSyncLockManager.setState(uuid, PlayerSyncState.SAVED);
                    PlayerSyncLockManager.setState(uuid, PlayerSyncState.CONFIRMED);
                    if (Main.DEBUG) {
                        System.out.println("[MySqlDataManager] SYNC save confirmed for: " + player.getName());
                    }
                } catch (Exception e) {
                    if (Main.DEBUG) {
                        System.err.println("[MySqlDataManager] Error saving player data: " + player.getName());
                        e.printStackTrace();
                    }
                    PlayerSyncLockManager.setState(uuid, PlayerSyncState.IDLE);
                    throw e;
                } finally {
                    PlayerSyncLockManager.setState(uuid, PlayerSyncState.IDLE);
                }
            }
        } finally {
            if (!async) {
                PlayerSyncLockManager.unlock(uuid);
            }
        }
    }

    private void performSaveOperations(Player player) {
        SyncManager.inventoryDataManager.savePlayer(player, false);
        SyncManager.armorDataManager.savePlayer(player, false);
        SyncManager.enderchestDataManager.savePlayer(player, false);
        SyncManager.locationDataManager.savePlayer(player, false);
        SyncManager.experienceDataManager.savePlayer(player, false);
        SyncManager.healthDataManager.savePlayer(player, false);
        SyncManager.gamemodeDataManager.savePlayer(player, false);
        SyncManager.moneyDataManager.savePlayer(player, false);
        SyncManager.effectDataManager.savePlayer(player, false);
        SyncManager.advancementDataManager.savePlayer(player, false);
        SyncManager.statsDataManager.savePlayer(player, false);
        SyncManager.hotbarSlotSelectionDataManager.savePlayer(player, false);
        SyncManager.saturationDataManager.savePlayer(player, false);
    }

    public boolean checkDatabaseConnection() {
        return Main.mySqlConnectionHandler.getMySQL().isConnectionAlive();
    }

    public void applyDataToPlayer(Player player) {
        UUID uuid = player.getUniqueId();

        if (!PlayerSyncLockManager.tryLock(uuid)) {
            if (Main.DEBUG) {
                System.out.println("[MySqlDataManager] Failed to acquire lock for applying data: " + player.getName());
            }
            return;
        }

        PlayerSyncLockManager.incrementAsyncOperation(uuid);

        try {
            PlayerSyncLockManager.setState(uuid, PlayerSyncState.LOADING);

            if (Main.DEBUG) {
                System.out.println("[MySqlDataManager] Attempting to apply data to player: " + player.getName());
            }

            SyncManager.inventoryDataManager.applyPlayer(player);
            SyncManager.armorDataManager.applyPlayer(player);
            SyncManager.enderchestDataManager.applyPlayer(player);
            SyncManager.locationDataManager.applyPlayer(player);
            SyncManager.experienceDataManager.applyPlayer(player);
            SyncManager.healthDataManager.applyPlayer(player);
            SyncManager.gamemodeDataManager.applyPlayer(player);
            SyncManager.moneyDataManager.applyPlayer(player);
            SyncManager.effectDataManager.applyPlayer(player);
            SyncManager.advancementDataManager.applyPlayer(player);
            SyncManager.statsDataManager.applyPlayer(player);
            SyncManager.hotbarSlotSelectionDataManager.applyPlayer(player);
            SyncManager.saturationDataManager.applyPlayer(player);

            PlayerSyncLockManager.setState(uuid, PlayerSyncState.COMPLETED);

            if (Main.DEBUG) {
                System.out.println("[MySqlDataManager] Data applied successfully to player: " + player.getName());
            }
        } catch (Exception e) {
            if (Main.DEBUG) {
                System.err.println("[MySqlDataManager] Error applying player data: " + player.getName());
                e.printStackTrace();
            }
            PlayerSyncLockManager.setState(uuid, PlayerSyncState.IDLE);
            throw e;
        } finally {
            PlayerSyncLockManager.setState(uuid, PlayerSyncState.IDLE);
            PlayerSyncLockManager.decrementAsyncOperation(uuid);
            PlayerSyncLockManager.unlock(uuid);
        }
    }

    public void saveAllOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.savePlayerData(player, false, false);
        }
    }

    public void saveAllOnlinePlayersAsync() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.savePlayerData(player, true, false);
        }
    }

    public void savePlayerDataCritical(Player player) {
        savePlayerData(player, true, true);
    }
}
