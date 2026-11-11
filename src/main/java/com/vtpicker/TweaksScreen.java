package com.vtpicker;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.vtpicker.VanillaTweaks.Category;
import com.vtpicker.VanillaTweaks.Pack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The Vanilla Tweaks resource pack picker in game, laid out like the site's: categories on the left, a grid of
 * pack tiles (icon + name, grey when picked) with sub-category headers, description on hover.
 */
public class TweaksScreen extends Screen {

    // Site colours (selector.css): tiles rgba(26,26,26,.8), picked rgba(163,163,163,.7).
    private static final int TILE = 0xCC1A1A1A;
    private static final int TILE_HOVER = 0xCC303030;
    private static final int TILE_PICKED = 0xB3A3A3A3;
    private static final int PANEL = 0x99000000;
    private static final int ACCENT = 0xFF4FD64F;
    private static final int MUTED = 0xFFA0A0A0;

    private static final int TOP = 34;
    private static final int BOTTOM = 52;
    private static final int SIDEBAR = 130;
    private static final int ROW = 14;
    private static final int TILE_W = 66;
    private static final int TILE_H = 62;
    private static final int GAP = 4;
    private static final int HEADER_H = 16;

    /** Icons stay loaded for the whole game session; null = being fetched or failed. */
    private static final Map<String, Icon> ICONS = new ConcurrentHashMap<>();
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

    private record Icon(Identifier id, int width, int height) {
    }

    private record Tile(Pack pack, int x, int y) {
    }

    private record Header(String title, int depth, int y) {
    }

    @Nullable
    private final Screen parent;
    private List<Category> categories = List.of();
    private final Map<String, Pack> packsByName = new HashMap<>();
    private final Set<String> picked = VanillaTweaks.loadSelection();
    private Set<String> applied = new LinkedHashSet<>(this.picked);
    private int category;
    private String status = "Loading the pack list from vanillatweaks.net…";
    private boolean busy = true;

    // Layout of the current category, in content coordinates (before scrolling).
    private final List<Tile> tiles = new ArrayList<>();
    private final List<Header> headers = new ArrayList<>();
    private int contentHeight;
    private double scroll;
    private double sidebarScroll;
    private Button applyButton;

    public TweaksScreen(@Nullable Screen parent) {
        super(Component.literal("Vanilla Tweaks"));
        this.parent = parent;
        VanillaTweaks.fetchCategories().whenComplete((list, error) -> Minecraft.getInstance().execute(() -> {
            this.busy = false;
            if (error != null) {
                this.status = "Could not reach vanillatweaks.net. Check your connection.";
                VanillaTweaks.LOG.warn("Pack list failed", error);
                return;
            }
            this.categories = list;
            list.forEach(this::index);
            this.picked.retainAll(this.packsByName.keySet());
            this.status = "";
            this.layout();
        }));
    }

    private void index(Category c) {
        c.packs().forEach(p -> this.packsByName.put(p.name(), p));
        c.children().forEach(this::index);
    }

    @Override
    protected void init() {
        int y = this.height - 28;
        int w = 100;
        int x = this.width / 2 - (3 * w + 8) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Clear all"), b -> this.picked.clear())
            .bounds(x, y, w, 20).build());
        this.applyButton = this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> this.apply())
            .bounds(x + w + 4, y, w, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(x + 2 * (w + 4), y, w, 20).build());
        this.layout();
    }

    // ---- layout ----

    private int contentLeft() {
        return SIDEBAR + 16;
    }

    private int contentRight() {
        return this.width - 12;
    }

    private int contentBottom() {
        return this.height - BOTTOM;
    }

    private void layout() {
        this.tiles.clear();
        this.headers.clear();
        if (this.categories.isEmpty()) {
            return;
        }
        this.category = Math.min(this.category, this.categories.size() - 1);
        int[] y = {4};
        this.layoutCategory(this.categories.get(this.category), y);
        this.contentHeight = y[0];
        this.clampScroll();
    }

    private void layoutCategory(Category c, int[] y) {
        if (c.depth() > 0) {
            this.headers.add(new Header(c.title(), c.depth(), y[0]));
            y[0] += HEADER_H;
        }
        int columns = Math.max(1, (this.contentRight() - this.contentLeft() - 6 + GAP) / (TILE_W + GAP));
        for (int i = 0; i < c.packs().size(); i++) {
            Pack p = c.packs().get(i);
            this.tiles.add(new Tile(p, (i % columns) * (TILE_W + GAP), y[0] + (i / columns) * (TILE_H + GAP)));
            requestIcon(p.name());
        }
        if (!c.packs().isEmpty()) {
            y[0] += ((c.packs().size() + columns - 1) / columns) * (TILE_H + GAP) + 4;
        }
        c.children().forEach(child -> this.layoutCategory(child, y));
    }

    private void clampScroll() {
        int view = this.contentBottom() - TOP;
        this.scroll = Math.max(0, Math.min(this.scroll, this.contentHeight - view));
        int sidebarView = this.contentBottom() - TOP;
        this.sidebarScroll = Math.max(0, Math.min(this.sidebarScroll, this.categories.size() * ROW + 8 - sidebarView));
    }

    // ---- icons ----

    private static void requestIcon(String name) {
        if (!REQUESTED.add(name)) {
            return;
        }
        String url = VanillaTweaks.SITE + "/assets/resources/icons/resourcepacks/" + VanillaTweaks.VERSION + "/"
            + name + ".png";
        VanillaTweaks.getBytes(url).thenAccept(bytes -> {
            try {
                NativeImage image = NativeImage.read(bytes);
                Minecraft.getInstance().execute(() -> {
                    Identifier id = Identifier.fromNamespaceAndPath("vtpicker",
                        "icon/" + name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
                    int w = image.getWidth();
                    int h = image.getHeight();
                    Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "vtpicker " + name, image));
                    ICONS.put(name, new Icon(id, w, h));
                });
            } catch (java.io.IOException e) {
                VanillaTweaks.LOG.debug("Icon {} unreadable", name);
            }
        }).exceptionally(e -> {
            VanillaTweaks.LOG.debug("Icon {} failed: {}", name, e.toString());
            return null;
        });
    }

    // ---- picking and applying ----

    private void toggle(Pack pack) {
        if (this.picked.remove(pack.name())) {
            return;
        }
        // Like the site: picking a pack drops the ones it can't be combined with.
        List<String> dropped = new ArrayList<>();
        for (String other : pack.incompatible()) {
            if (this.picked.remove(other)) {
                Pack p = this.packsByName.get(other);
                dropped.add(p != null ? p.display() : other);
            }
        }
        this.picked.add(pack.name());
        this.status = dropped.isEmpty() ? "" : "Replaced " + String.join(", ", dropped);
    }

    private void apply() {
        List<Pack> selected = this.picked.stream().map(this.packsByName::get).toList();
        Set<String> snapshot = new LinkedHashSet<>(this.picked);
        this.busy = true;
        this.status = selected.isEmpty() ? "Removing the pack…" : "Building your pack on vanillatweaks.net…";
        VanillaTweaks.download(selected).whenComplete((zip, error) -> Minecraft.getInstance().execute(() -> {
            this.busy = false;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                this.status = "Failed: " + cause.getMessage();
                VanillaTweaks.LOG.warn("Applying failed", error);
                return;
            }
            VanillaTweaks.saveSelection(snapshot);
            this.applied = snapshot;
            this.status = snapshot.isEmpty() ? "Vanilla Tweaks pack removed" : "Applied " + snapshot.size() + " tweaks";
            VanillaTweaks.enable(zip);
        }));
    }

    // ---- drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        this.applyButton.active = !this.busy && !this.categories.isEmpty() && !this.picked.equals(this.applied);
        this.applyButton.setMessage(Component.literal(this.picked.equals(this.applied) || this.busy ? "Apply"
            : "Apply (" + this.picked.size() + ")"));
        super.extractRenderState(g, mouseX, mouseY, a);

        g.centeredText(this.font, Component.literal("Vanilla Tweaks").withStyle(s -> s.withBold(true)), this.width / 2, 8, 0xFFFFFFFF);
        g.centeredText(this.font, "Resource Packs · " + VanillaTweaks.VERSION + " · " + this.picked.size() + " selected",
            this.width / 2, 20, MUTED);
        if (!this.status.isEmpty()) {
            g.centeredText(this.font, this.status, this.width / 2, this.height - 42,
                this.status.startsWith("Failed") || this.status.startsWith("Could") ? 0xFFFF6060 : ACCENT);
        }

        this.drawSidebar(g, mouseX, mouseY);
        this.drawContent(g, mouseX, mouseY);
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int left = 8;
        int bottom = this.contentBottom();
        g.fill(left, TOP, left + SIDEBAR, bottom, PANEL);
        g.enableScissor(left, TOP, left + SIDEBAR, bottom);
        for (int i = 0; i < this.categories.size(); i++) {
            Category c = this.categories.get(i);
            int y = TOP + 4 + i * ROW - (int) this.sidebarScroll;
            boolean hover = mouseX >= left && mouseX < left + SIDEBAR && mouseY >= y && mouseY < y + ROW
                && mouseY >= TOP && mouseY < bottom;
            if (i == this.category) {
                g.fill(left, y, left + SIDEBAR, y + ROW, TILE_PICKED);
                g.fill(left, y, left + 2, y + ROW, ACCENT);
            } else if (hover) {
                g.fill(left, y, left + SIDEBAR, y + ROW, 0x40FFFFFF);
            }
            int count = this.countPicked(c);
            String n = count > 0 ? String.valueOf(count) : "";
            g.text(this.font, this.font.plainSubstrByWidth(c.title(), SIDEBAR - 14 - this.font.width(n)), left + 7, y + 3,
                0xFFFFFFFF, true);
            g.text(this.font, n, left + SIDEBAR - 5 - this.font.width(n), y + 3, ACCENT, true);
        }
        g.disableScissor();
    }

    private int countPicked(Category c) {
        int n = 0;
        for (Pack p : c.packs()) {
            if (this.picked.contains(p.name())) {
                n++;
            }
        }
        for (Category child : c.children()) {
            n += this.countPicked(child);
        }
        return n;
    }

    private void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int left = this.contentLeft();
        int right = this.contentRight();
        int bottom = this.contentBottom();
        g.fill(left - 4, TOP, right, bottom, PANEL);
        if (this.categories.isEmpty()) {
            return;
        }
        g.enableScissor(left - 4, TOP, right, bottom);
        int dy = TOP - (int) this.scroll;
        for (Header h : this.headers) {
            int y = dy + h.y() + 4;
            int x = left + (h.depth() - 1) * 8;
            g.text(this.font, h.title(), x, y, h.depth() == 1 ? 0xFFFFFFFF : 0xFFD0D0D0, true);
            g.fill(x + this.font.width(h.title()) + 6, y + 4, right - 10, y + 5, 0x40FFFFFF);
        }
        Pack hovered = null;
        boolean inView = mouseY >= TOP && mouseY < bottom && mouseX >= left - 4 && mouseX < right;
        for (Tile t : this.tiles) {
            int x = left + t.x();
            int y = dy + t.y();
            if (y > bottom || y + TILE_H < TOP) {
                continue;
            }
            boolean hover = inView && mouseX >= x && mouseX < x + TILE_W && mouseY >= y && mouseY < y + TILE_H;
            boolean on = this.picked.contains(t.pack().name());
            g.fill(x, y, x + TILE_W, y + TILE_H, on ? TILE_PICKED : hover ? TILE_HOVER : TILE);
            if (on) {
                g.outline(x, y, TILE_W, TILE_H, 0xFFFFFFFF);
            }
            Icon icon = ICONS.get(t.pack().name());
            int ix = x + (TILE_W - 32) / 2;
            if (icon != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, icon.id(), ix, y + 5, 0, 0, 32, 32, icon.width(), icon.height(),
                    icon.width(), icon.height());
            } else {
                g.fill(ix, y + 5, ix + 32, y + 37, 0x30FFFFFF);
            }
            List<FormattedCharSequence> lines = this.font.split(Component.literal(t.pack().display()), TILE_W - 4);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                g.centeredText(this.font, lines.get(i), x + TILE_W / 2, y + 40 + i * 10, 0xFFFFFFFF);
            }
            if (hover) {
                hovered = t.pack();
            }
        }
        g.disableScissor();
        this.drawScrollbar(g, right, bottom);
        if (hovered != null) {
            g.setTooltipForNextFrame(this.tooltip(hovered), mouseX, mouseY);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int right, int bottom) {
        int view = bottom - TOP;
        if (this.contentHeight <= view) {
            return;
        }
        int bar = Math.max(16, view * view / this.contentHeight);
        int y = TOP + (int) ((view - bar) * this.scroll / (this.contentHeight - view));
        g.fill(right - 4, TOP, right - 1, bottom, 0x40000000);
        g.fill(right - 4, y, right - 1, y + bar, 0xFFC0C0C0);
    }

    private List<FormattedCharSequence> tooltip(Pack pack) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.literal(pack.display()).withStyle(s -> s.withBold(true)).getVisualOrderText());
        for (String paragraph : pack.description().split("\n")) {
            if (!paragraph.isBlank()) {
                lines.addAll(this.font.split(Component.literal(paragraph).withColor(0xC0C0C0), 220));
            }
        }
        List<String> clashes = pack.incompatible().stream()
            .map(n -> this.packsByName.containsKey(n) ? this.packsByName.get(n).display() : n).toList();
        if (!clashes.isEmpty()) {
            lines.addAll(this.font.split(Component.literal("Incompatible with: " + String.join(", ", clashes))
                .withColor(0xFFB547), 220));
        }
        return lines;
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || this.categories.isEmpty()) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (my < TOP || my >= this.contentBottom()) {
            return false;
        }
        if (mx >= 8 && mx < 8 + SIDEBAR) {
            int i = (int) ((my - TOP - 4 + this.sidebarScroll) / ROW);
            if (i >= 0 && i < this.categories.size() && i != this.category) {
                this.category = i;
                this.scroll = 0;
                this.layout();
            }
            return true;
        }
        int left = this.contentLeft();
        for (Tile t : this.tiles) {
            int x = left + t.x();
            int y = TOP - (int) this.scroll + t.y();
            if (mx >= x && mx < x + TILE_W && my >= y && my < y + TILE_H) {
                this.toggle(t.pack());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx < 8 + SIDEBAR) {
            this.sidebarScroll -= sy * ROW;
        } else {
            this.scroll -= sy * (TILE_H + GAP) / 2.0;
        }
        this.clampScroll();
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
