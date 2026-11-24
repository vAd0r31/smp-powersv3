package dev.smppowers;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Imperium Essence (+1 level) and Reroll Token (new random power).
 * Both are normal vanilla items with a hidden tag, so players need no resource pack.
 * The tag is what makes them real: a renamed echo shard from an anvil does nothing.
 */
final class EssenceItems {
    private EssenceItems() {}

    static final String TAG = "smppowers_item";
    static final String ESSENCE = "essence";
    static final String REROLL = "reroll";

    static ItemStack essence(int count) {
        return make(Items.ECHO_SHARD, ESSENCE, "Imperium Essence", ChatFormatting.LIGHT_PURPLE,
                "Right-click to gain a power level.", count);
    }

    static ItemStack rerollToken() {
        return make(Items.NAUTILUS_SHELL, REROLL, "Reroll Token", ChatFormatting.AQUA,
                "Right-click to get a new random power.", 1);
    }

    private static ItemStack make(Item item, String kind, String name, ChatFormatting color, String lore, int count) {
        ItemStack stack = new ItemStack(item, count);
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG, kind);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color, ChatFormatting.BOLD));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore).withStyle(ChatFormatting.GRAY))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    /** Returns "essence", "reroll" or null. */
    static String kind(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String v = data.copyTag().getStringOr(TAG, "");
        return v.isEmpty() ? null : v;
    }
}
