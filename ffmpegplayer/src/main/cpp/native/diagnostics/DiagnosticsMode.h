#ifndef MOTRO_DIAGNOSTICS_MODE_H
#define MOTRO_DIAGNOSTICS_MODE_H

#include <cctype>
#include <string>

enum class DiagnosticsMode {
    Off,
    Basic,
    Latency
};

inline const char *diagnosticsModeName(DiagnosticsMode mode) {
    switch (mode) {
        case DiagnosticsMode::Off: return "off";
        case DiagnosticsMode::Basic: return "basic";
        case DiagnosticsMode::Latency: return "latency";
    }
    return "basic";
}

inline bool parseDiagnosticsMode(const std::string &value, DiagnosticsMode &out) {
    std::string normalized;
    normalized.reserve(value.size());
    for (char c : value) {
        if (!std::isspace(static_cast<unsigned char>(c))) {
            normalized.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
        }
    }
    if (normalized == "off") {
        out = DiagnosticsMode::Off;
        return true;
    }
    if (normalized == "basic") {
        out = DiagnosticsMode::Basic;
        return true;
    }
    if (normalized == "latency") {
        out = DiagnosticsMode::Latency;
        return true;
    }
    return false;
}

#endif  // MOTRO_DIAGNOSTICS_MODE_H
