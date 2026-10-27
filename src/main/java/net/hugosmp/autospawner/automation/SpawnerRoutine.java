package net.hugosmp.autospawner.automation;

import net.hugosmp.autospawner.HugoSmpClient;
import net.hugosmp.autospawner.config.HugoConfig;
import net.hugosmp.autospawner.config.HugoConfig.ItemRule;
import net.hugosmp.autospawner.config.HugoConfig.Mode;
import net.hugosmp.autospawner.config.HugoConfig.OrderTarget;
import net.hugosmp.autospawner.util.CommandGuard;
import net.hugosmp.autospawner.util.Helper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ablauf: Spawner oeffnen -> Items entnehmen -> /sell-Menue befuellen -> je Order-Item /order <Name>,
 * Order waehlen, Items abgeben. Ein Zustandsautomat, der pro Client-Tick hoechstens eine Aktion ausfuehrt.
 */
public final class SpawnerRoutine {
    private SpawnerRoutine() {}

    private enum S {
        IDLE, NEXT_SPAWNER, WAIT_SPAWNER_GUI, WITHDRAW,
        SELL_CMD, WAIT_SELL_GUI, SELL_DEPOSIT,
        ORDER_CMD, WAIT_ORDER_LIST, PICK_ORDER, WAIT_DEPOSIT_GUI, ORDER_DEPOSIT,
        FINISH
    }

    private static S state = S.IDLE;
    private static int stateTicks, cooldown, page, spIdx, lastSyncId;
    private static boolean confirmClicked, sellButtonClicked, deliverClicked;
    private static volatile boolean inventoryFull;
    private static int withdrawClicks;
    private static int round = 1, roundClicks;
    private static boolean roundHadFull;
    private static final Map<String, Integer> orderRepeats = new HashMap<>();
    private static List<BlockPos> spawners = new ArrayList<>();
    private static final Map<Integer, Integer> attempts = new HashMap<>();
    private static final ArrayDeque<ItemRule> orderQueue = new ArrayDeque<>();
    private static ItemRule currentOrder;

    public static boolean isRunning() { return state != S.IDLE; }

    /** Wird fuer jede Server-Nachricht aufgerufen (siehe HugoSmpClient). */
    public static void onMessage(String text) {
        if (state == S.IDLE || text == null) return;
        String needle = HugoConfig.get().inventoryFullText.toLowerCase(Locale.ROOT);
        if (text.toLowerCase(Locale.ROOT).contains(needle)) inventoryFull = true;
    }

    public static void reset() {
        inventoryFull = false;
        state = S.IDLE;
        spawners = new ArrayList<>();
        attempts.clear();
        orderQueue.clear();
        currentOrder = null;
    }

    // ------------------------------------------------------------------ Start / Stop
    public static boolean start(MinecraftClient mc, String reason) {
        if (state != S.IDLE) return false;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return false;
        HugoConfig c = HugoConfig.get();
        if (GuardService.isAlarm()) return false;
        if (c.items.isEmpty()) { Helper.msg("Keine Items konfiguriert."); return false; }
        if (p.getMainHandStack().getItem() instanceof SpawnEggItem) {
            Helper.msg("Spawn-Ei in der Hand - Start abgebrochen (Spawner-Typ koennte sich aendern).");
            return false;
        }
        spawners = Helper.findSpawners(p, mc.world, c.spawnerScanRadius, 4.5);
        if (spawners.isEmpty()) {
            Helper.msg("Keine Spawner in Reichweite (4,5 Bloecke). Naechster Versuch nach dem Intervall.");
            c.lastRunEpochMs = System.currentTimeMillis();
            c.save();
            return false;
        }
        spIdx = 0;
        inventoryFull = false;
        round = 1;
        roundClicks = 0;
        roundHadFull = false;
        orderRepeats.clear();
        orderQueue.clear();
        Helper.msg("Starte Spawner-Lauf (" + reason + "): " + spawners.size() + " Spawner.");
        setState(S.NEXT_SPAWNER);
        return true;
    }

    public static void abort(MinecraftClient mc, String why) {
        if (state == S.IDLE) return;
        Helper.msg("Lauf abgebrochen: " + why);
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            mc.player.closeHandledScreen();
        }
        reset();
    }

    // ------------------------------------------------------------------ Tick
    public static void tick(MinecraftClient mc) {
        if (state == S.IDLE) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) { reset(); return; }
        if (GuardService.isAlarm()) { abort(mc, "Schutz ausgeloest"); return; }
        if (cooldown > 0) { cooldown--; return; }
        stateTicks++;
        try {
            step(mc, p, HugoConfig.get());
        } catch (Exception e) {
            HugoSmpClient.LOGGER.error("Fehler im Spawner-Lauf", e);
            abort(mc, "Fehler: " + e.getClass().getSimpleName());
        }
    }

    private static void setState(S s) { state = s; stateTicks = 0; }

    private static boolean guiOpen(ClientPlayerEntity p) {
        return p.currentScreenHandler != p.playerScreenHandler;
    }

    private static void click(MinecraftClient mc, ClientPlayerEntity p, int slot, SlotActionType t) {
        mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, slot, 0, t, p);
    }

    private static void step(MinecraftClient mc, ClientPlayerEntity p, HugoConfig c) {
        switch (state) {
            case NEXT_SPAWNER -> {
                if (guiOpen(p)) { p.closeHandledScreen(); cooldown = 5; return; }
                if (spIdx >= spawners.size()) { afterSpawners(mc, p, c); return; }
                BlockPos pos = spawners.get(spIdx);
                if (!mc.world.getBlockState(pos).isOf(Blocks.SPAWNER)) { spIdx++; return; }
                Vec3d center = Vec3d.ofCenter(pos);
                Helper.lookAt(p, center);
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                        new BlockHitResult(center, Direction.UP, pos, false));
                p.swingHand(Hand.MAIN_HAND);
                setState(S.WAIT_SPAWNER_GUI);
            }
            case WAIT_SPAWNER_GUI -> {
                if (guiOpen(p)) {
                    page = 0; attempts.clear(); sellButtonClicked = false; withdrawClicks = 0;
                    cooldown = c.clickDelayTicks + 3;
                    setState(S.WITHDRAW);
                } else if (stateTicks > c.screenTimeoutTicks) {
                    Helper.msg("Spawner-Menue hat sich nicht geoeffnet, ueberspringe.");
                    spIdx++;
                    setState(S.NEXT_SPAWNER);
                }
            }
            case WITHDRAW -> withdraw(mc, p, c);

            case SELL_CMD -> {
                if (guiOpen(p)) { p.closeHandledScreen(); cooldown = 5; return; }
                if (CommandGuard.send(mc, "sell", false)) setState(S.WAIT_SELL_GUI);
                else if (stateTicks > 400) abort(mc, "/sell konnte nicht gesendet werden");
            }
            case WAIT_SELL_GUI -> {
                if (guiOpen(p)) {
                    attempts.clear(); confirmClicked = false;
                    cooldown = c.clickDelayTicks + 3;
                    setState(S.SELL_DEPOSIT);
                } else if (stateTicks > c.screenTimeoutTicks) {
                    Helper.msg("/sell-Menue hat sich nicht geoeffnet.");
                    nextOrderOrFinish();
                }
            }
            case SELL_DEPOSIT -> {
                if (!guiOpen(p)) { nextOrderOrFinish(); return; }
                int slot = findInvSlot(p.currentScreenHandler, c.idsWithMode(Mode.SELL));
                if (slot >= 0) {
                    attempts.merge(slot, 1, Integer::sum);
                    click(mc, p, slot, SlotActionType.QUICK_MOVE);
                    cooldown = c.clickDelayTicks;
                    return;
                }
                if (c.sellConfirmSlot >= 0 && !confirmClicked) {
                    confirmClicked = true;
                    click(mc, p, c.sellConfirmSlot, SlotActionType.PICKUP);
                    cooldown = 10;
                    return;
                }
                p.closeHandledScreen();
                cooldown = 10;
                nextOrderOrFinish();
            }

            case ORDER_CMD -> {
                if (guiOpen(p)) { p.closeHandledScreen(); cooldown = 5; return; }
                String name = currentOrder.orderPlayer.isEmpty() ? c.orderPlayer : currentOrder.orderPlayer;
                if (name.isEmpty()) {
                    Helper.msg("Kein Order-Spielername gesetzt - " + currentOrder.item + " wird uebersprungen.");
                    nextOrderOrFinish();
                    return;
                }
                if (CommandGuard.send(mc, "order " + name, false)) { page = 0; setState(S.WAIT_ORDER_LIST); }
                else if (stateTicks > 400) abort(mc, "/order konnte nicht gesendet werden");
            }
            case WAIT_ORDER_LIST -> {
                if (guiOpen(p)) {
                    lastSyncId = p.currentScreenHandler.syncId;
                    cooldown = c.clickDelayTicks + 4;
                    setState(S.PICK_ORDER);
                } else if (stateTicks > c.screenTimeoutTicks) {
                    Helper.msg("Order-Liste hat sich nicht geoeffnet.");
                    nextOrderOrFinish();
                }
            }
            case PICK_ORDER -> pickOrder(mc, p, c);
            case WAIT_DEPOSIT_GUI -> {
                if (guiOpen(p) && p.currentScreenHandler.syncId != lastSyncId) {
                    lastSyncId = p.currentScreenHandler.syncId;
                    attempts.clear(); confirmClicked = false; deliverClicked = false;
                    cooldown = c.clickDelayTicks + 4;
                    setState(S.ORDER_DEPOSIT);
                } else if (stateTicks > c.screenTimeoutTicks) {
                    Helper.msg("Abgabe-Menue hat sich nicht geoeffnet.");
                    if (guiOpen(p)) p.closeHandledScreen();
                    cooldown = 10;
                    nextOrderOrFinish();
                }
            }
            case ORDER_DEPOSIT -> {
                if (!guiOpen(p)) { nextOrderOrFinish(); return; }
                ScreenHandler h = p.currentScreenHandler;
                // Schritt 1: "Ganzes Inventar liefern"
                if (!deliverClicked) {
                    int slot = findMenuSlotByName(h, c.orderDeliverAllText);
                    if (slot < 0) slot = c.orderDeliverAllButtonSlot;
                    if (slot < 0 || slot >= h.slots.size()) {
                        Helper.msg("Button 'Ganzes Inventar liefern' nicht gefunden.");
                        p.closeHandledScreen();
                        cooldown = 10;
                        nextOrderOrFinish();
                        return;
                    }
                    click(mc, p, slot, SlotActionType.PICKUP);
                    deliverClicked = true;
                    stateTicks = 0;
                    cooldown = 15; // Server Zeit zum Aktualisieren geben
                    return;
                }
                // Schritt 2: "Bestaetigen" (erscheint erst nach Schritt 1)
                if (!confirmClicked) {
                    int slot = findMenuSlotByName(h, c.orderConfirmText);
                    if (slot < 0 && stateTicks > 40 && c.orderConfirmButtonSlot >= 0
                            && c.orderConfirmButtonSlot < h.slots.size()) {
                        slot = c.orderConfirmButtonSlot;
                    }
                    if (slot >= 0) {
                        click(mc, p, slot, SlotActionType.PICKUP);
                        confirmClicked = true;
                        cooldown = 15;
                        return;
                    }
                    if (stateTicks > c.screenTimeoutTicks) {
                        Helper.msg("Bestaetigen-Button nicht gefunden (evtl. nichts zu liefern).");
                        p.closeHandledScreen();
                        cooldown = 10;
                        nextOrderOrFinish();
                    }
                    return;
                }
                if (guiOpen(p)) p.closeHandledScreen();
                cooldown = 10;
                requeueIfLeft(p, c);
                nextOrderOrFinish();
            }
            case FINISH -> {
                // Spawner war bei Inventar-voll noch nicht leer -> naechste Runde
                if (roundHadFull && roundClicks > 0 && round < c.maxRounds) {
                    List<BlockPos> list = Helper.findSpawners(p, mc.world, c.spawnerScanRadius, 4.5);
                    if (!list.isEmpty()) {
                        round++;
                        spawners = list;
                        spIdx = 0;
                        inventoryFull = false;
                        roundHadFull = false;
                        roundClicks = 0;
                        orderRepeats.clear();
                        cooldown = 20;
                        Helper.msg("Spawner noch nicht leer - starte Runde " + round + ".");
                        setState(S.NEXT_SPAWNER);
                        return;
                    }
                }
                if (roundHadFull && roundClicks == 0) {
                    Helper.msg("Keine Fortschritte in dieser Runde (Inventar/Orders voll?) - Lauf beendet.");
                } else if (roundHadFull && round >= c.maxRounds) {
                    Helper.msg("Maximale Rundenzahl (" + c.maxRounds + ") erreicht - Lauf beendet.");
                }
                c.lastRunEpochMs = System.currentTimeMillis();
                c.save();
                Helper.msg("Spawner-Lauf abgeschlossen (" + round + " Runde(n)).");
                reset();
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ Spawner-Menue
    private static void withdraw(MinecraftClient mc, ClientPlayerEntity p, HugoConfig c) {
        if (!guiOpen(p)) { spIdx++; setState(S.NEXT_SPAWNER); return; }
        if (inventoryFull || !hasFreeSlot(p)) {
            inventoryFull = true;
            roundHadFull = true;
            Helper.msg("Inventar voll - entnehme nichts mehr, verarbeite jetzt das Inventar.");
            p.closeHandledScreen();
            spIdx = spawners.size(); // restliche Spawner ueberspringen
            cooldown = 10;
            setState(S.NEXT_SPAWNER);
            return;
        }
        if (withdrawClicks > 400) {
            Helper.msg("Zu viele Klicks bei diesem Spawner - weiter zum naechsten.");
            p.closeHandledScreen();
            spIdx++;
            cooldown = 10;
            setState(S.NEXT_SPAWNER);
            return;
        }
        ScreenHandler h = p.currentScreenHandler;
        boolean viaButton = c.spawnerMenuSellButtonSlot >= 0;
        // Mit Sell-Button im Menue: nur Order-Items entnehmen, den Rest verkauft der Button.
        Set<String> wanted = viaButton ? c.idsWithMode(Mode.ORDER) : c.allIds();

        for (int i = 0; i < h.slots.size(); i++) {
            Slot s = h.slots.get(i);
            if (s.inventory instanceof PlayerInventory) continue;
            ItemStack st = s.getStack();
            if (st.isEmpty() || !wanted.contains(Helper.idOf(st))) continue;
            if (attempts.merge(i, 1, Integer::sum) > 3) continue;
            click(mc, p, i, SlotActionType.QUICK_MOVE);
            withdrawClicks++;
            roundClicks++;
            cooldown = c.clickDelayTicks;
            return;
        }
        if (c.spawnerMenuNextPageSlot >= 0 && page < c.spawnerMenuMaxPages - 1) {
            page++;
            attempts.clear();
            click(mc, p, c.spawnerMenuNextPageSlot, SlotActionType.PICKUP);
            cooldown = 8;
            return;
        }
        if (viaButton && !sellButtonClicked) {
            sellButtonClicked = true;
            click(mc, p, c.spawnerMenuSellButtonSlot, SlotActionType.PICKUP);
            cooldown = 10;
            return;
        }
        p.closeHandledScreen();
        spIdx++;
        cooldown = 10;
        setState(S.NEXT_SPAWNER);
    }

    private static void afterSpawners(MinecraftClient mc, ClientPlayerEntity p, HugoConfig c) {
        orderQueue.clear();
        for (ItemRule r : c.items) {
            if (r.mode != Mode.ORDER) continue;
            Item it = Registries.ITEM.get(Identifier.of(r.item));
            if (Helper.count(p, it) > 0) orderQueue.add(r);
        }
        boolean sellNeeded = c.spawnerMenuSellButtonSlot < 0 && hasAny(p, c.idsWithMode(Mode.SELL));
        if (sellNeeded) setState(S.SELL_CMD);
        else nextOrderOrFinish();
    }

    private static boolean hasAny(ClientPlayerEntity p, Set<String> ids) {
        for (String id : ids) {
            if (Helper.count(p, Registries.ITEM.get(Identifier.of(id))) > 0) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Orders
    private static void nextOrderOrFinish() {
        MinecraftClient mc = MinecraftClient.getInstance();
        while (!orderQueue.isEmpty()) {
            currentOrder = orderQueue.poll();
            Item it = Registries.ITEM.get(Identifier.of(currentOrder.item));
            if (mc.player != null && Helper.count(mc.player, it) > 0) {
                setState(S.ORDER_CMD);
                return;
            }
        }
        setState(S.FINISH);
    }

    private static void pickOrder(MinecraftClient mc, ClientPlayerEntity p, HugoConfig c) {
        if (!guiOpen(p)) { nextOrderOrFinish(); return; }
        ScreenHandler h = p.currentScreenHandler;
        Item target = Registries.ITEM.get(Identifier.of(currentOrder.item));
        String needle = c.specificOrderText.toLowerCase(Locale.ROOT);

        int best = -1;
        double bestPrice = -2;
        for (int i = 0; i < h.slots.size(); i++) {
            Slot s = h.slots.get(i);
            if (s.inventory instanceof PlayerInventory) continue;
            ItemStack st = s.getStack();
            if (st.isEmpty() || !st.isOf(target)) continue;
            if (c.orderMode == OrderTarget.SPECIFIC
                    && !Helper.fullText(st).toLowerCase(Locale.ROOT).contains(needle)) continue;
            double price = Helper.parsePrice(st);
            if (price > bestPrice) { bestPrice = price; best = i; }
        }
        if (best >= 0) {
            lastSyncId = h.syncId;
            click(mc, p, best, SlotActionType.PICKUP);
            cooldown = 4;
            setState(S.WAIT_DEPOSIT_GUI);
            return;
        }
        if (c.orderNextPageSlot >= 0 && page < c.orderMaxPages - 1) {
            page++;
            click(mc, p, c.orderNextPageSlot, SlotActionType.PICKUP);
            cooldown = 8;
            return;
        }
        Helper.msg("Keine passende Order fuer " + currentOrder.item + " gefunden.");
        p.closeHandledScreen();
        cooldown = 10;
        nextOrderOrFinish();
    }

    // ------------------------------------------------------------------ Hilfsfunktion
    /** Sind nach der Lieferung noch Items uebrig (Order war voll), wird die naechsthoechste Order versucht. */
    private static void requeueIfLeft(ClientPlayerEntity p, HugoConfig c) {
        if (c.orderMode != OrderTarget.HIGHEST || currentOrder == null) return;
        Item it = Registries.ITEM.get(Identifier.of(currentOrder.item));
        if (Helper.count(p, it) <= 0) return;
        int n = orderRepeats.merge(currentOrder.item, 1, Integer::sum);
        if (n <= c.orderMaxRepeats) {
            orderQueue.addFirst(currentOrder);
            Helper.msg("Noch Items uebrig - versuche die naechste Order (" + n + "/" + c.orderMaxRepeats + ").");
        }
    }

    private static boolean hasFreeSlot(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < Math.min(36, inv.size()); i++) if (inv.getStack(i).isEmpty()) return true;
        return false;
    }

    private static int findMenuSlotByName(ScreenHandler h, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        for (int i = 0; i < h.slots.size(); i++) {
            Slot s = h.slots.get(i);
            if (s.inventory instanceof PlayerInventory) continue;
            ItemStack st = s.getStack();
            if (st.isEmpty()) continue;
            if (st.getName().getString().toLowerCase(Locale.ROOT).contains(n)) return i;
        }
        return -1;
    }

    private static int findInvSlot(ScreenHandler h, Set<String> ids) {
        for (int i = 0; i < h.slots.size(); i++) {
            Slot s = h.slots.get(i);
            if (!(s.inventory instanceof PlayerInventory)) continue;
            ItemStack st = s.getStack();
            if (st.isEmpty() || !ids.contains(Helper.idOf(st))) continue;
            if (attempts.getOrDefault(i, 0) >= 3) continue;
            return i;
        }
        return -1;
    }
}
