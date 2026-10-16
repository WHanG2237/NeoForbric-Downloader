# ModRelay

[简体中文](README.md) · [Download](https://github.com/WHanG2237/NeoForbric-Downloader/releases) · [Changelog](CHANGELOG.en.md)

**Installed a mod for the wrong loader? Let ModRelay look for a compatible build.**

ModRelay, formerly NeoForbric, is a mod download and replacement tool for **Minecraft 1.21.1 / NeoForge 21.1.x**. It detects Forge and Fabric mods in your instance, looks for suitable NeoForge builds, and downloads and installs the replacements.

The in-game interface calls this process “translation.” Underneath, it performs lookups, downloads, verification, backups, and file replacement. For mods that still need a Fabric environment, it can install [Sinytra Connector](https://github.com/Sinytra/Connector) and [Forgified Fabric API](https://github.com/Sinytra/ForgifiedFabricAPI) as needed. It does not translate bytecode or guarantee that every combination of mods will work.

## Features

- Find NeoForge builds for the current Minecraft version, with a mapping file for specifying ported projects.
- Install missing required dependencies for newly installed mods.
- Back up replaced originals by default and refuse to overwrite a different file with the same name.
- Handle confirmed duplicates while keeping unresolved files and recording the reason.
- Show startup and per-file download progress, with download results, average speeds, and a processing report.
- Configure the mod in-game, including optional concurrent file downloads and segmented downloads within a file.

A replacement may have a different version number from the original mod. If no suitable build is found or replacement fails, the original is kept; NeoForge may still be unable to load it.

## Installation and upgrades

1. Prepare a Minecraft 1.21.1 / NeoForge 21.1.x instance.
2. Exit the game and back up the instance's `mods/` folder.
3. Download `neoforbric-<version>.jar` from [Releases](https://github.com/WHanG2237/NeoForbric-Downloader/releases) and place it in `mods/`.
4. Start the game. Initial lookups and downloads require an internet connection.

When upgrading, remove the old NeoForbric / ModRelay JAR to avoid duplicate loading. File names and configuration paths still use `neoforbric`, so existing settings and backups remain usable. GitHub's Source code archives and source ZIPs are for building the project; do not put them in `mods/`.

**Installing or enabling Connector / FFAPI for the first time still requires a restart.** ModRelay attempts to restart automatically. If it cannot, follow the prompt to restart manually.

## Configuration and logs

Optionally install [Cloth Config](https://modrinth.com/mod/cloth-config) to open the settings through Mods → ModRelay → Config. Restart after changing settings. You can also edit the files directly:

| Path | Purpose |
| --- | --- |
| `config/neoforbric/config.properties` | Download sources, concurrency, restart behavior, and other settings |
| `config/neoforbric/ports.properties` | Mappings between mods and ported projects |
| `config/neoforbric/blacklist.properties` | Skip list, identifying mods by modid |
| `logs/neoforbric.log` | Lookup, download, and file operation logs |
| `logs/neoforbric-report.json` | Processing report for the current startup |
| `mods/.neoforbric/backup/` | Originals backed up during replacement and duplicate cleanup |

By default, one file is processed at a time and segmented downloads are disabled. You can adjust concurrency or enable segments to suit your connection, but more connections do not necessarily mean faster downloads. Speed depends on the network, server, and available bandwidth.

To restore a backup manually, exit the game first and check the files against the log. Do not delete backups, temporary files, or recovery records under `mods/.neoforbric/` while the game is running.

## Download sources and compatibility

The default source is the **official Modrinth API**. ModRelay queries projects and versions, then fetches files from the returned download addresses. Mods available only on other platforms may require manual installation. CurseForge access is not provided.

Third-party Modrinth mirrors are disabled by default. This project's registration and access scope for the built-in MCIM endpoint have not been confirmed. The User-Agent identifies ModRelay; it does not grant service authorization.

ModRelay is available through [GitHub Releases](https://github.com/WHanG2237/NeoForbric-Downloader/releases). Connector, FFAPI, and other mods are downloaded separately rather than bundled with ModRelay. Their compatibility, copyright, and licenses remain with their respective projects.

This is an experimental project. Replacing loader-specific builds cannot resolve every dependency in a modpack, so try it in a copy of your instance first. See [Known limitations](docs/KNOWN-LIMITATIONS.md) for more details.

## Feedback and source

Report problems through [Issues](https://github.com/WHanG2237/NeoForbric-Downloader/issues). Include the Minecraft / NeoForge versions, affected mods, reproduction steps, and relevant logs. Check for tokens and other sensitive information before uploading logs.

- [Build from source](docs/BUILD.md)
- [English changelog](CHANGELOG.en.md) · [中文更新日志](CHANGELOG.md)

## Author and AI contribution

The maintainer supplied the concept, made feature decisions, and tested the user experience. DeepSeek produced most of the code, scripts, tests, and documentation, with Codex contributing reviews and some changes. “蓝色大肥鱼” is DeepSeek's project signature.

Thanks to NeoForge, Sinytra Connector, Forgified Fabric API, Modrinth, and the mod authors.

## License

Project code is available under the [MIT License](LICENSE). The Gradle Wrapper distributed with the source uses Apache-2.0; see [Third-party notices](THIRD-PARTY.en.md). The icon's permission status and attribution are documented in [ICON-LICENSE.md](ICON-LICENSE.md); the code's MIT license does not apply to it. Mods downloaded at runtime retain their own licenses.
