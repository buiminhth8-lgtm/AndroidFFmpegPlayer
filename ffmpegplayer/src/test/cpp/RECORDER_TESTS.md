# Recorder 异步录制测试

线程模型：`onPacket()` 只在短时队列锁下克隆 AVPacket 引用；唯一 Remux Worker 负责打开、写 header、AAC BSF、关键帧门控、时间戳重写、分片、flush、trailer 和 close。Worker 做 I/O 时不持队列锁。独立生命周期锁只串行化 start/stop/release，播放线程不获取它。Java 录制启停也不跨原生等待持有事件回调锁。

输入打开后复制 codec parameters（含 extradata）和 time base；队列项持有对应快照，重连关闭输入不会让 Worker 悬空访问。兼容的重连会重新等待关键帧，并将新时间戳衔接到已写 DTS；流布局或编码参数改变则停止录制，播放继续。

队列上限：512 packets 且 16 MiB（引用缓冲区大小加 side data）。最多另有一个已出队的包正在处理。溢出不等待、不任意丢弃 GOP 中间帧后继续写；关闭入队，记录错误，Worker 丢弃剩余队列并完成文件关闭。正常 stop/release 则关闭入队、排空队列、写 trailer/close 并 join。写入错误会停止录制、丢弃剩余包，保留错误统计。所有 packet 引用用 RAII 管理。

`getRecordState()` 返回队列大小、字节数、丢弃数、包数/字节高水位、上限、写入数及错误数。现有播放器 stats 的 `recorder` 对象提供同一快照，原有录制统计字段保留。

## Host tests

在仓库根目录执行（Windows 可在 Visual Studio Developer Command Prompt 下使用 Ninja）：

```sh
cmake -S ffmpegplayer/src/test/cpp -B build/recorder-tests -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build build/recorder-tests --config RelWithDebInfo
ctest --test-dir build/recorder-tests -C RelWithDebInfo --output-on-failure
```

可添加 `-DRECORDER_ASAN=ON` 检查 packet/快照内存访问。Windows ASan 测试进程需要 Visual Studio 编译器运行库所在目录位于 PATH 中。

`RecorderAsyncTest` 编译真正的 PlayerRemuxRecorder.cpp，链接可控制 I/O 阻塞/失败的 FFmpeg 替身；不往用户磁盘写媒体文件。测试使用条件变量建立阻塞点，而非靠 sleep 猜测线程顺序，覆盖：

- packet 引用寿命、首关键帧、音频监听独立性、四种格式路径、AAC BSF、反复启停与活动 release；
- 包数、字节数、side data 和单个超大包的边界；
- 写盘/flush/分片 trailer/close 阻塞期间生产者与统计查询仍完成；
- 同时 stop/release、正常排空、溢出丢弃、写失败后回收；
- 重连后旧流参数寿命、时间戳衔接、编码参数变化；
- open/write/flush/trailer/close/packet clone 失败。

每个用例结束都断言 packet、底层引用缓冲区、格式上下文、BSF 和文件句柄计数归零。测试同时验证输出 I/O 不在生产者线程运行。

## Android / 真实媒体验证

```sh
./gradlew :ffmpegplayer:assembleDebug :ffmpegplayer:assembleRelease :app:testDebugUnitTest
```

Host 替身不验证真实容器字节和 AAC 转换结果；仍需在 Android 设备用实际音视频源录制 MP4/MOV/MKV/TS、分片和重连，使用 ffprobe/播放器校验输出。同步 stop/release 会等待当前底层磁盘调用返回；若操作系统 I/O 永久挂起，C++ 无法安全强杀该调用，但播放线程和队列上限不依赖它返回。
