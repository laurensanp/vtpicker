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
import net.minecraft.client.gui.components.EditBox;
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

    // Flat and dark like Sodium's settings: translucent black panels, one accent colour (the icon's green).
    private static final int PANEL = 0x90000000;
    private static final int TILE = 0x18FFFFFF;
    private static final int HOVER = 0x30FFFFFF;
    private static final int TILE_PICKED = 0x504FD64F;
    private static final int ACCENT = 0xFF4FD64F;
    private static final int MUTED = 0xFFA0A0A0;
    /** Two frames of the icon (hips down, hips up), swapped every 160 ms like the GIF in the README. */
    private static final Identifier[] LOGO = {Identifier.fromNamespaceAndPath("vtpicker", "logo0"),
        Identifier.fromNamespaceAndPath("vtpicker", "logo1")};
    private static boolean logoLoaded;

    private static final int EDGE = 6;
    /** Top of the tile area (below the content panel's title strip). */
    private static final int TOP = 30;
    /** Top of the category list (below the sidebar's header with the icon). */
    private static final int SIDE_TOP = 44;
    private static final int BOTTOM = 34;
    private static final int SIDEBAR = 140;
    private static final int ROW = 16;
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
    private final Set<String> picked = VanillaTweaks.loadSelection("packs");
    private Set<String> applied = VanillaTweaks.loadSelection("applied");
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
    private EditBox searchBox;
    /** Not blank: the tiles are the packs of every category that match it. */
    private String query = "";

    public TweaksScreen(@Nullable Screen parent) {
        super(Component.literal("Vanilla Twerks"));
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
        int y = this.height - 27;
        int w = 64;
        int x = this.contentRight() - 3 * w - 2 * GAP;
        this.addRenderableWidget(new FlatButton(x, y, w, "Clear all", false, b -> {
            this.picked.clear();
            VanillaTweaks.saveSelection(this.picked, this.applied);
        }));
        this.applyButton = this.addRenderableWidget(new FlatButton(x + w + GAP, y, w, "Apply", true, b -> this.apply()));
        this.addRenderableWidget(new FlatButton(x + 2 * (w + GAP), y, w, "Done", false, b -> this.onClose()));
        this.searchBox = new EditBox(this.font, this.contentRight() - 108, EDGE + 4, 100, 16, Component.literal("Search"));
        this.searchBox.setHint(Component.literal("Search…"));
        this.searchBox.setMaxLength(60);
        this.searchBox.setValue(this.query);
        this.searchBox.setResponder(text -> {
            this.query = text;
            this.scroll = 0;
            this.layout();
        });
        this.addRenderableWidget(this.searchBox);
        this.layout();
    }

    /** Typing searches right away. */
    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.searchBox);
    }

    private boolean searching() {
        return !this.query.isBlank();
    }

    // ---- layout ----

    private int contentLeft() {
        return EDGE + SIDEBAR + 8;
    }

    private int contentRight() {
        return this.width - EDGE;
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
        if (this.searching()) {
            this.categories.forEach(c -> this.layoutSearch(c, "", y));
        } else {
            this.layoutCategory(this.categories.get(this.category), y);
        }
        this.contentHeight = y[0];
        this.clampScroll();
    }

    private void layoutCategory(Category c, int[] y) {
        if (c.depth() > 0) {
            this.headers.add(new Header(c.title(), c.depth(), y[0]));
            y[0] += HEADER_H;
        }
        this.grid(c.packs(), y);
        c.children().forEach(child -> this.layoutCategory(child, y));
    }

    /** Search: every category (sub-categories too) that has matching packs, under its full name. */
    private void layoutSearch(Category c, String parents, int[] y) {
        String path = parents.isEmpty() ? c.title() : parents + " › " + c.title();
        List<Pack> found = new ArrayList<>(c.packs().stream()
            .filter(p -> VanillaTweaks.matches(p, path, this.query)).toList());
        if (!found.isEmpty()) {
            // Packs whose own name matches come before those matching by description or category.
            found.sort(java.util.Comparator.comparing(p -> !VanillaTweaks.matches(p.display(), this.query)));
            this.headers.add(new Header(path, 1, y[0]));
            y[0] += HEADER_H;
            this.grid(found, y);
        }
        c.children().forEach(child -> this.layoutSearch(child, path, y));
    }

    private void grid(List<Pack> packs, int[] y) {
        int columns = Math.max(1, (this.contentRight() - this.contentLeft() - 6 + GAP) / (TILE_W + GAP));
        for (int i = 0; i < packs.size(); i++) {
            Pack p = packs.get(i);
            this.tiles.add(new Tile(p, (i % columns) * (TILE_W + GAP), y[0] + (i / columns) * (TILE_H + GAP)));
            requestIcon(p.name());
        }
        if (!packs.isEmpty()) {
            y[0] += ((packs.size() + columns - 1) / columns) * (TILE_H + GAP) + 4;
        }
    }

    private void clampScroll() {
        int view = this.contentBottom() - TOP;
        this.scroll = Math.max(0, Math.min(this.scroll, this.contentHeight - view));
        int sidebarView = this.height - EDGE - SIDE_TOP;
        this.sidebarScroll = Math.max(0, Math.min(this.sidebarScroll, this.categories.size() * ROW - sidebarView));
    }

    // ---- icons ----

    /** The mod's own icon, read from the jar: without Fabric API a mod's assets are not in the resource manager. */
    private static boolean loadLogo() {
        if (!logoLoaded) {
            logoLoaded = true;
            for (int i = 0; i < LOGO.length; i++) {
                String file = "/assets/vtpicker/icon" + (i == 0 ? "" : "2") + ".png";
                try (java.io.InputStream in = TweaksScreen.class.getResourceAsStream(file)) {
                    Minecraft.getInstance().getTextureManager().register(LOGO[i],
                        new DynamicTexture(() -> "vtpicker logo", NativeImage.read(in)));
                } catch (java.io.IOException | RuntimeException e) {
                    VanillaTweaks.LOG.warn("Icon {} not readable", file, e);
                }
            }
        }
        return Minecraft.getInstance().getTextureManager().getTexture(LOGO[1]) instanceof DynamicTexture;
    }

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
        this.pick(pack);
        // Saved right away, so picks survive closing the screen or quitting the game.
        VanillaTweaks.saveSelection(this.picked, this.applied);
    }

    private void pick(Pack pack) {
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
            this.applied = snapshot;
            VanillaTweaks.saveSelection(this.picked, this.applied);
            this.status = snapshot.isEmpty() ? "Vanilla Twerks pack removed" : "Applied " + snapshot.size() + " tweaks";
            VanillaTweaks.enable(zip);
        }));
    }

    // ---- drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        this.applyButton.active = !this.busy && !this.categories.isEmpty() && !this.picked.equals(this.applied);
        this.applyButton.setMessage(Component.literal(this.picked.equals(this.applied) || this.busy ? "Apply"
            : "Apply (" + this.picked.size() + ")"));
        this.drawSidebar(g, mouseX, mouseY);
        this.drawContent(g, mouseX, mouseY);
        if (!this.status.isEmpty()) {
            g.text(this.font, this.status, this.contentLeft() - 2, this.height - 21,
                this.status.startsWith("Failed") || this.status.startsWith("Could") ? 0xFFFF6060 : ACCENT, true);
        }
        // Widgets last, on top of the panels.
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int left = EDGE;
        int bottom = this.height - EDGE;
        g.fill(left, EDGE, left + SIDEBAR, bottom, PANEL);
        // Header: icon, name, what is selected.
        if (loadLogo()) {
            g.blit(RenderPipelines.GUI_TEXTURED, LOGO[(int) (System.currentTimeMillis() / 160 % 2)], left + 6, EDGE + 6, 0, 0, 24, 24, 256, 256, 256, 256);
        }
        g.text(this.font, Component.literal("Vanilla Twerks").withStyle(s -> s.withBold(true)), left + 36, EDGE + 8, 0xFFFFFFFF, true);
        g.text(this.font, VanillaTweaks.VERSION + " · " + this.picked.size() + " picked", left + 36, EDGE + 19, MUTED, true);
        g.fill(left + 6, SIDE_TOP - 4, left + SIDEBAR - 6, SIDE_TOP - 3, 0x30FFFFFF);

        g.enableScissor(left, SIDE_TOP, left + SIDEBAR, bottom);
        for (int i = 0; i < this.categories.size(); i++) {
            Category c = this.categories.get(i);
            int y = SIDE_TOP + i * ROW - (int) this.sidebarScroll;
            boolean hover = mouseX >= left && mouseX < left + SIDEBAR && mouseY >= y && mouseY < y + ROW
                && mouseY >= SIDE_TOP && mouseY < bottom;
            boolean current = i == this.category && !this.searching();
            if (current) {
                g.fill(left, y, left + SIDEBAR, y + ROW, HOVER);
                g.fill(left, y, left + 2, y + ROW, ACCENT);
            } else if (hover) {
                g.fill(left, y, left + SIDEBAR, y + ROW, TILE);
            }
            int count = this.countPicked(c);
            String n = count > 0 ? String.valueOf(count) : "";
            g.text(this.font, this.font.plainSubstrByWidth(c.title(), SIDEBAR - 16 - this.font.width(n)), left + 8, y + 4,
                current ? ACCENT : hover ? 0xFFFFFFFF : 0xFFD0D0D0, true);
            g.text(this.font, n, left + SIDEBAR - 6 - this.font.width(n), y + 4, ACCENT, true);
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
        g.fill(left - 4, EDGE, right, bottom, PANEL);
        if (this.categories.isEmpty()) {
            return;
        }
        // Title strip: the category and how much of it is picked.
        Category shown = this.categories.get(this.category);
        Component title = Component.literal(this.searching() ? "Search" : shown.title()).withStyle(s -> s.withBold(true));
        g.text(this.font, title, left, EDGE + 8, 0xFFFFFFFF, true);
        String info = this.searching() ? this.tiles.size() + " found"
            : this.countPicked(shown) + " of " + this.tiles.size() + " picked";
        int infoX = left + this.font.width(title) + 8;
        if (infoX + this.font.width(info) < this.searchBox.getX() - 6) {
            g.text(this.font, info, infoX, EDGE + 8, MUTED, true);
        }
        if (this.tiles.isEmpty()) {
            g.text(this.font, "Nothing matches \"" + this.query.trim() + "\"", left, TOP + 8, MUTED, true);
        }
        g.fill(left, TOP - 4, right - 6, TOP - 3, 0x30FFFFFF);
        g.enableScissor(left - 4, TOP, right, bottom);
        int dy = TOP - (int) this.scroll;
        for (Header h : this.headers) {
            int y = dy + h.y() + 4;
            int x = left + (h.depth() - 1) * 8;
            g.text(this.font, h.title(), x, y, h.depth() == 1 ? ACCENT : 0xFFD0D0D0, true);
            g.fill(x + this.font.width(h.title()) + 6, y + 4, right - 10, y + 5, 0x30FFFFFF);
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
            g.fill(x, y, x + TILE_W, y + TILE_H, on ? TILE_PICKED : hover ? HOVER : TILE);
            if (on) {
                g.outline(x, y, TILE_W, TILE_H, ACCENT);
            } else if (hover) {
                g.outline(x, y, TILE_W, TILE_H, 0x60FFFFFF);
            }
            Icon icon = ICONS.get(t.pack().name());
            int ix = x + (TILE_W - 32) / 2;
            if (icon != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, icon.id(), ix, y + 5, 0, 0, 32, 32, icon.width(), icon.height(),
                    icon.width(), icon.height());
            } else {
                g.fill(ix, y + 5, ix + 32, y + 37, TILE);
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
        g.fill(right - 4, y, right - 2, y + bar, 0x80FFFFFF);
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
        if (mx >= EDGE && mx < EDGE + SIDEBAR) {
            int i = (int) Math.floor((my - SIDE_TOP + this.sidebarScroll) / ROW);
            if (my >= SIDE_TOP && my < this.height - EDGE && i >= 0 && i < this.categories.size()
                && (i != this.category || this.searching())) {
                this.category = i;
                this.scroll = 0;
                this.query = "";
                this.searchBox.setValue("");
                this.layout();
            }
            return true;
        }
        if (my < TOP || my >= this.contentBottom()) {
            return false;
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
        if (mx < EDGE + SIDEBAR) {
            this.sidebarScroll -= sy * ROW;
        } else {
            this.scroll -= sy * (TILE_H + GAP) / 2.0;
        }
        this.clampScroll();
        return true;
    }

    @Override
    public void onClose() {
        // Like the vanilla pack screen: leaving applies what was picked (finishes in the background).
        if (!this.busy && !this.categories.isEmpty() && !this.picked.equals(this.applied)) {
            this.apply();
        }
        this.minecraft.gui.setScreen(this.parent);
    }

    /** A button without the vanilla texture: dark, lighter under the mouse, accent text for the main action. */
    private final class FlatButton extends Button {

        private final boolean primary;

        FlatButton(int x, int y, int width, String label, boolean primary, OnPress onPress) {
            super(x, y, width, 20, Component.literal(label), onPress, DEFAULT_NARRATION);
            this.primary = primary;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            boolean lit = this.active && this.isHoveredOrFocused();
            g.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, lit ? 0xE0000000 : PANEL);
            if (this.primary && this.active) {
                g.fill(this.getX(), this.getY() + this.height - 1, this.getX() + this.width, this.getY() + this.height, ACCENT);
            }
            if (this.isFocused()) {
                g.outline(this.getX(), this.getY(), this.width, this.height, 0xFFFFFFFF);
            }
            g.centeredText(TweaksScreen.this.font, this.getMessage(), this.getX() + this.width / 2,
                this.getY() + 6, !this.active ? 0xFF707070 : this.primary ? ACCENT : 0xFFFFFFFF);
        }
    }
}
