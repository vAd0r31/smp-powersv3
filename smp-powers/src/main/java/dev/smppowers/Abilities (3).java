package dev.smppowers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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

/** The 19 abilities and 19 ultimates. Numbers come from config.json. */
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

    /** /domain: the domain expansion, same command for Gojo and Sukuna. */
    static boolean castDomain(ServerPlayer p) {
        Power power = PowerEngine.powerOf(p);
        if (power == null || !Jujutsu.is(power)) {
            PowerEngine.tell(p, "Only Gojo and Sukuna have a domain expansion.", ChatFormatting.GRAY);
            return false;
        }
        if (!PowerEngine.enabledFor(p)) {
            PowerEngine.tell(p, "Powers are off for you right now.", ChatFormatting.RED);
            return false;
        }
        return Jujutsu.castSlot(p, power, 4);
    }

    /** /ability <slot>. Slot 1 is the normal ability; Gojo and Sukuna also have slots 2 to 4. */
    static boolean castSlot(ServerPlayer p, int slot) {
        if (slot <= 1) return cast(p);
        Power power = PowerEngine.powerOf(p);
        if (power == null) {
            PowerEngine.tell(p, "You have no power yet.", ChatFormatting.RED);
            return false;
        }
        if (!PowerEngine.enabledFor(p)) {
            PowerEngine.tell(p, "Powers are off for you right now.", ChatFormatting.RED);
            return false;
        }
        if (!Jujutsu.is(power)) {
            PowerEngine.tell(p, "Your power has one ability. Use /ability.", ChatFormatting.GRAY);
            return false;
        }
        return Jujutsu.castSlot(p, power, slot);
    }

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

        // Mimic casts whatever ability was used near it recently, with that power's numbers.
        Power eff = power;
        if (power == Power.MIMIC) {
            eff = mimicSource(p);
            if (eff == null) {
                PowerEngine.tell(p, "Nobody nearby used an ability in the last few seconds.", ChatFormatting.GRAY);
                return false;
            }
        }

        PowerSettings s = PowerEngine.config.settings(eff);
        int lvl = d.level;
        ServerLevel level = Compat.level(p);
        Vec3 look = p.getLookAngle();
        int t = PowerEngine.now();
        UUID id = p.getUUID();

        switch (eff) {
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
                Fx.arc(p, target);
            }
            case SHADE -> {
                int ticks = secs(s.abilityDuration, lvl);
                Compat.effect(p, MobEffects.INVISIBILITY, ticks, 0);
                Compat.particles(level, ParticleTypes.LARGE_SMOKE, p.position().add(0, 1, 0), 30, 0.5);
            }
            case VITAL -> PowerEngine.siphon.put(id, t + secs(s.abilityDuration, lvl));
            case VENOM -> PowerEngine.venomArmed.put(id, t + 20 * 20);
            case SMASH -> {
                if (blockedInEnd(p, eff)) {
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
                Fx.mark(p, target);
            }
            case DRAGON -> {
                Vec3 pos = p.position().add(look.x * 3, 0, look.z * 3);
                PowerEngine.zones.add(new Zone(id, Zone.Kind.DRAGON, level, pos, s.abilityRadius,
                        t + secs(s.abilityDuration, lvl), false, PowerEngine.damageFor(s.abilityDamage, lvl)));
            }
            case SAND -> {
                int ticks = secs(s.abilityDuration, lvl);
                for (LivingEntity e : targetsAround(p, level, p.position(), s.abilityRadius)) {
                    Compat.effectNamed(e, ticks, 0, "BLINDNESS");
                    Compat.effect(e, MobEffects.SLOWNESS, ticks, 0);
                }
            }
            case REAPER -> {
                LivingEntity target = lookTarget(p, s.abilityRadius);
                if (target == null) {
                    PowerEngine.tell(p, "No target in your sights.", ChatFormatting.GRAY);
                    return false;
                }
                int ticks = secs(s.abilityDuration, lvl);
                PowerEngine.reaperMarks.put(target.getUUID(), new PowerEngine.Mark(id, t + ticks));
                Compat.effect(target, MobEffects.GLOWING, ticks, 0);
                Fx.mark(p, target);
            }
            case ECHO -> {
                int ticks = secs(s.abilityDuration, lvl);
                for (LivingEntity e : targetsAround(p, level, p.position(), s.abilityRadius)) {
                    Compat.hurt(e, p, PowerEngine.damageFor(s.abilityDamage, lvl));
                    Compat.effect(e, MobEffects.SLOWNESS, 30, 2);
                    Compat.effect(e, MobEffects.GLOWING, ticks, 0);
                }
            }
            case GRAVITY -> {
                for (LivingEntity e : targetsAround(p, level, p.position(), s.abilityRadius)) {
                    Vec3 to = p.position().add(0, 1, 0).subtract(e.position());
                    Vec3 dir = to.lengthSqr() < 0.01 ? Vec3.ZERO : to.normalize();
                    Compat.push(e, new Vec3(dir.x * s.abilityStrength, 0.35 + Math.max(0, dir.y) * 0.2, dir.z * s.abilityStrength));
                }
            }
            case RIFT -> {
                if (!blink(p, level, s.abilityRadius)) {
                    PowerEngine.tell(p, "No room to blink there.", ChatFormatting.GRAY);
                    return false;
                }
            }
            case ILLUSION -> {
                int ticks = secs(s.abilityDuration, lvl);
                Fx.decoys(p);
                Compat.effect(p, MobEffects.INVISIBILITY, ticks, 0);
                Compat.effect(p, MobEffects.SPEED, ticks, 1);
            }
            case GOJO, SUKUNA -> {
                if (!Jujutsu.slot1(p, eff, s, lvl)) return false;
            }
            default -> { }
        }

        if (power != Power.MIMIC) {
            lastCastPower = power;
            lastCastTick = t;
            lastCastPos = p.position();
            lastCastOwner = id;
        }
        Fx.ability(p, eff);
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
        // Mimic's ultimate is the nearest player's ultimate.
        Power eff = power;
        if (power == Power.MIMIC) {
            eff = mimicUltimateSource(p);
            if (eff == null) {
                PowerEngine.tell(p, "Nobody close enough to copy.", ChatFormatting.GRAY);
                return false;
            }
        }
        if (blockedInEnd(p, eff)) {
            PowerEngine.tell(p, eff.ultimateName + " is disabled in the End.", ChatFormatting.RED);
            return false;
        }

        PowerSettings s = cfg.settings(eff);
        int lvl = d.level;
        int t = PowerEngine.now();
        int duration = secs(s.ultimateDuration, lvl);
        ServerLevel level = Compat.level(p);
        UUID id = p.getUUID();

        switch (eff) {
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
            case STONE, SHADE, VITAL, DRAGON, STORM, REAPER, ECHO -> { /* handled every tick while active */ }
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
            case SAND -> {
                LivingEntity target = lookTarget(p, s.ultimateRadius);
                if (target == null) {
                    PowerEngine.tell(p, "No target in your sights.", ChatFormatting.GRAY);
                    return false;
                }
                Compat.effect(target, MobEffects.SLOWNESS, duration, 6);
                Compat.effectNamed(target, duration, 0, "BLINDNESS");
                Fx.bury(p, target, duration);
            }
            case GRAVITY -> {
                Vec3 aim = p.getLookAngle();
                Vec3 center = p.position().add(aim.x * 6, 0, aim.z * 6);
                PowerEngine.zones.add(new Zone(id, Zone.Kind.GRAVITY, level, center, s.ultimateRadius,
                        t + duration, false, PowerEngine.damageFor(s.ultimateDamage, lvl)));
            }
            case RIFT -> {
                LivingEntity target = lookTarget(p, s.ultimateRadius);
                if (target == null) {
                    PowerEngine.tell(p, "No target in your sights.", ChatFormatting.GRAY);
                    return false;
                }
                PowerEngine.riftLinks.put(id, target.getUUID());
                riftSwap(p);
            }
            case ILLUSION -> {
                for (LivingEntity e : targetsAround(p, level, p.position(), s.ultimateRadius)) {
                    Compat.effectNamed(e, duration, 0, "NAUSEA", "CONFUSION");
                    Compat.effect(e, MobEffects.SLOWNESS, duration, 0);
                }
                Compat.effect(p, MobEffects.SPEED, duration, 0);
            }
            case GOJO, SUKUNA -> Jujutsu.startUltimate(p, eff, s, lvl);
            default -> { }
        }

        Fx.ultimate(p, eff);
        PowerEngine.ultimates.put(id, new PowerEngine.Active(eff, t + duration));
        d.charge = 0;
        Compat.particles(level, ParticleTypes.END_ROD, p.position().add(0, 1, 0), 60, 0.8);
        PowerEngine.tell(p, (power == eff ? power.ultimateName : "Mirror: " + eff.ultimateName) + "!", ChatFormatting.GOLD);
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
        Fx.ability(p, Power.TIDE);
        return true;
    }

    // ---------- Smash: slam and meteor ----------

    private static void startSlam(ServerPlayer p, double upSpeed, boolean meteor) {
        Compat.push(p, new Vec3(0, upSpeed, 0));
        PowerEngine.slams.put(p.getUUID(), new int[] { PowerEngine.now(), meteor ? 1 : 0, 0 });
        PowerEngine.noFall.put(p.getUUID(), PowerEngine.now() + 400);
        Fx.slamStart(p, meteor);
    }

    /** Called every tick for a player in a slam. slam = {startTick, isMeteor, phase}. */
    static void slamTick(ServerPlayer p, Power power, int[] slam, int t) {
        int age = t - slam[0];
        if (slam[2] == 0) {
            Fx.slamRise(p, slam[1] == 1);
            // rising: after a short time (or once we start falling) smash down
            if (age >= 8 || p.getDeltaMovement().y < 0) {
                slam[2] = 1;
                Compat.push(p, new Vec3(0, -(slam[1] == 1 ? 2.4 : 1.6), 0));
            }
        } else {
            Compat.push(p, new Vec3(0, Math.min(p.getDeltaMovement().y, -1.2), 0));
            Fx.slamFall(p, slam[1] == 1, age);
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
                Fx.slamImpact(p, meteor, radius);
                PowerEngine.slams.remove(p.getUUID());
                PowerEngine.noFall.put(p.getUUID(), t + 40);
            }
        }
    }

    // ---------- Storm ----------

    static void strike(ServerPlayer owner, LivingEntity target, float damage) {
        ServerLevel level = Compat.level(target);
        // Hit first, then spawn the bolt. The bolt's own damage used to land first and start the
        // invulnerability window, which made our hit count for almost nothing.
        target.invulnerableTime = 0;
        Compat.hurt(target, owner, damage);
        net.minecraft.world.entity.Entity bolt = null;
        try {
            for (java.lang.reflect.Field f : EntityType.class.getFields()) {
                if (f.getName().contains("LIGHTNING")) {
                    EntityType<?> boltType = (EntityType<?>) f.get(null);
                    bolt = boltType.create(level, EntitySpawnReason.TRIGGERED);
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        if (bolt != null) {
            bolt.setPos(target.getX(), target.getY(), target.getZ());
            level.addFreshEntity(bolt);
        }
    }

    // ---------- Mimic ----------

    static Power lastCastPower;
    static int lastCastTick;
    static Vec3 lastCastPos;
    static UUID lastCastOwner;

    /** Mimic can't copy abilities that only work through the caster's own hits. */
    private static boolean copyable(Power x) {
        return !x.secret() && x != Power.MIMIC && x != Power.VENOM && x != Power.VITAL && x != Power.REAPER;
    }

    static Power mimicSource(ServerPlayer p) {
        if (lastCastPower == null || lastCastPos == null || p.getUUID().equals(lastCastOwner) || !copyable(lastCastPower)) return null;
        if (PowerEngine.now() - lastCastTick > 300) return null;
        if (lastCastPos.distanceTo(p.position()) > PowerEngine.config.settings(Power.MIMIC).abilityRadius) return null;
        return lastCastPower;
    }

    static Power mimicUltimateSource(ServerPlayer p) {
        double range = PowerEngine.config.settings(Power.MIMIC).ultimateRadius;
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer o : PowerEngine.server.getPlayerList().getPlayers()) {
            if (o == p || !o.isAlive() || o.level() != p.level()) continue;
            Power op = PowerEngine.powerOf(o);
            if (op == null || op == Power.MIMIC) continue;
            double dist = o.position().distanceTo(p.position());
            if (dist <= range && dist < bestDist) {
                bestDist = dist;
                best = o;
            }
        }
        return best == null ? null : PowerEngine.powerOf(best);
    }

    // ---------- Rift ----------

    private static boolean clear(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
    }

    private static String at(Vec3 v) {
        return Fx.f(v.x) + " " + Fx.f(v.y) + " " + Fx.f(v.z);
    }

    /** Teleports up to dist blocks along the look direction, stopping before a wall. */
    static boolean blink(ServerPlayer p, ServerLevel level, double dist) {
        Vec3 look = p.getLookAngle();
        Vec3 best = null;
        for (double d = 1.0; d <= dist; d += 0.5) {
            Vec3 feet = p.position().add(look.scale(d));
            if (!clear(level, BlockPos.containing(feet)) || !clear(level, BlockPos.containing(feet.add(0, 1, 0)))) break;
            best = feet;
        }
        if (best == null) return false;
        Fx.blinkFx(level, p.position(), best);
        Fx.cmd("execute in " + Compat.dimensionId(level) + " run tp " + p.getUUID() + " " + at(best));
        PowerEngine.noFall.put(p.getUUID(), PowerEngine.now() + 60);
        return true;
    }

    /** Linked Rift: swap places with the linked target. */
    static void riftSwap(ServerPlayer p) {
        UUID linked = PowerEngine.riftLinks.get(p.getUUID());
        if (linked == null) return;
        ServerLevel level = Compat.level(p);
        net.minecraft.world.entity.Entity found = level.getEntity(linked);
        if (!(found instanceof LivingEntity other) || !other.isAlive()) {
            PowerEngine.riftLinks.remove(p.getUUID());
            return;
        }
        Vec3 a = p.position();
        Vec3 b = other.position();
        Fx.riftSwapFx(level, a, b);
        String dim = Compat.dimensionId(level);
        Fx.cmd("execute in " + dim + " run tp " + p.getUUID() + " " + at(b));
        Fx.cmd("execute in " + dim + " run tp " + other.getUUID() + " " + at(a));
        PowerEngine.noFall.put(p.getUUID(), PowerEngine.now() + 60);
    }

    // ---------- Reaper and Echo ultimates ----------

    static void reaperSweep(ServerPlayer p, PowerSettings s) {
        for (LivingEntity e : targetsAround(p, Compat.level(p), p.position(), s.ultimateRadius)) {
            if (e.getHealth() <= e.getMaxHealth() * s.ultimateStrength) {
                Fx.execute(e);
                Compat.hurt(e, p, e.getHealth() + 1000f);
            }
        }
    }

    static void echoPulse(ServerPlayer p, PowerSettings s, int lvl) {
        for (LivingEntity e : targetsAround(p, Compat.level(p), p.position(), s.ultimateRadius)) {
            Compat.hurt(e, p, PowerEngine.damageFor(s.ultimateDamage, lvl));
            Compat.effect(e, MobEffects.GLOWING, 40, 0);
            Compat.effect(e, MobEffects.SLOWNESS, 30, 0);
        }
        Fx.echoPulse(p, s.ultimateRadius);
    }

    static void stormPulse(ServerPlayer p, PowerSettings s, int lvl) {
        List<LivingEntity> targets = targetsAround(p, Compat.level(p), p.position(), s.ultimateRadius);
        if (targets.isEmpty()) return;
        LivingEntity pick = targets.get(PowerEngine.RANDOM.nextInt(targets.size()));
        strike(p, pick, PowerEngine.damageFor(s.ultimateDamage, lvl));
    }
}
