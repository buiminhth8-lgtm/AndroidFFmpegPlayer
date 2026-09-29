#include "native/PlayerRemuxRecorder.h"
#include "recorder_fakes/FakeFFmpeg.h"
#include <cstdlib>
#include <cstring>
#include <functional>
#include <future>
#include <iostream>
#include <stdexcept>
using namespace std::chrono_literals;

// Keep assertions enabled in Release too. A failed gate test aborts instead of
// destroying a future whose blocked worker would hide the original failure.
#define CHECK(condition) do { if (!(condition)) { std::cerr << __LINE__ << ": " #condition "\n"; std::abort(); } } while (0)
static int tests = 0;
static int64_t number(const std::string &json, const std::string &key) {
    const auto start = json.find("\"" + key + "\":");
    CHECK(start != std::string::npos);
    return std::stoll(json.substr(start + key.size() + 3));
}
static void contains(const std::string &text, const std::string &part) { CHECK(text.find(part) != std::string::npos); }
static void ready(std::future<void> &task) { CHECK(task.wait_for(2s) == std::future_status::ready); task.get(); }
static void send(PlayerRemuxRecorder &recorder, int stream, int64_t pts, bool key = true, int bytes = 32) {
    AVPacket *packet = fake::packet(stream, pts, key, bytes);
    recorder.onPacket(packet);
    // Producer releases immediately; worker must retain its own reference.
    av_packet_free(&packet);
}
static void setInput(PlayerRemuxRecorder &recorder, bool audio = true) {
    AVFormatContext *input = fake::input(audio);
    recorder.setInput(input);
    avformat_free_context(input);
}
static void noLeaks() {
    CHECK(fake::packets == 0); CHECK(fake::buffers == 0); CHECK(fake::contexts == 0);
    CHECK(fake::filters == 0); CHECK(fake::files == 0);
}
static void run(const char *name, const std::function<void()> &test) {
    fake::reset(); test(); noLeaks(); ++tests; std::cout << "PASS " << name << "\n";
}
int main() {
    run("idle/release/start without input", [] {
        PlayerRemuxRecorder recorder;
        contains(recorder.start("./test.mp4"), "\"success\":false");
        recorder.stop(); recorder.release(); recorder.release();
        setInput(recorder);
        contains(recorder.start("./test.mp4"), "recorder is released");
    });
    run("all formats, AAC, first keyframe, monitoring independent, repeated sessions", [] {
        PlayerRemuxRecorder recorder;
        setInput(recorder);
        for (const char *format : {"mp4", "mov", "mkv", "ts"}) {
            for (int iteration = 0; iteration < 3; ++iteration) {
                recorder.setAudioPlaybackState(iteration % 2 == 0);
                contains(recorder.start(std::string("./test.") + format), "\"success\":true");
                contains(recorder.start("./duplicate.mp4"), "already recording");
                send(recorder, 1, 0); send(recorder, 0, 0, false);
                send(recorder, 0, 40); send(recorder, 1, 40);
                const std::string stopped = recorder.stop();
                contains(stopped, "\"state\":\"stopped\"");
                CHECK(number(stopped, "videoPacketCount") == 1);
                CHECK(number(stopped, "audioPacketCount") == 1);
                CHECK(number(stopped, "packetsWritten") == 2);
                CHECK(number(stopped, "queuePackets") == 0);
                CHECK(number(stopped, "queueBytes") == 0);
                CHECK(number(stopped, "queueDrops") == 0);
            }
        }
        CHECK(fake::aacPackets == 6); CHECK(fake::headers == 12); CHECK(fake::trailers == 12);
        for (const auto &packet : fake::written()) { CHECK(packet.marker == 73); CHECK(packet.pts == 0); }
        for (const auto id : fake::ioThreads()) CHECK(id != std::this_thread::get_id());
    });
    run("blocked open does not block producer or state", [] {
        PlayerRemuxRecorder recorder; setInput(recorder);
        fake::blockIo("open");
        auto start = std::async(std::launch::async, [&] { contains(recorder.start("./test.mp4"), "\"success\":true"); });
        CHECK(fake::waitForIo());
        auto producer = std::async(std::launch::async, [&] { send(recorder, 0, 0); recorder.getState(); });
        ready(producer); fake::unblockIo(); ready(start); recorder.stop();
    });
    run("blocked write: bounded packet overflow and nonblocking stats", [] {
        PlayerRemuxRecorder recorder; setInput(recorder);
        recorder.start("./test.mp4"); fake::blockIo("write"); send(recorder, 0, 0);
        CHECK(fake::waitForIo());
        auto producer = std::async(std::launch::async, [&] {
            for (size_t i = 0; i <= PlayerRemuxRecorder::kMaxQueuePackets; ++i) send(recorder, 0, (i + 1) * 40);
            CHECK(!recorder.isRecording());
            const auto stats = recorder.getState();
            CHECK(number(stats, "queuePackets") == PlayerRemuxRecorder::kMaxQueuePackets);
            CHECK(number(stats, "queueHighWatermark") == PlayerRemuxRecorder::kMaxQueuePackets);
            CHECK(number(stats, "queueDrops") == 1);
        });
        ready(producer); fake::unblockIo();
        const auto stopped = recorder.stop();
        contains(stopped, "queue overflow"); contains(stopped, "\"state\":\"error\"");
        CHECK(number(stopped, "queueDrops") == PlayerRemuxRecorder::kMaxQueuePackets + 1);
        CHECK(number(stopped, "packetsWritten") == 1);
        contains(recorder.start("./restart.mp4"), "\"success\":true");
        send(recorder, 0, 0); recorder.stop();
    });
    run("byte overflow and oversized packet", [] {
        PlayerRemuxRecorder recorder; setInput(recorder); recorder.start("./test.ts");
        fake::blockIo("write"); send(recorder, 0, 0); CHECK(fake::waitForIo());
        for (int i = 0; i < 5; ++i) send(recorder, 0, (i + 1) * 40, true, 4 * 1024 * 1024);
        auto stats = recorder.getState();
        CHECK(number(stats, "queueBytes") == PlayerRemuxRecorder::kMaxQueueBytes);
        CHECK(number(stats, "queuePackets") == 4); CHECK(!recorder.isRecording());
        fake::unblockIo(); recorder.stop(); recorder.start("./large.ts");
        send(recorder, 0, 0, true, static_cast<int>(PlayerRemuxRecorder::kMaxQueueBytes + 1));
        stats = recorder.stop(); CHECK(number(stats, "queueHighWatermark") == 0); CHECK(number(stats, "queueDrops") == 1);
    });
    run("normal stop drains; concurrent stop/release never joins twice", [] {
        PlayerRemuxRecorder recorder; setInput(recorder); recorder.start("./test.mp4");
        fake::blockIo("write"); send(recorder, 0, 0); CHECK(fake::waitForIo());
        for (int i = 1; i < 20; ++i) send(recorder, 0, i * 40);
        auto stop = std::async(std::launch::async, [&] { recorder.stop(); });
        // Stop is synchronous, but producer and snapshot reads must stay live.
        auto release = std::async(std::launch::async, [&] { recorder.release(); });
        auto stats = std::async(std::launch::async, [&] { recorder.getState(); recorder.isRecording(); });
        ready(stats); fake::unblockIo(); ready(stop); ready(release);
        CHECK(fake::writes == 20); CHECK(fake::trailers == 1);
        contains(recorder.getState(), "\"state\":\"released\"");
    });
    run("packet side data participates in byte limit", [] {
        PlayerRemuxRecorder recorder; setInput(recorder); recorder.start("./test.ts");
        auto *packet = fake::packet(0, 0);
        packet->side_data_elems = 1;
        packet->side_data = static_cast<AVPacketSideData *>(std::calloc(1, sizeof(AVPacketSideData)));
        packet->side_data[0].size = PlayerRemuxRecorder::kMaxQueueBytes;
        packet->side_data[0].data = static_cast<uint8_t *>(std::calloc(PlayerRemuxRecorder::kMaxQueueBytes, 1));
        recorder.onPacket(packet); av_packet_free(&packet);
        const auto stats = recorder.stop();
        CHECK(number(stats, "queueDrops") == 1); CHECK(number(stats, "queueHighWatermark") == 0);
    });
    for (const char *operation : {"write", "flush", "close"}) {
        run(operation, [operation] {
            PlayerRemuxRecorder recorder; setInput(recorder); recorder.start("./test.mp4");
            fake::blockIo(operation);
            if (std::string(operation) == "write") fake::failWrite = true;
            send(recorder, 0, 0);
            std::future<void> stop;
            if (std::string(operation) == "close") stop = std::async(std::launch::async, [&] { recorder.stop(); });
            CHECK(fake::waitForIo());
            auto producer = std::async(std::launch::async, [&] {
                send(recorder, 0, 40); recorder.getState(); recorder.setAudioPlaybackState(true);
                contains(recorder.getState(), "\"audioPlaybackEnabled\":true");
            });
            ready(producer); fake::unblockIo();
            if (stop.valid()) ready(stop); else recorder.stop();
        });
    }
    run("active release drains and repeated empty sessions close exactly once", [] {
        PlayerRemuxRecorder recorder; setInput(recorder);
        for (int i = 0; i < 20; ++i) { recorder.start("./empty.mp4"); recorder.stop(); }
        recorder.start("./active.mp4"); send(recorder, 0, 0); send(recorder, 1, 0);
        recorder.release(); CHECK(fake::writes == 2); CHECK(fake::trailers == 21);
    });
    run("segment rotates on keyframes in worker; blocked trailer isolated", [] {
        PlayerRemuxRecorder recorder; setInput(recorder); recorder.startSegmented("./segment_%03d.mp4", 1);
        send(recorder, 0, 0); send(recorder, 0, 1200, false);
        fake::blockIo("trailer"); send(recorder, 0, 1500);
        CHECK(fake::waitForIo());
        auto producer = std::async(std::launch::async, [&] { send(recorder, 1, 1500); recorder.getState(); });
        ready(producer); fake::unblockIo();
        const auto stats = recorder.stop();
        CHECK(number(stats, "completedSegmentCount") == 2); CHECK(fake::headers == 2);
        CHECK(fake::trailers == 2); CHECK(number(stats, "packetsWritten") == 4);
    });
    run("reconnect keeps owned metadata and rebases timestamps", [] {
        PlayerRemuxRecorder recorder; setInput(recorder); recorder.start("./test.mp4");
        fake::blockIo("write"); send(recorder, 0, 1000); CHECK(fake::waitForIo());
        send(recorder, 1, 1000); recorder.clearInput(); setInput(recorder);
        send(recorder, 0, 0, false); send(recorder, 0, 40); send(recorder, 1, 40);
        fake::unblockIo(); auto stats = recorder.stop();
        CHECK(number(stats, "packetsWritten") == 4);
        const auto packets = fake::written(); CHECK(packets[2].dts > packets[0].dts);
        CHECK(packets[3].dts > packets[1].dts);
    });
    run("reconnect missing timestamps remain explicit and monotonic", [] {
        PlayerRemuxRecorder recorder; setInput(recorder); recorder.start("./test.mp4");
        fake::blockIo("write"); send(recorder, 0, 1000); CHECK(fake::waitForIo());
        setInput(recorder);
        auto *unknown = fake::packet(0, 0, true, 32);
        unknown->pts = unknown->dts = AV_NOPTS_VALUE;
        unknown->duration = 40;
        recorder.onPacket(unknown); av_packet_free(&unknown);
        send(recorder, 0, 0); send(recorder, 0, 40); send(recorder, 1, 40);
        fake::unblockIo(); const auto stats = recorder.stop();
        CHECK(number(stats, "writeErrors") == 0); CHECK(number(stats, "packetsWritten") == 5);
        const auto packets = fake::written();
        for (size_t i = 1; i < 4; ++i) {
            CHECK(packets[i].dts != AV_NOPTS_VALUE); CHECK(packets[i].pts >= packets[i].dts);
            CHECK(packets[i].dts > packets[i-1].dts);
        }
    });
    run("changed reconnect codec stops only recorder", [] {
        PlayerRemuxRecorder recorder; setInput(recorder); recorder.start("./test.ts");
        fake::blockIo("write"); send(recorder, 0, 0); CHECK(fake::waitForIo());
        auto *input = fake::input(); input->streams[0]->codecpar->width = 1280;
        recorder.setInput(input); avformat_free_context(input); send(recorder, 0, 40);
        fake::unblockIo(); contains(recorder.stop(), "codec parameters changed");
    });
    for (const char *failure : {"open", "write", "flush", "trailer", "close", "clone"}) {
        run(failure, [failure] {
            PlayerRemuxRecorder recorder; setInput(recorder);
            if (std::string(failure) == "open") fake::failOpen = true;
            auto started = recorder.start("./test.mp4");
            if (fake::failOpen) { contains(started, "\"success\":false"); recorder.stop(); return; }
            fake::failWrite = std::string(failure) == "write";
            fake::failFlush = std::string(failure) == "flush";
            fake::failTrailer = std::string(failure) == "trailer";
            fake::failClose = std::string(failure) == "close";
            fake::failClone = std::string(failure) == "clone";
            auto producer = std::async(std::launch::async, [&] { send(recorder, 0, 0); recorder.getState(); });
            ready(producer);
            const auto stats = recorder.stop(); contains(stats, "\"state\":\"error\"");
            CHECK(number(stats, "writeErrors") > 0); CHECK(number(stats, "queuePackets") == 0);
        });
    }
    std::cout << "ALL_RECORDER_ASYNC_TESTS_PASSED cases=" << tests << "\n";
}
