# ModRelay（模组接力）

[English](README.en.md) · [下载模组](https://github.com/WHanG2237/NeoForbric-Downloader/releases) · [更新日志](CHANGELOG.md)

**模组装错加载器了？让 ModRelay 帮你找找对应版本。**

ModRelay（原名 NeoForbric）是适用于 **Minecraft 1.21.1 / NeoForge 21.1.x** 的模组下载与替换工具。它会识别实例中的 Forge、Fabric 模组，尝试寻找适用的 NeoForge 构建，下载并替换原文件。

游戏内用“转译”来呈现这一过程，实际执行的是查询、下载、校验、备份和替换。对于仍需 Fabric 环境的模组，ModRelay 会按需安装 [Sinytra Connector（信雅互联）](https://github.com/Sinytra/Connector) 和 [Forgified Fabric API](https://github.com/Sinytra/ForgifiedFabricAPI)。它不进行字节码转译，也不能保证所有混装模组都能运行。

## 功能

- 查找适用于当前 Minecraft 版本的 NeoForge 构建，也支持通过映射表指定移植项目。
- 补齐新安装模组缺少的必需前置。
- 默认备份被替换的原件；遇到不同内容的同名文件时拒绝覆盖。
- 处理可确认的重复模组，无法确认的冲突保留文件并记录原因。
- 显示启动阶段与文件下载进度，记录下载结果、平均速度和处理报告。
- 提供游戏内配置，以及可选的多文件并发、单文件分段下载。

找到的构建可能与原模组的版本号不同。没有合适构建或无法完成替换时，原件会保留；这并不代表 NeoForge 能继续加载它。

## 安装与升级

1. 准备 Minecraft 1.21.1 / NeoForge 21.1.x 实例。
2. 退出游戏，备份实例的 `mods/` 文件夹。
3. 从 [Releases](https://github.com/WHanG2237/NeoForbric-Downloader/releases) 下载 `neoforbric-<版本>.jar`，放入 `mods/`。
4. 启动游戏，首次查询与下载需要网络连接。

升级时先移除旧版 NeoForbric / ModRelay JAR，避免重复加载。模组文件名和配置目录仍使用 `neoforbric`，旧配置与备份可以继续沿用。GitHub 的 Source code 和源码 ZIP 用于构建，不要放入 `mods/`。

**首次安装或启用 Connector / FFAPI 后仍需重启一次。** ModRelay 会尝试自动重启；未能自动重启时，请按提示手动操作。

## 配置与日志

可选安装 [Cloth Config](https://modrinth.com/mod/cloth-config)，通过“模组 → ModRelay → 配置”打开设置。配置修改后重启生效，也可以直接编辑下列文件：

| 路径 | 用途 |
| --- | --- |
| `config/neoforbric/config.properties` | 下载来源、并发、重启等设置 |
| `config/neoforbric/ports.properties` | 模组与移植项目的映射 |
| `config/neoforbric/blacklist.properties` | 跳过列表，按 modid 指定不自动处理的模组 |
| `logs/neoforbric.log` | 查询、下载和文件处理日志 |
| `logs/neoforbric-report.json` | 本次启动的处理报告 |
| `mods/.neoforbric/backup/` | 替换与去重时保存的原件备份 |

默认一次处理一个文件，单文件分段下载关闭。可以根据网络情况调整并发或开启分段，但连接数增加不一定更快。下载速度会受网络、服务器和可用带宽影响。

需要手动恢复备份时，先退出游戏，再根据日志核对文件。不要在游戏运行期间删除 `mods/.neoforbric/` 中的备份、临时文件或恢复记录。

## 下载来源与兼容范围

默认通过 **Modrinth 官方 API** 查询模组与版本，再从返回的下载地址获取文件。仅在其它平台发布的模组可能无法自动获取，需要自行安装。当前不提供 CurseForge 接入。

第三方 Modrinth 镜像默认关闭。内置的 MCIM 端点尚未完成本项目的登记与接入范围确认；请求中的 User-Agent 用于标识 ModRelay，不代表服务授权。

ModRelay 在 [GitHub Releases](https://github.com/WHanG2237/NeoForbric-Downloader/releases) 提供下载。Connector、FFAPI 和其它模组都是独立下载的文件，不内嵌在 ModRelay 中，其兼容性、版权和许可由各自项目决定。

这是一个实验性项目。完整整合包的依赖关系无法仅靠替换加载器版本解决，建议先在实例副本中使用。更多限制见 [已知限制](docs/KNOWN-LIMITATIONS.md)。

## 反馈与源码

遇到问题可以提交 [Issue](https://github.com/WHanG2237/NeoForbric-Downloader/issues)。请附上 Minecraft / NeoForge 版本、涉及的模组、复现步骤和相关日志；上传前检查并移除令牌等敏感信息。

- [从源码构建](docs/BUILD.md)
- [中文更新日志](CHANGELOG.md) · [English changelog](CHANGELOG.en.md)

## 作者与 AI 贡献

项目由维护者提出构想、决定功能并进行体验测试，代码、脚本、测试和文档主要由 DeepSeek 完成，Codex 参与复核与部分修改。“蓝色大肥鱼”是 DeepSeek 在本项目中使用的署名。

感谢 NeoForge、Sinytra Connector、Forgified Fabric API、Modrinth，以及各模组作者。

## 许可

项目代码采用 [MIT License](LICENSE)。随源码分发的 Gradle Wrapper 采用 Apache-2.0，详见 [第三方许可说明](THIRD-PARTY.md)。图标的授权状态与署名见 [ICON-LICENSE.md](ICON-LICENSE.md)，不适用代码的 MIT 许可。运行时下载的模组保留各自的许可。
