# NeoForbric

[中文说明](README.md)

An automatic mod downloader and replacement tool for Minecraft **1.21.1 / NeoForge 21.1.x**. It looks for NeoForge builds of Forge and Fabric mods and installs them into the current instance when possible.

The in-game interface uses “translation” as a presentation theme. The actual work is querying repositories, downloading files, verifying their metadata, replacing files safely, and installing separate compatibility components when they are required.

## What it does

- **Forge mods:** looks for a NeoForge build for the current Minecraft version.
- **Fabric mods:** looks for a port or a matching NeoForge build; when a Fabric mod must run through a compatibility layer, it can download Sinytra Connector and Forgified Fabric API.
- **Port mappings:** lets you pin a source mod to a project on Modrinth or CurseForge.
- **Required dependencies:** can install missing required dependencies for newly installed or replaced mods. Optional dependencies are not installed automatically.
- **File protection:** backs up replaced files by default, refuses to overwrite a different file with the same name, and keeps recovery records when an operation is interrupted.

NeoForbric is not a universal bytecode translator and cannot make every Forge or Fabric mod work on NeoForge. If no suitable build or compatibility support is available, the original file is kept and NeoForge reports the remaining problem.

## Requirements and scope

- Minecraft **1.21.1**
- NeoForge **21.1.x**; client verification used **21.1.256**
- A network connection for repository queries and downloads
- Optional [Cloth Config](https://modrinth.com/mod/cloth-config) for the in-game configuration screen

The project is currently a client-focused tool. See [Known limitations](docs/KNOWN-LIMITATIONS.md) before using it in a large modpack or on a dedicated server.

## Installation

1. Prepare a Minecraft 1.21.1 / NeoForge 21.1.x instance.
2. Exit the game and back up the instance's `mods` folder.
3. Download `neoforbric-0.0.8.jar` from the GitHub Release and place it in `mods`.
4. Start the game. Repository queries and downloads require network access.

When Connector or Forgified Fabric API is installed or enabled for the first time, the game must restart once so NeoForge can discover the new service-layer component. NeoForbric normally starts that restart automatically; if the launcher does not allow it, follow the in-game message and restart manually.

The GitHub-generated **Source code** archive is source code, not a mod. Do not put it in `mods`.

## Configuration

With Cloth Config installed, open **Mods → NeoForbric → Configure**. The pages cover general behavior, automatic restart, translation and dependencies, downloads and network, port mappings, the skip list, and advanced settings.

All configuration changes take effect after saving and restarting the game. Without Cloth Config, edit these files directly:

| File | Purpose |
| --- | --- |
| `config/neoforbric/config.properties` | Main switches, sources, network, and restart settings |
| `config/neoforbric/ports.properties` | Source mod IDs and preferred port projects |
| `config/neoforbric/blacklist.properties` | Mod IDs that NeoForbric must leave untouched |

The skip list takes priority over port mappings and automatic crawling. It does not prevent Connector or Forgified Fabric API from being installed when another Fabric mod needs them.

## Files and logs

| Path | Purpose |
| --- | --- |
| `mods/.neoforbric/backup/` | Long-term backups of replaced or removed originals |
| `mods/.neoforbric/rollback/` | Temporary originals kept during an installation |
| `mods/.neoforbric/journal/` | Recovery records for unfinished file operations |
| `logs/neoforbric.log` | Detailed decisions, downloads, replacements, and recovery |
| `logs/neoforbric-report.json` | Machine-readable report for the current run |

Sinytra Connector keeps its own conversion cache. In the tested setup it uses `.cache/connector/`; that cache is separate from NeoForbric's backups and is not bundled into this project.

## Current version

The current release is **0.0.8**. See [CHANGELOG.md](CHANGELOG.md) for the version history and the GitHub Release for the downloadable jar and SHA-256 value.

## Verification and limits

The 0.0.8 candidate passed the existing offline checks (170), fix probes (170), and engine probes (141); two independent builds produced the same jar. The published documentation also records the eight real-instance scenarios and their raw logs.

The project has not completed dedicated-server testing, verification with a real CurseForge API key, large modpack performance testing, or every possible operating-system failure mode. A multi-mod jar may contain unique mod IDs; when duplicate cleanup removes that jar, inspect the report and the backup before deleting anything manually.

See [Known limitations](docs/KNOWN-LIMITATIONS.md) for the full list. Do not describe NeoForbric as a guaranteed universal loader.

## Build from source

You need **JDK 21** and network access for build dependencies. The repository includes Gradle Wrapper 8.14.2.

Windows PowerShell:

```powershell
.\gradlew.bat build
```

Linux / macOS:

```bash
chmod +x gradlew
./gradlew build
```

The artifact is `build/libs/neoforbric-0.0.8.jar`. See [Build and project structure](docs/BUILD.md) for the two source sets and runtime layout.

## Reporting a problem

When opening an issue, include the Minecraft, NeoForge, and NeoForbric versions, reproduction steps, the affected mod versions, `neoforbric.log`, and `neoforbric-report.json`. Add `latest.log` or a crash report when relevant.

Remove personal paths, private server addresses, and API keys before uploading logs. Never publish a CurseForge API key.

## License and third-party components

NeoForbric is released under the [MIT License](LICENSE). The project is independent from Mojang, NeoForged, Sinytra, Modrinth, and CurseForge.

Sinytra Connector and Forgified Fabric API are not bundled in this jar. When needed, NeoForbric downloads the corresponding upstream files at runtime; those components remain under their own licenses.
