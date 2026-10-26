package net.hugosmp.autospawner.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.hugosmp.autospawner.HugoSmpClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Konfiguration, gespeichert unter .minecraft/config/hugosmp-autospawner.json
 */
public class HugoConfig {

    public enum Mode { SELL, ORDER }

    public enum OrderTarget { HIGHEST, SPECIFIC }

    public static class ItemRule {
        public String item = "minecraft:bone";
        public Mode mode = Mode.SELL;
        /** Optional: ueberschreibt den globalen Order-Spielernamen nur fuer dieses Item. */
        public String orderPlayer = "";

        public ItemRule() {}

        public ItemRule(String item, Mode mode) {
            this.item = item;
            this.mode = mode;
        }
    }

    public static final Pattern PLAYER_NAME = Pattern.compile("^\\.?[A-Za-z0-9_]{3,16}$");

    // ---------------------------------------------------------------- Allgemein
    public boolean enabled = true;
    public String serverAddress = "hugosmp.net";
    public double intervalHours = 5.5;
    /** Wird automatisch gepflegt (Zeitpunkt des letzten Laufs). */
    public long lastRunEpochMs = 0;

    // ---------------------------------------------------------------- Items / Orders
    public List<ItemRule> items = new ArrayList<>(List.of(
            new ItemRule("minecraft:bone", Mode.SELL),
            new ItemRule("minecraft:gunpowder", Mode.SELL)
    ));
    public OrderTarget orderMode = OrderTarget.HIGHEST;
    /** Globaler Spielername fuer /order <Name>. */
    public String orderPlayer = "";
    /** Nur bei orderMode=SPECIFIC: Text, der im Namen/Lore der Order vorkommen muss. */
    public String specificOrderText = "";

    // ---------------------------------------------------------------- Schutz
    public boolean guardEnabled = true;
    public List<String> trustedPlayers = new ArrayList<>();
    public double guardRadius = 30.0;
    public boolean requireSilkTouch = true;
    /** Hotbar-Slot (0-8) der Silk-Touch-Spitzhacke, -1 = automatisch suchen. */
    public int silkPickaxeHotbarSlot = -1;
    public int emergencyMineTimeoutSec = 25;
    public int postSpawnWaitTicks = 40;

    // ---------------------------------------------------------------- Technik / GUI-Layout
    public int commandCooldownMs = 1500;
    public int clickDelayTicks = 2;
    public int screenTimeoutTicks = 100;
    /** Suchradius (Bloecke) fuer Spawner; Reichweite ist hart auf 4.5 begrenzt. */
    public int spawnerScanRadius = 5;
    /** Slot des "Naechste Seite"-Buttons im Spawner-Menue, -1 = keine Seiten. */
    public int spawnerMenuNextPageSlot = -1;
    public int spawnerMenuMaxPages = 5;
    /** Slot des "Alles verkaufen"-Buttons im Spawner-Menue, -1 = stattdessen /sell nutzen. */
    public int spawnerMenuSellButtonSlot = -1;
    /** Slot eines Bestaetigen-Buttons im /sell-Menue, -1 = nur Menue schliessen. */
    public int sellConfirmSlot = -1;
    /** Order-Abgabe-Menue: Buttons werden zuerst am Namen erkannt, die Slots sind nur der Ersatz (-1 = aus). */
    public String orderDeliverAllText = "Ganzes Inventar liefern";
    public String orderConfirmText = "Best\u00e4tigen";
    public int orderDeliverAllButtonSlot = 6;
    public int orderConfirmButtonSlot = 8;
    /** Chat-Meldung des Servers, bei der das Entnehmen aus dem Spawner stoppt. */
    public String inventoryFullText = "Kein Platz im Inventar";
    /** Slot des "Naechste Seite"-Buttons in der Order-Liste, -1 = keine Seiten. */
    public int orderNextPageSlot = -1;
    public int orderMaxPages = 3;

    // ================================================================ Persistenz
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("hugosmp-autospawner.json");
    private static HugoConfig instance;

    public static synchronized HugoConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static synchronized void load() {
        HugoConfig cfg = null;
        try {
            if (Files.exists(FILE)) {
                cfg = GSON.fromJson(Files.readString(FILE), HugoConfig.class);
            }
        } catch (Exception e) {
            HugoSmpClient.LOGGER.error("Config konnte nicht gelesen werden, Sicherung wird angelegt.", e);
            try {
                Files.copy(FILE, FILE.resolveSibling(FILE.getFileName() + ".broken"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
        }
        if (cfg == null) cfg = new HugoConfig();
        cfg.sanitize();
        instance = cfg;
        cfg.save();
    }

    public synchronized void save() {
        try {
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this));
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            HugoSmpClient.LOGGER.error("Config konnte nicht gespeichert werden.", e);
        }
    }

    /** Bereinigt Werte und speichert. */
    public void apply() {
        sanitize();
        save();
    }

    public synchronized void sanitize() {
        if (serverAddress == null || serverAddress.isBlank()) serverAddress = "hugosmp.net";
        if (orderMode == null) orderMode = OrderTarget.HIGHEST;
        if (orderPlayer == null) orderPlayer = "";
        if (specificOrderText == null) specificOrderText = "";
        if (orderDeliverAllText == null || orderDeliverAllText.isBlank()) orderDeliverAllText = "Ganzes Inventar liefern";
        if (orderConfirmText == null || orderConfirmText.isBlank()) orderConfirmText = "Best\u00e4tigen";
        if (inventoryFullText == null || inventoryFullText.isBlank()) inventoryFullText = "Kein Platz im Inventar";
        if (!orderPlayer.isEmpty() && !PLAYER_NAME.matcher(orderPlayer).matches()) {
            HugoSmpClient.LOGGER.warn("Ungueltiger orderPlayer '{}' entfernt.", orderPlayer);
            orderPlayer = "";
        }

        if (items == null) items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        items.removeIf(r -> {
            if (r == null || r.item == null) return true;
            r.item = r.item.trim().toLowerCase(Locale.ROOT);
            if (!r.item.contains(":")) r.item = "minecraft:" + r.item;
            Identifier id = Identifier.tryParse(r.item);
            if (id == null || !Registries.ITEM.containsId(id)) {
                HugoSmpClient.LOGGER.warn("Unbekanntes Item '{}' in der Config entfernt.", r.item);
                return true;
            }
            if (!seen.add(r.item)) return true;
            if (r.mode == null) r.mode = Mode.SELL;
            if (r.orderPlayer == null || !PLAYER_NAME.matcher(r.orderPlayer).matches()) r.orderPlayer = "";
            return false;
        });

        if (trustedPlayers == null) trustedPlayers = new ArrayList<>();
        List<String> cleaned = new ArrayList<>();
        for (String n : trustedPlayers) {
            if (n != null && PLAYER_NAME.matcher(n.trim()).matches() && !containsIgnoreCase(cleaned, n.trim())) {
                cleaned.add(n.trim());
            }
        }
        trustedPlayers = cleaned;

        intervalHours = clamp(intervalHours, 0.1, 168);
        guardRadius = clamp(guardRadius, 5, 128);
        commandCooldownMs = (int) clamp(commandCooldownMs, 500, 10_000);
        clickDelayTicks = (int) clamp(clickDelayTicks, 1, 20);
        screenTimeoutTicks = (int) clamp(screenTimeoutTicks, 40, 600);
        spawnerScanRadius = (int) clamp(spawnerScanRadius, 1, 5);
        spawnerMenuMaxPages = (int) clamp(spawnerMenuMaxPages, 1, 20);
        orderMaxPages = (int) clamp(orderMaxPages, 1, 20);
        emergencyMineTimeoutSec = (int) clamp(emergencyMineTimeoutSec, 3, 120);
        postSpawnWaitTicks = (int) clamp(postSpawnWaitTicks, 0, 400);
        if (silkPickaxeHotbarSlot < -1 || silkPickaxeHotbarSlot > 8) silkPickaxeHotbarSlot = -1;

        // Erster Start: Intervall beginnt jetzt, kein sofortiger Lauf.
        if (lastRunEpochMs <= 0) lastRunEpochMs = System.currentTimeMillis();
    }

    // ================================================================ Helfer
    public Set<String> idsWithMode(Mode m) {
        Set<String> out = new HashSet<>();
        for (ItemRule r : items) if (r.mode == m) out.add(r.item);
        return out;
    }

    public Set<String> allIds() {
        Set<String> out = new HashSet<>();
        for (ItemRule r : items) out.add(r.item);
        return out;
    }

    public boolean isTrusted(String name) {
        return containsIgnoreCase(trustedPlayers, name);
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String x : list) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

