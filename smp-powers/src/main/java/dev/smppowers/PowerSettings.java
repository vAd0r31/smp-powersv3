package dev.smppowers;

/**
 * Per-power numbers, saved in config.json so they can be balanced without recompiling.
 * Not every field is used by every power (see the comments in defaultsFor).
 */
public class PowerSettings {
    public double abilityCooldown = 20;   // seconds
    public double abilityDuration = 0;    // seconds (effect / ignite / cloud length)
    public double abilityRadius = 0;      // blocks (or range for targeted abilities)
    public double abilityDamage = 0;
    public double abilityStrength = 0;    // launch / knockback speed, or heal fraction
    public double ultimateDuration = 10;  // seconds
    public double ultimateRadius = 0;
    public double ultimateDamage = 0;     // damage per pulse for storm-type ultimates
    public double ultimateStrength = 0;   // launch / boost speed
    public double ultimateWindup = 0;     // seconds of charging before the ultimate fires (Gojo / Sukuna)
    public double abilityExtra = 0;       // spare number for slot 1 (Gojo / Sukuna)

    // Extra ability slots, only used by Gojo and Sukuna (/ability 2, 3, 4).
    // Each slot: cooldown s, duration s, radius/range blocks, damage, strength, extra number.
    public double slot2Cooldown = 0, slot2Duration = 0, slot2Radius = 0, slot2Damage = 0, slot2Strength = 0, slot2Extra = 0;
    public double slot3Cooldown = 0, slot3Duration = 0, slot3Radius = 0, slot3Damage = 0, slot3Strength = 0, slot3Extra = 0;
    public double slot4Cooldown = 0, slot4Duration = 0, slot4Radius = 0, slot4Damage = 0, slot4Strength = 0, slot4Extra = 0;

    public static PowerSettings defaultsFor(Power p) {
        return switch (p) {
            // Dash speed 1.8. Ultimate: 20s riptide surge, launch speed 1.6.
            case TIDE -> of(20, 0, 0, 0, 1.8, 20, 0, 0, 1.6);
            // Burst: radius 5, 4 damage, ignite 4s. Ultimate: fire storm radius 5, 2 damage per half second.
            case EMBER -> of(30, 4, 5, 4, 0, 10, 5, 2, 0);
            // Launch upward speed 1.3. Ultimate: 15s of slow falling + jump boost, launch 1.7.
            case GALE -> of(15, 0, 0, 0, 1.3, 15, 0, 0, 1.7);
            // Shockwave radius 6, 3 damage, knockback 1.2. Ultimate: 10s of Resistance III.
            case STONE -> of(25, 0, 6, 3, 1.2, 10, 0, 0, 0);
            // Freeze: slowness II for 4s within 6. Ultimate: blizzard radius 8, 1 damage per half second.
            case FROST -> of(25, 4, 6, 0, 0, 10, 8, 1, 0);
            // Strike: range 20, 6 damage. Ultimate: 8s thunderstorm, radius 12, 5 damage per bolt.
            case STORM -> of(30, 0, 20, 6, 0, 8, 12, 5, 0);
            // Vanish 5s. Ultimate: 10s invisible + Speed II.
            case SHADE -> of(35, 5, 0, 0, 0, 10, 0, 0, 0);
            // Life siphon 5s, heal 30% of damage dealt. Ultimate: 10s Regeneration III + absorption.
            case VITAL -> of(30, 5, 0, 0, 0.3, 10, 0, 0, 0);
            // Next hit poisons + weakens for 5s. Ultimate: toxic cloud radius 6 for 10s.
            case VENOM -> of(20, 5, 0, 0, 0, 10, 6, 0, 0);
            // Slam radius 5, 4 damage. Ultimate: meteor, launch 2.2, radius 8, 10 damage.
            case SMASH -> of(20, 0, 5, 4, 1.0, 10, 8, 10, 2.2);
            // Mark: glow 10s, range 40. Ultimate: everyone within 50 glows 15s.
            case HUNTER -> of(25, 10, 40, 0, 0, 15, 50, 0, 0);
            // Breath: cloud radius 4 for 6s, 2 damage per half second. Ultimate: 15s wings, boost 0.8.
            case DRAGON -> of(30, 6, 4, 2, 0, 15, 0, 0, 0.8);
            // Sandstorm: blind + slow everyone within 6 for 4s. Ultimate: bury what you look at (range 20) for 5s.
            case SAND -> of(25, 4, 6, 0, 0, 5, 20, 0, 0);
            // Mark what you look at (range 25) for 10s, your hits do +40% (double under half health).
            // Ultimate: 8s, anyone within 12 under 20% health is executed.
            case REAPER -> of(25, 10, 25, 0, 0.4, 8, 12, 0, 0.2);
            // Sonic wave: radius 12, 3 damage, slow, glow for 5s. Ultimate: 8s of pulses, radius 10, 2 damage each second.
            case ECHO -> of(25, 5, 12, 3, 0, 8, 10, 2, 0);
            // Copy the last ability used within 40 blocks (15s). Ultimate: copy the nearest player's ultimate (within 30).
            case MIMIC -> of(20, 0, 40, 0, 0, 15, 30, 0, 0);
            // Pull everyone within 10 toward you (strength 1.2). Ultimate: 10s gravity well, radius 10, 1 damage per half second.
            case GRAVITY -> of(25, 0, 10, 0, 1.2, 10, 10, 1, 0.5);
            // Blink: teleport up to 8 blocks where you look. Ultimate: 12s link to what you look at (range 30), swap places every 3s.
            case RIFT -> of(15, 0, 8, 0, 0, 12, 30, 0, 0);
            // Mirage: 4s invisible + Speed II, decoys left behind. Ultimate: 8s, everyone within 15 gets nausea.
            case ILLUSION -> of(25, 4, 0, 0, 0, 8, 15, 0, 0);
            // GOJO. Slot 1 Lapse Blue: orb lives 3s, pulls everything within 12, 3 damage per pulse (every 0.25s), pull 1.1.
            //   Ultimate Hollow Purple: 2.5s charge, beam range 90, beam radius 3, 70 damage.
            //   Slot 2 Reversal Red: range 35, 18 damage, knockback 2.4, blast radius 6 (extra).
            //   Slot 3 Infinity: toggle, max 10s, barrier radius 4, push strength 0.7.
            //   Slot 4 Domain (Unlimited Void): 12s, radius 15, 2 damage per 0.5s, everything inside is crippled.
            case GOJO -> {
                PowerSettings g = of(8, 3, 12, 3, 1.1, 5, 90, 70, 3);
                g.ultimateWindup = 2.5;
                g.slot2Cooldown = 12; g.slot2Radius = 35; g.slot2Damage = 18; g.slot2Strength = 2.4; g.slot2Extra = 6;
                g.slot3Cooldown = 10; g.slot3Duration = 10; g.slot3Radius = 4; g.slot3Strength = 0.7;
                g.slot4Cooldown = 90; g.slot4Duration = 12; g.slot4Radius = 15; g.slot4Damage = 2;
                yield g;
            }
            // SUKUNA. Slot 1 Dismantle: 3 slashes, range 14, 5 damage each, knockback 0.4.
            //   Slot 2 Cleave: range 22, at least 10 damage or 40% of the target's max health (strength), capped at 45 (extra).
            //   Slot 3 Domain (Malevolent Shrine): 12s, radius 16, 3 damage per 0.5s, bleeding.
            //   Ultimate Divine Flame: 2s charge, range 80, blast radius 14, 60 damage, burns 15s (ability extra).
            case SUKUNA -> {
                PowerSettings k = of(6, 0, 14, 5, 0.4, 5, 14, 60, 80);
                k.ultimateWindup = 2.0;
                k.abilityExtra = 15; // seconds the Divine Flame burns people
                k.slot2Cooldown = 14; k.slot2Radius = 22; k.slot2Damage = 10; k.slot2Strength = 0.4; k.slot2Extra = 45;
                k.slot3Cooldown = 90; k.slot3Duration = 12; k.slot3Radius = 16; k.slot3Damage = 3;
                yield k;
            }
        };
    }

    private static PowerSettings of(double ac, double ad, double ar, double adm, double ast,
                                    double ud, double ur, double udm, double ust) {
        PowerSettings s = new PowerSettings();
        s.abilityCooldown = ac;
        s.abilityDuration = ad;
        s.abilityRadius = ar;
        s.abilityDamage = adm;
        s.abilityStrength = ast;
        s.ultimateDuration = ud;
        s.ultimateRadius = ur;
        s.ultimateDamage = udm;
        s.ultimateStrength = ust;
        return s;
    }
}
