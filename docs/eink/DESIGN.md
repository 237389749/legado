# Legado 墨水屏特化：真正要解决的问题

> 2026-10-25 起。**看这一份就够**：原理 → 选型 → 落地 → 实测 → 勘误。
> 迁移/重装/交接看 [`RESTORE-EINK.md`](RESTORE-EINK.md)；可复用的方法论在
> `~/.dsh/skills/android-eink-ification/`（已备份到 `backup/skills-android-eink-ification/`）。
>
> 进展：`io.legado.app.epd` 模块已落地并装机验证（2 级量化、文字全黑、对话框也量化），
> 24 个单测通过。**唯一未验证的是真实阅读链路**（翻页/残影预算/跨章全刷），见 §10。

> 2026-10-25（第二版）。上一版把主线放在"性能/省电/砍功能"上，方向偏了——
> Poke6（SDM636 / 2+32 eMMC）已经能流畅跑普通版阅读，硬件还在往上走（天玑 8300 级墨水屏已出现），
> 用"跑不动"当理由的方案会随硬件失效。
> 本版改为从**显示介质的物理特性**推导，与 SoC 无关。

---

## 1. 判据：帧完整性（Frame Integrity）

LCD 上，屏幕是**连续的流**：任何中间帧都不可见，所以 App 可以自由地表达"过程"——
逐项出现、旋转加载、淡入淡出、跟手滑动。这些中间帧是免费的、不可见的、无需设计的。

EPD 上，屏幕是**离散的状态**：每一次提交都是一次物理事件，会被看见、会留下残影、
会占用几百毫秒。**没有"不可见的中间帧"这回事。**

> **原则：每一帧都必须是完整的、有意义的、可停留的画面。**
> LCD 上这条性质天然成立（你看不到中间帧）；EPD 上必须由软件强制。

这叫帧完整性。它不是性能指标——**即使 CPU 无限快、刷新零延迟，只要 App 还在表达"过程"，
屏幕上就会出现半成品画面**。这就是"割裂感"的物理来源。

### 五条推论

| # | 推论 | 反例（LCD 习惯） |
|---|---|---|
| 1 | **一屏一提交**：一次用户意图 = 一次 EPD 提交 | 搜索时每个结果 item 各自 invalidate |
| 2 | **无过渡，只有替换**：transition → replace | Activity 转场、共享元素、淡入 |
| 3 | **先就绪再呈现**（ready-then-show） | 先显示空列表，再逐项填 |
| 4 | **调色板量化**：渲染前降到 2~4 级，不靠面板抖动 | Material tonal palette、12% overlay、阴影 |
| 5 | **残影预算显式化**：同一区域重复刷新要计数、要偿还 | 顶栏时钟每分钟刷、spinner 原地转 |

### 为什么 SoC 升级不能解决

约束在**时间与物理**维度，不在算力维度：

- 一次提交的耗时由波形帧数决定（Poke6：GC16 38 帧、DU 22 帧、DU4 24 帧、A2 10 帧），CPU 再快也改不了；
- 灰阶与速度互斥（要 16 级灰阶就必须走会闪的 GC16；要快就只能 1~2bpp）；
- 残影是累积的物理量，与帧率无关，只与"同一区域被刷了多少次 × 对比度"有关。

更快的 SoC 反而**让做错更容易**：动画终于跑得动了，于是 App 会在 EPD 上以 60fps 提交 60 次刷新。
所以特化的价值不会随硬件消失，只会从"能不能跑"变成"该不该这样表达"。

---

## 2. 割裂感的具体来源（可逐条定位）

按"同一区域被重复刷新的次数"排序，这是残影风险的量化定义：
`ghost_risk ≈ Σ(重叠面积 × 对比度 × 次数)`。

| 现象 | 机制 | 残影风险 |
|---|---|---|
| 加载动画（spinner / 骨架屏 / 进度条） | 同一小块区域持续刷新 | **最高**：40×40dp 刷 60 次 |
| 搜索结果逐项涌现 | 每个 item 插入触发一次局部刷新 + item 动画 | 高 |
| 封面/图片渐进解码 | 同一矩形多次刷新（模糊→清晰） | 高 |
| 滚动列表（惯性/平滑） | 每个 MOVE 一次提交，且触发全屏 A2 | 高（Poke6 上直接是全屏 `update[1]`） |
| Activity 转场 / 共享元素 | 两次全屏 + 中间半透明帧 | 中高 |
| 文本重排（WebView reflow） | 整屏内容位移两次以上 | 中 |
| 对话框 dim | 全屏 alpha 叠加 = 全屏抖动 | 中 |
| ripple / elevation | 局部 alpha 混合，1bpp 下变噪点 | 中 |
| 顶栏时钟 | 每分钟一次局部刷新 | 低但持续 |

注意：**这些都不是"慢"**。列表逐项出现哪怕只用 200ms，屏幕上依然留下了 N 个半成品状态。

---

## 3. Legado 的现状：阅读页已经对了，错的是"浏览器式"界面

读源码的结论：

| 界面 | 是否满足帧完整性 | 依据 |
|---|---|---|
| 阅读页翻页 | ✅ 基本满足 | `NoAnimPageDelegate` 的 `onDraw/setBitmap/onAnimStop` 全空，只 `fillPage()` → 一页一次提交 |
| 阅读页顶栏时钟 | ❌ | `PageView.upTime()` / `upBattery()` 独立重绘 |
| 连续滚动模式 | ❌ | `ScrollPageDelegate` 的 `ACTION_MOVE` 每帧 `onScroll()` |
| 书架 | ❌ | RecyclerView 平滑滚动 + 封面异步解码 |
| 搜索/发现 | ❌ | 结果逐项插入 + 加载态 |
| 详情页 | ❌ | 简介 WebView 重排、评分/封面动画 |
| 目录/弹窗 | ❌ | 列表滚动、dim 过渡 |

**所以：上一版建议"砍掉搜索/详情/书源"是错的。** 它们不是性能包袱，恰恰是
帧完整性被破坏最严重的地方——应该被**重做呈现时序**，而不是删除。

---

## 4. 真正要做的四件事

### 4.1 提交合并（Commit Coalescing）

同一帧内多个 View 的 `invalidate()` 合并成一次 EPD 提交。

- 落点：`ReadBookActivity` / `BaseActivity` 的根布局包一层 `EinkRenderHost`，
  重写 `invalidate` 走 `Choreographer` 合并，一帧最多提交一次。
- 效果：解决"多个控件各自刷新"导致的多次物理刷新（典型：列表 + 图片 + 顶栏同时变）。
- 注意：这不是"降低刷新率"，是"把同一时刻的多个变化当成一个状态"。

### 4.2 整屏原子化的列表（Paged List）

核心不是换控件，是换呈现策略：

- 固定 item 高度（避免二次 measure/layout 导致的位置漂移）；
- 预取下一屏、图片先解码完成再统一贴（**ready-then-show**）；
- `ItemAnimator = null`，插入/删除不做位移动画；
- 一屏 N 项，整屏翻（对齐纸质"页"的隐喻）。

### 4.3 加载态的重新设计

spinner/骨架屏是为"让人知道在动"而存在的，但 EPD 的"动"本身就是噪声。

- 进入 → 立即提交一屏**静态**的"加载中"（一次提交）；
- 数据就绪 → 整屏替换（一次提交）；
- 结果集分页呈现，不逐项涌现；
- 进度如果必须存在，用"阶段文字"而非"移动的条"。

### 4.4 量化设计系统（Quantized Design System）

Material 的色板是给几百级灰阶的屏幕设计的。EPD 实际可用的是 2 级（DU）或 4 级（DU4）。

- 全局 token 重映射到 4 级：`白 / 浅灰 / 深灰 / 黑`；
- 禁止 alpha < 1（阴影、disabled、dim、ripple）；
- 阴影 → 1px 描边；elevation → 无；
- 渲染前做一次显式量化（而不是交给面板抖动）；
- 字重补偿：1bpp 下细笔画会断，小字号需要加粗。

### 4.5 残影预算（Ghost Budget）

- 阅读页：每 N 页全刷一次，章节切换全刷；
- 列表/搜索：每次进入全刷一次，滚动不刷；
- 对话框关闭后全刷（清 dim 残留）；
- 计数器与策略放 `RefreshGovernor`，设备差异放 `DeviceProfile`（Poke6：A2 只能全屏）。

---

## 5. 验收：按"一次交互的提交次数"算

| 交互 | 现在（普通版） | 目标 |
|---|---|---|
| 翻页 | 1 | 1 |
| 打开搜索 → 结果出现 | N+1（每项一次） | **1** |
| 打开书籍详情 | 3~5（封面/简介/评分） | **1–2** |
| 打开目录 | 多次 | **1** |
| 弹出菜单 | 2（dim + 内容） | **1** |
| 一次滚动 200px | 数十次（且触发全屏 A2） | **0**（不允许滚动） |

采集方式：`dmesg` 里 `waveform[*] update[*]` 的条目数，按操作切窗统计。

---

## 6. 产物形态：不是 fork，是一层可复用的运行时

`eink-ui`（可被任何 App 依赖的库）：

```
eink-ui
├── EinkRenderHost     // 提交合并，一帧一提交
├── PagedList          // 整屏原子化列表 + ready-then-show
├── EinkTheme          // 4 级量化 token，禁 alpha
├── GhostBudget        // 残影计数与全刷调度
├── DeviceProfile      // 设备档案（波形表、A2 是否全屏、灰阶数）
└── RefreshBackend     // Onyx 反射 / Hisense / Generic(no-op)
```

Legado 只是第一个宿主。理由：这个问题的普遍性远大于阅读——
工作区里的 E-Ink Launcher、任何 Android App 都踩同一组坑。做成层，收益可复用；
做成 fork，只能服务一本书。

---

## 7. 与上一版的差异（勘误）

| 上一版 | 本版 |
|---|---|
| 以"省电/性能"为主线 | 以"帧完整性"为主线，与 SoC 无关 |
| 建议砍掉搜索/详情/书源/WebView | 恰恰是重做重点：它们是割裂感最重的地方 |
| 重点在阅读页翻页 | 阅读页已经基本正确，重点在"浏览器式"界面 |
| 目标是"更省更快" | 目标是"任何时刻截屏都是一张完整画面" |

---

## 8. 基座选型：`LegadoTeam/legado` master

### 8.1 候选横评（2026-10-25 实测）

| 仓库 | 星 | 近 30 天提交 | 阅读器形态 | Compose 文件 | 结论 |
|---|---|---|---|---|---|
| **LegadoTeam/legado** | 1684 | 76 | **View + `PageDelegate`（7 个）** | **0** | ✅ 选它 |
| HapeLee/legado-with-MD3 | 6651 | ≥100 | **Compose 重写，无 PageView/Delegate** | 大量 | ❌ 无插槽 |
| huajideshutiao/legado | 313 | — | **KMP 迁移中**（commonMain/androidMain） | 部分 | ❌ 地基在动 |
| joestar817/legado_NG | 151 | ≥100 | Sigma 下游 | 部分 | ❌ 链路太深 |
| Rimchars/legado (Archive) | 640 | 9/24 停更 | 玻璃拟态 + AI | 大量 | ❌ 方向相反 |
| Luoyacheng/legado-E (Sigma) | 2962 | 8/01 停更 | 主题化 | — | ❌ 停更 |
| refgd/legado (R) | 317 | 6/21 停更 | Archive 下游 | — | ❌ 停更 |
| youfengknight/Legado_Max | — | 6/17 | 已声明停止维护 | — | ❌ |
| gedoor/legado（原版） | 47159 | 已归档删库（仓库 0.1MB） | — | — | ❌ |

星数衡量的是 UI 讨喜程度，不是引擎质量。MD3 有 6651 星，但它的全部卖点
（Material You、动效、现代化 UI）正是本方案要拆掉的东西。

### 8.2 决定性理由：只有 main 能"加法"改造

`ReadView.upPageAnim()` 里是一个 `when (ReadBook.pageAnim())`，按枚举实例化 delegate：

```kotlin
when (ReadBook.pageAnim()) {
    PageAnim.coverPageAnim -> if (pageDelegate !is CoverPageDelegate) { pageDelegate = CoverPageDelegate(this) }
    PageAnim.slidePageAnim -> ...
    PageAnim.scrollPageAnim -> ...
    ...
}
```

所以墨水屏化可以是**纯增量**：

1. `PageAnim` 加一个 `einkPageAnim` 常量（1 行）
2. `when` 加一支（3 行）
3. 新增 `EinkPageDelegate : HorizontalPageDelegate`（在 e-ink 模式下把 `fillPage()` 收敛为一次提交 + 触发 `RefreshGovernor`）
4. 新增独立 `epd` 模块

**改动面 < 20 行现有代码**，上游 rebase 冲突近似为零。相比之下，MD3 里
`PageDelegate` 体系不存在——要么重写 reader，要么在 Compose 的重组模型里重做提交合并，成本翻倍。

另一个被低估的资产：main 的 reader 有完整契约测试
（`HorizontalPageDelegateContractTest`、`ScrollReadPositionContractTest`），
可以照抄这套范式给"一页一提交"写断言。

### 8.3 风险

main 若长期落后于 MD3，引擎可能需要迁移。缓解：保持改动**纯增量**（新模块 + 新 delegate +
少量 hook），换基座时只需搬 `epd` 模块与 4 处 hook，而不是重做。

### 8.4 fork 策略

- `git remote add upstream https://github.com/LegadoTeam/legado`
- 只在 `eink` 分支开发，rebase 到 **release tag**（3.26.x），不跟 `master` HEAD
- `applicationId` 独立（如 `io.legado.eink`，namespace 保持 `io.legado.app`），可与官方版共存做 A/B
- 冲突预算：diff 超过 ~200 行就说明该把 UI 剥成独立模块了

---

## 9. 落地进度（v0 骨架）

仓库：`legado-eink/`（clone 自 `LegadoTeam/legado` @ `531f9fb`，2026-10-22），分支 `eink`。

### 9.1 已实现

| 文件 | 作用 |
|---|---|
| `epd/DeviceProfile.kt` | 设备事实：厂商识别、波形 UI 值常量（DU=1 / GC16=2 / A2=4 / 全清=98）、A2 是否只能全屏 |
| `epd/RefreshBackend.kt` | 后端接口 + no-op 兜底 + Onyx 反射实现（`android.onyx.ViewUpdateHelper`） |
| `epd/RefreshGovernor.kt` | 残影预算：翻页计数、跨章/每 N 页触发整屏全刷、`afterNextDraw` 保证在内容上屏后才清 |
| `epd/EinkRuntime.kt` | 门面：`install()` / `onPageTurn()`，开关绑定"墨水屏模式"（`themeMode == 3`） |
| `page/delegate/EinkPageDelegate.kt` | 墨水屏翻页：无动画、不截图，一次翻页一次提交 + 上报 |

现有代码只改了两处：

1. `ReadView.upPageAnim()` 的 `else` 分支加一层 `if (AppConfig.isEInkMode)` → 用 `EinkPageDelegate`
2. `App.onCreate()` 里 `EinkRuntime.install(this)`

### 9.2 设计取舍

- **不新增动画枚举**：墨水屏模式（themeMode=3）已经强制 `pageAnimEInk = 4`，直接在这个档位上分流，用户无感。
- **scope 默认不钉 DU**（`FORCE_FAST_SCOPE = false`）：DU 是 1bpp，图片/封面会变纯黑白，需要先实测正文可读性。v0 只跑"残影预算"这条链路。
- **系统 per-app 刷新配置不为 NONE 时 scope 可能被忽略**（见 `RefreshModeHelper` 注释），所以骨架不依赖它。
- **反射失败即 no-op**，不抛异常，不影响非 Onyx 设备。

### 9.3 验证方式

已经跑通的：

| 项 | 结果 |
|---|---|
| 编译 `:app:compileAppDebugKotlin` | BUILD SUCCESSFUL（10m19s，首次含依赖下载） |
| 单测 `:app:testAppDebugUnitTest --tests "io.legado.app.epd.*"` | 6 tests / 0 failures |
| 打包 `:app:assembleAppDebug` | app-app-debug.apk 48.5 MB |
| 装机 | `com.legado.app.debug` 与官方 `com.legado.app.release` 共存，界面正常 |
| 运行时 | `I EinkRefresh: install vendor=ONYX model=ONYX Poke6 backend=onyx available=true a2FullScreenOnly=true` |

```bash
adb logcat -s EinkRefresh          # install 行 + 每次 clearGhosting 结果
adb shell su -c "dmesg" | grep -oE "waveform\[[0-9]\] update\[[01]\]" | sort | uniq -c
```

预期：每 30 页或跨章出现一次 `clearGhosting -> true`，且 dmesg 里对应一次 GC16 全屏。

### 9.3.1 本机构建环境（踩过的坑）

本机只有 JDK 25，而项目 toolchain 要求 **21**：

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\jdk-21.0.12.1+1"   # Temurin 21，已装在 ~/.jdks
$env:GRADLE_OPTS 已在 ~/.gradle/gradle.properties 配好 127.0.0.1:7890 代理
.\gradlew.bat :app:compileAppDebugKotlin --no-daemon
```

- 任务名是 `compileAppDebugKotlin`（有 `app` flavor），不是 `compileDebugKotlin`
- Gradle 不走系统代理，必须在 `~/.gradle/gradle.properties` 写 `systemProp.https.proxyHost`
- minSdk 23 时 `mergeAppDebugGlobalSynthetics` 会因 htmlunit-core-js 报 "Increase the minSdkVersion to 26"，故单独一个 commit 提到 26

### 9.4 待办

- [x] 单测：`RefreshGovernor` 的计数/跨章/关闭三态（已拆出纯函数 `nextIntent`）
- [x] 列表/搜索的一次性提交（`EinkListGate`，见 §9.5）
- [ ] 实测 DU 正文可读性，决定 `FORCE_FAST_SCOPE` 默认值
- [ ] 跨章检测改用 `TextChapter` 章节索引，不用对象身份（对象可能复用）
- [ ] 阅读页顶栏时钟（`PageView.upTime`）改为翻页时更新
- [ ] 有书源后实测搜索：一次搜索的 gate hold/release 次数是否 ≈ 1
- [ ] 打开一本书实测 30 页，抓 `clearGhosting` 与 dmesg 波形分布

### 9.5 第二步：列表一次性成型（B）

**问题**：搜索结果是一批批到达的，每来一批 `notifyItemRangeInserted` 就触发一次
layout + 绘制；在 EPD 上就是十几个「半成品画面」，而且 `scrollToBottom()` 还在每次插入后
滚一次，等于又叠一层刷新。

**做法**（`epd/EinkListGate.kt`）：给 Adapter 挂 `AdapterDataObserver`，收到任何变化就
`RecyclerView.suppressLayout(true)` —— 压住期间 RecyclerView 不重新布局，屏幕保持上一帧
完整画面；静默 200ms（最长压 600ms，避免用户面对空白）后放行，一次性 layout、一次绘制。

- 只对适配器变化生效，正常滚动完全不受影响
- 决策逻辑抽成纯类 `BurstGate`，6 个单测覆盖（静默放行 / 持续变化 / 最长上限 / 重入）
- `EinkUiPolicy` 在 Activity resume 时遍历视图树，对每个 RecyclerView：
  `itemAnimator = null`、`overScrollMode = NEVER`（边缘光晕是渐变，在 EPD 上是抖动噪点）、挂 gate
- 策略开关跟随「墨水屏模式」，不依赖厂商后端，任何 e-ink 设备都能生效

**设备实测**（Poke6，themeMode=3）：

```
I EinkRefresh: install vendor=ONYX model=ONYX Poke6 backend=onyx available=true a2FullScreenOnly=true
I EinkRefresh: ui policy installed
I EinkRefresh: list gate attached id=2131363214 adapter=ExploreAdapter
I EinkRefresh: list gate attached id=2131363167 adapter=RssAdapter
I EinkRefresh: list gate attached id=2131363167 adapter=PreferenceGroupAdapter
```

说明视图树遍历确实穿透到 Fragment 内的列表，且没有破坏首屏渲染。

**顺带发现的 token 问题**：书架空态提示文字是 `#666` 级中灰，在 GC16 下是灰的，
一旦切到 DU(1bpp) 就会变抖动噪点。这就是「4 级量化调色板」必须做的直接证据 ——
不是审美问题，是 1bpp 下字会糊。

**已知限制**：adapter 在运行时被替换后（同一 RV 换 adapter），gate 仍挂在旧 adapter 上。
等有真实书源场景再按需处理，别提前抽象。

### 9.6 第三步：灰阶——把抖动权拿回自己手里

**问题**：图片是 8bpp 连续灰，面板只有 1bpp（DU）或 16 级（GC16）。
交给面板自己映射 = 面板随机抖动 = 脏、不可复现，还带残影。

**做法**（`epd/EinkGray.kt`）：8×8 Bayer 有序抖动，压到 2 / 4 / 16 级。

```
q = floor(lum / step + threshold)        step = 255 / (levels - 1)
threshold = (bayer + 0.5) / 64            // 抖动时
threshold = 0.5                           // 不抖动时 = 四舍五入
```

三个性质（都有单测）：

- **幂等**：量化过的图再量化一次不变（因为 lum 恰好落在 `q*step` 上）
- **均值保持**：平灰 0x80 转 2 级后，黑白各占约一半，平均亮度仍在 128 附近
- **alpha 不变**

集成点只有一个：`CoverImageView` 的 Glide 请求加 `optionalTransform(EinkGrayTransformation())`，
仅墨水屏模式生效。封面是列表里面积最大、灰度最连续的块，值得先动。

6 个单测（`EinkGrayTest`），总测试数 18。

**为什么没做「全局 UI 灰阶量化」**：View 体系没有后处理钩子。

- `ColorFilter` 只有线性变换（`ColorMatrixColorFilter`），做不出分段量化
- `RenderEffect` 要 API 31+，Poke6 是 Android 11（API 30）
- 想真正逐像素量化，只能**渲染到 Bitmap 再贴回**：`decorView.draw(canvas)` → 量化 → 画一张全屏 Bitmap

所以全局量化有两条路，代价差别很大：

| 路线 | 做法 | 代价 | 风险 |
|---|---|---|---|
| A 主题层收敛 | 把 `#666`/`#999`/alpha 全部换成 4 级 token | 要逐个改，且要知道 Material3 的 attr 映射 | 低，但改不全就有漏网 |
| B 帧缓冲量化 | 包一层容器，每帧渲染到 Bitmap 再量化贴回 | 每帧 ~20–40ms + 6MB，1.5M 像素遍历 | 高，但一次到位；EPD 本来一帧就要几百 ms，时间预算够 |

B 是唯一能做到「所见即量化」的方案，也是真正意义上的「为 EPD 重写渲染」。
A 只能算「配色规范」。等确定要钉 DU 再决定走哪条——钉 DU 时 B 是必需品，不钉则 A 够用。

### 9.7 第四步：帧缓冲量化（B 路线，已落地）

`epd/EinkFrameHost.kt`：把 `android.R.id.content` 的唯一子 View 包进一个 FrameLayout，
`dispatchDraw` 里先画进复用 Bitmap → 量化像素 → 贴回。View 树结构不变，
输入事件 / findViewById / Fragment 容器都不受影响；遇到 `SurfaceView` 直接跳过该界面；
任何异常永久关闭自己，回到原样绘制。

**实测性能（Poke6，1072×1448 = 1.5M 像素）**

| 阶段 | 耗时 |
|---|---|
| 绘制到 Bitmap | 11–16 ms |
| `getPixels` | 25 ms |
| 量化 | 20 ms（LUT 优化前 78–98 ms） |
| `setPixels` | 2 ms |
| **合计** | **59–65 ms**（首帧 95 ms） |

两个关键优化：

1. **LUT**：`rows[64][256]`，按 Bayer 位置 × 亮度直接查表。逐像素浮点除法是 78–98ms，
   查表后 20ms。量化本身就是「亮度 → 离散灰阶」，天生适合查表。
2. **节流 120ms**：一次连续绘制里只量化「落定」的那一帧，中间帧原样画（便宜），
   120ms 后再补一次量化。这样动画/连点不会每帧都吃 60ms。

**⚠️ 勘误（2026-10-26 实测更正）**：早先记录「书架空态提示是 #c0c0c0、2 级会抹白」是**错的**。
打点结果：该 TextView 的 `currentTextColor` 是 `#8a000000`（合成后亮度 117），
本来就低于 127.5，2 级下会正常变黑。当时量到的 170 是**抗锯齿边缘像素**，不是字色。

真正偏亮的是 `#8a2c2c2c` 这类（合成亮度 140）——设置页的次要说明文字，
2 级下确实会消失。现在由 `EinkPalette` 收敛到 `#444444`（详见 §9.8）。

### 9.8 文字亮度收敛与 2 级量化（已落地）

**EinkPalette**：遍历 TextView，按「文字 alpha × 视图链 alpha」合成到白底算真实亮度，
超过 0x7F 的压到 `#444444`，同时把 `view.alpha` 恢复为 1（只改颜色不够，父链 alpha 会再冲淡）。

- 挂在 `ViewTreeObserver.OnGlobalLayout` 上：列表/空态是数据回来后才出现的，resume 时一次性扫描抓不到
- 实测一次启动收敛 13 处（`#8a2c2c2c` → lum 140）
- 主题叠加方案已排除：`ThemeOverlay` 会让 `BottomNavigationView` inflate 时抛
  `Resources$NotFoundException: Resource ID #0x0`，直接崩启动页

**2 级量化实测**（`frameLevels = 2`，Poke6，1072×1448）：

| 指标 | 4 级 | 2 级 |
|---|---|---|
| 屏上灰阶数 | 4（0/85/170/255） | **2（0/255）** |
| 次要文字 | 85 或 170 灰 | 纯黑，反而更清楚 |
| 单帧耗时 | 58–61ms | 61–64ms（量化本身一样） |

像素直方图验证：整屏只剩 `lum=0` 与 `lum=255` 两个值。

**代价**：没有灰阶层次——「全部」和说明文字一样黑；封面/照片变成二值网点。

**结论（2026-10-27 确认）：2 级定为默认。** 封面呈现为二值网点，观感可接受，
因此不做「图片 4 级 / 文字 2 级」的分区量化——那会引入区域追踪和一条新的代码路径，
收益不抵复杂度。

补充一点容易被忽略的机制：封面在 Glide 层已经做过 4 级 Bayer 抖动，
再被帧缓冲阈值化成 2 级时，`85 → 0`、`170 → 255`，
所以**抖动图案会以纯黑白棋盘的形式保留下来**，等效于 1bpp 网点——
这就是「二值化但不糊成一团」的原因。图片链路上的抖动不能省。

唯一遗留风险：大面积纯黑（暗色封面、深色插画）在 EPD 上摆动最强、残影最重，
只能靠残影预算（每 N 页 / 跨章全刷）兜。

### 9.9 第五步：把对话框也拉进 1bpp（2026-10-27）

**问题**：对话框是独立 window，`android.R.id.content` 那一套包不到它。
设置里的单选框、说明文字、框线全在留着灰阶——4 级时不明显，2 级下就成了「一块灰」。

**做法**（三处基类，各自 `onStart` 里一行）：

| 类 | 覆盖范围 |
|---|---|
| `BaseDialogFragment` | 39 个业务对话框 |
| `BasePrefDialogFragment` | 设置项弹窗 |
| `ListPreferenceDialog` | 「主题模式」这类列表选择弹窗（androidx 的 PreferenceDialogFragmentCompat） |

三个配套修正：

1. **文字统一纯黑**：`EinkPalette` 从「亮度 > 127 才收敛」改成「非纯黑一律拉回 #000」。
   理由：DU 只有 2 色，被判成 85 的灰字在**未经量化的窗口**里会原样显示成灰。
2. **对话框底色**：墨水屏模式下 windowBackground 被显式设成透明，
   帧宿主直接取色会让面板消失（文字浮在页面上）→ 透明一律回落到白。
3. **框线**：`bg_eink_border_*` 原来用 `@color/divider`（40% 黑，亮度 153），
   2 级阈值下判成白，框线直接没了 → 改纯黑。

**验证**：主题模式弹窗现在是 白底 + 纯黑框线 + 纯黑单选框 + 纯黑文字，与页面一致。

**仍未覆盖**：状态栏 / 导航栏（SystemUI）、以及窗口管理器绘制的那圈极淡投影（亮度 205，
在宿主之外）。前者要下沉到 SurfaceFlinger，后者是 OS 画的，都不在应用层能解决的范围。

---

## 10. 当前状态与下一步（2026-10-28）

### 已验证（有证据）

| 层 | 项 | 证据 |
|---|---|---|
| — | Onyx 反射装配 | `install vendor=ONYX backend=onyx available=true` |
| L1 | 阅读页 delegate 分流 | 代码 + 编译通过（**真机翻页未测**） |
| L2 | 列表一次性成型 | `gate attached/hold/release` 日志 |
| L3 | 文字统一纯黑 | clamp 日志 + 截图（含对话框） |
| L4 | 帧缓冲量化 2 级 | 整屏直方图 `distinct=2`；对话框 `host installed: dialog` |
| L5 | 残影预算 | 单测 6 例（**真机未测**） |

24 个单测：`BurstGate 6 / EinkGray 6 / EinkGrayBuffer 2 / EinkPalette 4 / RefreshGovernor 6`。

### 未验证（接手第一件事）

1. **真实阅读 30 页**：`EinkPageDelegate` → `clearGhosting` → dmesg 波形，
   这条链一次都没在真机走过。判据：翻页 1 条波形、跨章 1 次全刷。
2. **真实搜索**（需书源）：一次搜索的 `gate hold/release` 次数应 ≈ 1。
3. **封面 2 级观感**：口头确认可接受，但没有留存截图。

### 未做（有意搁置）

- 状态栏 / 导航栏量化（SystemUI 层，应用层无解）
- 阅读页顶栏时钟改为翻页时更新（用户判断影响小）
- 原地 spinner / 导入动画（频率低，按 `ghost_risk` 公式不值得动架构）
- 4 级 / 2 级切换开关（当前写死 2 级；要开的话在 `EinkUiPolicy.frameLevels`）

### 风险

- `minSdk` 从 23 抬到 26（为绕 htmlunit 的 dex 约束），与上游有偏离
- 设备事实只来自 Poke6 一台；换机型必须重新量波形
- 真机阅读链路未验证，L1/L5 目前只有单元测试和代码推断支撑

---

## 11. 2026-10-31：三个真机缺陷（滚动失效 / 开关不可读 / 红绿失真）

三个现象都由用户实测提出，全部在本机（Poke6，横屏 1072×1448）复现并验证修复。

### 11.1 「设置面板无法下滑」的真因：帧宿主截断了子树的失效

**现象**：阅读页 → 设置（`MoreConfigDialog`），手指往上滑，列表没有任何反应；
但点一下原来位置的开关，触发的却是滚动到新位置后的那一行。

**测量**：`adb shell dumpsys gfxinfo com.legado.app.debug` 在一次完整滑动前后
`Total frames rendered` 只 +1；而 `uiautomator dump` 显示列表首项已经从
「屏幕方向」变成了「平板/横屏双页」。**视图树滚了，屏幕一帧都没重画。**

**根因**：`EinkFrameHost.dispatchDraw` 把子树画进 Bitmap 再贴回。硬件渲染下
每个 View 是一条 display list，这一贴等于把子树从 display list 里**摘掉**，
只剩一条 `drawBitmap`。子 View 自己 `invalidate()` 时，RenderThread 只重录
那一小条（而它已经不在树里），宿主不会重绘 → 屏幕冻结在上一帧。
滚动是唯一「只 invalidate、不 requestLayout」的交互，所以只有它暴露出来。

**修法**（`EinkFrameHost`）：

```kotlin
override fun onDescendantInvalidated(child: View, target: View) {
    super.onDescendantInvalidated(child, target)
    invalidate()          // 子树失效必须把宿主一起拉进来重录
}
```

顺带把节流分支从「画未量化的子树」改成「贴上一张量化过的位图」：
中间帧不再出现灰阶，代价是画面最多延迟 120ms，这在 EPD 上无感。

> 这条坑对任何「渲染到 Bitmap 再贴回」的方案都成立，不只是本工程。

### 11.2 开关：把「颜色」换成「填充」

**现象**：2 级量化下，Material 开关**开 = 一团黑**（彩色轨道 + 彩色滑块都变黑）、
**关 = 完全看不见**（灰轨道 + 白滑块都变白）。偏好设置里的 `android.widget.Switch`、
书源列表里的 `ThemeSwitch` 都一样。

**修法**（`epd/EinkWidgets.kt`，不动控件、只换 drawable，随 `EinkUiPolicy` 遍历视图树生效）：

| 状态 | 轨道 | 滑块 |
|---|---|---|
| 开 | 纯黑实心 44×24dp | 黑点，融进轨道 = 一根实心条 |
| 关 | 白底 2dp 黑框（空心） | 左端黑点 |

两个线索都不依赖色相：**实心 / 空心** + **黑点位置**。

踩过的三个坑：

1. **滑块不能做成状态选择器**。换 drawable 之后 `drawableStateChanged()` 不会重放，
   「开」会把默认（黑）滑块贴到黑轨道上，又是一团黑。所以滑块用**单一**黑白双色/纯黑，
   状态只由轨道承担。
2. **`CircleImageView.setColorFilter(cf: ColorFilter)` 的 Kotlin 非空参数**会让
   `clearColorFilter()`（内部调用 `setColorFilter(null)`）抛
   `NullPointerException: Parameter specified as non-null is null`。
   已改成 `ColorFilter?`——这是上游就有的 bug，任何 `clearColorFilter()` 调用都会中招。
3. **只靠 `OnGlobalLayout` 会漏掉滚动后新绑定的行**（实测：往下滑，新出现的开关
   还是原样的「一团黑」）。改成给每个 `RecyclerView` 挂
   `OnChildAttachStateChangeListener`，行一挂上来就补一次黑白化。

### 11.3 红绿状态点：换成实心 / 空心

书源列表的「探索可用」标记原来用 `Color.GREEN` / `Color.RED`：
绿色合成亮度≈150 → 2 级下变白（点直接消失），红色≈76 → 变黑。
**结果「启用」看不见、「禁用」看得见，语义正好反了。**

改成 `eink_dot_filled`（实心 = 启用）/ `eink_dot_hollow`（空心 = 未启用），
两者都是纯黑，靠形状区分。

### 11.4 其他

- `dialog_read_book_style.xml` 外面套了一层 `ScrollView`，并在 `onStart` 里给窗口
  加了高度上限：横屏可用高度约 570dp，面板内容约 610dp，原来超出部分被窗口直接裁掉。
  （本机实测恰好装得下，但换小屏/竖屏就会裁，属于兜底。）
- `SmoothCheckBox`（「共用布局」）未选底色是 30% 黑，2 级下变白 → 复选框消失；
  墨水屏下改为「未选 = 黑盘 / 已选 = 白底黑勾」。
- 对话框窗口现在也走 `EinkUiPolicy.attachWindow()`：之前只有 Activity 有统一入口，
  偏好设置里的开关、列表 gate 全都没生效。

### 11.5 仍未处理

- 「换源」页的 👍/👎 用 `md_red_A200` / `md_red_100` 表示选中/未选：
  浅红（亮度≈215）在 2 级下变白 → 未选图标不可见。可点但看不见，
  需要换成「选中实心 / 未选空心」的图标对，属于图标资源工作。
- 状态栏 / 导航栏（SystemUI 层）依旧不在应用层可解范围内。

