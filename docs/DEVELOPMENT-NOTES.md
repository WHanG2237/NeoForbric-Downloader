# 实现说明 / Implementation notes

- 文件提交仍由 FileInstaller 和 InstallJournal 负责。HTTP 的 target 是引擎 staging 文件；分段先在独立随机目录合并并验证，不直接修改玩家原件。
- RequestLimiter 在同一个 monitor 内同时检查并占用全局与 host 额度。等待者不预占任何一份额度；permit 用原子状态保证并发关闭至多释放一次。
- 单流与分段复用 Http.TransferWatchdog；错误 body 复用 Http.drain/BodyGuard。截止时间用单调时钟的绝对值传递。排队之后重算请求剩余时间，不能把排队等待从总预算中抹掉。
- 每次实际分段下载拥有自己的 executor 与随机临时目录。完成服务按先完成的 future 收取结果，一项失败即可取消其余项。结束前关闭读取流并等待任务停止；未停止时保留临时目录并报告，避免边写边删。
- SegmentedDownload.Result 区分 SUCCESS、FALLBACK、ABORTED。总时限耗尽或主动中断不允许单流回退；普通协议失败可在剩余预算内回退。单次尝试超时后可回退下一次尝试，总预算不会重置。
- 范围校验解析完整 start/end/total，探测要求 0-0；压缩响应不能作为有效 range。没有强 ETag 时不会发送 If-Range；弱 ETag 导致回退。无验证器情况下最终长度与 SHA-1 仍必须匹配。
- 阶段使用实例级 ID；任务终态按任务 ID 去重。kept 参与收尾计数。字节事件不推进阶段任务数量，按 taskId/attempt 过滤过期事件。
- 0.0.10 起运行时来源只剩官方 Modrinth（`api.modrinth.com`）：CurseForge 接入已整体移除，不留隐藏开关、匿名代理或自动回退；指向 `curseforge.com` / `forgecdn.net` / `curseforge.gg` 的地址**在任何配置槽位**都被拒绝。内置第三方镜像（社区 MCIM）**保留为默认地址但两个开关默认关闭**，必须 `mirror.thirdparty.modrinth.enable` 与 `source.modrinth.mirror.enable` 同时打开才访问，且只以「第三方镜像」标签出现、绝不被当成官方源。旧配置里指向已移除来源的键只被忽略并注释掉，不继续发起请求、不打印秘密、不批量删除用户目录；映射里的 CurseForge 数字 ID 不当作 Modrinth ID 使用，只给出可操作说明。隔离实现细节见 `docs/61-0.0.10-来源移除与升级迁移.md`（维护根侧，不在本仓库内）。
- 端点判定只有一处：`Mirrors.classify(key, slot, url, thirdPartyAllowed)`，槽位分 OFFICIAL / MIRROR / COMPONENT，在查询链建立之前完成。被拒绝的槽位放进 `RefusedSource`，它不发任何请求、只回答 RESTRICTED，因此拒绝不会被写成永久的「不存在」记录。
- 端点身份进入负缓存指纹用 `Mirrors.identityOf(...)`：scheme/host/port/**路径**/查询参数**名**保留明文，查询参数值与 userinfo 只在内存里取 SHA-256 前 12 位十六进制。默认端口、结尾斜杠、scheme 与主机大小写归一化，路径不合并。指纹版本 `decisions-v5`。
- 下载摘要用两个独立字段：`termination=` 表示整次操作为何停止，`lastFailure=` 表示最近一次尝试的失败类别。耗尽重试只写 `termination`，不覆盖具体的失败类别；成功时两者都归 `none`。字节口径不变：`receivedBytes = streamBytes + segmentBytes`，失败分子取实际接收量。
- 许可与署名材料（`LICENSE`、`THIRD-PARTY*.md`、`ICON-LICENSE.md`、`licenses/`）同时进入外层 JAR 与内嵌 body JAR 的 `META-INF/licenses/`。Gradle Wrapper 是 Apache-2.0 的第三方内容，版权头原样保留，不与项目自写代码并列。

These notes describe design intent and constraints. Automated probes cover bounded local failure cases; they do not establish universal server or mod compatibility. Production source has no inline comments by project convention.

测试脚本的健康启动判定同时检查声音初始化与加载失败标志，声音初始化本身不是主菜单证据。故意保留的 Forge 原件应得到明确的 FML 不兼容报告，不能把错误页当作正常启动。冲突夹具使用独立 lowcode NeoForge modid，不借用真实整合包前置，避免重复清理污染后续测试。
