#ifndef MOTRO_SNAPSHOT_MANAGER_H
#define MOTRO_SNAPSHOT_MANAGER_H

#include <cstdint>
#include <string>
#include <vector>

class SnapshotManager {
public:
    // 按扩展名将 RGBA 编码为 PNG 或 JPEG；stride 为每行字节数，ptsUs 为帧的媒体时间戳。
    static std::string saveRgba(const std::string &outputPath,
                                const std::vector<uint8_t> &rgba,
                                int width,
                                int height,
                                int stride,
                                int64_t ptsUs);
};

#endif // MOTRO_SNAPSHOT_MANAGER_H