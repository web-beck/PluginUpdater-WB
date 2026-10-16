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
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class ConfigManager {
    private final PluginUpdater plugin;
    private String mcVersion;
    private String serverTypeOverride;
    private List<String> allowedPlayers;
    private String trackingType;

    public ConfigManager(PluginUpdater plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Void> syncConfig() {
        plugin.reloadConfig();

        mcVersion = plugin.getConfig().getString("minecraft-version", Bukkit.getBukkitVersion().split("-")[0]);
        serverTypeOverride = plugin.getConfig().getString("server-type-override", "paper");
        trackingType = plugin.getConfig().getString("tracking-type", "all");
        allowedPlayers = plugin.getConfig().getStringList("allowed-players");

        if (!plugin.getConfig().contains("minecraft-version")) plugin.getConfig().set("minecraft-version", mcVersion);
        if (!plugin.getConfig().contains("server-type-override")) plugin.getConfig().set("server-type-override", "paper");
        if (!plugin.getConfig().contains("tracking-type")) plugin.getConfig().set("tracking-type", "all");
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
        Map<String, File> pluginJarMap = new HashMap<>();

        Set<String> loadedPlugins = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(p -> p.getName().toLowerCase())
                .filter(name -> !ignoredDetectedPlugins.contains(name))
                .collect(Collectors.toSet());

        for (String configPluginName : new ArrayList<>(pluginsSection.getKeys(false))) {
            if (configPluginName.equals("Modrinth-Example") || configPluginName.equals("PluginUpdater-WB") || configPluginName.equals("GitHub-Example") || configPluginName.equals("CustomPlugin-Example") || configPluginName.equals("HangarPlugin-Example") || configPluginName.equals("SpigotPlugin-Example")) {
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

            // Collect jar file path (used for Modrinth hash lookup)
            try {
                var cs = plugin.getClass().getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    String path = cs.getLocation().toURI().getPath();
                    if (path != null && path.startsWith("/") && path.length() > 2 && path.charAt(2) == ':')
                        path = path.substring(1); // fix Windows /C:/ prefix
                    File jarFile = new File(path);
                    if (jarFile.exists() && jarFile.getName().endsWith(".jar"))
                        pluginJarMap.put(name, jarFile);
                }
            } catch (Exception ignored) {}

            if (!pluginsSection.contains(name)) {
                ConfigurationSection pSec = pluginsSection.createSection(name);
                pSec.set("enabled", true);
                if (trackingType != null) {
                    if (trackingType.equalsIgnoreCase("all")) {
                        pSec.set("allowed-release-types", Arrays.asList("release", "beta", "alpha"));
                    } else {
                        pSec.set("allowed-release-types", Collections.singletonList(trackingType.toLowerCase()));
                    }
                } else {
                    pSec.set("allowed-release-types", Collections.singletonList("release"));
                }
                pSec.set("current-version", plugin.getDescription().getVersion());
                changesMade = true;

                // Use plugin.yml website URL — only accept Modrinth, Hangar, or Spigot
                String website = plugin.getDescription().getWebsite();
                String[] sourceInfo = parseSourceFromUrl(website);
                if (sourceInfo != null && Set.of("MODRINTH", "HANGAR", "SPIGOT").contains(sourceInfo[0])) {
                    pSec.set("type", sourceInfo[0]);
                    pSec.set("project-id", sourceInfo[1]);
                    pSec.set("github-repo", null);
                    pSec.set("custom-url", null);
                    this.plugin.getLogger().info("Auto-configured " + name + " from plugin.yml website → " + sourceInfo[0] + " [" + sourceInfo[1] + "]");
                } else {
                    // No usable URL — queue for async hash + name search
                    pSec.set("type", "MODRINTH");
                    pSec.set("project-id", name.toLowerCase().replace(" ", "-"));
                    newlyScannedPlugins.add(name);
                }
            } else {
                ConfigurationSection pSec = pluginsSection.getConfigurationSection(name);
                if (pSec != null && !pSec.contains("allowed-release-types")) {
                    if (trackingType != null) {
                        if (trackingType.equalsIgnoreCase("all")) {
                            pSec.set("allowed-release-types", Arrays.asList("release", "beta", "alpha"));
                        } else {
                            pSec.set("allowed-release-types", Collections.singletonList(trackingType.toLowerCase()));
                        }
                    } else {
                        pSec.set("allowed-release-types", Collections.singletonList("release"));
                    }
                    changesMade = true;
                }
                if (pSec != null) {
                    String currentConfigVersion = pSec.getString("current-version", "");
                    if (!currentConfigVersion.equals(plugin.getDescription().getVersion())) {
                        pSec.set("current-version", plugin.getDescription().getVersion());
                        changesMade = true;
                    }

                    // Re-resolve if project-id is still a placeholder (name slug)
                    String currentType = pSec.getString("type", "MODRINTH");
                    String currentId   = pSec.getString("project-id", "");
                    String placeholder = name.toLowerCase().replace(" ", "-");
                    if (currentType.equals("MODRINTH") && currentId.equalsIgnoreCase(placeholder)) {
                        String website = plugin.getDescription().getWebsite();
                        String[] sourceInfo = parseSourceFromUrl(website);
                        if (sourceInfo != null && Set.of("MODRINTH", "HANGAR", "SPIGOT").contains(sourceInfo[0])) {
                            pSec.set("type", sourceInfo[0]);
                            pSec.set("project-id", sourceInfo[1]);
                            pSec.set("github-repo", null);
                            pSec.set("custom-url", null);
                            changesMade = true;
                            this.plugin.getLogger().info("Re-resolved " + name + " from plugin.yml website → " + sourceInfo[0] + " [" + sourceInfo[1] + "]");
                        } else {
                            newlyScannedPlugins.add(name);
                        }
                    }
                }
            }
        }

        sortPluginConfig(pluginsSection);

        // If tracking-type is set, enforce it for all plugins
        if (trackingType != null) {
            for (String key : pluginsSection.getKeys(false)) {
                if (trackingType.equalsIgnoreCase("all")) {
                    pluginsSection.set(key + ".allowed-release-types", Arrays.asList("release", "beta", "alpha"));
                } else {
                    pluginsSection.set(key + ".allowed-release-types", Collections.singletonList(trackingType.toLowerCase()));
                }
            }
            changesMade = true;
        }

        if (changesMade || plugin.getConfig().getKeys(false).size() <= 4) {
            saveAndFormatConfig();
        }

        if (!newlyScannedPlugins.isEmpty()) {
            CompletableFuture<Void> resolution = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                // Fire all lookups in parallel: jar hash (most reliable), then name-based
                Map<String, CompletableFuture<String>> hashFutures     = new HashMap<>();
                Map<String, CompletableFuture<String>> modrinthFutures = new HashMap<>();
                Map<String, CompletableFuture<String>> hangarFutures   = new HashMap<>();
                Map<String, CompletableFuture<String>> spigotFutures   = new HashMap<>();

                for (String pName : newlyScannedPlugins) {
                    File jar = pluginJarMap.get(pName);
                    if (jar != null) {
                        hashFutures.put(pName, CompletableFuture.supplyAsync(() -> {
                            try { return getRealModrinthIdFromHash(jar); } catch (Exception e) { return null; }
                        }));
                    }
                    modrinthFutures.put(pName, CompletableFuture.supplyAsync(() -> {
                        try { return getRealModrinthId(pName, jar); } catch (Exception e) { return null; }
                    }));
                    hangarFutures.put(pName, CompletableFuture.supplyAsync(() -> {
                        try { return getRealHangarId(pName, jar); } catch (Exception e) { return null; }
                    }));
                    spigotFutures.put(pName, CompletableFuture.supplyAsync(() -> {
                        try { return getRealSpigotId(pName, jar); } catch (Exception e) { return null; }
                    }));
                }

                List<CompletableFuture<?>> all = new ArrayList<>();
                all.addAll(hashFutures.values());
                all.addAll(modrinthFutures.values());
                all.addAll(hangarFutures.values());
                all.addAll(spigotFutures.values());
                CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).join();

                // Pick best source: hash is definitive; for name matches, fetch upload dates and pick most recent
                Map<String, String[]> resolved = new HashMap<>();
                List<String> stillUnresolved = new ArrayList<>();
                for (String pName : newlyScannedPlugins) {
                    String hashId     = hashFutures.containsKey(pName) ? hashFutures.get(pName).join() : null;
                    String modrinthId = modrinthFutures.get(pName).join();
                    String hangarId   = hangarFutures.get(pName).join();
                    String spigotId   = spigotFutures.get(pName).join();

                    if (hashId != null) {
                        // Exact jar match on Modrinth — definitive, no need to compare dates
                        resolved.put(pName, new String[]{"MODRINTH", hashId, hashId, null, null});
                        continue;
                    }

                    // Fetch upload dates in parallel for every source that found a match
                    Map<String, CompletableFuture<Instant>> dateFutures = new HashMap<>();
                    if (modrinthId != null) {
                        final String id = modrinthId;
                        dateFutures.put("MODRINTH", CompletableFuture.supplyAsync(() -> {
                            try { return getModrinthProjectDate(id); } catch (Exception e) { return null; }
                        }));
                    }
                    if (hangarId != null) {
                        final String id = hangarId;
                        dateFutures.put("HANGAR", CompletableFuture.supplyAsync(() -> {
                            try { return getHangarProjectDate(id); } catch (Exception e) { return null; }
                        }));
                    }
                    if (spigotId != null) {
                        final String id = spigotId;
                        dateFutures.put("SPIGOT", CompletableFuture.supplyAsync(() -> {
                            try { return getSpigotProjectDate(id); } catch (Exception e) { return null; }
                        }));
                    }
                    CompletableFuture.allOf(dateFutures.values().toArray(new CompletableFuture[0])).join();

                    // Pick the source with the most recently uploaded version
                    String bestType = null;
                    String bestId   = null;
                    Instant bestDate = null;
                    for (Map.Entry<String, CompletableFuture<Instant>> de : dateFutures.entrySet()) {
                        Instant date = de.getValue().join();
                        if (date != null && (bestDate == null || date.isAfter(bestDate))) {
                            bestDate = date;
                            bestType = de.getKey();
                            bestId   = bestType.equals("MODRINTH") ? modrinthId
                                     : bestType.equals("HANGAR")   ? hangarId
                                     : spigotId;
                        }
                    }

                    // Fall back to order if no dates were returned
                    if (bestType == null) {
                        if      (modrinthId != null) { bestType = "MODRINTH"; bestId = modrinthId; }
                        else if (hangarId   != null) { bestType = "HANGAR";   bestId = hangarId;   }
                        else if (spigotId   != null) { bestType = "SPIGOT";   bestId = spigotId;   }
                    }

                    if (bestType != null) {
                        resolved.put(pName, new String[]{bestType, bestId, modrinthId, hangarId, spigotId});
                    } else {
                        // None of the primary sources matched — queue for GitHub fallback
                        stillUnresolved.add(pName);
                    }
                }

                // GitHub fallback: only called when modrinth/hangar/spigot all returned nothing
                if (!stillUnresolved.isEmpty()) {
                    Map<String, CompletableFuture<String>> githubFutures = new HashMap<>();
                    for (String pName : stillUnresolved) {
                        File jar = pluginJarMap.get(pName);
                        githubFutures.put(pName, CompletableFuture.supplyAsync(() -> {
                            try { return getRealGitHubRepo(pName, jar); } catch (Exception e) { return null; }
                        }));
                    }
                    CompletableFuture.allOf(githubFutures.values().toArray(new CompletableFuture[0])).join();
                    for (String pName : stillUnresolved) {
                        String githubRepo = githubFutures.get(pName).join();
                        if (githubRepo != null) {
                            resolved.put(pName, new String[]{"GITHUB", githubRepo, null, null, null});
                        }
                        // else: leave placeholder, user must set manually
                    }
                }

                if (!resolved.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (Map.Entry<String, String[]> entry : resolved.entrySet()) {
                            String pluginName = entry.getKey();
                            String[] vals     = entry.getValue();
                            String sourceType = vals[0];
                            String resolvedId = vals[1];
                            String mAltId     = vals[2];
                            String hAltId     = vals[3];
                            String sAltId     = vals[4];
                            plugin.getConfig().set("plugins." + pluginName + ".type", sourceType);
                            if (sourceType.equals("GITHUB")) {
                                plugin.getConfig().set("plugins." + pluginName + ".project-id", null);
                                plugin.getConfig().set("plugins." + pluginName + ".github-repo", resolvedId);
                            } else {
                                plugin.getConfig().set("plugins." + pluginName + ".project-id", resolvedId);
                                plugin.getConfig().set("plugins." + pluginName + ".github-repo", null);
                            }
                            plugin.getConfig().set("plugins." + pluginName + ".custom-url", null);
                            // Store alternate source IDs so update checks can fall back with the correct ID per platform
                            plugin.getConfig().set("plugins." + pluginName + ".alt-modrinth-id",
                                !sourceType.equals("MODRINTH") && mAltId != null ? mAltId : null);
                            plugin.getConfig().set("plugins." + pluginName + ".alt-hangar-id",
                                !sourceType.equals("HANGAR") && hAltId != null ? hAltId : null);
                            plugin.getConfig().set("plugins." + pluginName + ".alt-spigot-id",
                                !sourceType.equals("SPIGOT") && sAltId != null ? sAltId : null);
                            plugin.getLogger().info("Auto-resolved " + pluginName + " → " + sourceType + " [" + resolvedId + "]");
                        }
                        saveAndFormatConfig();
                        resolution.complete(null);
                    });
                } else {
                    resolution.complete(null);
                }
            });
            return resolution;
        }
        return CompletableFuture.completedFuture(null);
    }

    private void sortPluginConfig(ConfigurationSection pluginsSection) {
        if (pluginsSection == null) return;

        List<String> allKeys = new ArrayList<>(pluginsSection.getKeys(false));
        List<String> scannedKeys = new ArrayList<>();
        List<String> finalSortedKeys = new ArrayList<>();

        List<String> fixedExamples = Arrays.asList("Modrinth-Example", "PluginUpdater-WB", "GitHub-Example", "HangarPlugin-Example", "SpigotPlugin-Example", "CustomPlugin-Example");

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
                            pluginKey.equals("PluginUpdater-WB") ||
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
        String resolvedId = extractIdFromInput(type, projectId);
        String pluginPath = "plugins." + pluginName;

        plugin.getConfig().set(pluginPath + ".type", type);
        plugin.getConfig().set(pluginPath + ".project-id", null);
        plugin.getConfig().set(pluginPath + ".github-repo", null);
        plugin.getConfig().set(pluginPath + ".custom-url", null);

        if (type.equals("MODRINTH") || type.equals("HANGAR") || type.equals("SPIGOT")) {
            plugin.getConfig().set(pluginPath + ".project-id", resolvedId);
            saveAndFormatConfig();
            plugin.sendMsg(sender, ChatColor.GREEN + "Set " + pluginName + " to " + type + " with ID " + resolvedId + ".");
        } else if (type.equals("GITHUB")) {
            plugin.getConfig().set(pluginPath + ".github-repo", resolvedId);
            saveAndFormatConfig();
            plugin.sendMsg(sender, ChatColor.GREEN + "Set " + pluginName + " to GitHub with repo " + resolvedId + ".");
        } else if (type.equals("CUSTOM")) {
            plugin.getConfig().set(pluginPath + ".custom-url", resolvedId);
            saveAndFormatConfig();
            plugin.sendMsg(sender, ChatColor.GREEN + "Set " + pluginName + " to Custom with URL " + resolvedId + ".");
        } else {
            plugin.sendMsg(sender, ChatColor.RED + "Invalid source type. Use Modrinth, Hangar, Spigot, GitHub, or Custom.");
        }
    }

    public String[] parseSourceFromUrl(String url) {
        if (url == null || url.isBlank() || !url.contains("://")) return null;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            List<String> segments = Arrays.stream(path.split("/"))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            if (host.contains("modrinth.com") && segments.size() >= 2
                    && (segments.get(0).equalsIgnoreCase("plugin")
                        || segments.get(0).equalsIgnoreCase("mod")
                        || segments.get(0).equalsIgnoreCase("project"))) {
                return new String[]{"MODRINTH", segments.get(1)};
            }
            if (host.contains("hangar.papermc.io") && segments.size() >= 2
                    && !segments.get(0).equalsIgnoreCase("api")) {
                return new String[]{"HANGAR", segments.get(1)};
            }
            if (host.contains("spigotmc.org") && segments.size() >= 2
                    && segments.get(0).equalsIgnoreCase("resources")) {
                String candidate = segments.get(segments.size() - 1);
                if (candidate.matches("\\d+")) return new String[]{"SPIGOT", candidate};
                if (candidate.contains(".")) {
                    String last = candidate.substring(candidate.lastIndexOf('.') + 1);
                    if (last.matches("\\d+")) return new String[]{"SPIGOT", last};
                }
            }
            if (host.contains("github.com") && segments.size() >= 2) {
                return new String[]{"GITHUB", segments.get(0) + "/" + segments.get(1)};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractIdFromInput(String type, String input) {
        if (input == null || !input.contains("://")) return input;
        try {
            URI uri = URI.create(input);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            List<String> segments = Arrays.stream(path.split("/"))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            switch (type.toUpperCase(Locale.ROOT)) {
                case "MODRINTH":
                    if (host.contains("modrinth.com") && segments.size() >= 2)
                        return segments.get(1); // /plugin|mod|project/<slug>
                    break;
                case "HANGAR":
                    if (host.contains("hangar.papermc.io") && segments.size() >= 2
                            && !segments.get(0).equalsIgnoreCase("api"))
                        return segments.get(1); // /Author/<slug>
                    break;
                case "SPIGOT":
                    if (host.contains("spigotmc.org") && segments.size() >= 2
                            && segments.get(0).equalsIgnoreCase("resources")) {
                        String candidate = segments.get(segments.size() - 1);
                        if (candidate.matches("\\d+")) return candidate;
                        if (candidate.contains(".")) {
                            String lastPart = candidate.substring(candidate.lastIndexOf('.') + 1);
                            if (lastPart.matches("\\d+")) return lastPart;
                        }
                    }
                    break;
                case "GITHUB":
                    if (host.contains("github.com") && segments.size() >= 2)
                        return segments.get(0) + "/" + segments.get(1); // owner/repo
                    break;
            }
        } catch (Exception ignored) {}
        return input;
    }

    public String addPluginFromUrl(CommandSender sender, String url) {
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        String type;
        String sourceValue = null;
        String pluginName = null;

        try {
            URI uri = URI.create(url);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            List<String> segments = Arrays.stream(path.split("/"))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            if (host.contains("modrinth.com")) {
                type = "MODRINTH";
                if (segments.size() >= 2 && (segments.get(0).equalsIgnoreCase("project")
                        || segments.get(0).equalsIgnoreCase("plugin")
                        || segments.get(0).equalsIgnoreCase("mod"))) {
                    sourceValue = segments.get(1);
                }
            } else if (host.contains("spigotmc.org")) {
                type = "SPIGOT";
                if (segments.size() >= 2 && segments.get(0).equalsIgnoreCase("resources")) {
                    String candidate = segments.get(segments.size() - 1);
                    if (candidate.matches("\\d+")) {
                        sourceValue = candidate;
                    } else if (candidate.contains(".")) {
                        String[] parts = candidate.split("\\.");
                        String lastPart = parts[parts.length - 1];
                        if (lastPart.matches("\\d+")) {
                            sourceValue = lastPart;
                        }
                    }
                }
            } else if (host.contains("github.com")) {
                type = "GITHUB";
                if (segments.size() >= 2) {
                    sourceValue = segments.get(0) + "/" + segments.get(1);
                }
            } else if (host.contains("hangar.papermc.io")) {
                type = "HANGAR";
                if (segments.size() >= 2 && !segments.get(0).equalsIgnoreCase("api")) {
                    // Web URL: hangar.papermc.io/Author/Slug → use Slug as project ID
                    sourceValue = segments.get(1);
                }
            } else {
                type = "CUSTOM";
                sourceValue = url;
            }

            if (type == null || sourceValue == null) {
                type = "CUSTOM";
                sourceValue = url;
            }

            String slug = sourceValue.contains("/") ? sourceValue.substring(sourceValue.lastIndexOf('/') + 1) : sourceValue;
            for (org.bukkit.plugin.Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
                if (loaded.getName().equalsIgnoreCase(slug) || loaded.getName().equalsIgnoreCase(sourceValue)) {
                    pluginName = loaded.getName();
                    break;
                }
            }

            if (pluginName == null && type.equals("GITHUB") && sourceValue.contains("/")) {
                String repoName = sourceValue.substring(sourceValue.indexOf('/') + 1);
                for (org.bukkit.plugin.Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
                    if (loaded.getName().equalsIgnoreCase(repoName)) {
                        pluginName = loaded.getName();
                        break;
                    }
                }
            }

            if (pluginName == null && !type.equals("CUSTOM")) {
                String fetchedName = fetchPluginNameFromSource(type, sourceValue);
                if (fetchedName != null && !fetchedName.isBlank()) {
                    fetchedName = fetchedName.replaceAll("[^A-Za-z0-9 _-]", "").trim();
                    if (!fetchedName.isEmpty()) {
                        pluginName = fetchedName;
                    }
                }
            }

            if (pluginName == null) {
                pluginName = slug.replaceAll("[^A-Za-z0-9_-]", "");
                if (pluginName.isEmpty()) {
                    plugin.sendMsg(sender, ChatColor.RED + "Could not infer a plugin name from the URL. Please use /upd plugin id instead.");
                    return null;
                }
            }

            ConfigurationSection pluginsSection = plugin.getConfig().getConfigurationSection("plugins");
            if (pluginsSection == null) {
                pluginsSection = plugin.getConfig().createSection("plugins");
            }
            if (!pluginsSection.contains(pluginName)) {
                ConfigurationSection pSec = pluginsSection.createSection(pluginName);
                pSec.set("enabled", true);
                pSec.set("current-version", "0.0.0");
                if (trackingType != null && trackingType.equalsIgnoreCase("all")) {
                    pSec.set("allowed-release-types", Arrays.asList("release", "beta", "alpha"));
                } else if (trackingType != null) {
                    pSec.set("allowed-release-types", Collections.singletonList(trackingType.toLowerCase()));
                } else {
                    pSec.set("allowed-release-types", Collections.singletonList("release"));
                }
            }

            plugin.getConfig().set("plugins." + pluginName + ".type", type);
            plugin.getConfig().set("plugins." + pluginName + ".project-id", type.equals("CUSTOM") || type.equals("GITHUB") ? null : sourceValue);
            plugin.getConfig().set("plugins." + pluginName + ".github-repo", type.equals("GITHUB") ? sourceValue : null);
            plugin.getConfig().set("plugins." + pluginName + ".custom-url", type.equals("CUSTOM") ? sourceValue : null);
            saveAndFormatConfig();
            plugin.sendMsg(sender, ChatColor.GREEN + "Added plugin config for " + pluginName + " using source " + type + ".");
            if (!type.equals("CUSTOM")) {
                plugin.sendMsg(sender, ChatColor.AQUA + "Verify with /upd plugin info " + pluginName + " and adjust the ID if needed.");
            }
            return pluginName;
        } catch (Exception e) {
            plugin.sendMsg(sender, ChatColor.RED + "Failed to parse plugin URL: " + e.getMessage());
            return null;
        }
    }

    private String fetchPluginNameFromSource(String type, String sourceValue) {
        try {
            if (type.equals("MODRINTH")) {
                return fetchModrinthProjectName(sourceValue);
            }
            if (type.equals("SPIGOT")) {
                return fetchSpigotResourceName(sourceValue);
            }
            if (type.equals("GITHUB")) {
                return fetchGitHubRepoName(sourceValue);
            }
            if (type.equals("HANGAR")) {
                return fetchHangarProjectName(sourceValue);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Instant getModrinthProjectDate(String projectId) throws Exception {
        String url = "https://api.modrinth.com/v2/project/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8.toString());
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("updated")) return Instant.parse(json.get("updated").getAsString());
        return null;
    }

    private Instant getHangarProjectDate(String projectId) throws Exception {
        String url = "https://hangar.papermc.io/api/v1/projects/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8.toString());
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("lastUpdated")) return Instant.parse(json.get("lastUpdated").getAsString());
        return null;
    }

    private Instant getSpigotProjectDate(String resourceId) throws Exception {
        String url = "https://api.spiget.org/v2/resources/" + URLEncoder.encode(resourceId, StandardCharsets.UTF_8.toString());
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("updateDate")) return Instant.ofEpochSecond(json.get("updateDate").getAsLong());
        return null;
    }

    private String fetchModrinthProjectName(String projectId) throws Exception {
        String url = "https://api.modrinth.com/v2/project/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8.toString());
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("title")) return json.get("title").getAsString();
        if (json.has("name")) return json.get("name").getAsString();
        return null;
    }

    private String fetchSpigotResourceName(String resourceId) throws Exception {
        String url = "https://api.spiget.org/v2/resources/" + URLEncoder.encode(resourceId, StandardCharsets.UTF_8.toString());
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.has("name") ? json.get("name").getAsString() : null;
    }

    private String fetchGitHubRepoName(String repo) throws Exception {
        String url = "https://api.github.com/repos/" + repo;
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("name")) return json.get("name").getAsString();
        if (json.has("full_name")) return json.get("full_name").getAsString();
        return null;
    }

    private String fetchHangarProjectName(String projectId) throws Exception {
        String url = "https://hangar.papermc.io/api/v1/projects/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8.toString());
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("pluginName")) return json.get("pluginName").getAsString();
        if (json.has("name")) return json.get("name").getAsString();
        if (json.has("title")) return json.get("title").getAsString();
        return null;
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

    private File getPluginJarFile(String pluginName) {
        org.bukkit.plugin.Plugin bPlugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (bPlugin == null) return null;
        try {
            var cs = bPlugin.getClass().getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            String path = cs.getLocation().toURI().getPath();
            if (path != null && path.startsWith("/") && path.length() > 2 && path.charAt(2) == ':')
                path = path.substring(1);
            File f = new File(path);
            return (f.exists() && f.getName().endsWith(".jar")) ? f : null;
        } catch (Exception e) { return null; }
    }

    private List<String> buildNameVariants(String pluginName, File jarFile) {
        Set<String> seen = new LinkedHashSet<>();
        seen.add(pluginName);
        String expanded = PluginUpdaterUtils.expandCamelCase(pluginName);
        if (!expanded.equalsIgnoreCase(pluginName)) seen.add(expanded);
        if (jarFile != null) {
            String jarBase = PluginUpdaterUtils.extractJarBaseName(jarFile);
            if (jarBase != null && !jarBase.equalsIgnoreCase(pluginName)) {
                seen.add(jarBase);
                String jarExpanded = PluginUpdaterUtils.expandCamelCase(jarBase);
                if (!jarExpanded.equalsIgnoreCase(jarBase)) seen.add(jarExpanded);
            }
        }
        return new ArrayList<>(seen);
    }

    private boolean isAnyMatch(List<String> allNames, String title, String slug) {
        for (String name : allNames) {
            if (PluginUpdaterUtils.isNameMatch(title, name)
                    || PluginUpdaterUtils.isNameMatch(slug, name)) return true;
        }
        return false;
    }

    public String getRealModrinthId(String pluginName) throws Exception {
        return getRealModrinthId(pluginName, getPluginJarFile(pluginName));
    }

    private String getRealModrinthId(String pluginName, File jarFile) throws Exception {
        List<String> allNames = buildNameVariants(pluginName, jarFile);
        Set<String> triedSlugs = new LinkedHashSet<>();
        for (String name : allNames) {
            triedSlugs.add(name.toLowerCase().replace(" ", "-"));
            triedSlugs.add(PluginUpdaterUtils.expandCamelCase(name).toLowerCase().replace(" ", "-"));
        }

        for (String trySlug : triedSlugs) {
            String exactUrl = "https://api.modrinth.com/v2/project/" + URLEncoder.encode(trySlug, StandardCharsets.UTF_8.toString());
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(exactUrl)).header("User-Agent", "PluginUpdater-WB").build();
            HttpResponse<String> res = plugin.getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
                String title   = json.has("title") ? json.get("title").getAsString() : null;
                String hitSlug = json.has("slug")  ? json.get("slug").getAsString()  : null;
                if (json.has("id") && isAnyMatch(allNames, title, hitSlug)) {
                    return json.get("id").getAsString();
                }
            }
        }

        Set<String> triedQueries = new LinkedHashSet<>();
        for (String name : allNames) {
            if (triedQueries.add(name.toLowerCase())) {
                String searchUrl = "https://api.modrinth.com/v2/search?query="
                        + URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
                        + "&limit=10&facets=" + URLEncoder.encode("[[\"project_type:plugin\"]]", StandardCharsets.UTF_8.toString());
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(searchUrl)).header("User-Agent", "PluginUpdater-WB").build();
                HttpResponse<String> res = plugin.getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) continue;
                JsonArray hits = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("hits");
                if (hits == null) continue;
                for (JsonElement el : hits) {
                    JsonObject hit = el.getAsJsonObject();
                    String hitTitle = hit.has("title") ? hit.get("title").getAsString() : null;
                    String hitSlug  = hit.has("slug")  ? hit.get("slug").getAsString()  : null;
                    if (isAnyMatch(allNames, hitTitle, hitSlug)) {
                        return hit.get("project_id").getAsString();
                    }
                }
            }
        }
        return null;
    }

    public String getRealSpigotId(String pluginName) throws Exception {
        return getRealSpigotId(pluginName, getPluginJarFile(pluginName));
    }

    private String getRealSpigotId(String pluginName, File jarFile) throws Exception {
        List<String> allNames = buildNameVariants(pluginName, jarFile);
        Set<String> triedQueries = new LinkedHashSet<>();
        for (String name : allNames) {
            if (triedQueries.add(name.toLowerCase())) {
                String searchUrl = "https://api.spiget.org/v2/search/resources/"
                        + URLEncoder.encode(name, StandardCharsets.UTF_8.toString()) + "?size=10";
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(searchUrl)).header("User-Agent", "PluginUpdater-WB").build();
                HttpResponse<String> res = plugin.getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) continue;
                JsonArray results = JsonParser.parseString(res.body()).getAsJsonArray();
                for (JsonElement el : results) {
                    JsonObject resource = el.getAsJsonObject();
                    if (resource.has("name") && resource.has("id")) {
                        String resourceName = resource.get("name").getAsString();
                        if (isAnyMatch(allNames, resourceName, null)) {
                            return resource.get("id").getAsString();
                        }
                    }
                }
            }
        }
        return null;
    }

    public String getRealModrinthIdFromHash(File jarFile) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        try (FileInputStream fis = new FileInputStream(jarFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) sha1.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : sha1.digest()) sb.append(String.format("%02x", b));
        String hash = sb.toString();

        String url = "https://api.modrinth.com/v2/version_file/" + hash + "?algorithm=sha1";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "PluginUpdater-WB")
                .build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("project_id")) return json.get("project_id").getAsString();
        }
        return null;
    }

    public String getRealHangarId(String pluginName) throws Exception {
        return getRealHangarId(pluginName, getPluginJarFile(pluginName));
    }

    private String getRealHangarId(String pluginName, File jarFile) throws Exception {
        List<String> allNames = buildNameVariants(pluginName, jarFile);
        Set<String> triedSlugs = new LinkedHashSet<>();
        for (String name : allNames) {
            triedSlugs.add(name.toLowerCase().replace(" ", "-"));
            triedSlugs.add(PluginUpdaterUtils.expandCamelCase(name).toLowerCase().replace(" ", "-"));
        }

        for (String trySlug : triedSlugs) {
            String exactUrl = "https://hangar.papermc.io/api/v1/projects/" + URLEncoder.encode(trySlug, StandardCharsets.UTF_8.toString());
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(exactUrl)).header("User-Agent", "PluginUpdater-WB").build();
            HttpResponse<String> res = plugin.getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
                String projectName = json.has("pluginName") ? json.get("pluginName").getAsString()
                                   : json.has("name") ? json.get("name").getAsString() : null;
                String projectSlug = null;
                if (json.has("namespace")) {
                    JsonObject ns = json.getAsJsonObject("namespace");
                    if (ns.has("slug")) projectSlug = ns.get("slug").getAsString();
                }
                if (isAnyMatch(allNames, projectName, projectSlug)) {
                    return projectSlug != null ? projectSlug : trySlug;
                }
            }
        }

        Set<String> triedQueries = new LinkedHashSet<>();
        for (String name : allNames) {
            if (triedQueries.add(name.toLowerCase())) {
                String searchUrl = "https://hangar.papermc.io/api/v1/projects?query="
                        + URLEncoder.encode(name, StandardCharsets.UTF_8.toString()) + "&limit=10";
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(searchUrl)).header("User-Agent", "PluginUpdater-WB").build();
                HttpResponse<String> res = plugin.getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) continue;
                JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
                JsonArray results = json.has("result") ? json.getAsJsonArray("result") : null;
                if (results == null) continue;
                for (JsonElement el : results) {
                    JsonObject project = el.getAsJsonObject();
                    String projectSlug = null;
                    String projectName = null;
                    if (project.has("namespace")) {
                        JsonObject ns = project.getAsJsonObject("namespace");
                        if (ns.has("slug")) projectSlug = ns.get("slug").getAsString();
                    }
                    if (project.has("name")) projectName = project.get("name").getAsString();
                    if (isAnyMatch(allNames, projectName, projectSlug)) {
                        return projectSlug != null ? projectSlug : pluginName.toLowerCase().replace(" ", "-");
                    }
                }
            }
        }
        return null;
    }

    public String getRealGitHubRepo(String pluginName) throws Exception {
        return getRealGitHubRepo(pluginName, getPluginJarFile(pluginName));
    }

    private String getRealGitHubRepo(String pluginName, File jarFile) throws Exception {
        List<String> allNames = buildNameVariants(pluginName, jarFile);

        for (String name : allNames) {
            for (String suffix : new String[]{" topic:minecraft-plugin", ""}) {
                String searchUrl = "https://api.github.com/search/repositories?q="
                        + URLEncoder.encode(name + suffix, StandardCharsets.UTF_8.toString())
                        + "&per_page=10";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(searchUrl))
                        .header("User-Agent", "PluginUpdater-WB")
                        .header("Accept", "application/vnd.github+json")
                        .build();
                HttpResponse<String> res = plugin.getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) continue;
                JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
                JsonArray items = json.has("items") ? json.getAsJsonArray("items") : null;
                if (items == null) continue;
                for (JsonElement el : items) {
                    JsonObject repo = el.getAsJsonObject();
                    String repoName = repo.has("name")      ? repo.get("name").getAsString()      : null;
                    String fullName = repo.has("full_name") ? repo.get("full_name").getAsString() : null;
                    if (isAnyMatch(allNames, repoName, null)) return fullName;
                    if (repo.has("topics") && !repo.get("topics").isJsonNull()) {
                        for (JsonElement t : repo.getAsJsonArray("topics")) {
                            if (isAnyMatch(allNames, t.getAsString(), null)) return fullName;
                        }
                    }
                }
            }
        }
        return null;
    }
}
