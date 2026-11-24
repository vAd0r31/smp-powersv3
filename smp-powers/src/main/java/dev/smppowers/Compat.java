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
        target.hurtServer(l, l.damageSources().playerAttack(source), amount);
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
        p.displayClientMessage(c, true);
    }

    static void msg(ServerPlayer p, Component c) {
        p.sendSystemMessage(c);
    }

    static void particles(ServerLevel l, ParticleOptions type, Vec3 pos, int count, double spread) {
        l.sendParticles(type, pos.x, pos.y, pos.z, count, spread, spread / 2.0, spread, 0.02);
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
