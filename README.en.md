# NeoForbric

[中文说明](README.md)

An automatic mod downloader and replacement tool for Minecraft **1.21.1 / NeoForge 21.1.x**. It identifies Forge and Fabric mods, looks for suitable NeoForge builds, and downloads and installs them when available.

The in-game interface presents this process as “translation.” The implementation queries repositories, downloads files, verifies them, and replaces files with backups. Sinytra Connector and Forgified Fabric API are downloaded separately when compatibility support is needed. **It cannot make every Forge or Fabric mod work on NeoForge.**

## Installation

1. Prepare a Minecraft 1.21.1 / NeoForge 21.1.x instance. Client checks use NeoForge 21.1.256.
2. Exit the game and back up the instance's `mods/` folder.
3. Download `neoforbric-0.0.9.jar` from [Releases](https://github.com/WHanG2237/NeoForbric-Downloader/releases) and put it in `mods/`. If 0.0.9 is not listed yet, wait for its release; source availability does not mean a binary has been published.
4. Start the game. Initial queries and downloads require a network connection.

**Installing or enabling Connector / FFAPI for the first time still requires one restart.** NeoForbric normally starts it automatically. If the launcher blocks it, follow the message and restart manually. These components cannot be mounted dynamically in the same JVM.

GitHub's `Source code` archives and the source ZIP are for developers. Do not put them in `mods/`.

## Behavior

- Looks for NeoForge builds for the current Minecraft version.
- Uses port mappings or separate compatibility components for Fabric mods when applicable.
- Installs missing required dependencies for newly installed mods; optional dependencies are not installed automatically.
- Backs up originals by default and refuses to overwrite a different file with the same name.
- Records unfinished replacements and tries to recover them on the next start.

When no suitable build is available, the original is kept and the reason is reported. Keeping the original does not guarantee that NeoForge can load it. The available replacement may have a different mod version.

## Configuration

Install optional [Cloth Config](https://modrinth.com/mod/cloth-config) to use **Mods → NeoForbric → Configure**. Save and restart to apply changes. Without it, edit:

| File | Purpose |
| --- | --- |
| `config/neoforbric/config.properties` | Main switches, sources, downloads, restart settings |
| `config/neoforbric/ports.properties` | Source mod IDs and preferred port projects |
| `config/neoforbric/blacklist.properties` | Skip list of mod IDs to leave untouched |

Logs are in `logs/neoforbric.log`; the latest processing report is `logs/neoforbric-report.json`.

## Concurrent files and segmented downloads

Several files can download concurrently. Version 0.0.9 adds optional ranges within one file. It requires a suitable known size, a checksum, and valid HTTP Range support. All parts are verified and merged before the result is installed. Unsupported responses fall back to a single stream. Cancellation and an exhausted total deadline stop the download.

| Setting | Default | Meaning |
| --- | --- | --- |
| `http.parallelism` | `8` | Concurrent file-processing tasks |
| `http.segmented.enable` | `false` | Enable ranges within a file; off by default |
| `http.segmented.maxSegments` | `4` | Maximum ranges per file |
| `http.segmented.thresholdMb` | `16` | Minimum file size for attempting ranges |
| `http.segmented.minSegmentMb` | `4` | Minimum target size of each range |
| `http.maxActiveRequests` | `8` | Shared concurrent download request limit, including probes |
| `http.maxRequestsPerHost` | `4` | Concurrent download request limit per host |

Both forms of concurrency share the request limits. More connections do not increase an already saturated total bandwidth. Speed improvements depend on the server and network; no real-CDN speedup is promised.

The startup bar shows completed tasks in the **current stage** and resets when the stage changes. File status shows the current attempt and byte progress. Failed, skipped, and unchanged tasks also count as processed.

## Backups and recovery

Original files are backed up under `mods/.neoforbric/backup/` by default. Temporary rollback files and `journal/` records help recover incomplete changes. They are not bundled copies of the compatibility layer. Do not remove them while the game is running. For manual recovery, exit the game first and check file identities and logs before restoring a backup.

Connector and FFAPI are independent downloads, not embedded in the NeoForbric JAR. Their compatibility remains subject to their upstream projects.

## Limits, builds, and changes

- [Known limitations](docs/KNOWN-LIMITATIONS.md)
- [Build from source](docs/BUILD.md)
- [Changelog](CHANGELOG.en.md) · [中文更新日志](CHANGELOG.md)

The source version is **0.0.9**. Use Release assets and their checksum file to identify a published binary.

## Credits and license

Thanks to NeoForge, [Sinytra Connector](https://github.com/Sinytra/Connector), Forgified Fabric API, Modrinth, CurseForge, MCIM, and the mod authors. Repository and mirror availability is outside this project's control. Downloads remain subject to the platforms' and authors' terms. Third-party components retain their own copyright and licenses.

NeoForbric source is distributed under the [MIT License](LICENSE).
