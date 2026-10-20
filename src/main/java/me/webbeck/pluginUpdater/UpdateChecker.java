package me.webbeck.pluginUpdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class UpdateChecker {
    private final PluginUpdater plugin;
    private final ConfigManager configManager;
    private final HttpClient httpClient;

    public UpdateChecker(PluginUpdater plugin, ConfigManager configManager, HttpClient httpClient) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.httpClient = httpClient;
    }

    public void runUpdateCheck(CommandSender sender, boolean bypassFilters, String listMode) {
        plugin.sendMsg(sender, ChatColor.AQUA + "Starting async plugin update check...");

        CompletableFuture.runAsync(() -> {
            ConfigurationSection pluginsSec = plugin.getConfig().getConfigurationSection("plugins");
            if (pluginsSec == null) return;

            List<String> keys = new ArrayList<>(pluginsSec.getKeys(false));
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            int total = keys.size();
            AtomicInteger current = new AtomicInteger(0);

            plugin.getPendingUpdates().clear();

            for (String pluginName : keys) {
                current.incrementAndGet();
                ConfigurationSection pSec = pluginsSec.getConfigurationSection(pluginName);
                if (pSec == null || !pSec.getBoolean("enabled", true)) continue;

                plugin.updateActionBar(sender, "Checking Plugins: " + current.get() + "/" + total + " (" + pluginName + ")");

                String type = pSec.getString("type", "MODRINTH").toUpperCase();
                List<String> allowedTypes = pSec.getStringList("allowed-release-types");
                if (bypassFilters || allowedTypes.contains("all") || allowedTypes.contains("ALL")) {
                    allowedTypes = Arrays.asList("release", "beta", "alpha", "prerelease");
                }

                Plugin runningPlugin = Bukkit.getPluginManager().getPlugin(pluginName);
                String currentVer = runningPlugin != null ? runningPlugin.getDescription().getVersion() : pSec.getString("current-version", "0.0.0");

                try {
                    UpdateInfo foundUpdate = null;
                    if (type.equals("GITHUB")) {
                        foundUpdate = checkGitHub(pluginName, pSec.getString("github-repo"), currentVer, allowedTypes);
                    } else if (type.equals("CUSTOM")) {
                        foundUpdate = new UpdateInfo(pluginName, currentVer, "Custom", pSec.getString("custom-url"), pluginName + "-update.jar");
                    } else {
                        // Each source uses its own stored ID; alternates are set during discovery
                        String projectId  = pSec.getString("project-id");
                        String serverType = configManager.getPluginServerType(pluginName);
                        List<String> finalAllowedTypes = allowedTypes;

                        String modrinthId = type.equals("MODRINTH") ? projectId : pSec.getString("alt-modrinth-id");
                        String hangarId   = type.equals("HANGAR")   ? projectId : pSec.getString("alt-hangar-id");
                        String spigotId   = type.equals("SPIGOT")   ? projectId : pSec.getString("alt-spigot-id");

                        CompletableFuture<UpdateInfo> mFuture = modrinthId != null
                            ? CompletableFuture.supplyAsync(() -> {
                                try { return checkModrinth(pluginName, modrinthId, currentVer, finalAllowedTypes, serverType); } catch (Exception e) { return null; }
                              })
                            : CompletableFuture.completedFuture(null);
                        CompletableFuture<UpdateInfo> hFuture = hangarId != null
                            ? CompletableFuture.supplyAsync(() -> {
                                try { return checkHangar(pluginName, hangarId, currentVer, finalAllowedTypes, serverType); } catch (Exception e) { return null; }
                              })
                            : CompletableFuture.completedFuture(null);
                        CompletableFuture<UpdateInfo> sFuture = spigotId != null
                            ? CompletableFuture.supplyAsync(() -> {
                                try { return checkSpigot(pluginName, spigotId, currentVer); } catch (Exception e) { return null; }
                              })
                            : CompletableFuture.completedFuture(null);
                        CompletableFuture.allOf(mFuture, hFuture, sFuture).join();

                        UpdateInfo mResult = mFuture.join();
                        UpdateInfo hResult = hFuture.join();
                        UpdateInfo sResult = sFuture.join();

                        // Prefer the configured source; fall back to alternates (each with its own correct ID)
                        if (type.equals("HANGAR")) {
                            foundUpdate = hResult != null ? hResult : (mResult != null ? mResult : sResult);
                        } else if (type.equals("SPIGOT")) {
                            foundUpdate = sResult != null ? sResult : (mResult != null ? mResult : hResult);
                        } else {
                            foundUpdate = mResult != null ? mResult : (hResult != null ? hResult : sResult);
                        }
                    }

                    // GitHub fallback: if GitHub has no matching releases, try Modrinth/Hangar/Spigot
                    if (foundUpdate == null && type.equals("GITHUB")) {
                        String altM = pSec.getString("alt-modrinth-id");
                        String altH = pSec.getString("alt-hangar-id");
                        String altS = pSec.getString("alt-spigot-id");
                        String fbServer = configManager.getPluginServerType(pluginName);
                        List<String> fbTypes = allowedTypes;

                        if (altM == null && altH == null && altS == null) {
                            plugin.getLogger().info(pluginName + ": GitHub has no matching releases — detecting alternate sources");
                            String detM = null, detH = null, detS = null;
                            try { detM = configManager.getRealModrinthId(pluginName); } catch (Exception ignored) {}
                            try { detH = configManager.getRealHangarId(pluginName);   } catch (Exception ignored) {}
                            try { detS = configManager.getRealSpigotId(pluginName);   } catch (Exception ignored) {}
                            altM = detM; altH = detH; altS = detS;
                            if (detM != null || detH != null || detS != null) {
                                final String fm = detM, fh = detH, fs = detS;
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    plugin.getConfig().set("plugins." + pluginName + ".alt-modrinth-id", fm);
                                    plugin.getConfig().set("plugins." + pluginName + ".alt-hangar-id",   fh);
                                    plugin.getConfig().set("plugins." + pluginName + ".alt-spigot-id",   fs);
                                    configManager.saveAndFormatConfig();
                                });
                            }
                        }

                        if (foundUpdate == null && altM != null) {
                            try { foundUpdate = checkModrinth(pluginName, altM, currentVer, fbTypes, fbServer); } catch (Exception ignored) {}
                        }
                        if (foundUpdate == null && altH != null) {
                            try { foundUpdate = checkHangar(pluginName, altH, currentVer, fbTypes, fbServer); } catch (Exception ignored) {}
                        }
                        if (foundUpdate == null && altS != null) {
                            try { foundUpdate = checkSpigot(pluginName, altS, currentVer); } catch (Exception ignored) {}
                        }
                    }

                    if (foundUpdate != null && (type.equals("CUSTOM") || !PluginUpdaterUtils.versionsMatch(currentVer, foundUpdate.newVersion))) {
                        plugin.getPendingUpdates().put(pluginName.toLowerCase(), foundUpdate);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to check " + pluginName + ": " + e.getMessage());
                }
            }

            plugin.updateActionBar(sender, "");
            plugin.setInitialCheckComplete();

            if (listMode != null) {
                displayPluginList(sender, listMode);
            } else {
                plugin.sendMsg(sender, ChatColor.GREEN + "Update check complete! Found " + plugin.getPendingUpdates().size() + " pending updates.");
                if (!plugin.getPendingUpdates().isEmpty()) {
                    plugin.sendMsg(sender, ChatColor.YELLOW + "Use /upd list to view them.");
                }
            }
        });
    }

    public void displayPluginList(CommandSender sender, String listMode) {
        String mode = listMode == null ? "pending" : listMode.toLowerCase();
        ConfigurationSection pSec = plugin.getConfig().getConfigurationSection("plugins");
        if (pSec == null) return;

        List<String> sortedKeys = new ArrayList<>(pSec.getKeys(false));
        sortedKeys.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> enabled = new ArrayList<>();
        for (String k : sortedKeys) {
            if (pSec.getBoolean(k + ".enabled", true)) enabled.add(k);
        }

        if (mode.equals("all")) {
            int totalEnabled = enabled.size();
            int totalPending = (int) enabled.stream().filter(k -> plugin.getPendingUpdates().containsKey(k.toLowerCase())).count();
            int totalUpToDate = totalEnabled - totalPending;

            // Header
            sender.sendMessage(Component.text("━━━[ ", NamedTextColor.GOLD)
                    .append(Component.text("All Plugins", NamedTextColor.AQUA))
                    .append(Component.text(" (" + totalEnabled + ")", NamedTextColor.DARK_GRAY))
                    .append(Component.text(" ]━━━", NamedTextColor.GOLD)));

            for (String pName : enabled) {
                String sourceType = configManager.getPluginSourceType(pName);
                Plugin p = Bukkit.getPluginManager().getPlugin(pName);
                String currentVersion = p != null ? p.getDescription().getVersion()
                        : pSec.getString(pName + ".current-version", "?");
                UpdateInfo info = plugin.getPendingUpdates().get(pName.toLowerCase());

                // Name — clickable to show plugin info
                Component line = Component.text(" ")
                        .append(Component.text(pName, NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.runCommand("/upd info " + pName))
                                .hoverEvent(HoverEvent.showText(Component.text("Click for details", NamedTextColor.GRAY))))
                        .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("[" + sourceType + "]", NamedTextColor.DARK_GRAY))
                        .append(Component.text("  " + currentVersion, NamedTextColor.GRAY));

                if (info != null) {
                    line = line
                            .append(Component.text("  →  ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(info.newVersion, NamedTextColor.YELLOW))
                            .append(Component.text("  "))
                            .append(Component.text("[UPDATE]", NamedTextColor.GREEN)
                                    .clickEvent(ClickEvent.runCommand("/upd update " + pName))
                                    .hoverEvent(HoverEvent.showText(Component.text("Click to update " + pName, NamedTextColor.YELLOW))));
                } else {
                    line = line.append(Component.text("  ✔", NamedTextColor.DARK_GREEN));
                }

                sender.sendMessage(line);
            }

            // Footer summary
            Component footer = Component.text("─── ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(totalUpToDate + " up to date", NamedTextColor.GREEN));
            if (totalPending > 0) {
                footer = footer
                        .append(Component.text("  •  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(totalPending + " update" + (totalPending != 1 ? "s" : "") + " available", NamedTextColor.GOLD));
            }
            footer = footer.append(Component.text(" ───", NamedTextColor.DARK_GRAY));
            sender.sendMessage(footer);

        } else {
            // Pending updates list
            boolean foundAny = false;
            for (String pName : enabled) {
                UpdateInfo info = plugin.getPendingUpdates().get(pName.toLowerCase());
                if (info != null) {
                    plugin.sendInteractiveListMsg(sender, pName, info.oldVersion, info.newVersion, true);
                    foundAny = true;
                }
            }
            if (!foundAny) {
                plugin.sendMsg(sender, ChatColor.GREEN + "All tracked plugins are currently up to date!");
            }
        }
    }

    public String getRealModrinthId(String pluginName) throws Exception {
        return configManager.getRealModrinthId(pluginName);
    }

    public String getRealSpigotId(String pluginName) throws Exception {
        return configManager.getRealSpigotId(pluginName);
    }

    public String getRealHangarId(String pluginName) throws Exception {
        return configManager.getRealHangarId(pluginName);
    }

    UpdateInfo checkModrinth(String pluginName, String projectId, String currentVer, List<String> allowedTypes, String serverType) throws Exception {
        String loadersStr;
        if (serverType.equalsIgnoreCase("auto")) {
            loadersStr = Bukkit.getVersion().toLowerCase().contains("paper") ? "[\"paper\",\"spigot\",\"bukkit\"]" : "[\"spigot\",\"bukkit\"]";
        } else {
            loadersStr = "[\"" + serverType.toLowerCase() + "\"]";
        }

        String gameVerStr = "[\"" + configManager.getMinecraftVersion() + "\"]";
        String url = "https://api.modrinth.com/v2/project/" + projectId + "/version?loaders="
                + URLEncoder.encode(loadersStr, StandardCharsets.UTF_8.toString())
                + "&game_versions=" + URLEncoder.encode(gameVerStr, StandardCharsets.UTF_8.toString());

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) return null;

        JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
        for (JsonElement element : versions) {
            JsonObject vObj = element.getAsJsonObject();
            String vType = vObj.get("version_type").getAsString();

            if (!allowedTypes.contains(vType)) continue;

            String newVer = vObj.get("version_number").getAsString();
            JsonArray files = vObj.getAsJsonArray("files");
            if (files.size() == 0) continue;

            JsonObject primaryFile = files.get(0).getAsJsonObject();
            String downloadUrl = primaryFile.get("url").getAsString();
            String fileName = primaryFile.get("filename").getAsString();

            UpdateInfo info = new UpdateInfo(pluginName, currentVer, newVer, downloadUrl, fileName);
            JsonArray deps = vObj.getAsJsonArray("dependencies");
            if (deps != null) {
                for (JsonElement depElem : deps) {
                    JsonObject depObj = depElem.getAsJsonObject();
                    if (depObj.has("dependency_type") && depObj.get("dependency_type").getAsString().equals("required")) {
                        JsonElement projIdElem = depObj.get("project_id");
                        if (!projIdElem.isJsonNull()) {
                            info.requiredDependencies.add(projIdElem.getAsString());
                        }
                    }
                }
            }
            return info;
        }
        return null;
    }

    UpdateInfo checkGitHub(String pluginName, String repo, String currentVer, List<String> allowedTypes) throws Exception {
        String url = "https://api.github.com/repos/" + repo + "/releases";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) return null;

        JsonArray releases = JsonParser.parseString(response.body()).getAsJsonArray();
        for (JsonElement element : releases) {
            JsonObject rObj = element.getAsJsonObject();
            boolean isPrerelease = rObj.get("prerelease").getAsBoolean();
            String effectiveType = isPrerelease ? "beta" : "release";

            if (!allowedTypes.contains(effectiveType)) continue;

            String newVer = rObj.get("tag_name").getAsString();
            JsonArray assets = rObj.getAsJsonArray("assets");

            for (JsonElement assetElem : assets) {
                JsonObject assetObj = assetElem.getAsJsonObject();
                String fileName = assetObj.get("name").getAsString();
                if (fileName.endsWith(".jar")) {
                    String downloadUrl = assetObj.get("browser_download_url").getAsString();
                    return new UpdateInfo(pluginName, currentVer, newVer, downloadUrl, fileName);
                }
            }
        }
        return null;
    }

    UpdateInfo checkHangar(String pluginName, String projectId, String currentVer, List<String> allowedTypes, String serverType) throws Exception {
        String url = "https://hangar.papermc.io/api/v1/projects/" + projectId + "/versions";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) return null;

        JsonArray versions = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("result");
        String platform = serverType.equalsIgnoreCase("auto") ? "PAPER" : serverType.toUpperCase();

        for (JsonElement element : versions) {
            JsonObject vObj = element.getAsJsonObject();
            String channel = vObj.getAsJsonObject("channel").get("name").getAsString().toLowerCase();
            String effectiveType = channel.contains("snapshot") || channel.contains("beta") ? "beta" : "release";

            if (!allowedTypes.contains(effectiveType) && !allowedTypes.contains("all") && !allowedTypes.contains("ALL")) continue;

            String newVer = vObj.get("name").getAsString();
            JsonObject downloads = vObj.getAsJsonObject("downloads");

            JsonObject platformDownload = downloads.has(platform) ? downloads.getAsJsonObject(platform) : null;
            if (platformDownload == null && downloads.has("PAPER")) platformDownload = downloads.getAsJsonObject("PAPER");
            if (platformDownload == null && downloads.has("WATERFALL")) platformDownload = downloads.getAsJsonObject("WATERFALL");
            if (platformDownload == null && downloads.size() > 0) platformDownload = downloads.entrySet().iterator().next().getValue().getAsJsonObject();

            if (platformDownload != null && !platformDownload.isJsonNull()) {
                String finalPlatform = platform;
                if (!downloads.has(finalPlatform)) {
                    if (downloads.has("PAPER")) finalPlatform = "PAPER";
                    else finalPlatform = downloads.entrySet().iterator().next().getKey();
                }

                String downloadUrl = "https://hangar.papermc.io/api/v1/projects/" + projectId + "/versions/" + newVer + "/" + finalPlatform + "/download";
                String fileName = pluginName + "-" + newVer + ".jar";

                if (platformDownload.has("fileInfo") && !platformDownload.get("fileInfo").isJsonNull() && platformDownload.getAsJsonObject("fileInfo").has("name")) {
                    fileName = platformDownload.getAsJsonObject("fileInfo").get("name").getAsString();
                }
                return new UpdateInfo(pluginName, currentVer, newVer, downloadUrl, fileName);
            }
        }
        return null;
    }

    UpdateInfo checkSpigot(String pluginName, String projectId, String currentVer) throws Exception {
        String url = "https://api.spiget.org/v2/resources/" + projectId + "/versions/latest";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) return null;

        JsonObject vObj = JsonParser.parseString(response.body()).getAsJsonObject();
        String newVer = vObj.get("name").getAsString();
        String downloadUrl = "https://api.spiget.org/v2/resources/" + projectId + "/download";
        String fileName = pluginName + "-" + newVer + ".jar";

        return new UpdateInfo(pluginName, currentVer, newVer, downloadUrl, fileName);
    }

    public Map<String, String> fetchAllChannelsModrinth(String projectId, String serverType) throws Exception {
        Map<String, String> latestVersions = new HashMap<>();
        String loadersStr;
        if (serverType.equalsIgnoreCase("auto")) {
            loadersStr = Bukkit.getVersion().toLowerCase().contains("paper") ? "[\"paper\",\"spigot\",\"bukkit\"]" : "[\"spigot\",\"bukkit\"]";
        } else {
            loadersStr = "[\"" + serverType.toLowerCase() + "\"]";
        }
        String gameVerStr = "[\"" + configManager.getMinecraftVersion() + "\"]";
        String url = "https://api.modrinth.com/v2/project/" + projectId + "/version?loaders="
                + URLEncoder.encode(loadersStr, StandardCharsets.UTF_8.toString())
                + "&game_versions=" + URLEncoder.encode(gameVerStr, StandardCharsets.UTF_8.toString());

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
            for (JsonElement element : versions) {
                JsonObject vObj = element.getAsJsonObject();
                String vType = vObj.get("version_type").getAsString();
                String newVer = vObj.get("version_number").getAsString();
                latestVersions.putIfAbsent(vType, newVer);
                if (latestVersions.size() >= 3) break;
            }
        }
        return latestVersions;
    }

    public Map<String, String> fetchAllChannelsGitHub(String repo) throws Exception {
        Map<String, String> latestVersions = new HashMap<>();
        String url = "https://api.github.com/repos/" + repo + "/releases";

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonArray releases = JsonParser.parseString(response.body()).getAsJsonArray();
            for (JsonElement element : releases) {
                JsonObject rObj = element.getAsJsonObject();
                boolean isPrerelease = rObj.get("prerelease").getAsBoolean();
                String effectiveType = isPrerelease ? "beta" : "release";
                String newVer = rObj.get("tag_name").getAsString();
                latestVersions.putIfAbsent(effectiveType, newVer);
                if (latestVersions.containsKey("release") && latestVersions.containsKey("beta")) break;
            }
        }
        return latestVersions;
    }

    public Map<String, String> fetchAllChannelsHangar(String projectId) throws Exception {
        Map<String, String> latestVersions = new HashMap<>();
        String url = "https://hangar.papermc.io/api/v1/projects/" + projectId + "/versions";

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonArray versions = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("result");
            for (JsonElement element : versions) {
                JsonObject vObj = element.getAsJsonObject();
                String channel = vObj.getAsJsonObject("channel").get("name").getAsString().toLowerCase();
                String effectiveType = channel.contains("snapshot") || channel.contains("beta") ? "beta" : "release";
                String newVer = vObj.get("name").getAsString();
                latestVersions.putIfAbsent(effectiveType, newVer);
                if (latestVersions.size() >= 2) break;
            }
        }
        return latestVersions;
    }

    public Map<String, String> fetchAllChannelsSpigot(String projectId) throws Exception {
        Map<String, String> latestVersions = new HashMap<>();
        String url = "https://api.spiget.org/v2/resources/" + projectId + "/versions/latest";

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonObject vObj = JsonParser.parseString(response.body()).getAsJsonObject();
            latestVersions.put("release", vObj.get("name").getAsString());
        }
        return latestVersions;
    }

    /**
     * Fetches available version channels for a plugin, with automatic fallback:
     * primary source → stored alt IDs → live detection (cached on first run).
     */
    public Map<String, String> fetchAllChannelsWithFallback(
            String pluginName, String type,
            org.bukkit.configuration.ConfigurationSection pSec, String serverType) throws Exception {

        Map<String, String> versions = new HashMap<>();

        // 1. Try the configured primary source
        String projectId  = pSec.getString("project-id");
        String githubRepo = pSec.getString("github-repo");
        switch (type) {
            case "MODRINTH": if (projectId  != null) versions.putAll(fetchAllChannelsModrinth(projectId, serverType)); break;
            case "HANGAR":   if (projectId  != null) versions.putAll(fetchAllChannelsHangar(projectId));               break;
            case "SPIGOT":   if (projectId  != null) versions.putAll(fetchAllChannelsSpigot(projectId));               break;
            case "GITHUB":   if (githubRepo != null) versions.putAll(fetchAllChannelsGitHub(githubRepo));              break;
        }
        if (!versions.isEmpty()) return versions;

        // 2. Try stored alternate source IDs
        String altM = pSec.getString("alt-modrinth-id");
        String altH = pSec.getString("alt-hangar-id");
        String altS = pSec.getString("alt-spigot-id");

        // 3. No alternates stored — do live detection and cache for future calls
        if (altM == null && altH == null && altS == null) {
            String detM = null, detH = null, detS = null;
            try { detM = configManager.getRealModrinthId(pluginName); } catch (Exception ignored) {}
            try { detH = configManager.getRealHangarId(pluginName);   } catch (Exception ignored) {}
            try { detS = configManager.getRealSpigotId(pluginName);   } catch (Exception ignored) {}
            if (detM != null || detH != null || detS != null) {
                altM = detM; altH = detH; altS = detS;
                final String fm = detM, fh = detH, fs = detS;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!type.equals("MODRINTH")) plugin.getConfig().set("plugins." + pluginName + ".alt-modrinth-id", fm);
                    if (!type.equals("HANGAR"))   plugin.getConfig().set("plugins." + pluginName + ".alt-hangar-id",   fh);
                    if (!type.equals("SPIGOT"))   plugin.getConfig().set("plugins." + pluginName + ".alt-spigot-id",   fs);
                    configManager.saveAndFormatConfig();
                });
            }
        }

        // 4. Try each alternate in order until one returns versions
        if (altM != null && !type.equals("MODRINTH")) versions.putAll(fetchAllChannelsModrinth(altM, serverType));
        if (versions.isEmpty() && altH != null && !type.equals("HANGAR")) versions.putAll(fetchAllChannelsHangar(altH));
        if (versions.isEmpty() && altS != null && !type.equals("SPIGOT")) versions.putAll(fetchAllChannelsSpigot(altS));

        return versions;
    }
}
