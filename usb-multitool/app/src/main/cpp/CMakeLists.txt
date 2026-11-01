cmake_minimum_required(VERSION 3.22.1)
project(usb_multitool_native)

set(NTFS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ntfs-3g)

file(GLOB NTFS_SOURCES ${NTFS_DIR}/libntfs-3g/*.c)
list(FILTER NTFS_SOURCES EXCLUDE REGEX ".*win32_io\\.c$")

add_library(usb_native SHARED
    native-lib.cpp
    usb_block.c
    ntfs_engine.c
    ntfs_jni.cpp
    ${NTFS_SOURCES}
)

target_include_directories(usb_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${NTFS_DIR}/include
    ${NTFS_DIR}/include/ntfs-3g
)

target_compile_definitions(usb_native PRIVATE
    HAVE_CONFIG_H=1
    _FILE_OFFSET_BITS=64
)

find_library(log-lib log)
target_link_libraries(usb_native ${log-lib})

# 16 KB page-size readiness for Android 15+.
target_link_options(usb_native PRIVATE "-Wl,-z,max-page-size=16384")
