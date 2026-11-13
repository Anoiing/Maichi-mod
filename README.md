# 麦驰直连

安卓原型提供「米家」「监控」「电梯」和「设置」四个入口，以 MatePad 11 大屏为主要适配目标。监控选择页横向排列单元门机、地库 1 和地库 2。调试版默认进入离线样本模式，可以播放抓包里的画面、展示门机占线，并查看呼梯的模拟回应。设置中可以开启模拟超时，或切到有线联调。新补的单元门来电收发记录已用于实现前台来电、接听、挂断、开锁请求和语音原型，实际门锁与双向声音仍需现场验收。

界面按 Mediora 的配色与控件规范实现纸白工作区、硬阴影和九种可切换主题。首页左侧为加粗时钟与日期，右侧上排为主题色米家入口和监控入口，下排为电梯和设置，配合轻量光束与流星背景。卡片尺寸保持既有比例，图标采用双色填面。监控选择保留三路平铺；网络状态只展示连接，不可点击。设置分为本机与网络、门机设备、声音与通话和外观，数值以纯文字展示，逐项编辑时才显示输入框，并支持全局主题即时保存。字体放大时内容可滚动，通话按钮随可用高度等比缩放。设计与验收见[平板界面记录](docs/superpowers/plans/2026-11-13-tablet-visual-redesign.md)。

## 构建调试包

使用 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。将本机 SDK 路径写入 `local.properties` 的 `sdk.dir`。本地样本由 TShark 导出，命令需要 `video.pcapng`、`rj3.pcapng`、`rj4.pcapng`、`rj3-ev.pcapng` 和 `1111111.pcapng` 放在同一目录。

```sh
python3 tools/export_capture.py --input-dir /path/to/captures --output-dir .maichi-fixtures
# 补充两个地库样本时，在上面的导出命令末尾追加：
# --garage-capture /path/to/333333.pcapng
# 取得地库 1 正常视频及请求后，还可追加以下两个参数：
# --garage-one-capture /path/to/dddddd3333.pcapng
# --garage-one-requests-capture /path/to/ddd33333.pcapng
# 补充单元门来电收发记录时，还可追加：
# --call-rx-capture /path/to/mmmmm11111111.pcapng
# --call-tx-capture /path/to/mmmmmm2222222.pcapng
(
  umask 077
  mkdir -p app/src/debug/assets/maichi-local
)
chmod 700 app/src/debug/assets/maichi-local
install -m 600 .maichi-fixtures/monitor-rx.tsv.gz app/src/debug/assets/maichi-local/monitor-rx.tsvz
install -m 600 .maichi-fixtures/elevator-rx.tsv.gz app/src/debug/assets/maichi-local/elevator-rx.tsvz
# 导出过地库样本后，一并加入调试包：
if [ -f .maichi-fixtures/monitor-basement-1-rx.tsv.gz ]; then
  install -m 600 .maichi-fixtures/monitor-basement-1-rx.tsv.gz app/src/debug/assets/maichi-local/monitor-basement-1-rx.tsvz
  install -m 600 .maichi-fixtures/monitor-basement-2-rx.tsv.gz app/src/debug/assets/maichi-local/monitor-basement-2-rx.tsvz
fi
if [ -f .maichi-fixtures/call-rx.tsv.gz ]; then
  install -m 600 .maichi-fixtures/call-rx.tsv.gz app/src/debug/assets/maichi-local/call-rx.tsvz
fi
./gradlew -PmaichiFixtureDir=.maichi-fixtures :app:testDebugUnitTest :app:assembleDebug
python3 -B -m unittest discover -s tools -p 'test_*.py'
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。导出的包头采用测试标识，带样本的调试包包含原抓包的监控画面，仅供本机调试。在 macOS 和 Linux 上，样本、调试 APK 和构建目录仅对当前用户开放。回归验证会将监控和呼梯的出站字节与样本对照，并解码单元门的 608 和 168 帧、地库 1 的 743 帧和地库 2 的 139 帧 JPEG。三个地库导出参数中，`--garage-capture` 使用按地库 1、地库 2 顺序出现的原始记录识别两台门机，后两个参数需要与它一起使用。地库 1 正常视频会更新默认回放，原来的占线记录独立保留做回归。

## 米家入口

首页主色卡点击后直接切换到已安装的米家 App，读取米家自身应用图标；未安装或无法启动时会显示提示。跨 App 返回后恢复本 App 的前台连接，后台来电接收仍属后续范围。

## 真机调试

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s Maichi
```

平板接入对讲网前，先验证扩展坞供电、有线 IPv4 和无线 ADB。切换时让原室内机离线，并先确认系统能够配置本户所需的以太网地址。打开 App 的「设置」，逐项编辑本机房号和门机编号，再在右上角的「连接方式」弹窗选择「有线联调」。App 保存的 IP 字段只用于选择、核对网口和构造协议；它不能替普通应用修改系统以太网地址。系统没有静态地址入口时，需要先解决网络配置这一前提。

本机 IP 和子网掩码留空时自动读取以太网。手动填写时，App 会核对系统的网口配置。门机 IP 留空时自动发现，填写后按地址筛选门机回应。网关、原机 MAC 和中心地址可作为本户联调记录保存。连接后设置页会显示平板实际读取到的以太网参数。

三个监控入口分别使用各自的门机编号和 IP。地库入口需要在设置中填写对应编号，IP 可以留空自动发现；本机房号和单元门机编号仍是有线连接的基础配置。呼梯始终使用单元门机。门机占线时界面提供重试，应用停止本次请求的周期包。MatePad 11 已通过有线网口验证三路实时出画；一次呼梯收到设备回应，用户现场确认电梯到达本层。

主动监控从每次发起查看、开始设备发现起最多持续 30 秒，到时发送结束请求并返回门机选择页。门机提前结束时也返回选择页。手动退出、切换门机或断开连接会取消本次计时；重新进入时重新计时。该限制只用于主动监控。两份新增地库记录的分析见[主动监控抓包](docs/protocol/active-monitor-captures.md)。

为其他住户构建时可用 `./gradlew :app:assembleRelease` 生成待签名的 APK。该构建默认进入有线模式，安装后每户各自填写参数。离线抓包样本放在调试构建专用目录中。

先验监控出画、退出和拔插恢复。预览页在有线恢复后可点击「重新连接」。有线端口发生异常时 App 会尝试重开端口，若仍失败可在设置页手动重连。再用一次正常呼梯核对请求、设备回包和到梯结果。每次呼梯由用户点击发起，重连不会补发上一条请求。已知回包没有可确认的请求编号，App 按对端和等待窗口匹配。抓包细节和验收项见[开发方案与计划](docs/superpowers/plans/2026-11-12-maichi-android-prototype.md)。

## 来电原型

单元门呼叫时，App 左侧显示视频，右侧上下排列两个大方按钮：「开锁」和「接听」。接听后，同一按钮切换为红色「挂断」。接听才启用语音；有线模式首次使用需允许麦克风权限。开锁只发送一次请求，发送提示不代表门锁已打开。「设置 → 声音与通话 → 开锁后自动挂断」默认开启：请求发出 3 秒后结束当前来电；关闭后保留画面，需手动挂断。重复开锁从最后一次请求重新计时。返回、挂断、离开前台或断网会取消计时，停止铃声、播放和录音。当前尚不支持后台、锁屏接听。

调试版处于离线样本模式时，可以从「设置 → 连接方式 → 模拟来电」播放新记录。演示模式会明确标示，接听只播放样本声音，不录制麦克风，也不向对讲网发送请求。来电没有主动监控的 30 秒上限；连续 8 秒没有匹配的控制或媒体数据时结束等待。

新来电样本的 670 帧 JPEG 已全部解码，645 条室内机出站握手、控制和语音报文已与编码逐条比对。语音目前按抓包推断的 8 kHz、16 位、小端、单声道 PCM 实现，仍待实际听音与双向通话校准。完整依据和边界见[来电抓包分析](docs/protocol/incoming-call-capture.md)。
