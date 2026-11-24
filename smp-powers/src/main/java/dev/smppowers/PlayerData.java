package dev.smppowers;

/** Saved per player in config/smppowers/players.json. */
public class PlayerData {
    public String power;            // Power id, null until first join
    public int level = 1;           // 1 to maxLevel
    public int charge = 0;          // 0 to 100
    public long abilityReadyAt = 0; // epoch milliseconds, so cooldowns keep counting while offline
}
