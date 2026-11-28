package dev.smppowers;

import com.mojang.datafixers.util.Pair;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The two secret powers: Gojo and Sukuna.
 * Several abilities each (/ability 1 to 4), domain expansions with a cutscene, big ultimates,
 * infinite lunge (hunger is kept full) and invisible armor and held items for everyone else.
 * All the numbers are in config.json under "gojo" and "sukuna".
 */
final class Jujutsu {
    private Jujutsu() {}

    /** True while a Jujutsu attack is damaging someone, so Infinity does not block it (it is a sure hit). */
    static boolean sureHit = false;

    static final Map<UUID, Integer> infinityEnd = new HashMap<>();   // Gojo's Infinity: owner -> tick it ends
    static final List<Orb> orbs = new ArrayList<>();                 // Lapse Blue orbs
    static final List<Domain> domains = new ArrayList<>();
    static final List<Channel> channels = new ArrayList<>();         // Hollow Purple / Divine Flame

    static final class Orb {
        final UUID owner; final ServerLevel level; final Vec3 pos; final int start, end, lvl;
        Orb(UUID owner, ServerLevel level, Vec3 pos, int start, int end, int lvl) {
            this.owner = owner; this.level = level; this.pos = pos; this.start = start; this.end = end; this.lvl = lvl;
        }
    }

    static final class Domain {
        final UUID owner; final Power power; final ServerLevel level; final Vec3 center;
        final double radius, damage; final int start, end, lvl;
        Domain(UUID owner, Power power, ServerLevel level, Vec3 center, double radius, double damage, int start, int end, int lvl) {
            this.owner = owner; this.power = power; this.level = level; this.center = center;
            this.radius = radius; this.damage = damage; this.start = start; this.end = end; this.lvl = lvl;
        }
    }

    static final class Channel {
        final UUID owner; final Power power; final int start, windup, lvl;
        boolean fired = false;
        Vec3 origin, dir;
        int fireTick, travel;
        double range;
        Vec3 impact;
        final Set<UUID> hit = new HashSet<>();
        Channel(UUID owner, Power power, int start, int windup, int lvl) {
            this.owner = owner; this.power = power; this.start = start; this.windup = windup; this.lvl = lvl;
        }
    }

    // ---------- small helpers ----------

    static boolean is(Power p) {
        return p == Power.GOJO || p == Power.SUKUNA;
    }

    static String[] slotNames(Power p) {
        if (p == Power.GOJO) return new String[] { "Lapse Blue", "Reversal Red", "Infinity", "Domain Expansion: Unlimited Void" };
        return new String[] { "Dismantle", "Cleave", "Domain Expansion: Malevolent Shrine" };
    }

    /** {cooldown, duration, radius, damage, strength, extra} for slot 1 to 4. */
    static double[] stats(PowerSettings s, int slot) {
        return switch (slot) {
            case 1 -> new double[] { s.abilityCooldown, s.abilityDuration, s.abilityRadius, s.abilityDamage, s.abilityStrength, s.abilityExtra };
            case 2 -> new double[] { s.slot2Cooldown, s.slot2Duration, s.slot2Radius, s.slot2Damage, s.slot2Strength, s.slot2Extra };
            case 3 -> new double[] { s.slot3Cooldown, s.slot3Duration, s.slot3Radius, s.slot3Damage, s.slot3Strength, s.slot3Extra };
            default -> new double[] { s.slot4Cooldown, s.slot4Duration, s.slot4Radius, s.slot4Damage, s.slot4Strength, s.slot4Extra };
        };
    }

    private static long[] slots(PlayerData d) {
        if (d.slotReadyAt == null || d.slotReadyAt.length < 5) d.slotReadyAt = new long[5];
        return d.slotReadyAt;
    }

    private static int secs(double seconds, int lvl) {
        return (int) Math.round(seconds * PowerEngine.durationScale(lvl) * 20);
    }

    private static double cd(double base, int lvl) {
        double f = Math.max(0.3, 1.0 - PowerEngine.config.cooldownReductionPerLevel * (lvl - 1));
        return base * f;
    }

    /** Damage with the level bonus but without the normal ability damage cap, so these can be as strong as the config says. */
    private static float dmg(double base, int lvl) {
        return (float) (base * (1.0 + PowerEngine.config.damageBonusPerLevel * (lvl - 1)));
    }

    static void hit(LivingEntity e, ServerPlayer p, float amount) {
        sureHit = true;
        try {
            Compat.hurt(e, p, amount);
        } finally {
            sureHit = false;
        }
    }

    private static boolean passable(ServerLevel l, Vec3 v) {
        BlockPos bp = BlockPos.containing(v);
        return l.getBlockState(bp).getCollisionShape(l, bp).isEmpty();
    }

    private static ServerPlayer player(UUID id) {
        MinecraftServer s = PowerEngine.server;
        return s == null ? null : s.getPlayerList().getPlayer(id);
    }

    private static String cdText(long readyAt) {
        long ms = readyAt - System.currentTimeMillis();
        return ms <= 0 ? "READY" : ((ms + 999) / 1000) + "s";
    }

    private static LivingEntity firstOnRay(ServerPlayer p, ServerLevel l, Vec3 v, double grow) {
        for (LivingEntity e : Abilities.targetsAround(p, l, v, 4.0)) {
            if (e.getBoundingBox().inflate(grow).contains(v)) return e;
        }
        return null;
    }

    // ---------- HUD ----------

    static String hud(ServerPlayer p, Power pw, PlayerData d, String ultText) {
        long[] sl = slots(d);
        if (pw == Power.GOJO) {
            String inf = infinityEnd.containsKey(p.getUUID()) ? "ON" : cdText(sl[3]);
            return "Blue: " + cdText(d.abilityReadyAt) + " | Red: " + cdText(sl[2]) + " | Infinity: " + inf
                    + " | Domain: " + cdText(sl[4]) + " | Purple: " + ultText;
        }
        return "Dismantle: " + cdText(d.abilityReadyAt) + " | Cleave: " + cdText(sl[2])
                + " | Domain: " + cdText(sl[3]) + " | Flame: " + ultText;
    }

    // ---------- casting ----------

    /** Slot 1 comes from Abilities.cast (it handles the cooldown). */
    static boolean slot1(ServerPlayer p, Power pw, PowerSettings s, int lvl) {
        return pw == Power.GOJO ? blue(p, s, lvl) : dismantle(p, s, lvl);
    }

    /** /ability 2, 3 or 4. */
    static boolean castSlot(ServerPlayer p, Power pw, int slot) {
        if (pw == Power.SUKUNA && slot == 4) slot = 3; // slot 4 is the domain for both, so /ability 4 and /domain work for Sukuna too
        String[] names = slotNames(pw);
        if (slot < 1 || slot > names.length) {
            PowerEngine.tell(p, pw.display + " has " + names.length + " abilities: use /ability 1 to " + names.length
                    + ", and /ultimate.", ChatFormatting.GRAY);
            return false;
        }
        PlayerData d = PowerEngine.data(p);
        PowerSettings s = PowerEngine.config.settings(pw);
        int lvl = d.level;
        long[] sl = slots(d);
        UUID id = p.getUUID();

        // Infinity is a toggle: pressing it again turns it off (and starts the cooldown).
        if (pw == Power.GOJO && slot == 3 && infinityEnd.containsKey(id)) {
            infinityOff(p, true);
            return true;
        }

        long ms = sl[slot] - System.currentTimeMillis();
        if (ms > 0) {
            PowerEngine.tell(p, names[slot - 1] + " ready in " + ((ms + 999) / 1000) + "s", ChatFormatting.GRAY);
            return false;
        }

        boolean ok;
        if (pw == Power.GOJO) {
            ok = switch (slot) {
                case 2 -> red(p, s, lvl);
                case 3 -> infinityOn(p, s, lvl);
                default -> domain(p, pw, s, 4, lvl);
            };
        } else {
            ok = slot == 2 ? cleave(p, s, lvl) : domain(p, pw, s, 3, lvl);
        }
        if (!ok) return false;
        // Infinity starts its cooldown when it is switched off.
        if (!(pw == Power.GOJO && slot == 3)) {
            sl[slot] = System.currentTimeMillis() + (long) (cd(stats(s, slot)[0], lvl) * 1000);
        }
        return true;
    }

    /** Called from Abilities.ultimate: starts the charge-up. */
    static void startUltimate(ServerPlayer p, Power pw, PowerSettings s, int lvl) {
        int windup = Math.max(1, (int) Math.round(s.ultimateWindup * 20));
        channels.add(new Channel(p.getUUID(), pw, PowerEngine.now(), windup, lvl));
        if (pw == Power.GOJO) Fx.purpleStart(p, windup);
        else Fx.flameStart(p, windup);
    }

    // ---------- Gojo: Lapse Blue ----------

    private static boolean blue(ServerPlayer p, PowerSettings s, int lvl) {
        ServerLevel l = Compat.level(p);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Vec3 c = eye.add(look.scale(2.0));
        for (double d = 2.0; d <= 7.0; d += 0.5) {
            Vec3 v = eye.add(look.scale(d));
            if (!passable(l, v)) break;
            c = v;
        }
        int t = PowerEngine.now();
        int life = Math.max(10, secs(s.abilityDuration, lvl));
        orbs.add(new Orb(p.getUUID(), l, c, t, t + life, lvl));
        Fx.blueCast(p, c, life);
        return true;
    }

    private static void orbTick(MinecraftServer srv, int t) {
        Iterator<Orb> it = orbs.iterator();
        while (it.hasNext()) {
            Orb o = it.next();
            ServerPlayer owner = srv.getPlayerList().getPlayer(o.owner);
            if (owner == null || !owner.isAlive() || t >= o.end) {
                it.remove();
                continue;
            }
            PowerSettings s = PowerEngine.config.settings(Power.GOJO);
            if (t % 2 == 0) Fx.blueOrb(o.level, o.pos, t - o.start, s.abilityRadius);
            double radius = s.abilityRadius;
            for (LivingEntity e : Abilities.targetsAround(owner, o.level, o.pos, radius)) {
                Vec3 to = o.pos.subtract(e.position().add(0, e.getBbHeight() * 0.5, 0));
                double dist = to.length();
                if (dist < 0.4) continue;
                double sp = Math.min(s.abilityStrength, 0.25 + dist * 0.12);
                Vec3 v = to.normalize().scale(sp);
                Compat.push(e, new Vec3(v.x, v.y + 0.04, v.z));
            }
            if ((t - o.start) % 5 == 0) {
                double hitR = Math.max(2.5, radius * 0.35);
                float base = dmg(s.abilityDamage, o.lvl);
                for (LivingEntity e : Abilities.targetsAround(owner, o.level, o.pos, hitR)) {
                    double dist = e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(o.pos);
                    hit(e, owner, dist < 1.8 ? base * 2f : base);
                }
            }
        }
    }

    // ---------- Gojo: Reversal Red ----------

    private static boolean red(ServerPlayer p, PowerSettings s, int lvl) {
        ServerLevel l = Compat.level(p);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        double range = s.slot2Radius, blast = Math.max(1, s.slot2Extra);
        Vec3 hit = eye.add(look.scale(range));
        LivingEntity direct = null;
        for (double d = 1.0; d <= range; d += 0.5) {
            Vec3 v = eye.add(look.scale(d));
            if (!passable(l, v)) {
                hit = eye.add(look.scale(Math.max(0.5, d - 0.5)));
                break;
            }
            LivingEntity e = firstOnRay(p, l, v, 0.5);
            if (e != null) {
                hit = v;
                direct = e;
                break;
            }
        }
        float base = dmg(s.slot2Damage, lvl);
        for (LivingEntity e : Abilities.targetsAround(p, l, hit, blast)) {
            double dist = e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(hit);
            float amt = e == direct ? base : (float) (base * (0.45 + 0.55 * Math.max(0, 1 - dist / blast)));
            hit(e, p, amt);
            Vec3 away = e.position().add(0, 0.5, 0).subtract(hit);
            away = away.lengthSqr() < 0.01 ? look : away.normalize();
            Compat.push(e, new Vec3(away.x * s.slot2Strength, 0.5 + Math.max(0, away.y) * 0.3, away.z * s.slot2Strength));
        }
        Fx.redBlast(l, eye.add(look.scale(1.2)).add(0, -0.25, 0), hit, blast);
        return true;
    }

    // ---------- Gojo: Infinity (toggle) ----------

    private static boolean infinityOn(ServerPlayer p, PowerSettings s, int lvl) {
        infinityEnd.put(p.getUUID(), PowerEngine.now() + Math.max(20, secs(s.slot3Duration, lvl)));
        Fx.infinityOn(p);
        PowerEngine.tell(p, "Infinity: ON", ChatFormatting.AQUA);
        return true;
    }

    static void infinityOff(ServerPlayer p, boolean startCooldown) {
        if (infinityEnd.remove(p.getUUID()) == null) return;
        Fx.infinityOff(p);
        PowerEngine.tell(p, "Infinity: OFF", ChatFormatting.GRAY);
        if (startCooldown) {
            PlayerData d = PowerEngine.data(p);
            PowerSettings s = PowerEngine.config.settings(Power.GOJO);
            slots(d)[3] = System.currentTimeMillis() + (long) (cd(s.slot3Cooldown, d.level) * 1000);
        }
    }

    private static void infinityTick(MinecraftServer srv, int t) {
        if (infinityEnd.isEmpty()) return;
        for (UUID id : new ArrayList<>(infinityEnd.keySet())) {
            ServerPlayer p = srv.getPlayerList().getPlayer(id);
            if (p == null || !p.isAlive() || PowerEngine.powerOf(p) != Power.GOJO) {
                infinityEnd.remove(id);
                continue;
            }
            if (t >= infinityEnd.get(id)) {
                infinityOff(p, true);
                continue;
            }
            PowerSettings s = PowerEngine.config.settings(Power.GOJO);
            ServerLevel l = Compat.level(p);
            Vec3 c = p.position().add(0, 1, 0);
            double r = Math.max(1.5, s.slot3Radius);
            for (LivingEntity e : Abilities.targetsAround(p, l, c, r)) {
                Vec3 away = e.position().subtract(p.position()).multiply(1, 0, 1);
                double dist = Math.max(0.2, away.length());
                away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
                double sp = s.slot3Strength * (0.4 + 0.6 * Math.max(0, 1 - dist / r));
                Compat.push(e, new Vec3(away.x * sp, Math.max(e.getDeltaMovement().y, 0.04), away.z * sp));
                if (t % 6 == 0) Fx.infinityRipple(l, e.position().add(0, e.getBbHeight() * 0.5, 0));
            }
            AABB box = new AABB(c.x - r * 2, c.y - r * 2, c.z - r * 2, c.x + r * 2, c.y + r * 2, c.z + r * 2);
            for (Projectile pr : l.getEntitiesOfClass(Projectile.class, box)) {
                if (pr.getOwner() == p) continue;
                Vec3 pv = pr.getDeltaMovement();
                if (pv.lengthSqr() < 0.0004) continue;
                double dist = pr.position().distanceTo(c);
                if (dist > r * 1.6) continue;
                Compat.push(pr, pv.scale(0.18));
                if (t % 4 == 0) Fx.infinityRipple(l, pr.position());
            }
            if (t % 3 == 0) Fx.infinityShell(l, c, r, t);
        }
    }

    /** True if Infinity should cancel this damage. Sure hits (domains, Purple, Dismantle, Cleave) go through. */
    static boolean shields(ServerPlayer p, DamageSource src) {
        if (sureHit || !infinityEnd.containsKey(p.getUUID())) return false;
        return src.getEntity() != null || src.getDirectEntity() != null
                || src.is(DamageTypeTags.IS_PROJECTILE) || src.is(DamageTypeTags.IS_EXPLOSION)
                || src.is(DamageTypeTags.IS_FALL) || src.is(DamageTypeTags.IS_LIGHTNING) || src.is(DamageTypeTags.IS_FIRE);
    }

    // ---------- Sukuna: Dismantle and Cleave ----------

    private static boolean dismantle(ServerPlayer p, PowerSettings s, int lvl) {
        UUID id = p.getUUID();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Fx.later(i * 3, () -> slashOnce(id, idx, lvl));
        }
        return true;
    }

    private static void slashOnce(UUID id, int idx, int lvl) {
        ServerPlayer p = player(id);
        if (p == null || !p.isAlive()) return;
        PowerSettings s = PowerEngine.config.settings(Power.SUKUNA);
        ServerLevel l = Compat.level(p);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        double range = s.abilityRadius;
        Fx.slashArc(p, range, idx);
        float base = dmg(s.abilityDamage, lvl);
        for (LivingEntity e : Abilities.targetsAround(p, l, eye, range)) {
            Vec3 to = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
            if (to.lengthSqr() < 0.01) continue;
            if (to.normalize().dot(look) < 0.7) continue;
            hit(e, p, base);
            Vec3 n = to.normalize();
            Compat.push(e, new Vec3(n.x * s.abilityStrength, 0.15, n.z * s.abilityStrength));
            Fx.slashHit(e);
        }
    }

    private static boolean cleave(ServerPlayer p, PowerSettings s, int lvl) {
        LivingEntity target = Abilities.lookTarget(p, s.slot2Radius);
        if (target == null) {
            PowerEngine.tell(p, "No target in your sights.", ChatFormatting.GRAY);
            return false;
        }
        double amt = Math.max(s.slot2Damage, target.getMaxHealth() * s.slot2Strength);
        if (s.slot2Extra > 0) amt = Math.min(amt, s.slot2Extra);
        hit(target, p, dmg(amt, lvl));
        Fx.cleaveFx(Compat.level(p), target.position().add(0, target.getBbHeight() * 0.5, 0), p.getLookAngle());
        return true;
    }

    // ---------- Domain expansions ----------

    private static boolean domain(ServerPlayer p, Power pw, PowerSettings s, int slot, int lvl) {
        UUID id = p.getUUID();
        for (Domain d : domains) {
            if (d.owner.equals(id)) {
                PowerEngine.tell(p, "Your domain is already open.", ChatFormatting.GRAY);
                return false;
            }
        }
        double[] st = stats(s, slot);
        ServerLevel l = Compat.level(p);
        Vec3 c = p.position();
        int t = PowerEngine.now();
        boolean scene = PowerEngine.config.domainCutscene;
        int cut = scene ? Math.max(10, PowerEngine.config.domainCutsceneTicks) : 0;
        int life = Math.max(40, secs(st[1], lvl));
        double radius = st[2];
        domains.add(new Domain(id, pw, l, c, radius, dmg(st[3], lvl), t + cut, t + cut + life, lvl));

        if (scene) cutscene(p, pw, l, c, radius, cut);
        else Fx.domainOpen(l, c, pw, radius, life);
        return true;
    }

    private static void cutscene(ServerPlayer owner, Power pw, ServerLevel l, Vec3 c, double radius, int cut) {
        boolean gojo = pw == Power.GOJO;
        String top = "{\"text\":\"DOMAIN EXPANSION\",\"bold\":true,\"color\":\"" + (gojo ? "aqua" : "red") + "\"}";
        String sub = gojo
                ? "{\"text\":\"\\u7121\\u91cf\\u7a7a\\u51e6  Unlimited Void\",\"color\":\"white\",\"italic\":true}"
                : "{\"text\":\"\\u4f0f\\u9b54\\u5fa1\\u5eda\\u5b50  Malevolent Shrine\",\"color\":\"dark_red\",\"italic\":true}";
        int hold = Math.max(10, cut - 12);
        PowerSettings s = PowerEngine.config.settings(pw);

        // everyone close enough sees the title card, victims are frozen and flashed
        for (ServerPlayer o : PowerEngine.server.getPlayerList().getPlayers()) {
            if (o.level() != owner.level() || o.distanceToSqr(owner) > radius * radius * 3.2) continue;
            String u = o.getUUID().toString();
            Fx.cmd("title " + u + " times 4 " + hold + " 12");
            Fx.cmd("title " + u + " subtitle " + sub);
            Fx.cmd("title " + u + " title " + top);
            if (o != owner) {
                Compat.effect(o, MobEffects.SLOWNESS, cut, 10);
                Compat.effectNamed(o, 16, 0, "BLINDNESS");
                Compat.effectNamed(o, cut, 4, "WEAKNESS");
                Compat.push(o, Vec3.ZERO);
            }
        }
        for (LivingEntity e : Abilities.targetsAround(owner, l, c, radius * 1.2)) {
            if (e instanceof ServerPlayer) continue;
            Compat.effect(e, MobEffects.SLOWNESS, cut, 10);
        }
        Compat.effect(owner, MobEffects.RESISTANCE, cut + 20, 4);
        Fx.sound(l, c, gojo ? "minecraft:block.end_portal.spawn" : "minecraft:entity.wither.spawn", 3.0f, gojo ? 0.7f : 0.6f);
        Fx.sound(l, c, gojo ? "minecraft:entity.warden.sonic_boom" : "minecraft:entity.ender_dragon.growl", 3.0f, 0.6f);
        Fx.cutscene(l, c, pw, cut);
        int life = Math.max(40, (int) Math.round(stats(s, gojo ? 4 : 3)[1] * 20));
        Fx.later(cut - 8, () -> Fx.domainOpen(l, c, pw, radius, life));
    }

    private static void domainTick(MinecraftServer srv, int t) {
        Iterator<Domain> it = domains.iterator();
        while (it.hasNext()) {
            Domain d = it.next();
            ServerPlayer owner = srv.getPlayerList().getPlayer(d.owner);
            if (owner == null || !owner.isAlive() || t >= d.end) {
                if (t >= d.start) Fx.domainClose(d.level, d.center, d.power, d.radius);
                if (owner != null) PowerEngine.tell(owner, "Domain closed.", ChatFormatting.GRAY);
                it.remove();
                continue;
            }
            if (t < d.start) continue;
            boolean gojo = d.power == Power.GOJO;
            if (t % 2 == 0) Fx.domainAmbient(d.level, d.center, d.power, d.radius, t - d.start);
            if ((t - d.start) % 10 != 0) continue;

            // the owner is untouchable and fast inside
            if (owner.level() == d.level && owner.position().distanceTo(d.center) <= d.radius * 1.2) {
                if (gojo) {
                    Compat.effect(owner, MobEffects.SPEED, 30, 2);
                    Compat.effect(owner, MobEffects.RESISTANCE, 30, 2);
                    Compat.effect(owner, MobEffects.REGENERATION, 30, 1);
                } else {
                    Compat.effectNamed(owner, 30, 2, "STRENGTH", "DAMAGE_BOOST");
                    Compat.effect(owner, MobEffects.SPEED, 30, 1);
                    Compat.effect(owner, MobEffects.RESISTANCE, 30, 2);
                }
            }
            for (LivingEntity e : Abilities.targetsAround(owner, d.level, d.center, d.radius)) {
                hit(e, owner, (float) d.damage);
                if (gojo) {
                    // information overload: they can barely move, mine or swing
                    Compat.effect(e, MobEffects.SLOWNESS, 40, 5);
                    Compat.effectNamed(e, 40, 3, "WEAKNESS");
                    Compat.effectNamed(e, 40, 3, "MINING_FATIGUE", "DIG_SLOWDOWN");
                    Compat.effectNamed(e, 40, 0, "DARKNESS");
                } else {
                    Compat.effectNamed(e, 40, 0, "WITHER");
                    Compat.effect(e, MobEffects.GLOWING, 40, 0);
                    Compat.effect(e, MobEffects.SLOWNESS, 20, 0);
                    Fx.slashHit(e);
                }
            }
        }
    }

    // ---------- Ultimates: Hollow Purple and Divine Flame ----------

    private static final double BEAM_SPEED = 5.0; // blocks per tick

    private static void channelTick(MinecraftServer srv, int t) {
        Iterator<Channel> it = channels.iterator();
        while (it.hasNext()) {
            Channel ch = it.next();
            ServerPlayer p = srv.getPlayerList().getPlayer(ch.owner);
            if (p == null || !p.isAlive()) {
                it.remove();
                continue;
            }
            PowerSettings s = PowerEngine.config.settings(ch.power);
            ServerLevel l = Compat.level(p);
            int age = t - ch.start;
            if (age < ch.windup) {
                if (t % 2 == 0) {
                    if (ch.power == Power.GOJO) Fx.purpleCharge(p, age, ch.windup);
                    else Fx.flameCharge(p, age, ch.windup);
                }
                if (t % 5 == 0) {
                    Compat.effect(p, MobEffects.SLOWNESS, 10, 2);
                    Compat.effect(p, MobEffects.RESISTANCE, 10, 3);
                }
                continue;
            }
            if (!ch.fired) {
                ch.fired = true;
                ch.fireTick = t;
                ch.dir = p.getLookAngle();
                ch.origin = p.getEyePosition().add(ch.dir.scale(3.0)).add(0, -0.1, 0);
                if (ch.power == Power.GOJO) {
                    ch.range = s.ultimateRadius;
                    ch.travel = Math.max(2, (int) Math.ceil(ch.range / BEAM_SPEED));
                    Fx.purpleFire(l, ch.origin, ch.dir, ch.range, s.ultimateStrength, ch.travel);
                    Compat.push(p, new Vec3(-ch.dir.x * 0.5, 0.1, -ch.dir.z * 0.5));
                } else {
                    // find where the flame arrow lands: first wall or creature on its path
                    double max = s.ultimateStrength;
                    double dist = max;
                    for (double d = 1.5; d <= max; d += 1.0) {
                        Vec3 v = ch.origin.add(ch.dir.scale(d));
                        if (!passable(l, v) || firstOnRay(p, l, v, 0.8) != null) {
                            dist = d;
                            break;
                        }
                    }
                    ch.range = dist;
                    ch.impact = ch.origin.add(ch.dir.scale(dist));
                    ch.travel = Math.max(2, (int) Math.ceil(dist / BEAM_SPEED));
                    Fx.flameFire(l, ch.origin, ch.dir, dist, ch.travel);
                    Compat.push(p, new Vec3(-ch.dir.x * 0.4, 0.1, -ch.dir.z * 0.4));
                }
                continue;
            }
            int fly = t - ch.fireTick;
            if (ch.power == Power.GOJO) {
                purpleStep(p, l, s, ch, fly);
            } else {
                flameStep(p, l, s, ch, fly);
            }
            if (fly >= ch.travel) it.remove();
        }
    }

    private static void purpleStep(ServerPlayer p, ServerLevel l, PowerSettings s, Channel ch, int fly) {
        double width = Math.max(1.0, s.ultimateStrength);
        Vec3 head = ch.origin.add(ch.dir.scale(BEAM_SPEED * fly));
        Vec3 prev = ch.origin.add(ch.dir.scale(BEAM_SPEED * Math.max(0, fly - 1)));
        Fx.purpleTrail(l, head, ch.dir, width);
        float base = dmg(s.ultimateDamage, ch.lvl);
        int steps = (int) Math.ceil(BEAM_SPEED);
        for (int i = 0; i <= steps; i++) {
            Vec3 v = prev.lerp(head, (double) i / steps);
            for (LivingEntity e : Abilities.targetsAround(p, l, v, width + 3.0)) {
                if (ch.hit.contains(e.getUUID())) continue;
                if (!e.getBoundingBox().inflate(width).contains(v)) continue;
                ch.hit.add(e.getUUID());
                hit(e, p, base);
                Compat.push(e, new Vec3(ch.dir.x * 2.6, 0.5, ch.dir.z * 2.6));
                Fx.purpleHit(l, e.position().add(0, e.getBbHeight() * 0.5, 0));
            }
        }
    }

    private static void flameStep(ServerPlayer p, ServerLevel l, PowerSettings s, Channel ch, int fly) {
        if (fly < ch.travel) {
            Vec3 head = ch.origin.add(ch.dir.scale(Math.min(ch.range, BEAM_SPEED * fly)));
            Fx.flameTrail(l, head, ch.dir);
            return;
        }
        // impact
        double r = s.ultimateRadius;
        float base = dmg(s.ultimateDamage, ch.lvl);
        int burn = Math.max(1, (int) Math.round(s.abilityExtra));
        for (LivingEntity e : Abilities.targetsAround(p, l, ch.impact, r)) {
            double dist = e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(ch.impact);
            hit(e, p, (float) (base * (0.55 + 0.45 * Math.max(0, 1 - dist / r))));
            Compat.ignite(e, burn);
            Vec3 away = e.position().subtract(ch.impact);
            away = away.lengthSqr() < 0.01 ? new Vec3(0, 0, 0) : away.normalize();
            Compat.push(e, new Vec3(away.x * 1.6, 0.9, away.z * 1.6));
        }
        Fx.flameImpact(l, ch.impact, r);
    }

    // ---------- passives: infinite lunge and invisible gear ----------

    static void passives(ServerPlayer p, Power pw) {
        int d = 60;
        if (pw == Power.GOJO) {
            Compat.effect(p, MobEffects.SPEED, d, 0);
            Compat.effect(p, MobEffects.NIGHT_VISION, 400, 0);
        } else {
            Compat.effectNamed(p, d, 0, "STRENGTH", "DAMAGE_BOOST");
            Compat.effect(p, MobEffects.RESISTANCE, d, 0);
        }
    }

    private static Method getFood, setFood;
    private static boolean foodTried;

    /** Keeps hunger full so the spear lunge (which costs hunger) never runs out. Reflection so a rename can't break the build. */
    static void fillHunger(ServerPlayer p) {
        try {
            if (!foodTried) {
                foodTried = true;
                for (Method m : p.getClass().getMethods()) {
                    if (m.getName().equals("getFoodData") && m.getParameterCount() == 0) {
                        getFood = m;
                        break;
                    }
                }
            }
            if (getFood != null) {
                Object fd = getFood.invoke(p);
                if (setFood == null && fd != null) {
                    for (Method m : fd.getClass().getMethods()) {
                        if (m.getName().equals("setFoodLevel") && m.getParameterCount() == 1) {
                            setFood = m;
                            break;
                        }
                    }
                }
                if (setFood != null && fd != null) {
                    setFood.invoke(fd, 20);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // use the command below
        }
        Fx.cmd("effect give " + p.getUUID() + " minecraft:saturation 1 0 true");
    }

    /** Everyone else sees the player with no armor and nothing in their hands. Repeated because the server resends real gear on changes. */
    static void hideGear(MinecraftServer srv, ServerPlayer p) {
        List<Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
        list.add(Pair.of(EquipmentSlot.HEAD, ItemStack.EMPTY));
        list.add(Pair.of(EquipmentSlot.CHEST, ItemStack.EMPTY));
        list.add(Pair.of(EquipmentSlot.LEGS, ItemStack.EMPTY));
        list.add(Pair.of(EquipmentSlot.FEET, ItemStack.EMPTY));
        list.add(Pair.of(EquipmentSlot.MAINHAND, ItemStack.EMPTY));
        list.add(Pair.of(EquipmentSlot.OFFHAND, ItemStack.EMPTY));
        ClientboundSetEquipmentPacket pk = new ClientboundSetEquipmentPacket(p.getId(), list);
        for (ServerPlayer o : srv.getPlayerList().getPlayers()) {
            if (o == p || o.level() != p.level() || o.distanceToSqr(p) > 128.0 * 128.0) continue;
            o.connection.send(pk);
        }
    }

    /** Puts the real gear back on everyone's screen (used when the player stops being Gojo or Sukuna). */
    static void restoreGear(MinecraftServer srv, ServerPlayer p) {
        List<Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND }) {
            list.add(Pair.of(slot, p.getItemBySlot(slot).copy()));
        }
        ClientboundSetEquipmentPacket pk = new ClientboundSetEquipmentPacket(p.getId(), list);
        for (ServerPlayer o : srv.getPlayerList().getPlayers()) {
            if (o == p || o.level() != p.level()) continue;
            o.connection.send(pk);
        }
    }

    // ---------- tick and cleanup ----------

    static void tick(MinecraftServer srv, int t) {
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (!p.isAlive()) continue;
            Power pw = PowerEngine.powerOf(p);
            if (pw == null || !is(pw) || !PowerEngine.enabledFor(p)) continue;
            if (PowerEngine.config.secretInfiniteLunge && t % 5 == 0) fillHunger(p);
            if (PowerEngine.config.secretInvisibleGear && t % 2 == 0) hideGear(srv, p);
            if (t % 10 == 0) Fx.idleAura(p, pw, t);
        }
        if (!infinityEnd.isEmpty()) infinityTick(srv, t);
        if (!orbs.isEmpty()) orbTick(srv, t);
        if (!domains.isEmpty()) domainTick(srv, t);
        if (!channels.isEmpty()) channelTick(srv, t);
    }

    /** Forgets everything running for this player (left, died, or got a different power). */
    static void clear(UUID id) {
        infinityEnd.remove(id);
        orbs.removeIf(o -> o.owner.equals(id));
        domains.removeIf(d -> d.owner.equals(id));
        channels.removeIf(c -> c.owner.equals(id));
    }

    static void clearAndRestore(ServerPlayer p) {
        clear(p.getUUID());
        if (PowerEngine.server != null) restoreGear(PowerEngine.server, p);
    }

    static void reset() {
        infinityEnd.clear();
        orbs.clear();
        domains.clear();
        channels.clear();
    }
}
