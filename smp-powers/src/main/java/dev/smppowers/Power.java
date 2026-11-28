package dev.smppowers;

import java.util.Locale;

/** The 21 powers (19 normal + the secret Gojo and Sukuna). Numbers live in PowerSettings / config.json, this is only names and flags. */
public enum Power {
    TIDE("Tide", 0x3FA7FF, true, "Dash", "Tidal Surge"),
    EMBER("Ember", 0xFF7A1A, false, "Fire Burst", "Fire Storm"),
    GALE("Gale", 0xBFEFFF, true, "Launch", "Skybound"),
    STONE("Stone", 0x9A9A9A, false, "Shockwave", "Stone Skin"),
    FROST("Frost", 0x9FE8FF, false, "Freeze", "Blizzard"),
    STORM("Storm", 0xFFE74A, false, "Strike", "Thunderstorm"),
    SHADE("Shade", 0x8B6FD0, false, "Vanish", "Shadow Cloak"),
    VITAL("Vital", 0xFF5A7A, false, "Life Siphon", "Surge"),
    VENOM("Venom", 0x5FD13A, false, "Venom Strike", "Toxic Cloud"),
    SMASH("Smash", 0xC98A3C, true, "Ground Slam", "Meteor"),
    HUNTER("Hunter", 0x2FA84F, false, "Mark", "Hunter's Eye"),
    DRAGON("Dragon", 0xA64DFF, true, "Dragon Breath", "Wings"),
    SAND("Sand", 0xE3C27A, false, "Sandstorm", "Burial"),
    REAPER("Reaper", 0xC0243C, false, "Reap Mark", "Execution"),
    ECHO("Echo", 0x2FD6C6, false, "Sonic Wave", "Resonance"),
    MIMIC("Mimic", 0xD86AD8, false, "Copycat", "Mirror"),
    GRAVITY("Gravity", 0x7058D8, false, "Pull", "Singularity"),
    RIFT("Rift", 0x4D7CFF, false, "Blink", "Linked Rift"),
    ILLUSION("Illusion", 0xB8A0FF, false, "Mirage", "Mass Illusion"),
    // Secret powers: never rolled or rerolled, only given with /power set or the admin GUI.
    GOJO("Gojo", 0x6FB7FF, false, "Lapse Blue", "Hollow Purple"),
    SUKUNA("Sukuna", 0xD01030, false, "Dismantle", "Divine Flame");

    public final String display;
    public final int color;
    /** Movement ultimates are blocked in the End (config) so nobody skips to the outer islands. */
    public final boolean movementUltimate;
    public final String abilityName;
    public final String ultimateName;

    Power(String display, int color, boolean movementUltimate, String abilityName, String ultimateName) {
        this.display = display;
        this.color = color;
        this.movementUltimate = movementUltimate;
        this.abilityName = abilityName;
        this.ultimateName = ultimateName;
    }

    /** Private-use character the resource pack draws as this power's icon. */
    public char icon() {
        return (char) (0xE000 + ordinal());
    }

    /** Secret powers have several abilities (/ability 1-4) and are never handed out randomly. */
    public boolean secret() {
        return this == GOJO || this == SUKUNA;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Power byId(String id) {
        if (id == null) return null;
        for (Power p : values()) {
            if (p.id().equalsIgnoreCase(id)) return p;
        }
        return null;
    }
}
