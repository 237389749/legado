# 墨水屏 Legado 改造 —— 迁移 / 重装交接

> 写于 2026-10-28。目的：换机器、重装系统后能在半小时内回到可继续开发的状态，
> 并且不丢失「为什么这么做」的判断依据。

## 重装前检查清单

- [ ] **整个 `C:\Users\root\Documents\eink` 目录**（`boot_patched_*.img`、`eink_waveform.wbf`、
      `ref.md`、`E-Ink-Launcher/` 里的波形与内核笔记，都不在本项目里，但都是这套判断的依据）
- [ ] `backup/` 三件套（源码 zip、patches、skill）
- [ ] `LEGADO-EINK.md`（设计文档）与 `RESTORE-EINK.md`（本文）
- [ ] `~/.dsh/skills/android-eink-ification/`（skill 已复制进 `backup/`，可不单独备）
- [ ] 可选：`~/.gradle/caches`（不备也行，重装后重新下载约 10 分钟）

设备端不需要备份：debug 包随时可重装，书源/书籍在设备里。

---

## 0. 三样东西分别在哪

| 内容 | 位置 | 备份形式 |
|---|---|---|
| 设计文档（方案 + 勘误 + 实测数据） | `LEGADO-EINK.md` | 纯文本，随工作区 |
| fork 代码 | `legado-eink/`（分支 `eink`，基座 `531f9fb`） | `backup/legado-eink-src.zip` + `backup/patches/` |
| 可复用 skill | `~/.dsh/skills/android-eink-ification/` | `backup/skills-android-eink-ification/` |

> **patches 已验证**：在临时目录用 `531f9fb` 起一个干净仓库、`git am` 打完 9 个补丁，
> 得到的 tree hash 与当前 HEAD 一致（`413235e7…`）。也就是说源码即使全丢，也能从
> 上游 + patches 精确重建。

> 源码 zip 是**不含 .git 和 build/** 的干净树（2831 个文件，31MB → 压后 10.6MB）。
> 历史用 patches 恢复；不想恢复历史的话，直接解压 zip 就能编译。

## 1. 恢复步骤

```powershell
# 1) 解压源码
Expand-Archive backup\legado-eink-src.zip -DestinationPath D:\src\legado-eink

# 2) 让 Gradle 找到 SDK
"D:\src\legado-eink\local.properties" | Out-Null
"sdk.dir=C\:\\Users\<你>\\AppData\\Local\\Android\\Sdk" | Out-File -Encoding ascii D:\src\legado-eink\local.properties

# 3) JDK 21（必须，不能用 25）
#    https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse
#    解压到 %USERPROFILE%\.jdks\jdk-21.x

# 4) 代理（Gradle 不读系统代理，必须写这个文件）
#    %USERPROFILE%\.gradle\gradle.properties
#    systemProp.http.proxyHost=127.0.0.1
#    systemProp.http.proxyPort=7890
#    systemProp.https.proxyHost=127.0.0.1
#    systemProp.https.proxyPort=7890

# 5) 构建
$env:JAVA_HOME="$env:USERPROFILE\.jdks\jdk-21.0.12.1+1"
cd D:\src\legado-eink
.\gradlew.bat :app:assembleAppDebug --no-daemon --console=plain
```

**要恢复 git 历史**（推荐，之后好 rebase 上游）：

```powershell
git clone https://github.com/LegadoTeam/legado.git legado-eink
cd legado-eink
git checkout -b eink 531f9fb
git am ..\backup\patches\*.patch
```

patches 是 9 个提交，顺序即文件名顺序；`0001` 是 minSdk 26，其余是 epd 模块与各处挂钩。

## 2. 环境坑（都踩过）

| 坑 | 现象 | 处理 |
|---|---|---|
| JDK 版本 | `Cannot find a Java installation ... toolchain 21` | 装 JDK 21，`JAVA_HOME` 指过去；JDK 25 不行 |
| Gradle 代理 | 依赖解析卡住十分钟无输出 | 写 `~/.gradle/gradle.properties` 的 `systemProp.*`，`GRADLE_OPTS` 不够 |
| 任务名 | `compileDebugKotlin` 报 ambiguous | 项目有 `app` flavor，用 `compileAppDebugKotlin` / `assembleAppDebug` |
| minSdk | `mergeAppDebugGlobalSynthetics` 报 "Increase the minSdkVersion to 26" | htmlunit-core-js 的 dex 约束，仓库里已提到 26 |
| 首次构建 | 约 10 分钟 | 下载 Gradle 发行版 + 全部依赖 |
| adb daemon | `cannot open ...\adb.log: Permission denied` | `$env:TEMP='<可写目录>'; $env:TMP=$env:TEMP` 后重启 adb |
| 截图损坏 | `exec-out screencap -p > x.png` 得到坏 PNG | 先落 `/sdcard` 再 `adb pull` |

## 3. 设备侧

- 测试机：BOOX Poke6，`adb` 序列号 `6C7F0E64`（另一台是故障机，见 `E-Ink-Launcher/HANDOFF.md`）
- debug 包名 `com.legado.app.debug`，可与官方 `com.legado.app.release` 共存
- 开启墨水屏模式：`themeMode` 偏好设为 `"3"`（或用 root 改 prefs，见下）

```bash
# 用 root 改偏好（应用需先 force-stop）
su -c "sed -i 's|<string name=\"themeMode\">0</string>|<string name=\"themeMode\">3</string>|' \
  /data/data/com.legado.app.debug/shared_prefs/com.legado.app.debug_preferences.xml"
```

Onyx 的刷新接口走反射（无 SDK 依赖），细节见
`skills-android-eink-ification/references/03-onyx-poke6.md`。

## 4. 现在做到哪了

**已验证（有截图/直方图/log 证据）**

| 项 | 证据 |
|---|---|
| epd 运行时装配、Onyx 反射可用 | `install vendor=ONYX ... backend=onyx available=true` |
| 列表一次性成型 | gate attached / hold / release 日志 |
| 文字统一纯黑 | clamp 日志 + 截图 |
| 帧缓冲量化到 2 级 | 整屏直方图 `distinct=2` |
| 对话框也量化 | `frame host installed: dialog`，主题弹窗黑白 |
| 单测 | 24 个，全过 |

**未验证（下一个接手的人先做这个）**

1. **真实阅读链路**：`EinkPageDelegate`、残影预算、跨章全刷，一次都没在真机翻过页。
   判断标准：翻一页只出 1 条波形；跨章出现一次 `clearGhosting -> true`。
2. **真实搜索 burst**：需要有书源。看一次搜索的 `gate hold/release` 次数是否 ≈ 1。
3. **封面在 2 级下的观感**：用户口径是「二值化可以接受」，但没留下截图。

**未做（想做的话）**

- 状态栏 / 导航栏量化（SystemUI，应用层做不到）
- 阅读页顶栏时钟改为翻页时更新（现在每分钟一次局部刷新，用户判断影响小，暂缓）
- 导入/刷新动画（原地 spinner）——频率低，暂缓
- 4 级 / 2 级切换开关（当前写死 2 级）

## 5. 关键决策（一句话版，细节在 LEGADO-EINK.md）

- **判据是帧完整性，不是性能**：硬件升级不会让「中间帧可见」消失。
- **2 级而非 4 级**：整屏只剩 0/255，次要文字变纯黑反而更清楚；封面网点化可接受。
- **不做「图片 4 级 / 文字 2 级」分区**：复杂度换来的收益是零。
- **不改主题（ThemeOverlay）**：会让 `BottomNavigationView` inflate 崩；改在视图层收敛。
- **基座选 `LegadoTeam/legado` 主分支**：唯一保留 View 版阅读器（`PageDelegate` 可加法扩展）。
- **改动面**：对上游只动 9 个文件、约 20 行；其余全在 `io.legado.app.epd` 包里。

## 6. 继续做的话，优先级

1. 真机翻 30 页，抓 `dmesg` 波形分布（验证 L1 与 L5）
2. 有书源后测一次搜索（验证 L2）
3. 再谈优化：`getPixels` 25ms 仍占大头，可试 `copyPixelsToBuffer` + `setPixels` 混合（注意 `copyPixelsFromBuffer` 不会让纹理失效）
