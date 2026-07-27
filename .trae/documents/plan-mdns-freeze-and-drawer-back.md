# Plan: 修复 mDNS 扫描卡死闪退 + 侧滑菜单拦截系统返回

## Context

用户报告两个独立 bug：

1. **mDNS 扫描卡死闪退** — 即便没连相机 WiFi 热点，只要点"开始扫描"或进入设置页让 `LaunchedEffect(connectionType)` 自动触发 `onStartWifiScan()`，`WifiCameraDiscovery.startFullScan()` 就会卡住，最终闪退。
2. **侧滑菜单的系统返回被吃** — 在 `CameraScreen` 主屏从屏幕左/右侧滑出 `CameraSettingsPanel` 抽屉菜单（`ModalNavigationDrawer`）后，按系统返回键会**直接 finish 整个 Activity**（关 APP），而不是先关闭抽屉回到主屏。

### Bug 1 根因（`WifiCameraDiscovery.kt`）

经过逐行审计（L120–394），定位到 **3 个相互叠加的根因**：

- **A. 无并发上限的子网端口扫描**（L321–352 `runFallbackPortScan`）：
  - `for (host in 1..254) { jobs += async { for (port in knownCommandPorts) { socket.connect(...) } } }`
  - 254 主机 × 17 端口 = 最多 **4,318 个并发 `Socket().connect()`**，每次超时 600 ms。
  - 没连相机 WiFi 时本机无 WiFi 接口，`localSubnetBase()` 返回 null 直接跳过——但若**连到了其他 WiFi**（如家庭路由）或扫描前切到 WiFi 模式触发了 `LaunchedEffect`，`localSubnetBase()` 会拿到子网，254 并发连接瞬间把文件描述符表打爆 → `IOException: Too many open files` → native crash → APP 闪退。
- **B. 全轮询没有 `withTimeout`**（L138–149 `startFullScan`）：3 轮 `runRoundScan` × (mDNS `delay(2_000)` + 端口扫描 ~10 s) ≈ **35 s 无封顶**，UI 一直显示"SCANNING"，用户只能强杀。
- **C. `scope` 永生 + 无 `release()`**（L35）：`SupervisorJob + Dispatchers.IO` 永不 cancel；扫描 Job 也没跟踪，`stopDiscovery()` 不会取消在飞的 `runFallbackPortScan`，即便用户点"停止"也要等所有 in-flight socket 跑完（最坏 10 s）。

### Bug 2 根因（`CameraScreen.kt`）

- `rememberDrawerState(initialValue = DrawerValue.Closed)`（L126）+ `ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = drawerState.isOpen, ...)`（L128–184）。
- 项目用的是 `androidx.compose.material3:material3:1.x`，**抽屉打开时系统返回由 Material3 的 `ModalNavigationDrawer` 内置 `BackHandler(enabled = drawerState.isOpen)` 处理**——但是**因为本项目把 `gesturesEnabled` 设为 `drawerState.isOpen` 且抽屉状态是 `remember` 而非 `rememberSaveable`**，在某些 Compose 版本（特别是 `material3` < 1.2.0 的 predictive back 行为变化）下内置 BackHandler 不一定生效。
- 兜底：在 `CameraScreen` 显式注册 `BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }`。
- 整个 `app/src/main` 唯一一处 `BackHandler` 在 `SettingsScreen.kt:168`（处理 Settings→Camera 返回），没有第二处覆盖 drawer 场景。

---

## Files to modify

### A. `app/src/main/java/com/phtontools/phtonview/connection/WifiCameraDiscovery.kt`

1. **加 `MulticastLock` 持有/释放**（解决"没拿到 mDNS 多播"）：
   - 字段：`private var multicastLock: WifiManager.MulticastLock? = null`
   - 新增 `private fun acquireMulticastLock()` / `private fun releaseMulticastLock()`，包裹在 `try { ... } catch (e: Throwable) { AppLogger.w(...) }`。
   - `startFullScan()` 入口 acquire；`stopDiscovery()` 入口 release。
2. **加 `private var scanJob: Job? = null` 跟踪全轮询**，`startFullScan()` 中 `scanJob = scope.launch { ... }`；`stopDiscovery()` 中 `scanJob?.cancel(); scanJob = null`。
3. **`startFullScan()` 整个 `scope.launch` 用 `withTimeoutOrNull(35_000L)` 包裹**，超时则 `AppLogger.w("mDNS full scan timed out after 35s")`、`_scanProgress.value = ScanProgress.FAILED`、`stopDiscovery()`。`maxRounds` 默认改 `2`（更激进，第一轮失败立即第二轮）。
4. **`runFallbackPortScan()` 改并发上限**：
   - 用 `flatMapMerge(concurrency = 32)` 替代裸 `async + awaitAll`。
   - 端口列表收敛到 6 个常用 PTP-IP 端口：`listOf(15740, 15741, 4757, 4759, 49152, 80)`（保留 17 个已无意义，且容易把 SYN 风暴打到家庭路由器被 QoS 丢包）。
   - 每个 host 内层 port 循环**找到开放端口就 `break`**，避免一台相机对应多个端口重复入列。
5. **加 `fun release()`**：内部 `scope.cancel(); scanJob = null; releaseMulticastLock()`，幂等。
6. **线程安全**：`discoveryListeners` 改 `CopyOnWriteArrayList<NsdManager.DiscoveryListener>()`，避免 NSD 回调线程与 IO 线程同时读写。

### B. `app/src/main/java/com/phtontools/phtonview/data/repository/CameraRepositoryImpl.kt`

- 在 `release()`（L2450–2476）的 `runCatching { conn?.release() }` 之后追加 `runCatching { wifiDiscovery.release() }` —— 确保 Activity 销毁时停止所有飞行中的 mDNS 扫描和端口扫描。

### C. `app/src/main/java/com/phtontools/phtonview/ui/CameraScreen.kt`

- 在 `CameraScreen` Composable 内、紧跟 `rememberDrawerState`（L126 之后）插入：
  ```kotlin
  BackHandler(enabled = drawerState.isOpen) {
      scope.launch { drawerState.close() }
  }
  ```
- 在文件顶部 import 块加入 `import androidx.activity.compose.BackHandler`（目前 import 块在 L2–L72 区域）。

### D. `app/version.properties`

- `versionBuild=06` → `versionBuild=07`（`versionDate` 保持 `2026-07-27`）。

---

## Reusable utilities to leverage

- `AppLogger.d / w / e / report`（`util/AppLogger.kt`）—— 所有改动点都用现成 logger 记录扫描阶段、socket 错误、超时。
- `MutableStateFlow` + `collectAsStateWithLifecycle` —— UI 已经在用 `_scanProgress` / `_discoveredServices`，无需新增 API。
- `kotlinx.coroutines.flow.flatMapMerge` —— 已经在项目其他模块用过（如 `LiveViewFlow` 合并），直接复用模式。

---

## Verification

1. **编译**：`./gradlew.bat :app:compileDebugKotlin` 0 error。
2. **release 构建**：`./gradlew.bat :app:assembleRelease` 成功，APK 落入 `app/build/outputs/apk/release/app-release.apk`。
3. **冒烟（手动）**：
   - 启动 APP → 不连相机 WiFi → 进入设置页 → 看到"SCANNING_PORTS"进度 → **最迟 35 秒**后状态变 `FAILED` 而不是挂死；可正常点返回回到 CameraScreen。
   - 在 CameraScreen 打开抽屉 → 按系统返回键 → 抽屉关闭、APP 不退出。
   - 在抽屉内打开设置页（SettingsScreen）→ 按系统返回键 → 回到 CameraScreen（已有 BackHandler 路径）。
4. **logcat 关键字**：`WifiCameraDiscovery`、`SCAN_TIMEOUT`、`MulticastLock acquired/released`、`scanJob cancelled`。
5. **回归**：连到相机热点扫描能发现相机（确保没误改 `serviceTypes` / `knownCommandPorts` 列表）。
