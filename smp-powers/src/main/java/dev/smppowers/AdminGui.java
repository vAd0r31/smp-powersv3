package dev.smppowers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Admin control panel: a normal chest menu that is built on the server, so nobody needs any mod.
 * Open it with /power gui (or /powergui). Left click = +, right click = -, shift = 10 times more.
 * Everything saves to config.json / players.json as you click.
 */
final class AdminGui {
    private AdminGui() {}

    interface Act { void run(ServerPlayer viewer, int button, boolean shift); }

    interface Page {
        String title();
        void render(Menu m);
    }

    private static Page page(String title, Consumer<Menu> r) {
        return new Page() {
            @Override public String title() { return title; }
            @Override public void render(Menu m) { r.accept(m); }
        };
    }

    static void open(ServerPlayer p) {
        open(p, mainPage());
    }

    static void open(ServerPlayer p, Page pg) {
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new Menu(id, inv, pg), Component.literal(pg.title())));
    }

    // ================= the menu itself =================

    static final class Menu extends AbstractContainerMenu {
        final SimpleContainer box = new SimpleContainer(54);
        final Act[] acts = new Act[54];
        final ServerPlayer viewer;
        final Page page;
        boolean viaClicked = false;
        boolean navigated = false;

        Menu(int id, Inventory inv, Page page) {
            super(MenuType.GENERIC_9x6, id);
            this.page = page;
            this.viewer = (ServerPlayer) inv.player;
            for (int i = 0; i < 54; i++) addSlot(new Locked(this, box, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18, i));
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) addSlot(new Locked(this, inv, 9 + r * 9 + c, 8 + c * 18, 140 + r * 18, -1));
            }
            for (int c = 0; c < 9; c++) addSlot(new Locked(this, inv, c, 8 + c * 18, 198, -1));
            page.render(this);
        }

        void put(int slot, ItemStack s, Act a) {
            if (slot < 0 || slot >= 54) return;
            box.setItem(slot, s);
            acts[slot] = a;
        }

        /** Rebuilds the page after a value changed, without closing the window. */
        void refresh() {
            for (int i = 0; i < 54; i++) {
                box.setItem(i, ItemStack.EMPTY);
                acts[i] = null;
            }
            page.render(this);
            resync();
        }

        /** Opens another page on the next tick (never while a click is still being processed). */
        void go(Page next) {
            navigated = true;
            ServerPlayer v = viewer;
            if (PowerEngine.server != null) PowerEngine.server.execute(() -> open(v, next));
        }

        void press(int slot, int button, boolean shift) {
            Act a = acts[slot];
            if (a == null) return;
            try {
                a.run(viewer, button, shift);
            } catch (Exception e) {
                PowersMod.LOGGER.warn("admin gui click failed", e);
                PowerEngine.tell(viewer, "That click failed, see the server log.", ChatFormatting.RED);
            }
        }

        void resync() {
            for (String n : new String[] { "broadcastFullState", "sendAllDataToRemote" }) {
                try {
                    Method m = AbstractContainerMenu.class.getMethod(n);
                    m.invoke(this);
                    return;
                } catch (Throwable ignored) {
                    // try the next name
                }
            }
            broadcastChanges();
        }

        // No @Override on purpose: if a future version renames this, the slots below still can't be picked up.
        public void clicked(int slotId, int button, ClickType type, Player player) {
            viaClicked = true;
            if (slotId >= 0 && slotId < 54 && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) {
                press(slotId, button, type == ClickType.QUICK_MOVE);
            }
            if (!navigated) resync();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    /** A slot nobody can take from or put into. If the click hook above is missing it still works as a left-click button. */
    static final class Locked extends Slot {
        private final Menu menu;
        private final int idx;

        Locked(Menu menu, Container c, int i, int x, int y, int idx) {
            super(c, i, x, y);
            this.menu = menu;
            this.idx = idx;
        }

        @Override
        public boolean mayPlace(ItemStack s) {
            return false;
        }

        @Override
        public boolean mayPickup(Player p) {
            if (!menu.viaClicked && idx >= 0) menu.press(idx, 0, false);
            return false;
        }
    }

    // ================= item helpers =================

    static ItemStack icon(Item item, ChatFormatting color, String name, String... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color).withStyle(st -> st.withItalic(false)));
        List<Component> l = new ArrayList<>();
        for (String x : lore) l.add(Component.literal(x).withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)));
        s.set(DataComponents.LORE, new ItemLore(l));
        return s;
    }

    static Item itemFor(Power pw) {
        return switch (pw) {
            case TIDE -> Items.WATER_BUCKET;
            case EMBER -> Items.BLAZE_POWDER;
            case GALE -> Items.FEATHER;
            case STONE -> Items.STONE;
            case FROST -> Items.SNOWBALL;
            case STORM -> Items.LIGHTNING_ROD;
            case SHADE -> Items.INK_SAC;
            case VITAL -> Items.GOLDEN_APPLE;
            case VENOM -> Items.SPIDER_EYE;
            case SMASH -> Items.MACE;
            case HUNTER -> Items.BOW;
            case DRAGON -> Items.DRAGON_BREATH;
            case SAND -> Items.SAND;
            case REAPER -> Items.NETHERITE_HOE;
            case ECHO -> Items.ECHO_SHARD;
            case MIMIC -> Items.AMETHYST_SHARD;
            case GRAVITY -> Items.OBSIDIAN;
            case RIFT -> Items.ENDER_PEARL;
            case ILLUSION -> Items.PHANTOM_MEMBRANE;
            case GOJO -> Items.LIGHT_BLUE_DYE;
            case SUKUNA -> Items.RED_DYE;
        };
    }

    private static ChatFormatting colorFor(Power pw) {
        return pw.secret() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.AQUA;
    }

    static String num(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        String s = String.format(Locale.ROOT, "%.3f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static String label(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char ch = camel.charAt(i);
            if (i == 0) {
                sb.append(Character.toUpperCase(ch));
                continue;
            }
            char prev = camel.charAt(i - 1);
            if (Character.isUpperCase(ch) || (Character.isDigit(ch) && !Character.isDigit(prev))) sb.append(' ');
            sb.append(ch);
        }
        return sb.toString();
    }

    private static void back(Menu m, Page to) {
        m.put(49, icon(Items.ARROW, ChatFormatting.YELLOW, "Back"), (v, b, sh) -> m.go(to));
    }

    private static ServerPlayer find(UUID id) {
        return PowerEngine.server == null ? null : PowerEngine.server.getPlayerList().getPlayer(id);
    }

    private static void saveAll() {
        PowerEngine.config.save();
        PowerEngine.store.save();
    }

    // ================= pages =================

    static Page mainPage() {
        return page("Powers admin", m -> {
            m.put(10, icon(Items.PLAYER_HEAD, ChatFormatting.GREEN, "Players",
                    "Change a player's power, level,", "charge and cooldowns."), (v, b, sh) -> m.go(playersPage()));
            m.put(12, icon(Items.ANVIL, ChatFormatting.GOLD, "Power balance",
                    "Edit cooldowns, damage, radius and more", "for every power (also Gojo and Sukuna)."), (v, b, sh) -> m.go(powerList()));
            m.put(14, icon(Items.COMPARATOR, ChatFormatting.AQUA, "Server settings",
                    "Levels, charge, HUD, effects, rules."), (v, b, sh) -> m.go(settingsPage()));
            m.put(16, icon(Items.NETHER_STAR, ChatFormatting.LIGHT_PURPLE, "Items and quick powers",
                    "Essence, Reroll Tokens, give yourself a power."), (v, b, sh) -> m.go(itemsPage()));
            m.put(28, icon(Items.WRITABLE_BOOK, ChatFormatting.YELLOW, "Save now", "Writes config.json and players.json."),
                    (v, b, sh) -> {
                        saveAll();
                        PowerEngine.tell(v, "Saved.", ChatFormatting.GREEN);
                    });
            m.put(30, icon(Items.REPEATER, ChatFormatting.YELLOW, "Reload from files",
                    "Use after editing config.json by hand."), (v, b, sh) -> {
                        PowerEngine.reload();
                        PowerEngine.tell(v, "Reloaded.", ChatFormatting.GREEN);
                    });
            m.put(34, icon(Items.BOOK, ChatFormatting.WHITE, "How to use",
                    "Left click: +  |  Right click: -", "Shift: 10 times bigger steps.", "Changes save as you click."), null);
        });
    }

    static Page playersPage() {
        return page("Players", m -> {
            int i = 0;
            if (PowerEngine.server != null) {
                for (ServerPlayer p : PowerEngine.server.getPlayerList().getPlayers()) {
                    if (i >= 45) break;
                    Power pw = PowerEngine.powerOf(p);
                    PlayerData d = PowerEngine.data(p);
                    UUID id = p.getUUID();
                    m.put(i++, icon(Items.PLAYER_HEAD, ChatFormatting.GREEN, p.getName().getString(),
                            "Power: " + (pw == null ? "none" : pw.display),
                            "Level " + d.level + "   Charge " + d.charge + "%", "Click to edit"),
                            (v, b, sh) -> m.go(playerPage(id)));
                }
            }
            back(m, mainPage());
        });
    }

    static Page playerPage(UUID id) {
        return page("Player", m -> {
            ServerPlayer t = find(id);
            if (t == null) {
                m.put(22, icon(Items.BARRIER, ChatFormatting.RED, "Player is offline"), null);
                back(m, playersPage());
                return;
            }
            PlayerData d = PowerEngine.data(t);
            Power pw = PowerEngine.powerOf(t);
            m.put(4, icon(Items.PLAYER_HEAD, ChatFormatting.GREEN, t.getName().getString(),
                    "Power: " + (pw == null ? "none" : pw.display), "Level " + d.level + "   Charge " + d.charge + "%"), null);

            m.put(10, icon(pw == null ? Items.BARRIER : itemFor(pw), ChatFormatting.AQUA, "Power: " + (pw == null ? "none" : pw.display),
                    "Click to pick a different power."), (v, b, sh) -> m.go(pickPower(id)));

            ItemStack lvl = icon(Items.EXPERIENCE_BOTTLE, ChatFormatting.GREEN, "Level: " + d.level,
                    "Left +1, right -1 (1 to " + PowerEngine.config.maxLevel + ")");
            lvl.setCount(Math.max(1, Math.min(64, d.level)));
            m.put(12, lvl, (v, b, sh) -> {
                ServerPlayer tt = find(id);
                if (tt == null) return;
                PlayerData dd = PowerEngine.data(tt);
                dd.level = Math.max(1, Math.min(PowerEngine.config.maxLevel, dd.level + (b == 1 ? -1 : 1)));
                saveAll();
                m.refresh();
            });
            m.put(14, icon(Items.REDSTONE, ChatFormatting.RED, "Ultimate charge: " + d.charge + "%",
                    "Left +1, right -1", "Shift: 10 at a time"), (v, b, sh) -> {
                ServerPlayer tt = find(id);
                if (tt == null) return;
                PlayerData dd = PowerEngine.data(tt);
                int step = (sh ? 10 : 1) * (b == 1 ? -1 : 1);
                dd.charge = Math.max(0, Math.min(100, dd.charge + step));
                m.refresh();
            });
            m.put(16, icon(Items.GLOWSTONE_DUST, ChatFormatting.YELLOW, "Fill charge to 100%"), (v, b, sh) -> {
                ServerPlayer tt = find(id);
                if (tt == null) return;
                PowerEngine.data(tt).charge = 100;
                m.refresh();
            });
            m.put(28, icon(Items.CLOCK, ChatFormatting.AQUA, "Reset all cooldowns"), (v, b, sh) -> {
                ServerPlayer tt = find(id);
                if (tt == null) return;
                PlayerData dd = PowerEngine.data(tt);
                dd.abilityReadyAt = 0;
                if (dd.slotReadyAt != null) java.util.Arrays.fill(dd.slotReadyAt, 0L);
                PowerEngine.tell(v, "Cooldowns reset.", ChatFormatting.GREEN);
            });
            m.put(30, icon(Items.BARRIER, ChatFormatting.RED, "Clear power", "They will have no power until you set one."),
                    (v, b, sh) -> {
                        ServerPlayer tt = find(id);
                        if (tt == null) return;
                        PlayerData dd = PowerEngine.data(tt);
                        dd.power = null;
                        dd.charge = 0;
                        PowerEngine.ultimates.remove(id);
                        Jujutsu.clearAndRestore(tt);
                        saveAll();
                        m.refresh();
                    });
            m.put(32, icon(Items.ENDER_EYE, ChatFormatting.LIGHT_PURPLE, "Reroll to a random power"), (v, b, sh) -> {
                ServerPlayer tt = find(id);
                if (tt == null) return;
                PowerEngine.assignPower(tt, PowerEngine.randomPower(PowerEngine.powerOf(tt)));
                m.refresh();
            });
            m.put(34, icon(Items.TNT, ChatFormatting.RED, "Stop their ultimate"), (v, b, sh) -> {
                PowerEngine.ultimates.remove(id);
                Jujutsu.clear(id);
                PowerEngine.tell(v, "Stopped.", ChatFormatting.GREEN);
            });
            m.put(37, icon(Items.ECHO_SHARD, ChatFormatting.LIGHT_PURPLE, "Give Imperium Essence", "Shift: 16"), (v, b, sh) -> {
                ServerPlayer tt = find(id);
                if (tt != null) PowerEngine.giveOrDrop(tt, EssenceItems.essence(sh ? 16 : 1));
            });
            m.put(39, icon(Items.NAUTILUS_SHELL, ChatFormatting.AQUA, "Give Reroll Token", "Shift: 16"), (v, b, sh) -> {
                ServerPlayer tt = find(id);
                if (tt == null) return;
                for (int i = 0; i < (sh ? 16 : 1); i++) PowerEngine.giveOrDrop(tt, EssenceItems.rerollToken());
            });
            back(m, playersPage());
        });
    }

    private static void powerGrid(Menu m, java.util.function.Function<Power, Act> onClick) {
        int[] slots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34 };
        Power[] all = Power.values();
        for (int i = 0; i < all.length && i < slots.length; i++) {
            Power pw = all[i];
            List<String> lore = new ArrayList<>();
            if (Jujutsu.is(pw)) {
                String[] names = Jujutsu.slotNames(pw);
                for (int k = 0; k < names.length; k++) lore.add("/ability " + (k + 1) + ": " + names[k]);
                lore.add("Ultimate: " + pw.ultimateName);
                lore.add("SECRET: never rolled or rerolled");
            } else {
                lore.add("Ability: " + pw.abilityName);
                lore.add("Ultimate: " + pw.ultimateName);
            }
            m.put(slots[i], icon(itemFor(pw), colorFor(pw), pw.display, lore.toArray(new String[0])), onClick.apply(pw));
        }
    }

    static Page pickPower(UUID id) {
        return page("Pick a power", m -> {
            powerGrid(m, pw -> (v, b, sh) -> {
                ServerPlayer t = find(id);
                if (t == null) return;
                PowerEngine.assignPower(t, pw);
                PowerEngine.tell(v, t.getName().getString() + " is now " + pw.display + ".", ChatFormatting.GREEN);
                PowerEngine.tell(t, "Your power is now " + pw.display + "!", ChatFormatting.GOLD);
                m.go(playerPage(id));
            });
            back(m, playerPage(id));
        });
    }

    static Page powerList() {
        return page("Power balance", m -> {
            powerGrid(m, pw -> (v, b, sh) -> m.go(powerEditor(pw)));
            back(m, mainPage());
        });
    }

    private static final String[] BASE_FIELDS = { "abilityCooldown", "abilityDuration", "abilityRadius", "abilityDamage",
            "abilityStrength", "ultimateDuration", "ultimateRadius", "ultimateDamage", "ultimateStrength" };

    private static List<String> fieldsFor(Power pw) {
        List<String> out = new ArrayList<>(List.of(BASE_FIELDS));
        if (pw.secret()) {
            out.add("abilityExtra");
            out.add("ultimateWindup");
            int slots = Jujutsu.slotNames(pw).length;
            for (int s = 2; s <= slots; s++) {
                for (String k : new String[] { "Cooldown", "Duration", "Radius", "Damage", "Strength", "Extra" }) out.add("slot" + s + k);
            }
        }
        return out;
    }

    private static double stepFor(String name, double v) {
        if (name.endsWith("Strength")) return 0.1;
        if (name.endsWith("Windup")) return 0.5;
        return 1;
    }

    private static Item itemForField(String name) {
        if (name.endsWith("Cooldown")) return Items.CLOCK;
        if (name.endsWith("Duration")) return Items.REPEATER;
        if (name.endsWith("Radius")) return Items.COMPASS;
        if (name.endsWith("Damage")) return Items.IRON_SWORD;
        if (name.endsWith("Strength")) return Items.FEATHER;
        return Items.PAPER;
    }

    static Page powerEditor(Power pw) {
        return page(pw.display + " settings", m -> {
            PowerSettings s = PowerEngine.config.settings(pw);
            PowerSettings def = PowerSettings.defaultsFor(pw);
            List<String> names = fieldsFor(pw);
            for (int i = 0; i < names.size() && i < 45; i++) {
                String n = names.get(i);
                try {
                    Field f = PowerSettings.class.getField(n);
                    double val = f.getDouble(s);
                    double dv = f.getDouble(def);
                    List<String> lore = new ArrayList<>();
                    lore.add("Value: " + num(val) + "   (default " + num(dv) + ")");
                    lore.add(describe(pw, n));
                    lore.add("Left +" + num(stepFor(n, val)) + "  Right -" + num(stepFor(n, val)) + "  Shift x10");
                    m.put(i, icon(itemForField(n), ChatFormatting.YELLOW, label(n) + ": " + num(val), lore.toArray(new String[0])),
                            (v, b, sh) -> {
                                try {
                                    double cur = f.getDouble(s);
                                    double step = stepFor(n, cur) * (sh ? 10 : 1) * (b == 1 ? -1 : 1);
                                    double nv = Math.max(0, Math.round((cur + step) * 1000.0) / 1000.0);
                                    f.setDouble(s, nv);
                                    PowerEngine.config.save();
                                    m.refresh();
                                } catch (IllegalAccessException e) {
                                    PowersMod.LOGGER.warn("could not set {}", n, e);
                                }
                            });
                } catch (ReflectiveOperationException e) {
                    PowersMod.LOGGER.warn("missing setting {}", n);
                }
            }
            int w = PowerEngine.config.powerWeights.getOrDefault(pw.id(), 1);
            m.put(47, icon(Items.EMERALD, ChatFormatting.GREEN, "Roll weight: " + w,
                    pw.secret() ? "Secret powers are never rolled." : "Higher = rolled more often. 0 = never.",
                    "Left +1, right -1"), (v, b, sh) -> {
                        int cur = PowerEngine.config.powerWeights.getOrDefault(pw.id(), 1);
                        PowerEngine.config.powerWeights.put(pw.id(), Math.max(0, cur + (b == 1 ? -1 : 1)));
                        PowerEngine.config.save();
                        m.refresh();
                    });
            m.put(51, icon(Items.TNT, ChatFormatting.RED, "Reset to defaults", "Puts every number of this power back."),
                    (v, b, sh) -> {
                        PowerEngine.config.powers.put(pw.id(), PowerSettings.defaultsFor(pw));
                        PowerEngine.config.save();
                        m.refresh();
                    });
            back(m, powerList());
        });
    }

    /** What a number does, for the powers whose slots mean different things. */
    private static String describe(Power pw, String field) {
        if (pw.secret()) {
            String[] names = Jujutsu.slotNames(pw);
            if (field.startsWith("ability")) return "Slot 1: " + names[0];
            if (field.startsWith("slot")) {
                int s = field.charAt(4) - '0';
                if (s >= 1 && s <= names.length) return "Slot " + s + ": " + names[s - 1];
            }
            if (field.startsWith("ultimate")) return "Ultimate: " + pw.ultimateName;
        } else {
            if (field.startsWith("ability")) return "Ability: " + pw.abilityName;
            if (field.startsWith("ultimate")) return "Ultimate: " + pw.ultimateName;
        }
        return "";
    }

    static Page settingsPage() {
        return page("Server settings", m -> {
            Config c = PowerEngine.config;
            int i = 0;
            for (Field f : Config.class.getFields()) {
                if (Modifier.isStatic(f.getModifiers()) || i >= 45) continue;
                Class<?> type = f.getType();
                if (type != boolean.class && type != int.class && type != double.class) continue;
                try {
                    String n = f.getName();
                    if (type == boolean.class) {
                        boolean on = f.getBoolean(c);
                        m.put(i++, icon(on ? Items.LIME_DYE : Items.GRAY_DYE, on ? ChatFormatting.GREEN : ChatFormatting.RED,
                                label(n) + ": " + (on ? "ON" : "OFF"), "Click to switch."), (v, b, sh) -> {
                                    try {
                                        f.setBoolean(c, !f.getBoolean(c));
                                        c.save();
                                        m.refresh();
                                    } catch (IllegalAccessException e) {
                                        PowersMod.LOGGER.warn("could not set {}", n, e);
                                    }
                                });
                    } else {
                        double cur = type == int.class ? f.getInt(c) : f.getDouble(c);
                        double step = type == int.class ? 1 : (Math.abs(cur) < 1.5 ? 0.01 : 0.5);
                        m.put(i++, icon(Items.COMPARATOR, ChatFormatting.YELLOW, label(n) + ": " + num(cur),
                                "Left +" + num(step) + "  Right -" + num(step) + "  Shift x10"), (v, b, sh) -> {
                                    try {
                                        double now = type == int.class ? f.getInt(c) : f.getDouble(c);
                                        double nv = Math.max(0, now + step * (sh ? 10 : 1) * (b == 1 ? -1 : 1));
                                        if (type == int.class) f.setInt(c, (int) Math.round(nv));
                                        else f.setDouble(c, Math.round(nv * 1000.0) / 1000.0);
                                        c.save();
                                        m.refresh();
                                    } catch (IllegalAccessException e) {
                                        PowersMod.LOGGER.warn("could not set {}", n, e);
                                    }
                                });
                    }
                } catch (IllegalAccessException e) {
                    PowersMod.LOGGER.warn("could not read {}", f.getName(), e);
                }
            }
            back(m, mainPage());
        });
    }

    static Page itemsPage() {
        return page("Items and quick powers", m -> {
            m.put(10, icon(Items.ECHO_SHARD, ChatFormatting.LIGHT_PURPLE, "Imperium Essence", "Click: 1   Shift: 16"),
                    (v, b, sh) -> PowerEngine.giveOrDrop(v, EssenceItems.essence(sh ? 16 : 1)));
            m.put(12, icon(Items.NAUTILUS_SHELL, ChatFormatting.AQUA, "Reroll Token", "Click: 1   Shift: 16"), (v, b, sh) -> {
                for (int i = 0; i < (sh ? 16 : 1); i++) PowerEngine.giveOrDrop(v, EssenceItems.rerollToken());
            });
            m.put(14, icon(Items.LIGHT_BLUE_DYE, ChatFormatting.LIGHT_PURPLE, "Become Gojo",
                    "Secret power for you."), (v, b, sh) -> {
                        PowerEngine.assignPower(v, Power.GOJO);
                        PowerEngine.tell(v, "You are Gojo. /ability 1-4 and /ultimate.", ChatFormatting.AQUA);
                    });
            m.put(16, icon(Items.RED_DYE, ChatFormatting.RED, "Become Sukuna",
                    "Secret power for you."), (v, b, sh) -> {
                        PowerEngine.assignPower(v, Power.SUKUNA);
                        PowerEngine.tell(v, "You are Sukuna. /ability 1-3 and /ultimate.", ChatFormatting.RED);
                    });
            m.put(28, icon(Items.ENDER_EYE, ChatFormatting.YELLOW, "Re-roll my own power"), (v, b, sh) ->
                    PowerEngine.assignPower(v, PowerEngine.randomPower(PowerEngine.powerOf(v))));
            m.put(30, icon(Items.REDSTONE, ChatFormatting.RED, "Fill my charge to 100%"), (v, b, sh) -> PowerEngine.data(v).charge = 100);
            m.put(32, icon(Items.CLOCK, ChatFormatting.AQUA, "Reset my cooldowns"), (v, b, sh) -> {
                PlayerData d = PowerEngine.data(v);
                d.abilityReadyAt = 0;
                if (d.slotReadyAt != null) java.util.Arrays.fill(d.slotReadyAt, 0L);
            });
            back(m, mainPage());
        });
    }
}
