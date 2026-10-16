# 第三方组件与许可清单

本文件说明 ModRelay（内部 modid `neoforbric`，原名 NeoForbric）实际**再分发**了哪些第三方内容、各自适用什么许可、以及哪些东西只是构建期或运行期依赖而**没有**被打包。

本文件按最终产物核对，不引用旧候选清单。核对对象：源码仓库内容、构建出的模组 JAR、以及 JAR 内嵌的 body JAR。

- 维护者：DeepSeek（AI 助手）；项目署名为 `蓝色大肥鱼`。见 [README](README.md#作者与-ai-贡献)。
- 本文件与 [`licenses/`](licenses/) 一并随源码与发行物分发。

## 一、本项目自有内容：MIT

除下面列出的第三方内容与美术素材外，本项目的源代码、构建脚本、语言文件、文档均由本项目以 **MIT** 许可分发，见 [`LICENSE`](LICENSE)。

## 二、随源码再分发的第三方内容：Gradle Wrapper（Apache-2.0）

`gradlew`、`gradlew.bat` 与 `gradle/wrapper/gradle-wrapper.jar` 是 Gradle 项目的官方文件，由本仓库原样再分发。它们**不是**本项目自写的 MIT 内容，也不适用本项目 MIT 许可。

**实际来源（已逐字节核对，不是推断）：**

| 文件 | 核对结果 |
| --- | --- |
| `gradle/wrapper/gradle-wrapper.jar` | 62,076 字节 / `ed2c26eba7cfb93cc2b7785d05e534f07b5b48b5e7fc941921cd098628abca58`，与 Gradle 官方仓库 `gradle/gradle` 标签 **`v8.1.1`** 的 `gradle/wrapper/gradle-wrapper.jar` **逐字节相同**。55 个条目，全部为 `org/gradle/**` 的 Gradle Wrapper 类；包内**没有** LICENSE/NOTICE 条目。 |
| `gradlew` | 与 `gradle/gradle` 标签 `v8.1.1` 的 `gradlew` 除第 199 行 `DEFAULT_JVM_OPTS` 外完全相同（Gradle 官方仓库那份额外加了 `-Dfile.encoding=UTF-8`，本项目这份没有）。原作者版权头与 Apache-2.0 声明**原样保留**。 |
| `gradlew.bat` | 同上：与 `v8.1.1` 的 `gradlew.bat` 除第 37 行 `DEFAULT_JVM_OPTS` 外相同（行尾为 CRLF）。版权头**原样保留**。 |
| `gradle/wrapper/gradle-wrapper.properties` | 由本项目维护，`distributionUrl` 指向 `gradle-8.14.2-bin.zip`；相比 Gradle 8.14.2 自己生成的同名文件缺少 `validateDistributionUrl=true`。 |

**结论**：Wrapper 是 **Gradle 8.1.1 生成的原件**（启动脚本与 wrapper JAR 都来自 8.1.1），`distributionUrl` 后来被改成本项目实际使用的 8.14.2。JAR 未被修改、未被打包工具重新打包，也**不是** 8.14.2 自己生成的版本。

Gradle 以 **Apache License 2.0** 分发。因此本仓库附带：

- [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) —— Apache-2.0 许可正文（取自 <https://www.apache.org/licenses/LICENSE-2.0.txt>，11,358 字节 / `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`）。
- [`licenses/gradle-NOTICE.txt`](licenses/gradle-NOTICE.txt) —— Gradle 发行版自带、对应 Apache-2.0 第 4(d) 节的 NOTICE 文件（868 字节 / `c2de5fd9adc7ccc4e1382a0e1f38ec6e14f8ca6e28d3c20289fe80353b019aa1`）。

关于 NOTICE 的准确说法：本项目再分发的这两个 Wrapper 文件本身**没有**内嵌 NOTICE 文件，因此严格说并不因它们而产生第 4(d) 节的 NOTICE 复制义务；这里仍照录 Gradle 发行版的 NOTICE，是为了让「本项目实际使用的 Gradle 8.14.2」的归属信息一并可查，而不是声称某份声明曾被省略。

**已知待改进项（本轮未改，如实登记）**：`gradle-wrapper.properties` 未设置 `distributionSha256Sum`，也没有 `validateDistributionUrl`。二者都能提高下载完整性校验强度。Gradle 8.14.2 的 `gradle-8.14.2-bin.zip` 官方校验值为 `7197a12f450794931532469d4ff21a59ea2c1cd59a3ec3f89c035c3c420a6999`（取自 `https://services.gradle.org/distributions/gradle-8.14.2-bin.zip.sha256`）。本轮不修改 Wrapper 行为，仅在此登记。

## 三、打包进模组 JAR 的内容

发行物 `neoforbric-<版本>.jar` 同时是外层定位器与内层 body JAR 的容器。按构建出的 JAR 实际列出：

| 产物 | 条目数 | 内容 |
| --- | --- | --- |
| 外层 `neoforbric-0.0.10.jar` | 120 | `com/deepseek/neoforbric/**` 类、`META-INF/services/**`、`META-INF/neoforbric/neoforbric-body.jar`、`neoforbric-ports.properties`、`neoforbric-build.properties`、许可材料 |
| 内嵌 `META-INF/neoforbric/neoforbric-body.jar` | 27 | `com/deepseek/neoforbric/body/**` 类、`assets/neoforbric/**`、`META-INF/neoforge.mods.toml`、`neoforbric.mixins.json`、`icon.png` |

**JAR 内没有任何第三方代码**：所有 `.class` 条目都在 `com/deepseek/neoforbric/` 之下，没有其它 Java 包。逐条核对过，不是按「没看到版权头」推断的。

## 四、运行期独立下载、**不打包**的组件

以下组件由 ModRelay 在运行时从各自的官方地址**单独下载**并放进 `mods/`。它们是与本模组 JAR 分开的独立文件，版权与许可归各自原作者，**不受本项目 MIT 重新授权**，也不受图标许可影响。

| 组件 | 用途 | 官方项目 | 许可归属 |
| --- | --- | --- | --- |
| Sinytra Connector（信雅互联） | Fabric 模组兼容层 | <https://github.com/Sinytra/Connector> | 归 Sinytra 项目，以该项目的许可为准 |
| Forgified Fabric API | Connector 所需的 Fabric API 实现 | <https://github.com/Sinytra/ForgifiedFabricAPI> | 归 Sinytra 项目，以该项目的许可为准 |

本项目不复制、不改写、不重新授权这些文件；下载后的使用条件由使用者与原作者/平台之间决定。

## 五、构建期依赖（**不内嵌**）

| 依赖 | 用途 | 是否进入发行 JAR |
| --- | --- | --- |
| NeoForge / Minecraft 1.21.1 编译接口 | 编译 | 否 |
| ModDevGradle（`net.neoforged.moddev`） | 构建插件 | 否 |
| Cloth Config（`me.shedaniel.cloth:cloth-config-neoforge`） | 编译期配置界面接口，`compileOnly`（参见 `build.gradle` 的 `bodyClothConfig`） | **否**，未安装 Cloth Config 的实例照常运行 |
| Gradle 及构建期下载的其它依赖 | 构建 | 否 |

把「构建期解析过的全部 Gradle 依赖」一律说成「已内嵌」是错误的；这里的判断依据是对最终 JAR 的逐条清点，而不是依赖声明。

## 六、美术素材

模组图标 `src/body/resources/icon.png` **不是 MIT**，其许可与署名单独登记，见 [`ICON-LICENSE.md`](ICON-LICENSE.md)。本节不重复其结论。

## 七、方法说明

本清单的核对方式是：读 `gradlew` / `gradlew.bat` 的头部声明；把 Wrapper 文件与 Gradle 官方仓库对应标签的文件做逐字节比较；`unzip -l` 清点最终 JAR 与内嵌 body JAR 的每一个条目；在本地 Gradle 发行版里查找 NOTICE 与 LICENSE 的实际内容。

**「文件没有版权头」不能证明「源码没有第三方来源」**，反之亦然。上一轮交付曾把 `gradlew*` 与项目自写代码并列，也没有为 Wrapper 附许可证正文，这是本清单要纠正的具体错误。
