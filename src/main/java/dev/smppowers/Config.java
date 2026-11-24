package dev.smppowers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/** Everything configurable lives in config/smppowers/config.json. Run /power reload after editing. */
public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Levels
    public int maxLevel = 5;
    public int ultimateMinLevel = 5;            // ultimates unlock at max level, like Strength SMP
    public double cooldownReductionPerLevel = 0.08;
    public double durationBonusPerLevel = 0.10;
    public double damageBonusPerLevel = 0.10;

    // Ultimate charge (0 to 100)
    public int killCharge = 25;
    public int mobKillCharge = 2;
    public int damageTakenCharge = 1;
    public int chargeAfterDeath = 0;

    // Kills
    public int antiFarmMinutes = 30;
    public boolean bossesDropEssence = true;    // Ender Dragon, Wither, Warden

    // Balance
    public double maxAbilityDamage = 10.0;
    public boolean affectOtherPlayers = true;
    public boolean affectHostileMobs = true;
    public boolean disableMovementUltimatesInEnd = true;
    public boolean allowInCreative = false;
    public List<String> disabledDimensions = new ArrayList<>();   // e.g. "minecraft:the_nether"

    // Tide ultimate: right-click a trident to ride a riptide with no water
    public boolean tideSurgeRequiresTrident = true;

    // Display
    public boolean actionBarHud = true;

    public Map<String, Integer> powerWeights = new LinkedHashMap<>();
    public Map<String, PowerSettings> powers = new LinkedHashMap<>();

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("smppowers");
    }

    public PowerSettings settings(Power p) {
        return powers.computeIfAbsent(p.id(), k -> PowerSettings.defaultsFor(p));
    }

    public static Config load() {
        Path file = dir().resolve("config.json");
        Config cfg = new Config();
        try {
            if (Files.exists(file)) {
                try {
                    Config read = GSON.fromJson(Files.readString(file), Config.class);
                    if (read != null) cfg = read;
                } catch (Exception parseError) {
                    // Keep a copy so a typo in the file never wipes the admin's numbers.
                    Path backup = dir().resolve("config.json.broken");
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    PowersMod.LOGGER.error("config.json is invalid, using defaults. A copy was saved as config.json.broken", parseError);
                }
            }
        } catch (Exception e) {
            PowersMod.LOGGER.error("Could not read config.json, using defaults", e);
        }
        cfg.fillDefaults();
        cfg.save();
        return cfg;
    }

    private void fillDefaults() {
        if (powers == null) powers = new LinkedHashMap<>();
        if (powerWeights == null) powerWeights = new LinkedHashMap<>();
        if (disabledDimensions == null) disabledDimensions = new ArrayList<>();
        for (Power p : Power.values()) {
            powers.computeIfAbsent(p.id(), k -> PowerSettings.defaultsFor(p));
            powerWeights.putIfAbsent(p.id(), 1);
        }
        if (maxLevel < 1) maxLevel = 1;
        if (ultimateMinLevel < 1) ultimateMinLevel = 1;
    }

    public void save() {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("config.json"), GSON.toJson(this));
        } catch (Exception e) {
            PowersMod.LOGGER.error("Could not write config.json", e);
        }
    }
}
