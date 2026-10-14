# 实现说明 / Implementation notes

- 文件提交仍由 FileInstaller 和 InstallJournal 负责。HTTP 的 target 是引擎 staging 文件；分段先在独立随机目录合并并验证，不直接修改玩家原件。
- RequestLimiter 在同一个 monitor 内同时检查并占用全局与 host 额度。等待者不预占任何一份额度；permit 用原子状态保证并发关闭至多释放一次。
- 单流与分段复用 Http.TransferWatchdog；错误 body 复用 Http.drain/BodyGuard。截止时间用单调时钟的绝对值传递。排队之后重算请求剩余时间，不能把排队等待从总预算中抹掉。
- 每次实际分段下载拥有自己的 executor 与随机临时目录。完成服务按先完成的 future 收取结果，一项失败即可取消其余项。结束前关闭读取流并等待任务停止；未停止时保留临时目录并报告，避免边写边删。
- SegmentedDownload.Result 区分 SUCCESS、FALLBACK、ABORTED。总时限耗尽或主动中断不允许单流回退；普通协议失败可在剩余预算内回退。单次尝试超时后可回退下一次尝试，总预算不会重置。
- 范围校验解析完整 start/end/total，探测要求 0-0；压缩响应不能作为有效 range。没有强 ETag 时不会发送 If-Range；弱 ETag 导致回退。无验证器情况下最终长度与 SHA-1 仍必须匹配。
- 阶段使用实例级 ID；任务终态按任务 ID 去重。kept 参与收尾计数。字节事件不推进阶段任务数量，按 taskId/attempt 过滤过期事件。

These notes describe design intent and constraints. Automated probes cover bounded local failure cases; they do not establish universal server or mod compatibility. Production source has no inline comments by project convention.

测试脚本的健康启动判定同时检查声音初始化与加载失败标志，声音初始化本身不是主菜单证据。故意保留的 Forge 原件应得到明确的 FML 不兼容报告，不能把错误页当作正常启动。冲突夹具使用独立 lowcode NeoForge modid，不借用真实整合包前置，避免重复清理污染后续测试。
