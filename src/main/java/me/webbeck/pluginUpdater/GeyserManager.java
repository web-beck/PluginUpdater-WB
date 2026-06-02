package me.webbeck.pluginUpdater;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

public class GeyserManager {
    private final PluginUpdater plugin;
    private final HttpClient httpClient;

    public GeyserManager(PluginUpdater plugin, HttpClient httpClient) {
        this.plugin = plugin;
        this.httpClient = httpClient;
    }

    public void displayGeyserList(CommandSender sender) {
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GOLD + "=== Geyser Addons Management ==="));
        String[] addons = {"Geyser", "Floodgate", "MCXboxBroadcast"};

        Component downloadAllBtn = Component.text("[DOWNLOAD MISSING]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/upd plugin geyser download all"))
                .hoverEvent(HoverEvent.showText(Component.text("Download any missing Geyser addons", NamedTextColor.YELLOW)));
        Component updateAllBtn = Component.text(" [UPDATE ALL]", NamedTextColor.LIGHT_PURPLE)
                .clickEvent(ClickEvent.runCommand("/upd plugin geyser update all"))
                .hoverEvent(HoverEvent.showText(Component.text("Force download latest versions for all Geyser addons", NamedTextColor.GOLD)));
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GRAY + "Actions: ").append(downloadAllBtn).append(updateAllBtn));

        for (String addon : addons) {
            boolean isEnabled = plugin.getConfig().getBoolean("geyser-addons." + addon, true);
            boolean downloaded = isGeyserAddonDownloaded(addon);

            Component tc = Component.text("- " + addon + " ", NamedTextColor.AQUA)
                    .append(Component.text(downloaded ? "(downloaded) " : "(missing) ", NamedTextColor.GRAY));

            Component toggleBtn;
            if (isEnabled) {
                toggleBtn = Component.text("[DISABLE]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/upd plugin geyser toggle " + addon + " false"))
                        .hoverEvent(HoverEvent.showText(Component.text("Disable updates for " + addon, NamedTextColor.YELLOW)));
            } else {
                toggleBtn = Component.text("[ENABLE]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/upd plugin geyser toggle " + addon + " true"))
                        .hoverEvent(HoverEvent.showText(Component.text("Enable updates for " + addon, NamedTextColor.YELLOW)));
            }

            Component updateBtn = Component.text(" [UPDATE]", NamedTextColor.LIGHT_PURPLE)
                    .clickEvent(ClickEvent.runCommand("/upd plugin geyser update " + addon))
                    .hoverEvent(HoverEvent.showText(Component.text("Force download latest " + addon, NamedTextColor.GOLD)));

            if (isEnabled) {
                sender.sendMessage(tc.append(toggleBtn).append(updateBtn));
            } else {
                sender.sendMessage(tc.append(toggleBtn));
            }
        }
    }

    public void setGeyserSupport(CommandSender sender, boolean enabled) {
        plugin.getConfig().set("geyser-addons.enabled", enabled);
        plugin.saveConfig();
        plugin.sendMsg(sender, ChatColor.GREEN + "Geyser addon management is now " + (enabled ? "ENABLED" : "DISABLED") + ".");
        if (enabled) {
            displayGeyserList(sender);
        }
    }

    public File getGeyserAddonDestination(String addonName) {
        File updateFolder = new File(plugin.getDataFolder().getParentFile(), "update");
        if (!updateFolder.exists()) updateFolder.mkdirs();

        if (addonName.equalsIgnoreCase("Geyser")) {
            return new File(updateFolder, "Geyser-Spigot.jar");
        } else if (addonName.equalsIgnoreCase("Floodgate")) {
            return new File(updateFolder, "floodgate.jar");
        } else if (addonName.equalsIgnoreCase("MCXboxBroadcast")) {
            File extFolder = new File(plugin.getDataFolder().getParentFile(), "Geyser-Spigot/extensions");
            if (!extFolder.exists()) extFolder.mkdirs();
            return new File(extFolder, "MCXboxBroadcastExtension.jar");
        }
        return null;
    }

    public boolean isGeyserAddonDownloaded(String addonName) {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File updateFolder = new File(pluginsDir, "update");

        if (addonName.equalsIgnoreCase("Geyser")) {
            File installed = new File(pluginsDir, "Geyser-Spigot.jar");
            File staged = new File(updateFolder, "Geyser-Spigot.jar");
            return installed.exists() || staged.exists();
        } else if (addonName.equalsIgnoreCase("Floodgate")) {
            File installed = new File(pluginsDir, "floodgate.jar");
            File staged = new File(updateFolder, "floodgate.jar");
            return installed.exists() || staged.exists();
        } else if (addonName.equalsIgnoreCase("MCXboxBroadcast")) {
            File extFolder = new File(pluginsDir, "Geyser-Spigot/extensions");
            File installed = new File(extFolder, "MCXboxBroadcastExtension.jar");
            File staged = new File(updateFolder, "MCXboxBroadcastExtension.jar");
            return installed.exists() || staged.exists();
        }
        return false;
    }

    public void downloadGeyserAddon(CommandSender sender, String addonName, boolean force) {
        String url;
        File dest = getGeyserAddonDestination(addonName);
        if (dest == null) return;

        if (addonName.equalsIgnoreCase("Geyser")) {
            url = "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot";
        } else if (addonName.equalsIgnoreCase("Floodgate")) {
            url = "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot";
        } else if (addonName.equalsIgnoreCase("MCXboxBroadcast")) {
            url = "https://github.com/MCXboxBroadcast/Broadcaster/releases/latest/download/MCXboxBroadcastExtension.jar";
        } else {
            return;
        }

        if (dest.exists() && !force) {
            plugin.sendMsg(sender, ChatColor.YELLOW + addonName + " is already downloaded.");
            return;
        }

        File finalDest = dest;
        String finalUrl = url;
        plugin.sendMsg(sender, ChatColor.AQUA + (force ? "Updating " : "Downloading ") + addonName + "...");

        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(finalUrl)).build();
                httpClient.send(req, HttpResponse.BodyHandlers.ofFile(finalDest.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
                plugin.sendMsg(sender, ChatColor.GREEN + "Successfully downloaded " + addonName + "!");
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to download " + addonName + ": " + e.getMessage());
            }
        });
    }

    public void downloadAllGeyserAddons(CommandSender sender, boolean force) {
        String[] addons = {"Geyser", "Floodgate", "MCXboxBroadcast"};
        for (String addon : addons) {
            downloadGeyserAddon(sender, addon, force);
        }
    }
}
