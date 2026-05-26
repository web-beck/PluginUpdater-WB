# 🔄 PluginUpdater-WB

> **Automatic plugin update management for Paper/Spigot Minecraft servers.**
> Detects, downloads, and stages updates for all your plugins — supporting Modrinth, Hangar, GitHub Releases, and SpigotMC — with zero manual intervention.

---

## ✨ Features

- **Auto-detection** — Scans loaded plugins on startup and populates the config automatically
- **Multi-source support** — Modrinth, Hangar, GitHub Releases, SpigotMC, or custom download URLs
- **Smart version matching** — Strips build metadata, platform suffixes, and tag prefixes before comparing
- **Release channel control** — Track `release`, `beta`, `alpha`, or `all` per plugin or globally
- **Automatic backups** — Backs up the current jar before every update (keeps the 2 most recent)
- **Rollback system** — Browse and restore backups directly from in-game clickable menus
- **Geyser addon manager** — Built-in support for downloading/updating Geyser, Floodgate, and MCXboxBroadcast
- **Modrinth ID resolver** — Auto-resolves precise Modrinth project IDs on first scan
- **Per-plugin server type** — Set `paper`, `spigot`, `folia`, `purpur`, or `auto` globally or per-plugin
- **Interactive chat UI** — Clickable buttons for updating, toggling, rolling back, and tracking channels
- **Admin join notifications** — Alerts permitted players on login when updates are pending
- **Async everything** — All network calls and downloads run off the main thread

---

## 📋 Requirements

- **Java 11+**
- **Paper or Spigot** 1.19+ (uses Adventure API natively)
- The server `update` folder must be writable (standard Bukkit behavior)

---

## ⚙️ Installation

1. Drop `PluginUpdater-WB.jar` into your `plugins/` folder.
2. Start or restart your server.
3. The plugin will auto-generate `config.yml` and scan all loaded plugins.
4. Review `plugins/PluginUpdater-WB/config.yml` and adjust any project IDs that need fixing.
5. Run `/upd check` to perform your first update scan.

---

## 🔧 Configuration

```yaml
# Your server's Minecraft version (auto-detected if not set)
minecraft-version: "26.1.2"

# Default server type for Modrinth/Hangar loader filtering
# Options: auto, paper, spigot, folia, purpur
server-type-override: paper

# Players (by name) allowed to use the updater in addition to ops
allowed-players:
  - AdminName

# Geyser addon management (Geyser, Floodgate, MCXboxBroadcast)
geyser-addons:
  enabled: false
  Geyser: true
  Floodgate: true
  MCXboxBroadcast: true

# Below is where the plugin stores configuration for individual updates.
# Auto-populated on startup based on currently loaded plugins.
plugins:

  # Example – Modrinth plugin
  Modrinth-Example:
    enabled: true
    type: MODRINTH
    project-id: worldedit
    allowed-release-types:
      - release
    current-version: 7.3.0

  # Example – GitHub Releases plugin
  GitHub-Example:
    enabled: true
    type: GITHUB
    github-repo: AuthorName/RepoName
    allowed-release-types:
      - release
    current-version: 1.0.0

  # Example – Hangar plugin
  HangarPlugin-Example:
    enabled: true
    type: HANGAR
    project-id: AuthorName/ProjectName
    allowed-release-types:
      - release
    current-version: 1.0.0

  # Example – SpigotMC plugin
  SpigotPlugin-Example:
    enabled: true
    type: SPIGOT
    project-id: "12345"
    current-version: 1.0.0

  # Example – Custom direct-download URL
  CustomPlugin-Example:
    enabled: true
    type: CUSTOM
    custom-url: https://example.com/myplugin-latest.jar
    current-version: 1.0.0
```

### Plugin `type` values

| Type | Description |
|---|---|
| `MODRINTH` | Fetches from the Modrinth API. Uses `project-id` (slug or 8-char ID). |
| `GITHUB` | Fetches from GitHub Releases. Uses `github-repo` (`Author/Repo`). |
| `HANGAR` | Fetches from Hangar (PaperMC). Uses `project-id` (`Author/Slug`). |
| `SPIGOT` | Fetches from SpigotMC via Spiget. Uses the numeric resource `project-id`. |
| `CUSTOM` | Downloads directly from `custom-url`. Version checking is bypassed. |

---

## 🛠️ Commands

All commands are available as both `/updater` and `/upd`.

| Command | Description |
|---|---|
| `/upd help` | Shows the help menu |
| `/upd check` | Checks all enabled plugins for updates |
| `/upd run` | Downloads and stages all pending updates |
| `/upd run <plugin>` | Downloads and stages the update for a specific plugin |
| `/upd reload` | Reloads config and re-syncs the plugin list |
| `/upd list` | Lists all plugins with pending updates |
| `/upd list all` | Lists all enabled plugins with their update status |
| `/upd list versions` | Lists all plugins with their latest available version (bypasses channel filters) |
| `/upd list enabled` | Lists all currently enabled (tracked) plugins |
| `/upd list disabled` | Lists all currently disabled (untracked) plugins |
| `/upd plugin info <plugin>` | Shows version info across all channels for a plugin |
| `/upd plugin redownload <plugin>` | Force re-downloads the latest version of a plugin |
| `/upd plugin rollback <plugin>` | Opens the backup restoration menu for a plugin |
| `/upd plugin rollback <plugin> <file>` | Restores a specific backup file |
| `/upd plugin track <plugin\|all> <release\|beta\|alpha\|all>` | Sets the release channel for a plugin |
| `/upd plugin track server <type>` | Sets the global server type override |
| `/upd plugin track <plugin> server <type>` | Sets a per-plugin server type override |
| `/upd plugin id <plugin>` | Auto-resolves and locks the Modrinth project ID |
| `/upd plugin id <plugin> <Modrinth\|Hangar\|Spigot> <projectId>` | Manually sets the source and project ID |
| `/upd plugin geyser` | Opens the Geyser addon management menu |
| `/upd plugin geyser enable\|disable` | Enables or disables Geyser addon management |
| `/upd plugin geyser download <all\|Geyser\|Floodgate\|MCXboxBroadcast>` | Downloads missing Geyser addon jars |
| `/upd plugin geyser update <all\|Geyser\|Floodgate\|MCXboxBroadcast>` | Force-updates Geyser addon jars |

---

## 🔑 Permissions

| Permission | Description |
|---|---|
| `pluginupdater.admin` | Full access to all commands |
| Server op | Automatically granted full access |
| `allowed-players` list | Named players in config also receive full access |

---

## 🔁 How Updates Work

1. On startup (1 tick delayed), the plugin scans all loaded plugins and syncs `config.yml`.
2. An async update check runs against each enabled plugin's configured source.
3. Versions are compared after stripping platform tags (`-paper`, `-spigot`, etc.) and build metadata (`+build.123`).
4. Plugins with a newer version available are stored in memory as **pending updates**.
5. Running `/upd run` (or clicking `[CLICK TO UPDATE]` in chat) will:
   - Back up the currently running jar to `plugins/PluginUpdater-WB/backups/`
   - Download the new jar into the server's `plugins/update/` folder
   - Clean up old backups, keeping only the 2 most recent per plugin
6. The update takes effect on the **next server restart** (standard Bukkit update folder behavior).

---

## 💾 Geyser Addon Support

When enabled, PluginUpdater-WB can download and update **Geyser**, **Floodgate**, and **MCXboxBroadcast** independently of the standard plugin tracking system. This is useful because these projects use a custom build server rather than Modrinth or GitHub Releases.

- **Geyser** and **Floodgate** download from `download.geysermc.org`
- **MCXboxBroadcast** downloads from its GitHub Releases page
- MCXboxBroadcast is placed in `plugins/Geyser-Spigot/extensions/` automatically

Enable via: `/upd plugin geyser enable`

---

## 📁 File Structure

```
plugins/
├── PluginUpdater-WB.jar
├── PluginUpdater-WB/
│   ├── config.yml          ← Auto-managed plugin configuration
│   └── backups/            ← Jar backups before each update
│       └── PluginName-1.0.0.jar
└── update/                 ← Staged jars (applied on restart)
    └── PluginName-1.1.0.jar
```

---

## 🤝 Contributing

Pull requests and issue reports are welcome! If a plugin's project ID isn't being resolved correctly, use `/upd plugin id <plugin>` to trigger a manual Modrinth search, or set it explicitly with `/upd plugin id <plugin> Modrinth <id>`.

---

## 📜 License

This project is open source. See `LICENSE` for details.
