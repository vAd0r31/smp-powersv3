package dev.smppowers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** All the live state and the per-tick logic. Everything is static because there is one server. */
final class PowerEngine {
    private PowerEngine() {}

    static Config config = new Config();
    static final DataStore store = new DataStore();
    static MinecraftServer server;
    static final Random RANDOM = new Random();

    /** Running ultimate for a player. */
    static final class Active {
        final Power power;
        final int endTick;
        Active(Power power, int endTick) { this.power = power; this.endTick = endTick; }
    }

    static final Map<UUID, Active> ultimates = new HashMap<>();
    static final List<Zone> zones = new ArrayList<>();
    static final Map<UUID, Integer> venomArmed = new HashMap<>();   // until tick
    static final Map<UUID, Integer> siphon = new HashMap<>();       // until tick
    static final Map<UUID, Integer> noFall = new HashMap<>();       // until tick
    static final Map<UUID, int[]> slams = new HashMap<>();          // {startTick, isMeteor(0/1)}
    static final Map<UUID, Integer> tridentCooldown = new HashMap<>();
    static final Map<String, Long> killPairs = new HashMap<>();
    static final Map<UUID, Vec3> lastPos = new HashMap<>();
    static final Set<UUID> stillPlayers = new HashSet<>();
    private static boolean bonusHit = false;

    // ---------- setup ----------

    static void start(MinecraftServer s) {
        server = s;
        reload();
    }

    static void reload() {
        config = Config.load();
        store.load();
    }

    static void stop() {
        store.save();
        ultimates.clear();
        zones.clear();
        venomArmed.clear();
        siphon.clear();
        noFall.clear();
        slams.clear();
        lastPos.clear();
        stillPlayers.clear();
        server = null;
    }

    // ---------- helpers ----------

    static int now() {
        return server == null ? 0 : server.getTickCount();
    }

    static Power powerOf(ServerPlayer p) {
        PlayerData d = store.peek(p.getUUID());
        return d == null ? null : Power.byId(d.power);
    }

    static PlayerData data(ServerPlayer p) {
        return store.get(p.getUUID());
    }

    static boolean enabledFor(ServerPlayer p) {
        if (p.isSpectator()) return false;
        if (!config.allowInCreative && p.isCreative()) return false;
        return !config.disabledDimensions.contains(Compat.dimensionId(p.level()));
    }

    static Power randomPower(Power exclude) {
        int total = 0;
        for (Power p : Power.values()) {
            if (p == exclude) continue;
            total += Math.max(0, config.powerWeights.getOrDefault(p.id(), 1));
        }
        if (total <= 0) {
            Power[] all = Power.values();
            return all[RANDOM.nextInt(all.length)];
        }
        int roll = RANDOM.nextInt(total);
        for (Power p : Power.values()) {
            if (p == exclude) continue;
            roll -= Math.max(0, config.powerWeights.getOrDefault(p.id(), 1));
            if (roll < 0) return p;
        }
        return Power.TIDE;
    }

    static void assignPower(ServerPlayer p, Power power) {
        PlayerData d = data(p);
        d.power = power.id();
        d.charge = 0;
        d.abilityReadyAt = 0;
        ultimates.remove(p.getUUID());
        store.save();
    }

    static double cooldownSeconds(Power p, int level) {
        double base = config.settings(p).abilityCooldown;
        double f = Math.max(0.3, 1.0 - config.cooldownReductionPerLevel * (level - 1));
        return base * f;
    }

    static double durationScale(int level) {
        return 1.0 + config.durationBonusPerLevel * (level - 1);
    }

    static float damageFor(double base, int level) {
        double v = base * (1.0 + config.damageBonusPerLevel * (level - 1));
        return (float) Math.min(v, config.maxAbilityDamage);
    }

    static void tell(ServerPlayer p, String text, ChatFormatting color) {
        Compat.msg(p, Component.literal(text).withStyle(color));
    }

    static void giveOrDrop(ServerPlayer p, ItemStack stack) {
        if (!p.getInventory().add(stack) && !stack.isEmpty()) {
            dropAt(Compat.level(p), p.position(), stack);
        }
    }

    static void dropAt(ServerLevel level, Vec3 pos, ItemStack stack) {
        level.addFreshEntity(new ItemEntity(level, pos.x, pos.y + 0.5, pos.z, stack));
    }

    // ---------- join / leave ----------

    static void onJoin(ServerPlayer p) {
        PlayerData d = data(p);
        if (Power.byId(d.power) == null) {
            Power chosen = randomPower(null);
            assignPower(p, chosen);
            tell(p, "You got the " + chosen.display + " power! Use /ability. /power info shows everything.", ChatFormatting.GOLD);
        }
        d.level = Math.max(1, Math.min(config.maxLevel, d.level));
    }

    static void onLeave(ServerPlayer p) {
        UUID id = p.getUUID();
        ultimates.remove(id);
        venomArmed.remove(id);
        siphon.remove(id);
        noFall.remove(id);
        slams.remove(id);
        lastPos.remove(id);
        stillPlayers.remove(id);
        zones.removeIf(z -> z.owner.equals(id));
        store.save();
    }

    // ---------- tick ----------

    static void tick(MinecraftServer s) {
        int t = s.getTickCount();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (!p.isAlive()) continue;
            Power power = powerOf(p);
            if (power == null || !enabledFor(p)) continue;
            UUID id = p.getUUID();

            // Stillness tracking (Stone)
            Vec3 prev = lastPos.put(id, p.position());
            if (prev != null && prev.distanceToSqr(p.position()) < 0.0004) stillPlayers.add(id);
            else stillPlayers.remove(id);

            if (t % 10 == 0) passives(p, power);

            Active act = ultimates.get(id);
            if (act != null) {
                if (t >= act.endTick) {
                    ultimates.remove(id);
                    tell(p, power.ultimateName + " ended.", ChatFormatting.GRAY);
                } else {
                    ultimateTick(p, act, t);
                }
            }

            int[] slam = slams.get(id);
            if (slam != null) Abilities.slamTick(p, power, slam, t);

            if (config.actionBarHud && t % 10 == 0) hud(p, power, t);
        }

        if (t % 10 == 0) zonePulse(t);

        // forget expired timers
        venomArmed.values().removeIf(v -> v < t);
        siphon.values().removeIf(v -> v < t);
        noFall.values().removeIf(v -> v < t);
        if (t % 600 == 0) {
            long cutoff = System.currentTimeMillis() - config.antiFarmMinutes * 60_000L;
            killPairs.values().removeIf(v -> v < cutoff);
        }
        if (t % 6000 == 0) store.save();
    }

    private static void passives(ServerPlayer p, Power power) {
        int d = 60; // refreshed every 10 ticks so it never flickers
        switch (power) {
            case TIDE -> {
                Compat.effect(p, MobEffects.WATER_BREATHING, d, 0);
                Compat.effect(p, MobEffects.DOLPHINS_GRACE, d, 0);
            }
            case EMBER -> Compat.effect(p, MobEffects.FIRE_RESISTANCE, d, 0);
            case GALE -> Compat.effect(p, MobEffects.JUMP_BOOST, d, 1);
            case STONE -> {
                if (stillPlayers.contains(p.getUUID())) Compat.effect(p, MobEffects.RESISTANCE, d, 0);
            }
            case FROST -> Compat.freeze(p, 0);
            case SHADE -> Compat.effect(p, MobEffects.NIGHT_VISION, 400, 0);
            case VITAL -> Compat.effect(p, MobEffects.REGENERATION, d, 0);
            case VENOM -> p.removeEffect(MobEffects.POISON);
            case HUNTER -> {
                if (p.getMainHandItem().is(Items.BOW) || p.getMainHandItem().is(Items.CROSSBOW)) {
                    Compat.effect(p, MobEffects.SPEED, d, 0);
                }
            }
            case DRAGON -> {
                if (p.isShiftKeyDown()) Compat.effect(p, MobEffects.SLOW_FALLING, d, 0);
            }
            default -> { }
        }
    }

    private static void ultimateTick(ServerPlayer p, Active act, int t) {
        PowerSettings s = config.settings(act.power);
        int level = data(p).level;
        switch (act.power) {
            case GALE -> {
                Compat.effect(p, MobEffects.SLOW_FALLING, 40, 0);
                Compat.effect(p, MobEffects.JUMP_BOOST, 40, 3);
                if (p.isSprinting() && !p.onGround()) {
                    Vec3 look = p.getLookAngle();
                    Compat.push(p, look.scale(Math.min(1.8, s.ultimateStrength)));
                }
            }
            case STONE -> Compat.effect(p, MobEffects.RESISTANCE, 40, 2);
            case SHADE -> {
                Compat.effect(p, MobEffects.INVISIBILITY, 40, 0);
                Compat.effect(p, MobEffects.SPEED, 40, 1);
            }
            case VITAL -> {
                Compat.effect(p, MobEffects.REGENERATION, 40, 2);
                Compat.effect(p, MobEffects.ABSORPTION, 40, 1);
            }
            case DRAGON -> {
                Compat.effect(p, MobEffects.SLOW_FALLING, 40, 0);
                if (p.isSprinting() && !p.onGround()) {
                    Vec3 look = p.getLookAngle();
                    Compat.push(p, look.scale(Math.min(1.6, s.ultimateStrength + 0.4)));
                    Compat.particles(Compat.level(p), ParticleTypes.PORTAL, p.position(), 6, 0.4);
                }
            }
            case STORM -> {
                if (t % 20 == 0) Abilities.stormPulse(p, s, level);
            }
            default -> { }
        }
    }

    private static void hud(ServerPlayer p, Power power, int t) {
        PlayerData d = data(p);
        long ms = d.abilityReadyAt - System.currentTimeMillis();
        String ability = ms <= 0 ? "READY" : ((ms + 999) / 1000) + "s";
        Active act = ultimates.get(p.getUUID());
        String ult;
        if (act != null) ult = "ACTIVE " + Math.max(0, (act.endTick - t) / 20) + "s";
        else if (d.level < config.ultimateMinLevel) ult = "needs Lv" + config.ultimateMinLevel;
        else if (d.charge >= 100) ult = "READY";
        else ult = d.charge + "%";
        Compat.actionBar(p, Component.literal(power.display + " Lv" + d.level
                + "  |  Ability: " + ability + "  |  Ultimate: " + ult).withStyle(ChatFormatting.WHITE));
    }

    // ---------- zones ----------

    private static void zonePulse(int t) {
        Iterator<Zone> it = zones.iterator();
        while (it.hasNext()) {
            Zone z = it.next();
            if (t >= z.endTick) {
                it.remove();
                continue;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(z.owner);
            if (owner == null) {
                it.remove();
                continue;
            }
            if (z.follow) {
                z.pos = owner.position();
                z.level = Compat.level(owner);
            }
            var particle = switch (z.kind) {
                case FIRE -> ParticleTypes.FLAME;
                case TOXIC -> ParticleTypes.WITCH;
                case FROST -> ParticleTypes.SNOWFLAKE;
                case DRAGON -> ParticleTypes.PORTAL;
            };
            Compat.particles(z.level, particle, z.pos.add(0, 0.5, 0), 25, z.radius * 0.5);

            for (LivingEntity e : Abilities.targetsAround(owner, z.level, z.pos, z.radius)) {
                switch (z.kind) {
                    case FIRE -> {
                        Compat.ignite(e, 3);
                        if (z.damage > 0) Compat.hurt(e, owner, (float) Math.min(z.damage, config.maxAbilityDamage));
                    }
                    case TOXIC -> {
                        Compat.effect(e, MobEffects.POISON, 60, 1);
                        Compat.effect(e, MobEffects.WEAKNESS, 60, 0);
                    }
                    case FROST -> {
                        Compat.effect(e, MobEffects.SLOWNESS, 40, 2);
                        Compat.freeze(e, 140);
                        if (z.damage > 0) Compat.hurt(e, owner, (float) Math.min(z.damage, config.maxAbilityDamage));
                    }
                    case DRAGON -> {
                        if (z.damage > 0) Compat.hurt(e, owner, (float) Math.min(z.damage, config.maxAbilityDamage));
                    }
                }
            }
        }
    }

    // ---------- damage ----------

    /** Returns false to cancel the damage (immunities). */
    static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer p)) return true;
        Power power = powerOf(p);
        if (power == null || !enabledFor(p)) return true;

        if (noFall.containsKey(p.getUUID()) && source.is(DamageTypeTags.IS_FALL)) return false;
        switch (power) {
            case EMBER -> {
                if (source.is(DamageTypeTags.IS_FIRE)) return false;
            }
            case GALE -> {
                if (source.is(DamageTypeTags.IS_FALL)) return false;
            }
            case FROST -> {
                if (source.is(DamageTypes.FREEZE)) return false;
            }
            case STORM -> {
                if (source.is(DamageTypeTags.IS_LIGHTNING)) return false;
            }
            default -> { }
        }
        return true;
    }

    static void afterDamage(LivingEntity entity, DamageSource source, float baseDamage, float taken, boolean blocked) {
        if (taken <= 0) return;

        // Taking damage charges the ultimate
        if (entity instanceof ServerPlayer victim && powerOf(victim) != null) {
            addCharge(victim, config.damageTakenCharge);
        }

        if (!(source.getEntity() instanceof ServerPlayer attacker)) return;
        Power power = powerOf(attacker);
        if (power == null || !enabledFor(attacker) || attacker == entity) return;
        UUID id = attacker.getUUID();
        int t = now();
        int level = data(attacker).level;

        if (power == Power.VITAL && siphon.containsKey(id)) {
            float heal = (float) (taken * config.settings(Power.VITAL).abilityStrength);
            attacker.heal(Math.max(1f, heal));
        }
        if (power == Power.VENOM && venomArmed.containsKey(id)) {
            venomArmed.remove(id);
            int secs = (int) Math.round(config.settings(Power.VENOM).abilityDuration * durationScale(level));
            Compat.effect(entity, MobEffects.POISON, secs * 20, 1);
            Compat.effect(entity, MobEffects.WEAKNESS, secs * 20, 0);
        }
        // Smash: maces hit 25% harder. Guarded so the bonus hit doesn't trigger another bonus.
        if (power == Power.SMASH && !bonusHit && attacker.getMainHandItem().is(Items.MACE)) {
            bonusHit = true;
            try {
                float extra = Math.min(taken * 0.25f, (float) config.maxAbilityDamage);
                Compat.hurt(entity, attacker, extra);
            } finally {
                bonusHit = false;
            }
        }
    }

    // ---------- kills ----------

    static void addCharge(ServerPlayer p, int amount) {
        PlayerData d = data(p);
        d.charge = Math.max(0, Math.min(100, d.charge + amount));
    }

    static void afterDeath(LivingEntity dead, DamageSource source) {
        if (dead instanceof ServerPlayer victim) {
            ultimates.remove(victim.getUUID());
            slams.remove(victim.getUUID());
            PlayerData vd = data(victim);
            vd.charge = Math.max(0, config.chargeAfterDeath);
            if (source.getEntity() instanceof ServerPlayer killer && killer != victim) {
                playerKill(killer, victim);
            }
            store.save();
            return;
        }

        if (!(source.getEntity() instanceof ServerPlayer killer) || powerOf(killer) == null) return;

        boolean boss = dead instanceof EnderDragon || dead instanceof WitherBoss || dead instanceof Warden;
        if (boss && config.bossesDropEssence) {
            dropAt(Compat.level(dead), dead.position(), EssenceItems.essence(1));
            tell(killer, "The boss dropped an Imperium Essence!", ChatFormatting.LIGHT_PURPLE);
        } else if (dead instanceof Enemy) {
            addCharge(killer, config.mobKillCharge);
        }
    }

    private static void playerKill(ServerPlayer killer, ServerPlayer victim) {
        if (powerOf(killer) == null) return;
        PlayerData kd = data(killer);
        PlayerData vd = data(victim);

        // Anti-farm: same killer and victim only counts once per window
        String key = killer.getUUID() + ">" + victim.getUUID();
        long nowMs = System.currentTimeMillis();
        Long last = killPairs.get(key);
        if (last != null && nowMs - last < config.antiFarmMinutes * 60_000L) {
            tell(killer, "No level for that kill (same player again too soon).", ChatFormatting.GRAY);
            return;
        }
        killPairs.put(key, nowMs);

        addCharge(killer, config.killCharge);

        // Level 1 victims have nothing to lose, so nothing to steal
        if (vd.level <= 1) {
            tell(killer, victim.getName().getString() + " was level 1, no level gained.", ChatFormatting.GRAY);
            return;
        }
        vd.level--;
        tell(victim, "You lost a level. You are now level " + vd.level + ".", ChatFormatting.RED);

        if (kd.level < config.maxLevel) {
            kd.level++;
            tell(killer, "You stole a level! Level " + kd.level + ".", ChatFormatting.GREEN);
        } else {
            dropAt(Compat.level(victim), victim.position(), EssenceItems.essence(1));
            tell(killer, "You are max level, so it dropped as Imperium Essence.", ChatFormatting.LIGHT_PURPLE);
        }
    }
}
