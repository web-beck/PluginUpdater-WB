package me.webbeck.pluginUpdater;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

                    var req = java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(info.downloadUrl)).build();
                    File targetFile = new File(updateFolder, info.fileName);
                    plugin.getHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofFile(targetFile.toPath(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));

                    plugin.getPendingUpdates().remove(info.pluginName.toLowerCase());
                    plugin.sendMsg(sender, ChatColor.GREEN + "Successfully downloaded update for " + info.pluginName);
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
