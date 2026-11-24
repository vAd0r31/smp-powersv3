package dev.smppowers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Arrays;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

final class PowerCommands {
    private PowerCommands() {}

    private static final SuggestionProvider<CommandSourceStack> POWER_NAMES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(Power.values()).map(Power::id), builder);

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("ability").executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            Abilities.cast(p);
            return 1;
        }));

        d.register(Commands.literal("ultimate").executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            Abilities.ultimate(p);
            return 1;
        }));

        d.register(Commands.literal("withdraw").executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            PlayerData data = PowerEngine.data(p);
            if (PowerEngine.powerOf(p) == null) {
                PowerEngine.tell(p, "You have no power yet.", ChatFormatting.RED);
                return 0;
            }
            if (data.level <= 1) {
                PowerEngine.tell(p, "You can't withdraw your last level.", ChatFormatting.RED);
                return 0;
            }
            data.level--;
            PowerEngine.giveOrDrop(p, EssenceItems.essence(1));
            PowerEngine.store.save();
            PowerEngine.tell(p, "Withdrew a level. You are now level " + data.level + ".", ChatFormatting.LIGHT_PURPLE);
            return 1;
        }));

        // Anyone can look at their own info
        LiteralArgumentBuilder<CommandSourceStack> power = Commands.literal("power");

        power.then(Commands.literal("info").executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            info(p, p);
            return 1;
        }));

        // Admin only below
        power.then(Commands.literal("set").requires(Compat.admin())
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("power", StringArgumentType.word()).suggests(POWER_NAMES)
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    Power pw = Power.byId(StringArgumentType.getString(ctx, "power"));
                                    if (pw == null) return fail(ctx, "Unknown power.");
                                    PowerEngine.assignPower(target, pw);
                                    return ok(ctx, target.getName().getString() + " is now " + pw.display + ".");
                                }))));

        power.then(Commands.literal("clear").requires(Compat.admin())
                .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                    PlayerData data = PowerEngine.data(target);
                    data.power = null;
                    data.charge = 0;
                    PowerEngine.ultimates.remove(target.getUUID());
                    PowerEngine.store.save();
                    return ok(ctx, "Cleared " + target.getName().getString() + "'s power.");
                })));

        power.then(Commands.literal("charge").requires(Compat.admin())
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                            PowerEngine.data(target).charge = IntegerArgumentType.getInteger(ctx, "amount");
                            return ok(ctx, "Charge set.");
                        }))));

        power.then(Commands.literal("level").requires(Compat.admin())
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 10)).executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                            int lvl = Math.min(IntegerArgumentType.getInteger(ctx, "level"), PowerEngine.config.maxLevel);
                            PowerEngine.data(target).level = lvl;
                            PowerEngine.store.save();
                            return ok(ctx, "Level set to " + lvl + ".");
                        }))));

        power.then(Commands.literal("essence").requires(Compat.admin())
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.literal("reroll").executes(ctx -> {
                            PowerEngine.giveOrDrop(EntityArgument.getPlayer(ctx, "player"), EssenceItems.rerollToken());
                            return ok(ctx, "Gave a Reroll Token.");
                        }))
                        .executes(ctx -> {
                            PowerEngine.giveOrDrop(EntityArgument.getPlayer(ctx, "player"), EssenceItems.essence(1));
                            return ok(ctx, "Gave an Imperium Essence.");
                        })));

        power.then(Commands.literal("reload").requires(Compat.admin()).executes(ctx -> {
            PowerEngine.reload();
            return ok(ctx, "Reloaded config.json and players.json.");
        }));

        d.register(power);
    }

    private static void info(ServerPlayer viewer, ServerPlayer p) {
        Power pw = PowerEngine.powerOf(p);
        PlayerData data = PowerEngine.data(p);
        if (pw == null) {
            PowerEngine.tell(viewer, "No power yet.", ChatFormatting.GRAY);
            return;
        }
        PowerSettings s = PowerEngine.config.settings(pw);
        Compat.msg(viewer, Component.literal("Power: " + pw.display + "  Level " + data.level + "/" + PowerEngine.config.maxLevel
                + "\nAbility: " + pw.abilityName + " (" + Math.round(PowerEngine.cooldownSeconds(pw, data.level)) + "s cooldown)"
                + "\nUltimate: " + pw.ultimateName + " (needs level " + PowerEngine.config.ultimateMinLevel + " and 100% charge, now " + data.charge + "%)"
                + "\nCommands: /ability  /ultimate  /withdraw").withStyle(ChatFormatting.GOLD));
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal(msg));
        return 0;
    }
}
