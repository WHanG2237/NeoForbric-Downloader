# 已知边界 / Known limitations

- 支持目标为 Minecraft 1.21.1 / NeoForge 21.1.x，客户端验收目标为 21.1.256。其他版本不在保证范围。
- 这是下载与替换工具，不是通用字节码转译器；无法保证所有混装模组能运行。
- 首次安装或启用 Connector / FFAPI 仍需重启一次；兼容性由其上游实现决定。
- 可用替换的模组版本可能与原件不同；没有完整的版本约束求解器。已存在但版本不满足的前置只报告，不自动替换。
- 重复清理以共享 modid 分组，一个 jar 中独有的 modid 可能随输家一起移走；请检查日志与备份。
- 自动恢复依赖磁盘内容和操作权限。不能保证在磁盘满、文件损坏、杀软拦截等系统故障下自动修好。
- 配置由多个文件顺序保存，保存失败可能已写入部分设置。英文页签在窄窗口需滚动。
- 分段默认关闭；需要合适大小、校验哈希和服务器 Range 支持。总带宽、服务器策略和网络波动都会影响收益。
- 错误响应体最多读取 8 KiB 或等待 3 秒（更早的有效截止时间优先）。成功响应各项 0 值按原配置的不限/关闭语义处理。
- 专用服务端、真实 CurseForge 官方 Key、大整合包性能、真实 CDN 提速、真实文件系统故障和完整旧版本升级路径没有全面验收。

## English

The target is Minecraft 1.21.1 / NeoForge 21.1.x, with client checks on 21.1.256. This tool downloads replacements; it is not a universal bytecode translator. Compatibility with all mixed-loader mod sets is not guaranteed.

Connector / FFAPI still need one restart after initial installation or activation. Replacement mod versions can differ. There is no complete dependency-version solver; existing incompatible dependency versions are reported rather than automatically replaced.

Duplicate groups can remove a losing JAR's unique mod IDs. Check logs and backups. Recovery depends on readable, writable and consistent disk contents. Saving multiple configuration files can partially succeed.

Segmenting is off by default and depends on server support. Its speed benefit is not guaranteed. Error bodies have an 8 KiB / three-second cap, subject to an earlier deadline. Dedicated servers, a real official CurseForge key, large-pack performance, real-CDN speed gains, filesystem faults, and complete historical upgrade paths have not been comprehensively verified.
