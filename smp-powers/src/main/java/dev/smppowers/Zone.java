package dev.smppowers;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** A lingering area (fire storm, toxic cloud, blizzard, dragon breath) that pulses twice a second. */
final class Zone {
    enum Kind { FIRE, TOXIC, FROST, DRAGON }

    final UUID owner;
    final Kind kind;
    ServerLevel level;
    Vec3 pos;
    final double radius;
    final int endTick;
    final boolean follow;
    final double damage;

    Zone(UUID owner, Kind kind, ServerLevel level, Vec3 pos, double radius, int endTick, boolean follow, double damage) {
        this.owner = owner;
        this.kind = kind;
        this.level = level;
        this.pos = pos;
        this.radius = radius;
        this.endTick = endTick;
        this.follow = follow;
        this.damage = damage;
    }
}
