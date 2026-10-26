package net.hugosmp.autospawner.automation;

import net.hugosmp.autospawner.HugoSmpClient;
import net.hugosmp.autospawner.config.HugoConfig;
import net.hugosmp.autospawner.util.CommandGuard;
import net.hugosmp.autospawner.util.Helper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Trusted-Schutz: unbekannter Spieler im Radius ->
 * 1. Spawner mit Silk-Touch-Spitzhacke abbauen (und Drops einsammeln), 2. /spawn, 3. Ausloggen.
 */
public final class GuardService {
    private GuardService() {}

    private enum E { OFF, MINING, COLLECT, SPAWN, WAIT, LOGOUT }

    private static E state = E.OFF;
    private static boolean alarm = false;
    private static BlockPos target;
    private static int targetTicks, collectTicks, waitTicks, spawnTicks;
    private static long startMs;
    private static final Set<BlockPos> skipped = new HashSet<>();

    public static boolean isAlarm() { return alarm; }

    public static void reset() {
        alarm = false;
        state = E.OFF;
        target = null;
        skipped.clear();
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) return;
        HugoConfig c = HugoConfig.get();

        if (state == E.OFF) {
            if (!c.guardEnabled || alarm || p.age < 60 || !Helper.onTargetServer(mc)) return;
            if (mc.world.getTime() % 5 != 0) return;
            String intruder = detect(mc, p, c);
            if (intruder != null) trigger(mc, intruder);
            return;
        }
        try {
            emergency(mc, p, c);
        } catch (Exception e) {
            HugoSmpClient.LOGGER.error("Fehler im Notfall-Ablauf, springe zu /spawn.", e);
            releaseKeys(mc);
            state = E.SPAWN;
        }
    }

    private static String detect(MinecraftClient mc, ClientPlayerEntity p, HugoConfig c) {
        for (AbstractClientPlayerEntity o : mc.world.getPlayers()) {
            if (o.getUuid().equals(p.getUuid())) continue;
            String name = o.getName().getString();
            if (c.isTrusted(name)) continue;
            if (p.distanceTo(o) <= c.guardRadius) return name;
        }
        return null;
    }

    private static void trigger(MinecraftClient mc, String intruder) {
        alarm = true;
        SpawnerRoutine.abort(mc, "Unbekannter Spieler erkannt");
        Helper.msg("\u00a7cALARM: Unbekannter Spieler '" + intruder + "' in der Naehe!");
        HugoSmpClient.LOGGER.warn("Guard ausgeloest durch {}", intruder);
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            mc.player.closeHandledScreen();
        }
        startMs = System.currentTimeMillis();
        target = null;
        skipped.clear();
        collectTicks = 0;
        spawnTicks = 0;
        state = E.MINING;
    }

    private static void emergency(MinecraftClient mc, ClientPlayerEntity p, HugoConfig c) {
        switch (state) {
            case MINING -> {
                boolean timeout = System.currentTimeMillis() - startMs > c.emergencyMineTimeoutSec * 1000L;
                int slot = Helper.findPickaxeSlot(p, c);
                if (timeout || slot < 0) {
                    Helper.msg(timeout ? "Zeitlimit beim Abbauen erreicht." : "Keine passende Spitzhacke gefunden!");
                    state = timeout ? E.COLLECT : E.SPAWN;
                    return;
                }
                if (target == null) {
                    List<BlockPos> list = Helper.findSpawners(p, mc.world, 5, 4.5);
                    list.removeAll(skipped);
                    if (list.isEmpty()) { state = E.COLLECT; return; }
                    target = list.get(0);
                    targetTicks = 0;
                }
                if (!mc.world.getBlockState(target).isOf(Blocks.SPAWNER)) { target = null; return; }
                p.getInventory().setSelectedSlot(slot);
                Helper.lookAt(p, Vec3d.ofCenter(target));
                mc.interactionManager.updateBlockBreakingProgress(target, Direction.UP);
                p.swingHand(Hand.MAIN_HAND);
                if (++targetTicks > 160) { skipped.add(target); target = null; }
            }
            case COLLECT -> {
                List<ItemEntity> drops = mc.world.getEntitiesByClass(ItemEntity.class,
                        p.getBoundingBox().expand(10), e -> e.getStack().isOf(Items.SPAWNER));
                if (drops.isEmpty() || ++collectTicks > 200) { releaseKeys(mc); state = E.SPAWN; return; }
                ItemEntity nearest = drops.stream()
                        .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p))).get();
                Helper.lookAt(p, new Vec3d(nearest.getX(), p.getEyeY(), nearest.getZ()));
                mc.options.forwardKey.setPressed(true);
                mc.options.jumpKey.setPressed(p.horizontalCollision);
            }
            case SPAWN -> {
                releaseKeys(mc);
                if (CommandGuard.send(mc, "spawn", true) || ++spawnTicks > 40) {
                    waitTicks = c.postSpawnWaitTicks;
                    state = E.WAIT;
                }
            }
            case WAIT -> {
                if (--waitTicks <= 0) state = E.LOGOUT;
            }
            case LOGOUT -> {
                Helper.msg("Logge aus.");
                state = E.OFF;
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().getConnection()
                            .disconnect(Text.literal("HugoSMP AutoSpawner: Unbekannter Spieler in der Naehe."));
                }
            }
            default -> { }
        }
    }

    private static void releaseKeys(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }
}
