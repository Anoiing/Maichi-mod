# 专用门禁终端重构与可靠性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过重划现有代码职责，先完成后台来电、锁屏唤醒和网络/启动恢复，再加入感知亮屏与低亮待机。

**Architecture:** 将现有 AppConnection 重构为唯一 IntercomController，由薄的 IntercomService 持有；将 MainActivity 的来电逻辑整体迁入 IncomingCallActivity，声音继续集中在现有 CallAudio。复用已验证的协议、会话、以太网/样本传输和设置界面，移除原位置的重复逻辑。

**Tech Stack:** 原生 Kotlin、Android View/XML、JDK 17、minSdk 26、compileSdk/targetSdk 36、现有 JUnit 4.13.2；AndroidX Test 只用于设备测试，人脸阶段才引入 CameraX/本地 ML Kit。

**Spec:** `docs/superpowers/specs/2026-11-13-dedicated-intercom-design.md`。状态：开发前草案；以下任务尚未执行。

## Global Constraints

- 平板长期专用于本户门禁。第一期交付后台来电、锁屏唤醒、断网恢复、启动恢复和必要的诊断交互；第一期通过真机验收后，再开发人脸感知亮屏与无人待机。
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

## Review Focus

- 旧通知、权限结果和挂断计时跨越下一通：旧 CallKey 必须拒绝；任务 2、3、4 直接测试。
- 开锁进入发送后结果不确定：不得因为界面超时自动重发；任务 2、3 直接测试。
- 重启尚未首次解锁：配置可读且没有空值回写，不启动相机/麦克风；任务 1、4 直接测试。
- 视频或一个语音方向失败：当前控制、另一语音方向和可用按钮保留；任务 2、3 直接测试。
- 网口反复变化和页面反复重建：不生成第二个连接/资源所有者，旧回调不能清理新资源；任务 2、3、4 直接测试。

---

## 整体改造图与文件归属

```text
现有协议 / 传输 / TerminalSession / IncomingCallSession
                          │
              IntercomController（改造 AppConnection）
                 │                │
          现有 CallAudio      唯一 IntercomState
                                  │
                 IntercomService 持有其生命周期
                       │                    │
               MainActivity        IncomingCallActivity
              首页/监控/设置         本次来电/权限操作

AppSettings：统一配置       StartupReceiver：进入同一启动入口
第二期：PresenceDetector → ScreenPolicy → 屏幕需求
```

第一期四个必需新生产文件为 `IntercomService.kt`、`IncomingCallActivity.kt`、`AppSettings.kt`、`StartupReceiver.kt`；`AppConnection.kt` 重命名为 `IntercomController.kt`。状态与小型映射先在控制器同文件内，通知/保活放 Service，参数存储放 AppSettings，故障说明复用 ConnectionSettingsView。测试、必要布局与资源文件按任务创建。

不引入通用 Runtime/Command 总线、独立 Recovery/Startup/Readiness Policy、独立诊断页或历史日志数据库。已有两个真实传输实现需要的 DatagramTransport、会话时钟和取消接口保留。文件数量不是硬性指标；如实际实现需要拆分，必须指出新增文件集中管理了什么真实职责、移除了哪些复杂度。

控制器对外继续使用具体方法：`start(options)`、`stop()`、`openMonitor(source)`、`closeMonitor()`、`callElevator()`、`answerCall(key)`、`hangUpCall(key)`、`unlockCall(key, autoHangUp, onResult)`。新增 `observe(observer): Closeable` 返回可取消订阅，订阅立即获得当前状态；回调运行在主线程，界面解绑后不再持有 View/Activity。不要再包一个转发同样方法的控制器接口。

`CallKey(runId: String, sequence: Int)` 用于跨进程和来电校验，沿用现有代次机制；`UnlockResult` 只有 `SENT / REJECTED / UNCONFIRMED` 三种结果。`IntercomState` 复用既有 LinkStatus、TerminalState、CallStatus，补充 CallKey、录音/播放健康、画面停顿、必要权限和最近故障；主状态由这些事实直接推导。旧 UI 可变业务状态删除，展示所需的当前页面/草稿继续由界面持有。

## Task 1: P0 重构控制器出口与统一配置

**Files:**
- Rename: `app/src/main/kotlin/com/maichimod/prototype/AppConnection.kt` → `app/src/main/kotlin/com/maichimod/prototype/IntercomController.kt`。
- Create: `app/src/main/kotlin/com/maichimod/prototype/AppSettings.kt`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/ConnectionOptions.kt`、`app/src/main/kotlin/com/maichimod/prototype/MainActivity.kt`、`app/build.gradle.kts`。
- Test: `app/src/test/kotlin/com/maichimod/prototype/IntercomStateTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/AppSettingsTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/IntercomControllerTest.kt`。
- Create: `docs/runbooks/dedicated-intercom-acceptance.md`。

**Interfaces:** Consumes 现有连接/会话及 ConnectionOptions；Produces 上述控制器具体方法、订阅和状态；`AppSettings.load(): ConnectionOptions`、`save(options): Boolean`、`isReceptionEnabled(): Boolean`、`setReceptionEnabled(enabled): Boolean`。内部明确区分 Missing/Invalid 与有效配置，不能把读错误包装成一份空默认值。

- [ ] **Step 1: 固定基线并写失败用例。** 记录 `git status --short`、当前已有未提交文件及已有测试结果；为 `observerReceivesCurrentStateAndCanDetach`、`settingsMigrationPreservesAllOptions`、`failedSaveDoesNotActivateNewIdentity`、`lockedReadDoesNotAccessCredentialStorage` 写断言：订阅立即有快照、解绑无回调、现有字段和自动挂断开关保留、失败不切换当前身份、锁定启动只读必要记录。验收模板列出设计 A/N/C/M/O/S 全部场景，现场前提未验证则明确留待验收。
- [ ] **Step 2: 验证失败。** 使用现有 `:app:testDebugUnitTest`；需要设备用例时仅增加 AndroidX Test core/runner `1.7.0`、ext.junit `1.3.0` 和 AndroidJUnitRunner，运行新用例确认因行为尚未存在失败。设备测试使用隔离的偏好存储和假传输，不覆盖真实参数。
- [ ] **Step 3: 在原代码上完成迁移。** 重命名控制器并建立一份状态；订阅机制替换构造函数对单一 Activity 的绑定。AppSettings 将启动所需身份/地址和开关集中到设备保护的私有配置，其他注记保留普通私有存储；升级先验证迁移再使用新记录，不持续双写或自动回退。ConnectionOptions 只保留数据和校验，删除原 Activity 偏好读写调用，同一提交更新所有调用方，不保留两版入口。
- [ ] **Step 4: 回归。** 运行 `./gradlew -PmaichiFixtureDir=.maichi-fixtures :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`，在隔离模拟器运行对应设备测试。现有监控、呼梯、来电和设置回放保持；没有样本时如实记录跳过。只读核对平板 API、网口地址、供电和锁屏状态，不自动更改系统设置。
- [ ] **Step 5: 清理并提交。** 确认原 AppConnection 定义和页面私有存储调用已移除，提交本任务拥有的文件，建议 `refactor: centralize intercom state and configuration`。

## Task 2: P1 Service 承载接待并迁出声音

**Files:**
- Create: `app/src/main/kotlin/com/maichimod/prototype/IntercomService.kt`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/IntercomController.kt`、`app/src/main/kotlin/com/maichimod/prototype/CallAudio.kt`、`app/src/main/kotlin/com/maichimod/prototype/MainActivity.kt`、`app/src/main/kotlin/com/maichimod/prototype/session/IncomingCallSession.kt`、`app/src/main/AndroidManifest.xml`。
- Test: `app/src/androidTest/kotlin/com/maichimod/prototype/IntercomServiceTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/CallAudioTest.kt`、现有 `app/src/test/kotlin/com/maichimod/prototype/session/IncomingCallSessionTest.kt`。

**Interfaces:** Consumes 任务 1 控制器；Produces Service 的本地 Binder 暴露同一个控制器实例。CallAudio 继续只有一个实例，提供 `startRinging()`、`start(capture, send)`、`stop()`，通过现有错误回调扩展录音/播放失败类型，无需额外策略类。`unlockCall` 使用本次 CallKey 与三态结果，校验和冷却都直接收口在现有方法。

- [ ] **Step 1: 写失败用例。** `activityStopKeepsReceptionAndAnsweredCall` 断言退后台连接关闭次数为 0；`rebindDoesNotCreateSecondController` 断言网络实例仍为 1；`captureFailureKeepsPlayback`、`playbackFailureDoesNotDisconnectCall` 断言只关闭失败方向；`staleCallCannotUnlock`、`slowSendDoesNotRetryUnlock`、`ringRetryDoesNotExtendSixtySecondLimit` 断言旧标识发送数为 0、已开始发送超过 1 秒仍只发送一次、重复来电不延长 60000 毫秒上限。保留现有 8 秒无数据、3 秒自动挂断及重复开锁测试。
- [ ] **Step 2: 验证失败。** 运行新的会话 JVM 测试和 Service/声音设备测试；构造网络和声音的失败输入时使用现有依赖注入位置或文件内最小工厂，不能为测试建立一组通用管理器。
- [ ] **Step 3: 移动所有权。** Service 创建并关闭唯一控制器，负责系统前台服务、常驻通知与平台生命周期；按网络设备交互使用 connectedDevice 类型，用户接听且适用系统要求满足时再启用 microphone 类型。控制器协调现有会话和声音。把 Activity 的铃声/录音/播放逻辑移入 CallAudio/控制器并删除原实现；MainActivity 通过 Binder 订阅，onStop 只结束主动监控并解绑。服务启动失败形成 A05 状态；停用释放资源并保存意图，保存失败明确提示。响铃和演示不录音，麦克风只在用户接听且条件满足时启用。
- [ ] **Step 4: 验证。** 全部现有会话/协议回放和本任务设备用例通过；检查同一通没有双重铃声、没有 Activity 持有声音资源，语音失败不误报网络断开。开锁发送前超过 1 秒可拒绝，已经发送中的等待超时只提示；1.5 秒冷却和发送完成后 3 秒挂断继续生效。系统 Home 与明确挂断分别测试。
- [ ] **Step 5: 清理并提交。** 移除 MainActivity 的连接创建/关闭、声音字段和对应回调；不在 Service 再维护另一份 CallStatus。建议 `refactor: host intercom reception and audio outside activity`。

## Task 3: P2 迁移锁屏来电页并补齐故障交互

**Files:**
- Create: `app/src/main/kotlin/com/maichimod/prototype/IncomingCallActivity.kt`、`app/src/main/res/layout/activity_incoming_call.xml`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/MainActivity.kt`、`app/src/main/kotlin/com/maichimod/prototype/IntercomService.kt`、`app/src/main/kotlin/com/maichimod/prototype/ConnectionSettingsView.kt`、`app/src/main/res/layout/activity_main.xml`、`app/src/main/res/layout/page_call.xml`、`app/src/main/res/layout/page_dashboard.xml`、`app/src/main/res/layout/page_monitor.xml`、`app/src/main/res/layout/page_monitor_sources.xml`、`app/src/main/res/layout/page_settings.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/AndroidManifest.xml`。
- Test: `app/src/androidTest/kotlin/com/maichimod/prototype/IncomingCallInteractionTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/ReceptionStatusTest.kt`。

**Interfaces:** Consumes 统一 IntercomState 和控制器具体方法；Produces 唯一来电 Activity。通知渲染是 Service 内的幂等方法；拒接通过显式 Service Intent 校验 CallKey，无需新增接收器/通知框架。通知接听进入可见来电页后提交操作，开锁只在本次来电页提供。

- [ ] **Step 1: 写失败用例。** `cancelBeforeUiLaunchCannotRingAgain`、`oldNotificationCannotAnswerNextCall`、`endingTimerCannotDismissNewCall`、`microphoneDeniedShowsListenOnly`、`videoFailureKeepsUnlockAvailable`、`unconfirmedUnlockNeverShowsUnlocked`、`disabledMonitorKeepsReasonAndSetupAction`；断言过期动作不发送、局部故障不全局禁用、结束说明 3 秒且新来电优先、待发送超时不重新启用重复开锁。
- [ ] **Step 2: 验证失败。** 在 API 31/36 隔离模拟器运行测试；授权状态与门禁传输使用测试配置，不触碰真实门锁。
- [ ] **Step 3: 整体搬迁来电交互。** 将 MainActivity 现有 renderCall、接听权限和来电按钮方法迁到新 Activity，继续使用 page_call.xml；移除旧页面 include 和分支。使用公开锁屏展示/亮屏接口和按版本适配的系统来电通知；通知与前台常驻渠道分开，声音只由 CallAudio 管理。连接状态和恢复入口直接补到现有设置页；持续故障用页面内说明，不能只有 Toast。当前权限不足显示接待受限，返回设置后重新读取事实状态。
- [ ] **Step 4: 验证交互矩阵。** 覆盖 A01～A11、C01～C14、M01～M07、O01～O08 的相关界面；确认来电打断监控后回选择页，打断配置后恢复草稿，锁屏不能直接进入连接参数。首次缺画 5 秒、停帧 3 秒只降级画面；正常手动挂断立即返回，对端/异常结束先释放资源再显示 3 秒原因。重复创建页面不能重新接听或建连接。
- [ ] **Step 5: 清理并提交。** MainActivity 不再存在来电页渲染/权限回调的第二实现；状态文案只有一份事实来源，建议 `refactor: isolate lockscreen call interaction and recovery feedback`。

## Task 4: P3 在现有连接流程内完成重连、启动和电源恢复

**Files:**
- Create: `app/src/main/kotlin/com/maichimod/prototype/StartupReceiver.kt`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/IntercomController.kt`、`app/src/main/kotlin/com/maichimod/prototype/IntercomService.kt`、`app/src/main/kotlin/com/maichimod/prototype/AppSettings.kt`、`app/src/main/kotlin/com/maichimod/prototype/transport/EthernetTransport.kt`、`app/src/main/AndroidManifest.xml`。
- Test: `app/src/androidTest/kotlin/com/maichimod/prototype/ConnectionRecoveryTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/StartupRecoveryTest.kt`；扩展现有会话虚拟时钟测试。

**Interfaces:** Consumes 现有 start/selectWiredLink/recoverWiredSession 逻辑和 AppSettings；Produces 同一 start 入口的幂等恢复、状态中的重试进度与最近故障。StartupReceiver 只转发系统启动/更新事件；Service 依据已保存启用意图启动控制器，直接管理一个 CPU 锁，无额外 Supervisor/StartupPolicy/PowerLease。

- [ ] **Step 1: 写失败用例。** `retryDelaysAreBounded` 断言 `500,1000,2000,5000,10000,30000,30000`；`bindingSuccessAloneDoesNotResetFailureCount`、`validPacketOrSixtyStableSecondsResetsBackoff`、`oldSocketFailureCannotCloseNewSocket`、`manualRetryReplacesPendingRetry`、`multipleBootEventsStartOnce`、`disabledReceptionDoesNotRestart`、`lockedBootDoesNotRecord`、`sleepDoesNotExtendOldCallDeadline`；同时验证无网卡不空转、地址错不无限重试、长时间无来电不重开健康连接。
- [ ] **Step 2: 验证失败。** 通过现有 Socket 工厂注入绑定/读写失败和旧回调；虚拟时钟验证秒级截止时间，设备测试验证 Service/Direct Boot，不能用 JVM 直接运行 Android Handler 的空实现。
- [ ] **Step 3: 修改现有方法。** 保留一个重试计数、一个取消任务和当前连接代次；分类暂时 I/O 与配置/权限错误，不建立可配置重试框架。每个 Socket 仍绑定有线 Network，网口事件立即评估，不要求互联网验证。启动和首次解锁前均读取同一权威运行配置；不录音、不启相机、不恢复旧操作。CPU 锁在启用且监听就绪/通话中按条件续期，停止或无需要时释放；外部供电变化只更新状态和负载策略。
- [ ] **Step 4: 验证。** 覆盖 N01～N11，重连后的出站列表不得出现开锁/接听/呼梯补发；在隔离设备验证锁定重启、首次解锁、进程恢复、用户停用和系统强行停止的差异。强行停止后需用户重新打开的系统行为写入说明，不以定时拉起规避。恢复过程中只保留当前一条提示和一个资源所有者。
- [ ] **Step 5: 清理并提交。** 删除原有只重试一次的平行分支和任何遗留页面重连计时器；建议 `fix: restore dedicated intercom listening after network and startup changes`。

## Task 5: P4 整体回归、结构复核与真实验收

**Files:**
- Modify: `docs/runbooks/dedicated-intercom-acceptance.md`、`README.md`、`docs/protocol/incoming-call-capture.md`；只有测量发现确切问题时修改所属现有代码。

**Interfaces:** Consumes 前四任务可安装构建；Produces 第一阶段交付证据和结构复核。不是新一轮架构扩展。

- [ ] **Step 1: 完整回归。** 运行 `./gradlew -PmaichiFixtureDir=.maichi-fixtures :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest` 和 `python3 -B -m unittest discover -s tools -p 'test_*.py'`；在隔离模拟器运行全部设备测试。样本缺失、跳过、模拟通过和真实通过分别记录。
- [ ] **Step 2: 复核整体结构。** 检查连接、状态、声音和来电页是否各有唯一所有者；原位置逻辑是否真正删除；是否残留双写、转发层、双状态机、临时兼容入口或只为假设未来而增加的接口。控制器中如果出现真实独立且难以理解的职责，再针对该部分拆分；不能以控制器一个文件为目标塞入所有系统细节。
- [ ] **Step 3: 现场核心验收。** MatePad 核对持久地址和同时供电，完成前台/后台/锁屏来电、单向与双向声音、未接/接听/挂断/开锁、连续来电、旧通知、拔插网线/网卡、地址改变、权限撤销/恢复、重启首次解锁前后。物理开锁和到梯由现场观察确认；有/无安全锁均有明确结果，系统设置变更由用户参与。
- [ ] **Step 4: 按实测处理性能。** 网络地址就绪后恢复目标 5 秒，完整合法来电收到后铃声/展示目标 2 秒。先记录控制队列与 JPEG 解码耗时；只有实测控制响应不满足目标，才把现有解码移到一个有界工作队列，并直接更新现有 JpegAssembler/会话观察接口和测试，成功解码才刷新健康时间，不长期保留两套路径。当前监控“仅交付最新帧”做法复用于来电。
- [ ] **Step 5: 稳定性与交付。** 先完成 24 小时，再完成 72 小时观察，包含可追溯测试来电、息屏、网络变化和电源状态。未通过的真实接待、地址恢复、声音或门锁结果先修复，不能以回放替代，也不能进入人脸阶段。更新实际能力说明，建议 `docs: record dedicated intercom acceptance and operation`。

## Task 6: P5 在稳定接待上加入感知与低亮待机

**Files:**
- Create: `app/src/main/kotlin/com/maichimod/prototype/PresenceDetector.kt`、`app/src/main/kotlin/com/maichimod/prototype/ScreenPolicy.kt`。
- Modify: `app/src/main/kotlin/com/maichimod/prototype/IntercomController.kt`、`app/src/main/kotlin/com/maichimod/prototype/IntercomService.kt`、`app/src/main/kotlin/com/maichimod/prototype/MainActivity.kt`、`app/src/main/kotlin/com/maichimod/prototype/ConnectionSettingsView.kt`、`app/src/main/kotlin/com/maichimod/prototype/AppSettings.kt`、`app/src/main/res/layout/page_settings.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/AndroidManifest.xml`、`app/build.gradle.kts`。
- Test: `app/src/test/kotlin/com/maichimod/prototype/ScreenPolicyTest.kt`、`app/src/androidTest/kotlin/com/maichimod/prototype/PresenceDetectorTest.kt`。

**Interfaces:** Consumes 已通过任务 5 的运行状态；Produces `PresenceDetector.start(onPresence)`、`stop()`、`close()`，结果为 `PRESENT / ABSENT / UNKNOWN`；`ScreenPolicy` 以时间、通话/监控状态、最近触摸和存在结果为输入，只返回亮度/常亮与检测间隔。ScreenPolicy 是多种真实输入汇合的独立规则，不另造通用策略基类。

- [ ] **Step 1: 写失败用例。** 仅在第一期通过后开始，编写 `presenceForEightHundredMsRestoresBrightness`、`thirtySecondIdleDimsOnlyWithoutCallOrMonitor`、`unknownCameraUsesTouchFallback`、`oldAbsenceCannotDimNewCall`、`lowBatteryPausesDetectionOnly`、`detectorCanRestartAfterStop`；断言 800/30000 毫秒、5% 亮度及 10% 电量条件。覆盖 S01～S09，不重新设计门禁连接。
- [ ] **Step 2: 验证失败。** 运行 `./gradlew :app:testDebugUnitTest --tests '*ScreenPolicyTest'`；规则测试不需要相机、服务或真实门机。
- [ ] **Step 3: 最小接入。** 从 SmartFace 复用已核对的本地检测思路，修正暂停恢复和资源释放；CameraX core/camera2/lifecycle 稳定版 `1.6.2`、bundled ML Kit `16.1.7` 为本方案查询基线，执行时检查构建兼容后固定，不引入 Compose 或动态下载模型。相机从可见用户启用流程启动，必要时在现有 Service 按实际系统版本增加 camera 条件。感知失败回到触摸空闲策略，不影响门禁监听。
- [ ] **Step 4: 验证。** 检查暗光、遮挡、侧脸、相机占用、权限拒绝、夜间、来电抢占、停止后重启与功耗；检测最多 200 毫秒一次，稳定无人/夜间 2 秒一次。真正系统息屏后的感知唤醒单列真机验收，未通过则只开放应用内低亮，真实来电唤醒继续独立工作。
- [ ] **Step 5: 清理并提交。** 删除 Activity 或检测回调中直接控制亮度的重复分支，屏幕决定集中在 ScreenPolicy；建议 `feat: add presence-aware intercom standby`，更新实际支持范围。

## 场景覆盖与执行约束

| 场景 | 主要任务 | 完成依据 |
| --- | --- | --- |
| A01～A11 启用、配置、权限、不可用 | 1、2、3、4 | 统一状态、保存失败、设置返回重检和明确恢复入口 |
| N01～N11 网络与恢复 | 4、5 | 注入故障与真实网口恢复；旧任务不影响新连接 |
| C01～C14 来电与页面切换 | 2、3、4、5 | 重复操作、页面重建、旧通知、锁屏与连续来电 |
| M01～M07 音视频故障 | 2、3、5 | 局部降级、资源释放、实测控制响应和实际听音 |
| O01～O08 开锁、呼梯、监控 | 2、3、5 | 发送次数/结果边界、现有字节回放与物理结果 |
| S01～S09 屏幕、感知与供电 | 4、5、6 | 接待独立、纯屏幕规则、真机功耗与息屏范围 |

每个实现任务先写能证明行为的失败用例，再改代码并回归；提交只包含本任务拥有的文件。执行前核对当前未提交改动：现有来电功能本身包含未提交文件，不能从旧 HEAD 建新工作区后遗漏基础，也不能自动归并或回滚他人改动。每步保持可构建，必要的调用方迁移在同一任务完成，内部接口无需保留永久兼容层。

本轮不开发独立诊断应用、历史事件存储、日志分享、访客存档或设备管理框架；状态解释和恢复动作直接复用现有界面与日志。它们只有在真实维护需求出现时再单独评估。开发计划和场景数量不等于代码模块数量。

依赖依据：[AndroidX Test 稳定版本](https://developer.android.com/jetpack/androidx/releases/test)、[CameraX 稳定版本](https://developer.android.com/jetpack/androidx/releases/camera)、[本地 ML Kit 模型](https://developers.google.com/ml-kit/vision/face-detection/android)。系统约束与真实设备边界见设计文档的依据章节。
