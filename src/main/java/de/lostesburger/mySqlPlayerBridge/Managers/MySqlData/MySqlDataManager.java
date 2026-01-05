package de.lostesburger.mySqlPlayerBridge.Managers.MySqlData;


import de.craftcore.craftcore.global.minecraftVersion.Minecraft;
import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.craftcore.craftcore.global.scheduler.SchedulerException;
import de.lostesburger.mySqlPlayerBridge.Exceptions.NBTSerializationException;
import de.lostesburger.mySqlPlayerBridge.Exceptions.NoPlayerDataException;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.Modules.ModulesManager;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;


public class MySqlDataManager {
    public final MySqlManager mySqlManager;
    public final boolean DEBUG = false;
    
    private final ConcurrentHashMap<UUID, ReentrantLock> playerLocks = new ConcurrentHashMap<>();

    public MySqlDataManager(MySqlManager manager){
        mySqlManager = manager;
    }
    
    private ReentrantLock getPlayerLock(UUID uuid) {
        return playerLocks.computeIfAbsent(uuid, k -> new ReentrantLock());
    }

    public boolean hasData(Player player){
        UUID uuid = player.getUniqueId();
        try {
            if(Main.DEBUG){
                System.out.println("Checking if player has data! Player: "+player.getName());
            }
            return this.mySqlManager.entryExists(Main.TABLE_NAME, Map.of("uuid", uuid.toString()));
        } catch (MySqlError e) {
            new MySqlErrorHandler().hasPlayerData(player);
            throw new RuntimeException(e);
        }
    }

    public HashMap<String, Object> getCurrentData(Player player){

        String gamemode = player.getGameMode().toString();
        int exp_level = player.getLevel();
        float exp = player.getExp();
        double health = player.getHealth();
        double money = 0.0;
        if(Main.modulesManager.syncVaultEconomy){
            money = Main.vaultManager.getBalance(player);
        }

        Location location = player.getLocation();
        String world = Objects.requireNonNull(location.getWorld()).getName();
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        float yaw = location.getYaw();
        float pitch = location.getPitch();

        HashMap<String, Object> map = new HashMap<>();
        map.put("server_type", Main.serverType);
        map.put("serialization_type", Main.serializationType.toString());
        map.put("gamemode", gamemode);
        map.put("exp_level", exp_level);
        map.put("exp", exp);
        map.put("health", health);
        //map.put("saturation",saturation);
        map.put("money", money);
        map.put("world", world);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("yaw", yaw);
        map.put("pitch", pitch);

        String serializedInventory;
        String serializedEnderChest;
        String serializedArmor;
        try {
            if(Main.nbtSerializer == null){
                throw new NBTSerializationException("nbtserializer not loaded on serialize", null);
            }
            serializedInventory = Main.nbtSerializer.serialize(player.getInventory().getContents());
            serializedEnderChest = Main.nbtSerializer.serialize(player.getEnderChest().getContents());
            serializedArmor = Main.nbtSerializer.serialize(new ItemStack[]{
                    player.getInventory().getBoots(),
                    player.getInventory().getLeggings(),
                    player.getInventory().getChestplate(),
                    player.getInventory().getHelmet()
            });

            if(Main.DEBUG){
                System.out.println("Inv: "+serializedInventory);
                System.out.println("EnderChest"+serializedEnderChest);
                System.out.println("Armor: "+serializedArmor);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        map.put("inventory", serializedInventory);
        map.put("enderchest", serializedEnderChest);
        map.put("armor", serializedArmor);

        return map;
    }
    
    public void savePlayerData(Player player, boolean async){
        UUID uuid = player.getUniqueId();
        ReentrantLock lock = getPlayerLock(uuid);
        
        lock.lock();
        try {
            if(!this.hasData(player) && Main.config.getBoolean("settings.no-entry-protection")){ 
                return; 
            }
            
            HashMap<String, Object> data = this.getCurrentData(player);
            try {
                if(Main.DEBUG){
                    System.out.println("Attempting to save player data. Player: "+player.getName());
                }
                this.mySqlManager.setOrUpdateEntry(Main.TABLE_NAME, Map.of("uuid", uuid.toString()), data);
            } catch (MySqlError e) {
                new MySqlErrorHandler().savePlayerData(player, data);
                throw new RuntimeException(e);
            }

            Main.effectDataManager.savePlayer(player, async);
            Main.advancementDataManager.savePlayer(player, async);
            Main.statsDataManager.savePlayer(player, async);
            Main.hotbarSlotSelectionDataManager.savePlayer(player, async);
            Main.saturationDataManager.savePlayer(player, async);
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads() && !lock.isLocked()) {
                playerLocks.remove(uuid);
            }
        }
    }


    public HashMap<String, Object> getPlayerDataFromDB(Player player) throws NoPlayerDataException {
        if(!hasData(player)){
            throw new NoPlayerDataException(player);
        }

        try {
            return (HashMap<String, Object>) this.mySqlManager.getEntry(Main.TABLE_NAME, Map.of("uuid", player.getUniqueId().toString()));
        } catch (MySqlError e) {
            new MySqlErrorHandler().getPlayerData(player);
            throw new RuntimeException(e);
        }
    }

    public boolean checkDatabaseConnection(){ return Main.mySqlConnectionHandler.getMySQL().isConnectionAlive(); }

    public void applyDataToPlayer(Player player){
        UUID uuid = player.getUniqueId();
        ReentrantLock lock = getPlayerLock(uuid);
        
        lock.lock();
        try {
            if(Main.DEBUG){
                System.out.println("attempting to applyDataToPlayer player: "+player.getName());
            }

            HashMap<String, Object> data;
            try {
                data = this.getPlayerDataFromDB(player);
            } catch (NoPlayerDataException e) {
                throw new RuntimeException(e);
            }

            Scheduler.run(() -> {
            ModulesManager modules = Main.modulesManager;

            if(modules.syncInventory){
                try {
                    if(Main.nbtSerializer == null){
                        throw new NBTSerializationException("nbtserializer not loaded", null);
                    }
                    player.getInventory().setContents(Main.nbtSerializer.deserialize(String.valueOf(data.get("inventory"))));
                } catch (Exception e) {
                    player.kickPlayer(Chat.getMessage("sync-failed"));
                    throw new RuntimeException(e);
                }

            }
            if(modules.syncEnderChest){
                try {
                    if(Main.nbtSerializer == null){
                        throw new NBTSerializationException("nbtserializer not loaded", null);
                    }
                    player.getEnderChest().setContents(Main.nbtSerializer.deserialize(String.valueOf(data.get("enderchest"))));
                } catch (Exception e) {
                    player.kickPlayer(Chat.getMessage("sync-failed"));
                    throw new RuntimeException(e);
                }
            }
            if(modules.syncArmorSlots){
                try {
                    if(Main.nbtSerializer == null){
                        throw new NBTSerializationException("nbtserializer not loaded", null);
                    }
                    player.getInventory().setArmorContents(Main.nbtSerializer.deserialize(String.valueOf(data.get("armor"))));
                } catch (Exception e) {
                    player.kickPlayer(Chat.getMessage("sync-failed"));
                    throw new RuntimeException(e);
                }
            }
            if(modules.syncGamemode){
                player.setGameMode(GameMode.valueOf(String.valueOf(data.get("gamemode"))));
            }
            if(modules.syncHealth){
                double healthToSet = (Double) data.get("health");
                double maxHealth = player.getMaxHealth();
                if(healthToSet > maxHealth){
                    player.setHealth(maxHealth);
                    if(Main.DEBUG){
                        System.out.println("Player " + player.getName() + " health clamped from " + healthToSet + " to max health " + maxHealth);
                    }
                } else {
                    player.setHealth(healthToSet);
                }
            }
            if(modules.syncVaultEconomy){
                Main.vaultManager.setBalance(player, (Double) data.get("money"));
            }
            if(modules.syncExp){
                player.setLevel((Integer) data.get("exp_level"));
                player.setExp((Float) data.get("exp"));
            }
            if(modules.syncLocation){
                World world = Bukkit.getWorld((String) data.get("world"));
                Location location = new Location(world, (Double) data.get("x"), (Double) data.get("y"), (Double) data.get("z"), (Float) data.get("yaw"), (Float) data.get("pitch"));

                if (Minecraft.isFolia()){
                    try {
                        Scheduler.runRegionalScheduler(() -> {
                            try {
                                Method teleportAsync = player.getClass().getMethod("teleportAsync", Location.class);
                                CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) teleportAsync.invoke(player, location);

                                future.thenAccept(success -> {
                                    if (!success) {
                                        Bukkit.getLogger().warning("Failed to teleport player! Player: " + player.getName());
                                    }
                                });
                            } catch (NoSuchMethodException e) {} catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, Main.getInstance(), location);
                    } catch (SchedulerException e) {
                        throw new RuntimeException(e+ " Caused by trying to teleport the player async without running Folia");
                    }
                }else {
                    player.teleport(location);
                }
            }

        }, Main.getInstance());

        Main.effectDataManager.applyPlayer(player);
        Main.advancementDataManager.applyPlayer(player);
        Main.statsDataManager.applyPlayer(player);
        Main.hotbarSlotSelectionDataManager.applyPlayer(player);
        Main.saturationDataManager.applyPlayer(player);
        
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads() && !lock.isLocked()) {
                playerLocks.remove(uuid);
            }
        }
    }

    public void saveAllOnlinePlayers(){
        for (Player player : Bukkit.getOnlinePlayers()){
            this.savePlayerData(player, false);
        }
    }

    public void saveAllOnlinePlayersAsync(){
        for (Player player : Bukkit.getOnlinePlayers()){
            Scheduler.runAsync(() -> { this.savePlayerData(player, true);}, Main.getInstance());
        }
    }
}
