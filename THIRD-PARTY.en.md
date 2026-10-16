# Third-party components and license inventory

This file states which third-party content ModRelay (internal modid `neoforbric`, formerly NeoForbric) actually **redistributes**, which license applies to each part, and which things are only build-time or runtime dependencies that are **not** bundled.

It is checked against the final artifacts, not against an older candidate inventory. Checked: the source repository, the built mod JAR, and the body JAR nested inside it.

- Maintainer: DeepSeek (AI assistant); the project signature is `蓝色大肥鱼`. See [README](README.en.md#author-and-ai-contribution).
- This file ships with the source and with the distribution, together with [`licenses/`](licenses/).

## 1. This project's own content: MIT

Apart from the third-party content and the artwork listed below, this project's source code, build scripts, language files, and documentation are distributed by this project under **MIT**, see [`LICENSE`](LICENSE).

## 2. Third-party content redistributed with the source: the Gradle Wrapper (Apache-2.0)

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are official Gradle project files, redistributed here unchanged. They are **not** MIT content written by this project, and this project's MIT license does not apply to them.

**Actual provenance (verified byte by byte, not inferred):**

| File | Verification result |
| --- | --- |
| `gradle/wrapper/gradle-wrapper.jar` | 62,076 bytes / `ed2c26eba7cfb93cc2b7785d05e534f07b5b48b5e7fc941921cd098628abca58` — **byte-identical** to `gradle/wrapper/gradle-wrapper.jar` at the official Gradle repository tag **`v8.1.1`**. 55 entries, all Gradle Wrapper classes under `org/gradle/**`; the archive contains **no** LICENSE or NOTICE entry. |
| `gradlew` | Identical to `gradle/gradle` tag `v8.1.1`'s `gradlew` except line 199 (`DEFAULT_JVM_OPTS`): Gradle's own repository copy additionally sets `-Dfile.encoding=UTF-8`, this one does not. The original copyright header and Apache-2.0 notice are **kept verbatim**. |
| `gradlew.bat` | Same: identical to `v8.1.1`'s `gradlew.bat` except line 37 (`DEFAULT_JVM_OPTS`); line endings are CRLF. The copyright header is **kept verbatim**. |
| `gradle/wrapper/gradle-wrapper.properties` | Maintained by this project; `distributionUrl` points at `gradle-8.14.2-bin.zip`; compared with the file Gradle 8.14.2 itself generates it lacks `validateDistributionUrl=true`. |

**Conclusion**: the Wrapper is the **original Gradle 8.1.1 output** (both launcher scripts and the wrapper JAR come from 8.1.1); `distributionUrl` was later changed to the 8.14.2 this project actually uses. The JAR was not modified and was not repackaged by any tooling, and it is **not** the jar Gradle 8.14.2 generates.

Gradle is distributed under the **Apache License 2.0**. This repository therefore ships:

- [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) — the Apache-2.0 license text (from <https://www.apache.org/licenses/LICENSE-2.0.txt>, 11,358 bytes / `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`).
- [`licenses/gradle-NOTICE.txt`](licenses/gradle-NOTICE.txt) — the NOTICE file shipped with the Gradle distribution, corresponding to section 4(d) of Apache-2.0 (868 bytes / `c2de5fd9adc7ccc4e1382a0e1f38ec6e14f8ca6e28d3c20289fe80353b019aa1`).

An accurate statement about NOTICE: the two Wrapper files redistributed here carry **no** embedded NOTICE file, so strictly speaking they create no 4(d) obligation to reproduce one. It is reproduced here anyway so that the attribution for the Gradle 8.14.2 this project actually runs is also on file — not to imply that a notice was ever omitted.

**Known improvement, recorded but not made this round**: `gradle-wrapper.properties` sets neither `distributionSha256Sum` nor `validateDistributionUrl`. Both would strengthen download integrity checking. The official checksum of `gradle-8.14.2-bin.zip` is `7197a12f450794931532469d4ff21a59ea2c1cd59a3ec3f89c035c3c420a6999` (from `https://services.gradle.org/distributions/gradle-8.14.2-bin.zip.sha256`). This round does not change Wrapper behavior; it is recorded here only.

## 3. What is inside the mod JAR

The distribution `neoforbric-<version>.jar` is both the outer locator and the container for the inner body JAR. Listed from the built JAR:

| Artifact | Entries | Content |
| --- | --- | --- |
| Outer `neoforbric-0.0.10.jar` | 120 | `com/deepseek/neoforbric/**` classes, `META-INF/services/**`, `META-INF/neoforbric/neoforbric-body.jar`, `neoforbric-ports.properties`, `neoforbric-build.properties`, license material |
| Nested `META-INF/neoforbric/neoforbric-body.jar` | 27 | `com/deepseek/neoforbric/body/**` classes, `assets/neoforbric/**`, `META-INF/neoforge.mods.toml`, `neoforbric.mixins.json`, `icon.png` |

**There is no third-party code in the JAR**: every `.class` entry lives under `com/deepseek/neoforbric/`, and no other Java package exists. That was checked entry by entry, not inferred from the absence of a copyright header.

## 4. Downloaded independently at runtime, **not** bundled

ModRelay downloads the following from each project's official address at runtime and places the file in `mods/`. They are separate files from this mod's JAR; copyright and license stay with their authors, and **this project's MIT license does not re-license them** — nor does the icon license affect them.

| Component | Purpose | Official project | License |
| --- | --- | --- | --- |
| Sinytra Connector | Fabric mod compatibility layer | <https://github.com/Sinytra/Connector> | Sinytra project; its own license applies |
| Forgified Fabric API | Fabric API implementation required by Connector | <https://github.com/Sinytra/ForgifiedFabricAPI> | Sinytra project; its own license applies |

This project does not copy, modify, or relicense these files; the terms that apply after download are between the user and the original authors or platform.

## 5. Build-time dependencies (**not embedded**)

| Dependency | Purpose | In the distribution JAR? |
| --- | --- | --- |
| NeoForge / Minecraft 1.21.1 compile APIs | compile | No |
| ModDevGradle (`net.neoforged.moddev`) | build plugin | No |
| Cloth Config (`me.shedaniel.cloth:cloth-config-neoforge`) | compile-time config-screen API, `compileOnly` (see `bodyClothConfig` in `build.gradle`) | **No** — instances without Cloth Config run normally |
| Gradle and other build-time dependencies | build | No |

Calling "every Gradle dependency resolved at build time" an embedded dependency is wrong. The determination here comes from an entry-by-entry inventory of the final JAR, not from the dependency declarations.

## 6. Artwork

The mod icon `src/body/resources/icon.png` is **not MIT**; its license and attribution are recorded separately in [`ICON-LICENSE.md`](ICON-LICENSE.md). This section does not restate that conclusion.

## 7. Method

This inventory was produced by: reading the header notices in `gradlew` / `gradlew.bat`; comparing the Wrapper files byte by byte against the corresponding files at the matching official Gradle repository tag; listing every entry of the final JAR and of the nested body JAR with `unzip -l`; and reading the actual NOTICE and LICENSE shipped inside the local Gradle distribution.

**"The file has no copyright header" does not prove "the source has no third-party origin"**, and the reverse does not hold either. The previous delivery listed `gradlew*` alongside first-party code and shipped no license text for the Wrapper; that is the specific error this inventory corrects.
