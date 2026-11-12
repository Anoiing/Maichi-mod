# 麦驰直连

安卓原型提供「监控」「电梯」和「设置」三个入口，以 MatePad 11 大屏为主要适配目标。调试版默认进入离线样本模式，可以播放抓包里的画面，并查看呼梯的模拟回应。设置中可以开启模拟超时，或切到有线联调。来电和双向对讲随补抓包接入。

## 构建调试包

使用 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。将本机 SDK 路径写入 `local.properties` 的 `sdk.dir`。本地样本由 TShark 导出，命令需要 `video.pcapng`、`rj3.pcapng`、`rj4.pcapng`、`rj3-ev.pcapng` 和 `1111111.pcapng` 放在同一目录。

```sh
python3 tools/export_capture.py --input-dir /path/to/captures --output-dir .maichi-fixtures
(
  umask 077
  mkdir -p app/src/debug/assets/maichi-local
)
chmod 700 app/src/debug/assets/maichi-local
install -m 600 .maichi-fixtures/monitor-rx.tsv.gz app/src/debug/assets/maichi-local/monitor-rx.tsvz
install -m 600 .maichi-fixtures/elevator-rx.tsv.gz app/src/debug/assets/maichi-local/elevator-rx.tsvz
./gradlew -PmaichiFixtureDir=.maichi-fixtures :app:testDebugUnitTest :app:assembleDebug
python3 -B -m unittest discover -s tools -p 'test_*.py'
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。导出的包头采用测试标识，带样本的调试包包含原抓包的监控画面，仅供本机调试。在 macOS 和 Linux 上，样本、调试 APK 和构建目录仅对当前用户开放。回归验证会将监控和呼梯的出站字节与样本对照，并解码两份抓包中的 JPEG 帧。

## 真机调试

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s Maichi
```

平板接入对讲网前，先验证扩展坞供电、有线 IPv4 和无线 ADB。切换时让原室内机离线，在平板的以太网设置里配置本户地址。打开 App 的「设置」，逐项编辑本机房号和门机编号，再在右上角的「连接方式」弹窗选择「有线联调」。

本机 IP 和子网掩码留空时自动读取以太网。手动填写时，App 会核对系统的网口配置。门机 IP 留空时自动发现，填写后按地址筛选门机回应。网关、原机 MAC 和中心地址可作为本户联调记录保存。连接后设置页会显示平板实际读取到的以太网参数。

为其他住户构建时可用 `./gradlew :app:assembleRelease` 生成待签名的 APK。该构建默认进入有线模式，安装后每户各自填写参数。离线抓包样本放在调试构建专用目录中。

先验监控出画、退出和拔插恢复。预览页在有线恢复后可点击「重新连接」。有线端口发生异常时 App 会尝试重开端口，若仍失败可在设置页手动重连。再用一次正常呼梯核对请求、设备回包和到梯结果。每次呼梯由用户点击发起，重连不会补发上一条请求。已知回包没有可确认的请求编号，App 按对端和等待窗口匹配。抓包细节和验收项见[开发方案与计划](docs/superpowers/plans/2026-11-12-maichi-android-prototype.md)。
