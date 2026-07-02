cmake_minimum_required(VERSION 3.22.1)

project(native_ffmpeg)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(FFMPEG_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/ffmpeg/include)
set(FFMPEG_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

include_directories(${FFMPEG_INCLUDE_DIR})

add_library(native-ffmpeg SHARED
        native-ffmpeg-jni.cpp
        native/NativePlayer.cpp
        native/VideoRenderer.cpp
        native/PlayerRemuxRecorder.cpp
        native/SnapshotManager.cpp)

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavutil.so)

add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswscale.so)

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavformat.so)

# Present in jniLibs, but not linked in this player phase.
add_library(avfilter SHARED IMPORTED)
set_target_properties(avfilter PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavfilter.so)

add_library(avdevice SHARED IMPORTED)
set_target_properties(avdevice PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavdevice.so)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(native-ffmpeg
        avformat
        avcodec
        avutil
        swscale
        ${log-lib}
        ${android-lib})
