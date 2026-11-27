package dev.smppowers;

import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Every Minecraft call that tends to get renamed between versions lives here.
 * If the build fails after a Minecraft update, the fix is almost always in this one file.
 */
final class Compat {
    private Compat() {}

    static ServerLevel level(Entity e) {
        return (ServerLevel) e.level();
    }

    /** Damage that counts as a player hit (so kills credit the player). */
    static void hurt(LivingEntity target, ServerPlayer source, float amount) {
        ServerLevel l = level(target);
        // Ability damage must land in full: clear the "just got hurt" timer (otherwise a hit that
        // comes right after another one is mostly ignored) and use magic damage credited to the
        // player, which armor does not shrink. Falls back to a normal player hit if it can't be found.
        target.invulnerableTime = 0;
        net.minecraft.world.damagesource.DamageSource ds = null;
        try {
            if (!triedMagic) {
                triedMagic = true;
                for (java.lang.reflect.Method m : l.damageSources().getClass().getMethods()) {
                    if (m.getName().equals("indirectMagic") && m.getParameterCount() == 2) {
                        indirectMagic = m;
                        break;
                    }
                }
            }
            if (indirectMagic != null) ds = (net.minecraft.world.damagesource.DamageSource) indirectMagic.invoke(l.damageSources(), source, source);
        } catch (Throwable ignored) {
            // use the fallback below
        }
        if (ds == null) ds = l.damageSources().playerAttack(source);
        target.hurtServer(l, ds, amount);
    }

    private static boolean triedMagic = false;
    private static java.lang.reflect.Method indirectMagic = null;

    /** Applies a potion effect looked up by field name, trying each name in order (survives renames). */
    @SuppressWarnings("unchecked")
    static void effectNamed(LivingEntity e, int ticks, int amplifier, String... names) {
        for (String n : names) {
            try {
                Object o = net.minecraft.world.effect.MobEffects.class.getField(n).get(null);
                effect(e, (Holder<MobEffect>) o, ticks, amplifier);
                return;
            } catch (Exception ignored) {
                // try the next name
            }
        }
    }

    static void hurtMagic(LivingEntity target, float amount) {
        ServerLevel l = level(target);
        target.hurtServer(l, l.damageSources().magic(), amount);
    }

    static void effect(LivingEntity e, Holder<MobEffect> effect, int ticks, int amplifier) {
        e.addEffect(new MobEffectInstance(effect, ticks, amplifier, true, false, true));
    }

    static void push(Entity e, Vec3 velocity) {
        e.setDeltaMovement(velocity);
        e.hurtMarked = true; // tells the client about the new velocity
    }

    static void ignite(Entity e, int seconds) {
        e.igniteForSeconds(seconds);
    }

    static void freeze(LivingEntity e, int ticks) {
        e.setTicksFrozen(ticks);
    }

    static void actionBar(ServerPlayer p, Component c) {
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(c));
    }

    static void msg(ServerPlayer p, Component c) {
        p.sendSystemMessage(c);
    }

    static void particles(ServerLevel l, ParticleOptions type, Vec3 pos, int count, double spread) {
        l.sendParticles(type, pos.x, pos.y, pos.z, count, spread, spread / 2.0, spread, 0.02);
    }

    /** Finds the lightning bolt entity type by name so a rename can't break the build. */
    @SuppressWarnings("rawtypes")
    static net.minecraft.world.entity.EntityType lightningType() {
        try {
            for (java.lang.reflect.Field f : net.minecraft.world.entity.EntityType.class.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getName().contains("LIGHTNING")) {
                    return (net.minecraft.world.entity.EntityType) f.get(null);
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /** Points an item at a custom model from the resource pack (assets/smppowers/items/<name>.json). */
    static void itemModel(net.minecraft.world.item.ItemStack stack, String name) {
        stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("smppowers", name));
    }

    static boolean isEnd(Level l) {
        return l.dimension() == Level.END;
    }

    static String dimensionId(Level l) {
        return l.dimension().identifier().toString();
    }

    static Predicate<CommandSourceStack> admin() {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }

    static boolean damageIs(DamageSource s, net.minecraft.tags.TagKey<net.minecraft.world.damagesource.DamageType> tag) {
        return s.is(tag);
    }
}
