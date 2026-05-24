# PluginUpdater-WB

PluginUpdater-WB is a next-generation plugin manager designed specifically for modern Paper servers. It completely automates the process of checking, tracking, downloading, and backing up your server's plugins.

Built with native `java.net.http.HttpClient` and `CompletableFuture`, it operates 100% asynchronously. Your main server thread will never freeze or lag, no matter how many plugins you are checking or downloading.

---

## ✨ Key Features

- **⚡ Fully Asynchronous**  
  Zero impact on server performance. All HTTP requests, API parsing, and file downloading happen in the background.

- **🧠 Smart Config Syncing & Auto-Cleanup**  
  Drop it into your server and restart. The plugin will automatically detect every installed plugin and populate the `config.yml` for you. If you delete a plugin `.jar` from your server, it automatically cleans it from the config.

- **🔄 Multi-Platform Support**  
  Native API integration with Modrinth and GitHub Releases. Also supports raw Custom URLs for direct downloads.

- **🛡️ Automated Backups & Rollbacks**  
  Before downloading an update, the plugin automatically copies your current `.jar` into a `backups/` folder. It smartly keeps only the 2 most recent backups to save disk space. Need to revert? Use the interactive in-game `/upd plugin rollback` menu.

- **🎛️ Release Channels: Alpha, Beta, Release**  
  By default, the plugin safely tracks only `Release` builds. Want Beta updates for WorldEdit but stable releases for Essentials? You can independently set the tracking channel for every plugin using an interactive in-game UI, or track `All` channels simultaneously.

- **📦 Dependency Awareness**  
  When checking Modrinth, it detects required dependencies and warns you in chat before you apply an update.

- **🖱️ Interactive Chat UI**  
  Built with Kyori Adventure. Almost every command features rich, clickable buttons and hover text for a seamless admin experience.

---

## 🛠️ Commands

**Alias:** `/updater` or `/upd`

**Permission:** `pluginupdater.admin`

Defaults to OP. You can also whitelist specific usernames in the `config.yml` to bypass permissions.

---

## 🟢 Core Commands

| Command | Description |
|---|---|
| `/upd help` | Shows the interactive help menu. |
| `/upd check` | Manually triggers the async update checker. Admins get notified automatically on join if updates are found. |
| `/upd apply [PluginName]` | Downloads all pending updates to the `update/` folder. Updates are applied on the next restart. Provide a plugin name to only update that specific plugin. |
| `/upd reload` | Reloads the `config.yml` and synchronizes the list of loaded plugins. |

---

## 🔧 Plugin Management Commands

| Command | Description |
|---|---|
| `/upd plugin list [all\|v]` | Shows all plugins with pending updates. Includes a clickable `[CLICK TO UPDATE]` button. Add `all` to see the status of every plugin, or `v` to fetch the absolute latest physical releases, bypassing your channel filters. |
| `/upd plugin info <PluginName>` | Displays an interactive menu showing the absolute latest version available for the plugin across all channels: Release, Beta, and Alpha. Includes clickable buttons to easily change your tracking type. |
| `/upd plugin settype <PluginName> <type>` | Manually sets the release channel you want to track for a plugin. Valid types: `release`, `beta`, `alpha`, or `all`. |
| `/upd plugin redownload <PluginName>` | Bypasses the version checker and forces a fresh download of the latest tracked `.jar` file. |
| `/upd plugin rollback <PluginName> [FileName]` | Opens an interactive menu of recent backups for that plugin. Click `[RESTORE]` to automatically stage the old `.jar` in the update folder for the next restart. |

---

## ⚙️ Configuration Setup

You barely have to configure anything.

1. Place the `PluginUpdater-WB.jar` file in your `plugins/` folder.
2. Start the server.
3. Open this file:

   ```text
   plugins/PluginUpdater-WB/config.yml
   ```

4. You will see that the plugin has automatically added every plugin on your server to the config under:

   ```text
   # Scanned Plugins #
   ```

5. By default, it assumes scanned plugins are on Modrinth.
6. If a plugin is on GitHub, change the type to `GITHUB` and paste the `github-repo`.

   Example:

   ```yml
   github-repo: EssentialsX/Essentials
   ```

7. Type this in-game:

   ```text
   /upd reload
   ```

8. You are ready to go.

---

## Example Config Block

```yml
plugins:

  # Example of a Modrinth plugin
  WorldEdit:
    enabled: false
    type: MODRINTH
    project-id: worldedit
    allowed-release-types:
      - release
      - beta
    current-version: 7.2.15

  # Example of a GitHub plugin
  Essentials:
    enabled: false
    type: GITHUB
    github-repo: EssentialsX/Essentials
    allowed-release-types:
      - release
    current-version: 2.20.1

  # ========================================== #
  #              Scanned Plugins               #
  # ========================================== #

  # Auto-generated entry
  SomeCoolPlugin:
    enabled: true
    type: MODRINTH
    project-id: somecoolplugin
    allowed-release-types:
      - release
    current-version: 1.0.5
```

---

## ⚠️ Requirements

- **Java 17 or higher**  
  Uses Java's modern `HttpClient`.

- **PaperMC or forks like Purpur/Folia version 1.20+**

> **Note:** This plugin relies on the native Kyori Adventure chat API provided by Paper and will not work on legacy Spigot.

---

README.md
```
