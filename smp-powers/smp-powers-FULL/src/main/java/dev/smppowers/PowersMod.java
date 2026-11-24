package dev.smppowers;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PowersMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("smppowers");

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(PowerEngine::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> PowerEngine.stop());
        ServerTickEvents.END_SERVER_TICK.register(PowerEngine::tick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> PowerEngine.onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PowerEngine.onLeave(handler.getPlayer()));

        ServerLivingEntityEvents.ALLOW_DAMAGE.register(PowerEngine::allowDamage);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(PowerEngine::afterDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(PowerEngine::afterDeath);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PowerCommands.register(dispatcher));

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            ItemStack stack = sp.getItemInHand(hand);

            String kind = EssenceItems.kind(stack);
            if (EssenceItems.ESSENCE.equals(kind)) {
                PlayerData d = PowerEngine.data(sp);
                if (PowerEngine.powerOf(sp) == null) return InteractionResult.FAIL;
                if (d.level >= PowerEngine.config.maxLevel) {
                    PowerEngine.tell(sp, "You are already max level.", ChatFormatting.GRAY);
                    return InteractionResult.FAIL;
                }
                stack.shrink(1);
                d.level++;
                PowerEngine.store.save();
                PowerEngine.tell(sp, "Imperium Essence used. You are level " + d.level + ".", ChatFormatting.LIGHT_PURPLE);
                return InteractionResult.SUCCESS;
            }
            if (EssenceItems.REROLL.equals(kind)) {
                Power old = PowerEngine.powerOf(sp);
                Power fresh = PowerEngine.randomPower(old);
                stack.shrink(1);
                PowerEngine.assignPower(sp, fresh);
                PowerEngine.tell(sp, "New power: " + fresh.display + "!", ChatFormatting.GOLD);
                return InteractionResult.SUCCESS;
            }

            if (stack.is(Items.TRIDENT) && PowerEngine.config.tideSurgeRequiresTrident && Abilities.tideSurge(sp)) {
                return InteractionResult.FAIL; // eat the click so the trident isn't thrown
            }
            return InteractionResult.PASS;
        });

        LOGGER.info("SMP Powers loaded.");
    }
}
