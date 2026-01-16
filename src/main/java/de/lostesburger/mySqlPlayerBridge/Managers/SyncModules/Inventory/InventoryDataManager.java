package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Inventory;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Exceptions.NBTSerializationException;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public class InventoryDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public InventoryDataManager() {
        this.enabled = Main.modulesManager.syncInventory;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if (!this.mySqlManager.tableExists(Main.TABLE_NAME_INVENTORY)) {
                throw new RuntimeException("Inventory mysql table is missing!");
            }
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, boolean async) {
        if (!this.enabled)
            return;
        this.save(player);
    }

    public void saveManual(UUID uuid, String serializedInventory, boolean async) {
        if (async) {
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid.toString(), serializedInventory);
            }, Main.getInstance());
        } else {
            this.insertToMySql(uuid.toString(), serializedInventory);
        }
    }

    private void save(Player player) {
        if (!this.enabled)
            return;

        if (Main.nbtSerializer == null) {
            throw new NBTSerializationException("nbtserializer not loaded on serialize", null);
        }

        String serializedInventory;
        try {
            serializedInventory = Main.nbtSerializer.serialize(player.getInventory().getContents());

            if (serializedInventory == null || serializedInventory.trim().isEmpty()) {
                System.err.println("[InventoryDataManager] ERROR: Empty serialization for " + player.getName());
                return;
            }

            if (Main.DEBUG) {
                System.out.println("[InventoryDataManager] Serialized inventory (" + serializedInventory.length()
                        + " chars) for: " + player.getName());
            }
        } catch (Exception e) {
            System.err.println("[InventoryDataManager] Failed to serialize inventory for " + player.getName());
            throw new RuntimeException(e);
        }

        this.insertToMySql(player.getUniqueId().toString(), serializedInventory);
    }

    private void insertToMySql(String uuid, String serializedInventory) {
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_INVENTORY,
                    Map.of("uuid", uuid),
                    Map.of("inventory", serializedInventory));
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }

    public void applyPlayer(Player player) {
        if (!this.enabled)
            return;

        if (Main.nbtSerializer == null) {
            throw new NBTSerializationException("nbtserializer not loaded", null);
        }

        Map<String, Object> entry;
        try {
            entry = mySqlManager.getEntry(Main.TABLE_NAME_INVENTORY,
                    Map.of("uuid", player.getUniqueId().toString()));
        } catch (MySqlError e) {
            System.err.println("[InventoryDataManager] Database error loading inventory for " + player.getName());
            throw new RuntimeException(e);
        }

        if (entry == null || entry.isEmpty()) {
            if (Main.DEBUG) {
                System.out.println("[InventoryDataManager] No inventory data found for " + player.getName());
            }
            return;
        }

        String serializedData = String.valueOf(entry.get("inventory"));
        if (serializedData == null || serializedData.equals("null") || serializedData.trim().isEmpty()) {
            System.err.println("[InventoryDataManager] Invalid serialized data for " + player.getName());
            return;
        }

        if (Main.DEBUG) {
            System.out.println("[InventoryDataManager] Loading inventory (" + serializedData.length() + " chars) for: "
                    + player.getName());
        }

        try {
            org.bukkit.inventory.ItemStack[] items = Main.nbtSerializer.deserialize(serializedData);
            if (items == null) {
                System.err.println("[InventoryDataManager] Deserialization returned null for " + player.getName());
                return;
            }
            player.getInventory().setContents(items);
            if (Main.DEBUG) {
                System.out.println("[InventoryDataManager] Successfully loaded inventory for " + player.getName());
            }
        } catch (Exception e) {
            System.err.println("[InventoryDataManager] Failed to deserialize/apply inventory for " + player.getName());
            e.printStackTrace();
            player.kickPlayer(Chat.getMessage("sync-failed"));
            throw new RuntimeException(e);
        }
    }
}
