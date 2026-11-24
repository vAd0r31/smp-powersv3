package dev.smppowers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Visual and sound effects for every ability and ultimate.
 *
 * Particles are looked up by name (so a renamed particle just goes missing instead of breaking the build).
 * Flying rocks, pillars and shockwaves are vanilla block_display entities made with the /summon command,
 * animated with /data merge, and removed again. They have no collision and never change the world.
 * Sounds go through /playsound.
 */
final class Fx {
    private Fx() {}

    private static final Random R = new Random();
    private static final Object NONE = new Object();
    private static final Map<String, Object> PARTICLES = new HashMap<>();
    private record Task(int at, Runnable run) {}
    private static final List<Task> tasks = new ArrayList<>();
    private static final Map<UUID, int[]> trails = new HashMap<>(); // {kind, endTick}
    private static int live = 0;
    private static final int MAX_LIVE = 260;

    static boolean on() {
        return PowerEngine.config.effects && PowerEngine.server != null;
    }

    // ================= low level =================

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    static void later(int ticks, Runnable r) {
        tasks.add(new Task(PowerEngine.now() + Math.max(0, ticks), r));
    }

    static void tick(MinecraftServer s, int t) {
        if (!tasks.isEmpty()) {
            List<Task> due = new ArrayList<>();
            tasks.removeIf(k -> {
                if (t >= k.at()) {
                    due.add(k);
                    return true;
                }
                return false;
            });
            for (Task k : due) {
                try {
                    k.run().run();
                } catch (Exception e) {
                    PowersMod.LOGGER.warn("fx task failed", e);
                }
            }
        }
        // movement trails (dash, launch)
        if (!trails.isEmpty()) {
            trails.entrySet().removeIf(e -> {
                ServerPlayer p = s.getPlayerList().getPlayer(e.getKey());
                if (p == null || t > e.getValue()[1]) return true;
                ServerLevel l = Compat.level(p);
                Vec3 c = p.position().add(0, 1, 0);
                if (e.getValue()[0] == 0) {
                    p(l, "BUBBLE_POP", c, 6, 0.3, 0.4, 0.3, 0.02);
                    p(l, "SPLASH", c, 8, 0.3, 0.4, 0.3, 0.1);
                    p(l, "DOLPHIN", c, 3, 0.3, 0.3, 0.3, 0);
                } else {
                    p(l, "CLOUD", c, 6, 0.3, 0.4, 0.3, 0.02);
                    p(l, "POOF", c.subtract(0, 0.5, 0), 4, 0.3, 0.1, 0.3, 0.02);
                    p(l, "END_ROD", c, 2, 0.3, 0.3, 0.3, 0.01);
                }
                return false;
            });
        }
        // armed venom / life siphon glow
        if (t % 4 == 0) {
            for (UUID id : new ArrayList<>(PowerEngine.venomArmed.keySet())) {
                ServerPlayer p = s.getPlayerList().getPlayer(id);
                if (p != null) {
                    ServerLevel l = Compat.level(p);
                    p(l, "SNEEZE", p.position().add(0, 1, 0), 3, 0.4, 0.5, 0.4, 0.01);
                    p(l, "WITCH", p.position().add(0, 1.2, 0), 3, 0.4, 0.5, 0.4, 0);
                }
            }
            for (UUID id : new ArrayList<>(PowerEngine.siphon.keySet())) {
                ServerPlayer p = s.getPlayerList().getPlayer(id);
                if (p != null) {
                    ServerLevel l = Compat.level(p);
                    spiral(l, "HEART", p.position(), 0.9, 2.0, 1.5, 3, t * 0.3);
                    p(l, "GLOW", p.position().add(0, 1, 0), 3, 0.5, 0.7, 0.5, 0);
                }
            }
        }
    }

    /** Removes any leftover display entities (after a crash or restart). */
    static void cleanup() {
        if (PowerEngine.server == null) return;
        for (String dim : new String[] { "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end" }) {
            cmd("execute in " + dim + " run kill @e[type=minecraft:block_display,tag=smppfx]");
        }
        tasks.clear();
        trails.clear();
        live = 0;
    }

    static void cmd(String c) {
        MinecraftServer s = PowerEngine.server;
        if (s == null) return;
        try {
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), c);
        } catch (Exception e) {
            PowersMod.LOGGER.warn("fx command failed: {}", c, e);
        }
    }

    static void sound(ServerLevel l, Vec3 v, String id, float vol, float pitch) {
        cmd("execute in " + Compat.dimensionId(l) + " run playsound " + id + " master @a "
                + f(v.x) + " " + f(v.y) + " " + f(v.z) + " " + vol + " " + pitch);
    }

    private static ParticleOptions pt(String name) {
        if (PARTICLES.containsKey(name)) {
            Object o = PARTICLES.get(name);
            return o == NONE ? null : (ParticleOptions) o;
        }
        Object found = NONE;
        try {
            Object o = ParticleTypes.class.getField(name).get(null);
            if (o instanceof ParticleOptions) found = o;
        } catch (Exception ignored) {
            // particle doesn't exist in this version, skip it
        }
        PARTICLES.put(name, found);
        return found == NONE ? null : (ParticleOptions) found;
    }

    static void p(ServerLevel l, String name, double x, double y, double z, int count, double dx, double dy, double dz, double speed) {
        ParticleOptions o = pt(name);
        if (o != null) l.sendParticles(o, x, y, z, count, dx, dy, dz, speed);
    }

    static void p(ServerLevel l, String name, Vec3 v, int count, double dx, double dy, double dz, double speed) {
        p(l, name, v.x, v.y, v.z, count, dx, dy, dz, speed);
    }

    // ================= particle shapes =================

    static void ring(ServerLevel l, String n, Vec3 c, double r, int pts) {
        for (int i = 0; i < pts; i++) {
            double a = i * Math.PI * 2 / pts;
            p(l, n, c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    /** A ring that grows outward over several ticks. */
    static void expandingRing(ServerLevel l, String n, Vec3 c, double maxR, int steps, int pts) {
        for (int k = 1; k <= steps; k++) {
            final double r = maxR * k / steps;
            later(k, () -> ring(l, n, c, r, Math.max(8, (int) (pts * r / maxR))));
        }
    }

    static void spiral(ServerLevel l, String n, Vec3 base, double r, double h, double turns, int pts, double phase) {
        for (int i = 0; i < pts; i++) {
            double t = (double) i / pts;
            double a = phase + t * turns * Math.PI * 2;
            p(l, n, base.x + Math.cos(a) * r, base.y + t * h, base.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    static void column(ServerLevel l, String n, Vec3 base, double spread, double h, int pts) {
        for (int i = 0; i < pts; i++) {
            p(l, n, base.x + (R.nextDouble() - 0.5) * spread, base.y + R.nextDouble() * h, base.z + (R.nextDouble() - 0.5) * spread, 1, 0, 0, 0, 0);
        }
    }

    static void sphereShell(ServerLevel l, String n, Vec3 c, double r, int pts) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < pts; i++) {
            double y = 1 - (i / (double) (pts - 1)) * 2;
            double rad = Math.sqrt(1 - y * y);
            double a = golden * i;
            p(l, n, c.x + Math.cos(a) * rad * r, c.y + y * r, c.z + Math.sin(a) * rad * r, 1, 0, 0, 0, 0);
        }
    }

    static void line(ServerLevel l, String n, Vec3 a, Vec3 b, int steps) {
        for (int i = 0; i <= steps; i++) {
            Vec3 v = a.lerp(b, (double) i / steps);
            p(l, n, v.x, v.y, v.z, 1, 0, 0, 0, 0);
        }
    }

    static void jag(ServerLevel l, String n, Vec3 a, Vec3 b, int segs, double off) {
        Vec3 prev = a;
        for (int i = 1; i <= segs; i++) {
            Vec3 v = a.lerp(b, (double) i / segs);
            if (i < segs) v = v.add((R.nextDouble() - 0.5) * off, (R.nextDouble() - 0.5) * off, (R.nextDouble() - 0.5) * off);
            line(l, n, prev, v, 4);
            prev = v;
        }
    }

    // ================= block displays =================

    private static String quat() {
        double x = R.nextGaussian(), y = R.nextGaussian(), z = R.nextGaussian();
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6) { x = 0; y = 1; z = 0; len = 1; }
        double ang = R.nextDouble() * Math.PI, s = Math.sin(ang / 2);
        return "[" + f(x / len * s) + "f," + f(y / len * s) + "f," + f(z / len * s) + "f," + f(Math.cos(ang / 2)) + "f]";
    }

    private static String tf(double sx, double sy, double sz, double tx, double ty, double tz, String rot) {
        return "{left_rotation:" + rot + ",right_rotation:[0f,0f,0f,1f],scale:[" + f(sx) + "f," + f(sy) + "f," + f(sz)
                + "f],translation:[" + f(tx) + "f," + f(ty) + "f," + f(tz) + "f]}";
    }

    private static final String NO_ROT = "[0f,0f,0f,1f]";

    /** Summons one block display. Returns its UUID text, or null if too many are alive. */
    private static String spawn(ServerLevel l, Vec3 pos, String block, double sx, double sy, double sz,
                                double tx, double ty, double tz, boolean glow) {
        if (live >= MAX_LIVE) return null;
        live++;
        UUID u = UUID.randomUUID();
        long m = u.getMostSignificantBits(), lo = u.getLeastSignificantBits();
        String nbt = "{UUID:[I;" + (int) (m >> 32) + "," + (int) m + "," + (int) (lo >> 32) + "," + (int) lo + "],"
                + "Tags:[\"smppfx\"],block_state:{Name:\"" + block + "\"},"
                + (glow ? "brightness:{block:15,sky:15}," : "")
                + "transformation:" + tf(sx, sy, sz, tx, ty, tz, NO_ROT) + "}";
        cmd("execute in " + Compat.dimensionId(l) + " run summon minecraft:block_display "
                + f(pos.x) + " " + f(pos.y) + " " + f(pos.z) + " " + nbt);
        return u.toString();
    }

    private static void morph(String id, int delay, int dur, double sx, double sy, double sz,
                              double tx, double ty, double tz, String rot) {
        if (id == null) return;
        later(delay, () -> cmd("data merge entity " + id + " {start_interpolation:0,interpolation_duration:" + dur
                + ",transformation:" + tf(sx, sy, sz, tx, ty, tz, rot) + "}"));
    }

    private static void kill(String id, int delay) {
        if (id == null) return;
        later(delay, () -> {
            cmd("kill " + id);
            live = Math.max(0, live - 1);
        });
    }

    /** Chunks of a block flying out, up, then falling and shrinking away. */
    static void debris(ServerLevel l, Vec3 c, String block, int count, double radius, double height, double size, boolean glow) {
        for (int i = 0; i < count; i++) {
            double a = R.nextDouble() * Math.PI * 2;
            double d = radius * (0.3 + 0.7 * R.nextDouble());
            double dx = Math.cos(a) * d, dz = Math.sin(a) * d;
            double up = height * (0.5 + 0.5 * R.nextDouble());
            double sz = size * (0.6 + 0.8 * R.nextDouble());
            String id = spawn(l, c.add(0, 0.1, 0), block, sz, sz, sz, -sz / 2, 0, -sz / 2, glow);
            morph(id, 1, 7, sz, sz, sz, dx - sz / 2, up, dz - sz / 2, quat());
            morph(id, 8, 8, sz * 0.2, sz * 0.2, sz * 0.2, dx * 1.2, -0.1, dz * 1.2, quat());
            kill(id, 17);
        }
    }

    /** A column that shoots up from the ground, holds, then thins away. */
    static void pillar(ServerLevel l, Vec3 base, String block, double w, double h, int grow, int hold, boolean glow) {
        String id = spawn(l, base, block, w, 0.05, w, -w / 2, 0, -w / 2, glow);
        morph(id, 1, grow, w, h, w, -w / 2, 0, -w / 2, NO_ROT);
        morph(id, 1 + grow + hold, 6, 0.02, h, 0.02, -0.01, 0, -0.01, NO_ROT);
        kill(id, 1 + grow + hold + 7);
    }

    /** Ground shockwave: rings of blocks that pop up one after another moving outward. */
    static void ripple(ServerLevel l, Vec3 c, double maxR, String block) {
        int k = 0;
        for (double r = 1.5; r <= maxR + 0.01; r += 1.5) {
            k++;
            final double rad = r;
            int n = (int) Math.max(6, Math.round(r * 2.2));
            double off = R.nextDouble() * Math.PI;
            for (int i = 0; i < n; i++) {
                double a = off + i * Math.PI * 2 / n;
                final Vec3 pos = new Vec3(c.x + Math.cos(a) * rad, c.y, c.z + Math.sin(a) * rad);
                later(k * 2, () -> {
                    double s = 0.7;
                    String id = spawn(l, pos, block, s, s, s, -s / 2, -0.4, -s / 2, false);
                    morph(id, 1, 3, s, s, s, -s / 2, 0.45, -s / 2, quat());
                    morph(id, 4, 5, s, s, s, -s / 2, -0.5, -s / 2, NO_ROT);
                    kill(id, 10);
                });
            }
        }
    }

    static void spikes(ServerLevel l, Vec3 c, double r, int n) {
        for (int i = 0; i < n; i++) {
            double a = R.nextDouble() * Math.PI * 2, d = r * (0.3 + 0.7 * R.nextDouble());
            final Vec3 pos = new Vec3(c.x + Math.cos(a) * d, c.y, c.z + Math.sin(a) * d);
            final String blk = R.nextBoolean() ? "minecraft:packed_ice" : "minecraft:blue_ice";
            final double w = 0.5 + R.nextDouble() * 0.4, h = 1.6 + R.nextDouble() * 2.2;
            later(R.nextInt(6), () -> pillar(l, pos, blk, w, h, 3, 22, false));
        }
    }

    // ================= helpers about the world =================

    private static final String[] BAD = { "air", "water", "lava", "barrier", "portal", "fire", "torch", "sign", "banner", "door",
            "bed", "rail", "button", "lever", "plate", "fence", "wall", "pane", "chest", "head", "skull", "flower", "fern", "vine",
            "sapling", "mushroom", "carpet", "layer", "slab", "stairs", "leaves", "cobweb", "bush", "tall_", "kelp", "seagrass" };

    /** The block under the player, so rocks flying up match the terrain. */
    static String blockBelow(ServerLevel l, Vec3 p) {
        for (int dy = 0; dy <= 4; dy++) {
            BlockState st = l.getBlockState(BlockPos.containing(p.x, p.y - 0.5 - dy, p.z));
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
            for (String bad : BAD) {
                if (id.contains(bad)) return "minecraft:stone";
            }
            return id;
        }
        return "minecraft:stone";
    }

    static double groundY(ServerLevel l, Vec3 p) {
        for (int dy = 0; dy < 80; dy++) {
            BlockPos bp = BlockPos.containing(p.x, p.y - dy, p.z);
            if (!l.getBlockState(bp).isAir()) return bp.getY() + 1.0;
        }
        return p.y;
    }

    // ================= abilities =================

    static void ability(ServerPlayer p, Power power) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 pos = p.position(), look = p.getLookAngle(), chest = pos.add(0, 1, 0);
        PowerSettings s = PowerEngine.config.settings(power);
        int t = PowerEngine.now();
        switch (power) {
            case TIDE -> {
                sound(l, pos, "minecraft:item.trident.riptide_1", 1.3f, 1.2f);
                expandingRing(l, "SPLASH", pos.add(0, 0.15, 0), 3.5, 6, 30);
                expandingRing(l, "BUBBLE_POP", pos.add(0, 0.6, 0), 2.5, 5, 20);
                p(l, "FALLING_WATER", chest, 40, 0.6, 0.8, 0.6, 0);
                trails.put(p.getUUID(), new int[] { 0, t + 14 });
            }
            case EMBER -> {
                double r = s.abilityRadius;
                sound(l, pos, "minecraft:entity.blaze.shoot", 1.5f, 0.8f);
                sound(l, pos, "minecraft:entity.generic.explode", 1.0f, 1.3f);
                expandingRing(l, "FLAME", pos.add(0, 0.2, 0), r, 6, 44);
                expandingRing(l, "SMALL_FLAME", pos.add(0, 0.5, 0), r * 0.7, 5, 30);
                p(l, "LAVA", chest, 25, 0.8, 0.5, 0.8, 0);
                p(l, "EXPLOSION", chest, 2, 0.5, 0.3, 0.5, 0);
                column(l, "FLAME", pos, 1.0, 2.5, 24);
                debris(l, pos, "minecraft:magma_block", 12, r, 2.2, 0.35, true);
                debris(l, pos, "minecraft:netherrack", 10, r, 1.6, 0.3, false);
            }
            case GALE -> {
                sound(l, pos, "minecraft:entity.wind_charge.wind_burst", 1.5f, 1.0f);
                expandingRing(l, "CLOUD", pos.add(0, 0.1, 0), 3, 5, 24);
                expandingRing(l, "POOF", pos.add(0, 0.3, 0), 4, 6, 20);
                spiral(l, "CLOUD", pos, 0.8, 3, 3, 30, 0);
                trails.put(p.getUUID(), new int[] { 1, t + 16 });
            }
            case STONE -> {
                double r = s.abilityRadius;
                String b = blockBelow(l, pos);
                sound(l, pos, "minecraft:entity.generic.explode", 1.2f, 0.6f);
                sound(l, pos, "minecraft:block.stone.break", 1.5f, 0.6f);
                ripple(l, pos, r, b);
                debris(l, pos, b, 14, r * 0.6, 2.5, 0.4, false);
                p(l, "EXPLOSION", pos.add(0, 0.3, 0), 4, r * 0.3, 0.1, r * 0.3, 0);
                expandingRing(l, "CLOUD", pos.add(0, 0.2, 0), r, 6, 36);
            }
            case FROST -> {
                double r = s.abilityRadius;
                sound(l, pos, "minecraft:block.glass.break", 1.5f, 0.7f);
                sound(l, pos, "minecraft:entity.player.hurt_freeze", 1.0f, 1.0f);
                expandingRing(l, "SNOWFLAKE", pos.add(0, 0.3, 0), r, 6, 50);
                spikes(l, pos, r, 12);
                p(l, "SNOWFLAKE", chest, 60, r * 0.4, 0.8, r * 0.4, 0.02);
            }
            case STORM -> {
                sound(l, pos, "minecraft:item.trident.thunder", 1.3f, 1.2f);
                p(l, "ELECTRIC_SPARK", chest, 60, 0.7, 1.0, 0.7, 0.3);
                expandingRing(l, "ELECTRIC_SPARK", pos.add(0, 0.2, 0), 3, 4, 30);
            }
            case SHADE -> {
                sound(l, pos, "minecraft:entity.enderman.teleport", 1.2f, 0.7f);
                p(l, "LARGE_SMOKE", chest, 40, 0.5, 0.9, 0.5, 0.02);
                for (int k = 0; k < 5; k++) {
                    final double r = 2.5 - k * 0.5;
                    later(k * 2, () -> ring(l, "SQUID_INK", chest, r, 24));
                }
                spiral(l, "SCULK_SOUL", pos, 0.7, 2.2, 2, 24, 0);
            }
            case VITAL -> {
                sound(l, pos, "minecraft:block.beacon.power_select", 1.3f, 1.4f);
                spiral(l, "HEART", pos, 0.9, 2.4, 2, 12, 0);
                expandingRing(l, "HAPPY_VILLAGER", pos.add(0, 0.2, 0), 2.5, 5, 20);
                p(l, "HEART", chest, 12, 0.7, 0.8, 0.7, 0);
            }
            case VENOM -> {
                sound(l, pos, "minecraft:entity.slime.squish", 1.3f, 0.6f);
                p(l, "SNEEZE", chest, 30, 0.5, 0.8, 0.5, 0.03);
                spiral(l, "WITCH", pos, 0.8, 2.2, 2, 24, 0);
                expandingRing(l, "WITCH", pos.add(0, 0.3, 0), 2, 4, 20);
            }
            case SMASH -> sound(l, pos, "minecraft:entity.wind_charge.wind_burst", 1.5f, 0.7f);
            case HUNTER -> {
                p(l, "CRIT", chest, 15, 0.5, 0.8, 0.5, 0.1);
                sound(l, pos, "minecraft:block.note_block.pling", 1.0f, 1.8f);
            }
            case DRAGON -> {
                sound(l, pos, "minecraft:entity.ender_dragon.growl", 1.5f, 1.1f);
                Vec3 eye = p.getEyePosition();
                for (int i = 1; i <= 14; i++) {
                    Vec3 v = eye.add(look.scale(i * 0.6));
                    double sp = i * 0.07;
                    p(l, "WITCH", v, 6, sp, sp, sp, 0.02);
                    p(l, "PORTAL", v, 8, sp, sp, sp, 0.3);
                }
            }
        }
    }

    // ----- target based abilities -----

    static void arc(ServerPlayer owner, LivingEntity target) {
        if (!on()) return;
        ServerLevel l = Compat.level(owner);
        Vec3 a = owner.getEyePosition().add(0, -0.3, 0), b = target.position().add(0, target.getBbHeight() / 2, 0);
        jag(l, "ELECTRIC_SPARK", a, b, 6, 0.9);
        p(l, "ELECTRIC_SPARK", b, 40, 0.5, 0.7, 0.5, 0.3);
        sound(l, b, "minecraft:entity.lightning_bolt.impact", 1.2f, 1.4f);
    }

    static void mark(ServerPlayer owner, LivingEntity target) {
        if (!on()) return;
        ServerLevel l = Compat.level(owner);
        Vec3 c = target.position();
        double h = target.getBbHeight();
        for (int k = 0; k < 3; k++) {
            ring(l, "ELECTRIC_SPARK", c.add(0, 0.2 + h * k / 2.0, 0), 0.8, 16);
        }
        column(l, "END_ROD", c.add(0, h + 0.2, 0), 0.2, 1.6, 12);
        line(l, "END_ROD", owner.getEyePosition().add(0, -0.3, 0), c.add(0, h / 2, 0), 24);
        sound(l, c, "minecraft:entity.experience_orb.pickup", 1.2f, 0.5f);
    }

    static void siphonBeam(ServerPlayer attacker, LivingEntity victim) {
        if (!on()) return;
        ServerLevel l = Compat.level(attacker);
        line(l, "HAPPY_VILLAGER", victim.position().add(0, 1, 0), attacker.position().add(0, 1, 0), 10);
        p(l, "HEART", attacker.position().add(0, 1.6, 0), 3, 0.3, 0.3, 0.3, 0);
    }

    static void venomHit(LivingEntity victim) {
        if (!on()) return;
        ServerLevel l = Compat.level(victim);
        Vec3 c = victim.position().add(0, victim.getBbHeight() / 2, 0);
        p(l, "SNEEZE", c, 30, 0.4, 0.6, 0.4, 0.05);
        spiral(l, "WITCH", victim.position(), 0.7, 2, 2, 20, 0);
        sound(l, c, "minecraft:entity.slime.squish", 1.2f, 0.5f);
    }

    // ================= slam / meteor =================

    static void slamStart(ServerPlayer p, boolean meteor) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 pos = p.position();
        if (meteor) {
            sound(l, pos, "minecraft:entity.ender_dragon.growl", 2.0f, 0.6f);
            sound(l, pos, "minecraft:entity.blaze.shoot", 2.0f, 0.5f);
            expandingRing(l, "FLAME", pos.add(0, 0.2, 0), 5, 6, 40);
            column(l, "FLAME", pos, 1.0, 4, 40);
            debris(l, pos, blockBelow(l, pos), 10, 3, 2.5, 0.4, false);
        } else {
            expandingRing(l, "CLOUD", pos.add(0, 0.2, 0), 3, 5, 24);
            p(l, "POOF", pos, 20, 0.5, 0.1, 0.5, 0.1);
            debris(l, pos, blockBelow(l, pos), 6, 2, 2, 0.3, false);
        }
    }

    static void slamRise(ServerPlayer p, boolean meteor) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 pos = p.position();
        if (meteor) {
            p(l, "FLAME", pos, 10, 0.4, 0.3, 0.4, 0.05);
            p(l, "LAVA", pos, 2, 0.4, 0.3, 0.4, 0);
        } else {
            p(l, "CLOUD", pos, 8, 0.4, 0.3, 0.4, 0.05);
        }
    }

    static void slamFall(ServerPlayer p, boolean meteor, int age) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 pos = p.position();
        PowerSettings s = PowerEngine.config.settings(Power.SMASH);
        double radius = meteor ? s.ultimateRadius : s.abilityRadius;
        Vec3 g = new Vec3(pos.x, groundY(l, pos), pos.z);
        if (age % 2 == 0) {
            ring(l, meteor ? "FLAME" : "CRIT", g.add(0, 0.1, 0), radius, 36);
            if (meteor) ring(l, "SOUL_FIRE_FLAME", g.add(0, 0.1, 0), radius * 0.5, 18);
        }
        if (meteor) {
            sphereShell(l, "FLAME", pos.add(0, 0.9, 0), 1.5, 26);
            p(l, "LARGE_SMOKE", pos.add(0, 2, 0), 6, 0.3, 0.7, 0.3, 0.02);
            p(l, "LAVA", pos.add(0, 1, 0), 3, 0.5, 0.5, 0.5, 0);
        } else {
            p(l, "CRIT", pos.add(0, 1, 0), 8, 0.3, 0.6, 0.3, 0.1);
            p(l, "CLOUD", pos.add(0, 2, 0), 4, 0.2, 0.5, 0.2, 0.02);
        }
    }

    static void slamImpact(ServerPlayer p, boolean meteor, double radius) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 g = p.position();
        String b = blockBelow(l, g);
        if (meteor) {
            sound(l, g, "minecraft:entity.generic.explode", 4.0f, 0.6f);
            sound(l, g, "minecraft:entity.dragon_fireball.explode", 3.0f, 0.7f);
            sound(l, g, "minecraft:block.anvil.land", 2.0f, 0.5f);
            for (int i = 0; i < 3; i++) {
                final int k = i;
                later(i * 3, () -> p(l, "EXPLOSION_EMITTER", g.add(0, 1 + k * 0.5, 0), 1, 0, 0, 0, 0));
            }
            ripple(l, g, radius, b);
            debris(l, g, b, 26, radius * 0.8, 6, 0.7, false);
            debris(l, g, "minecraft:magma_block", 14, radius * 0.6, 7, 0.5, true);
            expandingRing(l, "FLAME", g.add(0, 0.2, 0), radius * 1.3, 10, 70);
            expandingRing(l, "LARGE_SMOKE", g.add(0, 0.5, 0), radius * 1.1, 8, 50);
            pillar(l, g, "minecraft:orange_stained_glass", radius * 0.35, 12, 5, 12, true);
            column(l, "FLAME", g, radius * 0.3, 10, 40);
            p(l, "LAVA", g.add(0, 1, 0), 60, radius * 0.4, 1, radius * 0.4, 0);
            p(l, "LARGE_SMOKE", g.add(0, 3, 0), 40, radius * 0.3, 2, radius * 0.3, 0.05);
            later(4, () -> ring(l, "CLOUD", g.add(0, 6, 0), radius * 0.5, 30));
        } else {
            sound(l, g, "minecraft:entity.generic.explode", 2.0f, 0.9f);
            sound(l, g, "minecraft:block.anvil.land", 1.0f, 0.7f);
            ripple(l, g, radius, b);
            debris(l, g, b, 16, radius * 0.6, 3, 0.45, false);
            expandingRing(l, "CLOUD", g.add(0, 0.2, 0), radius, 6, 36);
            p(l, "EXPLOSION", g.add(0, 0.3, 0), 3, radius * 0.3, 0.1, radius * 0.3, 0);
            column(l, "POOF", g, 1.5, 3, 20);
        }
    }

    // ================= ultimates =================

    private static String blockFor(Power pw) {
        return switch (pw) {
            case TIDE -> "minecraft:light_blue_stained_glass";
            case EMBER -> "minecraft:orange_stained_glass";
            case GALE -> "minecraft:white_stained_glass";
            case STONE -> "minecraft:stone";
            case FROST -> "minecraft:packed_ice";
            case STORM -> "minecraft:yellow_stained_glass";
            case SHADE -> "minecraft:purple_stained_glass";
            case VITAL -> "minecraft:pink_stained_glass";
            case VENOM -> "minecraft:lime_stained_glass";
            case SMASH -> "minecraft:magma_block";
            case HUNTER -> "minecraft:green_stained_glass";
            case DRAGON -> "minecraft:purple_stained_glass";
        };
    }

    private static String particleFor(Power pw) {
        return switch (pw) {
            case TIDE -> "BUBBLE_POP";
            case EMBER -> "FLAME";
            case GALE -> "CLOUD";
            case STONE -> "ASH";
            case FROST -> "SNOWFLAKE";
            case STORM -> "ELECTRIC_SPARK";
            case SHADE -> "SQUID_INK";
            case VITAL -> "HEART";
            case VENOM -> "SNEEZE";
            case SMASH -> "FLAME";
            case HUNTER -> "END_ROD";
            case DRAGON -> "PORTAL";
        };
    }

    private static String soundFor(Power pw) {
        return switch (pw) {
            case TIDE -> "minecraft:item.trident.thunder";
            case EMBER -> "minecraft:entity.blaze.shoot";
            case GALE -> "minecraft:entity.wind_charge.wind_burst";
            case STONE -> "minecraft:entity.iron_golem.death";
            case FROST -> "minecraft:block.glass.break";
            case STORM -> "minecraft:entity.lightning_bolt.thunder";
            case SHADE -> "minecraft:entity.wither.spawn";
            case VITAL -> "minecraft:item.totem.use";
            case VENOM -> "minecraft:entity.evoker.prepare_summon";
            case SMASH -> "minecraft:entity.ender_dragon.growl";
            case HUNTER -> "minecraft:block.beacon.activate";
            case DRAGON -> "minecraft:entity.ender_dragon.growl";
        };
    }

    static void ultimate(ServerPlayer p, Power power) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 pos = p.position();
        PowerSettings s = PowerEngine.config.settings(power);
        String blk = blockFor(power), part = particleFor(power);
        sound(l, pos, soundFor(power), 2.0f, 0.9f);
        sound(l, pos, "minecraft:block.beacon.activate", 1.5f, 1.3f);
        pillar(l, pos, blk, 1.1, 14, 4, 16, true);
        expandingRing(l, part, pos.add(0, 0.2, 0), 9, 12, 70);
        expandingRing(l, "END_ROD", pos.add(0, 0.2, 0), 6, 8, 50);
        column(l, "END_ROD", pos, 0.6, 12, 30);
        debris(l, pos, blk, 14, 5, 5, 0.45, true);
        p(l, "TOTEM_OF_UNDYING", pos.add(0, 1, 0), 40, 0.6, 1.0, 0.6, 0.4);
        if (power == Power.HUNTER) radar(p, s.ultimateRadius);
    }

    /** Hunter's Eye: a huge ring sweeps outward and everything it passes lights up. */
    static void radar(ServerPlayer p, double radius) {
        ServerLevel l = Compat.level(p);
        Vec3 c = p.position().add(0, 0.2, 0);
        expandingRing(l, "END_ROD", c, radius, 14, 140);
        expandingRing(l, "ELECTRIC_SPARK", c.add(0, 1, 0), radius, 14, 100);
        for (LivingEntity e : Abilities.targetsAround(p, l, p.position(), radius)) {
            double dist = e.position().distanceTo(p.position());
            final Vec3 at = e.position();
            later((int) Math.min(14, dist / radius * 14), () -> {
                column(l, "END_ROD", at, 0.3, e.getBbHeight() + 1.5, 14);
                ring(l, "ELECTRIC_SPARK", at.add(0, 0.2, 0), 0.8, 14);
            });
        }
    }

    // ================= while an ultimate is running (every 2 ticks) =================

    static void aura(ServerPlayer p, Power power, int t) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 pos = p.position(), chest = pos.add(0, 1, 0);
        double ph = t * 0.25;
        switch (power) {
            case TIDE -> {
                spiral(l, "BUBBLE_POP", pos, 0.9, 2.2, 2, 10, ph);
                spiral(l, "DOLPHIN", pos, 1.1, 2.2, 2, 6, -ph);
                p(l, "FALLING_WATER", pos.add(0, 2.2, 0), 4, 0.4, 0.1, 0.4, 0);
            }
            case GALE -> {
                spiral(l, "CLOUD", pos, 1.1, 2.4, 2, 10, ph);
                ring(l, "END_ROD", pos.add(0, 0.1, 0), 1.3, 10);
            }
            case STONE -> {
                p(l, "ASH", chest, 6, 0.6, 0.9, 0.6, 0);
                ring(l, "CRIT", pos.add(0, 0.1, 0), 1.2, 10);
                if (t % 10 == 0) debris(l, pos, blockBelow(l, pos), 3, 1.5, 1.2, 0.25, false);
            }
            case SHADE -> {
                spiral(l, "SQUID_INK", pos, 0.8, 2.2, 2, 10, ph);
                p(l, "SCULK_SOUL", chest, 2, 0.5, 0.8, 0.5, 0.01);
            }
            case VITAL -> {
                spiral(l, "GLOW", pos, 0.9, 2.2, 2, 8, ph);
                if (t % 10 == 0) {
                    expandingRing(l, "HEART", pos.add(0, 0.2, 0), 3.5, 4, 14);
                    column(l, "END_ROD", pos, 0.5, 3, 8);
                }
            }
            case STORM -> {
                Vec3 sky = pos.add(0, 9, 0);
                p(l, "LARGE_SMOKE", sky, 10, 3.5, 0.5, 3.5, 0);
                p(l, "ELECTRIC_SPARK", sky, 4, 3.5, 0.5, 3.5, 0.2);
                if (t % 8 == 0) {
                    Vec3 top = sky.add((R.nextDouble() - 0.5) * 6, 0, (R.nextDouble() - 0.5) * 6);
                    Vec3 low = new Vec3(top.x, groundY(l, top) , top.z);
                    jag(l, "ELECTRIC_SPARK", top, low, 8, 1.2);
                }
            }
            case DRAGON -> wings(p, t);
            default -> { }
        }
    }

    /** Two flapping wings drawn with particles, following the player. */
    static void wings(ServerPlayer p, int t) {
        ServerLevel l = Compat.level(p);
        Vec3 look = p.getLookAngle();
        Vec3 fwd = new Vec3(look.x, 0, look.z);
        if (fwd.lengthSqr() < 1e-4) fwd = new Vec3(0, 0, 1);
        fwd = fwd.normalize();
        Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 base = p.position().add(0, 1.3, 0).subtract(fwd.scale(0.25));
        double flap = Math.sin(t * 0.35) * 0.45;
        double[] spread = { 0.25, 0.7, 1.1, 1.45 };
        double[] len = { 1.7, 2.3, 2.2, 1.6 };
        for (int side = -1; side <= 1; side += 2) {
            Vec3 shoulder = base.add(right.scale(0.2 * side));
            Vec3 prev = null;
            for (int i = 0; i < 4; i++) {
                double a = spread[i] + flap;
                Vec3 dir = right.scale(side * Math.sin(a)).add(up.scale(Math.cos(a) * 0.9)).subtract(fwd.scale(0.5)).normalize();
                Vec3 tip = shoulder.add(dir.scale(len[i]));
                line(l, "END_ROD", shoulder, tip, 5);
                if (prev != null) line(l, "PORTAL", prev, tip, 5);
                prev = tip;
            }
        }
        p(l, "WITCH", p.position().add(0, 1, 0), 2, 0.4, 0.5, 0.4, 0);
    }

    // ================= zones (every 2 ticks) =================

    static void zoneAmbient(Zone z, int t) {
        if (!on() || z.level == null) return;
        ServerLevel l = z.level;
        Vec3 c = z.pos;
        double r = z.radius, ph = t * 0.35;
        switch (z.kind) {
            case FIRE -> {
                for (int k = 0; k < 3; k++) {
                    double a0 = ph + k * 2.09;
                    for (int i = 0; i < 6; i++) {
                        double h = i * (2.8 / 6.0);
                        double rad = 0.5 + h * r * 0.15;
                        double a = a0 + h * 1.3;
                        p(l, "FLAME", c.x + Math.cos(a) * rad, c.y + h, c.z + Math.sin(a) * rad, 1, 0, 0, 0, 0);
                    }
                }
                ring(l, "FLAME", c.add(0, 0.1, 0), r, 30);
                for (int i = 0; i < 4; i++) {
                    double a = R.nextDouble() * Math.PI * 2, d = r * Math.sqrt(R.nextDouble());
                    p(l, "SMALL_FLAME", c.x + Math.cos(a) * d, c.y + 0.2, c.z + Math.sin(a) * d, 1, 0, 0.1, 0, 0.05);
                }
                if (t % 8 == 0) {
                    double a = R.nextDouble() * Math.PI * 2, d = r * Math.sqrt(R.nextDouble());
                    pillar(l, new Vec3(c.x + Math.cos(a) * d, c.y, c.z + Math.sin(a) * d), "minecraft:orange_stained_glass", 0.4, 3.5, 3, 3, true);
                }
            }
            case TOXIC -> {
                for (int i = 0; i < 8; i++) {
                    double a = R.nextDouble() * Math.PI * 2, d = r * Math.sqrt(R.nextDouble());
                    Vec3 v = new Vec3(c.x + Math.cos(a) * d, c.y + R.nextDouble() * 2, c.z + Math.sin(a) * d);
                    p(l, "SNEEZE", v, 2, 0.5, 0.3, 0.5, 0.01);
                }
                p(l, "WITCH", c.add(0, 1, 0), 6, r * 0.5, 0.8, r * 0.5, 0);
                ring(l, "WITCH", c.add(0, 0.2, 0), r, 28);
                p(l, "BUBBLE_POP", c.add(0, 0.5, 0), 3, r * 0.5, 0.3, r * 0.5, 0.02);
            }
            case FROST -> {
                for (int i = 0; i < 14; i++) {
                    double a = R.nextDouble() * Math.PI * 2, d = r * Math.sqrt(R.nextDouble());
                    p(l, "SNOWFLAKE", c.x + Math.cos(a) * d, c.y + 1 + R.nextDouble() * 6, c.z + Math.sin(a) * d, 0, 0, -1, 0, 0.15);
                }
                spiral(l, "SNOWFLAKE", c, r * 0.8, 3, 2, 20, ph);
                spiral(l, "CLOUD", c, r * 0.5, 2.5, 2, 8, -ph);
                ring(l, "SNOWFLAKE", c.add(0, 0.1, 0), r, 36);
                if (t % 12 == 0) spikes(l, c, r, 3);
            }
            case DRAGON -> {
                for (int i = 0; i < 8; i++) {
                    double a = R.nextDouble() * Math.PI * 2, d = r * Math.sqrt(R.nextDouble());
                    Vec3 v = new Vec3(c.x + Math.cos(a) * d, c.y + R.nextDouble() * 2.5, c.z + Math.sin(a) * d);
                    p(l, "WITCH", v, 2, 0.3, 0.3, 0.3, 0.01);
                }
                spiral(l, "PORTAL", c, r * 0.6, 3, 2, 20, ph);
                ring(l, "REVERSE_PORTAL", c.add(0, 0.1, 0), r, 24);
            }
        }
    }
}
