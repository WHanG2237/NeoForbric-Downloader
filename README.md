# NeoForbric

用于 **Minecraft 1.21.1 / NeoForge 21.1.x** 的模组自动下载与替换工具。

装错了模组加载器版本时，NeoForbric 会尝试查找可用的 NeoForge 构建；仍需运行 Fabric 模组时，按需安装 Sinytra Connector（信雅互联）和 Forgified Fabric API。

## 它实际做什么

界面使用「转译」作为项目的整活表现，实际处理方式是**查询、下载、校验和替换文件**，以及使用独立的兼容组件。NeoForbric 自身不是通用字节码转译器，也没有把三种加载器合并为一个。

- **Forge 模组**：查找适用于当前 Minecraft 版本的 NeoForge 构建，优先原模组版本，也可能尝试其他可用版本。
- **Fabric 模组**：优先查找移植版或对应的 NeoForge 构建；需要兼容层时，尝试安装信雅互联及其前置。
- **移植映射**：允许手动指定优先查询的项目，支持 Modrinth 和 CurseForge。
- **必需前置**：尝试补齐本次新装或替换模组缺少的必需依赖；不自动安装可选依赖，也不自动更换已存在但版本不合适的前置。
- **文件保护**：默认备份被替换的原件；不同内容撞名时拒绝覆盖。替换失败会尝试恢复，未完成的操作保留记录，在后续启动继续恢复。

**它不能保证所有 Forge/Fabric 模组或所有整合包都能运行。**找不到合适构建、缺少必需依赖或兼容层不支持的模组，仍可能导致 NeoForge 报错。

## 安装

1. 准备 Minecraft **1.21.1** 和 **NeoForge 21.1.x** 实例。实际客户端验证使用 NeoForge **21.1.256**。
2. 首次使用前，备份实例的 `mods` 文件夹。游戏退出后，将 Release 中的 **`neoforbric-0.0.8.jar`** 放入该实例的 `mods`。
3. 启动游戏。查询和下载需要网络，处理结果会写入日志。
4. 首次安装或启用信雅互联等兼容组件后，需要重启一次；默认尝试自动重启，无法自动重启时按界面提示手动操作。

首次下载没有免重启动态挂载。不要把 GitHub 自动生成的 `Source code.zip` 当作模组放进 `mods`。

如果想在游戏里改配置，安装适用于 NeoForge 的 [Cloth Config](https://modrinth.com/mod/cloth-config)。它是可选的：不安装仍可运行 NeoForbric，但需要手动编辑配置文件。信雅互联与 FFAPI 不打包在本 jar 内。

## 设置

装有 Cloth Config 时：**模组列表 → NeoForbric → 配置**。页面包括常规、自动重启、转译与依赖、下载与网络、移植映射、跳过列表及高级设置。

**所有设置都需保存并重启游戏后生效。**英文页签在较窄窗口下需要点击箭头滚动。

配置文件位于实例的 `config/neoforbric/`：

| 文件 | 用途 |
| --- | --- |
| `config.properties` | 主设置、来源、网络和自动重启参数 |
| `ports.properties` | 源 modid 到目标项目的移植映射 |
| `blacklist.properties` | 界面中的「跳过列表」，按 modid 停止自动处理 |

删除一条移植映射会回到自动处理流程。想保持某个模组原样，应将它加入跳过列表；兼容组件仍可能因实际需求安装。

几个容易误解的参数：

- `forge.keepFileName=true`：替换 Forge/Fabric 模组时沿用原文件名，名称可能仍带 Forge/Fabric；关闭则使用下载文件名。名字不能代表当前文件的真实加载器类型。
- `forge.backup=false`：成功替换后不保留长期原件，替换期间仍有临时保护。恢复失败时会记录原件位置，不保证能立即恢复。
- `http.retries=3`：每个地址最多尝试 **3 次，包含首次请求**。
- `dependencies.maxDepth=0`：按 1 层处理；停止补齐需要关闭 `dependencies.autoInstall`。
- `startup.stallMs`：每轮处理开始前的额外固定等待；不是整个加载界面的最短显示时间。

重复清理以 jar 为单位。一个 jar 包含多个 modid 时，移除它也可能让其中独有的模组缺失，请留意报告和备份。隐藏规则仅影响模组列表及计数，不会停止模组加载。

## 原件、缓存和日志

| 相对实例的路径 | 内容 |
| --- | --- |
| `mods/.neoforbric/backup/` | NeoForbric 被替换或清理的原件备份 |
| `mods/.neoforbric/rollback/` | 替换期间的临时原件 |
| `mods/.neoforbric/journal/` | 未完成文件操作的恢复记录 |
| `logs/neoforbric.log` | 查询、下载、替换和恢复的详细日志 |
| `logs/neoforbric-report.json` | 本轮处理的结构化报告 |
| `logs/latest.log` | Minecraft/NeoForge 游戏日志 |

信雅互联自行维护转换缓存；已测试版本使用 `.cache/connector/`。它与 NeoForbric 的原件备份目录独立，未合并。

手动还原前退出游戏，确认备份对应的模组和版本，再处理当前替换文件；不要把多个加载器版本盲目一起放回 `mods`。

## 0.0.8 的主要改进

- 不同内容撞名时拒绝覆盖，替换失败保留恢复线索，支持未完成操作的后续恢复。
- 区分暂时查询失败和确定查无结果；确定无结果有缓存期限，相关配置变化后会重查。
- 重复检测考虑一个 jar 中的多个 modid，并按实际落地文件核对保留项。
- 错误响应内容的读取有 3 秒上限，避免错误服务器无限拖住读取。
- 重写中英文配置文案，各 114 个词条，并补上「转译与依赖」页说明。

## 验证与边界

当前 0.0.8 候选在客户端路线经过离线自检 **170 项**、修复探针 **170 项**、引擎探针 **141 项**，均通过；两次独立构建得到相同 jar。已有实机场景原始日志包含 **37 条断言通过、0 失败**；早期脚本显示 74 是计数重复，已按原始断言更正。

当前版本未完成专用服务端实机验证、真实 CurseForge 官方 API Key 验证、大整合包性能测试及真实系统级故障覆盖。详细说明见 [已知边界](docs/KNOWN-LIMITATIONS.md)。

本仓库保留产品源码和构建文件。内部实机测试脚本及夹具未随首发源码提供，文档中的测试结果是已有复核记录；执行 Gradle build 不会自动运行上述独立探针。

## 从源码构建

需要 **JDK 21** 和可访问构建依赖的网络。使用项目附带的 Gradle Wrapper（8.14.2）。

Windows PowerShell：

```powershell
.\gradlew.bat build
```

Linux / macOS：

```bash
chmod +x gradlew
./gradlew build
```

产物：`build/libs/neoforbric-0.0.8.jar`。首次构建需要下载 Gradle、NeoForge 和编译依赖。

工程编译使用 NeoForge 21.1.77，实际客户端测试使用 21.1.256；配置界面的 Cloth Config 为编译期依赖，不打包进产物。详见 [构建与结构](docs/BUILD.md)。

## 问题反馈

提交 Issue 时请提供 Minecraft、NeoForge、NeoForbric 版本，复现步骤和相关模组版本，以及 `neoforbric.log`、`neoforbric-report.json`；崩溃时附 `latest.log` 或 crash report。

上传前检查日志中的个人路径、服务器地址和其他私人信息。不要提交 CurseForge API Key。

## 许可与说明

本项目采用 [MIT License](LICENSE)，大量代码由 AI 辅助构建。第三方模组及兼容组件遵循各自的许可，其作者与 NeoForbric 独立。

相关兼容组件：[Sinytra Connector](https://github.com/Sinytra/Connector)、[Forgified Fabric API](https://github.com/Sinytra/ForgifiedFabricAPI)。
