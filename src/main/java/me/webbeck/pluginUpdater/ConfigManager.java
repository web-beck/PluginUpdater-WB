package me.webbeck.pluginUpdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class ConfigManager {
    private final PluginUpdater plugin;
    private String mcVersion;
    private String serverTypeOverride;
    private List<String> allowedPlayers;

    public ConfigManager(PluginUpdater plugin) {
        this.plugin = plugin;
    }

    public void syncConfig() {
        plugin.reloadConfig();

        mcVersion = plugin.getConfig().getString("minecraft-version", Bukkit.getBukkitVersion().split("-")[0]);
        serverTypeOverride = plugin.getConfig().getString("server-type-override", "paper");
        allowedPlayers = plugin.getConfig().getStringList("allowed-players");

        if (!plugin.getConfig().contains("minecraft-version")) plugin.getConfig().set("minecraft-version", mcVersion);
        if (!plugin.getConfig().contains("server-type-override")) plugin.getConfig().set("server-type-override", "paper");
        if (!plugin.getConfig().contains("allowed-players")) plugin.getConfig().set("allowed-players", new ArrayList<>(Collections.singletonList("AdminName")));

        if (!plugin.getConfig().contains("geyser-addons")) {
            ConfigurationSection gSec = plugin.getConfig().createSection("geyser-addons");
            gSec.set("enabled", false);
            gSec.set("Geyser", true);
            gSec.set("Floodgate", true);
            gSec.set("MCXboxBroadcast", true);
        }

        ConfigurationSection pluginsSection = plugin.getConfig().getConfigurationSection("plugins");
        if (pluginsSection == null) {
            pluginsSection = plugin.getConfig().createSection("plugins");
        }

        boolean changesMade = false;
        Set<String> ignoredDetectedPlugins = Set.of("geyser", "geyser-spigot", "floodgate");
        List<String> newlyScannedPlugins = new ArrayList<>();

        Set<String> loadedPlugins = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(p -> p.getName().toLowerCase())
                .filter(name -> !ignoredDetectedPlugins.contains(name))
                .collect(Collectors.toSet());

        for (String configPluginName : new ArrayList<>(pluginsSection.getKeys(false))) {
            if (configPluginName.equals("Modrinth-Example") || configPluginName.equals("GitHub-Example") || configPluginName.equals("CustomPlugin-Example") || configPluginName.equals("HangarPlugin-Example") || configPluginName.equals("SpigotPlugin-Example")) {
                continue;
            }
            if (!loadedPlugins.contains(configPluginName.toLowerCase()) || ignoredDetectedPlugins.contains(configPluginName.toLowerCase())) {
                pluginsSection.set(configPluginName, null);
                changesMade = true;
                plugin.getLogger().info("Removed uninstalled or ignored plugin from config: " + configPluginName);
            }
        }

        for (var plugin : Bukkit.getPluginManager().getPlugins()) {
            String name = plugin.getName();
            if (name.equalsIgnoreCase(this.plugin.getName())) continue;
            if (ignoredDetectedPlugins.contains(name.toLowerCase())) continue;

            if (!pluginsSection.contains(name)) {
                ConfigurationSection pSec = pluginsSection.createSection(name);
                pSec.set("enabled", true);
                pSec.set("type", "MODRINTH");
                pSec.set("project-id", name.toLowerCase().replace(" ", "-"));
                pSec.set("allowed-release-types", Collections.singletonList("release"));
                pSec.set("current-version", plugin.getDescription().getVersion());
                changesMade = true;
                newlyScannedPlugins.add(name);
            } else {
                ConfigurationSection pSec = pluginsSection.getConfigurationSection(name);
                if (pSec != null && !pSec.contains("allowed-release-types")) {
                    pSec.set("allowed-release-types", Collections.singletonList("release"));
                    changesMade = true;
                }
                if (pSec != null) {
                    String currentConfigVersion = pSec.getString("current-version", "");
                    if (!currentConfigVersion.equals(plugin.getDescription().getVersion())) {
                        pSec.set("current-version", plugin.getDescription().getVersion());
                        changesMade = true;
                    }
                }
            }
        }

        sortPluginConfig(pluginsSection);

        if (changesMade || plugin.getConfig().getKeys(false).size() <= 4) {
            saveAndFormatConfig();
        }

        if (!newlyScannedPlugins.isEmpty()) {
            CompletableFuture.runAsync(() -> {
                Map<String, String> resolvedIds = new HashMap<>();
                for (String pName : newlyScannedPlugins) {
                    try {
                        String realId = getRealModrinthId(pName);
                        if (realId != null) {
                            resolvedIds.put(pName, realId);
                        }
                    } catch (Exception ignored) {
                    }
                }

                if (!resolvedIds.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (Map.Entry<String, String> entry : resolvedIds.entrySet()) {
                            plugin.getConfig().set("plugins." + entry.getKey() + ".project-id", entry.getValue());
                            plugin.getLogger().info("Auto-resolved precise Modrinth ID for " + entry.getKey() + ": " + entry.getValue());
                        }
                        saveAndFormatConfig();
                    });
                }
            });
        }
    }

    private void sortPluginConfig(ConfigurationSection pluginsSection) {
        if (pluginsSection == null) return;

        List<String> allKeys = new ArrayList<>(pluginsSection.getKeys(false));
        List<String> scannedKeys = new ArrayList<>();
        List<String> finalSortedKeys = new ArrayList<>();

        List<String> fixedExamples = Arrays.asList("Modrinth-Example", "GitHub-Example", "HangarPlugin-Example", "SpigotPlugin-Example", "CustomPlugin-Example");

        for (String ex : fixedExamples) {
            if (allKeys.contains(ex)) {
                finalSortedKeys.add(ex);
            }
        }

        for (String key : allKeys) {
            if (key.endsWith("-Example") && !fixedExamples.contains(key)) {
                finalSortedKeys.add(key);
            } else if (!key.endsWith("-Example")) {
                scannedKeys.add(key);
            }
        }

        scannedKeys.sort(String.CASE_INSENSITIVE_ORDER);
        finalSortedKeys.addAll(scannedKeys);

        Map<String, Map<String, Object>> tempMap = new LinkedHashMap<>();
        for (String key : finalSortedKeys) {
            tempMap.put(key, pluginsSection.getConfigurationSection(key).getValues(false));
        }

        plugin.getConfig().set("plugins", null);
        ConfigurationSection newSec = plugin.getConfig().createSection("plugins");
        for (Map.Entry<String, Map<String, Object>> entry : tempMap.entrySet()) {
            ConfigurationSection p = newSec.createSection(entry.getKey());
            for (Map.Entry<String, Object> val : entry.getValue().entrySet()) {
                p.set(val.getKey(), val.getValue());
            }
        }
    }

    public void saveAndFormatConfig() {
        plugin.saveConfig();
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
            List<String> formatted = new ArrayList<>();
            boolean inPlugins = false;
            boolean scannedHeaderAdded = false;

            for (String line : lines) {
                if (line.startsWith("plugins:")) {
                    inPlugins = true;
                    String previousNonBlank = "";
                    String previousNonBlank2 = "";
                    for (int i = formatted.size() - 1; i >= 0; i--) {
                        String candidate = formatted.get(i).trim();
                        if (!candidate.isEmpty()) {
                            if (previousNonBlank.isEmpty()) {
                                previousNonBlank = candidate;
                            } else if (previousNonBlank2.isEmpty()) {
                                previousNonBlank2 = candidate;
                                break;
                            }
                        }
                    }
                    boolean headerAlreadyPresent = previousNonBlank.equals("# The plugin will automatically populate this section on startup based on the plugins currently loaded on your server.")
                            && previousNonBlank2.equals("# Below is where the plugin stores configuration for individual updates.");
                    if (!headerAlreadyPresent) {
                        if (!formatted.isEmpty() && !formatted.get(formatted.size() - 1).trim().isEmpty()) {
                            formatted.add("");
                        }
                        formatted.add("# Below is where the plugin stores configuration for individual updates.");
                        formatted.add("# The plugin will automatically populate this section on startup based on the plugins currently loaded on your server.");
                    }
                    formatted.add(line);
                    continue;
                } else if (!line.startsWith(" ") && !line.isEmpty()) {
                    inPlugins = false;
                }

                String trimmed = line.trim();
                if (inPlugins && (trimmed.equals("# ========================================== #") || trimmed.equals("#              Scanned Plugins               #"))) {
                    continue;
                }

                if (inPlugins && line.matches("^  [a-zA-Z0-9_.-]+: *$")) {
                    String pluginKey = line.replace(":", "").trim();
                    boolean isExample = pluginKey.equals("Modrinth-Example") ||
                            pluginKey.equals("GitHub-Example") ||
                            pluginKey.equals("HangarPlugin-Example") ||
                            pluginKey.equals("SpigotPlugin-Example") ||
                            pluginKey.equals("CustomPlugin-Example");

                    if (!isExample && !scannedHeaderAdded) {
                        if (!formatted.isEmpty() && !formatted.get(formatted.size() - 1).trim().isEmpty()) {
                            formatted.add("");
                        }
                        formatted.add("  # ========================================== #");
                        formatted.add("  #              Scanned Plugins               #");
                        formatted.add("  # ========================================== #");
                        scannedHeaderAdded = true;
                    } else if (!formatted.isEmpty()) {
                        String prevLine = formatted.get(formatted.size() - 1).trim();
                        if (!prevLine.isEmpty() && !prevLine.startsWith("#") && !prevLine.equals("plugins:")) {
                            formatted.add("");
                        }
                    }
                }

                formatted.add(line);
            }
            Files.write(configFile.toPath(), formatted, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to format config: " + e.getMessage());
        }
    }

    public String getMinecraftVersion() {
        return mcVersion;
    }

    public String getServerTypeOverride() {
        return serverTypeOverride;
    }

    public List<String> getAllowedPlayers() {
        return allowedPlayers;
    }

    public boolean hasPermission(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("pluginupdater.admin")) return true;
        return allowedPlayers != null && allowedPlayers.contains(sender.getName());
    }

    public String getPluginServerType(String pluginName) {
        ConfigurationSection pSec = plugin.getConfig().getConfigurationSection("plugins." + pluginName);
        String configured = pSec != null ? pSec.getString("server-type", null) : null;
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return serverTypeOverride != null ? serverTypeOverride : "auto";
    }

    public String getPluginSourceType(String pluginName) {
        ConfigurationSection pSec = plugin.getConfig().getConfigurationSection("plugins." + pluginName);
        return pSec != null ? pSec.getString("type", "MODRINTH") : "MODRINTH";
    }

    public String resolvePluginName(String input) {
        ConfigurationSection pSec = plugin.getConfig().getConfigurationSection("plugins");
        if (pSec == null) return null;
        if (pSec.contains(input)) return input;
        return pSec.getKeys(false).stream().filter(k -> k.equalsIgnoreCase(input)).findFirst().orElse(null);
    }

    public List<String> getTrackedChannels(String resolvedName) {
        if (resolvedName.equalsIgnoreCase("all")) {
            return getTrackedChannelsForAllPlugins();
        }

        ConfigurationSection ts = plugin.getConfig().getConfigurationSection("plugins." + resolvedName);
        List<String> currentTracked = ts != null ? ts.getStringList("allowed-release-types") : Collections.emptyList();
        if (currentTracked == null || currentTracked.isEmpty()) {
            return Collections.singletonList("release");
        }
        return currentTracked;
    }

    private List<String> getTrackedChannelsForAllPlugins() {
        ConfigurationSection pluginsSec = plugin.getConfig().getConfigurationSection("plugins");
        if (pluginsSec == null) {
            return Collections.singletonList("release");
        }

        Set<String> uniqueChannels = new HashSet<>();
        for (String key : pluginsSec.getKeys(false)) {
            List<String> types = pluginsSec.getStringList(key + ".allowed-release-types");
            if (types == null || types.isEmpty()) {
                types = Collections.singletonList("release");
            }
            if (types.contains("all")) {
                return Collections.singletonList("all");
            }
            if (types.size() > 1) {
                return Collections.singletonList("all");
            }
            uniqueChannels.add(types.get(0).toLowerCase());
            if (uniqueChannels.size() > 1) {
                return Collections.singletonList("all");
            }
        }

        if (uniqueChannels.size() == 1) {
            return Collections.singletonList(uniqueChannels.iterator().next());
        }

        return Collections.singletonList("release");
    }

    public List<String> getEnabledPlugins() {
        ConfigurationSection pSec = plugin.getConfig().getConfigurationSection("plugins");
        if (pSec == null) return Collections.emptyList();
        return pSec.getKeys(false).stream()
                .filter(k -> pSec.getBoolean(k + ".enabled", true))
                .collect(Collectors.toList());
    }

    public void setPluginIdConfig(CommandSender sender, String pluginName, String source, String projectId) {
        String type = source.toUpperCase(Locale.ROOT);
        if (type.equals("MODRINTH") || type.equals("HANGAR") || type.equals("SPIGOT")) {
            plugin.getConfig().set("plugins." + pluginName + ".type", type);
            plugin.getConfig().set("plugins." + pluginName + ".project-id", projectId);
            plugin.getConfig().set("plugins." + pluginName + ".github-repo", null);
            saveAndFormatConfig();
            plugin.sendMsg(sender, ChatColor.GREEN + "Set " + pluginName + " to " + type + " with ID " + projectId + " in config.");
        } else {
            plugin.sendMsg(sender, ChatColor.RED + "Invalid source type. Use Modrinth, Hangar, or Spigot.");
        }
    }

    public void setPluginToggle(CommandSender sender, String pluginName, boolean enabled) {
        String resolvedName = resolvePluginName(pluginName);
        if (resolvedName == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + pluginName + "' not found in config.");
            return;
        }

        plugin.getConfig().set("plugins." + resolvedName + ".enabled", enabled);
        saveAndFormatConfig();
        plugin.sendMsg(sender, ChatColor.GREEN + resolvedName + " is now " + (enabled ? "ENABLED" : "DISABLED") + " for updates.");

        if (enabled) {
            plugin.getUpdateChecker().runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        }
    }

    public void setServerTypeOverride(String type) {
        plugin.getConfig().set("server-type-override", type.toLowerCase());
        saveAndFormatConfig();
        serverTypeOverride = type.toLowerCase();
    }

    public String getPrettyServerType(String serverType) {
        if (serverType == null) return "UNKNOWN";
        if (!serverType.equalsIgnoreCase("auto")) {
            return serverType.toUpperCase();
        }
        String detected = Bukkit.getVersion().toLowerCase().contains("paper") ? "Paper" : "Spigot";
        return "AUTO (" + detected + ")";
    }

    public String getRealModrinthId(String pluginName) throws Exception {
        String slug = pluginName.toLowerCase().replace(" ", "-");
        String exactUrl = "https://api.modrinth.com/v2/project/" + URLEncoder.encode(slug, StandardCharsets.UTF_8.toString());

        HttpRequest exactRequest = HttpRequest.newBuilder()
                .uri(URI.create(exactUrl))
                .header("User-Agent", "PluginUpdater-WB")
                .build();

        HttpResponse<String> exactResponse = HttpClient.newHttpClient().send(exactRequest, HttpResponse.BodyHandlers.ofString());

        if (exactResponse.statusCode() == 200) {
            JsonObject json = JsonParser.parseString(exactResponse.body()).getAsJsonObject();
            if (json.has("id")) {
                return json.get("id").getAsString();
            }
        }

        String searchUrl = "https://api.modrinth.com/v2/search?query="
                + URLEncoder.encode(pluginName, StandardCharsets.UTF_8.toString())
                + "&limit=5";

        HttpRequest searchRequest = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .header("User-Agent", "PluginUpdater-WB")
                .build();

        HttpResponse<String> searchResponse = HttpClient.newHttpClient().send(searchRequest, HttpResponse.BodyHandlers.ofString());

        if (searchResponse.statusCode() == 200) {
            JsonObject json = JsonParser.parseString(searchResponse.body()).getAsJsonObject();
            JsonArray hits = json.getAsJsonArray("hits");

            if (hits.size() > 0) {
                for (JsonElement element : hits) {
                    JsonObject hit = element.getAsJsonObject();
                    String hitTitle = hit.get("title").getAsString();
                    String hitSlug = hit.get("slug").getAsString();

                    if (hitTitle.equalsIgnoreCase(pluginName) || hitSlug.equalsIgnoreCase(slug) || hitSlug.equalsIgnoreCase(pluginName)) {
                        return hit.get("project_id").getAsString();
                    }
                }
                return hits.get(0).getAsJsonObject().get("project_id").getAsString();
            }
        }
        return null;
    }
}
