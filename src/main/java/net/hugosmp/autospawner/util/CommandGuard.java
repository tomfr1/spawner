package net.hugosmp.autospawner.util;

import net.hugosmp.autospawner.HugoSmpClient;
import net.hugosmp.autospawner.config.HugoConfig;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Einziger Weg, wie die Mod Server-Befehle sendet:
 * Whitelist (sell, order, spawn), Argument-Validierung, Mindestabstand, Limit pro Minute.
 */
public final class CommandGuard {
    private CommandGuard() {}

    private static final Set<String> ALLOWED = Set.of("sell", "order", "spawn");
    private static final Pattern FORMAT = Pattern.compile("^[a-z]{1,10}( [A-Za-z0-9_.]{1,20})?$");
    private static final int MAX_PER_MINUTE = 20;

    private static long lastSentMs = 0;
    private static final Deque<Long> window = new ArrayDeque<>();

    public static synchronized boolean send(MinecraftClient mc, String raw, boolean urgent) {
        if (mc.player == null || mc.getNetworkHandler() == null || raw == null) return false;

        String cmd = raw.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (!FORMAT.matcher(cmd).matches()) {
            HugoSmpClient.LOGGER.warn("Befehl abgelehnt (Format): '{}'", raw);
            return false;
        }
        String[] parts = cmd.split(" ");
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(root)) {
            HugoSmpClient.LOGGER.warn("Befehl abgelehnt (nicht erlaubt): '{}'", raw);
            return false;
        }
        if (root.equals("order") && parts.length == 2
                && !HugoConfig.PLAYER_NAME.matcher(parts[1]).matches()) {
            HugoSmpClient.LOGGER.warn("Befehl abgelehnt (Spielername): '{}'", raw);
            return false;
        }
        if (!root.equals("order") && parts.length > 1) return false;

        long now = System.currentTimeMillis();
        if (!urgent) {
            if (now - lastSentMs < HugoConfig.get().commandCooldownMs) return false;
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) window.pollFirst();
            if (window.size() >= MAX_PER_MINUTE) return false;
        }

        mc.getNetworkHandler().sendChatCommand(cmd);
        lastSentMs = now;
        window.addLast(now);
        HugoSmpClient.LOGGER.info("Befehl gesendet: /{}", cmd);
        return true;
    }
}
