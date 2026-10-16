# Changelog

## 0.0.10-alpha.9

This release closes one remaining gap: **an original file that changes while the clean-up is running**. The
alpha.8 and earlier changes are kept below.

### The redundancy decision and the original file now share one snapshot (A8-01)

- The decision to move a duplicate out of `mods/` was based on the coverage seen at scan time, but the last
  check before the move hashed the file only when the removal started. A file replaced in between by one with
  **the same size, the old modid and an extra unique modid** therefore certified itself as unchanged, and the
  file **with the new identity** was moved to the backup; that new modid left the active folder (kept in the
  backup, not deleted).
- A snapshot of the original (SHA-1, size, real loader, every modid and the version of each modid) is now taken
  **when the redundancy is decided**, and the pre-move check compares the real file against that snapshot:
  - an added modid keeps the file (the check no longer only asks whether the old modid is still there);
  - a vanished modid, a changed loader, changed version information, or a changed size/hash keeps the file;
  - a snapshot that could not be read keeps the file.
- A detected change means: nothing is moved, nothing is reported as success, and `unresolved-duplicate` is written
  with the concrete reason (for example "the file now also provides unique_b"). The journal record is closed for
  the real "not moved" state, and the original content is never lost or overwritten.
- The same snapshot check also guards the identical-copy de-duplication path, so a changed copy is kept there too.
- An unchanged original is still moved to the backup (covered by reverse cases); auto de-duplication was not
  switched off to avoid the problem.

### Boundaries

- The window between the final check and `Files.move` is not claimed to be atomic against arbitrary external
  writers; it still exists and is documented. This release fixes the case where the change happened **before**
  the final check and was accepted because the comparison baseline was read too late and only checked a subset
  of the identity.
- `VersionRange`, the download algorithm and defaults, the source policy, the progress layout, the
  `FileInstaller` transaction, the icon and the licences are unchanged.

## 0.0.10-alpha.8

This release closes the remaining **safety conditions of the duplicate file clean-up**, on top of
0.0.10-alpha.7. The alpha.7 and earlier changes are kept below.

### Version requirements are bound to a specific modid (A7R-01)

- alpha.7 compared a requirement against the version of **any** provider: with `aaa` required at `9.0`,
  a native `aaa 1.0` and another mod's unrelated `bbb 9.0`, the range looked satisfied and the original was moved.
- Each requirement is now checked only against files that really provide that modid, using the version
  **of that modid**: a multi-modid jar (primary `aaa=9.0`, secondary `bbb=1.0`) can no longer lend its
  primary version to a secondary modid.
- The scanner records a version for every modid (all `[[mods]]` blocks, plus the Fabric/Quilt metadata).
  **An unconfirmed secondary version counts as unverified and keeps the original**; unknown is never treated
  as satisfied. The `VersionRange` comparison itself is unchanged.
- Genuinely redundant files are still retired, covered by reverse cases.

### The current inventory and the providers are re-checked before any removal (A7R-02)

- alpha.7 decided from the **first scan**: if a provider disappeared or changed identity afterwards, the
  original was still moved and the modid could be left without an active file.
- The clean-up phase now **re-scans `mods/` and rebuilds the provider relations from the current inventory**
  (after replacements, dependency installs and the existing de-duplication), and then, immediately before
  **every move**, re-checks that each native file it relies on still exists, is readable, is still NeoForge,
  still provides the same modid and still reports the same version; the original itself is re-checked too
  (present, unchanged bytes, still provides its modids).
- If any check fails, **nothing is moved**: the original is kept and `unresolved-duplicate` is recorded with
  the reason. A vanished or re-identified provider is reported as well, never silently ignored.
- Journal and backup semantics are unchanged: recorded before and after the move, failures are never
  reported as success, and the recovery path does not regress.

### Conflicts are reported from the post-clean-up inventory (A7R-03)

- After successfully de-duplicating two identical natives, alpha.7 still reported from the **pre-clean-up**
  file list: a path that no longer existed hashed to nothing, counted as "different content", produced a
  `native-version-conflict` that did not exist and told the player that two files were kept for review.
- The conflict report is now built from a fresh scan taken **after every removal**; a file whose hash cannot
  be read is recorded as **unknown / read failure** and is no longer treated as proven-different.
- The conflict count is de-duplicated per **real active file**: one file involved in two modids counts once.
- Two natives with genuinely different content are still both kept and reported; no "highest version wins".

### The blacklist also protects the provider side (A7R-04)

- alpha.7 only checked whether the file being removed was blacklisted, never the **provider**: a native
  provider in the blacklist still authorised moving another file.
- A protected provider can no longer authorise a clean-up on its own: if no other usable provider covers
  every modid of the file once it is excluded, the original is kept and the reason is written out. A provider
  is protected when its primary modid or any of its modids is listed.
- Only when another usable provider still covers everything is the file retired as before, and a blacklisted
  original is never touched.

### The report now carries the installed identity and the backup path (A7-04)

- The `replaced` record keeps its original fields (which describe the **original file**) and adds
  `installedFile`, `installedKind`, `installedVersion`, `installedModIds`, `installedPath` and
  `backupPath`; the log separates the original identity, the source answer, the installed identity and the
  backup location as well.
- Fields are only **added**; the meaning of the existing ones is unchanged, so existing consumers keep working.

## 0.0.10-alpha.7

This release fixes **early loading text running off the window** and makes **duplicate mod files handled safely**,
on top of 0.0.10-alpha.6. **The content is cumulative**: the alpha.6, alpha.5, alpha.4, alpha.3 and 0.0.10 changes
are kept below.

### The stage line no longer runs off the window (A7-01)

- `ScreenProgress` only capped the **file rows** at 46 characters; the stage line appended the stage name, the
  counts and the latest status message with **no limit at all**. A real alpha.6 line measured 87 characters,
  which is **1044 px** in a window that is only 854 px wide.
- The budget is now derived from the loader's **real font and real drawing code**: Monocraft at size 24
  (ASCII 32-126) measures **12 px per character**, the progress bar is 400 px wide and starts at x=227,
  so a single line is capped at **500 px / 41 characters**.
- Stage titles are short: `Translating Fabric (3/15)`, `Translating Forge (3/15)`,
  `Preparing Fabric support (1/2)`, `Installing dependencies (12/40)`; **counts and the stage category are
  never lost to truncation**.
- **While a file row is visible the stage line does not repeat the file name**, and a new download clears the
  previous status immediately, so a finished file's `Translated ...` can no longer look like the current one.
- Status text is shown only when it fits; **full names and details stay in the log**. File rows follow the same
  41-character budget (the old 46 was never checked against pixels).

### Reusing an existing target still retires the original (A7-02)

- `FileInstaller.install()` used to return "reused" as soon as the target already held the same content,
  **without moving the original out of `mods/`**: the replacement was reported as successful while both the
  Fabric original and the native target stayed installed.
- Reuse now runs the full transaction: the journal record is written first, the original is moved to the
  backup (or to the temporary rollback file), the target is re-checked, and only then is the staged copy
  removed and the transaction closed.
- A failed re-check restores the original and reports failure; a failed restore **keeps the journal record and
  the recoverable copy** for the next start. The temporary rollback file is only deleted after the transaction
  completed and the target was verified.
- `installNew()` (dependency installs, no replaced original) keeps its original semantics.

### Same modid on a native and a non-native file (A7-03)

- Previously any Fabric/Forge/Quilt file sharing **any** modid with a native file was silently skipped and kept.
- A documented decision table now decides (see
  `docs/CODE-NOTES/0.0.10-alpha.7-阶段文本预算与重复文件处理-设计说明.md`):
  - a file is moved to the backup (journal + move, never deleted) **only when it is provably redundant**:
    every modid it provides has a real, scanned native provider and every known version requirement is met;
  - it is **kept** - and the log and report say **unresolved conflict** plus the reason - when it provides a
    modid no native file provides, when a declared version requirement is not met or cannot be compared,
    when it is blacklisted/skipped, or when `duplicates.removeRedundant=false`;
  - two **different** native files sharing a modid are both kept and reported as `native-version-conflict`
    with full paths, loader and version - the biggest version is **not** picked as the winner;
  - identical native copies still use the existing safe cleanup path (`removed-duplicate`).
- The real inventory is re-scanned after the cleanup and a failed cleanup is never reported as success.
- When the user has to decide, the log lists full paths, the real loader, the actual version, the modid and the reason.

### The log separates names from real identity (A7-04)

- `forge.keepFileName=true` keeps a Fabric file name on a NeoForge build, so the log and the report now
  separate: the **original file name and scanned version**, the **source-returned file name and version**,
  the **actual install path**, the **re-scanned loader/modid/version**, and the **backup path**.
- The `forge.keepFileName` default is unchanged and no forced renames were added. The origin of the user's
  second AppleSkin file is still **not determined**, and no document claims the mod created it.

### Version and identity

- Version `0.0.10-alpha.7`; the User-Agent is
  `ModRelay/0.0.10-alpha.7 (+https://github.com/WHanG2237/NeoForbric-Downloader)`.
- No internal identifier changed: modid `neoforbric`, packages, namespace, `config/neoforbric`,
  `mods/.neoforbric`, backup and journal paths, and the `neoforbric-<version>.jar` artifact rule.
- The download defaults are unchanged: **one file / ranges off / at most two ranges when enabled / a 4+4 budget**.
- This round did **not** restore CurseForge, enable or register MCIM, change the icon, publish, or run the game.

## 0.0.10-alpha.6

This release replaces the **version comparison with the same item model the loader uses**, on top of
0.0.10-alpha.5. **The content is cumulative**: the alpha.5, alpha.4, alpha.3 and 0.0.10 changes are kept
below.

### Qualified versions with a different numeric body length are no longer misjudged

- The alpha.5 comparator flattened a version into one **token stream** (`1.0.0-rc1` -> `1/0/0/rc/1`)
  and compared it position by position. That threw away the fact that everything after `-` is a
  **sublist**: when the numeric bodies have different lengths, the third `0` was compared directly
  against `rc` and decided the question **before the rc number was ever reached**:
  - `1.0.0-rc1` against `[1.0-rc2,1.0)` was judged **satisfied**, so an unsatisfied dependency was
    really installed and reported as verified (**wrongly accepted**);
  - `1.0-rc2` against `[1.0.0-rc1,1.0)` and `1.0.0-final` against `[1.0-final]` were judged
    **not satisfied** (**wrongly refused**).
  Both `1.0` and `1.0.0` are common in real mods, so this was not a theoretical edge case.
- Comparison now follows the **same item model as the loader** (Maven `ComparableVersion`, which is what
  NeoForge uses):
  - **Only `.` and `-` carry structure**: `-` opens a new sublist, while `_`, `+` and other
    characters are part of the qualifier text itself (so `1.0+build` > `1.0`, matching the loader);
  - **Normalisation drops trailing empty items** (numeric `0`, empty qualifier, empty sublist), so
    `1.0` = `1.0.0` = `1.0.0.0`, `1.0-rc1` = `1.0.0-rc1` = `1.0.0.0.0.0-rc1` and
    `1.0-final` = `1.0`;
  - **Ranks**: alpha < beta < milestone < rc < snapshot < release < sp < unknown; `ga`, `final` and
    `release` equal a release, `cr` equals `rc`, and the single letters `a`/`b`/`m` equal
    alpha/beta/milestone **only when a digit follows directly** (`1.0-a1` = `1.0-alpha1`).
- **One deliberate divergence remains**: the loader orders two **different** unknown qualifiers by
  dictionary order; this project returns "cannot tell" instead of turning that into a definite verdict.
  The same unknown qualifier on both sides still compares normally, and an unknown qualifier against a
  known qualifier or against a plain release is still decided.
- "Cannot tell" still **installs but is marked unverified**: the report never says verified, and
  installation and verification remain two separate dimensions.

### Cross-checks and boundaries

- Cross-checked against the Maven 3.8.5 comparator already on this machine: **all 3721 ordered pairs of
  61 versions agree**, plus **55** version/range verdicts; Maven is **not** shipped inside the product.
- Real install cases: `1.0.0-rc1` is **not installed** under `[1.0-rc2,1.0)` and is reported
  `not satisfied`; `1.0-rc2` installs and is verified under `[1.0.0-rc1,1.0)`; `1.0.0-final`
  installs and is verified under `[1.0-final]`; `1.0.0-foo` installs under `[1.0-bar,2.0)` but is
  reported `version range unverified`.
- Still **not** a full version solver; the download scheduling, candidate order, download defaults
  (one file / ranges off / at most two ranges / a 4+4 request budget), the refused-source state, the
  report wording and the file protection were not touched this round.

### Version and identity

- Version `0.0.10-alpha.6`; the User-Agent is
  `ModRelay/0.0.10-alpha.6 (+https://github.com/WHanG2237/NeoForbric-Downloader)`.
- No internal identifier changed: modid `neoforbric`, packages, resource namespace,
  `config/neoforbric`, `mods/.neoforbric`, backup and journal paths, and the artifact name rule
  `neoforbric-<version>.jar`; old configs, backups and journals stay compatible.
- The public README still **introduces the mod only**: installation and upgrading refer to
  `neoforbric-<version>.jar` and the latest Release.
- This round did **not** restore CurseForge, **did not** enable or register MCIM, **did not** change the
  icon and did **not** publish anything.

## 0.0.10-alpha.5

This release fixes **version qualifier comparison** on top of 0.0.10-alpha.4. **The content is
cumulative**: the alpha.4, alpha.3 and 0.0.10 changes are kept below.

### Common qualifier versions are no longer misjudged

- The strict comparator added in 0.0.10-alpha.4 fixed multi-range requirements but still produced
  wrong verdicts for common qualified versions:
  1.0-sp1 (service pack) was judged **lower** than 1.0, so a legal `[1.0,)` was refused;
  the release aliases 1.0-final / 1.0-ga / 1.0-release did not compare equal to 1.0, so `[1.0]` was
  refused; and 1.0-rc10 vs 1.0-rc2 fell back to string order, so 1.0-rc2 was **wrongly accepted** into
  `[1.0-rc10,1.0)` and reported as verified.
- What changed:
  - **Qualifiers and numbers are separated**: tokens now split at every letter/digit boundary, so
    `rc10` is `rc` + `10` and `sp1` is `sp` + `1`; rc10 > rc2 and alpha10 > alpha2 are compared
    **numerically**, never as strings.
  - **Release aliases are equivalent**: `ga`, `final` and `release` share the "no suffix" rank, so
    `1.0-final` equals `1.0`.
  - **`sp` and unknown qualifiers rank above a release**: `1.0-sp1` > `1.0`, matching the loader.
  - **Two different unknown qualifiers are no longer ordered by dictionary order**: that case returns
    "cannot tell" instead of a definite satisfied/unsatisfied verdict; the same unknown qualifier on
    both sides still compares normally.
- **"Cannot tell" still installs but is marked unverified**, exactly as in alpha.3/alpha.4; installation
  and verification remain two separate dimensions.
- This round cross-checked **46** version/range pairs against the Maven comparator already on this
  machine, **all in agreement**; real install cases confirm `1.0-sp1` installs under `[1.0,)` and is
  reported verified, while `1.0-rc2` is refused under `[1.0-rc10,1.0)` and is never reported verified.
  > Corrected in alpha.6: those 46 pairs did **not** cover "different numeric body length plus a
  > qualifier" (for example `1.0.0-rc1` against `1.0-rc2`). That boundary was only fixed in alpha.6,
  > by moving to the loader item model described above.
- Still **no new runtime dependency**; neither the outer jar nor the embedded body jar contains Maven
  or `commons-lang3` classes.

### Version and identity

- Version `0.0.10-alpha.5`; the User-Agent is
  `ModRelay/0.0.10-alpha.5 (+https://github.com/WHanG2237/NeoForbric-Downloader)`.
- No internal identifier changed: modid `neoforbric`, packages, resource namespace,
  `config/neoforbric`, `mods/.neoforbric`, backup and journal paths, and the artifact name rule
  `neoforbric-<version>.jar`; old configs, backups and journals stay compatible.
- The public README now **introduces the mod only**: installation and upgrading refer to
  `neoforbric-<version>.jar` and the latest Release, with no hard-coded alpha version.
- The download defaults are unchanged: **one file / ranges off / at most two ranges when enabled /
  a 4+4 request budget**.
- This round did **not** restore CurseForge, **did not** enable or register MCIM, **did not** change
  the icon and **did not** publish.

## 0.0.10-alpha.4

This release fixes two version-range defects on top of 0.0.10-alpha.3 and makes the report wording
consistent. **The content is cumulative**: the 0.0.10-alpha.3 and 0.0.10 changes are kept below.

### A legal multi-range requirement is no longer refused

- The range check used to split on the **first comma only**, so a union such as `(,1.0],[1.2,)`
  was compared as a single range and an obviously valid version like 1.5.0 was judged
  **definitely unsatisfied** and refused. Since 0.0.10-alpha.3 that judgement gates new dependency
  installs, so the impact was real.
- Ranges are now parsed with the semantics the loader uses: `restriction(,restriction)*`, where a
  restriction may be `[a,b)`, `[a,b]`, `(a,b)`, `(,b]`, `[a,)` or `[v]` (exact), and
  **several comma-separated restrictions are a union** (matching any of them satisfies the range).
- **Anything not supported now returns "cannot tell"** instead of a definite verdict:
  `>=1.0.0`, `*`, a bare version, unbalanced brackets, more than one comma inside one restriction,
  or a bound that is not a version. Those still install but are explicitly marked **unverified**,
  exactly as in 0.0.10-alpha.3.
- Version ordering is now a **strict version order** (numeric parts plus the qualifier order
  alpha/beta/milestone/rc/snapshot/release/sp). The loose normaliser written for cross-loader
  **name** matching is no longer reused for range comparison; the two answer different questions.
- No new runtime dependency was added: the product carries no Maven and no other range framework.
  The tests cross-check against the Maven comparator already present on this machine and assert that
  neither the outer jar nor the embedded body jar contains `org/apache/maven` or `commons-lang3`.
- This is still **not** a version solver: conflicting requirements between parents are not resolved
  automatically, and an installed-but-unsatisfying dependency is still reported rather than replaced.

### "Cannot tell" no longer turns into "verified" in the report

- One install used to report two different things: the log said `installing it unverified`, the notes
  said unverified, and the final structured report still said `(verified against …)`, simply because
  the file had been installed.
- The structured report now carries the **actual verdict** and lists every parent's outcome:
  `a-parent.jar needs [1.0,2.0) -> satisfied`, `b-parent.jar needs >=1.0.0 -> unverified`,
  `c-parent.jar needs [2.5,3.0) -> not satisfied`.
- The report always states the nature of the result: `version range verified`,
  `version range unverified` or `version range not satisfied`. **An unverified install never says
  "verified" again.**
- Installation and verification are **two separate dimensions**: a successful install still counts as
  an install, but it does not mean the version constraint was verified; a refused install is not
  counted and leaves no new file behind.

### Version and identity

- Version `0.0.10-alpha.4`; the User-Agent is
  `ModRelay/0.0.10-alpha.4 (+https://github.com/WHanG2237/NeoForbric-Downloader)`.
- No internal identifier changed: modid `neoforbric`, packages, resource namespace,
  `config/neoforbric`, `mods/.neoforbric`, backup and journal paths, and the artifact name rule
  `neoforbric-<version>.jar`; old configs, backups and journals stay compatible.
- **Upgrading**: delete the old `neoforbric-0.0.9.jar` or older candidate JAR from `mods/` first,
  then add `neoforbric-0.0.10-alpha.4.jar`.
- This round did **not** restore CurseForge, **did not** enable or register MCIM, **did not** change
  the icon and **did not** publish. The download defaults stay **one file / ranges off / at most two
  ranges when enabled / a 4+4 request budget**.

## 0.0.10-alpha.3

This release fixes four defects on top of 0.0.10 and changes the download defaults. **The content is cumulative**: the 0.0.10 changes are kept below.

### Dependency provisioning now checks version ranges (D1)

- Previously the declared version range was only checked when the dependency was **already installed**. When it was missing, the mod resolved, downloaded and installed it after checking only the loader type and the modid.
- A parent asking for `[1.0,2.0)` therefore got 2.0.0 installed and reported as satisfied, while the game still failed to load.
- The mod now checks the downloaded file's actual modid, actual version, the parent's `versionRange`, the declared side and the NeoForge type **before** committing it.
- A range that is provably unsatisfied **refuses the install** and keeps the original files. The log and report state which parent needs it, the required range, the version found and why it was refused, and the install is not counted as satisfied.
- A range the mod cannot parse (for example the `>=1.0.0` style common in Fabric metadata) is installed but explicitly marked **unverified**; it never claims a verified install, and this deliberately avoids breaking normal automatic provisioning.
- The mod still **never** replaces a dependency the player already has, even when its version does not match; it reports.

### One dependency required by several parents is no longer skipped (D2)

- The "process each modid once" latch used to sit in front of the version check: with parent A requiring `[1.0,2.0)`, parent B requiring `[2.0,3.0)` and 1.5.0 on disk, checking A skipped B entirely and the conflict was never reported.
- Network downloads are still deduplicated by modid (one dependency is downloaded once), but **every parent's version constraint is now checked and recorded**.
- The constraints of a whole batch of parents are collected **before** anything is downloaded, so a later parent can **block** a version it does not accept instead of only reporting it afterwards.
- No automatic conflict resolution is attempted: contradictory ranges are reported honestly, never silently swapped.

### A refused source is no longer stored as "no build" (S1)

- A mirror endpoint refused by policy used to disappear from the query chain. With the official source also off, the chain was empty and "this configuration may not query" was written as `no NeoForge build for 1.21.1` and entered the negative cache.
- A refused endpoint now keeps a **structured state** (`RESTRICTED`), and having no enabled source at all is `CONFIG_ERROR`. Neither pretends to be `NOT_FOUND`, and the log explains the reason and how to recover.
- As long as a source that **can** answer exists, its "there really is no such build" stays the answer and is still cached for the TTL. Refused sources send no request and do not affect the conclusion, so fixing the configuration re-queries.
- Removed services (CurseForge hosts) are still refused in every slot, and MCIM is still refused unless both switches are on.

### Ranges and a single stream now share one source order

- Segmented downloads used to run on the raw address **before** the candidate list was consulted, so a single stream took the preferred alternate address (such as `cdn-raw.modrinth.com`) while ranges were pinned to `cdn.modrinth.com`; on the test machine the latter ran at about 4-6 KiB/s.
- Ranges now run **inside the candidate loop** and use the same candidate as a single stream: one range attempt on the current address, a fallback to one stream on that **same** address, and only then the next address.
- Cancellation and an exhausted total deadline still stop immediately, with no fallback and no retry past the deadline.
- The source logged is the address that was **actually used**, so "log says official, traffic went to a mirror" can no longer happen.

### Download defaults moved to the steady configuration

| Setting | New default | Meaning |
| --- | --- | --- |
| `http.parallelism` | `1` | One file at a time |
| `http.segmented.enable` | `false` | Ranges off |
| `http.segmented.maxSegments` | `2` | Even when ranges are enabled, start at two |
| `http.maxActiveRequests` | `4` | Shared request limit |
| `http.maxRequestsPerHost` | `4` | Per-host request limit |

- The basis is a controlled comparison on the test machine where **two runs of the same mode differed far more than the modes differed from each other** (the same single stream ran at 82 KB/s once and 2.89 MiB/s another time), and fetching two large files at once slowed both noticeably.
- Ranges **still work**: once enabled they are no longer pinned to a slow address. Users who want more can raise the file limit or enable ranges.
- An existing custom value in a config file is never overwritten silently; only the "apply recommended download settings on save" action writes the recommended values.
- Template comments, the config screen, both language files, the README and the test fixtures were updated with the new defaults.

### Version and identity

- Version `0.0.10-alpha.3`; the User-Agent is
  `ModRelay/0.0.10-alpha.3 (+https://github.com/WHanG2237/NeoForbric-Downloader)`.
- No internal identifier changed: modid `neoforbric`, packages, resource namespace, `config/neoforbric`, `mods/.neoforbric`, backup and journal paths, and the artifact name rule `neoforbric-<version>.jar`.
- **Upgrading**: delete the old `neoforbric-0.0.9.jar` or older candidate JAR from `mods/` first, then add `neoforbric-0.0.10-alpha.3.jar`.
- This round did **not** restore CurseForge, **did not** enable or register MCIM, **did not** change the icon and **did not** publish.

## 0.0.10

### Name and User-Agent (the outward-facing change of this round)

- The player-facing name changed from **NeoForbric** to **ModRelay** (Chinese 「**模组接力**」); **formerly NeoForbric**. The in-game screen title is `ModRelay settings` (Chinese `ModRelay（模组接力）设置`), and the settings text says "formerly NeoForbric". The name was chosen because it describes the actual function and is less likely to be mistaken for a loader or a real cross-language translator; it is **not** because any platform prohibits the old name.
- **No internal identifier changed**: modid `neoforbric`, Java packages `com.deepseek.neoforbric.*`, resource namespace `assets/neoforbric`, language keys `neoforbric.config.*`, `config/neoforbric/` and `mods/.neoforbric/` (including `backup/` and `journal/`), `logs/neoforbric.log` and `logs/neoforbric-report.json`, and the artifact file name `neoforbric-<version>.jar`. This keeps configs, backups, and journals from older versions recognized, so one instance never ends up with two copies of the mod.
- **Upgrade note**: delete the old `neoforbric-0.0.9.jar` from `mods/` first, then add `neoforbric-0.0.10.jar`; two files with the same modid are treated as duplicates.
- The display name has a single source: `mod_display_name` in `gradle.properties` → build metadata `display_name` → `displayName` in `neoforge.mods.toml` and the startup banner; the code reads it from build info, so the name is no longer hard-coded in several places.
- **One User-Agent for every request**: `ModRelay/<build version> (+https://github.com/WHanG2237/NeoForbric-Downloader)`, with the version taken from build metadata. The old value's `transpiler` wording is gone. **The User-Agent is not platform authorization, an API key, or proof of registration.**
- The repository is **not being renamed for now** and stays `WHanG2237/NeoForbric-Downloader`.

### Download sources (the largest change in this round)

- **CurseForge support removed entirely**: queries, downloads, fingerprint lookups, slug search, and CDN address construction are gone, together with the "bring your own API key" mode. `source.curseforge.*` settings no longer exist. No hidden switch, anonymous proxy, or fallback path remains, and an address pointing at a CurseForge host (`curseforge.com`, `forgecdn.net`, `curseforge.gg`) is **refused with an explanation**.
- **The official Modrinth API** (`https://api.modrinth.com/v2`) is the default and the only real source built into this project.
- **Third-party Modrinth endpoints are kept but off by default.** The built-in default value is the community mirror **MCIM** (`mod.mcimirror.top/modrinth/v2`), and it is contacted only when **both** `mirror.thirdparty.modrinth.enable=true` **and** `source.modrinth.mirror.enable=true` are set by hand; a leftover mirror switch in an older config does not enable it.
- When MCIM is used, the mod writes an explicit warning that **names `mod.mcimirror.top`** and states that this project's registration and access scope with that service are still being confirmed and its terms are not confirmed here; the service is never hidden behind the generic label "alternate source". **Until registration and access scope are confirmed it is not enabled by default and no request is sent to the real service.**
- Download addresses are no longer guessed: when the official API returns no usable download address, or a project explicitly sets `allowModDistribution=false`, the file is not downloaded and no CDN address is built from the id and name. A missing field is not treated as permission.
- "Distribution restricted or no address", "configuration error", "temporary failure", and "definitively no suitable build" are distinguished. A restricted result is never stored as a permanent "not found" and the remaining sources can still be queried.
- This project is published on GitHub only and is **not uploaded to Modrinth**: the publishing platform and the runtime download source are two different things, and publishing on GitHub does not mean the Modrinth API terms were confirmed clause by clause.

### Safe migration of an existing config

- Keys in an older config that point at **removed sources** (`source.curseforge.enable/api/apiKey`) are **ignored and commented out**; CurseForge is never contacted again, and no key value is ever written to logs, reports, or screenshots.
- The third-party Modrinth mirror keys **remain recognized**, but `mirror.thirdparty.modrinth.enable` and `source.modrinth.mirror.enable` **both stay off by default**; switch states in an older config are not treated as consent, and an old mirror address is not contacted while the switches are off.
- Deprecated keys produce **one** migration message; nothing bulk-deletes the user's directory, and an old key is never copied into the new config or the report.
- A **CurseForge numeric project ID in an old mapping is never mistaken for a Modrinth ID**: the program does not guess and does not silently delete user mappings; it prints an actionable message and leaves the decision to the user.
- Old decision caches and download/resume caches are not reused under the old sources: results whose origin cannot be established must be re-confirmed through the remaining source, and the negative-cache environment fingerprint is updated so old results cannot block a new legitimate query.
- Mods that are already installed and complete are not deleted merely because the source list changed.
- **Reduced functionality, stated honestly**: mods published only on CurseForge may no longer be replaced automatically; users can download them from a legitimate source and place them in `mods/`. Fetching Connector / FFAPI for the first time still requires one restart.

### Startup progress

- Stage counters advance as files **really** finish: a slow download at the front no longer holds back tasks that already completed.
- The progress row now puts the **percentage first**, shortens the prefix, and abbreviates the file name to the visible width, so the percentage is readable at 854×480 even for long names.
- With two concurrent files, the **stage completion count is shown inside the file row**, so file rows can no longer push the stage bar off the display.
- Each downloading file occupies exactly one row, and all ranges of one file are merged into that file's progress. The row is **removed immediately** when the file finishes, fails, or is cancelled; nothing is left behind on stage changes or at the end of the round.
- The loader's own `EARLY` startup bar is no longer presented as a single-file percentage, and the last two notification messages are no longer used as a file list.
- The "translating" theme text is kept. Speeds are not shown in the interface.

### Download defaults and configuration

- New configs default (from **0.0.10-alpha.3**) to **one** file at a time, ranges **off**, at most **two** ranges per file, and **four** total and per-host requests (4/4). Early 0.0.10 candidates defaulted to 2 files, ranges on, 4 ranges and 8/8.
- Concurrent files support 1; ranges support 2-8. Custom values in an existing config are not overwritten silently.
- The config screen adds "Apply recommended downloads on save": it writes the recommended values for that save only, a cancelled screen writes nothing, and other settings are kept.
- New `progress.files` (one row per file, on by default) and `debug.progress` (periodic diagnostics, off by default).

### Download accounting

- Every finished download, successful or not, writes one summary: file, result, bytes, whole-operation duration, average effective speed, attempts, source, single-stream or ranges, and the failure reason.
- Byte counts are reported separately: `receivedBytes` (actually received in this operation) / `reusedBytes` (a resumed part) / `verifiedBytes` (size of the file after verification) / `retainedBytes` (partial data left on disk after a failure or cancellation).
- **A failure's speed uses the actually received bytes as its numerator**, never the expected full size and never the bytes left after cleanup. On success the average effective speed is the verified file size divided by the whole download operation, including queueing, probing, retries, fallback, and merging.
- Reported speeds are never NaN, infinite, or negative. `debug.progress` adds one entry about every five seconds instead of one per block.

### Component status

- The Connector / FFAPI result now distinguishes three states: **"already in place", "genuinely not needed", and "needed but unobtainable"**. A temporary failure or a missing target is never recorded as "not needed" or "kept".
- Each task is counted exactly once and is never credited twice.

### Public documentation

- Corrected the packaged mod description and both READMEs: removed "universal" and "mixed loaders just start" claims, and stated plainly that this queries, downloads, verifies, backs up, and replaces files, depends on an upstream compatibility layer, and cannot make every mod work.
- Added a third-party component and service list, and an explicit description of what the mod does to an instance (modifies `mods/`, backs up by default, may restart the game, and keeping the original after a failure does not mean the game will start).
- Added a "publishing platform vs runtime source" section and an "author and AI contribution" section: `蓝色大肥鱼` is DeepSeek's project signature (not the owner's legal name), and AI development is disclosed honestly. ~~The icon was created by the project owner, who agreed to MIT.~~ — **that record was corrected in the final round**; see "Icon: corrected record" below.
- The wording of the `LICENSE` copyright line, the form of the `authors` entry, and whether any platform or service registration is required (including MCIM) are listed as pending human confirmation.
- Both READMEs and the packaged description now use **ModRelay** and state "formerly NeoForbric" prominently; a "rename and upgrade" section records the identifiers that did **not** change and the need to delete the old jar before upgrading.
- The public documentation now quotes the User-Agent and states that it is not authorization, an API key, or proof of registration, and records that MCIM is a **default-off** third-party endpoint whose registration status is unconfirmed.

### Unified endpoint policy (independent review R1)

- Every configurable source entry point — the official slot (`source.modrinth.official.api`), the mirror slot, and the Connector / Forgified Fabric API prerequisite and component queries — now goes through **one** classification and validation path, decided before any request exists. The gap where only the mirror slot was checked is gone.
- Putting the known third-party MCIM mirror in the official slot **no longer bypasses** the two switches: with either switch off it is refused with an actionable message, no request is sent, no config file is written, and it is **never** labelled official. With both switches on it may be used, but it is labelled `Modrinth third-party mirror (MCIM)` and the host is named in the log.
- Removed CurseForge addresses (`curseforge.com`, `forgecdn.net`, `curseforge.gg` and their subdomains) are refused in **every** slot, including the component/prerequisite path.
- Ordinary custom endpoints, including local fake-server addresses, still work; nothing was weakened. This project does not claim to recognise every unknown proxy.
- A refusal never becomes a permanent "does not exist" record, so it cannot block a later re-check after the address is corrected.
- **Boundary stated plainly**: what is validated is the endpoint written in the config. Download candidate URLs reported by the API, HTTP redirect targets, and DNS are separate boundaries; no "zero access on every hop" claim is made.

### Full endpoint identity in the negative-cache fingerprint (independent review R2)

- The mirror endpoint's full identity, **including its path**, now enters the environment fingerprint: `/one/v2` and `/two/v2` on the same host are now **different** fingerprints, so changing the path forces a re-check instead of reusing the old negative result until the TTL expires.
- Sensitive parts (query parameter values and userinfo) are hashed in memory (first 12 hex digits of SHA-256) before entering the fingerprint; no plaintext appears in the log or the state file. Parameter *names* stay in clear, so different query semantics remain distinguishable.
- Equivalent spellings are normalised: default ports, trailing slashes, scheme and host case. The same configuration yields the same fingerprint twice and does not churn.
- The marker was bumped from `decisions-v4` to **`decisions-v5`**, invalidating stale environments. TTL, temporary-failure, and configuration-error semantics are unchanged.

### The download failure summary keeps the concrete cause (independent review R5)

- The summary line no longer uses a single overwritable `reason=`; it now carries two independent fields: `termination=` (why the operation stopped) and `lastFailure=` (the most recent attempt's failure category).
- Exhausting retries **no longer** overwrites a stall with `all N source(s) failed`. Categories distinguish cancellation, the total deadline, the per-request timeout, a stalled transfer, a length mismatch, a hash mismatch, HTTP status, and local write errors.
- A successful download no longer leaves a stale failure state behind.
- Byte accounting is unchanged: `receivedBytes = streamBytes + segmentBytes`, the failure numerator is still the amount actually received, and `reusedBytes` / `verifiedBytes` / `retainedBytes` plus the `basis=` speed definition are as before. Cancellation, both deadlines, file protection and segmented cleanup were not weakened.

### Licence and attribution material (independent review R3)

- Added `THIRD-PARTY.md` / `THIRD-PARTY.en.md`, `licenses/Apache-2.0.txt`, and `licenses/gradle-NOTICE.txt`.
- **The Gradle Wrapper's provenance was verified byte by byte**: `gradle/wrapper/gradle-wrapper.jar` is **byte-identical** to the file of the same name at the official `gradle/gradle` repository tag `v8.1.1` (62,076 bytes / `ed2c26eba7cfb93cc2b7785d05e534f07b5b48b5e7fc941921cd098628abca58`); `gradlew` and `gradlew.bat` match `v8.1.1` apart from one `DEFAULT_JVM_OPTS` line, with the original copyright headers and Apache-2.0 notice **kept verbatim**. They are Apache-2.0 third-party content and are **no longer listed alongside first-party code**.
- Licence and attribution material now travels **with the actual distribution**: both the outer JAR and the nested body JAR contain their own `META-INF/licenses/`, rather than the material living only in the repository or the maintenance docs.

### Icon: corrected record (important)

- The mod icon used to be recorded as "created by the project owner, MIT". On inspection the image contains a **third-party character design** and **Minecraft-style elements**, so the whole image **cannot** be distributed under MIT and **cannot** be recorded as a confirmed CC-licensed work.
- The project owner's actual contribution is selecting, combining, and post-processing the material; this release does **not** deny that contribution or count it as third-party material.
- Version **0.0.10 is therefore not being published**: no distribution containing that image will be released until the licence is settled. The full facts, the still-missing chain of permission, and a currently-unusable attribution template are in `ICON-LICENSE.md` at the repository root.

**Unchanged limits**: ranges do not guarantee better speed and cannot exceed total bandwidth; installing or enabling Connector / FFAPI for the first time still requires one restart, and dynamic mounting is not supported.

## 0.0.9

- Fix startup progress overflowing across stages with many mods.
- Keep unchanged files in the stage's final count; report Connector and FFAPI outcomes separately.
- Show download attempt and byte progress; ignore events from old stages and attempts.
- Add optional segmented file downloads, off by default. Concurrent files and ranges share global and per-host request limits.
- Share timeout and stall protection with single-stream downloads. Queueing, probes, ranges, and fallback consume the same total deadline. Error bodies retain a three-second cap.
- Fix leaked request capacity after interrupted waits, and stop cancelled downloads from falling back and continuing.
- Stop segment workers and clean temporary parts when a transfer ends.
- Validate the full Content-Range and content encoding; do not send weak ETags in If-Range. Invalid or compressed responses cannot be accepted as valid ranges.
- Simplify the bilingual READMEs and use standard path separators in source archives.

Segmenting does not guarantee better speed. Initial installation or activation of Connector / FFAPI still requires one restart; dynamic mounting is not supported.

## 0.0.8

- Rewrite configuration text in both languages.
- Improve target-file protection, duplicate detection across multiple mod IDs, negative-cache fingerprints, and interrupted replacement recovery.
- Keep the recovery journal when both placement and restoration fail.
- Make archive ordering and timestamps reproducible.

Use the checksum list attached to each Release to identify published binaries. Historical test counts and unpublished candidate hashes do not identify the final release.
