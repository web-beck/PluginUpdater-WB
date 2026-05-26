# 🔄 PluginUpdater-WB

PluginUpdater-WB is a next-generation plugin manager designed specifically for modern Paper servers. It completely automates the process of checking, tracking, downloading, and backing up your server's plugins.

Built with native `java.net.http.HttpClient` and `CompletableFuture`, it operates 100% asynchronously. Your main server thread will never freeze or lag, no matter how many plugins you are checking or downloading.

---

## ✨ Key Features

### ⚡ Fully Asynchronous

Zero impact on server performance. All HTTP requests, API parsing, and file downloading happen in the background.

### 🧠 Smart Config Syncing & Auto-Cleanup

Drop it into your server and restart. The plugin will automatically detect every installed plugin and populate the `config.yml` for you. If you delete a plugin `.jar`, it automatically cleans it from the config!

### 🔄 Multi-Platform Support

Native API integration with Modrinth and GitHub Releases. Also supports raw Custom URLs for direct downloads.

### 🖥️ Server-Type Awareness

Accurately downloads the correct `.jar` for your specific server loader:

- Paper
- Spigot
- Purpur
- Folia
- Bukkit

### 🧰 Dedicated Geyser Manager

Native, built-in management for updating and tracking:

- Geyser-Spigot
- Floodgate
- MCXboxBroadcast

The auto-scanner smartly ignores these so you can manage them in their own dedicated interactive hub!

### 🛡️ Automated Backups & Rollbacks

Before downloading an update, the plugin automatically copies your current `.jar` into a `backups/` folder, keeping only the 2 most recent backups to save space.

Revert updates instantly with:

`/upd plugin rollback`

### 🎛️ Granular Tracking

By default, the plugin safely tracks only `Release` builds.

Use the interactive menu to track the following channels on a per-plugin basis or globally:

- Alpha
- Beta
- Release
- All

### 🖱️ Interactive Chat UI

Built with Kyori Adventure! Almost every command features rich, clickable buttons and hover-text for a seamless admin experience.

---

## 🛠️ Commands

**Alias:** `/updater` or `/upd`

**Permission:** `pluginupdater.admin`

Defaults to OP. You can also whitelist specific usernames in the `config.yml`.

---

## 🟢 Core Commands

| Command | Description |
|---|---|
| `/upd help` | Shows the interactive help menu. |
| `/upd check` | Manually triggers the async update checker. Admins get notified automatically on join if updates are found. |
| `/upd run [PluginName]` | Downloads all pending updates to the `update/` folder, applied on next restart. Provide a plugin name to only update that specific plugin. |
| `/upd reload` | Reloads the `config.yml` and synchronizes the list of loaded plugins. |

---

## 📋 List Commands

| Command | Description |
|---|---|
| `/upd list` | Shows a clean list of only the plugins that currently have pending updates. Includes a clickable `[CLICK TO UPDATE]` button. |
| `/upd list all` | Displays the status of every tracked plugin, including its source, server type, and current version. |
| `/upd list versions` | Bypasses your channel filters, like release-only, to fetch the absolute newest physical releases available on the APIs. |
| `/upd list enabled` | Shows all currently enabled plugins with a clickable `[DISABLE]` button. |
| `/upd list disabled` | Shows all currently disabled plugins with a clickable `[ENABLE]` button. |

---

## 🔧 Plugin Management Commands

| Command | Description |
|---|---|
| `/upd plugin info <PluginName>` | Displays an interactive menu showing the current version, downloaded server loader type, tracked channels, and the latest versions across all channels. |
| `/upd plugin track <PluginName\|all>` | Opens an interactive menu to adjust tracking channels and server-loader overrides for a plugin. You can also supply arguments directly, such as `/upd plugin track all beta`. |
| `/upd plugin track server <type>` | Sets the global server-type override. Options include `auto`, `paper`, `spigot`, `purpur`, and `folia`. |
| `/upd plugin redownload <PluginName>` | Bypasses the version checker and forces a fresh download of the latest tracked `.jar` file. |
| `/upd plugin rollback <PluginName> [FileName]` | Opens an interactive menu of recent backups for that plugin. Click `[RESTORE]` to stage the old jar. |

---

## 🧰 Geyser & Addons Commands

> **Note:** Geyser support must be turned on via `/upd plugin geyser enable` or in the `config.yml`.

| Command | Description |
|---|---|
| `/upd plugin geyser list` | Opens the Geyser management hub. View missing addons, toggle tracking, and manually update individual jars. |
| `/upd plugin geyser download all` | Automatically downloads any missing Geyser, Floodgate, or MCXboxBroadcast jars to their correct folders. |
| `/upd plugin geyser update all` | Force-downloads the absolute latest versions of all enabled Geyser addons. |

---

## ⚙️ Configuration Setup

You barely have to configure anything!

1. Place the `PluginUpdater-WB.jar` in your `plugins/` folder.
2. Start the server.
3. Open `plugins/PluginUpdater-WB/config.yml`.
4. The plugin has automatically added every plugin on your server to the config under `# Scanned Plugins #`.
5. By default, it assumes scanned plugins are on Modrinth.
6. If a plugin is on GitHub, change `type` to `GITHUB` and paste the `github-repo`, such as `EssentialsX/Essentials`.
7. Type `/upd reload` in-game.

---

## Example Config Block

```yaml
# ========================================== #
#            PluginUpdater-WB                #
# ========================================== #

# The Minecraft version this server is running. Used to query correct plugin updates.
# If left blank or missing, the plugin will attempt to auto-detect it.
minecraft-version: 26.1.2

# List of usernames allowed to use the commands even if they do not have OP or the pluginupdater.admin permission.
allowed-players:
- Username1
- Username2

# Overrides the loader type sent to Modrinth.
# Defaults to "paper". Can be set to "auto" to detect if you are running paper/spigot/etc.
# Valid options: "auto", "paper", "purpur", "folia", "spigot", "bukkit"
server-type-override: paper

# ========================================== #
#              Geyser Addons                 #
# ========================================== #
# Manages direct downloads for Geyser, Floodgate, and MCXboxBroadcast.
# Turn enabled to 'true' to manage them via '/upd plugin geyser'
geyser-addons:
  enabled: true
  Geyser: true
  Floodgate: true
  MCXboxBroadcast: true

# Below is where the plugin stores configuration for individual updates.
# The plugin will automatically populate this section on startup based on the plugins currently loaded on your server.
plugins:
  
  # Example of a Modrinth plugin
  WorldEdit-Example:
    enabled: false
    type: MODRINTH
    project-id: worldedit
    allowed-release-types:
    - release
    current-version: 7.2.15
  
  # Example of a GitHub plugin
  Essentials-Example:
    enabled: false
    type: GITHUB
    github-repo: EssentialsX/Essentials
    allowed-release-types:
    - release
    current-version: 2.20.1
  
  # Example of a Custom URL (Bypasses checking logic, will simply download the jar when '/upd run CustomPlugin' is used)
  CustomPlugin-Example:
    enabled: false
    type: CUSTOM
    custom-url: https://example.com/downloads/CustomPlugin-latest.jar
    current-version: 1.0.0
    allowed-release-types:
    - release
  
  # ========================================== #
  #              Scanned Plugins               #
  # ========================================== #
```

---

## ⚠️ Requirements

- Java 17 or higher
- PaperMC, or forks like Purpur and Folia, version 1.20+

> **Note:** This plugin utilizes Paper's native Kyori Adventure API and will not work on legacy Spigot.
