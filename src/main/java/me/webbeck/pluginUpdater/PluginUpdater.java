package me.webbeck.pluginUpdater;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PluginUpdater extends JavaPlugin implements Listener {
    private HttpClient httpClient;
    private final Map<String, UpdateInfo> pendingUpdates = new ConcurrentHashMap<>();
    private volatile boolean initialCheckComplete = false;

    // Holds the plugin name awaiting destructive confirmation (e.g. delete)
    private String pendingDeletionPlugin = null;

    private ConfigManager configManager;
    private UpdateChecker updateChecker;
    private UpdateDownloader updateDownloader;
    private GeyserManager geyserManager;
    private CommandHandler commandHandler;

    @Override
    public void onEnable() {
        httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        saveDefaultConfig();

        configManager = new ConfigManager(this);
        updateChecker = new UpdateChecker(this, configManager, httpClient);
        updateDownloader = new UpdateDownloader(this, updateChecker, configManager);
        geyserManager = new GeyserManager(this, httpClient);
        commandHandler = new CommandHandler(this, configManager, updateChecker, updateDownloader, geyserManager);

        getCommand("updater").setExecutor(commandHandler);
        getCommand("updater").setTabCompleter(commandHandler);
        getServer().getPluginManager().registerEvents(this, this);
        // Process any deletions that were scheduled from a previous session
        processPendingDeletions();

        // Register shutdown hook to ensure deletion at JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            getLogger().info("Executing shutdown hook for pending deletions...");
            performFinalDeletions();
        }, "PluginUpdater-ShutdownDeletions"));

        getServer().getScheduler().runTaskLater(this, () -> {
            configManager.syncConfig();
            updateChecker.runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        }, 1L);
    }

    @Override
    public void onDisable() {
        // Attempt to process any pending deletions during disable
        processPendingDeletions();
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public Map<String, UpdateInfo> getPendingUpdates() {
        return pendingUpdates;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public void sendMsg(org.bukkit.command.CommandSender sender, String msg) {
        Bukkit.getScheduler().runTask(this, () ->
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(msg))
        );
    }

    public void updateActionBar(org.bukkit.command.CommandSender sender, String msg) {
        if (sender instanceof Player player) {
            Bukkit.getScheduler().runTask(this, () ->
                    player.sendActionBar(Component.text(msg, NamedTextColor.AQUA))
            );
        }
    }

    public void sendInteractiveListMsg(org.bukkit.command.CommandSender sender, String name, String oldV, String newV, boolean needsUpdate) {
        Bukkit.getScheduler().runTask(this, () -> {
            Component tc = Component.text(name, NamedTextColor.AQUA)
                    .append(Component.text(": ", NamedTextColor.GRAY))
                    .append(Component.text(oldV, NamedTextColor.GRAY))
                    .append(Component.text(" -> ", NamedTextColor.GRAY))
                    .append(Component.text(newV, NamedTextColor.AQUA))
                    .append(Component.text(" ", NamedTextColor.GRAY));

            if (needsUpdate) {
                if (sender instanceof Player) {
                    Component btn = Component.text("[CLICK TO UPDATE]", NamedTextColor.GREEN)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/upd run " + name))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click to download update", NamedTextColor.YELLOW)));
                    tc = tc.append(btn);
                } else {
                    tc = tc.append(Component.text("[Use /upd run " + name + "]", NamedTextColor.GREEN));
                }
            } else {
                tc = tc.append(Component.text("[UP TO DATE]", NamedTextColor.GREEN));
            }

            sender.sendMessage(tc);
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (configManager.hasPermission(p)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!pendingUpdates.isEmpty()) {
                    p.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GOLD + "[PluginUpdater] " + ChatColor.YELLOW + "There are " + pendingUpdates.size() + " plugin updates pending! Use /upd list"));
                } else if (initialCheckComplete) {
                    p.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GOLD + "[PluginUpdater] " + ChatColor.GREEN + "All plugins are up to date!"));
                }
            }, 40L);
        }
    }

    public void setInitialCheckComplete() {
        this.initialCheckComplete = true;
    }

    public String getPendingDeletionPlugin() {
        return pendingDeletionPlugin;
    }

    public void setPendingDeletionPlugin(String pendingDeletionPlugin) {
        this.pendingDeletionPlugin = pendingDeletionPlugin;
    }

    private void processPendingDeletions() {
        File pendingFile = new File(getDataFolder(), "pending-deletions.txt");
        if (!pendingFile.exists()) return;

        File pluginsFolder = getDataFolder().getParentFile();
        java.util.List<String> remaining = new java.util.ArrayList<>();
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(pendingFile.toPath());
            for (String line : lines) {
                String entry = line == null ? "" : line.trim();
                if (entry.isEmpty()) continue;
                boolean success = false;
                try {
                    if (entry.startsWith("jar:")) {
                        String name = entry.substring(4);
                        File target = new File(pluginsFolder, name);
                        if (target.exists()) {
                            // Try to disable matching loaded plugin first
                            tryDisableLoadedPlugin(name);
                            
                            // Attempt direct deletion with retries
                            success = attemptDeletion(target, 3);
                            
                            if (success) {
                                getLogger().info("Deleted scheduled plugin jar: " + name);
                            } else {
                                getLogger().warning("Could not delete jar (queued for JVM shutdown): " + name);
                                // Queue as final fallback
                                target.deleteOnExit();
                                success = true; // Consider this successful since we queued it
                            }
                        } else {
                            getLogger().info("Scheduled jar not found: " + name);
                            success = true; // nothing to do
                        }
                    } else if (entry.startsWith("dir:")) {
                        String dirName = entry.substring(4);
                        File targetDir = new File(pluginsFolder, dirName);
                        if (targetDir.exists()) {
                            try {
                                java.nio.file.Files.walk(targetDir.toPath())
                                        .sorted(java.util.Comparator.reverseOrder())
                                        .forEach(p -> {
                                            try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
                                        });
                                getLogger().info("Deleted scheduled plugin data folder: " + dirName);
                                success = true;
                            } catch (Exception e) {
                                getLogger().warning("Failed to delete folder " + dirName + ": " + e.getMessage());
                                success = false;
                            }
                        } else {
                            getLogger().info("Scheduled plugin data folder not found: " + dirName);
                            success = true;
                        }
                    } else {
                        // legacy: plain filename
                        File target = new File(pluginsFolder, entry);
                        if (target.exists()) {
                            success = attemptDeletion(target, 3);
                            if (success) {
                                getLogger().info("Deleted scheduled plugin jar: " + entry);
                            } else {
                                target.deleteOnExit();
                                success = true;
                            }
                        } else {
                            success = true;
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to process scheduled deletion entry '" + entry + "': " + e.getMessage());
                    success = false;
                }

                if (!success) {
                    remaining.add(entry);
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to process pending deletions: " + e.getMessage());
        }

        try {
            if (remaining.isEmpty()) java.nio.file.Files.deleteIfExists(pendingFile.toPath());
            else java.nio.file.Files.write(pendingFile.toPath(), remaining);
        } catch (Exception ignored) {}
    }

    private void tryDisableLoadedPlugin(String jarName) {
        String lowerName = jarName.toLowerCase().replaceAll("\\.jar$", "");
        for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p == null) continue;
            String pName = p.getName() == null ? "" : p.getName().toLowerCase();
            if (lowerName.contains(pName) || pName.contains(lowerName)) {
                try {
                    getLogger().info("Attempting to disable plugin before deletion: " + p.getName());
                    Bukkit.getPluginManager().disablePlugin(p);
                    System.gc(); // Force garbage collection to release classloader
                } catch (Exception ignored) {}
                break;
            }
        }
    }

    private boolean attemptDeletion(File target, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            try {
                java.nio.file.Files.delete(target.toPath());
                return true;
            } catch (java.nio.file.NoSuchFileException e) {
                return true; // File already gone
            } catch (Exception e) {
                getLogger().warning("Deletion attempt " + (i + 1) + "/" + maxRetries + " failed for " + target.getName() + ": " + e.getClass().getSimpleName());
                if (i < maxRetries - 1) {
                    try {
                        Thread.sleep(100); // Brief pause before retry
                    } catch (InterruptedException ignored) {}
                }
            }
        }
        return false;
    }

    private void performFinalDeletions() {
        File pendingFile = new File(getDataFolder(), "pending-deletions.txt");
        if (!pendingFile.exists()) return;

        File pluginsFolder = getDataFolder().getParentFile();
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(pendingFile.toPath());
            for (String line : lines) {
                String entry = line == null ? "" : line.trim();
                if (entry.isEmpty()) continue;
                
                try {
                    if (entry.startsWith("jar:")) {
                        String name = entry.substring(4);
                        File target = new File(pluginsFolder, name);
                        if (target.exists()) {
                            attemptDeletion(target, 5); // More aggressive retries at shutdown
                        }
                    } else if (entry.startsWith("dir:")) {
                        String dirName = entry.substring(4);
                        File targetDir = new File(pluginsFolder, dirName);
                        if (targetDir.exists()) {
                            java.nio.file.Files.walk(targetDir.toPath())
                                    .sorted(java.util.Comparator.reverseOrder())
                                    .forEach(p -> {
                                        try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
                                    });
                        }
                    }
                } catch (Exception e) {
                    // Silently continue at shutdown
                }
            }
            java.nio.file.Files.deleteIfExists(pendingFile.toPath());
        } catch (Exception ignored) {}
    }
}
