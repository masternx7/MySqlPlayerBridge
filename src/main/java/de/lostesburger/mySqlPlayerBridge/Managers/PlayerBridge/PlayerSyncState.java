package de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge;

public enum PlayerSyncState {
    IDLE,
    LOCKED,
    SAVING,
    SAVED,
    CONFIRMED,
    LOADING,
    COMPLETED
}
