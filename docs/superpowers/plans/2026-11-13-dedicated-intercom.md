# 专用门禁终端重构与可靠性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于最新代码重划资源所有权，实现任意界面/应用、后台、暗屏和锁屏持续接待，再完成可配置闲置返回和感知待机。

**Architecture:** 保留 AppConnection 名称，在原类内补状态订阅和接待协调，由启动式前台 IntercomService 持有；MainActivity 的来电逻辑整体迁入 IncomingCallActivity，声音集中在现有 CallAudio。新增 UserInteractionService 仅处理授权后的跨应用闲置返回，与门禁监听分别运行；继续复用现有协议、传输、主题和设置界面。

**Tech Stack:** 原生 Kotlin、Android View/XML、JDK 17、minSdk 26、compileSdk/targetSdk 36、现有 JUnit 4.13.2；AndroidX Test 只用于设备测试，人脸阶段才引入 CameraX/本地 ML Kit。

**Spec:** `docs/superpowers/specs/2026-11-13-dedicated-intercom-design.md`。状态：开发前草案；以下任务尚未执行。

**Baseline:** `c65ddbe`，评估起点工作区干净；来电、米家入口、九种主题和当前界面已提交。当前仍由 MainActivity 创建/停止连接，没有上述新服务或独立来电 Activity，也尚无 androidTest 目录。本轮以 JDK 17 离线强制重跑：56 项 JVM 无失败/无跳过，9 项 Python 通过，Debug/Release Lint 均为 0 问题；没有在本轮安装或进行真实门机操作。

## Global Constraints

- 平板长期专用于本户门禁，同时保留已经实现的米家入口。第一期交付持续来电接待、锁屏唤醒、断网/启动恢复、故障交互和可配置的离开应用闲置返回；通过真机验收后，再开发人脸感知亮屏与无人待机。
- 只要接待已启用且对讲网可用，UDP 10008/10000 和有线网络状态监听持续保留；所有内部页面、外部应用、后台、暗屏、锁屏与主题重建均不停止或重建监听。UI 绑定数为 0 也继续接待。
- 自动返回和人脸感知分别是可选交互；关闭或授权不足不能影响来电监听，不能依靠自动回主界面弥补后台漏接。
- 现有原生 Kotlin、Android View/XML 界面和抓包验证过的门禁协议继续使用。
- 首期来电接待范围是已有抓包依据的单元门；地库 1、地库 2 继续提供主动监控，不将单元门来电格式未经验证推广到其他门机。
- 自动重试只用于恢复接待和网络资源。用户命令不自动重试、不跨断线排队。旧命令不能因恢复连接而补发。
- 开锁的“已发送”是本地发送结果，不是物理开门确认；呼梯的“门机已回应”不是电梯已到达。
- 录音仅在用户接听且当前权限、音频条件满足时开始。来电响铃、监控、离线演示和人脸检测均不启动上行录音。
- 离线样本始终明确标注“演示”；正式接待失败时不能自动切换为演示，离线模式不请求录音或发送真实控制命令。
- 相对时间使用包含深度睡眠的单调时间计算。屏幕恢复、重新绑定页面和网络恢复时先检查截止时间；不能依靠暂停期间没有运行的界面倒计时延长旧会话。
- 锁屏来电页仅开放本次来电的视频、接听、挂断和开锁；连接参数/诊断详情跳转需经过正常解锁流程。有系统安全锁时不尝试绕过密码。
- 网络重试为 0.5、1、2、5、10、30 秒，之后维持 30 秒；连续监听 60 秒或合法报文到达才重置失败次数；等待地址 10 秒补充说明。
- 来电无有效数据保护为 8 秒；未接听响铃上限为 60 秒；初始缺画提示 5 秒、已出画后停顿提示 3 秒、异常结束说明 3 秒。
- 开锁排队有效期为发送开始前 1 秒；等待超过 1 秒只补充说明；点按间隔 1.5 秒且前次已处理完；自动挂断为本地发送完成后 3 秒，默认开启。
- 主动监控从发现开始最多 30 秒；发现等待 2 秒、有效画面等待 5 秒、呼梯回应等待 3 秒，保留现有行为。
- CPU 保活为单实例、非引用计数、每次最多 10 分钟，每 60 秒核对条件并续期；持有锁不代替系统休眠和真机接待验收。
- 第二期有人确认 800 毫秒、空闲低亮 30 秒、低亮初值 5%；检测间隔 200 毫秒/低频 2 秒，夜间 22:00～06:00；电量不高于 10% 暂停感知检测。
- API 31 真机与 API 36 模拟器分别验证；系统网口持久地址、持续供电、真实门锁和双向声音使用现场证据。
- 保留现有米家入口、九种主题、四个设置分组、不可交互网络图标、MedioraUi 和稳定窗口边距；Appearance/appearance 偏好保持独立，首次解锁前来电页使用默认资源。
- 闲置返回默认 3 分钟，可关闭，建议可配 1～30 整数分钟；其他应用真实操作重新计时，锁屏/暗屏到期不主动亮屏，来电/通话优先。配置只有授权与操作检测有效时才实际生效。

## Review Focus

- 页面、米家与主题重建的进出：监听始终是同一实例，来电结束返回原前台；任务 2、3、6 直接测试。
- 旧通知、权限结果、挂断计时跨下一通，以及开锁发送结果不确定：拒绝旧键且不自动重发；任务 2、3、4 直接测试。
- 首次解锁前的主题/配置读取：安全接待，不读 appearance 凭据存储，不启动相机/麦克风；任务 1、3、4 直接测试。
- 局部音视频故障与旧网口回调：可用能力继续，不能清理新资源；任务 2、3、4 直接测试。
- 外部应用仍在操作、系统锁屏、权限中断或界面自动刷新：不误返回，不为自动返回影响接待；任务 5、6 直接测试。

---

## 整体改造图与文件归属

```text
现有协议 / 传输 / TerminalSession / IncomingCallSession
                          │
              AppConnection（原处重构、唯一协调入口）
                 │                │
          现有 CallAudio      唯一 IntercomState
                                  │
                 IntercomService 持有其生命周期
                       │                    │
               MainActivity        IncomingCallActivity
              首页/监控/设置         本次来电/权限操作

AppSettings：统一接待配置   StartupReceiver：进入同一启动入口
UserInteractionService：可选的全局闲置检测与返回，独立授权
第二期：PresenceDetector → ScreenPolicy → 屏幕需求
```

第一期五个必需新生产文件为 `IntercomService.kt`、`IncomingCallActivity.kt`、`AppSettings.kt`、`StartupReceiver.kt`、`UserInteractionService.kt`；AppConnection 不强制重命名，也不再包一层控制器。状态与小型映射先在其同文件内，通知/保活放 Service，接待参数放 AppSettings，故障说明与返回设置复用 ConnectionSettingsView。Appearance 与首页/控件视觉实现保持原有职责。

不引入通用 Runtime/Command 总线、独立 Recovery/Startup/Readiness Policy、独立诊断页或历史日志数据库。已有两个真实传输实现需要的 DatagramTransport、会话时钟和取消接口保留。文件数量不是硬性指标；如实际实现需要拆分，必须指出新增文件集中管理了什么真实职责、移除了哪些复杂度。

AppConnection 对外继续使用具体方法：`start(options)`、`stop()`、`openMonitor(source)`、`closeMonitor()`、`callElevator()`、`answerCall(key)`、`hangUpCall(key)`、`unlockCall(key, autoHangUp, onResult)`。新增 `observe(observer): Cancellation` 复用已有取消接口，订阅立即获得当前状态；回调在主线程，解绑后不持有 View/Activity。新增 `updateOptions(updated: ConnectionOptions, onSaved: (Boolean) -> Unit)` 在同一所有者内核对活动来电、保存并按需重连，不再由 MainActivity 先保存再盲目重启。

`CallKey(runId: String, sequence: Int)` 用于跨进程和来电校验，沿用现有代次机制；`UnlockResult` 只有 `SENT / REJECTED / UNCONFIRMED` 三种结果。`IntercomState` 复用既有 LinkStatus、TerminalState、CallStatus，补充当前已生效 ConnectionOptions、CallKey、录音/播放健康、画面停顿、必要权限和最近故障；主状态直接推导。当前页面、settings_section、settings_dialog 和主题仍属于界面。自动返回只持有所需的前台/操作时间投影，不拥有另一份门禁状态。

## Task 1: P0 完善现有连接出口与统一配置

**Files:**
- Modify: `app/src/main/kotlin/com/maichimod/prototype/AppConnection.kt`。
- Create: `app/src/main/kotlin/com/maichimod/prototype/AppSettings.kt`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/ConnectionOptions.kt`、`app/src/main/kotlin/com/maichimod/prototype/MainActivity.kt`、`app/src/main/kotlin/com/maichimod/prototype/ConnectionSettingsView.kt`、`app/build.gradle.kts`。
- Test: `app/src/test/kotlin/com/maichimod/prototype/IntercomStateTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/AppSettingsTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/AppConnectionTest.kt`。
- Create: `docs/runbooks/dedicated-intercom-acceptance.md`。

**Interfaces:** Consumes 现有连接/会话及 ConnectionOptions；Produces 上述控制器具体方法、订阅和状态；`AppSettings.load(): ConnectionOptions`、`save(options): Boolean`、`isReceptionEnabled(): Boolean`、`setReceptionEnabled(enabled): Boolean`。内部明确区分 Missing/Invalid 与有效配置，不能把读错误包装成一份空默认值。

- [ ] **Step 1: 固定基线并写失败用例。** 从已提交的最新基线记录状态，不再假设来电基础仍未提交。新增 `observerReceivesCurrentStateAndCanDetach`、`settingsMigrationPreservesAllOptions`、`failedSaveDoesNotActivateNewIdentity`、`saveFailureKeepsDialogDraft`、`appearancePreferencesAreNotMigrated`、`lockedReadDoesNotAccessCredentialStorage`；断言既有字段/开关不变、失败不换身份/关弹窗、appearance 文件不变。验收模板列出 A/N/C/M/O/S/R 全部场景；现有已覆盖的报文/计时行为直接复用测试。
- [ ] **Step 2: 验证失败。** 使用现有 `:app:testDebugUnitTest`；需要设备用例时仅增加 AndroidX Test core/runner `1.7.0`、ext.junit `1.3.0` 和 AndroidJUnitRunner，运行新用例确认因行为尚未存在失败。设备测试使用隔离的偏好存储和假传输，不覆盖真实参数。
- [ ] **Step 3: 在原代码上完成迁移。** 保留 AppConnection，在原处建立状态订阅，替换对单一 Activity 的构造绑定。AppSettings 仅迁移门禁运行参数；Appearance.current/save 和 appearance 文件保持独立。ConnectionSettingsView 的 onSave 改为接受保存完成回调，保存成功才关闭字段/模式弹窗，失败保留草稿，开关回滚。ConnectionOptions 保留数据和校验，删除其旧存储调用，同一提交迁移调用方；updateOptions 串行核对来电与配置，保存失败不激活新值。
- [ ] **Step 4: 回归。** 运行 `./gradlew -PmaichiFixtureDir=.maichi-fixtures :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`，在隔离模拟器运行对应设备测试。现有监控、呼梯、来电和设置回放保持；没有样本时如实记录跳过。只读核对平板 API、网口地址、供电和锁屏状态，不自动更改系统设置。
- [ ] **Step 5: 清理并提交。** 确认只有一份 AppConnection、一份门禁权威配置，旧页面私有参数读写已移除，外观逻辑未被错误合并；建议 `refactor: centralize intercom state and configuration`。

## Task 2: P1 Service 承载接待并迁出声音

**Files:**
- Create: `app/src/main/kotlin/com/maichimod/prototype/IntercomService.kt`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/AppConnection.kt`、`app/src/main/kotlin/com/maichimod/prototype/CallAudio.kt`、`app/src/main/kotlin/com/maichimod/prototype/MainActivity.kt`、`app/src/main/kotlin/com/maichimod/prototype/session/IncomingCallSession.kt`、`app/src/main/AndroidManifest.xml`。
- Test: `app/src/androidTest/kotlin/com/maichimod/prototype/IntercomServiceTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/CallAudioTest.kt`、现有 `app/src/test/kotlin/com/maichimod/prototype/session/IncomingCallSessionTest.kt`。

**Interfaces:** Consumes 任务 1 控制器；Produces Service 的本地 Binder 暴露同一个控制器实例。CallAudio 继续只有一个实例，提供 `startRinging()`、`start(capture, send)`、`stop()`，通过现有错误回调扩展录音/播放失败类型，无需额外策略类。`unlockCall` 使用本次 CallKey 与三态结果，校验和冷却都直接收口在现有方法。

- [ ] **Step 1: 写失败用例。** `allPagesAndExternalAppsKeepSameListener` 覆盖首页、门机选择、三路监控、四个设置分组/弹窗、主题 recreate、米家、后台、暗屏和锁屏：在网络/连接参数未变的纯界面操作中，Socket 实例恒为 1，离开/回到界面 close/start 增量为 0；`noBoundUiStillReceivesIncomingCall` 验证绑定数为 0 仍接待；`garageMonitorDoesNotReplaceIncomingHouseholdProfile` 验证单元门来电可打断地库。另测独立声音失败、旧 CallKey、不确定发送不重试和 60 秒响铃上限，复用既有 8 秒/3 秒/重复开锁用例。
- [ ] **Step 2: 验证失败。** 运行新的会话 JVM 测试和 Service/声音设备测试；构造网络和声音的失败输入时使用现有依赖注入位置或文件内最小工厂，不能为测试建立一组通用管理器。
- [ ] **Step 3: 移动所有权。** 使用启动式前台服务独立承载 AppConnection，UI 通过 Binder 绑定只是订阅，不使用仅绑定的 Service 生命周期；onUnbind 不停止接待。按网络设备交互使用 connectedDevice，接听后按条件启用 microphone。MainActivity.onStart 删除无条件 activateMode，改为幂等启用检查/绑定；onStop 只结束主动监控和取消本页订阅；onDestroy 不 close 共享连接。声音迁入 CallAudio/AppConnection，Service 不存第二份 CallStatus。显式停用才关闭监听；响铃、演示不录音。
- [ ] **Step 4: 验证。** 全部现有会话/协议回放和本任务设备用例通过；检查同一通没有双重铃声、没有 Activity 持有声音资源，语音失败不误报网络断开。开锁发送前超过 1 秒可拒绝，已经发送中的等待超时只提示；1.5 秒冷却和发送完成后 3 秒挂断继续生效。系统 Home 与明确挂断分别测试。
- [ ] **Step 5: 清理并提交。** 移除 MainActivity 的连接创建/关闭、声音字段和对应回调；不在 Service 再维护另一份 CallStatus。建议 `refactor: host intercom reception and audio outside activity`。

## Task 3: P2 迁移锁屏来电页并补齐故障交互

**Files:**
- Create: `app/src/main/kotlin/com/maichimod/prototype/IncomingCallActivity.kt`、`app/src/main/res/layout/activity_incoming_call.xml`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/MainActivity.kt`、`app/src/main/kotlin/com/maichimod/prototype/IntercomService.kt`、`app/src/main/kotlin/com/maichimod/prototype/ConnectionSettingsView.kt`、`app/src/main/res/layout/activity_main.xml`、`app/src/main/res/layout/page_call.xml`、`app/src/main/res/layout/page_dashboard.xml`、`app/src/main/res/layout/page_monitor.xml`、`app/src/main/res/layout/page_monitor_sources.xml`、`app/src/main/res/layout/page_settings.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/AndroidManifest.xml`。
- Test: `app/src/androidTest/kotlin/com/maichimod/prototype/IncomingCallInteractionTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/ReceptionStatusTest.kt`。

**Interfaces:** Consumes 统一 IntercomState 和控制器具体方法；Produces 唯一来电 Activity。通知渲染是 Service 内的幂等方法；拒接通过显式 Service Intent 校验 CallKey，无需新增接收器/通知框架。通知接听进入可见来电页后提交操作，开锁只在本次来电页提供。

- [ ] **Step 1: 写失败用例。** 覆盖旧通知/计时器、单向收听、视频降级、不确定开锁和不可用恢复入口。新增 `callOverExternalAppReturnsToExternalApp`、`themeRecreationPreservesListenerAndSettingsSection`、`lockedBootCallUsesDefaultThemeWithoutAppearanceRead`、`networkIconsRemainNonInteractive`：来电结束回米家而非强制首页，九种主题不影响监听，首次解锁前不读取 appearance，网络图标不变操作入口。
- [ ] **Step 2: 验证失败。** 在 API 31/36 隔离模拟器运行测试；授权状态与门禁传输使用测试配置，不触碰真实门锁。
- [ ] **Step 3: 整体搬迁来电交互。** 搬迁现有 renderCall/按钮/权限方法，复用 page_call、MedioraUi 和主题资源；正常解锁后按 Appearance 应用主题，首次解锁前使用默认资源。来电页采用通知临时 Activity 的独立任务：空 taskAffinity、excludeFromRecents、singleTask，启动使用 NEW_TASK/CLEAR_TASK，并按 CallKey 去重；关闭只结束临时任务，回原前台/锁屏，不强制拉起主页面。继续使用当前稳定 insets 做法。主页面保留 settings_section、settings_dialog 与草稿恢复；删除旧来电页和资源字段。故障反馈加在本机与网络/对应页面，不新增顶层分组或点击网络图标。
- [ ] **Step 4: 验证交互矩阵。** 覆盖 A01～A12、C01～C18、M01～M07、O01～O08；在九种主题与系统菜单展开时检验来电页尺寸、对比、稳定边距和控件状态。监控结束不重开、四分组/草稿可恢复、米家缺失保留已有提示；解锁前后主题读取安全。3 秒异常说明仅保留本次界面，不阻断下一通，重复打开不重建监听。
- [ ] **Step 5: 清理并提交。** MainActivity 不再存在来电页渲染/权限回调的第二实现；状态文案只有一份事实来源，建议 `refactor: isolate lockscreen call interaction and recovery feedback`。

## Task 4: P3 在现有连接流程内完成重连、启动和电源恢复

**Files:**
- Create: `app/src/main/kotlin/com/maichimod/prototype/StartupReceiver.kt`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/AppConnection.kt`、`app/src/main/kotlin/com/maichimod/prototype/IntercomService.kt`、`app/src/main/kotlin/com/maichimod/prototype/AppSettings.kt`、`app/src/main/kotlin/com/maichimod/prototype/transport/EthernetTransport.kt`、`app/src/main/AndroidManifest.xml`。
- Test: `app/src/androidTest/kotlin/com/maichimod/prototype/ConnectionRecoveryTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/StartupRecoveryTest.kt`；扩展现有会话虚拟时钟测试。

**Interfaces:** Consumes 现有 start/selectWiredLink/recoverWiredSession 逻辑和 AppSettings；Produces 同一 start 入口的幂等恢复、状态中的重试进度与最近故障。StartupReceiver 只转发系统启动/更新事件；Service 依据已保存启用意图启动控制器，直接管理一个 CPU 锁，无额外 Supervisor/StartupPolicy/PowerLease。

- [ ] **Step 1: 写失败用例。** `retryDelaysAreBounded` 断言 `500,1000,2000,5000,10000,30000,30000`；覆盖绑定成功不提前清零、旧回调隔离、手动重试去重、重复启动、停用与首次解锁前行为、睡眠后过期截止时间。新增 `validEthernetRetryKeepsCpuLease`，验证有效网口的临时绑定失败无需用户亮屏即可继续恢复；无网卡不空转，地址错/权限阻塞不无限持锁重试。
- [ ] **Step 2: 验证失败。** 通过现有 Socket 工厂注入绑定/读写失败和旧回调；虚拟时钟验证秒级截止时间，设备测试验证 Service/Direct Boot，不能用 JVM 直接运行 Android Handler 的空实现。
- [ ] **Step 3: 修改现有方法。** 保留一个重试计数、一个取消任务和当前连接代次；分类暂时 I/O 与配置/权限错误，不建立可配置重试框架。每个 Socket 仍绑定有线 Network，网口事件立即评估，不要求互联网验证。启动和首次解锁前均读取同一权威运行配置；不录音、不启相机、不恢复旧操作。CPU 锁在启用且有有效网口需监听/绑定/重试，或通话中按条件续期；无网口/停用/永久阻塞且无会话时释放。外部供电变化更新状态，不以亮屏为恢复前提。
- [ ] **Step 4: 验证。** 覆盖 N01～N11，重连后的出站列表不得出现开锁/接听/呼梯补发；在隔离设备验证锁定重启、首次解锁、进程恢复、用户停用和系统强行停止的差异。强行停止后需用户重新打开的系统行为写入说明，不以定时拉起规避。恢复过程中只保留当前一条提示和一个资源所有者。
- [ ] **Step 5: 清理并提交。** 删除原有只重试一次的平行分支和任何遗留页面重连计时器；建议 `fix: restore dedicated intercom listening after network and startup changes`。

## Task 5: P4 可配置的跨应用闲置返回

**Files:**
- Create: `app/src/main/kotlin/com/maichimod/prototype/UserInteractionService.kt`、`app/src/main/res/xml/user_interaction_service.xml`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/AppSettings.kt`、`app/src/main/kotlin/com/maichimod/prototype/MainActivity.kt`、`app/src/main/kotlin/com/maichimod/prototype/ConnectionSettingsView.kt`、`app/src/main/res/layout/page_settings.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/AndroidManifest.xml`。
- Test: `app/src/test/kotlin/com/maichimod/prototype/IdleReturnTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/UserInteractionServiceTest.kt`。

**Interfaces:** AppSettings 增加 `isIdleReturnEnabled(): Boolean`、`idleReturnMinutes(): Int`、`saveIdleReturn(enabled: Boolean, minutes: Int): Boolean`；默认意图开启、3 分钟，取值 1～30。UserInteractionService 是独立系统无障碍入口，必要计时函数留在同文件，使用已有 SessionScheduler 验证；订阅现有门禁服务的通话投影，不拥有连接。开关/时长保存在普通私有偏好，首次解锁前不读取；关闭或授权失败都不调用 AppConnection.stop。

- [ ] **Step 1: 先验证必要的平台能力并写失败用例。** 在目标 MatePad 做最小事件探测，用户手动授予系统操作检测权限；确认普通触摸方式不变，米家、系统设置、软键盘、硬件按键和系统控制菜单的操作可可靠重置计时，以及后台回门禁允许条件。随后编写 `returnsOnceAfterThreeMinuteExternalIdle`、`interactionAt179999RestartsDeadline`、`ownPagesNeverAutoNavigate`、`windowRefreshDoesNotCountAsUserInput`、`disabledOrUnauthorizedNeverReturns`、`lockedDeadlineWaitsForNormalUnlock`、`activeCallSuspendsReturn`、`failedForegroundStartDoesNotLoop`、`serviceRecoveryStartsFreshDeadline`、`returnDoesNotRestartListener`；断言 179999 毫秒不返回、180000 毫秒只返回一次、操作后完整重计。
- [ ] **Step 2: 验证失败与支持范围。** 运行 `./gradlew :app:testDebugUnitTest --tests '*IdleReturnTest'` 和对应设备用例。全局输入探测必须使用目标机事件证据；不能用 MainActivity.onUserInteraction 或固定延时假装其他应用的操作检测。未可靠覆盖的交互记录为受限，先解决实际能力再宣称该功能通过。
- [ ] **Step 3: 最小实现。** 只新增一个必要的无障碍服务；只订阅交互/前台判断需要的事件元数据，不读取页面内容、不请求触摸探索或模拟手势。保留一个截止时间、一个取消任务；外部操作重计，窗口刷新不续时，门禁任意页不跳转。按系统允许条件复用门禁原任务，保留页面和草稿，不恢复监控，不改变监听。锁屏/暗屏到期仅标记待返回，正常唤醒解锁后再核对；来电/通话优先，结束后仍在外部应用则重新给完整时长。
- [ ] **Step 4: 验证 R01～R10。** 本机与网络分组加入“闲置后返回门禁”和时长编辑，保存失败不误显示生效；权限不足明确提示并提供系统授权入口，门禁接待仍正常。验证时长变更、关闭、权限撤销、用户持续操作、通话中到期、暗屏到期及拒绝拉起；应用返回只能带来一次 UI 绑定，Socket 实例与通话状态不变。保留米家包查询/启动和现有失败提示。
- [ ] **Step 5: 清理并提交。** 不把无障碍检测作为 IntercomService 的依赖，不建立通用 IdleManager/多权限探测框架，不把返回偏好迁进 Direct Boot 接待记录；建议 `feat: return to intercom after configurable external idle`。

## Task 6: P5 整体回归、结构复核与真实验收

**Files:**
- Modify: `docs/runbooks/dedicated-intercom-acceptance.md`、`README.md`、`docs/protocol/incoming-call-capture.md`；只有测量发现确切问题时修改所属现有代码。

**Interfaces:** Consumes 前五任务可安装构建；Produces 第一阶段交付证据和结构复核。不是新一轮架构扩展。

- [ ] **Step 1: 完整回归。** 运行 `./gradlew -PmaichiFixtureDir=.maichi-fixtures :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest` 和 `python3 -B -m unittest discover -s tools -p 'test_*.py'`；在隔离模拟器运行全部设备测试。样本缺失、跳过、模拟通过和真实通过分别记录。
- [ ] **Step 2: 复核整体结构。** 检查连接、状态、声音和来电页是否各有唯一所有者；原位置逻辑是否真正删除；是否残留双写、转发层、双状态机、临时兼容入口或只为假设未来而增加的接口。控制器中如果出现真实独立且难以理解的职责，再针对该部分拆分；不能以控制器一个文件为目标塞入所有系统细节。
- [ ] **Step 3: 现场核心验收。** MatePad 核对持久地址/同时供电；逐项在首页、监控选择/三路播放、四个设置分组/弹窗、主题重建、米家/系统设置前台、后台、暗屏和锁屏发起真实来电，确认持续监听。检验通话后回原应用、单向/双向声音、开锁、旧通知、拔插网线/网卡、IP/权限变化与重启首次解锁前后；首次解锁前默认主题可用。另验 R01～R10，可配置闲置返回关闭时仍可后台接待。物理结果由现场确认，系统设置变更由用户参与。
- [ ] **Step 4: 按实测处理性能。** 网络地址就绪后恢复目标 5 秒，完整合法来电收到后铃声/展示目标 2 秒。先记录控制队列与 JPEG 解码耗时；只有实测控制响应不满足目标，才把现有解码移到一个有界工作队列，并直接更新现有 JpegAssembler/会话观察接口和测试，成功解码才刷新健康时间，不长期保留两套路径。当前监控“仅交付最新帧”做法复用于来电。
- [ ] **Step 5: 稳定性与交付。** 先完成 24 小时，再完成 72 小时观察，包含可追溯测试来电、息屏、米家长时间前台、用户持续操作不被抢占、闲置返回、网络/电源变化。未通过的真实接待、地址恢复、声音、门锁或自动返回先修复，不能以回放替代，也不能进入人脸阶段。保持历史界面记录时间边界，更新实际能力说明，建议 `docs: record dedicated intercom acceptance and operation`。

## Task 7: P6 在稳定接待上加入感知与低亮待机

**Files:**
- Create: `app/src/main/kotlin/com/maichimod/prototype/PresenceDetector.kt`、`app/src/main/kotlin/com/maichimod/prototype/ScreenPolicy.kt`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/AppConnection.kt`、`app/src/main/kotlin/com/maichimod/prototype/IntercomService.kt`、`app/src/main/kotlin/com/maichimod/prototype/MainActivity.kt`、`app/src/main/kotlin/com/maichimod/prototype/HomeDashboard.kt`、`app/src/main/kotlin/com/maichimod/prototype/ConnectionSettingsView.kt`、`app/src/main/kotlin/com/maichimod/prototype/AppSettings.kt`、`app/src/main/res/layout/page_settings.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/AndroidManifest.xml`、`app/build.gradle.kts`。
- Test: `app/src/test/kotlin/com/maichimod/prototype/ScreenPolicyTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/PresenceDetectorTest.kt`。

**Interfaces:** Consumes 已通过任务 6 的运行状态；Produces `PresenceDetector.start(onPresence)`、`stop()`、`close()`，结果为 `PRESENT / ABSENT / UNKNOWN`；`ScreenPolicy` 以时间、门禁窗口可见性、通话/监控状态、最近触摸和存在结果为输入，只返回本应用亮度/常亮与检测间隔。外部应用闲置返回与本应用 30 秒低亮是不同功能，不能共用一个到期动作。

- [ ] **Step 1: 写失败用例。** 仅在第一期通过后开始，覆盖有人 800 毫秒、空闲 30000 毫秒、5% 亮度、10% 电量，以及未知/旧检测和停止后重启。新增 `dimmedHomeStopsDecorativeFrames`、`externalAppPausesPresenceButKeepsReception`，确认只补现有 HomeDashboard 的低亮暂停，米家前台不受本应用亮度/人脸结果干扰；覆盖 S01～S11。
- [ ] **Step 2: 验证失败。** 运行 `./gradlew :app:testDebugUnitTest --tests '*ScreenPolicyTest'`；规则测试不需要相机、服务或真实门机。
- [ ] **Step 3: 最小接入。** 从 SmartFace 复用已核对的本地检测思路，修正暂停恢复和资源释放；CameraX core/camera2/lifecycle 稳定版 `1.6.2`、bundled ML Kit `16.1.7` 为本方案查询基线，执行时检查构建兼容后固定，不引入 Compose 或动态下载模型。相机从可见用户启用流程启动，必要时在现有 Service 按实际系统版本增加 camera 条件。感知失败回到触摸空闲策略，不影响门禁监听。
- [ ] **Step 4: 验证。** 检查暗光、遮挡、侧脸、相机占用、权限拒绝、夜间、来电抢占、停止后重启与功耗；检测最多 200 毫秒一次，稳定无人/夜间 2 秒一次。复用并保留现有 HomeDashboard 的离页/失焦/后台/系统禁用动画暂停，低亮时追加同一暂停条件。米家前台暂停本应用感知，不改全局亮度、不因人脸拉回门禁；自动返回后恢复门禁窗口规则。真正息屏后的感知唤醒另验，未通过只开放应用内低亮，来电监听始终独立。
- [ ] **Step 5: 清理并提交。** 删除 Activity 或检测回调中直接控制亮度的重复分支，屏幕决定集中在 ScreenPolicy；建议 `feat: add presence-aware intercom standby`，更新实际支持范围。

## 场景覆盖与执行约束

| 场景 | 主要任务 | 完成依据 |
| --- | --- | --- |
| A01～A12 启用、配置、权限、不可用 | 1、2、3、4 | 统一状态、保存失败保留编辑、设置返回重检 |
| N01～N11 网络与恢复 | 4、6 | 注入故障与真实恢复，监听不随 UI 绑定数变化 |
| C01～C18 来电与页面/应用切换 | 2、3、4、6 | 米家、九种主题、四分组、旧通知、锁屏及解锁前主题 |
| M01～M07 音视频故障 | 2、3、6 | 局部降级、资源释放、实测响应和实际听音 |
| O01～O08 开锁、呼梯、监控 | 2、3、6 | 发送次数/结果边界、现有字节回放与物理结果 |
| S01～S11 屏幕、感知与供电 | 4、6、7 | 接待独立、已有动效的低亮暂停、外部应用不干扰 |
| R01～R10 跨应用闲置返回 | 5、6 | 默认 3 分钟可配置，真实操作续时，授权/锁屏/通话优先 |

每个实现任务先写能证明新增行为的失败用例，再改代码并回归；提交只包含本任务拥有的文件。基线 `c65ddbe` 已含当前来电/主题/米家与文档，原“来电基础仍未提交”的假设已失效；执行时从包含本次文档更新的最新已提交基线开始，并再次核对工作区，保留后续新改动。每步保持可构建，必要调用方迁移同一任务完成，内部接口无需永久兼容层。现有报文/计时、主题标识和界面能力只扩展缺口，不重复搭建。

本轮不开发独立诊断应用、历史事件存储、日志分享、访客存档或设备管理框架；状态解释和恢复动作直接复用现有界面与日志。它们只有在真实维护需求出现时再单独评估。开发计划和场景数量不等于代码模块数量。

依赖依据：[AndroidX Test 稳定版本](https://developer.android.com/jetpack/androidx/releases/test)、[CameraX 稳定版本](https://developer.android.com/jetpack/androidx/releases/camera)、[本地 ML Kit 模型](https://developers.google.com/ml-kit/vision/face-detection/android)。系统约束与真实设备边界见设计文档的依据章节。
