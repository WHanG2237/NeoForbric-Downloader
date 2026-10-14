# 从源码构建 / Build from source

需要 JDK 21。Gradle Wrapper 会下载 Gradle 8.14.2 与构建依赖，首次构建需要网络。

Windows PowerShell（在源码根目录）：

```powershell
.\gradlew.bat clean build
Get-FileHash .\build\libs\neoforbric-0.0.9.jar -Algorithm SHA256
```

Linux/macOS：

```sh
chmod +x gradlew
./gradlew clean build
```

输出为 `build/libs/neoforbric-0.0.9.jar`。只安装这个 JAR；不要把源码目录或 ZIP 放入 mods。

The outer JAR runs in the FML service layer. The nested body JAR registers the in-game mod and configuration screen. Connector and FFAPI are independent runtime downloads, not embedded dependencies. Cloth Config is optional at runtime.

## 网络与进度回归 / Network and progress regression

源码包附带两个无需 Minecraft 进程的 Java 探针。下载探针只用本地 HTTP 服务器；进度探针需要 NeoForge 实例的 FML loader JAR，部分环境还需 `spi` JAR。

```powershell
New-Item -ItemType Directory -Path build\probes,build\probe-tmp -Force | Out-Null
javac -encoding UTF-8 -cp build\libs\neoforbric-0.0.9.jar -d build\probes tools\regression\DownloadRegression.java
java -Djava.io.tmpdir=build/probe-tmp -cp 'build/libs/neoforbric-0.0.9.jar;build/probes' DownloadRegression
```

探针会在当前目录的 `sandbox/regression/` 创建测试文件。不要把测试数字视作所有 Minecraft 环境的兼容保证。完整旧版业务测试需要开发夹具，未打包玩家或第三方完整实例。

The download probe uses localhost only. ProgressRegression requires the loader and, where applicable, SPI JAR on both compile and runtime classpaths. See the source for its assertions. Production Java source intentionally contains no comments; rationale is recorded in [DEVELOPMENT-NOTES.md](DEVELOPMENT-NOTES.md).
