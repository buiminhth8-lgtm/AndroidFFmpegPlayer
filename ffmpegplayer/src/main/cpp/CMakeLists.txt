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
        native/NativeYuvGlRenderer.cpp
        native/NativeOesRenderer.cpp
        native/NativeNv12GlRenderer.cpp
        native/PlayerOptions.cpp
        native/PlayerRemuxRecorder.cpp
        native/SnapshotManager.cpp
        native/ThermalConfig.cpp
        native/ThermalPaletteLut.cpp)

# Test-only native commands and their implementations are compiled exclusively
# for the Debug variant. Every other build type fails closed as production.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(FFMPEGPLAYER_TEST_HOOKS_VALUE 1)
else()
    set(FFMPEGPLAYER_TEST_HOOKS_VALUE 0)
endif()
target_compile_definitions(native-ffmpeg PRIVATE
        FFMPEGPLAYER_ENABLE_TEST_HOOKS=${FFMPEGPLAYER_TEST_HOOKS_VALUE})
message(STATUS
        "FFMPEGPLAYER_ENABLE_TEST_HOOKS=${FFMPEGPLAYER_TEST_HOOKS_VALUE} (CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE})")

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavutil.so)

add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswscale.so)

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswresample.so)

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavformat.so)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

target_link_libraries(native-ffmpeg
        avformat
        avcodec
        avutil
        swresample
        swscale
        ${log-lib}
        ${android-lib}
        ${egl-lib}
        ${glesv2-lib})
