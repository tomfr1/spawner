package net.hugosmp.autospawner.gui;

import net.hugosmp.autospawner.config.HugoConfig;
import net.hugosmp.autospawner.util.Helper;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

public class TrustedScreen extends Screen {
    private static final int PAGE = 6;
    private final Screen parent;
    private int page = 0;
    private TextFieldWidget nameField;

    public TrustedScreen(Screen parent) {
        super(Text.literal("Trusted-Spieler"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        HugoConfig c = HugoConfig.get();
        List<String> names = c.trustedPlayers;
        int cx = width / 2;
        int maxPage = Math.max(0, (names.size() - 1) / PAGE);
        page = Math.min(page, maxPage);

        for (int i = 0; i < PAGE; i++) {
            int idx = page * PAGE + i;
            if (idx >= names.size()) break;
            String n = names.get(idx);
            addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> {
                names.remove(n);
                c.apply();
                clearAndInit();
            }).dimensions(cx + 115, 30 + i * 24, 20, 20).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
            if (page > 0) { page--; clearAndInit(); }
        }).dimensions(cx - 155, 6, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
            if (page < maxPage) { page++; clearAndInit(); }
        }).dimensions(cx + 135, 6, 20, 20).build());

        nameField = new TextFieldWidget(textRenderer, cx - 155, height - 52, 110, 20, Text.empty());
        nameField.setMaxLength(17);
        nameField.setPlaceholder(Text.literal("Spielername"));
        addDrawableChild(nameField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Hinzufuegen"), b -> add(nameField.getText()))
                .dimensions(cx - 40, height - 52, 75, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Nahe Spieler"), b -> addNearby())
                .dimensions(cx + 40, height - 52, 115, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Zurueck"), b -> close())
                .dimensions(cx - 100, height - 28, 200, 20).build());
    }

    private void add(String raw) {
        HugoConfig c = HugoConfig.get();
        String n = raw == null ? "" : raw.trim();
        if (!HugoConfig.PLAYER_NAME.matcher(n).matches()) {
            Helper.msg("Ungueltiger Spielername (3-16 Zeichen: A-Z, 0-9, _).");
            return;
        }
        if (!c.isTrusted(n)) c.trustedPlayers.add(n);
        c.apply();
        page = Math.max(0, (c.trustedPlayers.size() - 1) / PAGE);
        clearAndInit();
    }

    private void addNearby() {
        if (client.world == null || client.player == null) return;
        HugoConfig c = HugoConfig.get();
        int added = 0;
        for (AbstractClientPlayerEntity o : client.world.getPlayers()) {
            if (o.getUuid().equals(client.player.getUuid())) continue;
            String n = o.getName().getString();
            if (HugoConfig.PLAYER_NAME.matcher(n).matches() && !c.isTrusted(n)) {
                c.trustedPlayers.add(n);
                added++;
            }
        }
        c.apply();
        Helper.msg(added + " Spieler zur Trusted-Liste hinzugefuegt.");
        clearAndInit();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        HugoConfig c = HugoConfig.get();
        int cx = width / 2;
        int maxPage = Math.max(0, (c.trustedPlayers.size() - 1) / PAGE);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Trusted-Spieler  (" + (page + 1) + "/" + (maxPage + 1) + ")"), cx, 12, 0xFFFFFFFF);
        for (int i = 0; i < PAGE; i++) {
            int idx = page * PAGE + i;
            if (idx >= c.trustedPlayers.size()) break;
            ctx.drawTextWithShadow(textRenderer, Text.literal(c.trustedPlayers.get(idx)),
                    cx - 150, 36 + i * 24, 0xFFFFFFFF);
        }
        if (c.trustedPlayers.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Noch keine Trusted-Spieler."),
                    cx, 60, 0xFF888888);
        }
    }

    @Override
    public void close() {
        HugoConfig.get().apply();
        client.setScreen(parent);
    }
}
