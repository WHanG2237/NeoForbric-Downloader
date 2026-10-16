# 已知边界 / Known limitations

- 支持目标为 Minecraft 1.21.1 / NeoForge 21.1.x，客户端验收目标为 21.1.256。其他版本不在保证范围。
- 这是下载与替换工具，不是通用字节码转译器；无法保证所有混装模组能运行。
- 首次安装或启用 Connector / FFAPI 仍需重启一次；兼容性由其上游实现决定。
- 可用替换的模组版本可能与原件不同；没有完整的版本约束求解器。已存在但版本不满足的前置只报告，不自动替换。
- 重复清理以共享 modid 分组，一个 jar 中独有的 modid 可能随输家一起移走；请检查日志与备份。
- 自动恢复依赖磁盘内容和操作权限。不能保证在磁盘满、文件损坏、杀软拦截等系统故障下自动修好。
- 配置由多个文件顺序保存，保存失败可能已写入部分设置。英文页签在窄窗口需滚动。
- 分段默认开启，但需要合适大小、校验哈希和服务器 Range 支持。总带宽、服务器策略和网络波动都会影响收益；分段不能突破总带宽。
- 错误响应体最多读取 8 KiB 或等待 3 秒（更早的有效截止时间优先）。成功响应各项 0 值按原配置的不限/关闭语义处理。
- **0.0.10 起运行时只使用官方来源**：官方 Modrinth API 默认开启。**CurseForge 接入已整体移除（含「自备 API Key」模式）**，指向 CurseForge 系主机的地址在任何配置槽位都会被拒绝。内置的第三方镜像（社区 MCIM）**保留为默认地址但默认关闭**：`mirror.thirdparty.modrinth.enable` 与 `source.modrinth.mirror.enable` **两个开关同时打开**才会访问，登记与接入范围尚未确认，本项目不代为登记。
  - 历史记录（不删除）：0.0.9 及更早版本默认通过内置第三方镜像访问 CurseForge，并可在填写自己的 API Key 后使用官方 `api.curseforge.com`；CF 部分在 0.0.10 全部移除。
  - **覆盖率变化**：只在 CurseForge 发布、官方 Modrinth 上没有对应项目的模组可能不再能被自动替换，需要用户自行从合法来源手动下载；已安装且完整的模组不会仅因来源变化被删除。
- 官方 API 没有返回可用下载地址，或项目设置了 allowModDistribution=false 时不会下载该文件，也不会自行拼接地址。这只表示无法自动获取，不代表模组不存在，也不代表作者禁止一切分发。
- 平台条款没有逐条确认：现行需要核对的是官方 Modrinth API 与用户自行启用的任何第三方端点；文件缓存期限、API 身份声明与接入登记等待核实。历史记录：早期版本还接入过 CurseForge 与内置第三方镜像，其条款同样未逐条确认。
- 专用服务端、大整合包性能、真实 CDN 提速、真实文件系统故障和完整旧版本升级路径没有全面验收。历史记录：「真实 CurseForge 官方 Key」一项在 0.0.9 及更早版本未验收，0.0.10 移除 CF 接入后该项不再适用。

## 0.0.10 增补

- **端点校验的边界**：0.0.10 统一校验的是**配置里写的端点**（官方槽位、镜像槽位、前置/组件查询共用一个判定）。API 返回的**下载候选地址**、**HTTP 重定向的目标**以及 **DNS** 是另外的边界，本版本没有宣称「全链路零访问」。普通自定义端点仍然可用，本项目不声称能识别所有未知代理。
- **日志里的 URL**：请求日志（`logs/neoforbric.log` 的 DEBUG/ERROR 行）会打印完整请求 URL。若使用者把密钥写进端点 URL 的**查询参数**，该密钥会出现在使用者自己的日志里。指纹与状态文件不会记录明文（查询参数值只以哈希形式进入），但**日志路径本轮未做脱敏**。这是既有行为，如实登记。
- **模组图标**：图标许可**尚未确认**（画面含第三方角色形象与 Minecraft 风格元素），因此**不能**按 MIT 分发，也**不能**登记为已确认的 CC 授权作品。完整事实与缺失材料见仓库根目录 `ICON-LICENSE.md`。**0.0.10 本轮不公开发布。**
- 来源精简与旧配置迁移的完整说明见维护根侧 `docs/61-0.0.10-来源移除与升级迁移.md`，许可与署名口径见 `docs/62-0.0.10-许可与署名清单.md` 与仓库根目录 `THIRD-PARTY.md`。维护根文档位于 `D:\NeoForbric\docs`，**不在本源码仓库内**。
- 本节只登记结论，不重复实现细节；代码证据由实现方给出。

## English

The target is Minecraft 1.21.1 / NeoForge 21.1.x, with client checks on 21.1.256. This tool downloads replacements; it is not a universal bytecode translator. Compatibility with all mixed-loader mod sets is not guaranteed.

Connector / FFAPI still need one restart after initial installation or activation. Replacement mod versions can differ. There is no complete dependency-version solver; existing incompatible dependency versions are reported rather than automatically replaced.

Duplicate groups can remove a losing JAR's unique mod IDs. Check logs and backups. Recovery depends on readable, writable and consistent disk contents. Saving multiple configuration files can partially succeed.

Segmenting is on by default but depends on server support. Its speed benefit is not guaranteed and it cannot exceed total bandwidth. Error bodies have an 8 KiB / three-second cap, subject to an earlier deadline.

**Since 0.0.10 the runtime uses official sources only**: the official Modrinth API is on by default. **CurseForge support was removed entirely, including the "bring your own API key" mode**, and an address pointing at a CurseForge host is refused in every configuration slot. The built-in third-party mirror (the community MCIM) is **kept as a default value but is off by default**: it is only used when **both** `mirror.thirdparty.modrinth.enable` and `source.modrinth.mirror.enable` are on. Its registration and access scope are unconfirmed, and this project does not register on the user's behalf.

- Historical record, kept: 0.0.9 and earlier reached CurseForge through a built-in third-party mirror by default, and could use the official `api.curseforge.com` with the user's own API key. The CurseForge paths were removed in 0.0.10.
- **Coverage change**: mods published only on CurseForge and absent from official Modrinth may no longer be replaced automatically and must be downloaded manually from a legitimate source. Mods already installed and complete are not deleted merely because the source list changed.

When the official API returns no usable download address, or a project sets allowModDistribution=false, the file is not downloaded and no address is constructed. That only means it cannot be fetched automatically; it does not mean the mod is missing and does not mean the author forbids all distribution.

Platform terms have not been confirmed clause by clause: what remains to be verified is the official Modrinth API and any third-party endpoint the user enables, including caching periods, API identification, and service registration. Historical record: earlier versions also used CurseForge and a built-in third-party mirror, whose terms were likewise never confirmed clause by clause.

Dedicated servers, large-pack performance, real-CDN speed gains, filesystem faults, and complete historical upgrade paths have not been comprehensively verified. Historical record: "a real official CurseForge key" was never verified in 0.0.9 and earlier, and that item no longer applies after the removal of CurseForge support in 0.0.10.

## Added in 0.0.10

- **Boundary of endpoint validation**: what 0.0.10 validates is the **endpoint written in the configuration** (the official slot, the mirror slot, and the component/prerequisite query all share one decision). The **download candidate URLs** the API reports, **HTTP redirect targets**, and **DNS** are separate boundaries; this version makes no "zero access on every hop" claim. Ordinary custom endpoints still work, and this project does not claim to recognise every unknown proxy.
- **URLs in the log**: request logging (DEBUG/ERROR lines in `logs/neoforbric.log`) prints the full request URL. If a user puts a secret in an endpoint URL's **query string**, that secret will appear in the user's own log. The fingerprint and the state file never record plaintext (query values enter only as hashes), but **the logging path was not redacted this round**. This is pre-existing behaviour, recorded as such.
- **Mod icon**: the icon's licence is **unconfirmed** (the image contains a third-party character design and Minecraft-style elements), so it **cannot** be distributed under MIT and **cannot** be recorded as a confirmed CC-licensed work. Full facts and the missing material are in `ICON-LICENSE.md` at the repository root. **0.0.10 is therefore not being published this round.**
- Source reduction and legacy-config migration are described in `docs/61-0.0.10-来源移除与升级迁移.md` on the maintenance side, and the licence and attribution position is in `docs/62-0.0.10-许可与署名清单.md` plus `THIRD-PARTY.md` at the repository root. Maintenance-side documents live in `D:\NeoForbric\docs` and are **not** part of this source repository.
- This section records conclusions only; implementation evidence is provided by the implementer.
