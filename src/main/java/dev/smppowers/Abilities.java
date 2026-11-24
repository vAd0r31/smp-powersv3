package dev.smppowers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The 12 abilities and 12 ultimates. Numbers come from config.json. */
final class Abilities {
    private Abilities() {}

    // ---------- shared helpers ----------

    /** Who an ability may hit: never the caster, players only if enabled, mobs only hostile ones if enabled. */
    static List<LivingEntity> targetsAround(ServerPlayer owner, ServerLevel level, Vec3 center, double radius) {
        AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == owner || !e.isAlive()) continue;
            if (e.position().distanceTo(center) > radius) continue;
            if (e instanceof ServerPlayer sp) {
                if (!PowerEngine.config.affectOtherPlayers || sp.isSpectator() || sp.isCreative()) continue;
            } else if (e instanceof Enemy) {
                if (!PowerEngine.config.affectHostileMobs) continue;
            } else if (e instanceof Animal) {
                continue; // never hurt farm animals by accident
            }
            out.add(e);
        }
        return out;
    }

    /** The living thing the player is looking at (roughly), or null. */
    static LivingEntity lookTarget(ServerPlayer p, double range) {
        ServerLevel level = Compat.level(p);
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : targetsAround(p, level, eye, range)) {
            Vec3 to = e.getEyePosition().subtract(eye);
            double dist = to.length();
            if (dist < 0.5) continue;
            double dot = to.normalize().dot(look);
            if (dot > 0.96 && dist < bestDist) {
                best = e;
                bestDist = dist;
            }
        }
        return best;
    }

    private static boolean blockedInEnd(ServerPlayer p, Power power) {
        return PowerEngine.config.disableMovementUltimatesInEnd && power.movementUltimate && Compat.isEnd(p.level());
    }

    private static int secs(double seconds, int level) {
        return (int) Math.round(seconds * PowerEngine.durationScale(level) * 20);
    }

    // ---------- casting entry points ----------

    /** Returns true if the ability was used. */
    static boolean cast(ServerPlayer p) {
        Power power = PowerEngine.powerOf(p);
        if (power == null) {
            PowerEngine.tell(p, "You have no power yet.", ChatFormatting.RED);
            return false;
        }
        if (!PowerEngine.enabledFor(p)) {
            PowerEngine.tell(p, "Powers are off for you right now.", ChatFormatting.RED);
            return false;
        }
        PlayerData d = PowerEngine.data(p);
        long ms = d.abilityReadyAt - System.currentTimeMillis();
        if (ms > 0) {
            PowerEngine.tell(p, power.abilityName + " ready in " + ((ms + 999) / 1000) + "s", ChatFormatting.GRAY);
            return false;
        }

        PowerSettings s = PowerEngine.config.settings(power);
        int lvl = d.level;
        ServerLevel level = Compat.level(p);
        Vec3 look = p.getLookAngle();
        int t = PowerEngine.now();
        UUID id = p.getUUID();

        switch (power) {
            case TIDE -> {
                Compat.push(p, look.scale(s.abilityStrength));
                PowerEngine.noFall.put(id, t + 60);
                Compat.particles(level, ParticleTypes.BUBBLE, p.position().add(0, 1, 0), 30, 0.6);
            }
            case EMBER -> {
                for (LivingEntity e : targetsAround(p, level, p.position(), s.abilityRadius)) {
                    Compat.hurt(e, p, PowerEngine.damageFor(s.abilityDamage, lvl));
                    Compat.ignite(e, (int) Math.max(1, s.abilityDuration * PowerEngine.durationScale(lvl)));
                }
                Compat.particles(level, ParticleTypes.FLAME, p.position().add(0, 1, 0), 80, s.abilityRadius * 0.4);
            }
            case GALE -> {
                Compat.push(p, new Vec3(look.x * 0.3, s.abilityStrength, look.z * 0.3));
                PowerEngine.noFall.put(id, t + 200);
                Compat.particles(level, ParticleTypes.CLOUD, p.position(), 30, 0.5);
            }
            case STONE -> {
                for (LivingEntity e : targetsAround(p, level, p.position(), s.abilityRadius)) {
                    Compat.hurt(e, p, PowerEngine.damageFor(s.abilityDamage, lvl));
                    Vec3 away = e.position().subtract(p.position()).multiply(1, 0, 1).normalize();
                    Compat.push(e, new Vec3(away.x * s.abilityStrength, 0.5, away.z * s.abilityStrength));
                }
                Compat.particles(level, ParticleTypes.EXPLOSION, p.position(), 8, s.abilityRadius * 0.4);
            }
            case FROST -> {
                for (LivingEntity e : targetsAround(p, level, p.position(), s.abilityRadius)) {
                    int ticks = secs(s.abilityDuration, lvl);
                    Compat.effect(e, MobEffects.SLOWNESS, ticks, 1);
                    Compat.freeze(e, ticks + 100);
                }
                Compat.particles(level, ParticleTypes.SNOWFLAKE, p.position().add(0, 1, 0), 80, s.abilityRadius * 0.4);
            }
            case STORM -> {
                LivingEntity target = lookTarget(p, s.abilityRadius);
                if (target == null) {
                    PowerEngine.tell(p, "No target in your sights.", ChatFormatting.GRAY);
                    return false;
                }
                strike(p, target, PowerEngine.damageFor(s.abilityDamage, lvl));
            }
            case SHADE -> {
                int ticks = secs(s.abilityDuration, lvl);
                Compat.effect(p, MobEffects.INVISIBILITY, ticks, 0);
                Compat.particles(level, ParticleTypes.LARGE_SMOKE, p.position().add(0, 1, 0), 30, 0.5);
            }
            case VITAL -> PowerEngine.siphon.put(id, t + secs(s.abilityDuration, lvl));
            case VENOM -> PowerEngine.venomArmed.put(id, t + 20 * 20);
            case SMASH -> {
                if (blockedInEnd(p, power)) {
                    PowerEngine.tell(p, "Ground Slam is off in the End.", ChatFormatting.RED);
                    return false;
                }
                startSlam(p, s.abilityStrength + 0.8, false);
            }
            case HUNTER -> {
                LivingEntity target = lookTarget(p, s.abilityRadius);
                if (target == null) {
                    PowerEngine.tell(p, "No target in your sights.", ChatFormatting.GRAY);
                    return false;
                }
                Compat.effect(target, MobEffects.GLOWING, secs(s.abilityDuration, lvl), 0);
            }
            case DRAGON -> {
                Vec3 pos = p.position().add(look.x * 3, 0, look.z * 3);
                PowerEngine.zones.add(new Zone(id, Zone.Kind.DRAGON, level, pos, s.abilityRadius,
                        t + secs(s.abilityDuration, lvl), false, PowerEngine.damageFor(s.abilityDamage, lvl)));
            }
        }

        d.abilityReadyAt = System.currentTimeMillis() + (long) (PowerEngine.cooldownSeconds(power, lvl) * 1000);
        return true;
    }

    static boolean ultimate(ServerPlayer p) {
        Power power = PowerEngine.powerOf(p);
        if (power == null) {
            PowerEngine.tell(p, "You have no power yet.", ChatFormatting.RED);
            return false;
        }
        if (!PowerEngine.enabledFor(p)) {
            PowerEngine.tell(p, "Powers are off for you right now.", ChatFormatting.RED);
            return false;
        }
        PlayerData d = PowerEngine.data(p);
        Config cfg = PowerEngine.config;
        if (d.level < cfg.ultimateMinLevel) {
            PowerEngine.tell(p, "Ultimates unlock at level " + cfg.ultimateMinLevel + ".", ChatFormatting.RED);
            return false;
        }
        if (d.charge < 100) {
            PowerEngine.tell(p, "Ultimate charge: " + d.charge + "%", ChatFormatting.GRAY);
            return false;
        }
        if (PowerEngine.ultimates.containsKey(p.getUUID())) {
            PowerEngine.tell(p, "Your ultimate is already running.", ChatFormatting.GRAY);
            return false;
        }
        if (blockedInEnd(p, power)) {
            PowerEngine.tell(p, power.ultimateName + " is disabled in the End.", ChatFormatting.RED);
            return false;
        }

        PowerSettings s = cfg.settings(power);
        int lvl = d.level;
        int t = PowerEngine.now();
        int duration = secs(s.ultimateDuration, lvl);
        ServerLevel level = Compat.level(p);
        UUID id = p.getUUID();

        switch (power) {
            case TIDE -> {
                PowerEngine.tell(p, "Riptide surge! Right-click while holding a trident.", ChatFormatting.AQUA);
                PowerEngine.noFall.put(id, t + duration + 100);
            }
            case EMBER -> PowerEngine.zones.add(new Zone(id, Zone.Kind.FIRE, level, p.position(), s.ultimateRadius,
                    t + duration, true, PowerEngine.damageFor(s.ultimateDamage, lvl)));
            case GALE -> {
                Compat.push(p, new Vec3(0, s.ultimateStrength, 0));
                PowerEngine.noFall.put(id, t + duration + 100);
            }
            case STONE, SHADE, VITAL, DRAGON, STORM -> { /* handled every tick while active */ }
            case FROST -> PowerEngine.zones.add(new Zone(id, Zone.Kind.FROST, level, p.position(), s.ultimateRadius,
                    t + duration, true, PowerEngine.damageFor(s.ultimateDamage, lvl)));
            case VENOM -> PowerEngine.zones.add(new Zone(id, Zone.Kind.TOXIC, level, p.position(), s.ultimateRadius,
                    t + duration, true, 0));
            case SMASH -> {
                startSlam(p, s.ultimateStrength, true);
                duration = 100; // only so the HUD shows it as running
            }
            case HUNTER -> {
                for (LivingEntity e : targetsAround(p, level, p.position(), s.ultimateRadius)) {
                    Compat.effect(e, MobEffects.GLOWING, duration, 0);
                }
            }
        }

        PowerEngine.ultimates.put(id, new PowerEngine.Active(power, t + duration));
        d.charge = 0;
        Compat.particles(level, ParticleTypes.END_ROD, p.position().add(0, 1, 0), 60, 0.8);
        PowerEngine.tell(p, power.ultimateName + "!", ChatFormatting.GOLD);
        return true;
    }

    // ---------- Tide: trident right-click with an active ultimate ----------

    static boolean tideSurge(ServerPlayer p) {
        var act = PowerEngine.ultimates.get(p.getUUID());
        if (act == null || act.power != Power.TIDE) return false;
        int t = PowerEngine.now();
        Integer cd = PowerEngine.tridentCooldown.get(p.getUUID());
        if (cd != null && cd > t) return true; // swallow the click, still on cooldown
        PowerEngine.tridentCooldown.put(p.getUUID(), t + 15);
        double speed = PowerEngine.config.settings(Power.TIDE).ultimateStrength;
        Compat.push(p, p.getLookAngle().scale(speed));
        Compat.particles(Compat.level(p), ParticleTypes.BUBBLE, p.position().add(0, 1, 0), 30, 0.6);
        return true;
    }

    // ---------- Smash: slam and meteor ----------

    private static void startSlam(ServerPlayer p, double upSpeed, boolean meteor) {
        Compat.push(p, new Vec3(0, upSpeed, 0));
        PowerEngine.slams.put(p.getUUID(), new int[] { PowerEngine.now(), meteor ? 1 : 0, 0 });
        PowerEngine.noFall.put(p.getUUID(), PowerEngine.now() + 400);
    }

    /** Called every tick for a player in a slam. slam = {startTick, isMeteor, phase}. */
    static void slamTick(ServerPlayer p, Power power, int[] slam, int t) {
        int age = t - slam[0];
        if (slam[2] == 0) {
            // rising: after a short time (or once we start falling) smash down
            if (age >= 8 || p.getDeltaMovement().y < 0) {
                slam[2] = 1;
                Compat.push(p, new Vec3(0, -(slam[1] == 1 ? 2.4 : 1.6), 0));
            }
        } else {
            Compat.push(p, new Vec3(0, Math.min(p.getDeltaMovement().y, -1.2), 0));
            if (p.onGround() || age > 120) {
                PowerSettings s = PowerEngine.config.settings(Power.SMASH);
                boolean meteor = slam[1] == 1;
                double radius = meteor ? s.ultimateRadius : s.abilityRadius;
                double dmg = meteor ? s.ultimateDamage : s.abilityDamage;
                int lvl = PowerEngine.data(p).level;
                ServerLevel level = Compat.level(p);
                for (LivingEntity e : targetsAround(p, level, p.position(), radius)) {
                    Compat.hurt(e, p, PowerEngine.damageFor(dmg, lvl));
                    Vec3 away = e.position().subtract(p.position()).multiply(1, 0, 1).normalize();
                    Compat.push(e, new Vec3(away.x * 0.9, 0.6, away.z * 0.9));
                }
                Compat.particles(level, ParticleTypes.EXPLOSION, p.position(), meteor ? 20 : 8, radius * 0.4);
                PowerEngine.slams.remove(p.getUUID());
                PowerEngine.noFall.put(p.getUUID(), t + 40);
            }
        }
    }

    // ---------- Storm ----------

    static void strike(ServerPlayer owner, LivingEntity target, float damage) {
        ServerLevel level = Compat.level(target);
        EntityType<?> boltType = EntityType.byString("lightning_bolt").orElse(null);
        net.minecraft.world.entity.Entity bolt = boltType == null ? null : boltType.create(level, EntitySpawnReason.TRIGGERED);
        if (bolt != null) {
            bolt.setPos(target.getX(), target.getY(), target.getZ());
            level.addFreshEntity(bolt);
        }
        Compat.hurt(target, owner, damage);
    }

    static void stormPulse(ServerPlayer p, PowerSettings s, int lvl) {
        List<LivingEntity> targets = targetsAround(p, Compat.level(p), p.position(), s.ultimateRadius);
        if (targets.isEmpty()) return;
        LivingEntity pick = targets.get(PowerEngine.RANDOM.nextInt(targets.size()));
        strike(p, pick, PowerEngine.damageFor(s.ultimateDamage, lvl));
    }
}
