#include "FakeFFmpeg.h"
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <cstdio>
extern "C" {
#include "libavcodec/bsf.h"
#include "libavutil/buffer.h"
#include "libavutil/channel_layout.h"
#include "libavutil/dict.h"
#include "libavutil/error.h"
#include "libavutil/mathematics.h"
#include "libavutil/time.h"
}
namespace {
std::mutex gateMutex;
std::condition_variable gateCv;
std::string blockedOperation;
bool entered = false;
std::vector<fake::WrittenPacket> output;
std::vector<std::thread::id> threadIds;
void io(const char *operation) {
    std::unique_lock<std::mutex> lock(gateMutex);
    threadIds.push_back(std::this_thread::get_id());
    if (blockedOperation == operation) {
        entered = true;
        gateCv.notify_all();
        gateCv.wait(lock, [] { return blockedOperation.empty(); });
    }
}
struct Buffer { std::atomic<int> refs{1}; uint8_t *data; };
struct Filter { AVPacket *pending = nullptr; };
}
namespace fake {
std::atomic<int> packets{0}, buffers{0}, contexts{0}, filters{0}, files{0};
std::atomic<int> writes{0}, headers{0}, trailers{0}, aacPackets{0};
std::atomic<bool> failWrite{false}, failOpen{false}, failFlush{false}, failClose{false}, failTrailer{false}, failClone{false};
void reset() {
    writes = 0; headers = 0; trailers = 0; aacPackets = 0;
    failWrite = false; failOpen = false; failFlush = false; failClose = false; failTrailer = false; failClone = false;
    std::lock_guard<std::mutex> lock(gateMutex);
    output.clear(); threadIds.clear(); blockedOperation.clear(); entered = false;
}
void blockIo(const std::string &operation) {
    std::lock_guard<std::mutex> lock(gateMutex);
    blockedOperation = operation; entered = false;
}
bool waitForIo() {
    std::unique_lock<std::mutex> lock(gateMutex);
    return gateCv.wait_for(lock, std::chrono::seconds(3), [] { return entered; });
}
void unblockIo() {
    std::lock_guard<std::mutex> lock(gateMutex);
    blockedOperation.clear(); gateCv.notify_all();
}
std::vector<WrittenPacket> written() { std::lock_guard<std::mutex> lock(gateMutex); return output; }
std::vector<std::thread::id> ioThreads() { std::lock_guard<std::mutex> lock(gateMutex); return threadIds; }
AVFormatContext *input(bool audio) {
    auto *ctx = avformat_alloc_context();
    auto *v = avformat_new_stream(ctx, nullptr);
    v->codecpar->codec_type = AVMEDIA_TYPE_VIDEO;
    v->codecpar->codec_id = AV_CODEC_ID_H264;
    v->codecpar->width = 640; v->codecpar->height = 480;
    v->time_base = {1, 1000};
    if (audio) {
        auto *a = avformat_new_stream(ctx, nullptr);
        a->codecpar->codec_type = AVMEDIA_TYPE_AUDIO;
        a->codecpar->codec_id = AV_CODEC_ID_AAC;
        a->codecpar->sample_rate = 48000;
        a->codecpar->ch_layout.nb_channels = 2;
        a->time_base = {1, 1000};
    }
    return ctx;
}
AVPacket *packet(int stream, int64_t pts, bool key, int bytes) {
    auto *p = av_packet_alloc();
    auto *buffer = new Buffer;
    buffer->data = static_cast<uint8_t *>(std::calloc(bytes > 0 ? bytes : 1, 1));
    buffer->data[0] = 73;
    p->buf = new AVBufferRef{};
    p->buf->buffer = reinterpret_cast<AVBuffer *>(buffer);
    p->buf->data = buffer->data; p->buf->size = bytes;
    p->data = buffer->data; p->size = bytes;
    p->stream_index = stream; p->pts = pts; p->dts = pts; p->duration = 40;
    p->flags = key ? AV_PKT_FLAG_KEY : 0;
    ++buffers;
    return p;
}
}
extern "C" {
AVPacket *av_packet_alloc() { ++fake::packets; auto *p = new AVPacket{}; p->pts = p->dts = AV_NOPTS_VALUE; return p; }
void av_packet_unref(AVPacket *p) {
    if (p->buf) {
        auto *b = reinterpret_cast<Buffer *>(p->buf->buffer);
        if (--b->refs == 0) { std::free(b->data); delete b; --fake::buffers; }
        delete p->buf;
    }
    for (int i = 0; i < p->side_data_elems; ++i) std::free(p->side_data[i].data);
    std::free(p->side_data);
    *p = {}; p->pts = p->dts = AV_NOPTS_VALUE;
}
void av_packet_free(AVPacket **p) { if (*p) { av_packet_unref(*p); delete *p; *p = nullptr; --fake::packets; } }
int av_packet_ref(AVPacket *dst, const AVPacket *src) {
    *dst = *src;
    if (src->buf) {
        dst->buf = new AVBufferRef(*src->buf);
        ++reinterpret_cast<Buffer *>(src->buf->buffer)->refs;
    }
    dst->side_data = nullptr;
    if (src->side_data_elems) {
        dst->side_data = static_cast<AVPacketSideData *>(std::calloc(src->side_data_elems, sizeof(AVPacketSideData)));
        for (int i = 0; i < src->side_data_elems; ++i) {
            dst->side_data[i] = src->side_data[i];
            dst->side_data[i].data = static_cast<uint8_t *>(std::malloc(src->side_data[i].size));
            std::memcpy(dst->side_data[i].data, src->side_data[i].data, src->side_data[i].size);
        }
    }
    return 0;
}
AVPacket *av_packet_clone(const AVPacket *p) { if (fake::failClone) return nullptr; auto *copy = av_packet_alloc(); av_packet_ref(copy, p); return copy; }
int64_t av_rescale_q(int64_t value, AVRational from, AVRational to) { return value * from.num * to.den / from.den / to.num; }
void av_packet_rescale_ts(AVPacket *p, AVRational from, AVRational to) {
    if (p->pts != AV_NOPTS_VALUE) p->pts = av_rescale_q(p->pts, from, to);
    if (p->dts != AV_NOPTS_VALUE) p->dts = av_rescale_q(p->dts, from, to);
    p->duration = av_rescale_q(p->duration, from, to);
}
AVFormatContext *avformat_alloc_context() { ++fake::contexts; return new AVFormatContext{}; }
AVStream *avformat_new_stream(AVFormatContext *ctx, const AVCodec *) {
    auto *s = new AVStream{}; s->codecpar = new AVCodecParameters{}; s->index = ctx->nb_streams;
    ctx->streams = static_cast<AVStream **>(std::realloc(ctx->streams, sizeof(AVStream *) * (ctx->nb_streams + 1)));
    ctx->streams[ctx->nb_streams++] = s; return s;
}
void avformat_free_context(AVFormatContext *ctx) {
    if (!ctx) return;
    for (unsigned i = 0; i < ctx->nb_streams; ++i) {
        std::free(ctx->streams[i]->codecpar->extradata);
        delete ctx->streams[i]->codecpar; delete ctx->streams[i];
    }
    std::free(ctx->streams); delete ctx->oformat; delete ctx; --fake::contexts;
}
int avcodec_parameters_copy(AVCodecParameters *dst, const AVCodecParameters *src) {
    std::free(dst->extradata); *dst = *src; dst->extradata = nullptr;
    if (src->extradata_size) {
        dst->extradata = static_cast<uint8_t *>(std::malloc(src->extradata_size));
        std::memcpy(dst->extradata, src->extradata, src->extradata_size);
    }
    return 0;
}
int av_channel_layout_compare(const AVChannelLayout *a, const AVChannelLayout *b) {
    if (a->nb_channels <= 0 || b->nb_channels <= 0) return AVERROR(EINVAL);
    return a->nb_channels == b->nb_channels ? 0 : 1;
}
const char *avcodec_get_name(AVCodecID) { return "test_codec"; }
int avformat_alloc_output_context2(AVFormatContext **ctx, const AVOutputFormat *, const char *format, const char *) {
    *ctx = avformat_alloc_context(); auto *out = new AVOutputFormat{};
    out->name = format && std::strcmp(format, "mov") == 0 ? "mov"
                : format && std::strcmp(format, "mpegts") == 0 ? "mpegts"
                : format && std::strcmp(format, "matroska") == 0 ? "matroska" : "mp4";
    (*ctx)->oformat = out; return 0;
}
int avio_open(AVIOContext **ctx, const char *, int) {
    io("open"); if (fake::failOpen) return AVERROR(EIO);
    *ctx = new AVIOContext{}; ++fake::files; return 0;
}
int avformat_write_header(AVFormatContext *, AVDictionary **) { io("header"); ++fake::headers; return 0; }
int av_interleaved_write_frame(AVFormatContext *, AVPacket *p) {
    io("write");
    if (fake::failWrite) { av_packet_unref(p); return AVERROR(EIO); }
    { std::lock_guard<std::mutex> lock(gateMutex); output.push_back({p->stream_index, p->pts, p->dts, p->data ? p->data[0] : -1}); }
    ++fake::writes; av_packet_unref(p); return 0;
}
void avio_flush(AVIOContext *ctx) { io("flush"); if (fake::failFlush) ctx->error = AVERROR(EIO); }
int av_write_trailer(AVFormatContext *) { io("trailer"); ++fake::trailers; return fake::failTrailer ? AVERROR(EIO) : 0; }
int avio_closep(AVIOContext **ctx) { io("close"); if (*ctx) { delete *ctx; *ctx = nullptr; --fake::files; } return fake::failClose ? AVERROR(EIO) : 0; }
int av_strerror(int, char *buffer, size_t size) { std::snprintf(buffer, size, "injected I/O error"); return 0; }
int64_t av_gettime_relative() { return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
int av_dict_set(AVDictionary **, const char *, const char *, int) { return 0; }
AVDictionaryEntry *av_dict_get(const AVDictionary *, const char *, const AVDictionaryEntry *, int) { return nullptr; }
void av_dict_free(AVDictionary **) {}
const AVBitStreamFilter *av_bsf_get_by_name(const char *name) { static AVBitStreamFilter filter{}; return std::strcmp(name, "aac_adtstoasc") == 0 ? &filter : nullptr; }
int av_bsf_alloc(const AVBitStreamFilter *, AVBSFContext **ctx) {
    *ctx = new AVBSFContext{}; (*ctx)->par_in = new AVCodecParameters{}; (*ctx)->par_out = new AVCodecParameters{};
    (*ctx)->priv_data = new Filter; ++fake::filters; return 0;
}
int av_bsf_init(AVBSFContext *ctx) { ctx->time_base_out = ctx->time_base_in; return avcodec_parameters_copy(ctx->par_out, ctx->par_in); }
int av_bsf_send_packet(AVBSFContext *ctx, AVPacket *p) {
    auto *filter = static_cast<Filter *>(ctx->priv_data);
    filter->pending = av_packet_clone(p); av_packet_unref(p); ++fake::aacPackets; return 0;
}
int av_bsf_receive_packet(AVBSFContext *ctx, AVPacket *p) {
    auto *filter = static_cast<Filter *>(ctx->priv_data);
    if (!filter->pending) return AVERROR(EAGAIN);
    av_packet_ref(p, filter->pending); av_packet_free(&filter->pending); return 0;
}
void av_bsf_flush(AVBSFContext *ctx) { av_packet_free(&static_cast<Filter *>(ctx->priv_data)->pending); }
void av_bsf_free(AVBSFContext **ctx) {
    if (!*ctx) return;
    av_bsf_flush(*ctx); delete static_cast<Filter *>((*ctx)->priv_data);
    std::free((*ctx)->par_in->extradata); std::free((*ctx)->par_out->extradata);
    delete (*ctx)->par_in; delete (*ctx)->par_out; delete *ctx; *ctx = nullptr; --fake::filters;
}
}
