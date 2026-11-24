package dev.smppowers;

import java.util.Locale;

/** The 12 powers. Numbers live in PowerSettings / config.json, this is only names and flags. */
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
    DRAGON("Dragon", 0xA64DFF, true, "Dragon Breath", "Wings");

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
