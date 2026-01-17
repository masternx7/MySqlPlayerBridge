package de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge;

import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.MySqlData.MySqlDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Player.PlayerManager;
import de.lostesburger.mySqlPlayerBridge.NoEntryProtection.NoEntryProtection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class PlayerBridgeManager implements Listener {
    private final MySqlDataManager mySqlDataManager;

    public PlayerBridgeManager() {
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
        this.startAutoSyncTask();
        this.mySqlDataManager = Main.mySqlConnectionHandler.getMySqlDataManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Scheduler.runAsync(() -> {
            try {
                if (Main.DEBUG) {
                    System.out.println("[PlayerBridge] Waiting for completion before loading: " + player.getName());
                }
                PlayerSyncLockManager.waitForCompletion(uuid, 30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[PlayerBridge] Interrupted while waiting for " + player.getName());
            }

            if (this.mySqlDataManager.hasData(player)) {
                Scheduler.run(() -> {
                    this.mySqlDataManager.applyDataToPlayer(player);
                    PlayerManager.sendDataLoadedMessage(player);
                }, Main.getInstance());
            } else {
                if (NoEntryProtection.isTriggered(player))
                    return;
                PlayerManager.registerPlayer(player);
                this.mySqlDataManager.savePlayerData(player, false);
                PlayerManager.sendCreatedDataMessage(player);
            }
        }, Main.getInstance());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeave(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (Main.DEBUG) {
            System.out.println("[PlayerBridge] Player leaving, critical save: " + player.getName());
        }

        try {
            this.mySqlDataManager.savePlayerDataCritical(player);
        } finally {
            PlayerSyncLockManager.cleanup(uuid);
            if (Main.DEBUG) {
                System.out.println("[PlayerBridge] Cleanup completed: " + player.getName());
            }
        }
    }

    private void startAutoSyncTask() {
        assert this.mySqlDataManager != null;
        Scheduler.Task task = Scheduler.runTimerAsync(() -> {

            PlayerSyncLockManager.checkAndCleanStaleLocksHolder();
            Main.mySqlConnectionHandler.getMySqlDataManager().saveAllOnlinePlayersAsync();

        }, Main.modulesManager.syncTaskDelay, Main.modulesManager.syncTaskDelay, Main.getInstance());
        Main.schedulers.add(task);
    }

}
