# RTSP AAC 重连矩阵工具

`rtsp_reconnect_matrix.py` 驱动 Debug 专用的 `RtspMatrixActivity`，采集播放器事件、stats、主线程心跳、Recorder 队列统计和进程资源。URL 与凭据只从本地配置经 stdin 写入应用私有目录；事件和 native log 会把 RTSP URL 替换为 `rtsp://[REDACTED]`。

## 准备

1. 安装带 Debug harness 的 APK：

   ```powershell
   .\gradlew.bat :app:assembleDebug
   adb -s ANDROID_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
   ```

2. 复制 `tools/rtsp_matrix_config.example.json` 到仓库外的本地文件，填写 adb serial、RTSP URL 和可用的故障控制命令。命令必须写成 argv 数组，不经过 shell 展开；运行器只向它们提供 `RTSP_MATRIX_SERIAL` 和 `RTSP_MATRIX_CASE` 两个环境变量。

3. 确保 RTSP 源实际发送 H264/H265 视频包和 AAC 音频包。仅在 SDP 中声明 AAC 不满足基线；工具会拒绝把无 AAC 数据的会话计为通过。

## 执行

先验证 TCP/UDP、Audio ON/OFF、Recording ON/OFF 的 8 种基线：

```powershell
python tools/rtsp_reconnect_matrix.py --config C:\local\rtsp-matrix.json `
  --output build\rtsp-baseline --baseline `
  --case R01 --case R02 --case R03 --case R04 `
  --case R05 --case R06 --case R07 --case R08
```

自动故障入口：

```powershell
python tools/rtsp_reconnect_matrix.py --config C:\local\rtsp-matrix.json `
  --output build\rtsp-matrix --run
```

需要拔网线等人工操作时使用 `--manual`。运行器会提示执行和恢复故障；必须保持 USB adb 在线：

```powershell
python tools/rtsp_reconnect_matrix.py --config C:\local\rtsp-matrix.json `
  --output build\rtsp-matrix --run --manual --resume --case R09
```

`--resume` 保留同一输出目录中此前完成的 Case。每个 Case 默认执行三轮，结果写入 `results.json`、`results.csv`、`RTSP_RECONNECT_MATRIX.md`，原始事件、脱敏 native log、录制文件和 ffprobe JSON 位于 Case 的 UUID 子目录。

故障命令语义如下：

- `network`：阻断播放器设备到 RTSP 地址的网络，恢复相同路由。
- `physical_link`：拔出/禁用真实链路后恢复；通常配合 `--manual`。
- `service_restart`：停止 RTSP 服务后重新启动，URL 保持不变。
- `source_404`：让同一个 URL 明确返回 404，再恢复资源。连接拒绝、超时或 DNS 失败不能替代 404。

首次打开即 404 的补充验证可选任意 R25–R32：在启动前让同一 URL 返回 404，确认日志先出现 `waiting_source` 且 `source404=true`，然后恢复资源。播放器应持续按 1/2/4/5 秒退避，并在资源恢复后发出 `reconnect_success`；首次 404 不应进入永久 ERROR。

## 判定

Case 只有在真实故障注入和恢复都完成后才可能 PASS。工具检查：状态事件顺序、退避、恢复后视频递增、Audio ON 的 PCM/AudioClock 代次、Audio OFF 不启动 worker、Recorder AAC/视频写入和分片轮转、零 queue drop/write error、ffprobe 可读、主线程心跳以及 FD/线程/native heap 增长。

Audio ON 的自动指标通过后，人工模式仍要求听音确认没有旧 PCM、明显爆音或永久静音。没有实际故障、没有 AAC 包、基线不稳定或缺少人工听音确认都会保持 `NOT_RUN`，不会生成虚假 PASS。

运行工具自身测试：

```powershell
python -m unittest discover -s tools -p test_rtsp_reconnect_matrix.py -v
```
