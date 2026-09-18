#pragma once
#ifdef _MSC_VER
#include <sys/stat.h>
#define S_ISDIR(m) (((m) & _S_IFMT) == _S_IFDIR)
#endif
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
inline int __android_log_print(int, const char *, const char *, ...) { return 0; }
