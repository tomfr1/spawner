package net.hugosmp.autospawner.util;

import net.hugosmp.autospawner.HugoSmpClient;
import net.hugosmp.autospawner.config.HugoConfig;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Helper {
    private Helper() {}

    private static final Pattern PRICE = Pattern.compile("\\$\\s*([0-9][0-9.,]*)\\s*([kKmMbBtT])?");

    // ------------------------------------------------------------ Chat / Server
    public static void msg(String text) {
        HugoSmpClient.LOGGER.info(text);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("\u00a76[HugoSMP] \u00a7r" + text), false);
        }
    }

    public static boolean onTargetServer(MinecraftClient mc) {
        ServerInfo si = mc.getCurrentServerEntry();
        if (si == null || si.address == null) return false;
        return si.address.toLowerCase(Locale.ROOT)
                .contains(HugoConfig.get().serverAddress.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------ Items
    public static String idOf(ItemStack s) {
        return Registries.ITEM.getId(s.getItem()).toString();
    }

    public static int count(ClientPlayerEntity p, Item item) {
        PlayerInventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < Math.min(36, inv.size()); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(item)) n += s.getCount();
        }
        return n;
    }

    public static boolean isPickaxe(ItemStack s) {
        return !s.isEmpty() && s.isIn(ItemTags.PICKAXES);
    }

    public static boolean hasSilkTouch(ItemStack s) {
        for (RegistryEntry<Enchantment> e : s.getEnchantments().getEnchantments()) {
            if (e.matchesKey(Enchantments.SILK_TOUCH)) return true;
        }
        return false;
    }

    /** Liefert den Hotbar-Slot (0-8) der passenden Spitzhacke oder -1. */
    public static int findPickaxeSlot(ClientPlayerEntity p, HugoConfig c) {
        PlayerInventory inv = p.getInventory();
        if (c.silkPickaxeHotbarSlot >= 0) {
            ItemStack s = inv.getStack(c.silkPickaxeHotbarSlot);
            return isPickaxe(s) && (!c.requireSilkTouch || hasSilkTouch(s)) ? c.silkPickaxeHotbarSlot : -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (isPickaxe(s) && (!c.requireSilkTouch || hasSilkTouch(s))) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------ Welt
    public static List<BlockPos> findSpawners(ClientPlayerEntity p, ClientWorld w, int radius, double reach) {
        List<BlockPos> out = new ArrayList<>();
        Vec3d eye = p.getEyePos();
        BlockPos o = p.getBlockPos();
        double r2 = reach * reach;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = o.add(x, y, z);
                    if (w.getBlockState(pos).isOf(Blocks.SPAWNER)
                            && eye.squaredDistanceTo(Vec3d.ofCenter(pos)) <= r2) {
                        out.add(pos.toImmutable());
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(b -> eye.squaredDistanceTo(Vec3d.ofCenter(b))));
        return out;
    }

    public static void lookAt(ClientPlayerEntity p, Vec3d target) {
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    // ------------------------------------------------------------ Order-Auswertung
    public static String fullText(ItemStack s) {
        StringBuilder sb = new StringBuilder(s.getName().getString());
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore != null) for (Text t : lore.lines()) sb.append(' ').append(t.getString());
        return sb.toString();
    }

    /** Hoechster "$"-Betrag in der Lore, -1 wenn keiner gefunden. */
    public static double parsePrice(ItemStack s) {
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        double best = -1;
        if (lore == null) return best;
        for (Text line : lore.lines()) {
            Matcher m = PRICE.matcher(line.getString());
            while (m.find()) {
                try {
                    double v = Double.parseDouble(m.group(1).replace(",", ""));
                    String suf = m.group(2);
                    if (suf != null) {
                        switch (suf.toLowerCase(Locale.ROOT)) {
                            case "k" -> v *= 1e3;
                            case "m" -> v *= 1e6;
                            case "b" -> v *= 1e9;
                            case "t" -> v *= 1e12;
                            default -> { }
                        }
                    }
                    best = Math.max(best, v);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------ Debug
    /** Schreibt alle Slots des aktuell offenen Menues ins Log, damit Slot-Nummern ermittelt werden koennen. */
    public static void dumpScreen(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        ScreenHandler h = p.currentScreenHandler;
        if (h == p.playerScreenHandler) {
            msg("Kein Menue offen. Oeffne ein Menue und starte /hugosmp debug erneut.");
            return;
        }
        String title = mc.currentScreen != null ? mc.currentScreen.getTitle().getString() : "?";
        msg("Menue '" + title + "' mit " + h.slots.size() + " Slots -> siehe logs/latest.log");
        HugoSmpClient.LOGGER.info("==== DEBUG Menue '{}' syncId={} ====", title, h.syncId);
        for (int i = 0; i < h.slots.size(); i++) {
            Slot s = h.slots.get(i);
            ItemStack st = s.getStack();
            if (st.isEmpty()) continue;
            HugoSmpClient.LOGGER.info("slot={} {} {} x{} | {} | price={}", i,
                    s.inventory instanceof PlayerInventory ? "[Inventar]" : "[Menue]   ",
                    idOf(st), st.getCount(), fullText(st), parsePrice(st));
        }
    }
}
