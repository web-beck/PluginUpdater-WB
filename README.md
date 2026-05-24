# 🔄 PluginUpdater-WB

> Automatically detect, download, and manage plugin updates for your Paper/Spigot Minecraft server — all from in-game or the console.

---

## ✨ Features

- **Auto-update detection** — Checks all tracked plugins on startup and alerts admins on join
- **Modrinth & GitHub support** — Pulls updates from both platforms automatically
- **Custom URL support** — Stage downloads from any direct `.jar` URL
- **Smart version matching** — Intelligently strips suffixes like `-paper`, `-release`, `v`, `+build` before comparing versions
- **Release channel control** — Track `release`, `beta`, `alpha`, or `all` per-plugin or globally
- **Server type override** — Set your loader globally (`paper`, `spigot`, `folia`, `purpur`, or `auto`)
- **Automatic backups** — Keeps the 2 most recent backups before overwriting a plugin
- **One-click rollback** — Restore a previous backup version with a clickable in-game button
- **Geyser addon management** — Download and update Geyser, Floodgate, and MCXboxBroadcast separately
- **Interactive chat UI** — Clickable buttons in chat for updates, toggling, tracking, and rollbacks
- **Action bar progress** — See which plugin is being checked in real time
- **Tab completion** — Full tab completion on all subcommands

---

## 📋 Requirements

- Paper (or Spigot/Folia/Purpur) **26.1+**
- Java **11+**

---

## 🚀 Installation

1. Download the latest release and place the `.jar` in your `/plugins/` folder
2. Start or restart your server — the plugin will auto-generate `config.yml` and detect all installed plugins
3. Review the config, set your `server-type-override` and `minecraft-version`, then use `/upd check`

---

## ⚙️ Configuration

```yaml
minecraft-version: "26.1.2"       # MC version used when querying Modrinth
server-type-override: "paper"     # Global loader type: auto, paper, spigot, folia, purpur
allowed-players:
  - "AdminName"                    # Non-op players allowed to use the updater

geyser-addons:
  enabled: false
  Geyser: true
  Floodgate: true
  MCXboxBroadcast: true

plugins:
  # Example entries (these won't be auto-removed):
  WorldEdit-Example:
    enabled: true
    type: MODRINTH               # MODRINTH | GITHUB | CUSTOM
    project-id: worldedit
    allowed-release-types:
      - release
    current-version: 7.3.1

  EssentialsX-Example:
    enabled: true
    type: GITHUB
    github-repo: EssentialsX/Essentials
    allowed-release-types:
      - release
    current-version: 2.20.1

  CustomPlugin-Example:
    enabled: true
    type: CUSTOM
    custom-url: "https://example.com/myplugin.jar"
    current-version: 1.0.0
```

All installed plugins are auto-detected and added to the `plugins` section. Uninstalled plugins are automatically removed on reload.

---

## 🛠️ Commands

All commands use `/updater` or the shorthand `/upd`.

| Command | Description |
|---|---|
| `/upd help` | Show the help menu |
| `/upd check` | Check all plugins for updates |
| `/upd run [plugin]` | Download all (or one) pending updates |
| `/upd reload` | Reload config and sync installed plugins |
| `/upd list` | List pending updates |
| `/upd list all` | List all tracked plugins with status |
| `/upd list versions` | Show current vs latest versions |
| `/upd list enabled` | Show all enabled plugins |
| `/upd list disabled` | Show all disabled plugins |
| `/upd plugin info <name>` | Show version & channel info for a plugin |
| `/upd plugin track <name\|all> <release\|beta\|alpha\|all>` | Set update channel |
| `/upd plugin track server <type>` | Set global server type override |
| `/upd plugin redownload <name>` | Force a fresh download of a plugin |
| `/upd plugin rollback <name> [file]` | Open the backup restore menu |
| `/upd plugin geyser` | Manage Geyser addons |
| `/upd plugin geyser download <all\|Geyser\|Floodgate\|MCXboxBroadcast>` | Download missing addons |
| `/upd plugin geyser update <all\|Geyser\|Floodgate\|MCXboxBroadcast>` | Force-update addons |

---

## 🔐 Permissions

| Permission | Description |
|---|---|
| `pluginupdater.admin` | Full access to all commands |
| OP | Grants full access by default |
| `allowed-players` list | Non-op players listed in config also get access |

---

## 🔁 How Updates Work

1. On startup (1-tick delay), all tracked plugins are checked asynchronously
2. Detected updates are stored in memory as **pending**
3. Use `/upd list` to see pending updates with clickable **[CLICK TO UPDATE]** buttons
4. Clicking (or running `/upd run`) downloads the new `.jar` to the server's `/update/` folder and backs up the old one
5. **Restart the server** to apply the staged update

---

## 🎮 Geyser Addon Support

Enable Geyser support in `config.yml` to manage **Geyser**, **Floodgate**, and **MCXboxBroadcast** independently from Modrinth tracking. These are downloaded directly from GeyserMC's build servers and GitHub releases.

```
/upd plugin geyser              → Opens the Geyser management panel
/upd plugin geyser download all → Download any missing addon jars
/upd plugin geyser update all   → Force-update all addon jars
```

---

## 📁 File Structure

```
plugins/
├── PluginUpdater-WB/
│   ├── config.yml          ← Main config (auto-managed)
│   └── backups/            ← Previous plugin versions (max 2 per plugin)
└── update/                 ← Staged updates (applied on next restart)
```

---

## 🤝 Contributing

Pull requests and issues are welcome! If a plugin isn't being detected correctly, check that the `project-id` (Modrinth) or `github-repo` field in config matches the correct identifier.

---

## 📄 License

This project is open source. See [LICENSE](LICENSE) for details.
