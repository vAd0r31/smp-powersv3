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

    static String f(double v) {
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
            case SAND -> {
                double r = s.abilityRadius;
                sound(l, pos, "minecraft:block.sand.break", 1.6f, 0.6f);
                sound(l, pos, "minecraft:entity.wind_charge.wind_burst", 1.0f, 0.6f);
                expandingRing(l, "CLOUD", pos.add(0, 0.3, 0), r, 6, 44);
                expandingRing(l, "ASH", pos.add(0, 0.8, 0), r * 0.8, 6, 36);
                spiral(l, "ASH", pos, r * 0.5, 3, 3, 30, 0);
                debris(l, pos, "minecraft:sand", 16, r, 2.6, 0.35, false);
                debris(l, pos, "minecraft:sandstone", 8, r, 1.8, 0.3, false);
            }
            case REAPER -> {
                sound(l, pos, "minecraft:entity.wither.shoot", 1.2f, 0.6f);
                p(l, "SCULK_SOUL", chest, 24, 0.5, 0.9, 0.5, 0.03);
                spiral(l, "SOUL_FIRE_FLAME", pos, 0.8, 2.4, 3, 20, 0);
                expandingRing(l, "SOUL", pos.add(0, 0.2, 0), 2.5, 5, 20);
            }
            case ECHO -> {
                double r = s.abilityRadius;
                sound(l, pos, "minecraft:entity.warden.sonic_boom", 1.4f, 1.3f);
                p(l, "SONIC_BOOM", chest, 1, 0, 0, 0, 0);
                expandingRing(l, "SONIC_BOOM", pos.add(0, 1, 0), r, 5, 8);
                expandingRing(l, "SCULK_SOUL", pos.add(0, 0.3, 0), r, 7, 40);
                expandingRing(l, "ELECTRIC_SPARK", pos.add(0, 1.2, 0), r * 0.7, 6, 24);
            }
            case GRAVITY -> gravityPull(p, s.abilityRadius);
            case RIFT -> sound(l, pos, "minecraft:entity.enderman.teleport", 1.0f, 1.6f);
            case ILLUSION -> {
                sound(l, pos, "minecraft:entity.illusioner.cast_spell", 1.4f, 1.0f);
                p(l, "WITCH", chest, 30, 0.5, 0.9, 0.5, 0.05);
                spiral(l, "ENCHANT", pos, 0.8, 2.2, 2, 20, 0);
            }
            default -> { }
        }
        if (!power.secret()) flourish(p, power, false, s.abilityRadius);
    }

    // ---- Illusion: stick-figure decoys made of particles ----

    private static void figure(ServerLevel l, Vec3 b, String n) {
        p(l, n, b.add(0, 1.75, 0), 5, 0.1, 0.1, 0.1, 0);
        line(l, n, b.add(0, 0.8, 0), b.add(0, 1.5, 0), 6);
        line(l, n, b.add(-0.45, 1.25, 0), b.add(0.45, 1.25, 0), 5);
        line(l, n, b.add(0, 0.8, 0), b.add(-0.2, 0, 0), 5);
        line(l, n, b.add(0, 0.8, 0), b.add(0.2, 0, 0), 5);
    }

    /** Leaves 3 glowing copies where the player was standing. */
    static void decoys(ServerPlayer p) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 c = p.position();
        Vec3[] spots = { c, c.add(2, 0, 0.5), c.add(-1.5, 0, 1.5), c.add(0.5, 0, -2) };
        for (int k = 0; k < 16; k++) {
            later(k * 2, () -> {
                for (Vec3 s : spots) figure(l, s, "END_ROD");
            });
        }
        p(l, "POOF", c.add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.05);
    }

    /** Ultimate Illusion: a decoy flickers in somewhere random near the caster. */
    private static void flicker(ServerPlayer p) {
        ServerLevel l = Compat.level(p);
        double a = R.nextDouble() * Math.PI * 2, d = 2 + R.nextDouble() * 5;
        Vec3 at = p.position().add(Math.cos(a) * d, 0, Math.sin(a) * d);
        for (int k = 0; k < 6; k++) later(k * 2, () -> figure(l, at, "ENCHANT"));
    }

    // ---- Rift ----

    static void blinkFx(ServerLevel l, Vec3 from, Vec3 to) {
        if (!on()) return;
        sound(l, from, "minecraft:entity.enderman.teleport", 1.2f, 1.4f);
        sound(l, to, "minecraft:entity.enderman.teleport", 1.2f, 0.9f);
        p(l, "REVERSE_PORTAL", from.add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.2);
        line(l, "PORTAL", from.add(0, 1, 0), to.add(0, 1, 0), 20);
        ring(l, "PORTAL", to.add(0, 0.2, 0), 1.0, 16);
        p(l, "REVERSE_PORTAL", to.add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.2);
    }

    static void riftSwapFx(ServerLevel l, Vec3 a, Vec3 b) {
        if (!on()) return;
        sound(l, a, "minecraft:entity.enderman.teleport", 1.3f, 0.7f);
        sound(l, b, "minecraft:entity.enderman.teleport", 1.3f, 0.7f);
        column(l, "REVERSE_PORTAL", a, 0.4, 2.5, 20);
        column(l, "REVERSE_PORTAL", b, 0.4, 2.5, 20);
        line(l, "PORTAL", a.add(0, 1, 0), b.add(0, 1, 0), 30);
        ring(l, "PORTAL", a.add(0, 0.2, 0), 1.2, 16);
        ring(l, "PORTAL", b.add(0, 0.2, 0), 1.2, 16);
    }

    // ---- Sand, Reaper, Echo ----

    /** Burial: a sand tower rises around the target. */
    static void bury(ServerPlayer owner, LivingEntity target, int ticks) {
        if (!on()) return;
        ServerLevel l = Compat.level(target);
        Vec3 pos = target.position();
        sound(l, pos, "minecraft:block.sand.break", 1.8f, 0.5f);
        sound(l, pos, "minecraft:entity.generic.explode", 1.0f, 0.6f);
        pillar(l, pos, "minecraft:sandstone", 1.5, 2.8, 8, Math.max(20, ticks - 10), false);
        debris(l, pos, "minecraft:sand", 20, 1.8, 3.2, 0.4, false);
        column(l, "ASH", pos, 0.9, 3, 30);
        expandingRing(l, "CLOUD", pos.add(0, 0.2, 0), 3, 6, 30);
    }

    static void execute(LivingEntity e) {
        if (!on()) return;
        ServerLevel l = Compat.level(e);
        Vec3 pos = e.position();
        sound(l, pos, "minecraft:entity.wither.break_block", 0.9f, 0.7f);
        column(l, "SOUL_FIRE_FLAME", pos, 0.4, e.getBbHeight() + 1.5, 20);
        p(l, "SOUL", pos.add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.05);
        p(l, "SCULK_SOUL", pos.add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.05);
    }

    static void echoPulse(ServerPlayer p, double radius) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 pos = p.position();
        sound(l, pos, "minecraft:entity.warden.sonic_boom", 1.5f, 1.2f);
        expandingRing(l, "SONIC_BOOM", pos.add(0, 1, 0), radius, 6, 10);
        expandingRing(l, "SCULK_SOUL", pos.add(0, 0.3, 0), radius, 8, 40);
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
            case SAND -> "minecraft:sand";
            case REAPER -> "minecraft:black_stained_glass";
            case ECHO -> "minecraft:sculk";
            case MIMIC -> "minecraft:magenta_stained_glass";
            case GRAVITY -> "minecraft:obsidian";
            case RIFT -> "minecraft:blue_stained_glass";
            case ILLUSION -> "minecraft:light_gray_stained_glass";
            case GOJO -> "minecraft:light_blue_stained_glass";
            case SUKUNA -> "minecraft:red_stained_glass";
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
            case SAND -> "ASH";
            case REAPER -> "SOUL_FIRE_FLAME";
            case ECHO -> "SCULK_SOUL";
            case MIMIC -> "END_ROD";
            case GRAVITY -> "REVERSE_PORTAL";
            case RIFT -> "PORTAL";
            case ILLUSION -> "ENCHANT";
            case GOJO -> "SOUL_FIRE_FLAME";
            case SUKUNA -> "FLAME";
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
            case SAND -> "minecraft:block.sand.break";
            case REAPER -> "minecraft:entity.wither.spawn";
            case ECHO -> "minecraft:entity.warden.sonic_boom";
            case MIMIC -> "minecraft:block.beacon.activate";
            case GRAVITY -> "minecraft:entity.enderman.teleport";
            case RIFT -> "minecraft:entity.enderman.teleport";
            case ILLUSION -> "minecraft:entity.illusioner.cast_spell";
            case GOJO -> "minecraft:block.beacon.activate";
            case SUKUNA -> "minecraft:entity.wither.spawn";
        };
    }

    static void ultimate(ServerPlayer p, Power power) {
        if (!on()) return;
        if (power.secret()) return; // Gojo and Sukuna have their own charge-up effects (see Jujutsu)
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
        flourish(p, power, true, s.ultimateRadius);
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
            case REAPER -> {
                spiral(l, "SOUL_FIRE_FLAME", pos, 0.9, 2.4, 2, 8, ph);
                p(l, "SCULK_SOUL", chest, 2, 0.5, 0.8, 0.5, 0.01);
                if (t % 20 == 0) expandingRing(l, "SOUL", pos.add(0, 0.2, 0), 8, 6, 30);
            }
            case ECHO -> {
                spiral(l, "SCULK_SOUL", pos, 1.0, 2.2, 2, 8, ph);
                ring(l, "ELECTRIC_SPARK", pos.add(0, 0.1, 0), 1.4, 10);
            }
            case ILLUSION -> {
                spiral(l, "ENCHANT", pos, 0.9, 2.4, 2, 10, ph);
                if (t % 20 == 0) flicker(p);
            }
            case RIFT -> {
                spiral(l, "REVERSE_PORTAL", pos, 0.9, 2.2, 2, 10, ph);
                ring(l, "PORTAL", pos.add(0, 0.1, 0), 1.3, 12);
            }
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

    // ================= Gravity, Gojo and Sukuna effects =================

    /** Rotates the vector (x, y, z) by the quaternion q = {x, y, z, w}. */
    private static double[] rot(double[] q, double x, double y, double z) {
        double tx = 2 * (q[1] * z - q[2] * y), ty = 2 * (q[2] * x - q[0] * z), tz = 2 * (q[0] * y - q[1] * x);
        return new double[] {
                x + q[3] * tx + (q[1] * tz - q[2] * ty),
                y + q[3] * ty + (q[2] * tx - q[0] * tz),
                z + q[3] * tz + (q[0] * ty - q[1] * tx) };
    }

    /** Turns a centered cube of side s around its middle (the translation keeps the middle where it is). */
    private static void spin(String id, int delay, int dur, double s, double ang, double ax, double ay, double az) {
        if (id == null) return;
        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-6) { ax = 0; ay = 1; az = 0; len = 1; }
        double sn = Math.sin(ang / 2);
        double[] q = { ax / len * sn, ay / len * sn, az / len * sn, Math.cos(ang / 2) };
        double[] c = rot(q, s / 2, s / 2, s / 2);
        String r = "[" + f(q[0]) + "f," + f(q[1]) + "f," + f(q[2]) + "f," + f(q[3]) + "f]";
        morph(id, delay, dur, s, s, s, -c[0], -c[1], -c[2], r);
    }

    /** A glowing ball made of two spinning, pulsing cubes (a dark core inside a glass shell). */
    static void orb(ServerLevel l, Vec3 c, String coreBlock, String shellBlock, double size, int life, boolean collapse) {
        if (!on()) return;
        double k = size * 0.55;
        String shell = spawn(l, c, shellBlock, 0.05, 0.05, 0.05, -0.025, -0.025, -0.025, true);
        String core = spawn(l, c, coreBlock, 0.05, 0.05, 0.05, -0.025, -0.025, -0.025, true);
        spin(shell, 1, 4, size, 0, 0, 1, 0);
        spin(core, 1, 4, k, 0, 0, 1, 0);
        for (int i = 4; i < life; i += 4) {
            double wob = 1.0 + 0.07 * Math.sin(i * 0.9);
            spin(shell, i + 1, 4, size * wob, i * 0.45, 0.3, 1, 0.2);
            spin(core, i + 1, 4, k * wob, -i * 0.6, 1, 0.3, 0.4);
        }
        if (collapse) {
            spin(shell, life, 5, 0.05, 6, 0, 1, 0);
            spin(core, life, 5, 0.05, 6, 0, 1, 0);
        }
        kill(shell, life + 6);
        kill(core, life + 6);
    }

    /** The same kind of ball, flying from one point to another. */
    static void flyOrb(ServerLevel l, Vec3 from, Vec3 to, int ticks, String coreBlock, String shellBlock, double size, boolean fadeEnd) {
        if (!on()) return;
        double k = size * 0.55;
        Vec3 d = to.subtract(from);
        String shell = spawn(l, from, shellBlock, size, size, size, -size / 2, -size / 2, -size / 2, true);
        String core = spawn(l, from, coreBlock, k, k, k, -k / 2, -k / 2, -k / 2, true);
        morph(shell, 1, ticks, size, size, size, d.x - size / 2, d.y - size / 2, d.z - size / 2, NO_ROT);
        morph(core, 1, ticks, k, k, k, d.x - k / 2, d.y - k / 2, d.z - k / 2, NO_ROT);
        if (fadeEnd) {
            morph(shell, ticks + 1, 4, 0.05, 0.05, 0.05, d.x - 0.025, d.y - 0.025, d.z - 0.025, NO_ROT);
            morph(core, ticks + 1, 4, 0.05, 0.05, 0.05, d.x - 0.025, d.y - 0.025, d.z - 0.025, NO_ROT);
        }
        kill(shell, ticks + 6);
        kill(core, ticks + 6);
    }

    /** A flat ring that can be tilted around the x axis. */
    static void disk(ServerLevel l, Vec3 c, double r, double tilt, double phase, String n, int pts) {
        double st = Math.sin(tilt), ct = Math.cos(tilt);
        for (int i = 0; i < pts; i++) {
            double a = phase + i * Math.PI * 2 / pts;
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            p(l, n, c.x + x, c.y + z * st, c.z + z * ct, 1, 0, 0, 0, 0);
        }
    }

    /** A ring around a direction (for beams and arrows). */
    static void perpRing(ServerLevel l, String n, Vec3 c, Vec3 dir, double r, int pts, double phase) {
        Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 a = dir.cross(up).normalize(), b = dir.cross(a).normalize();
        for (int i = 0; i < pts; i++) {
            double ang = phase + i * Math.PI * 2 / pts;
            Vec3 v = c.add(a.scale(Math.cos(ang) * r)).add(b.scale(Math.sin(ang) * r));
            p(l, n, v.x, v.y, v.z, 1, 0, 0, 0, 0);
        }
    }

    /** Particles that start out on a big sphere and fly into the middle. */
    static void inflow(ServerLevel l, Vec3 c, double r, int n, String name, double speed) {
        for (int i = 0; i < n; i++) {
            double y = R.nextDouble() * 2 - 1, a = R.nextDouble() * Math.PI * 2, rad = Math.sqrt(1 - y * y);
            Vec3 d = new Vec3(Math.cos(a) * rad, y, Math.sin(a) * rad);
            Vec3 s0 = c.add(d.scale(r * (0.5 + 0.5 * R.nextDouble())));
            p(l, name, s0.x, s0.y, s0.z, 0, -d.x, -d.y, -d.z, speed);
        }
    }

    /** A chunk of block pulled into a point while shrinking. */
    private static void pull(ServerLevel l, Vec3 from, Vec3 to, String block, double size, int ticks) {
        Vec3 d = to.subtract(from);
        String id = spawn(l, from, block, size, size, size, -size / 2, -size / 2, -size / 2, false);
        morph(id, 1, ticks, 0.05, 0.05, 0.05, d.x, d.y, d.z, quat());
        kill(id, ticks + 2);
    }

    private static Vec3 randomInSphere(Vec3 c, double r) {
        double y = R.nextDouble() * 2 - 1, a = R.nextDouble() * Math.PI * 2, rad = Math.sqrt(1 - y * y);
        double d = r * Math.cbrt(R.nextDouble());
        return c.add(Math.cos(a) * rad * d, y * d, Math.sin(a) * rad * d);
    }

    // ---- Gravity: Pull and Singularity ----

    /** Pull: a small black hole forms in front of you, spins up, then implodes. */
    static void gravityPull(ServerPlayer p, double r) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 pos = p.position(), look = p.getLookAngle();
        Vec3 c = pos.add(0, 1.3, 0).add(look.x * 2.2, 0, look.z * 2.2);
        sound(l, pos, "minecraft:entity.enderman.teleport", 1.3f, 0.4f);
        sound(l, pos, "minecraft:block.respawn_anchor.charge", 1.4f, 0.6f);
        sound(l, pos, "minecraft:entity.warden.sonic_boom", 0.7f, 0.5f);
        orb(l, c, "minecraft:black_concrete", "minecraft:purple_stained_glass", 1.4, 14, true);
        for (int k = 0; k < 7; k++) {
            final int kk = k;
            later(k * 2, () -> {
                double rr = r * (1.0 - kk / 7.0);
                disk(l, c, Math.max(0.8, rr), 0.35, kk * 0.9, "REVERSE_PORTAL", 34);
                disk(l, c, Math.max(0.6, rr * 0.7), -0.5, -kk * 0.9, "PORTAL", 22);
                sphereShell(l, "SQUID_INK", c, Math.max(0.5, rr * 0.35), 14);
                inflow(l, c, r, 16, "REVERSE_PORTAL", 0.55);
                p(l, "END_ROD", c, 3, 0.4, 0.4, 0.4, 0);
            });
        }
        later(14, () -> {
            expandingRing(l, "END_ROD", c, r * 0.9, 6, 48);
            expandingRing(l, "PORTAL", c.add(0, -1.0, 0), r, 7, 56);
            p(l, "REVERSE_PORTAL", c, 60, 0.3, 0.3, 0.3, 0.8);
            p(l, "SONIC_BOOM", c, 1, 0, 0, 0, 0);
            debris(l, c, "minecraft:obsidian", 10, r * 0.5, 2.0, 0.35, false);
            sound(l, pos, "minecraft:entity.generic.explode", 1.0f, 0.6f);
        });
    }

    /** Singularity: a black hole with a glowing accretion disk, jets, lightning and rocks being pulled in. */
    static void blackHole(ServerLevel l, Vec3 c, double r, int t) {
        Vec3 cc = c.add(0, 3.2, 0);
        double ph = t * 0.22;
        if (t % 20 == 0) {
            orb(l, cc, "minecraft:black_concrete", "minecraft:purple_stained_glass", 3.0, 22, false);
            sound(l, cc, "minecraft:block.beacon.ambient", 1.2f, 0.5f);
        }
        for (int k = 0; k < 4; k++) {
            double rr = 1.8 + k * (r * 0.8 - 1.8) / 3.0;
            disk(l, cc, rr, 0.28, ph * (1.6 - k * 0.3), k % 2 == 0 ? "REVERSE_PORTAL" : "PORTAL", 10 + k * 8);
        }
        disk(l, cc, 2.0, -0.6, -ph * 2, "END_ROD", 16);
        p(l, "END_ROD", cc.x, cc.y + 1.5, cc.z, 0, 0, 1, 0, 0.35);
        p(l, "END_ROD", cc.x, cc.y - 1.5, cc.z, 0, 0, -1, 0, 0.35);
        p(l, "REVERSE_PORTAL", cc.x, cc.y + 1.5, cc.z, 0, 0, 1, 0, 0.5);
        p(l, "REVERSE_PORTAL", cc.x, cc.y - 1.5, cc.z, 0, 0, -1, 0, 0.5);
        inflow(l, cc, r, 12, "REVERSE_PORTAL", 0.5);
        p(l, "SQUID_INK", cc, 6, 0.8, 0.8, 0.8, 0.02);
        double ring = r * (1.0 - (t % 30) / 30.0);
        ring(l, "PORTAL", c.add(0, 0.15, 0), Math.max(1, ring), 40);
        if (t % 6 == 0) {
            double a = R.nextDouble() * Math.PI * 2, rr = r * (0.5 + 0.4 * R.nextDouble());
            Vec3 from = new Vec3(c.x + Math.cos(a) * rr, c.y + 0.6, c.z + Math.sin(a) * rr);
            pull(l, from, cc, R.nextBoolean() ? "minecraft:obsidian" : "minecraft:crying_obsidian", 0.45, 12);
        }
        if (t % 10 == 0) {
            double a = R.nextDouble() * Math.PI * 2;
            Vec3 rim = new Vec3(c.x + Math.cos(a) * r * 0.9, c.y + 1 + R.nextDouble() * 3, c.z + Math.sin(a) * r * 0.9);
            jag(l, "ELECTRIC_SPARK", rim, cc, 7, 1.2);
        }
    }

    // ---- Gojo ----

    static void blueCast(ServerPlayer p, Vec3 c, int life) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        sound(l, c, "minecraft:block.beacon.activate", 1.6f, 1.8f);
        sound(l, c, "minecraft:entity.illusioner.cast_spell", 1.4f, 1.8f);
        sound(l, c, "minecraft:block.respawn_anchor.charge", 1.4f, 1.3f);
        orb(l, c, "minecraft:blue_concrete", "minecraft:light_blue_stained_glass", 1.6, life, true);
        orb(l, c, "minecraft:sea_lantern", "minecraft:white_stained_glass", 0.7, life, true);
        expandingRing(l, "SOUL_FIRE_FLAME", c, 3.5, 5, 32);
        expandingRing(l, "END_ROD", c, 2.5, 4, 24);
    }

    /** Lapse Blue, every 2 ticks while it lives. */
    static void blueOrb(ServerLevel l, Vec3 c, int age, double r) {
        if (!on()) return;
        double ph = age * 0.5;
        disk(l, c, 1.6, 0.5, ph, "SOUL_FIRE_FLAME", 12);
        disk(l, c, 2.4, -0.4, -ph * 0.8, "ELECTRIC_SPARK", 10);
        disk(l, c, 3.2, 0.1, ph * 0.6, "SOUL_FIRE_FLAME", 14);
        inflow(l, c, r, 8, "SOUL_FIRE_FLAME", 0.55);
        inflow(l, c, r * 0.6, 5, "END_ROD", 0.4);
        p(l, "END_ROD", c, 2, 0.3, 0.3, 0.3, 0.02);
        if (age % 10 == 0) sound(l, c, "minecraft:block.beacon.ambient", 1.0f, 1.8f);
    }

    /** Reversal Red: a red ball shoots out, then detonates. */
    static void redBlast(ServerLevel l, Vec3 from, Vec3 hit, double blast) {
        if (!on()) return;
        Vec3 d = hit.subtract(from);
        int ticks = Math.max(2, (int) Math.ceil(d.length() / 6.0));
        sound(l, from, "minecraft:entity.blaze.shoot", 1.6f, 0.5f);
        sound(l, from, "minecraft:entity.firework_rocket.blast", 1.6f, 0.5f);
        flyOrb(l, from, hit, ticks, "minecraft:redstone_block", "minecraft:red_stained_glass", 1.2, false);
        for (int i = 1; i <= ticks; i++) {
            final Vec3 v = from.lerp(hit, (double) i / ticks);
            later(i, () -> {
                p(l, "CRIMSON_SPORE", v, 8, 0.3, 0.3, 0.3, 0.05);
                p(l, "LAVA", v, 2, 0.2, 0.2, 0.2, 0);
                p(l, "ELECTRIC_SPARK", v, 3, 0.3, 0.3, 0.3, 0.1);
            });
        }
        later(ticks, () -> {
            sound(l, hit, "minecraft:entity.generic.explode", 2.0f, 0.7f);
            sound(l, hit, "minecraft:entity.firework_rocket.large_blast", 1.5f, 0.6f);
            p(l, "EXPLOSION_EMITTER", hit, 1, 0, 0, 0, 0);
            expandingRing(l, "CRIMSON_SPORE", hit, blast, 6, 50);
            expandingRing(l, "FLAME", hit.add(0, 0.3, 0), blast * 0.8, 5, 40);
            for (int k = 1; k <= 5; k++) {
                final double rr = blast * k / 5.0;
                later(k, () -> sphereShell(l, "ELECTRIC_SPARK", hit, rr, 40));
            }
            p(l, "LAVA", hit, 20, 1.0, 1.0, 1.0, 0.1);
            debris(l, hit, "minecraft:red_nether_bricks", 14, blast, 4, 0.5, true);
            ripple(l, new Vec3(hit.x, groundY(l, hit), hit.z), blast, "minecraft:redstone_block");
            orb(l, hit, "minecraft:redstone_block", "minecraft:red_stained_glass", blast * 0.6, 6, true);
        });
    }

    static void infinityOn(ServerPlayer p) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 c = p.position().add(0, 1, 0);
        sound(l, c, "minecraft:block.beacon.power_select", 1.5f, 1.6f);
        sound(l, c, "minecraft:entity.illusioner.cast_spell", 1.2f, 1.9f);
        expandingRing(l, "END_ROD", p.position().add(0, 0.2, 0), 4, 6, 40);
        for (int k = 1; k <= 5; k++) {
            final double rr = 0.8 + k * 0.5;
            later(k, () -> sphereShell(l, "SOUL_FIRE_FLAME", c, rr, 50));
        }
    }

    static void infinityOff(ServerPlayer p) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 c = p.position().add(0, 1, 0);
        sound(l, c, "minecraft:block.glass.break", 1.0f, 1.5f);
        sphereShell(l, "END_ROD", c, 3, 60);
    }

    /** The invisible barrier, drawn as slowly turning rings and sparks. */
    static void infinityShell(ServerLevel l, Vec3 c, double r, int t) {
        if (!on()) return;
        double ph = t * 0.08;
        for (int k = -2; k <= 2; k++) {
            double y = k * r * 0.3;
            double rr = Math.sqrt(Math.max(0.01, r * r - y * y));
            for (int i = 0; i < 10; i++) {
                double a = ph * (k % 2 == 0 ? 1 : -1) + i * Math.PI / 5;
                p(l, "END_ROD", c.x + Math.cos(a) * rr, c.y + y, c.z + Math.sin(a) * rr, 1, 0, 0, 0, 0);
            }
        }
        sphereShell(l, "ELECTRIC_SPARK", c, r, 8);
    }

    static void infinityRipple(ServerLevel l, Vec3 at) {
        if (!on()) return;
        p(l, "ELECTRIC_SPARK", at, 6, 0.3, 0.3, 0.3, 0.15);
        p(l, "END_ROD", at, 3, 0.2, 0.2, 0.2, 0.05);
    }

    /** Hollow Purple, start of the charge-up: a blue orb and a red orb fly together in front of Gojo. */
    static void purpleStart(ServerPlayer p, int windup) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0, look.x);
        right = right.lengthSqr() < 0.0001 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 center = eye.add(look.scale(3.0)).add(0, -0.1, 0);
        Vec3 leftPos = center.subtract(right.scale(3.0)).add(0, 0.5, 0);
        Vec3 rightPos = center.add(right.scale(3.0)).add(0, -0.3, 0);
        sound(l, eye, "minecraft:block.beacon.activate", 2.0f, 0.6f);
        sound(l, eye, "minecraft:entity.evoker.prepare_summon", 1.6f, 0.6f);
        flyOrb(l, leftPos, center, windup, "minecraft:blue_concrete", "minecraft:light_blue_stained_glass", 1.4, false);
        flyOrb(l, rightPos, center, windup, "minecraft:red_concrete", "minecraft:red_stained_glass", 1.4, false);
        later(Math.max(1, windup - 3), () -> {
            sound(l, center, "minecraft:entity.generic.explode", 1.5f, 0.5f);
            p(l, "END_ROD", center, 50, 0.6, 0.6, 0.6, 0.3);
            orb(l, center, "minecraft:black_concrete", "minecraft:purple_stained_glass", 3.0, 8, false);
        });
    }

    static void purpleCharge(ServerPlayer p, int age, int windup) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0, look.x);
        right = right.lengthSqr() < 0.0001 ? new Vec3(1, 0, 0) : right.normalize();
        double prog = Math.min(1.0, age / (double) windup);
        Vec3 center = eye.add(look.scale(3.0)).add(0, -0.1, 0);
        Vec3 left = center.subtract(right.scale(3.0 * (1 - prog))).add(0, 0.5 * (1 - prog), 0);
        Vec3 rp = center.add(right.scale(3.0 * (1 - prog))).add(0, -0.3 * (1 - prog), 0);
        disk(l, left, 0.9, 0.5, age * 0.6, "SOUL_FIRE_FLAME", 10);
        disk(l, rp, 0.9, -0.5, -age * 0.6, "CRIMSON_SPORE", 10);
        p(l, "ELECTRIC_SPARK", left, 2, 0.4, 0.4, 0.4, 0.05);
        p(l, "FLAME", rp, 2, 0.4, 0.4, 0.4, 0.02);
        if (prog > 0.45) {
            disk(l, center, 1.4 * prog, 0.3, age * 0.8, "WITCH", 14);
            inflow(l, center, 4.0, 10, "PORTAL", 0.5);
            p(l, "REVERSE_PORTAL", center, 4, 0.4, 0.4, 0.4, 0.2);
        }
        ring(l, "END_ROD", p.position().add(0, 0.1, 0), 0.8 + 2.2 * prog, 18);
        if (age % 10 == 0) sound(l, center, "minecraft:block.beacon.ambient", 1.4f, 0.5f + (float) prog);
    }

    static void purpleFire(ServerLevel l, Vec3 origin, Vec3 dir, double range, double width, int travel) {
        if (!on()) return;
        Vec3 end = origin.add(dir.scale(range));
        sound(l, origin, "minecraft:entity.warden.sonic_boom", 3.0f, 0.5f);
        sound(l, origin, "minecraft:entity.ender_dragon.growl", 2.0f, 1.2f);
        sound(l, origin, "minecraft:entity.lightning_bolt.thunder", 2.0f, 1.4f);
        flyOrb(l, origin, end, travel, "minecraft:black_concrete", "minecraft:purple_stained_glass", width * 1.8, true);
        flyOrb(l, origin, end, travel, "minecraft:purple_concrete", "minecraft:magenta_stained_glass", width * 2.8, true);
        p(l, "EXPLOSION_EMITTER", origin, 1, 0, 0, 0, 0);
        p(l, "SONIC_BOOM", origin.add(dir), 1, 0, 0, 0, 0);
        expandingRing(l, "WITCH", origin, 6, 6, 40);
    }

    static void purpleTrail(ServerLevel l, Vec3 head, Vec3 dir, double width) {
        if (!on()) return;
        String[] names = { "PORTAL", "WITCH", "REVERSE_PORTAL", "END_ROD" };
        Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 a = dir.cross(up).normalize(), b = dir.cross(a).normalize();
        for (int i = 0; i < 16; i++) {
            double ang = R.nextDouble() * Math.PI * 2, rr = width * (0.7 + 0.6 * R.nextDouble());
            Vec3 v = head.add(a.scale(Math.cos(ang) * rr)).add(b.scale(Math.sin(ang) * rr)).add(dir.scale(-R.nextDouble() * 5));
            p(l, names[i % 4], v, 1, 0.1, 0.1, 0.1, 0.03);
        }
        perpRing(l, "PORTAL", head, dir, width, 20, R.nextDouble());
        perpRing(l, "ELECTRIC_SPARK", head.subtract(dir.scale(2)), dir, width * 1.2, 12, R.nextDouble());
        p(l, "END_ROD", head, 8, width * 0.4, width * 0.4, width * 0.4, 0.05);
    }

    static void purpleHit(ServerLevel l, Vec3 at) {
        if (!on()) return;
        p(l, "EXPLOSION", at, 2, 0.5, 0.5, 0.5, 0);
        p(l, "WITCH", at, 30, 0.6, 0.6, 0.6, 0.2);
        p(l, "END_ROD", at, 12, 0.5, 0.5, 0.5, 0.3);
        sound(l, at, "minecraft:entity.generic.explode", 1.2f, 0.6f);
    }

    // ---- Sukuna ----

    static void slashArc(ServerPlayer p, double range, int idx) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Vec3 up = Math.abs(look.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = look.cross(up).normalize(), upv = right.cross(look).normalize();
        double roll = (idx - 1) * 0.9 + 0.35;
        Vec3 axis = right.scale(Math.cos(roll)).add(upv.scale(Math.sin(roll)));
        sound(l, eye, "minecraft:entity.player.attack.sweep", 1.6f, 0.6f + idx * 0.2f);
        sound(l, eye, "minecraft:entity.player.attack.crit", 1.2f, 1.4f);
        for (double d = range / 3.0; d <= range + 0.01; d += range / 3.0) {
            for (int i = -16; i <= 16; i++) {
                double ang = i / 16.0;
                Vec3 v = eye.add(look.scale(Math.cos(ang) * d)).add(axis.scale(Math.sin(ang) * d));
                p(l, "CRIT", v, 1, 0, 0, 0, 0);
                if (i % 4 == 0) p(l, "END_ROD", v, 1, 0, 0, 0, 0);
                if (i % 3 == 0) p(l, "CRIMSON_SPORE", v, 1, 0.1, 0.1, 0.1, 0);
            }
        }
        p(l, "SWEEP_ATTACK", eye.add(look.scale(range * 0.5)), 1, 0, 0, 0, 0);
    }

    static void slashHit(LivingEntity e) {
        if (!on()) return;
        ServerLevel l = Compat.level(e);
        Vec3 c = e.position().add(0, e.getBbHeight() * 0.5, 0);
        p(l, "CRIT", c, 12, 0.4, 0.5, 0.4, 0.3);
        p(l, "SWEEP_ATTACK", c, 1, 0, 0, 0, 0);
        p(l, "DAMAGE_INDICATOR", c, 3, 0.3, 0.3, 0.3, 0.1);
        sound(l, c, "minecraft:entity.player.attack.strong", 1.0f, 1.2f);
    }

    static void cleaveFx(ServerLevel l, Vec3 at, Vec3 look) {
        if (!on()) return;
        Vec3 axis = new Vec3(R.nextDouble() - 0.5, R.nextDouble() * 1.2 - 0.2, R.nextDouble() - 0.5);
        axis = axis.lengthSqr() < 0.01 ? new Vec3(0.6, 0.8, 0) : axis.normalize();
        Vec3 a = at.subtract(axis.scale(5)), b = at.add(axis.scale(5));
        line(l, "CRIT", a, b, 40);
        line(l, "END_ROD", a, b, 20);
        line(l, "CRIMSON_SPORE", a, b, 15);
        p(l, "SWEEP_ATTACK", at, 3, 0.8, 0.8, 0.8, 0);
        p(l, "EXPLOSION", at, 2, 0.4, 0.4, 0.4, 0);
        p(l, "DAMAGE_INDICATOR", at, 8, 0.5, 0.6, 0.5, 0.1);
        debris(l, at, "minecraft:red_concrete", 6, 2.5, 2.0, 0.25, true);
        sound(l, at, "minecraft:entity.player.attack.sweep", 1.8f, 0.5f);
        sound(l, at, "minecraft:entity.wither.break_block", 1.0f, 0.8f);
    }

    static void flameStart(ServerPlayer p, int windup) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Vec3 center = eye.add(look.scale(3.0)).add(0, -0.1, 0);
        sound(l, eye, "minecraft:entity.blaze.shoot", 2.0f, 0.5f);
        sound(l, eye, "minecraft:block.fire.ambient", 2.0f, 0.6f);
        sound(l, eye, "minecraft:entity.wither.ambient", 1.6f, 0.5f);
        orb(l, center, "minecraft:magma_block", "minecraft:orange_stained_glass", 1.6, windup, false);
    }

    static void flameCharge(ServerPlayer p, int age, int windup) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        double prog = Math.min(1.0, age / (double) windup);
        Vec3 center = eye.add(look.scale(3.0)).add(0, -0.1, 0);
        perpRing(l, "FLAME", center, look, 1.8, 28, age * 0.35);
        perpRing(l, "SMALL_FLAME", center, look, 1.2, 18, -age * 0.5);
        perpRing(l, "SOUL_FIRE_FLAME", center, look, 2.4 * prog, 20, age * 0.2);
        int n = (int) (6 + 20 * prog);
        for (int i = 0; i < n; i++) {
            Vec3 v = center.add(look.scale((i / (double) n - 0.5) * 5.0));
            p(l, "FLAME", v, 1, 0.05, 0.05, 0.05, 0);
        }
        p(l, "LAVA", center, 2, 0.5, 0.5, 0.5, 0);
        spiral(l, "FLAME", p.position(), 1.2, 2.4, 3, 20, age * 0.4);
        ring(l, "FLAME", p.position().add(0, 0.1, 0), 0.8 + 2.5 * prog, 24);
        if (age % 10 == 0) sound(l, center, "minecraft:block.fire.ambient", 1.6f, 0.6f + (float) prog);
    }

    static void flameFire(ServerLevel l, Vec3 origin, Vec3 dir, double dist, int travel) {
        if (!on()) return;
        Vec3 end = origin.add(dir.scale(dist));
        sound(l, origin, "minecraft:entity.ghast.shoot", 3.0f, 0.6f);
        sound(l, origin, "minecraft:entity.blaze.shoot", 3.0f, 0.5f);
        sound(l, origin, "minecraft:entity.generic.explode", 1.5f, 0.6f);
        flyOrb(l, origin, end, travel, "minecraft:magma_block", "minecraft:orange_stained_glass", 1.9, false);
        flyOrb(l, origin, end, travel, "minecraft:orange_concrete", "minecraft:red_stained_glass", 3.0, false);
        p(l, "EXPLOSION_EMITTER", origin, 1, 0, 0, 0, 0);
    }

    static void flameTrail(ServerLevel l, Vec3 head, Vec3 dir) {
        if (!on()) return;
        p(l, "FLAME", head, 10, 0.6, 0.6, 0.6, 0.08);
        p(l, "LAVA", head, 3, 0.5, 0.5, 0.5, 0);
        p(l, "SMOKE", head.subtract(dir.scale(1.5)), 6, 0.6, 0.6, 0.6, 0.02);
        line(l, "FLAME", head.subtract(dir.scale(4)), head, 10);
        perpRing(l, "FLAME", head, dir, 1.5, 12, R.nextDouble());
        perpRing(l, "SMALL_FLAME", head.subtract(dir.scale(2.5)), dir, 1.0, 8, R.nextDouble());
    }

    static void flameImpact(ServerLevel l, Vec3 c, double r) {
        if (!on()) return;
        sound(l, c, "minecraft:entity.generic.explode", 3.0f, 0.5f);
        sound(l, c, "minecraft:entity.generic.explode", 3.0f, 0.8f);
        sound(l, c, "minecraft:entity.ender_dragon.growl", 2.5f, 0.7f);
        p(l, "EXPLOSION_EMITTER", c, 3, 1.5, 0.5, 1.5, 0);
        pillar(l, c, "minecraft:orange_stained_glass", r * 0.45, 28, 4, 14, true);
        pillar(l, c, "minecraft:magma_block", r * 0.2, 20, 4, 12, true);
        for (int k = 1; k <= 8; k++) {
            final double rr = r * k / 8.0;
            later(k, () -> {
                sphereShell(l, "FLAME", c, rr, 80);
                if (rr > r * 0.5) sphereShell(l, "LAVA", c, rr * 0.8, 16);
            });
        }
        expandingRing(l, "FLAME", c.add(0, 0.3, 0), r, 10, 80);
        expandingRing(l, "SMOKE", c.add(0, 0.6, 0), r * 1.1, 10, 60);
        debris(l, c, "minecraft:magma_block", 24, r, 10, 0.8, true);
        ripple(l, new Vec3(c.x, groundY(l, c), c.z), r, "minecraft:magma_block");
        p(l, "FLAME", c, 120, r * 0.4, r * 0.3, r * 0.4, 0.25);
    }

    // ---- Domain expansions ----

    /** The cutscene before a domain opens: energy gathers, rings spin up, the screen flashes. */
    static void cutscene(ServerLevel l, Vec3 c, Power pw, int ticks) {
        if (!on()) return;
        boolean g = pw == Power.GOJO;
        for (int k = 0; k < ticks; k += 2) {
            final int kk = k;
            final double prog = k / (double) ticks;
            later(k, () -> {
                inflow(l, c.add(0, 1, 0), 10.0 * (1.0 - prog) + 2, 12, g ? "REVERSE_PORTAL" : "CRIMSON_SPORE", 0.5);
                disk(l, c.add(0, 0.1, 0), 1.0 + prog * 6, 0, kk * 0.5, g ? "END_ROD" : "FLAME", 30);
                disk(l, c.add(0, 1.3, 0), 1.6, Math.PI / 2, kk * 0.35, g ? "SOUL_FIRE_FLAME" : "FLAME", 24);
                disk(l, c.add(0, 1.3, 0), 1.2, Math.PI / 2, -kk * 0.5, g ? "ENCHANT" : "SMALL_FLAME", 18);
                column(l, g ? "END_ROD" : "SOUL_FIRE_FLAME", c, 1.0, 6, 8);
                if (kk % 10 == 0) {
                    sphereShell(l, g ? "END_ROD" : "CRIMSON_SPORE", c.add(0, 1, 0), 1 + prog * 5, 50);
                    sound(l, c, g ? "minecraft:block.beacon.ambient" : "minecraft:entity.wither.ambient", 1.6f, 0.5f + (float) prog);
                }
            });
        }
        later(Math.max(1, ticks - 2), () -> {
            p(l, g ? "END_ROD" : "EXPLOSION_EMITTER", c.add(0, 1, 0), g ? 80 : 4, 2, 2, 2, 0.5);
            sound(l, c, "minecraft:entity.generic.explode", 2.5f, g ? 0.5f : 0.4f);
        });
    }

    static void domainOpen(ServerLevel l, Vec3 c, Power pw, double r, int life) {
        if (!on()) return;
        boolean g = pw == Power.GOJO;
        Vec3 m = c.add(0, 1, 0);
        sound(l, c, g ? "minecraft:block.end_portal.spawn" : "minecraft:entity.wither.spawn", 3.0f, g ? 0.8f : 0.5f);
        sound(l, c, g ? "minecraft:entity.warden.sonic_boom" : "minecraft:entity.ender_dragon.growl", 3.0f, 0.5f);
        for (int k = 1; k <= 14; k++) {
            final double rr = r * k / 14.0;
            later(k, () -> {
                sphereShell(l, g ? "SQUID_INK" : "SMOKE", m, rr, 90);
                sphereShell(l, g ? "END_ROD" : "CRIMSON_SPORE", m, rr, 40);
            });
        }
        expandingRing(l, g ? "REVERSE_PORTAL" : "FLAME", new Vec3(c.x, groundY(l, c) + 0.2, c.z), r, 12, 90);
        if (!g) shrine(l, new Vec3(c.x, groundY(l, c), c.z), life);
    }

    /** Called every 2 ticks while the domain is open. */
    static void domainAmbient(ServerLevel l, Vec3 c, Power pw, double r, int age) {
        if (!on()) return;
        boolean g = pw == Power.GOJO;
        Vec3 m = c.add(0, 1, 0);
        if (g) {
            sphereShell(l, "SQUID_INK", m, r, 40);
            for (int i = 0; i < 10; i++) p(l, "END_ROD", randomInSphere(m, r * 0.95), 1, 0, 0, 0, 0);
            disk(l, m, r * 0.6, 0.25, age * 0.05, "ENCHANT", 40);
            disk(l, m, r * 0.35, -0.3, -age * 0.07, "REVERSE_PORTAL", 30);
            disk(l, m, r * 0.8, 0.1, age * 0.03, "END_ROD", 20);
            p(l, "ENCHANT", m, 20, r * 0.5, r * 0.5, r * 0.5, 0.5);
        } else {
            sphereShell(l, "CRIMSON_SPORE", m, r, 30);
            for (int i = 0; i < 4; i++) {
                Vec3 a = randomInSphere(m, r * 0.9);
                Vec3 d = new Vec3(R.nextDouble() - 0.5, R.nextDouble() - 0.5, R.nextDouble() - 0.5).normalize().scale(3 + R.nextDouble() * 4);
                line(l, "CRIT", a, a.add(d), 8);
                if (i % 2 == 0) p(l, "SWEEP_ATTACK", a.add(d.scale(0.5)), 1, 0, 0, 0, 0);
            }
            p(l, "SMOKE", m, 10, r * 0.6, r * 0.4, r * 0.6, 0.01);
            if (age % 4 == 0) ring(l, "FLAME", new Vec3(c.x, groundY(l, c) + 0.2, c.z), r, 60);
            if (age % 6 == 0) spikes(l, c, r, 4);
            if (age % 20 == 0) sound(l, c, "minecraft:entity.player.attack.sweep", 2.0f, 0.5f);
        }
    }

    static void domainClose(ServerLevel l, Vec3 c, Power pw, double r) {
        if (!on()) return;
        boolean g = pw == Power.GOJO;
        Vec3 m = c.add(0, 1, 0);
        sound(l, c, "minecraft:block.glass.break", 3.0f, 0.5f);
        sound(l, c, "minecraft:entity.generic.explode", 2.0f, 0.6f);
        for (int k = 1; k <= 6; k++) {
            final double rr = r * (1.0 + k * 0.08);
            later(k, () -> sphereShell(l, g ? "END_ROD" : "FLAME", m, rr, 100));
        }
        p(l, g ? "REVERSE_PORTAL" : "LAVA", m, 80, r * 0.5, r * 0.4, r * 0.5, 0.3);
    }

    /** One block-display piece of the shrine: grows out of the ground, stays, then sinks. */
    private static void piece(ServerLevel l, Vec3 base, String block, double ox, double oy, double oz,
                              double w, double h, double d, int life, boolean glow) {
        Vec3 at = base.add(ox, oy, oz);
        String id = spawn(l, at, block, w, 0.05, d, -w / 2, 0, -d / 2, glow);
        morph(id, 1, 8, w, h, d, -w / 2, 0, -d / 2, NO_ROT);
        morph(id, Math.max(10, life), 8, w, 0.02, d, -w / 2, 0, -d / 2, NO_ROT);
        kill(id, Math.max(10, life) + 9);
    }

    /** Malevolent Shrine: a dark pagoda with bone teeth and red lanterns rises in the middle of the domain. */
    private static void shrine(ServerLevel l, Vec3 b, int life) {
        piece(l, b, "minecraft:polished_blackstone", 0, 0, 0, 11, 0.4, 11, life, false);
        piece(l, b, "minecraft:blackstone", 0, 0.4, 0, 9, 0.3, 9, life, false);
        piece(l, b, "minecraft:magma_block", 0, 0.7, 0, 3, 0.3, 3, life, true);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                piece(l, b, "minecraft:red_nether_bricks", sx * 3.6, 0.7, sz * 3.6, 0.8, 6, 0.8, life, false);
            }
        }
        piece(l, b, "minecraft:dark_oak_planks", 0, 6.7, 0, 13, 0.5, 13, life, false);
        piece(l, b, "minecraft:crimson_planks", 0, 7.2, 0, 9, 0.5, 9, life, false);
        piece(l, b, "minecraft:red_nether_bricks", 0, 7.7, 0, 5, 0.5, 5, life, false);
        piece(l, b, "minecraft:bone_block", 0, 8.2, 0, 1, 1.4, 1, life, false);
        for (int i = -2; i <= 2; i++) {
            piece(l, b, "minecraft:bone_block", i * 1.2, 5.6, -6.2, 0.7, 1.0, 0.7, life, false);
        }
        piece(l, b, "minecraft:red_stained_glass", -1.8, 1.0, -5.0, 0.5, 0.7, 0.5, life, true);
        piece(l, b, "minecraft:red_stained_glass", 1.8, 1.0, -5.0, 0.5, 0.7, 0.5, life, true);
    }

    // ================= extra flair on every ability and ultimate =================

    /**
     * A themed layer drawn on top of every ability and ultimate: a spinning rune sigil that grows out from
     * the caster, a rising double helix, a pillar of light, a burst ring and flying debris. Ultimates get a
     * bigger version with expanding spheres and a ground shockwave.
     */
    static void flourish(ServerPlayer p, Power power, boolean ult, double radius) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 pos = p.position();
        final Vec3 g = new Vec3(pos.x, groundY(l, pos) + 0.1, pos.z);
        final String blk = blockFor(power), part = particleFor(power);
        final double r = Math.max(2.8, Math.min(ult ? 12 : 6, radius));
        final int frames = ult ? 16 : 9;
        final double top = ult ? 5.0 : 3.2;
        for (int k = 0; k < frames; k++) {
            final int kk = k;
            final double prog = (k + 1) / (double) frames;
            later(k, () -> {
                double rr = r * prog;
                disk(l, g, rr, 0, kk * 0.45, part, (int) (14 + rr * 5));
                disk(l, g, rr * 0.62, 0, -kk * 0.6, "ENCHANT", (int) (10 + rr * 3));
                for (int s = 0; s < 6; s++) {
                    double a = kk * 0.3 + s * Math.PI / 3;
                    p(l, "END_ROD", g.x + Math.cos(a) * rr, g.y + 0.05, g.z + Math.sin(a) * rr, 1, 0, 0, 0, 0);
                }
                double h = prog * top;
                for (int st = 0; st < 2; st++) {
                    double a = kk * 0.7 + st * Math.PI;
                    double hx = g.x + Math.cos(a) * r * 0.35, hz = g.z + Math.sin(a) * r * 0.35;
                    p(l, part, hx, g.y + h, hz, 1, 0, 0, 0, 0);
                    p(l, "END_ROD", hx, g.y + h, hz, 1, 0, 0, 0, 0);
                }
            });
        }
        later(frames, () -> {
            expandingRing(l, "END_ROD", g.add(0, 0.2, 0), r * 1.2, 5, 48);
            p(l, part, g.add(0, 1, 0), 40, r * 0.2, 0.6, r * 0.2, 0.15);
            p(l, "FIREWORK", g.add(0, 1.2, 0), 12, 0.4, 0.5, 0.4, 0.15);
        });
        pillar(l, g, blk, ult ? 0.9 : 0.5, ult ? 16 : 7, 3, ult ? 12 : 5, true);
        sound(l, pos, "minecraft:entity.firework_rocket.twinkle", 1.2f, 0.8f);
        if (ult) {
            sound(l, pos, "minecraft:block.beacon.power_select", 1.6f, 0.7f);
            for (int k = 1; k <= 6; k++) {
                final double rr = r * k / 6.0;
                later(k, () -> sphereShell(l, part, g.add(0, 1, 0), rr, 60));
            }
            debris(l, g, blk, 16, r, 6, 0.5, true);
            ripple(l, g, Math.min(r, 9), blk);
        } else {
            debris(l, g, blk, 6, r, 2.5, 0.3, true);
        }
    }

    /** A faint glow around Gojo and Sukuna, since their armor is invisible. Called every 10 ticks. */
    static void idleAura(ServerPlayer p, Power power, int t) {
        if (!on()) return;
        ServerLevel l = Compat.level(p);
        Vec3 pos = p.position();
        boolean g = power == Power.GOJO;
        spiral(l, g ? "SOUL_FIRE_FLAME" : "SMALL_FLAME", pos, 0.7, 2.0, 1.5, 4, t * 0.3);
        p(l, g ? "END_ROD" : "CRIMSON_SPORE", pos.add(0, 1, 0), 2, 0.4, 0.7, 0.4, 0.01);
        p(l, g ? "ELECTRIC_SPARK" : "FLAME", pos.add(0, 1.8, 0), 1, 0.2, 0.1, 0.2, 0.02);
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
            case GRAVITY -> blackHole(l, c, r, t);
        }
    }
}
