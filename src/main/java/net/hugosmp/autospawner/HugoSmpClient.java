package net.hugosmp.autospawner;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.hugosmp.autospawner.automation.GuardService;
import net.hugosmp.autospawner.automation.SpawnerRoutine;
import net.hugosmp.autospawner.config.HugoConfig;
import net.hugosmp.autospawner.gui.MainConfigScreen;
import net.hugosmp.autospawner.util.Helper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandRegistryAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HugoSmpClient implements ClientModInitializer {
    public static final String MOD_ID = "hugosmp_autospawner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static int debugCountdown = -1;

    @Override
    public void onInitializeClient() {
        HugoConfig.load();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            GuardService.reset();
            SpawnerRoutine.reset();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SpawnerRoutine.reset();
            GuardService.reset();
        });
        ClientTickEvents.END_CLIENT_TICK.register(HugoSmpClient::onTick);
        ClientCommandRegistrationCallback.EVENT.register(HugoSmpClient::registerCommands);

        LOGGER.info("HugoSMP AutoSpawner geladen. Befehle: /hugosmp gui|start|stop|status|reload|debug");
    }

    private static void onTick(MinecraftClient mc) {
        try {
            if (mc.player == null || mc.world == null) return;

            GuardService.tick(mc);
            SpawnerRoutine.tick(mc);

            if (debugCountdown >= 0 && --debugCountdown < 0) Helper.dumpScreen(mc);

            // Zeitplaner
            HugoConfig c = HugoConfig.get();
            if (!c.enabled || SpawnerRoutine.isRunning() || GuardService.isAlarm()) return;
            if (mc.player.age < 200 || mc.currentScreen != null || !Helper.onTargetServer(mc)) return;
            long due = c.lastRunEpochMs + (long) (c.intervalHours * 3_600_000L);
            if (System.currentTimeMillis() >= due) SpawnerRoutine.start(mc, "Intervall");
        } catch (Throwable t) {
            LOGGER.error("Fehler im Tick-Handler", t);
        }
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> d, CommandRegistryAccess ra) {
        d.register(ClientCommandManager.literal("hugosmp")
                .then(ClientCommandManager.literal("gui").executes(ctx -> {
                    MinecraftClient mc = ctx.getSource().getClient();
                    mc.send(() -> mc.setScreen(new MainConfigScreen(null)));
                    return 1;
                }))
                .then(ClientCommandManager.literal("start").executes(ctx -> {
                    SpawnerRoutine.start(ctx.getSource().getClient(), "Manuell");
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    SpawnerRoutine.abort(ctx.getSource().getClient(), "Manuell gestoppt");
                    return 1;
                }))
                .then(ClientCommandManager.literal("reload").executes(ctx -> {
                    HugoConfig.load();
                    Helper.msg("Config neu geladen.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    HugoConfig c = HugoConfig.get();
                    long left = c.lastRunEpochMs + (long) (c.intervalHours * 3_600_000L) - System.currentTimeMillis();
                    Helper.msg("Auto-System: " + (c.enabled ? "AN" : "AUS")
                            + " | Lauf aktiv: " + SpawnerRoutine.isRunning()
                            + " | naechster Lauf in " + Math.max(0, left / 60_000) + " min"
                            + " | Schutz: " + (c.guardEnabled ? "AN" : "AUS")
                            + (GuardService.isAlarm() ? " | ALARM" : ""));
                    return 1;
                }))
                .then(ClientCommandManager.literal("debug").executes(ctx -> {
                    debugCountdown = 100; // 5 Sekunden Zeit, ein Menue zu oeffnen
                    Helper.msg("Oeffne jetzt ein Menue (Spawner, /sell, /order). Dump in 5 Sekunden.");
                    return 1;
                })));
    }
}
