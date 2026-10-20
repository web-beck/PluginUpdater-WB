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

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "help", "version", "check", "update", "list", "info", "add", "enable", "disable",
            "track", "server", "source", "redownload", "rollback", "geyser", "reload");
    private static final List<String> CHANNELS = Arrays.asList("release", "beta", "alpha", "all");
    private static final List<String> SERVER_TYPES = Arrays.asList("auto", "paper", "spigot", "folia", "purpur");
    private static final List<String> SOURCES = Arrays.asList("MODRINTH", "HANGAR", "SPIGOT", "GITHUB", "CUSTOM");
    private static final List<String> SEARCHABLE_SOURCES = Arrays.asList("MODRINTH", "HANGAR", "SPIGOT");
    private static final List<String> GEYSER_ADDONS = Arrays.asList("Geyser", "Floodgate", "MCXboxBroadcast");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!configManager.hasPermission(sender)) {
            plugin.sendMsg(sender, ChatColor.RED + "You don't have permission to use this.");
            return true;
        }

        // Old "/upd plugin <sub> ..." commands still work: drop the "plugin" word.
        if (args.length > 0 && args[0].equalsIgnoreCase("plugin")) {
            args = Arrays.copyOfRange(args, 1, args.length);
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
            case "version":
            case "-v":
                plugin.sendMsg(sender, ChatColor.AQUA + "PluginUpdater-WB version " + plugin.getDescription().getVersion());
                break;
            case "check":
                updateChecker.runUpdateCheck(sender, false, null);
                break;
            case "update":
            case "run":
                String target = PluginUpdaterUtils.joinArgs(args, 1).toLowerCase();
                if (target.isEmpty() || target.equals("all")) {
                    updateDownloader.applyUpdates(sender, new ArrayList<>(plugin.getPendingUpdates().values()));
                } else if (plugin.getPendingUpdates().containsKey(target)) {
                    updateDownloader.applyUpdates(sender, Collections.singletonList(plugin.getPendingUpdates().get(target)));
                } else {
                    plugin.sendMsg(sender, ChatColor.RED + "No pending updates found for: " + target);
                }
                break;
            case "reload":
                configManager.syncConfig();
                plugin.sendMsg(sender, ChatColor.GREEN + "Configuration reloaded and synced!");
                break;
            case "list":
                String filter = args.length > 1 ? args[1].toLowerCase() : "pending";
                switch (filter) {
                    case "pending":
                    case "all":
                        updateChecker.displayPluginList(sender, filter);
                        break;
                    case "enabled":
                    case "disabled":
                        displayToggleList(sender, filter.equals("enabled"));
                        break;
                    default:
                        plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd list [pending|all|enabled|disabled]");
                }
                break;
            case "info":
                if (args.length == 1) {
                    updateChecker.displayPluginList(sender, "all");
                } else {
                    showPluginInfo(sender, PluginUpdaterUtils.joinArgs(args, 1));
                }
                break;
            case "add":
                if (args.length < 2) {
                    plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd add <url>");
                    return true;
                }
                String pluginName = configManager.addPluginFromUrl(sender, args[1]);
                if (pluginName != null) {
                    updateDownloader.downloadPluginToPluginsFolder(sender, pluginName);
                }
                break;
            case "enable":
            case "disable":
                if (args.length < 2) {
                    plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd " + sub + " <plugin>");
                    return true;
                }
                configManager.setPluginToggle(sender, PluginUpdaterUtils.joinArgs(args, 1), sub.equals("enable"));
                break;
            case "toggle":
                if (args.length < 3) {
                    plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd enable <plugin> or /upd disable <plugin>");
                    return true;
                }
                configManager.setPluginToggle(sender, PluginUpdaterUtils.joinArgs(args, 1, args.length - 1), Boolean.parseBoolean(args[args.length - 1]));
                break;
            case "track":
                handleTrackCommand(sender, args);
                break;
            case "server":
                if (args.length < 2) {
                    plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd server [plugin|all] <auto|paper|spigot|folia|purpur>");
                    return true;
                }
                setServerTypeOverride(sender, args[args.length - 1], args.length == 2 ? "all" : PluginUpdaterUtils.joinArgs(args, 1, args.length - 1));
                break;
            case "source":
            case "id":
            case "resolve":
                handleSourceCommand(sender, args);
                break;
            case "redownload":
                if (args.length < 2) {
                    plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd redownload <plugin>");
                    return true;
                }
                updateDownloader.forceRedownload(sender, PluginUpdaterUtils.joinArgs(args, 1));
                break;
            case "rollback":
                handleRollback(sender, args);
                break;
            case "geyser":
                handleGeyserCommand(sender, args);
                break;
            default:
                plugin.sendMsg(sender, ChatColor.RED + "Unknown command. Use /upd help");
        }
        return true;
    }

    private void handleSourceCommand(CommandSender sender, String[] args) {
        String usage = "Usage: /upd source <plugin> [Modrinth|Hangar|Spigot|GitHub|Custom] [id/repo/url]";
        if (args.length < 2) {
            plugin.sendMsg(sender, ChatColor.RED + usage);
            return;
        }

        // The plugin name may contain spaces, so read the source/id from the end.
        String last = args[args.length - 1].toUpperCase();
        String secondLast = args.length > 3 ? args[args.length - 2].toUpperCase() : "";
        String name;
        String source;
        String id = null;
        if (SOURCES.contains(secondLast)) {
            name = PluginUpdaterUtils.joinArgs(args, 1, args.length - 2);
            source = args[args.length - 2];
            id = args[args.length - 1];
        } else if (args.length > 2 && SEARCHABLE_SOURCES.contains(last)) {
            name = PluginUpdaterUtils.joinArgs(args, 1, args.length - 1);
            source = last;
        } else if (SOURCES.contains(last)) {
            plugin.sendMsg(sender, ChatColor.RED + usage);
            return;
        } else {
            name = PluginUpdaterUtils.joinArgs(args, 1);
            source = "MODRINTH";
        }

        String rName = configManager.resolvePluginName(name);
        if (rName == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + name + "' not found in config.");
            return;
        }

        if (id != null) {
            configManager.setPluginIdConfig(sender, rName, source, id);
        } else {
            searchSourceId(sender, rName, source);
        }
    }

    private void searchSourceId(CommandSender sender, String rName, String source) {
        plugin.sendMsg(sender, ChatColor.AQUA + "Searching " + source + " for the exact ID of " + rName + "...");
        new Thread(() -> {
            try {
                String realId;
                if (source.equals("SPIGOT")) {
                    realId = updateChecker.getRealSpigotId(rName);
                } else if (source.equals("HANGAR")) {
                    realId = updateChecker.getRealHangarId(rName);
                } else {
                    realId = updateChecker.getRealModrinthId(rName);
                }
                if (realId == null) {
                    plugin.sendMsg(sender, ChatColor.RED + "Could not find a match for " + rName + " on " + source + ".");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String pluginPath = "plugins." + rName;
                    plugin.getConfig().set(pluginPath + ".type", source);
                    plugin.getConfig().set(pluginPath + ".project-id", realId);
                    plugin.getConfig().set(pluginPath + ".github-repo", null);
                    plugin.getConfig().set(pluginPath + ".custom-url", null);
                    configManager.saveAndFormatConfig();
                    plugin.sendMsg(sender, ChatColor.GREEN + "Successfully locked " + rName + " to " + source + " ID: " + realId);
                });
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Search failed: " + e.getMessage());
            }
        }).start();
    }

    private void handleGeyserCommand(CommandSender sender, String[] args) {
        String gSub = args.length > 1 ? args[1].toLowerCase() : "";

        // "/upd geyser enable|disable" with no addon turns Geyser support itself on/off.
        if (args.length == 2 && (gSub.equals("enable") || gSub.equals("disable"))) {
            geyserManager.setGeyserSupport(sender, gSub.equals("enable"));
            return;
        }

        boolean geyserEnabled = plugin.getConfig().getBoolean("geyser-addons.enabled", false);
        if (!geyserEnabled) {
            Component enableBtn = Component.text("[ENABLE GEYSER SUPPORT]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/upd geyser enable"))
                    .hoverEvent(HoverEvent.showText(Component.text("Enable Geyser addon management", NamedTextColor.YELLOW)));
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.RED + "Geyser support is disabled in config.yml. "));
            sender.sendMessage(enableBtn);
            return;
        }

        if (args.length == 1) {
            geyserManager.displayGeyserList(sender);
            return;
        }

        String addon = args.length > 2 ? matchGeyserAddon(args[2]) : null;
        if (addon == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd geyser [enable|disable|download|update] [all|Geyser|Floodgate|MCXboxBroadcast]");
            return;
        }

        switch (gSub) {
            case "enable":
            case "disable":
            case "toggle":
                boolean state = gSub.equals("toggle") ? args.length > 3 && Boolean.parseBoolean(args[3]) : gSub.equals("enable");
                for (String a : addon.equals("all") ? GEYSER_ADDONS : Collections.singletonList(addon)) {
                    plugin.getConfig().set("geyser-addons." + a, state);
                }
                plugin.saveConfig();
                plugin.sendMsg(sender, ChatColor.GREEN + addon + " is now " + (state ? "ENABLED" : "DISABLED") + ".");
                geyserManager.displayGeyserList(sender);
                break;
            case "download":
            case "update":
                boolean force = gSub.equals("update");
                if (addon.equals("all")) {
                    geyserManager.downloadAllGeyserAddons(sender, force);
                } else {
                    geyserManager.downloadGeyserAddon(sender, addon, force);
                }
                break;
            default:
                plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd geyser [enable|disable|download|update] [all|Geyser|Floodgate|MCXboxBroadcast]");
        }
    }

    // Config keys are case-sensitive, so map "floodgate" -> "Floodgate".
    private String matchGeyserAddon(String name) {
        if (name.equalsIgnoreCase("all")) return "all";
        return GEYSER_ADDONS.stream().filter(a -> a.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private void handleRollback(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.sendMsg(sender, ChatColor.RED + "Usage: /upd rollback <plugin> [backup file]");
            return;
        }

        String last = args[args.length - 1];
        if (args.length > 2 && last.toLowerCase().endsWith(".jar")) {
            updateDownloader.performRollback(sender, PluginUpdaterUtils.joinArgs(args, 1, args.length - 1), last);
        } else {
            showRollbackOptions(sender, PluginUpdaterUtils.joinArgs(args, 1));
        }
    }

    private void handleTrackCommand(CommandSender sender, String[] args) {
        if (args.length == 1) {
            showTrackOptions(sender, "all");
            return;
        }

        // Old form: "track server <type>" / "track <plugin> server <type>"
        if (args.length >= 3 && args[args.length - 2].equalsIgnoreCase("server")) {
            setServerTypeOverride(sender, args[args.length - 1], args.length == 3 ? "all" : PluginUpdaterUtils.joinArgs(args, 1, args.length - 2));
            return;
        }

        String channel = args[args.length - 1].toLowerCase();
        if (args.length == 2 || !CHANNELS.contains(channel)) {
            showTrackOptions(sender, PluginUpdaterUtils.joinArgs(args, 1));
            return;
        }

        setTrack(sender, PluginUpdaterUtils.joinArgs(args, 1, args.length - 1), channel);
    }

    private void setTrack(CommandSender sender, String pluginName, String channel) {
        List<String> newChannelsForConfig;
        if (channel.equalsIgnoreCase("all")) {
            newChannelsForConfig = Arrays.asList("release", "beta", "alpha");
        } else if (Arrays.asList("release", "beta", "alpha").contains(channel.toLowerCase())) {
            newChannelsForConfig = Collections.singletonList(channel.toLowerCase());
        } else {
            plugin.sendMsg(sender, ChatColor.RED + "Invalid channel. Use release, beta, alpha, or all.");
            return;
        }

        if (pluginName.equalsIgnoreCase("all") || pluginName.equals("*")) {
            ConfigurationSection pluginsSec = plugin.getConfig().getConfigurationSection("plugins");
            if (pluginsSec != null) {
                for (String key : pluginsSec.getKeys(false)) {
                    pluginsSec.set(key + ".allowed-release-types", newChannelsForConfig);
                }
                // Also update the global tracking-type so UI reflects this as the server tracking type
                plugin.getConfig().set("tracking-type", channel.toLowerCase());
                configManager.saveAndFormatConfig();
                plugin.sendMsg(sender, ChatColor.GREEN + "Updated tracking channel for ALL plugins to: " + String.join(", ", newChannelsForConfig) + " (tracking-type set to " + channel.toLowerCase() + ")");
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

        plugin.getConfig().getConfigurationSection("plugins." + resolvedName).set("allowed-release-types", newChannelsForConfig);
        configManager.saveAndFormatConfig();
        plugin.sendMsg(sender, ChatColor.GREEN + "Updated tracking channel for " + resolvedName + " to: " + String.join(", ", newChannelsForConfig));
        updateChecker.runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        showTrackOptions(sender, resolvedName);
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
        sender.sendMessage(Component.text("=== PluginUpdater-WB Help ===", NamedTextColor.GOLD)
                .append(Component.text(" (click a command to use it)", NamedTextColor.DARK_GRAY)));
        sendHelpLine(sender, "/upd check", "Check all plugins for updates.");
        sendHelpLine(sender, "/upd update [plugin]", "Install pending updates (all, or just one plugin).");
        sendHelpLine(sender, "/upd list [all|enabled|disabled]", "Show pending updates, or plugins by status.");
        sendHelpLine(sender, "/upd info <plugin>", "Versions, source and tracking for a plugin.");
        sendHelpLine(sender, "/upd add <url>", "Add and download a plugin from a Modrinth/Hangar/Spigot/GitHub link.");
        sendHelpLine(sender, "/upd enable <plugin>", "Start checking a plugin for updates.");
        sendHelpLine(sender, "/upd disable <plugin>", "Stop checking a plugin for updates.");
        sendHelpLine(sender, "/upd track <plugin|all> <release|beta|alpha|all>", "Choose which release channel to follow.");
        sendHelpLine(sender, "/upd server <plugin|all> <auto|paper|spigot|folia|purpur>", "Choose which server build to download.");
        sendHelpLine(sender, "/upd source <plugin> [Modrinth|Hangar|Spigot|GitHub|Custom] [id/url]", "Find or set where a plugin downloads from.");
        sendHelpLine(sender, "/upd redownload <plugin>", "Force a fresh download of a plugin.");
        sendHelpLine(sender, "/upd rollback <plugin>", "Restore a plugin from a backup.");
        sendHelpLine(sender, "/upd geyser", "Manage Geyser, Floodgate and MCXboxBroadcast.");
        sendHelpLine(sender, "/upd reload", "Reload config.yml.");
        sendHelpLine(sender, "/upd version", "Show the PluginUpdater version.");
    }

    private void sendHelpLine(CommandSender sender, String cmd, String desc) {
        // Clicking pastes the command up to its first argument into chat.
        String base = cmd.replaceAll("\\s*[<\\[].*$", "");
        sender.sendMessage(Component.text(cmd, NamedTextColor.AQUA)
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(Component.text(desc, NamedTextColor.GRAY))
                .clickEvent(ClickEvent.suggestCommand(cmd.equals(base) ? base : base + " "))
                .hoverEvent(HoverEvent.showText(Component.text("Click to type " + base, NamedTextColor.YELLOW))));
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

        plugin.sendMsg(sender, ChatColor.AQUA + "Fetching version info for " + resolvedName + "...");

        Runnable task = () -> {
            Map<String, String> latestVersions = new java.util.HashMap<>();
            try {
                latestVersions.putAll(updateChecker.fetchAllChannelsWithFallback(resolvedName, type, targetSec, serverFileType));
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to fetch data: " + e.getMessage());
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean allTracked = currentTracked.contains("all") || currentTracked.contains("ALL") || currentTracked.size() >= 3;
                String channelDisplay = allTracked ? "all" : String.join(", ", currentTracked);

                String idDisplay;
                if (type.equals("MODRINTH") || type.equals("HANGAR") || type.equals("SPIGOT")) {
                    idDisplay = targetSec.getString("project-id", "<none>");
                } else if (type.equals("GITHUB")) {
                    idDisplay = targetSec.getString("github-repo", "<none>");
                } else {
                    idDisplay = "<none>";
                }

                // ── Header ──────────────────────────────────────────────────
                sender.sendMessage(Component.text("━━━[ ", NamedTextColor.GOLD)
                        .append(Component.text(resolvedName, NamedTextColor.AQUA))
                        .append(Component.text(" ]━━━", NamedTextColor.GOLD)));

                // ── Version │ Server │ Channel ──────────────────────────────
                sender.sendMessage(Component.text("Version: ", NamedTextColor.GRAY)
                        .append(Component.text(currentVer, NamedTextColor.WHITE))
                        .append(Component.text("  │  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Server: ", NamedTextColor.GRAY))
                        .append(Component.text(configManager.getPrettyServerType(serverFileType), NamedTextColor.YELLOW))
                        .append(Component.text("  │  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Channel: ", NamedTextColor.GRAY))
                        .append(Component.text(channelDisplay, NamedTextColor.YELLOW)));

                // ── MC Version ───────────────────────────────────────────────
                sender.sendMessage(Component.text("MC Version: ", NamedTextColor.GRAY)
                        .append(Component.text(configManager.getMinecraftVersion(), NamedTextColor.WHITE)));

                // ── Source + Change buttons ──────────────────────────────────
                sender.sendMessage(Component.text("Source: ", NamedTextColor.GRAY)
                        .append(Component.text(type, NamedTextColor.YELLOW))
                        .append(Component.text("  " + idDisplay, NamedTextColor.WHITE)));

                Component sourceButtons = Component.text("  Change: ", NamedTextColor.DARK_GRAY);
                for (String sourceType : Arrays.asList("MODRINTH", "GITHUB", "HANGAR", "SPIGOT", "CUSTOM")) {
                    boolean isCurrent = sourceType.equalsIgnoreCase(type);
                    sourceButtons = sourceButtons.append(
                            Component.text("[" + sourceType + "] ", isCurrent ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                                    .clickEvent(ClickEvent.suggestCommand("/upd source " + resolvedName + " " + sourceType + " "))
                                    .hoverEvent(HoverEvent.showText(Component.text(
                                            isCurrent ? "Currently using " + sourceType
                                                    : "Switch to " + sourceType + " — enter the ID or URL",
                                            NamedTextColor.YELLOW))));
                }
                sender.sendMessage(sourceButtons);

                // ── Available Versions ───────────────────────────────────────
                sender.sendMessage(Component.text("─────── ", NamedTextColor.DARK_GRAY)
                        .append(Component.text("Available Versions", NamedTextColor.GRAY))
                        .append(Component.text(" ───────", NamedTextColor.DARK_GRAY)));

                for (String channel : new String[]{"release", "beta", "alpha"}) {
                    String ver = latestVersions.get(channel);
                    String label = channel.substring(0, 1).toUpperCase() + channel.substring(1);
                    boolean isTracked = !allTracked && currentTracked.stream().anyMatch(c -> c.equalsIgnoreCase(channel));

                    Component line = Component.text("  " + label + ": ", NamedTextColor.GRAY);
                    if (ver == null) {
                        line = line.append(Component.text("None Found", NamedTextColor.DARK_GRAY));
                    } else {
                        line = line.append(Component.text(ver + " ", NamedTextColor.WHITE))
                                .append(Component.text(isTracked ? "[TRACKING]" : "[TRACK]",
                                                isTracked ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                                        .clickEvent(ClickEvent.runCommand("/upd track " + resolvedName + " " + channel))
                                        .hoverEvent(HoverEvent.showText(Component.text(
                                                isTracked ? "Currently tracking " + channel : "Click to track only " + channel,
                                                NamedTextColor.GOLD))));
                    }
                    sender.sendMessage(line);
                }

                // ── Tracking controls ────────────────────────────────────────
                sender.sendMessage(Component.text("──────────── ", NamedTextColor.DARK_GRAY)
                        .append(Component.text("Tracking", NamedTextColor.GRAY))
                        .append(Component.text(" ────────────", NamedTextColor.DARK_GRAY)));

                Component channelLine = Component.text("  Channel: ", NamedTextColor.GRAY);
                for (String ch : Arrays.asList("release", "beta", "alpha", "all")) {
                    boolean isSelected = allTracked ? ch.equals("all") : currentTracked.stream().anyMatch(c -> c.equalsIgnoreCase(ch));
                    channelLine = channelLine.append(
                            Component.text("[" + ch.toUpperCase() + "] ", isSelected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                                    .clickEvent(ClickEvent.runCommand("/upd track " + resolvedName + " " + ch))
                                    .hoverEvent(HoverEvent.showText(Component.text("Track " + ch + " versions", NamedTextColor.GOLD))));
                }
                sender.sendMessage(channelLine);

                String activeServerType = configManager.getPluginServerType(resolvedName);
                Component serverLine = Component.text("  Server:  ", NamedTextColor.GRAY);
                for (String st : Arrays.asList("auto", "paper", "spigot", "folia", "purpur")) {
                    boolean isSelected = st.equalsIgnoreCase(activeServerType);
                    serverLine = serverLine.append(
                            Component.text("[" + st.toUpperCase() + "] ", isSelected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                                    .clickEvent(ClickEvent.runCommand("/upd server " + resolvedName + " " + st))
                                    .hoverEvent(HoverEvent.showText(Component.text("Set server type to " + st, NamedTextColor.GOLD))));
                }
                sender.sendMessage(serverLine);
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
        String label = resolvedName.equalsIgnoreCase("all") ? "ALL PLUGINS" : resolvedName;

        sender.sendMessage(Component.text("━━━[ ", NamedTextColor.GOLD)
                .append(Component.text("Tracking: " + label, NamedTextColor.AQUA))
                .append(Component.text(" ]━━━", NamedTextColor.GOLD)));

        Component channelLine = Component.text("  Channel: ", NamedTextColor.GRAY);
        for (String channel : Arrays.asList("release", "beta", "alpha", "all")) {
            boolean isSelected = allTracked ? channel.equals("all") : currentTracked.stream().anyMatch(c -> c.equalsIgnoreCase(channel));
            channelLine = channelLine.append(
                    Component.text("[" + channel.toUpperCase() + "] ", isSelected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/upd track " + resolvedName + " " + channel))
                            .hoverEvent(HoverEvent.showText(Component.text("Track " + channel + " versions", NamedTextColor.GOLD))));
        }
        sender.sendMessage(channelLine);

        String activeServerType = resolvedName.equalsIgnoreCase("all") ? configManager.getServerTypeOverride() : configManager.getPluginServerType(resolvedName);
        Component serverLine = Component.text("  Server:  ", NamedTextColor.GRAY);
        for (String st : Arrays.asList("auto", "paper", "spigot", "folia", "purpur")) {
            boolean isSelected = st.equalsIgnoreCase(activeServerType);
            serverLine = serverLine.append(
                    Component.text("[" + st.toUpperCase() + "] ", isSelected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/upd server " + resolvedName + " " + st))
                            .hoverEvent(HoverEvent.showText(Component.text("Set server type to " + st, NamedTextColor.GOLD))));
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
                                .clickEvent(ClickEvent.runCommand("/upd disable " + pName))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to stop tracking this plugin", NamedTextColor.YELLOW)));
                    } else {
                        btn = Component.text("[ENABLE]", NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.runCommand("/upd enable " + pName))
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
                        .clickEvent(ClickEvent.runCommand("/upd rollback " + pluginName + " " + f.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to stage restore", NamedTextColor.YELLOW)));
                tc = tc.append(btn);
            } else {
                tc = tc.append(Component.text("(Run: /upd rollback " + pluginName + " " + f.getName() + ")", NamedTextColor.DARK_GRAY));
            }
            sender.sendMessage(tc);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!configManager.hasPermission(sender)) return Collections.emptyList();
        String sub = args[0].toLowerCase();
        List<String> options = new ArrayList<>();

        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
        } else if (args.length == 2) {
            switch (sub) {
                case "update":
                    options.addAll(plugin.getPendingUpdates().keySet());
                    options.add("all");
                    break;
                case "list":
                    options.addAll(Arrays.asList("pending", "all", "enabled", "disabled"));
                    break;
                case "add":
                    options.add("<url>");
                    break;
                case "enable": {
                    ConfigurationSection pSec = plugin.getConfig().getConfigurationSection("plugins");
                    if (pSec != null) {
                        pSec.getKeys(false).stream().filter(k -> !pSec.getBoolean(k + ".enabled", true)).forEach(options::add);
                    }
                    break;
                }
                case "disable":
                case "info":
                case "source":
                case "redownload":
                case "rollback":
                    options.addAll(configManager.getEnabledPlugins());
                    break;
                case "track":
                case "server":
                    options.addAll(configManager.getEnabledPlugins());
                    options.add("all");
                    break;
                case "geyser":
                    options.addAll(Arrays.asList("enable", "disable", "download", "update"));
                    break;
            }
        } else if (args.length == 3) {
            switch (sub) {
                case "track":
                    options.addAll(CHANNELS);
                    break;
                case "server":
                    options.addAll(SERVER_TYPES);
                    break;
                case "source":
                    options.addAll(Arrays.asList("Modrinth", "Hangar", "Spigot", "GitHub", "Custom"));
                    break;
                case "rollback":
                    File[] files = new File(plugin.getDataFolder(), "backups")
                            .listFiles((dir, name) -> name.toLowerCase().startsWith(args[1].toLowerCase() + "-"));
                    if (files != null) {
                        Arrays.stream(files).map(File::getName).forEach(options::add);
                    }
                    break;
                case "geyser":
                    options.add("all");
                    options.addAll(GEYSER_ADDONS);
                    break;
            }
        } else if (args.length == 4 && sub.equals("source")) {
            String source = args[2].toUpperCase();
            if (SEARCHABLE_SOURCES.contains(source)) {
                options.addAll(Arrays.asList("<ID>", "<URL>"));
            } else if (source.equals("GITHUB")) {
                options.addAll(Arrays.asList("<owner>/<repo>", "<URL>"));
            } else if (source.equals("CUSTOM")) {
                options.add("<URL>");
            }
        }

        String prefix = args[args.length - 1].toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }
}
