package com.vtpicker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Talks to vanillatweaks.net the way its own picker does: the pack list is the site's JSON for this game version
 * (fetched every time the screen opens, so it is always current), the zip is built by the site's endpoint.
 */
public final class VanillaTweaks {

    public static final Logger LOG = LoggerFactory.getLogger("Vanilla Twerks");
    public static final String SITE = "https://vanillatweaks.net";
    // shortcut: fixed to the game version this jar is built for; the site names versions like "26.3", "1.21".
    public static final String VERSION = "26.3";
    /** Our zips in resourcepacks/ start with this, so old ones can be swapped out and deleted. */
    private static final String FILE_PREFIX = "VanillaTweaks-ingame-";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
    private static final Gson GSON = new Gson();

    public record Pack(String name, String display, String description, List<String> incompatible, String categoryKey) {
    }

    /** A category on the site; key is the site's dotted id ("gui.hearts.lgbtq--pride"). */
    public record Category(String title, String key, int depth, List<Pack> packs, List<Category> children) {
    }

    private VanillaTweaks() {
    }

    // ---- pack list ----

    public static CompletableFuture<List<Category>> fetchCategories() {
        return getString(SITE + "/assets/resources/json/" + VERSION + "/rpcategories.json?" + System.currentTimeMillis())
            .thenApply(json -> {
                List<Category> list = parse(json);
                writeQuietly(cacheFile(), json);
                return list;
            })
            .exceptionally(error -> {
                // Offline: the last list we saw, if any.
                LOG.warn("Could not load the pack list from {}: {}", SITE, error.toString());
                try {
                    return parse(Files.readString(cacheFile()));
                } catch (IOException e) {
                    throw new RuntimeException("vanillatweaks.net not reachable", error);
                }
            });
    }

    static List<Category> parse(String json) {
        List<Category> out = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("categories")) {
            Category c = category(e.getAsJsonObject(), "", 0);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private static Category category(JsonObject json, String parentKey, int depth) {
        String title = json.get("category").getAsString();
        String key = (parentKey.isEmpty() ? "" : parentKey + ".") + slug(title);
        List<Pack> packs = new ArrayList<>();
        if (json.has("packs")) {
            for (JsonElement e : json.getAsJsonArray("packs")) {
                JsonObject p = e.getAsJsonObject();
                // The site's picker hides experimental packs too.
                if (p.has("experiment") && p.get("experiment").getAsBoolean()) {
                    continue;
                }
                List<String> incompatible = new ArrayList<>();
                if (p.has("incompatible")) {
                    p.getAsJsonArray("incompatible").forEach(i -> incompatible.add(i.getAsString()));
                }
                packs.add(new Pack(p.get("name").getAsString(), p.get("display").getAsString(),
                    plainText(p.has("description") ? p.get("description").getAsString() : ""), incompatible, key));
            }
        }
        List<Category> children = new ArrayList<>();
        if (json.has("categories")) {
            for (JsonElement e : json.getAsJsonArray("categories")) {
                Category c = category(e.getAsJsonObject(), key, depth + 1);
                if (c != null) {
                    children.add(c);
                }
            }
        }
        return packs.isEmpty() && children.isEmpty() ? null : new Category(title, key, depth, packs, children);
    }

    /** The site's category ids: lower case, every other character a dash ("Xisuma's Bases" -> "xisuma-s-bases"). */
    static String slug(String title) {
        return title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "-");
    }

    /** Descriptions carry a little HTML (spans, br, b). */
    static String plainText(String html) {
        return html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]*>", "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&amp;", "&").trim();
    }

    // ---- selection (remembered between starts) ----

    /**
     * Pack names in the order picked: "packs" = what the screen shows, "applied" = what the zip in use holds
     * (older files only have "packs", which was always applied).
     */
    public static Set<String> loadSelection(String field) {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonObject json = JsonParser.parseString(Files.readString(selectionFile())).getAsJsonObject();
            (json.has(field) ? json.getAsJsonArray(field) : json.getAsJsonArray("packs"))
                .forEach(e -> names.add(e.getAsString()));
        } catch (IOException | RuntimeException ignored) {
            // first start or broken file: nothing selected
        }
        return names;
    }

    public static void saveSelection(Set<String> picked, Set<String> applied) {
        JsonObject json = new JsonObject();
        json.addProperty("version", VERSION);
        json.add("packs", array(picked));
        json.add("applied", array(applied));
        writeQuietly(selectionFile(), GSON.toJson(json));
    }

    private static JsonArray array(Set<String> names) {
        JsonArray array = new JsonArray();
        names.forEach(array::add);
        return array;
    }

    // ---- building and enabling the pack ----

    /**
     * Asks the site for a zip of the selected packs and downloads it into resourcepacks/. Runs off the render
     * thread; the result is the downloaded file (null for an empty selection).
     */
    public static CompletableFuture<Path> download(List<Pack> selected) {
        if (selected.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (Pack p : selected) {
            byCategory.computeIfAbsent(p.categoryKey(), k -> new ArrayList<>()).add(p.name());
        }
        String form = "packs=" + URLEncoder.encode(GSON.toJson(byCategory), StandardCharsets.UTF_8)
            + "&version=" + URLEncoder.encode(VERSION, StandardCharsets.UTF_8);
        HttpRequest post = request(SITE + "/assets/server/zipresourcepacks.php")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        return HTTP.sendAsync(post, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!"success".equals(json.has("status") ? json.get("status").getAsString() : "")) {
                throw new IllegalStateException(json.has("message") ? json.get("message").getAsString()
                    : "vanillatweaks.net answered " + response.statusCode());
            }
            Path target = packDirectory().resolve(FILE_PREFIX + System.currentTimeMillis() + ".zip");
            return HTTP.sendAsync(request(SITE + json.get("link").getAsString()).GET().build(),
                HttpResponse.BodyHandlers.ofFile(target)).thenApply(r -> {
                    if (r.statusCode() != 200) {
                        throw new IllegalStateException("Download failed: HTTP " + r.statusCode());
                    }
                    return r.body();
                });
        });
    }

    /**
     * Render thread: puts the new zip on top of the selected resource packs in place of our old one (or only
     * removes the old one when zip is null) and reloads resources.
     */
    public static void enable(Path zip) {
        Minecraft mc = Minecraft.getInstance();
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        // The zip in use stays open until the reload below is done, so it goes on the next apply.
        deleteOldZips(selected.stream().filter(VanillaTweaks::isOurs).toList(), zip);
        selected.removeIf(VanillaTweaks::isOurs);
        if (zip != null) {
            selected.add("file/" + zip.getFileName());
        }
        repo.setSelected(selected);
        // Saves options.txt and reloads resources when the list changed (always: every zip has a new name).
        mc.options.updateResourcePacks(repo);
    }

    private static boolean isOurs(String packId) {
        return packId.startsWith("file/" + FILE_PREFIX);
    }

    private static void deleteOldZips(List<String> inUse, Path keep) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(packDirectory(), FILE_PREFIX + "*.zip")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!inUse.contains("file/" + name) && (keep == null || !name.equals(keep.getFileName().toString()))) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        LOG.debug("Old pack still in use: {}", file);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not clean up old packs", e);
        }
    }

    // ---- helpers ----

    public static CompletableFuture<byte[]> getBytes(String url) {
        return HTTP.sendAsync(request(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).thenApply(r -> {
            if (r.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + r.statusCode() + " for " + url);
            }
            return r.body();
        });
    }

    private static CompletableFuture<String> getString(String url) {
        return getBytes(url).thenApply(b -> new String(b, StandardCharsets.UTF_8));
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("User-Agent", "VT-Picker-Minecraft-Mod");
    }

    private static Path packDirectory() {
        return Minecraft.getInstance().getResourcePackDirectory();
    }

    private static Path configDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config");
    }

    private static Path cacheFile() {
        return configDir().resolve("vtpicker-packs-" + VERSION + ".json");
    }

    private static Path selectionFile() {
        return configDir().resolve("vtpicker.json");
    }

    private static void writeQuietly(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            LOG.warn("Could not write {}", file, e);
        }
    }
}
