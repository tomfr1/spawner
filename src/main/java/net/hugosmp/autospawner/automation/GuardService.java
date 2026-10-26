package net.hugosmp.autospawner.automation;

import net.hugosmp.autospawner.HugoSmpClient;
import net.hugosmp.autospawner.config.HugoConfig;
import net.hugosmp.autospawner.util.CommandGuard;
import net.hugosmp.autospawner.util.Helper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Trusted-Schutz: unbekannter Spieler im Radius ->
 * 1. sneaken und alle Spawner mit Silk-Touch-Spitzhacke abbauen (Sneak-Fenster wird alle ~9 s erneuert),
 * 2. Spawner-Drops einsammeln, bis alle im Inventar sind,
 * 3. /spawn, 4. nach Ankunft am Spawn ausloggen.
 */
public final class GuardService {
    private GuardService() {}

    private enum E { OFF, MINING, COLLECT, SPAWN, WAIT_ARRIVAL, WAIT_AFTER, LOGOUT }

    private static E state = E.OFF;
    private static boolean alarm = false;
    private static BlockPos target;
    private static int targetTicks, collectTicks, emptyTicks, waitTicks, spawnTicks, sendTicks, spawnSends;
    private static long startMs, sneakSince;
    private static boolean sneakReleasing;
    private static double originX, originY, originZ;
    private static ClientWorld worldRef;
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
            spawnTicks = 0;
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
        sneakSince = startMs;
        sneakReleasing = false;
        target = null;
        skipped.clear();
        collectTicks = 0;
        emptyTicks = 0;
        spawnTicks = 0;
        spawnSends = 0;
        state = E.MINING;
    }

    private static void emergency(MinecraftClient mc, ClientPlayerEntity p, HugoConfig c) {
        switch (state) {
            case MINING -> {
                holdSneak(mc, c);
                boolean timeout = System.currentTimeMillis() - startMs > c.emergencyMineTimeoutSec * 1000L;
                int slot = Helper.findPickaxeSlot(p, c);
                if (timeout || slot < 0) {
                    Helper.msg(timeout ? "Zeitlimit beim Abbauen erreicht." : "Keine passende Spitzhacke gefunden!");
                    spawnTicks = 0;
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
                holdSneak(mc, c);
                List<ItemEntity> drops = mc.world.getEntitiesByClass(ItemEntity.class,
                        p.getBoundingBox().expand(10), e -> e.getStack().isOf(Items.SPAWNER));
                if (drops.isEmpty()) {
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    if (++emptyTicks >= 15) finishCollect(mc, p, c, false);
                    return;
                }
                emptyTicks = 0;
                if (++collectTicks > 200) { finishCollect(mc, p, c, true); return; }
                ItemEntity nearest = drops.stream()
                        .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p))).get();
                Helper.lookAt(p, new Vec3d(nearest.getX(), p.getEyeY(), nearest.getZ()));
                mc.options.forwardKey.setPressed(true);
                mc.options.jumpKey.setPressed(p.horizontalCollision);
            }
            case SPAWN -> {
                releaseKeys(mc);
                if (CommandGuard.send(mc, "spawn", true)) {
                    originX = p.getX(); originY = p.getY(); originZ = p.getZ();
                    worldRef = mc.world;
                    sendTicks = 0;
                    spawnSends++;
                    state = E.WAIT_ARRIVAL;
                } else if (++spawnTicks > 40) {
                    Helper.msg("/spawn konnte nicht gesendet werden - logge aus.");
                    state = E.LOGOUT;
                }
            }
            case WAIT_ARRIVAL -> {
                sendTicks++;
                double dx = p.getX() - originX, dy = p.getY() - originY, dz = p.getZ() - originZ;
                boolean moved = dx * dx + dy * dy + dz * dz > 100.0; // > 10 Bloecke
                if (mc.world != worldRef || moved) {
                    Helper.msg("Am Spawn angekommen.");
                    waitTicks = c.postSpawnWaitTicks;
                    state = E.WAIT_AFTER;
                } else if (sendTicks > c.spawnRetryTicks) {
                    if (spawnSends < 3) {
                        Helper.msg("Noch nicht am Spawn - sende /spawn erneut.");
                        spawnTicks = 0;
                        state = E.SPAWN;
                    } else {
                        Helper.msg("Spawn nicht erreicht - logge zur Sicherheit trotzdem aus.");
                        state = E.LOGOUT;
                    }
                }
            }
            case WAIT_AFTER -> {
                if (--waitTicks <= 0) state = E.LOGOUT;
            }
            case LOGOUT -> {
                releaseKeys(mc);
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

    private static void finishCollect(MinecraftClient mc, ClientPlayerEntity p, HugoConfig c, boolean timedOut) {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        int left = Helper.findSpawners(p, mc.world, 5, 4.5).size();
        int inInv = Helper.count(p, Items.SPAWNER);
        if (left == 0 && !timedOut) {
            Helper.msg("Alle Spawner abgebaut und eingesammelt (" + inInv + " im Inventar).");
        } else {
            Helper.msg("Nicht alles eingesammelt/abgebaut (noch " + left + " Spawner-Bloecke"
                    + (timedOut ? ", Drops am Boden" : "") + ") - weiter mit /spawn.");
        }
        spawnTicks = 0;
        state = E.SPAWN;
    }

    // ------------------------------------------------------------------ Sneak
    /** Haelt Sneak gedrueckt und erneuert es kurz vor Ablauf des Sneak-Fensters (Standard 10 s). */
    private static void holdSneak(MinecraftClient mc, HugoConfig c) {
        long now = System.currentTimeMillis();
        if (sneakReleasing) {
            setSneak(mc, true);
            sneakReleasing = false;
            sneakSince = now;
            return;
        }
        if (now - sneakSince > Math.max(2000L, c.sneakWindowSec * 1000L - 1000L)) {
            setSneak(mc, false);
            sneakReleasing = true;
            return;
        }
        setSneak(mc, true);
    }

    /** Funktioniert auch bei aktiviertem "Sneak umschalten" in den Tasteneinstellungen. */
    private static void setSneak(MinecraftClient mc, boolean want) {
        KeyBinding k = mc.options.sneakKey;
        if (k.isPressed() != want) {
            k.setPressed(want);
            if (k.isPressed() != want) k.setPressed(true);
        }
    }

    private static void releaseKeys(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        setSneak(mc, false);
    }
}
