package net.hugosmp.autospawner.gui;

import net.hugosmp.autospawner.automation.SpawnerRoutine;
import net.hugosmp.autospawner.config.HugoConfig;
import net.hugosmp.autospawner.config.HugoConfig.OrderTarget;
import net.hugosmp.autospawner.util.Helper;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class MainConfigScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget intervalField, radiusField, orderPlayerField, specificField;

    public MainConfigScreen(Screen parent) {
        super(Text.literal("HugoSMP AutoSpawner"));
        this.parent = parent;
    }

    private static Text onOff(String label, boolean v) {
        return Text.literal(label + ": " + (v ? "\u00a7aAN" : "\u00a7cAUS"));
    }

    private static Text orderText(OrderTarget t) {
        return Text.literal("Order-Ziel: " + (t == OrderTarget.HIGHEST ? "\u00a7bHoechste Order" : "\u00a7eBestimmte Order"));
    }

    @Override
    protected void init() {
        HugoConfig c = HugoConfig.get();
        int cx = width / 2, left = cx - 155, right = cx + 5, w = 150, step = 28;
        int y = 36;

        addDrawableChild(ButtonWidget.builder(onOff("Auto-System", c.enabled), b -> {
            c.enabled = !c.enabled;
            b.setMessage(onOff("Auto-System", c.enabled));
        }).dimensions(left, y, w, 20).build());

        addDrawableChild(ButtonWidget.builder(onOff("Trusted-Schutz", c.guardEnabled), b -> {
            c.guardEnabled = !c.guardEnabled;
            b.setMessage(onOff("Trusted-Schutz", c.guardEnabled));
        }).dimensions(left, y + step, w, 20).build());

        addDrawableChild(ButtonWidget.builder(orderText(c.orderMode), b -> {
            c.orderMode = c.orderMode == OrderTarget.HIGHEST ? OrderTarget.SPECIFIC : OrderTarget.HIGHEST;
            b.setMessage(orderText(c.orderMode));
        }).dimensions(left, y + 2 * step, w, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Items & Modi ..."), b -> {
            applyFields();
            client.setScreen(new ItemRulesScreen(this));
        }).dimensions(left, y + 3 * step, w, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Trusted-Spieler ..."), b -> {
            applyFields();
            client.setScreen(new TrustedScreen(this));
        }).dimensions(left, y + 4 * step, w, 20).build());

        int fy = 46, fstep = 34;
        intervalField = field(right, fy, w, String.valueOf(c.intervalHours));
        radiusField = field(right, fy + fstep, w, String.valueOf(c.guardRadius));
        orderPlayerField = field(right, fy + 2 * fstep, w, c.orderPlayer);
        specificField = field(right, fy + 3 * fstep, w, c.specificOrderText);

        addDrawableChild(ButtonWidget.builder(Text.literal("Jetzt ausfuehren"), b -> {
            applyFields();
            client.setScreen(null);
            SpawnerRoutine.start(client, "Manuell");
        }).dimensions(cx - 155, height - 28, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Speichern & schliessen"), b -> close())
                .dimensions(cx - 50, height - 28, 205, 20).build());
    }

    private TextFieldWidget field(int x, int y, int w, String value) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 20, Text.empty());
        f.setMaxLength(64);
        f.setText(value == null ? "" : value);
        addDrawableChild(f);
        return f;
    }

    private void applyFields() {
        HugoConfig c = HugoConfig.get();
        try { c.intervalHours = Double.parseDouble(intervalField.getText().trim().replace(',', '.')); }
        catch (Exception ignored) { }
        try { c.guardRadius = Double.parseDouble(radiusField.getText().trim().replace(',', '.')); }
        catch (Exception ignored) { }
        String op = orderPlayerField.getText().trim();
        if (op.isEmpty() || HugoConfig.PLAYER_NAME.matcher(op).matches()) c.orderPlayer = op;
        else Helper.msg("Ungueltiger Order-Spielername (3-16 Zeichen: A-Z, 0-9, _).");
        c.specificOrderText = specificField.getText().trim();
        c.apply();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2, right = cx + 5;
        ctx.drawCenteredTextWithShadow(textRenderer, title, cx, 14, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Intervall (Stunden)"), right, 34, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Trusted-Radius (Bloecke)"), right, 68, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Order-Spieler (/order NAME)"), right, 102, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Bestimmte Order (Text)"), right, 136, 0xFFAAAAAA);
    }

    @Override
    public void close() {
        applyFields();
        client.setScreen(parent);
    }
}
