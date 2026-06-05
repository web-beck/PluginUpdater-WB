# PluginUpdater-WB 🔄

> Highly advanced, fully asynchronous plugin manager and updater for Paper servers.

**Version:** `26.1.2` &nbsp;|&nbsp; **API Version:** `26.1` &nbsp;|&nbsp; **Java:** 17+ &nbsp;|&nbsp; **Author:** WebBeck

---

## 📖 What It Does

PluginUpdater-WB automates keeping your Paper server's plugins up to date. It checks for new versions across multiple plugin repositories—Modrinth, Hangar, GitHub Releases, and SpigotMC—then downloads and installs updates asynchronously so your server stays responsive. It also supports direct custom download URLs and manages Geyser-ecosystem addons (Geyser, Floodgate, MCXboxBroadcast) natively.

## ✨ Features

- **Multi-source update checking** — Modrinth, Hangar, GitHub Releases, SpigotMC, and custom URLs
- **Fully asynchronous** — all network and I/O operations run off the main thread
- **Per-plugin configuration** — track `release`, `beta`, `alpha`, or `all` channels per plugin
- **Auto-detection** — scans loaded plugins on startup and populates config automatically
- **Geyser addon management** — first-class support for Geyser, Floodgate, and MCXboxBroadcast
- **Backup support** — backs up plugin jars before overwriting
- **Fine-grained permissions** — `pluginupdater.admin` permission node plus an allowlist for specific players
- **Server-type awareness** — supports Paper, Purpur, Folia, Spigot, and Bukkit loader targets

---

## 📋 Requirements

- Paper (or compatible fork) running **API version 26.1**
- Java **17** or newer

---

## 🚀 Installation

1. Download `PluginUpdater-WB-26.1.2-1.0.2.jar` and drop it into your server's `plugins/` folder.
2. Start (or restart) your server. The plugin will generate `config.yml` and auto-populate it with entries for every plugin currently loaded.
3. Edit `config.yml` to enable or tune tracking for each plugin (see [Configuration](#%EF%B8%8F-configuration) below).
4. Run `/upd reload` to apply changes without restarting.

---

## ⚙️ Configuration

`config.yml` is generated on first run. Key options:

```yaml
# Minecraft version used for Modrinth queries. Leave blank to auto-detect.
minecraft-version: '26.1.2'

# Players allowed to run commands without OP or the permission node.
allowed-players:
  - 'YourUsernameHere'

# Loader type sent to Modrinth. "auto" detects Paper/Spigot/etc at runtime.
# Valid: "auto", "paper", "purpur", "folia", "spigot", "bukkit"
server-type-override: 'paper'

# Global release channel for all plugins.
# Valid: "release", "beta", "alpha", "all"
tracking-type: 'all'

# Geyser addon management (Geyser, Floodgate, MCXboxBroadcast)
geyser-addons:
  enabled: false
  Geyser: true
  Floodgate: true
  MCXboxBroadcast: true
```

### Plugin Entries

Each plugin under the `plugins:` key supports one of five source types:

```yaml
plugins:

  # Modrinth
  MyPlugin:
    enabled: true
    type: MODRINTH
    project-id: osLrA9oB
    allowed-release-types:
      - release
    current-version: 1.0.0

  # GitHub Releases
  AnotherPlugin:
    enabled: true
    type: GITHUB
    github-repo: owner/repository
    allowed-release-types:
      - release
    current-version: 2.0.0

  # Hangar (PaperMC)
  HangarPlugin:
    enabled: true
    type: HANGAR
    project-id: ViaVersion
    allowed-release-types:
      - release
    current-version: 5.0.0

  # SpigotMC (numeric resource ID from the URL)
  SpigotPlugin:
    enabled: true
    type: SPIGOT
    project-id: '19254'
    allowed-release-types:
      - release
    current-version: 1.0.0

  # Custom URL (always downloads; skips version-check logic)
  CustomPlugin:
    enabled: true
    type: CUSTOM
    custom-url: 'https://example.com/downloads/CustomPlugin-latest.jar'
    current-version: 1.0.0
```

A reference file of common plugin templates is included as `common-plugins.yml` inside the jar.

---

## 💬 Commands

The main command is `/updater` (alias: `/upd`). Requires the `pluginupdater.admin` permission or OP (configurable).

| Command | Description |
|---|---|
| `/upd help` | Show all available subcommands |
| `/upd -v` | Display the plugin version |
| `/upd check` | Run an async update check across all enabled plugins |
| `/upd run` | Download and install all available updates |
| `/upd list` | List all tracked plugins and their current status |
| `/upd reload` | Reload `config.yml` without restarting the server |
| `/upd plugin <name>` | Check, update, or manage a specific plugin |
| `/upd plugin geyser` | Manage Geyser ecosystem addons (requires `geyser-addons.enabled: true`) |

---

## 🔒 Permissions

| Node | Description | Default |
|---|---|---|
| `pluginupdater.admin` | Full access to all updater commands | OP |

Non-OP players can also be granted access by adding their username to `allowed-players` in `config.yml`.

---

## 🆘 Support

- **Issues & bug reports:** Open an issue on the project repository
- **GitHub page:** [PluginUpdater-WB](https://github.com/web-beck/PluginUpdater-WB)

---

## 🤝 Contributing

Pull requests are welcome. Please open an issue first to discuss any significant changes. Make sure new features include appropriate config defaults and that all commands continue to work asynchronously.

---

## 👤 Maintainer

[**WebBeck**](www.webbeck.org)

See the `LICENSE` file for license terms.
