package me.webbeck.pluginUpdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
                    if (type.equals("MODRINTH")) {
                        foundUpdate = checkModrinth(pluginName, pSec.getString("project-id"), currentVer, allowedTypes, configManager.getPluginServerType(pluginName));
                    } else if (type.equals("GITHUB")) {
                        foundUpdate = checkGitHub(pluginName, pSec.getString("github-repo"), currentVer, allowedTypes);
                    } else if (type.equals("HANGAR")) {
                        foundUpdate = checkHangar(pluginName, pSec.getString("project-id"), currentVer, allowedTypes, configManager.getPluginServerType(pluginName));
                    } else if (type.equals("SPIGOT")) {
                        foundUpdate = checkSpigot(pluginName, pSec.getString("project-id"), currentVer);
                    } else if (type.equals("CUSTOM")) {
                        foundUpdate = new UpdateInfo(pluginName, currentVer, "Custom", pSec.getString("custom-url"), pluginName + "-update.jar");
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
        String header;
        switch (listMode == null ? "pending" : listMode.toLowerCase()) {
            case "all":
                header = "=== All Enabled Plugins ===";
                break;
            case "versions":
                header = "=== Plugin Versions ===";
                break;
            default:
                header = "=== Pending Updates ===";
                break;
        }

        plugin.sendMsg(sender, ChatColor.GOLD + header);
        ConfigurationSection pSec = plugin.getConfig().getConfigurationSection("plugins");
        if (pSec != null) {
            boolean foundAny = false;
            List<String> sortedKeys = new ArrayList<>(pSec.getKeys(false));
            sortedKeys.sort(String.CASE_INSENSITIVE_ORDER);

            for (String pName : sortedKeys) {
                if (!pSec.getBoolean(pName + ".enabled", true)) continue;

                String serverType = configManager.getPluginServerType(pName);
                String sourceType = configManager.getPluginSourceType(pName);
                Plugin p = Bukkit.getPluginManager().getPlugin(pName);
                String currentVersion = p != null ? p.getDescription().getVersion() : pSec.getString(pName + ".current-version", "Unknown");
                UpdateInfo info = plugin.getPendingUpdates().get(pName.toLowerCase());

                if (listMode.equalsIgnoreCase("all") || listMode.equalsIgnoreCase("versions")) {
                    StringBuilder line = new StringBuilder();
                    line.append(ChatColor.AQUA).append(pName);
                    line.append(ChatColor.DARK_GRAY).append(" [").append(sourceType).append("]");
                    line.append(ChatColor.YELLOW).append(" [").append(serverType.toUpperCase()).append("]");
                    line.append(ChatColor.GRAY).append(" [CUR ").append(currentVersion).append("]");

                    if (info != null) {
                        line.append(ChatColor.GRAY).append(" -> ");
                        line.append(ChatColor.AQUA).append("[").append(info.newVersion).append("]");
                        line.append(ChatColor.RED).append(" [UPDATE AVAILABLE]");
                    } else {
                        line.append(ChatColor.GREEN).append(" [UP TO DATE]");
                    }

                    if (listMode.equalsIgnoreCase("versions") && info == null) {
                        line = new StringBuilder();
                        line.append(ChatColor.AQUA).append(pName);
                        line.append(ChatColor.DARK_GRAY).append(" [").append(sourceType).append("]");
                        line.append(ChatColor.YELLOW).append(" [").append(serverType.toUpperCase()).append("]");
                        line.append(ChatColor.GRAY).append(" [CUR ").append(currentVersion).append("]");
                        line.append(ChatColor.GREEN).append(" [UP TO DATE]");
                    }

                    plugin.sendMsg(sender, line.toString());
                    foundAny = true;
                } else {
                    if (info != null) {
                        plugin.sendInteractiveListMsg(sender, pName, info.oldVersion, info.newVersion, true);
                        foundAny = true;
                    }
                }
            }
            if (!foundAny && listMode.equalsIgnoreCase("pending")) {
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
}
