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
