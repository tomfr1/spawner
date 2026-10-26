package net.hugosmp.autospawner.gui;

import net.hugosmp.autospawner.config.HugoConfig;
import net.hugosmp.autospawner.config.HugoConfig.ItemRule;
import net.hugosmp.autospawner.config.HugoConfig.Mode;
import net.hugosmp.autospawner.util.Helper;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;

public class ItemRulesScreen extends Screen {
    private static final int PAGE = 6;
    private final Screen parent;
    private int page = 0;
    private TextFieldWidget idField;

    public ItemRulesScreen(Screen parent) {
        super(Text.literal("Items & Modi"));
        this.parent = parent;
    }

    private static Text modeText(Mode m) {
        return Text.literal(m == Mode.SELL ? "\u00a7aVerkaufen (/sell)" : "\u00a7bOrder");
    }

    @Override
    protected void init() {
        HugoConfig c = HugoConfig.get();
        List<ItemRule> items = c.items;
        int cx = width / 2;
        int maxPage = Math.max(0, (items.size() - 1) / PAGE);
        page = Math.min(page, maxPage);

        for (int i = 0; i < PAGE; i++) {
            int idx = page * PAGE + i;
            if (idx >= items.size()) break;
            ItemRule r = items.get(idx);
            int y = 30 + i * 24;
            addDrawableChild(ButtonWidget.builder(modeText(r.mode), b -> {
                r.mode = r.mode == Mode.SELL ? Mode.ORDER : Mode.SELL;
                b.setMessage(modeText(r.mode));
                c.apply();
            }).dimensions(cx + 10, y, 100, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> {
                items.remove(r);
                c.apply();
                clearAndInit();
            }).dimensions(cx + 115, y, 20, 20).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
            if (page > 0) { page--; clearAndInit(); }
        }).dimensions(cx - 155, 6, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
            if (page < maxPage) { page++; clearAndInit(); }
        }).dimensions(cx + 135, 6, 20, 20).build());

        idField = new TextFieldWidget(textRenderer, cx - 155, height - 52, 150, 20, Text.empty());
        idField.setMaxLength(64);
        idField.setPlaceholder(Text.literal("minecraft:bone"));
        addDrawableChild(idField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Hinzufuegen"), b -> addItem(idField.getText()))
                .dimensions(cx, height - 52, 75, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Item in Hand"), b -> {
            ItemStack s = client.player != null ? client.player.getMainHandStack() : ItemStack.EMPTY;
            if (s.isEmpty()) Helper.msg("Du haelst kein Item in der Hand.");
            else addItem(Helper.idOf(s));
        }).dimensions(cx + 80, height - 52, 75, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Zurueck"), b -> close())
                .dimensions(cx - 100, height - 28, 200, 20).build());
    }

    private void addItem(String raw) {
        HugoConfig c = HugoConfig.get();
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return;
        if (!id.contains(":")) id = "minecraft:" + id;
        Identifier ident = Identifier.tryParse(id);
        if (ident == null || !Registries.ITEM.containsId(ident)) {
            Helper.msg("Unbekanntes Item: " + id);
            return;
        }
        for (ItemRule r : c.items) {
            if (r.item.equals(id)) { Helper.msg("Item ist bereits in der Liste."); return; }
        }
        c.items.add(new ItemRule(id, Mode.SELL));
        c.apply();
        page = Math.max(0, (c.items.size() - 1) / PAGE);
        clearAndInit();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        HugoConfig c = HugoConfig.get();
        int cx = width / 2;
        int maxPage = Math.max(0, (c.items.size() - 1) / PAGE);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Items & Modi  (" + (page + 1) + "/" + (maxPage + 1) + ")"), cx, 12, 0xFFFFFFFF);

        for (int i = 0; i < PAGE; i++) {
            int idx = page * PAGE + i;
            if (idx >= c.items.size()) break;
            ItemRule r = c.items.get(idx);
            int y = 30 + i * 24;
            Item item = Registries.ITEM.get(Identifier.of(r.item));
            ctx.drawItem(new ItemStack(item), cx - 155, y + 2);
            ctx.drawTextWithShadow(textRenderer, item.getName(), cx - 135, y + 2, 0xFFFFFFFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal(r.item), cx - 135, y + 12, 0xFF888888);
        }
    }

    @Override
    public void close() {
        HugoConfig.get().apply();
        client.setScreen(parent);
    }
}
