#ifndef MOTRO_SNAPSHOT_MANAGER_H
#define MOTRO_SNAPSHOT_MANAGER_H

#include <cstdint>
#include <string>
#include <vector>

class SnapshotManager {
public:
    static std::string saveRgba(const std::string &outputPath,
                                const std::vector<uint8_t> &rgba,
                                int width,
                                int height,
                                int stride,
                                int64_t ptsUs);
};

#endif // MOTRO_SNAPSHOT_MANAGER_H