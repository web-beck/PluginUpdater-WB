package me.webbeck.pluginUpdater;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CommandHandler implements CommandExecutor, TabCompleter {
    private final PluginUpdater plugin;
    private final ConfigManager configManager;
    private final UpdateChecker updateChecker;
    private final UpdateDownloader updateDownloader;
    private final GeyserManager geyserManager;

    public CommandHandler(PluginUpdater plugin, ConfigManager configManager, UpdateChecker updateChecker, UpdateDownloader updateDownloader, GeyserManager geyserManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.updateChecker = updateChecker;
        this.updateDownloader = updateDownloader;
        this.geyserManager = geyserManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!configManager.hasPermission(sender)) {
            plugin.sendMsg(sender, ChatColor.RED + "You don't have permission to use this.");
            return true;
        }

        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help":
                showHelp(sender);
                break;
            case "-v":
                plugin.sendMsg(sender, ChatColor.AQUA + "PluginUpdater-WB version " + plugin.getDescription().getVersion());
                break;
            case "check":
                updateChecker.runUpdateCheck(sender, false, null);
                break;
            case "run":
                if (args.length > 1) {
                    String target = PluginUpdaterUtils.joinArgs(args, 1).toLowerCase();
                    if (plugin.getPendingUpdates().containsKey(target)) {
                        updateDownloader.applyUpdates(sender, Collections.singletonList(plugin.getPendingUpdates().get(target)));
                    } else {
                        plugin.sendMsg(sender, ChatColor.RED + "No pending updates found for: " + target);
                    }
                } else {
                    updateDownloader.applyUpdates(sender, new ArrayList<>(plugin.getPendingUpdates().values()));
                }
                break;
            case "reload":
                configManager.syncConfig();
                plugin.sendMsg(sender, ChatColor.GREEN + "Configuration reloaded and synced!");
                break;
            case "list":
                if (args.length > 1) {
                    String filter = args[1].toLowerCase();
                    if (filter.equals("versions")) {
                        plugin.sendMsg(sender, ChatColor.YELLOW + "Fetching unfiltered latest physical releases...");
                        updateChecker.runUpdateCheck(sender, true, "versions");
                    } else if (filter.equals("all")) {
                        updateChecker.displayPluginList(sender, "all");
                    } else if (filter.equals("enabled")) {
                        displayToggleList(sender, true);
                    } else if (filter.equals("disabled")) {
                        displayToggleList(sender, false);
                    } else {
                        plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd list [all|versions|enabled|disabled]");
                    }
                } else {
                    updateChecker.displayPluginList(sender, "pending");
                }
                break;
            case "plugin":
                handlePluginSubcommand(sender, args);
                break;
            default:
                plugin.sendMsg(sender, ChatColor.RED + "Unknown subcommand. Use /upd help");
        }
        return true;
    }

    private void handlePluginSubcommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd plugin <info|track|redownload|rollback|geyser|id>");
            return;
        }

        String pluginSub = args[1].toLowerCase();
        switch (pluginSub) {
            case "id":
            case "resolve":
                handleResolveCommand(sender, args);
                break;
            case "geyser":
                handleGeyserCommand(sender, args);
                break;
            case "toggle":
                if (args.length < 4) return;
                configManager.setPluginToggle(sender, PluginUpdaterUtils.joinArgs(args, 2, args.length - 1), Boolean.parseBoolean(args[args.length - 1]));
                break;
            case "rollback":
                handleRollback(sender, args);
                break;
            case "redownload":
                if (args.length < 3) {
                    plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd plugin redownload <PluginName>");
                    return;
                }
                updateDownloader.forceRedownload(sender, PluginUpdaterUtils.joinArgs(args, 2));
                break;
            case "info":
                if (args.length < 3) {
                    plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd plugin info <PluginName>");
                    return;
                }
                showPluginInfo(sender, PluginUpdaterUtils.joinArgs(args, 2));
                break;
            case "track":
                handleTrackCommand(sender, args);
                break;
            default:
                plugin.sendMsg(sender, ChatColor.RED + "Unknown plugin subcommand.");
        }
    }

    private void handleResolveCommand(CommandSender sender, String[] args) {
        if (args.length == 3) {
            String rName = configManager.resolvePluginName(PluginUpdaterUtils.joinArgs(args, 2));
            if (rName == null) {
                plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + PluginUpdaterUtils.joinArgs(args, 2) + "' not found in config.");
                return;
            }
            plugin.sendMsg(sender, ChatColor.AQUA + "Searching Modrinth for the exact ID of " + rName + "...");
            Runnable task = () -> {
                try {
                    String realId = updateChecker.getRealModrinthId(rName);
                    if (realId != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.getConfig().set("plugins." + rName + ".type", "MODRINTH");
                            plugin.getConfig().set("plugins." + rName + ".project-id", realId);
                            plugin.getConfig().set("plugins." + rName + ".github-repo", null);
                            configManager.saveAndFormatConfig();
                            plugin.sendMsg(sender, ChatColor.GREEN + "Successfully locked " + rName + " to Modrinth ID: " + realId);
                        });
                    } else {
                        plugin.sendMsg(sender, ChatColor.RED + "Could not find a match for " + rName + " on Modrinth.");
                    }
                } catch (Exception e) {
                    plugin.sendMsg(sender, ChatColor.RED + "Search failed: " + e.getMessage());
                }
            };
            new Thread(task).start();
        } else if (args.length >= 5) {
            String rName = configManager.resolvePluginName(PluginUpdaterUtils.joinArgs(args, 2, args.length - 2));
            if (rName == null) {
                plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + PluginUpdaterUtils.joinArgs(args, 2, args.length - 2) + "' not found in config.");
                return;
            }
            String source = args[args.length - 2];
            String projectId = args[args.length - 1];
            configManager.setPluginIdConfig(sender, rName, source, projectId);
        } else {
            plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd plugin id <PluginName> [<Modrinth|Hangar|Spigot> <projectId>]");
        }
    }

    private void handleGeyserCommand(CommandSender sender, String[] args) {
        if (args.length > 2) {
            String gSub = args[2].toLowerCase();
            if (gSub.equals("enable")) {
                geyserManager.setGeyserSupport(sender, true);
                return;
            }
            if (gSub.equals("disable")) {
                geyserManager.setGeyserSupport(sender, false);
                return;
            }
        }

        boolean geyserEnabled = plugin.getConfig().getBoolean("geyser-addons.enabled", false);
        if (!geyserEnabled) {
            Component enableBtn = Component.text("[ENABLE GEYSER SUPPORT]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/upd plugin geyser enable"))
                    .hoverEvent(HoverEvent.showText(Component.text("Enable Geyser addon management", NamedTextColor.YELLOW)));
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.RED + "Geyser support is disabled in config.yml. "));
            sender.sendMessage(enableBtn);
            return;
        }

        if (args.length == 2) {
            geyserManager.displayGeyserList(sender);
            return;
        }

        String gSub = args[2].toLowerCase();
        switch (gSub) {
            case "toggle":
                if (args.length < 5) return;
                String addon = args[3];
                boolean state = Boolean.parseBoolean(args[4]);
                plugin.getConfig().set("geyser-addons." + addon, state);
                plugin.saveConfig();
                plugin.sendMsg(sender, ChatColor.GREEN + addon + " is now " + (state ? "ENABLED" : "DISABLED") + ".");
                geyserManager.displayGeyserList(sender);
                break;
            case "update":
                if (args.length < 4) return;
                if (args[3].equalsIgnoreCase("all")) {
                    geyserManager.downloadAllGeyserAddons(sender, true);
                } else {
                    geyserManager.downloadGeyserAddon(sender, args[3], true);
                }
                break;
            case "download":
                if (args.length < 4) return;
                if (args[3].equalsIgnoreCase("all")) {
                    geyserManager.downloadAllGeyserAddons(sender, false);
                } else {
                    geyserManager.downloadGeyserAddon(sender, args[3], false);
                }
                break;
        }
    }

    private void handleRollback(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd plugin rollback <PluginName> [FileName]");
            return;
        }

        String rollbackPlugin = args.length >= 4 ? PluginUpdaterUtils.joinArgs(args, 2, args.length - 1) : args[2];
        if (args.length == 4) {
            updateDownloader.performRollback(sender, rollbackPlugin, args[3]);
        } else if (args.length > 4) {
            updateDownloader.performRollback(sender, rollbackPlugin, PluginUpdaterUtils.joinArgs(args, 3));
        } else {
            showRollbackOptions(sender, rollbackPlugin);
        }
    }

    private void handleTrackCommand(CommandSender sender, String[] args) {
        if (args.length == 2) {
            showTrackOptions(sender, "all");
            return;
        }

        if (args.length >= 3 && args[2].equalsIgnoreCase("server")) {
            if (args.length == 3) {
                plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd plugin track server <auto|paper|spigot|folia|purpur>");
                return;
            }
            setServerTypeOverride(sender, args[3]);
            return;
        }

        if (args.length == 3) {
            showTrackOptions(sender, PluginUpdaterUtils.joinArgs(args, 2));
            return;
        }

        if (args.length >= 4 && args[args.length - 2].equalsIgnoreCase("server")) {
            String targetPlugin = PluginUpdaterUtils.joinArgs(args, 2, args.length - 2);
            setServerTypeOverride(sender, args[args.length - 1], targetPlugin);
            return;
        }

        if (args.length < 4) {
            plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd plugin track <PluginName|all> <release|beta|alpha|all> or /upd plugin track server <auto|paper|spigot|folia|purpur>");
            return;
        }

        String trackPlugin = PluginUpdaterUtils.joinArgs(args, 2, args.length - 1);
        String trackType = args[args.length - 1];
        setTrack(sender, trackPlugin, trackType);
    }

    private void setTrack(CommandSender sender, String pluginName, String channel) {
        List<String> newChannels;
        if (channel.equalsIgnoreCase("all")) {
            newChannels = Collections.singletonList("all");
        } else if (Arrays.asList("release", "beta", "alpha").contains(channel.toLowerCase())) {
            newChannels = Collections.singletonList(channel.toLowerCase());
        } else {
            plugin.sendMsg(sender, ChatColor.RED + "Invalid channel. Use release, beta, alpha, or all.");
            return;
        }

        if (pluginName.equalsIgnoreCase("all") || pluginName.equals("*")) {
            ConfigurationSection pluginsSec = plugin.getConfig().getConfigurationSection("plugins");
            if (pluginsSec != null) {
                for (String key : pluginsSec.getKeys(false)) {
                    pluginsSec.set(key + ".allowed-release-types", newChannels);
                }
                configManager.saveAndFormatConfig();
                plugin.sendMsg(sender, ChatColor.GREEN + "Updated tracking channel for ALL plugins to: " + String.join(", ", newChannels));
                updateChecker.runUpdateCheck(Bukkit.getConsoleSender(), false, null);
                showTrackOptions(sender, "all");
            }
            return;
        }

        String resolvedName = configManager.resolvePluginName(pluginName);
        if (resolvedName == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + pluginName + "' not found in config.");
            return;
        }

        plugin.getConfig().getConfigurationSection("plugins." + resolvedName).set("allowed-release-types", newChannels);
        configManager.saveAndFormatConfig();
        plugin.sendMsg(sender, ChatColor.GREEN + "Updated tracking channel for " + resolvedName + " to: " + String.join(", ", newChannels));
        updateChecker.runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        showTrackOptions(sender, resolvedName);
    }

    private void setServerTypeOverride(CommandSender sender, String type) {
        setServerTypeOverride(sender, type, "all");
    }

    private void setServerTypeOverride(CommandSender sender, String type, String showContext) {
        List<String> valid = Arrays.asList("auto", "paper", "spigot", "folia", "purpur");
        if (!valid.contains(type.toLowerCase())) {
            plugin.sendMsg(sender, ChatColor.RED + "Invalid server type. Use auto, paper, spigot, folia, or purpur.");
            return;
        }

        String context = showContext == null || showContext.isBlank() ? "all" : showContext;
        if (context.equalsIgnoreCase("all")) {
            configManager.setServerTypeOverride(type);
            plugin.sendMsg(sender, ChatColor.GREEN + "Global server type override set to: " + configManager.getServerTypeOverride());
        } else {
            String resolvedName = configManager.resolvePluginName(context);
            if (resolvedName == null) {
                plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + context + "' not found in config.");
                return;
            }
            plugin.getConfig().set("plugins." + resolvedName + ".server-type", type.toLowerCase());
            configManager.saveAndFormatConfig();
            plugin.sendMsg(sender, ChatColor.GREEN + "Server type override for " + resolvedName + " set to: " + type.toLowerCase());
        }

        updateChecker.runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        showTrackOptions(sender, context);
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(Component.text("=== PluginUpdater-WB Help ===", NamedTextColor.GOLD));
        sendHelpLine(sender, "/upd help", "Shows this help menu.");
        sendHelpLine(sender, "/upd -v", "Displays the PluginUpdater version.");
        sendHelpLine(sender, "/upd check", "Checks all plugins for updates asynchronously.");
        sendHelpLine(sender, "/upd run [plugin]", "Downloads and stages pending updates.");
        sendHelpLine(sender, "/upd reload", "Reloads config and synchronizes plugins.");
        sendHelpLine(sender, "/upd list [all|versions|enabled|disabled]", "Lists plugins based on status/filter.");
        sendHelpLine(sender, "/upd plugin info <plugin>", "Shows version & channel info for a plugin.");
        sendHelpLine(sender, "/upd plugin redownload <plugin>", "Forces a fresh download of a plugin.");
        sendHelpLine(sender, "/upd plugin rollback <plugin> [file]", "Opens the backup restoration menu.");
        sendHelpLine(sender, "/upd plugin track <plugin|all> <type>", "Sets tracking channel (release/beta/alpha/all).");
        sendHelpLine(sender, "/upd plugin id <plugin>", "Searches Modrinth and locks the exact project ID.");
        sendHelpLine(sender, "/upd plugin id <plugin> <Modrinth|Hangar|Spigot> <projectId>", "Sets plugin source type and project ID in config.");
        sendHelpLine(sender, "/upd plugin track server <auto|paper|spigot|folia|purpur>", "Sets global Modrinth server type override.");
        sendHelpLine(sender, "/upd plugin geyser", "Manage Geyser, Floodgate & MCXboxBroadcast.");
        sendHelpLine(sender, "/upd plugin geyser download <all|Geyser|Floodgate|MCXboxBroadcast>", "Download any missing Geyser addon jars.");
        sendHelpLine(sender, "/upd plugin geyser update <all|Geyser|Floodgate|MCXboxBroadcast>", "Force update Geyser addon jars.");
    }

    private void sendHelpLine(CommandSender sender, String cmd, String desc) {
        sender.sendMessage(Component.text(cmd, NamedTextColor.AQUA)
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(Component.text(desc, NamedTextColor.GRAY)));
    }

    private void showPluginInfo(CommandSender sender, String pluginName) {
        String resolvedName = configManager.resolvePluginName(pluginName);
        if (resolvedName == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + pluginName + "' not found in config.");
            return;
        }

        ConfigurationSection targetSec = plugin.getConfig().getConfigurationSection("plugins." + resolvedName);
        if (targetSec == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + resolvedName + "' not found in config.");
            return;
        }

        String type = targetSec.getString("type", "MODRINTH").toUpperCase();
        if (type.equals("CUSTOM")) {
            plugin.sendMsg(sender, ChatColor.YELLOW + resolvedName + " is a CUSTOM URL plugin. Version checking is bypassed.");
            return;
        }

        var runningPlugin = org.bukkit.Bukkit.getPluginManager().getPlugin(resolvedName);
        String currentVer = runningPlugin != null ? runningPlugin.getDescription().getVersion() : targetSec.getString("current-version", "0.0.0");
        List<String> currentTracked = targetSec.getStringList("allowed-release-types");
        String serverFileType = configManager.getPluginServerType(resolvedName);

        plugin.sendMsg(sender, ChatColor.AQUA + "Fetching version info for " + resolvedName + " across all channels...");

        Runnable task = () -> {
            Map<String, String> latestVersions = new java.util.HashMap<>();
            try {
                if (type.equals("MODRINTH")) {
                    latestVersions.putAll(updateChecker.fetchAllChannelsModrinth(targetSec.getString("project-id"), serverFileType));
                } else if (type.equals("GITHUB")) {
                    latestVersions.putAll(updateChecker.fetchAllChannelsGitHub(targetSec.getString("github-repo")));
                } else if (type.equals("HANGAR")) {
                    latestVersions.putAll(updateChecker.fetchAllChannelsHangar(targetSec.getString("project-id")));
                } else if (type.equals("SPIGOT")) {
                    latestVersions.putAll(updateChecker.fetchAllChannelsSpigot(targetSec.getString("project-id")));
                }
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to fetch data: " + e.getMessage());
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean allTracked = currentTracked.contains("all") || currentTracked.contains("ALL") || currentTracked.size() >= 3;

                Component header = Component.text("=== [ ", NamedTextColor.GOLD)
                        .append(Component.text(resolvedName, NamedTextColor.AQUA))
                        .append(Component.text(" ] ===", NamedTextColor.GOLD));

                Component serverTypeComp = Component.text("Downloaded Server Type: ", NamedTextColor.GRAY)
                        .append(Component.text(configManager.getPrettyServerType(serverFileType), NamedTextColor.YELLOW));

                Component curVerComp = Component.text("Current Version: ", NamedTextColor.GRAY)
                        .append(Component.text(currentVer, NamedTextColor.WHITE));

                Component trackedComp = Component.text("Tracked Channels: ", NamedTextColor.GRAY)
                        .append(Component.text(String.join(", ", currentTracked), NamedTextColor.YELLOW));

                sender.sendMessage(header);
                sender.sendMessage(serverTypeComp);
                sender.sendMessage(curVerComp);
                sender.sendMessage(trackedComp);
                sender.sendMessage(Component.text("Latest Available Updates:", NamedTextColor.GRAY));

                String[] channels = {"release", "beta", "alpha"};
                for (String channel : channels) {
                    String ver = latestVersions.get(channel);
                    String displayChannel = channel.substring(0, 1).toUpperCase() + channel.substring(1);
                    Component channelLine = Component.text("- " + displayChannel + ": ", NamedTextColor.GRAY);

                    if (ver == null) {
                        channelLine = channelLine.append(Component.text("None Found", NamedTextColor.DARK_GRAY));
                    } else {
                        channelLine = channelLine.append(Component.text(ver + " ", NamedTextColor.WHITE));
                        boolean isTracked = currentTracked.contains(channel) && !allTracked;
                        Component btn = Component.text(isTracked ? "[TRACKING]" : "[TRACK THIS]", isTracked ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                                .clickEvent(ClickEvent.runCommand("/upd plugin track " + resolvedName + " " + channel))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to ONLY track " + channel + " versions", NamedTextColor.GOLD)));
                        channelLine = channelLine.append(btn);
                    }
                    sender.sendMessage(channelLine);
                }

                Component allBtnLine = Component.text("- All Channels: ", NamedTextColor.GRAY)
                        .append(Component.text(allTracked ? "[TRACKING ALL]" : "[TRACK ALL]", allTracked ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                                .clickEvent(ClickEvent.runCommand("/upd plugin track " + resolvedName + " all"))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to track release, beta, and alpha", NamedTextColor.GOLD))));
                sender.sendMessage(allBtnLine);
            });
        };
        new Thread(task).start();
    }

    private void showTrackOptions(CommandSender sender, String pluginName) {
        String resolvedName = pluginName.equalsIgnoreCase("all") ? "all" : configManager.resolvePluginName(pluginName);
        if (resolvedName == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + pluginName + "' not found in config.");
            return;
        }

        List<String> currentTracked = configManager.getTrackedChannels(resolvedName);
        boolean allTracked = currentTracked.contains("all") || currentTracked.contains("ALL") || currentTracked.size() >= 3;

        sender.sendMessage(Component.text("=== Tracking Options for " + (resolvedName.equalsIgnoreCase("all") ? "ALL PLUGINS" : resolvedName) + " ===", NamedTextColor.GOLD));
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GRAY + "Download versions: "));

        List<String> channels = Arrays.asList("release", "beta", "alpha", "all");
        Component channelLine = Component.text("Channels: ", NamedTextColor.GRAY);
        for (String channel : channels) {
            boolean isSelected;
            if (allTracked) {
                isSelected = channel.equalsIgnoreCase("all");
            } else {
                isSelected = currentTracked.stream().anyMatch(c -> c.equalsIgnoreCase(channel));
            }

            NamedTextColor color = isSelected ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
            channelLine = channelLine.append(Component.text("[" + channel.toUpperCase() + "] ", color)
                    .clickEvent(ClickEvent.runCommand("/upd plugin track " + resolvedName + " " + channel))
                    .hoverEvent(HoverEvent.showText(Component.text("Track " + channel + " versions", NamedTextColor.GOLD))));
        }
        sender.sendMessage(channelLine);

        String activeServerType = resolvedName.equalsIgnoreCase("all") ? configManager.getServerTypeOverride() : configManager.getPluginServerType(resolvedName);
        String displayServerOverride = configManager.getPrettyServerType(activeServerType);
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GRAY + "Server override: " + ChatColor.YELLOW + displayServerOverride));

        List<String> serverTypes = Arrays.asList("auto", "paper", "spigot", "folia", "purpur");
        Component serverLine = Component.text("Server Type: ", NamedTextColor.GRAY);
        for (String type : serverTypes) {
            boolean isServerSelected = type.equalsIgnoreCase(activeServerType);
            serverLine = serverLine.append(Component.text("[" + type.toUpperCase() + "] ", isServerSelected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/upd plugin track " + resolvedName + " server " + type))
                    .hoverEvent(HoverEvent.showText(Component.text("Set server type override to " + type, NamedTextColor.GOLD))));
        }
        sender.sendMessage(serverLine);
    }

    private void displayToggleList(CommandSender sender, boolean showEnabled) {
        ConfigurationSection pSec = plugin.getConfig().getConfigurationSection("plugins");
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GOLD + "=== " + (showEnabled ? "Enabled" : "Disabled") + " Plugins ==="));

        if (pSec != null) {
            boolean foundAny = false;
            List<String> sortedKeys = new ArrayList<>(pSec.getKeys(false));
            sortedKeys.sort(String.CASE_INSENSITIVE_ORDER);

            for (String pName : sortedKeys) {
                boolean isEnabled = pSec.getBoolean(pName + ".enabled", true);
                if (isEnabled == showEnabled) {
                    foundAny = true;
                    Component tc = Component.text("- " + pName + " ", NamedTextColor.AQUA);
                    Component btn;
                    if (showEnabled) {
                        btn = Component.text("[DISABLE]", NamedTextColor.RED)
                                .clickEvent(ClickEvent.runCommand("/upd plugin toggle " + pName + " false"))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to stop tracking this plugin", NamedTextColor.YELLOW)));
                    } else {
                        btn = Component.text("[ENABLE]", NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.runCommand("/upd plugin toggle " + pName + " true"))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to resume tracking this plugin", NamedTextColor.YELLOW)));
                    }
                    sender.sendMessage(tc.append(btn));
                }
            }
            if (!foundAny) {
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GRAY + "No plugins found in this state."));
            }
        }
    }

    private void showRollbackOptions(CommandSender sender, String pluginName) {
        File backupDir = new File(plugin.getDataFolder(), "backups");
        File[] files = backupDir.listFiles((dir, name) -> name.toLowerCase().startsWith(pluginName.toLowerCase() + "-"));

        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GOLD + "=== Backups for " + pluginName + " ==="));
        if (files == null || files.length == 0) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.RED + "No backups found."));
            return;
        }

        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File f : files) {
            Component tc = Component.text("- " + f.getName() + " ", NamedTextColor.GRAY);
            if (sender instanceof Player) {
                Component btn = Component.text("[RESTORE]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/upd plugin rollback " + pluginName + " " + f.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to stage restore", NamedTextColor.YELLOW)));
                tc = tc.append(btn);
            } else {
                tc = tc.append(Component.text("(Run: /upd plugin rollback " + pluginName + " " + f.getName() + ")", NamedTextColor.DARK_GRAY));
            }
            sender.sendMessage(tc);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!configManager.hasPermission(sender)) return Collections.emptyList();
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subs = Arrays.asList("check", "run", "list", "reload", "plugin");
            completions.addAll(subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList()));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("run")) {
                completions.addAll(plugin.getPendingUpdates().keySet().stream().filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList()));
            } else if (args[0].equalsIgnoreCase("list")) {
                List<String> mods = Arrays.asList("all", "versions", "enabled", "disabled");
                completions.addAll(mods.stream().filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList()));
            } else if (args[0].equalsIgnoreCase("plugin")) {
                List<String> subs = Arrays.asList("info", "track", "rollback", "redownload", "geyser", "id");
                completions.addAll(subs.stream().filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList()));
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("plugin")) {
                if (Arrays.asList("rollback", "redownload", "info", "id").contains(args[1].toLowerCase())) {
                    completions.addAll(configManager.getEnabledPlugins().stream()
                            .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).collect(Collectors.toList()));
                } else if (args[1].equalsIgnoreCase("track")) {
                    List<String> pluginKeys = new ArrayList<>(configManager.getEnabledPlugins());
                    pluginKeys.add("all");
                    pluginKeys.add("server");
                    completions.addAll(pluginKeys.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).collect(Collectors.toList()));
                } else if (args[1].equalsIgnoreCase("geyser")) {
                    List<String> subs = Arrays.asList("enable", "disable", "update", "download", "toggle");
                    completions.addAll(subs.stream().filter(s -> s.startsWith(args[2].toLowerCase())).collect(Collectors.toList()));
                }
            }
        } else if (args.length == 4) {
            if (args[0].equalsIgnoreCase("plugin")) {
                if (args[1].equalsIgnoreCase("rollback")) {
                    File backupDir = new File(plugin.getDataFolder(), "backups");
                    if (backupDir.exists()) {
                        File[] files = backupDir.listFiles((dir, name) -> name.toLowerCase().startsWith(args[2].toLowerCase() + "-"));
                        if (files != null) {
                            completions.addAll(Arrays.stream(files).map(File::getName)
                                    .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase())).collect(Collectors.toList()));
                        }
                    }
                } else if (args[1].equalsIgnoreCase("id") || args[1].equalsIgnoreCase("resolve")) {
                    List<String> sources = Arrays.asList("Modrinth", "Hangar", "Spigot");
                    completions.addAll(sources.stream().filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase())).collect(Collectors.toList()));
                } else if (args[1].equalsIgnoreCase("track")) {
                    if (args[2].equalsIgnoreCase("server")) {
                        List<String> serverTypes = Arrays.asList("auto", "paper", "spigot", "folia", "purpur");
                        completions.addAll(serverTypes.stream().filter(s -> s.startsWith(args[3].toLowerCase())).collect(Collectors.toList()));
                    } else {
                        List<String> channels = Arrays.asList("release", "beta", "alpha", "all");
                        completions.addAll(channels.stream().filter(s -> s.startsWith(args[3].toLowerCase())).collect(Collectors.toList()));
                    }
                } else if (args[1].equalsIgnoreCase("geyser")) {
                    if (args[2].equalsIgnoreCase("toggle")) {
                        List<String> addons = Arrays.asList("Geyser", "Floodgate", "MCXboxBroadcast");
                        completions.addAll(addons.stream().filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase())).collect(Collectors.toList()));
                    } else if (args[2].equalsIgnoreCase("update") || args[2].equalsIgnoreCase("download")) {
                        List<String> addons = Arrays.asList("all", "Geyser", "Floodgate", "MCXboxBroadcast");
                        completions.addAll(addons.stream().filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase())).collect(Collectors.toList()));
                    }
                }
            }
        } else if (args.length == 5) {
            if (args[0].equalsIgnoreCase("plugin") && args[1].equalsIgnoreCase("geyser") && args[2].equalsIgnoreCase("toggle")) {
                List<String> bools = Arrays.asList("true", "false");
                completions.addAll(bools.stream().filter(s -> s.startsWith(args[4].toLowerCase())).collect(Collectors.toList()));
            }
        }

        completions.sort(String.CASE_INSENSITIVE_ORDER);
        return completions;
    }
}
