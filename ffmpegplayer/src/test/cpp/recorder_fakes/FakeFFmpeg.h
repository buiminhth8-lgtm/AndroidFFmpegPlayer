#pragma once
#include <atomic>
#include <chrono>
#include <string>
#include <thread>
#include <vector>
extern "C" {
#include "libavcodec/packet.h"
#include "libavformat/avformat.h"
}

// Link-time fake only: exercises production recorder code without Android/FFmpeg binaries.
// Does not validate real container bytes. Gates deterministically model blocking storage.
namespace fake {
struct WrittenPacket { int stream; int64_t pts; int64_t dts; int marker; };
extern std::atomic<int> packets, buffers, contexts, filters, files;
extern std::atomic<int> writes, headers, trailers, aacPackets;
extern std::atomic<bool> failWrite, failOpen, failFlush, failClose, failTrailer, failClone;
void reset();
void blockIo(const std::string &operation);
bool waitForIo();
void unblockIo();
std::vector<WrittenPacket> written();
std::vector<std::thread::id> ioThreads();
AVFormatContext *input(bool audio = true);
AVPacket *packet(int stream, int64_t pts, bool key = true, int bytes = 32);
}
