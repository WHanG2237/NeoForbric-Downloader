# Changelog

This file contains version history. The home page keeps the project description and installation guidance; release-specific details belong here and on the GitHub Release page.

## 0.0.8

### English

- Refuses to overwrite a different file with the same target name. Replacements keep a backup or rollback copy and preserve a journal when recovery is incomplete.
- Separates temporary query failures from confirmed “not found” results. Negative results expire and are reconsidered when the relevant configuration or environment changes.
- Detects duplicate mod IDs across all mod IDs declared by a jar and verifies the winner using the file that actually reached `mods/`.
- Caps reading an error response body at three seconds, preventing a broken server from holding the startup process indefinitely.
- Refreshes the Chinese and English configuration text and adds the description for the translation and dependency page.
- Keeps the 0.0.8 build reproducible and publishes the jar under its own versioned filename.

### 中文

- 不同内容撞名时拒绝覆盖；替换过程保留备份或回滚文件，恢复未完成时保留 journal 供下次启动继续处理。
- 区分暂时查询失败和确定查无结果；负结果会过期，相关配置或环境变化后会重新查询。
- 按一个 jar 声明的全部 modid 检测重复，并根据实际落地到 `mods/` 的文件核对保留项。
- 错误响应体的读取最多等待 3 秒，避免异常服务器无限拖住启动流程。
- 重写中英文配置文案，并补上「转译与依赖」页说明。
- 保持 0.0.8 构建可复现，并使用独立的版本文件名发布。

## 0.0.7

- Added safer configuration migration, automatic restart keep-alive behavior, duplicate handling, the skip list, and a revised in-game configuration screen.
- Clarified that failed searches leave the original mod for NeoForge to report instead of silently pretending that the mod was fixed.

## 0.0.6

- Reworked the Cloth Config screen, timeout units, port-mapping format, configuration persistence, and backup handling.

## 0.0.5

- Added download deadlines, retry/resume behavior, visible startup progress, and a CurseForge fallback path.

## 0.0.4

- Added community port mappings, dependency completion, duplicate-mod cleanup, and stronger post-download identity checks.

## 0.0.3

- Added parent-process keep-alive, restart log continuity, clearer independent logs, and dependency-based Connector detection.

## 0.0.2

- Added the first automatic restart flow, original filename preservation, and safer handling of disabled compatibility components.

## 0.0.1

- Initial release: find NeoForge builds for Forge/Fabric mods, download Connector and Forgified Fabric API when needed, and restart the game when the service layer must be rediscovered.
