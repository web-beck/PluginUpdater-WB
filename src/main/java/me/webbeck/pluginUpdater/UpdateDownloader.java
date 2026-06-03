package me.webbeck.pluginUpdater;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateDownloader {
    private final PluginUpdater plugin;
    private final UpdateChecker updateChecker;
    private final ConfigManager configManager;

    public UpdateDownloader(PluginUpdater plugin, UpdateChecker updateChecker, ConfigManager configManager) {
        this.plugin = plugin;
        this.updateChecker = updateChecker;
        this.configManager = configManager;
    }

    public void applyUpdates(CommandSender sender, List<UpdateInfo> updatesToApply) {
        if (updatesToApply.isEmpty()) {
            plugin.sendMsg(sender, ChatColor.RED + "No updates pending to apply.");
            return;
        }

        File updateFolder = new File(plugin.getDataFolder().getParentFile(), "update");
        if (!updateFolder.exists()) updateFolder.mkdirs();

        File backupFolder = new File(plugin.getDataFolder(), "backups");
        if (!backupFolder.exists()) backupFolder.mkdirs();

        plugin.sendMsg(sender, ChatColor.AQUA + "Downloading " + updatesToApply.size() + " updates asynchronously...");

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (UpdateInfo info : updatesToApply) {
            if (!info.requiredDependencies.isEmpty()) {
                plugin.sendMsg(sender, ChatColor.RED + "⚠️ " + info.pluginName + " Requires dependencies: " + String.join(", ", info.requiredDependencies));
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    Plugin runningPlugin = Bukkit.getPluginManager().getPlugin(info.pluginName);
                    if (runningPlugin != null) {
                        File runningJar = new File(runningPlugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
                        File backupFile = new File(backupFolder, info.pluginName + "-" + info.oldVersion + ".jar");
                        Files.copy(runningJar.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                        File[] pluginBackups = backupFolder.listFiles((dir, name) -> name.startsWith(info.pluginName + "-"));
                        if (pluginBackups != null && pluginBackups.length > 2) {
                            Arrays.sort(pluginBackups, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                            for (int i = 2; i < pluginBackups.length; i++) {
                                pluginBackups[i].delete();
                            }
                        }
                    }

                    File targetFile = new File(updateFolder, info.fileName);
                    if (targetFile.exists()) {
                        Path backupPath = new File(backupFolder, info.pluginName + "-existing.jar").toPath();
                        Files.copy(targetFile.toPath(), backupPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    File downloadedFile = downloadFileToDirectory(info.downloadUrl, updateFolder, info.fileName);

                    plugin.getPendingUpdates().remove(info.pluginName.toLowerCase());
                    plugin.sendMsg(sender, ChatColor.GREEN + "Successfully downloaded update for " + info.pluginName + " as " + downloadedFile.getName());
                } catch (Exception e) {
                    plugin.sendMsg(sender, ChatColor.RED + "Failed to download " + info.pluginName + ": " + e.getMessage());
                }
            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
            plugin.sendMsg(sender, ChatColor.GOLD + "All requested updates downloaded! Restart server to apply.");
            updateChecker.runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        });
    }

    public void downloadPluginToPluginsFolder(CommandSender sender, String pluginName) {
        String resolvedName = configManager.resolvePluginName(pluginName);
        if (resolvedName == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + pluginName + "' not found in config.");
            return;
        }

        var targetSec = plugin.getConfig().getConfigurationSection("plugins." + resolvedName);
        if (targetSec == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + resolvedName + "' not found in config.");
            return;
        }

        plugin.sendMsg(sender, ChatColor.AQUA + "Downloading " + resolvedName + " into the plugins folder...");

        CompletableFuture.runAsync(() -> {
            try {
                String type = targetSec.getString("type", "MODRINTH").toUpperCase();
                List<String> allowedTypes = targetSec.getStringList("allowed-release-types");
                if (allowedTypes.isEmpty() || allowedTypes.contains("all") || allowedTypes.contains("ALL")) {
                    allowedTypes = Arrays.asList("release", "beta", "alpha", "prerelease");
                }

                String currentVer = targetSec.getString("current-version", "0.0.0");
                UpdateInfo info = null;

                if (type.equals("MODRINTH")) {
                    String serverType = configManager.getPluginServerType(resolvedName);
                    info = updateChecker.checkModrinth(resolvedName, targetSec.getString("project-id"), currentVer, allowedTypes, serverType);
                } else if (type.equals("GITHUB")) {
                    info = updateChecker.checkGitHub(resolvedName, targetSec.getString("github-repo"), currentVer, allowedTypes);
                } else if (type.equals("HANGAR")) {
                    String serverType = configManager.getPluginServerType(resolvedName);
                    info = updateChecker.checkHangar(resolvedName, targetSec.getString("project-id"), currentVer, allowedTypes, serverType);
                } else if (type.equals("SPIGOT")) {
                    info = updateChecker.checkSpigot(resolvedName, targetSec.getString("project-id"), currentVer);
                } else if (type.equals("CUSTOM")) {
                    String customUrl = targetSec.getString("custom-url");
                    if (customUrl == null || customUrl.isBlank()) {
                        plugin.sendMsg(sender, ChatColor.RED + "Custom URL is missing for " + resolvedName + ".");
                        return;
                    }
                    String fileName = resolvedName + ".jar";
                    String path = java.net.URI.create(customUrl).getPath();
                    if (path != null && path.contains("/")) {
                        String candidate = path.substring(path.lastIndexOf('/') + 1);
                        if (candidate.toLowerCase().endsWith(".jar")) {
                            fileName = candidate;
                        }
                    }
                    info = new UpdateInfo(resolvedName, currentVer, "Custom", customUrl, fileName);
                }

                if (info == null) {
                    plugin.sendMsg(sender, ChatColor.RED + "Could not determine a downloadable release for " + resolvedName + ".");
                    return;
                }

                File pluginsFolder = plugin.getDataFolder().getParentFile();
                if (!pluginsFolder.exists()) {
                    pluginsFolder.mkdirs();
                }

                File updateFolder = new File(plugin.getDataFolder().getParentFile(), "update");
                if (!updateFolder.exists()) updateFolder.mkdirs();

                File targetFile = new File(pluginsFolder, info.fileName);
                File backupFolder = new File(plugin.getDataFolder(), "backups");
                if (!backupFolder.exists()) backupFolder.mkdirs();

                File downloadedFile;
                if (targetFile.exists()) {
                    // If the plugin jar already exists in the plugins folder, stage the new download into the update folder
                    downloadedFile = downloadFileToDirectory(info.downloadUrl, updateFolder, info.fileName);
                    plugin.sendMsg(sender, ChatColor.GREEN + "Downloaded " + resolvedName + " to update folder as " + downloadedFile.getName() + ". Restart server to apply.");
                } else {
                    // Otherwise download directly to plugins folder
                    downloadedFile = downloadFileToDirectory(info.downloadUrl, pluginsFolder, info.fileName);
                    plugin.sendMsg(sender, ChatColor.GREEN + "Downloaded " + resolvedName + " to plugins folder as " + downloadedFile.getName() + ". Restart server to load it.");
                }
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to download plugin to plugins folder: " + e.getMessage());
            }
        });
    }

    private File downloadFileToDirectory(String downloadUrl, File directory, String fallbackName) throws Exception {
        if (!directory.exists()) {
            directory.mkdirs();
        }

        File tempFile = new File(directory, fallbackName + ".download.tmp");
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(downloadUrl))
                .build();

        java.net.http.HttpResponse<Path> response = plugin.getHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofFile(tempFile.toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));

        String actualName = extractFileNameFromResponse(response, fallbackName);
        File resultFile = new File(directory, actualName);
        if (!resultFile.toPath().equals(tempFile.toPath())) {
            Files.move(tempFile.toPath(), resultFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return resultFile;
    }

    private String extractFileNameFromResponse(java.net.http.HttpResponse<?> response, String fallbackName) {
        var contentDisposition = response.headers().firstValue("Content-Disposition");
        if (contentDisposition.isPresent()) {
            Matcher matcher = Pattern.compile("filename\\*?=(?:UTF-8''?)?\"?([^\";]+)\"?").matcher(contentDisposition.get());
            if (matcher.find()) {
                String filename = matcher.group(1).trim();
                if (!filename.isEmpty()) {
                    return new File(filename).getName();
                }
            }
        }

        String path = response.uri().getPath();
        if (path != null && path.toLowerCase().endsWith(".jar")) {
            return new File(path).getName();
        }

        return fallbackName;
    }

    public void performRollback(CommandSender sender, String pluginName, String fileName) {
        File backupFolder = new File(plugin.getDataFolder(), "backups");
        File backupFile = new File(backupFolder, fileName);
        if (!backupFile.exists()) {
            plugin.sendMsg(sender, ChatColor.RED + "Backup file not found!");
            return;
        }

        File updateFolder = new File(plugin.getDataFolder().getParentFile(), "update");
        if (!updateFolder.exists()) updateFolder.mkdirs();

        CompletableFuture.runAsync(() -> {
            try {
                Files.copy(backupFile.toPath(), new File(updateFolder, fileName).toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.sendMsg(sender, ChatColor.GREEN + "Rollback staged! " + fileName + " placed in update folder. Restart to apply.");
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to stage rollback: " + e.getMessage());
            }
        });
    }

    public void forceRedownload(CommandSender sender, String pluginName) {
        String resolvedName = configManager.resolvePluginName(pluginName);
        if (resolvedName == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + pluginName + "' not found in config.");
            return;
        }

        var targetSec = plugin.getConfig().getConfigurationSection("plugins." + resolvedName);
        if (targetSec == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + resolvedName + "' not found in config.");
            return;
        }

        plugin.sendMsg(sender, ChatColor.AQUA + "Fetching latest version data for " + resolvedName + " to redownload...");

        CompletableFuture.runAsync(() -> {
            String type = targetSec.getString("type", "MODRINTH").toUpperCase();
            List<String> allowedTypes = targetSec.getStringList("allowed-release-types");
            if (allowedTypes.isEmpty() || allowedTypes.contains("all") || allowedTypes.contains("ALL")) {
                allowedTypes = Arrays.asList("release", "beta", "alpha", "prerelease");
            }

            Plugin runningPlugin = Bukkit.getPluginManager().getPlugin(resolvedName);
            String currentVer = runningPlugin != null ? runningPlugin.getDescription().getVersion() : targetSec.getString("current-version", "0.0.0");

            try {
                UpdateInfo info = null;
                if (type.equals("MODRINTH")) {
                    String serverType = configManager.getPluginServerType(resolvedName);
                    info = updateChecker.checkModrinth(resolvedName, targetSec.getString("project-id"), currentVer, allowedTypes, serverType);
                } else if (type.equals("GITHUB")) {
                    info = updateChecker.checkGitHub(resolvedName, targetSec.getString("github-repo"), currentVer, allowedTypes);
                } else if (type.equals("HANGAR")) {
                    String serverType = configManager.getPluginServerType(resolvedName);
                    info = updateChecker.checkHangar(resolvedName, targetSec.getString("project-id"), currentVer, allowedTypes, serverType);
                } else if (type.equals("SPIGOT")) {
                    info = updateChecker.checkSpigot(resolvedName, targetSec.getString("project-id"), currentVer);
                } else if (type.equals("CUSTOM")) {
                    info = new UpdateInfo(resolvedName, currentVer, "Custom", targetSec.getString("custom-url"), resolvedName + "-update.jar");
                }

                if (info != null) {
                    applyUpdates(sender, Collections.singletonList(info));
                } else {
                    plugin.sendMsg(sender, ChatColor.RED + "Could not find any valid releases for " + resolvedName + " to redownload.");
                }
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to fetch data for " + resolvedName + ": " + e.getMessage());
            }
        });
    }
}
