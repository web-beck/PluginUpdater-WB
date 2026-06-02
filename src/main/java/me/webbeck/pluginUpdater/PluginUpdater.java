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

        getServer().getScheduler().runTaskLater(this, () -> {
            configManager.syncConfig();
            updateChecker.runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        }, 1L);
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
                }
            }, 40L);
        }
    }
}
