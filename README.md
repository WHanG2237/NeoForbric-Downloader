# NeoForbric

[English](README.en.md)

Minecraft **1.21.1 / NeoForge 21.1.x** 的模组下载与替换工具。它会识别放错加载器的 Forge、Fabric 模组，查询适用的 NeoForge 构建，在找到合适文件后下载并替换。

游戏内以“转译”呈现这一过程；实际进行的是仓库查询、文件下载、校验、备份和替换。需要兼容层时，会单独下载 Sinytra Connector（信雅互联）及 Forgified Fabric API。**它不能保证所有 Forge、Fabric 模组都能在 NeoForge 上运行。**

## 安装

1. 准备 Minecraft 1.21.1、NeoForge 21.1.x 实例；客户端验收使用 NeoForge 21.1.256。
2. 退出游戏，备份实例的 `mods/` 文件夹。
3. 在 [Releases](https://github.com/WHanG2237/NeoForbric-Downloader/releases) 下载 `neoforbric-0.0.9.jar`，放入 `mods/`。若 0.0.9 尚未出现在 Release 中，请等候发布；仓库源码不等于已发布版本。
4. 启动游戏。首次查询、下载需要网络连接。

**首次安装或启用 Connector / FFAPI 后仍需重启一次。** NeoForbric 会尝试自动重启；启动器不允许时请按提示手动重启。没有同 JVM 动态挂载功能。

不要把 GitHub 的 `Source code` 或源码 ZIP 放进 `mods/`。它们是给开发者构建用的源码。

## 能处理什么

- 为 Forge 模组查找当前 Minecraft 版本适用的 NeoForge 构建。
- 为 Fabric 模组寻找 NeoForge 构建或映射表指定的移植版；必要时安装独立兼容层。
- 补齐新安装模组缺少的必需前置；可选前置不会自动安装。
- 默认备份替换前的原件；遇到不同内容的同名目标时拒绝覆盖。
- 记录未完成的文件替换，在下次启动时尝试恢复。

找不到合适构建时保留原件，并在日志和报告中说明。保留原件不代表游戏一定能继续加载。模组版本可能随可用构建改变，并非任何情况下都严格保持原版本。

## 配置

可选安装 [Cloth Config](https://modrinth.com/mod/cloth-config)，通过 **模组 → NeoForbric → 配置** 打开设置。保存后重启生效。未安装 Cloth Config 时可编辑：

| 文件 | 用途 |
| --- | --- |
| `config/neoforbric/config.properties` | 总开关、源、下载、重启与其他设置 |
| `config/neoforbric/ports.properties` | 源模组与移植项目的映射 |
| `config/neoforbric/blacklist.properties` | 跳过列表：指定不自动处理的 modid |

日志在 `logs/neoforbric.log`，本轮处理报告在 `logs/neoforbric-report.json`。

## 多文件并发与单文件分段

多个文件可以并发下载。0.0.9 新增可选的单文件分段：服务器支持 Range、长度满足阈值且有校验哈希时，将一个文件拆成几个区间同时下载，全部通过校验后合并。协议不支持时回退单流；主动取消或总时限耗尽时停止下载。

| 配置键 | 默认值 | 含义 |
| --- | --- | --- |
| `http.parallelism` | `8` | 文件处理任务的并发数 |
| `http.segmented.enable` | `false` | 启用单文件分段，默认关闭 |
| `http.segmented.maxSegments` | `4` | 每个文件最多分几段 |
| `http.segmented.thresholdMb` | `16` | 达到此大小才尝试分段 |
| `http.segmented.minSegmentMb` | `4` | 每段的最小目标大小 |
| `http.maxActiveRequests` | `8` | 所有文件共享的并发下载请求上限，含探测 |
| `http.maxRequestsPerHost` | `4` | 同一 host 的并发下载请求上限 |

两层并发共享请求额度，不是把两个线程数直接相乘。分段是否更快取决于服务器和网络：若总带宽已用满，增加连接通常没有帮助。真实 CDN 加速幅度没有保证。

启动页的长条显示**当前阶段已处理的任务数**；切换阶段会归零。下载状态显示当前文件的尝试编号和字节进度，失败、跳过和保留原件也计入阶段已处理数。

## 备份与恢复

默认的原件备份位于 `mods/.neoforbric/backup/`。临时回滚文件与 `journal/` 事务记录用于恢复未完成的操作，不是另一份兼容层。不要在游戏运行时删除它们。需要手动恢复时，先退出游戏，核对文件身份和日志，再从备份取回原件。

本项目不把 Connector 或 FFAPI 打包进自身 JAR；运行时下载的组件是独立文件。兼容层本身的兼容性由其上游项目决定。

## 边界、构建与更新

- [已知边界 / Known limitations](docs/KNOWN-LIMITATIONS.md)
- [从源码构建 / Build from source](docs/BUILD.md)
- [更新日志](CHANGELOG.md) · [Changelog](CHANGELOG.en.md)

当前源码版本为 **0.0.9**。正式发布文件以 Release 附件与 SHA-256 清单为准。

## 致谢与许可

感谢 NeoForge、[Sinytra Connector](https://github.com/Sinytra/Connector)、Forgified Fabric API、Modrinth、CurseForge、MCIM 及相关模组作者。仓库与镜像的可用性不由本项目保证；下载文件应遵守对应平台与作者的使用条件。第三方组件的版权与许可归其原作者。

NeoForbric 源码使用 [MIT License](LICENSE)。
