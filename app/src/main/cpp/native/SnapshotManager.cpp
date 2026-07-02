#include "SnapshotManager.h"

#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <cstddef>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <sys/stat.h>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/packet.h"
#include "libavutil/frame.h"
#include "libavutil/imgutils.h"
#include "libavutil/pixfmt.h"
#include "libswscale/swscale.h"
}

#define LOG_TAG "FFmpegNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string escapeJson(const std::string &value) {
    std::ostringstream out;
    for (char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    const char *hex = "0123456789abcdef";
                    out << "\\u00" << hex[(c >> 4) & 0x0f] << hex[c & 0x0f];
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

std::string jsonError(int errorCode, const std::string &message) {
    std::ostringstream out;
    out << "{\"success\":false,\"errorCode\":" << errorCode
        << ",\"errorMessage\":\"" << escapeJson(message) << "\"}";
    return out.str();
}

std::string jsonSuccess(const std::string &outputPath, int width, int height, int64_t ptsUs, const std::string &format) {
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"snapshot saved\","
        << "\"outputPath\":\"" << escapeJson(outputPath) << "\","
        << "\"width\":" << width << ","
        << "\"height\":" << height << ","
        << "\"ptsUs\":" << ptsUs << ","
        << "\"format\":\"" << format << "\"}";
    return out.str();
}

std::string lowerCopy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool endsWith(const std::string &value, const std::string &suffix) {
    if (value.size() < suffix.size()) {
        return false;
    }
    return lowerCopy(value.substr(value.size() - suffix.size())) == suffix;
}

std::string parentDirectory(const std::string &path) {
    const size_t slash = path.find_last_of("/\\");
    if (slash == std::string::npos) {
        return std::string();
    }
    if (slash == 0) {
        return path.substr(0, 1);
    }
    return path.substr(0, slash);
}

bool directoryExists(const std::string &path) {
    struct stat info = {};
    return !path.empty() && stat(path.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
}

void appendU32(std::vector<uint8_t> &data, uint32_t value) {
    data.push_back(static_cast<uint8_t>((value >> 24) & 0xff));
    data.push_back(static_cast<uint8_t>((value >> 16) & 0xff));
    data.push_back(static_cast<uint8_t>((value >> 8) & 0xff));
    data.push_back(static_cast<uint8_t>(value & 0xff));
}

uint32_t crc32(const uint8_t *data, size_t size) {
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < size; ++i) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 1u) ? (0xedb88320u ^ (crc >> 1u)) : (crc >> 1u);
        }
    }
    return crc ^ 0xffffffffu;
}

uint32_t adler32(const std::vector<uint8_t> &data) {
    static constexpr uint32_t MOD_ADLER = 65521;
    uint32_t a = 1;
    uint32_t b = 0;
    for (uint8_t byte : data) {
        a = (a + byte) % MOD_ADLER;
        b = (b + a) % MOD_ADLER;
    }
    return (b << 16u) | a;
}

void appendChunk(std::vector<uint8_t> &png, const char type[4], const std::vector<uint8_t> &payload) {
    appendU32(png, static_cast<uint32_t>(payload.size()));
    const size_t typeOffset = png.size();
    png.insert(png.end(), type, type + 4);
    png.insert(png.end(), payload.begin(), payload.end());
    const uint32_t crc = crc32(png.data() + typeOffset, 4 + payload.size());
    appendU32(png, crc);
}

std::string writeFile(const std::string &path, const std::vector<uint8_t> &bytes) {
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file.is_open()) {
        return jsonError(-1, "failed to open snapshot output file");
    }
    file.write(reinterpret_cast<const char *>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    if (!file.good()) {
        return jsonError(-1, "failed to write snapshot output file");
    }
    return std::string();
}

std::string savePng(const std::string &outputPath,
                    const std::vector<uint8_t> &rgba,
                    int width,
                    int height,
                    int stride,
                    int64_t ptsUs) {
    const size_t rowBytes = static_cast<size_t>(width) * 4u;
    std::vector<uint8_t> raw;
    raw.reserve((rowBytes + 1u) * static_cast<size_t>(height));
    for (int y = 0; y < height; ++y) {
        raw.push_back(0); // filter: none
        const uint8_t *row = rgba.data() + static_cast<size_t>(y) * static_cast<size_t>(stride);
        raw.insert(raw.end(), row, row + rowBytes);
    }

    std::vector<uint8_t> zlib;
    zlib.reserve(raw.size() + raw.size() / 65535u * 5u + 16u);
    zlib.push_back(0x78);
    zlib.push_back(0x01);
    size_t offset = 0;
    while (offset < raw.size()) {
        const uint16_t blockSize = static_cast<uint16_t>(std::min<size_t>(65535, raw.size() - offset));
        const bool finalBlock = offset + blockSize >= raw.size();
        zlib.push_back(finalBlock ? 0x01 : 0x00);
        zlib.push_back(static_cast<uint8_t>(blockSize & 0xff));
        zlib.push_back(static_cast<uint8_t>((blockSize >> 8) & 0xff));
        const uint16_t nlen = static_cast<uint16_t>(~blockSize);
        zlib.push_back(static_cast<uint8_t>(nlen & 0xff));
        zlib.push_back(static_cast<uint8_t>((nlen >> 8) & 0xff));
        zlib.insert(zlib.end(), raw.begin() + static_cast<std::ptrdiff_t>(offset), raw.begin() + static_cast<std::ptrdiff_t>(offset + blockSize));
        offset += blockSize;
    }
    appendU32(zlib, adler32(raw));

    std::vector<uint8_t> png;
    const uint8_t signature[] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    png.insert(png.end(), std::begin(signature), std::end(signature));

    std::vector<uint8_t> ihdr;
    appendU32(ihdr, static_cast<uint32_t>(width));
    appendU32(ihdr, static_cast<uint32_t>(height));
    ihdr.push_back(8); // bit depth
    ihdr.push_back(6); // RGBA
    ihdr.push_back(0); // compression
    ihdr.push_back(0); // filter
    ihdr.push_back(0); // interlace
    appendChunk(png, "IHDR", ihdr);
    appendChunk(png, "IDAT", zlib);
    appendChunk(png, "IEND", {});

    const std::string writeError = writeFile(outputPath, png);
    if (!writeError.empty()) {
        return writeError;
    }
    return jsonSuccess(outputPath, width, height, ptsUs, "png");
}

std::string saveJpeg(const std::string &outputPath,
                     const std::vector<uint8_t> &rgba,
                     int width,
                     int height,
                     int stride,
                     int64_t ptsUs) {
    const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_MJPEG);
    if (codec == nullptr) {
        return jsonError(-1, "FFmpeg mjpeg encoder is not available; use .png for snapshot");
    }

    AVCodecContext *codecContext = avcodec_alloc_context3(codec);
    if (codecContext == nullptr) {
        return jsonError(-1, "avcodec_alloc_context3 failed for mjpeg encoder");
    }
    codecContext->width = width;
    codecContext->height = height;
    codecContext->time_base = AVRational{1, 25};
    codecContext->pix_fmt = AV_PIX_FMT_YUVJ420P;
    codecContext->color_range = AVCOL_RANGE_JPEG;

    int result = avcodec_open2(codecContext, codec, nullptr);
    if (result < 0) {
        avcodec_free_context(&codecContext);
        return jsonError(result, "avcodec_open2 failed for mjpeg encoder");
    }

    AVFrame *frame = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();
    if (frame == nullptr || packet == nullptr) {
        av_frame_free(&frame);
        av_packet_free(&packet);
        avcodec_free_context(&codecContext);
        return jsonError(-1, "failed to allocate mjpeg frame/packet");
    }

    frame->format = codecContext->pix_fmt;
    frame->width = width;
    frame->height = height;
    result = av_frame_get_buffer(frame, 32);
    if (result < 0) {
        av_frame_free(&frame);
        av_packet_free(&packet);
        avcodec_free_context(&codecContext);
        return jsonError(result, "av_frame_get_buffer failed for mjpeg snapshot");
    }

    SwsContext *sws = sws_getContext(width, height, AV_PIX_FMT_RGBA,
                                     width, height, codecContext->pix_fmt,
                                     SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (sws == nullptr) {
        av_frame_free(&frame);
        av_packet_free(&packet);
        avcodec_free_context(&codecContext);
        return jsonError(-1, "sws_getContext failed for jpg snapshot");
    }

    const uint8_t *srcData[] = {rgba.data(), nullptr, nullptr, nullptr};
    const int srcLinesize[] = {stride, 0, 0, 0};
    sws_scale(sws, srcData, srcLinesize, 0, height, frame->data, frame->linesize);
    frame->pts = 0;

    result = avcodec_send_frame(codecContext, frame);
    if (result >= 0) {
        result = avcodec_receive_packet(codecContext, packet);
    }
    if (result < 0) {
        sws_freeContext(sws);
        av_frame_free(&frame);
        av_packet_free(&packet);
        avcodec_free_context(&codecContext);
        return jsonError(result, "failed to encode jpg snapshot");
    }

    std::vector<uint8_t> bytes(packet->data, packet->data + packet->size);
    const std::string writeError = writeFile(outputPath, bytes);

    sws_freeContext(sws);
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&codecContext);

    if (!writeError.empty()) {
        return writeError;
    }
    return jsonSuccess(outputPath, width, height, ptsUs, "jpg");
}

} // namespace

std::string SnapshotManager::saveRgba(const std::string &outputPath,
                                      const std::vector<uint8_t> &rgba,
                                      int width,
                                      int height,
                                      int stride,
                                      int64_t ptsUs) {
    LOGI("takePlayerSnapshot outputPath=%s", outputPath.c_str());

    if (outputPath.empty()) {
        return jsonError(-1, "outputPath is empty");
    }
    const std::string parent = parentDirectory(outputPath);
    if (!directoryExists(parent)) {
        return jsonError(-1, "outputPath parent directory does not exist: " + parent);
    }
    if (width <= 0 || height <= 0 || stride < width * 4 || rgba.empty()) {
        return jsonError(-1, "invalid snapshot frame");
    }

    if (endsWith(outputPath, ".png")) {
        return savePng(outputPath, rgba, width, height, stride, ptsUs);
    }
    if (endsWith(outputPath, ".jpg") || endsWith(outputPath, ".jpeg")) {
        return saveJpeg(outputPath, rgba, width, height, stride, ptsUs);
    }
    return jsonError(-1, "unsupported snapshot format; use .png or .jpg");
}