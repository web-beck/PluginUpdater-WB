PluginUpdater-WB is a next-generation plugin manager designed specifically for modern Paper servers. It completely automates the process of checking, tracking, downloading, and backing up your server's plugins.

Built with native java.net.http.HttpClient and CompletableFuture, it operates 100% asynchronously. Your main server thread will never freeze or lag, no matter how many plugins you are checking or downloading.

# ✨ Key Features

⚡ Fully Asynchronous: Zero impact on server performance. All HTTP requests, API parsing, and file downloading happen in the background.

🧠 Smart Config Syncing: Drop it into your server and restart. The plugin will automatically detect every installed plugin and populate the config.yml for you. If you delete a plugin jar, it automatically cleans it from the config!

🔄 Multi-Platform Support: Native API integration with Modrinth and GitHub Releases. Also supports raw Custom URLs for direct downloads.

🛡️ Automated Backups & Rollbacks: Before downloading an update, the plugin automatically copies your current .jar into a backups/ folder. It smartly keeps only the 2 most recent backups to save disk space. Need to revert? Use the interactive in-game /upd rollback menu!

🎛️ Release Channels (Alpha/Beta/Release): Want Beta updates for WorldEdit but only Stable releases for Essentials? You can independently set the tracking channel for every plugin using an interactive in-game UI.

📦 Dependency Awareness: When checking Modrinth, it detects required dependencies and warns you in chat before you apply an update.

🖱️ Interactive Chat UI: Built with Kyori Adventure! Almost every command features rich, clickable buttons and hover-text for a seamless admin experience.

# 🛠️ Commands

Alias: /updater or /upd

Permission: pluginupdater.admin (Defaults to OP. You can also whitelist specific usernames in the config.yml to bypass permissions).

Command

Description

/upd check - Manually triggers the async update checker. Admins get notified automatically on join if updates are found.

/upd list [all|v] - Shows all plugins with pending updates. Includes a clickable [CLICK TO UPDATE] button. Add all to see the status of every plugin, or v to fetch the absolute latest physical releases (bypassing your channel filters).

/upd apply [PluginName] - Downloads all pending updates to the update/ folder (applied on next restart). Provide a plugin name to only update that specific plugin.

/upd info <PluginName> - Displays an interactive menu showing the absolute latest version available for the plugin across all channels (Release, Beta, Alpha). Includes clickable buttons to easily change your tracking type!

/upd settype <PluginName> <type> - Manually sets the release channel you want to track for a plugin. Valid types: release, beta, alpha, or all.

/upd rollback <PluginName> [FileName] - Opens an interactive menu of recent backups for that plugin. Click [RESTORE] to automatically stage the old jar in the update folder for the next restart.

/upd redownload <PluginName> - Bypasses the version checker and forces a fresh download of the latest tracked .jar file.

/upd reload - Reloads the config.yml and synchronizes the list of loaded plugins.

# ⚙️ Configuration Setup

You barely have to configure anything!

Place the PluginUpdater-WB.jar in your plugins/ folder.

Start the server.

Open plugins/PluginUpdater-WB/config.yml. You will see that the plugin has automatically added every plugin on your server to the config!

By default, it assumes plugins are on Modrinth. If a plugin is on GitHub, simply change the type to GITHUB and paste the github-repo (e.g., EssentialsX/Essentials).

Type /upd reload in-game, and you are ready to go!

Example Config Block

plugins:
   A standard Modrinth setup (Auto-generated!)
  WorldEdit:
    enabled: true
    type: MODRINTH
    project-id: worldedit
    allowed-release-types:
      - release
      - beta
    current-version: 7.2.15

   A GitHub setup
  Essentials:
    enabled: true
    type: GITHUB
    github-repo: EssentialsX/Essentials
    allowed-release-types:
      - release
    current-version: 2.20.1


⚠️ Requirements

Java 17 or higher (Uses Java's modern HttpClient).

PaperMC (or forks like Purpur, Folia) version 1.20+.
(Note: This plugin relies on the native Kyori Adventure chat API provided by Paper and will not work on legacy Spigot).
