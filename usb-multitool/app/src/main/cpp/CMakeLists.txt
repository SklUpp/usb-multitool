cmake_minimum_required(VERSION 3.22.1)
project(usb_multitool_native C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)

set(FATFS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/fatfs/source)
set(EXFAT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/exfatprogs)
set(NTFS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ntfs-3g)

add_library(exfatprogs_fsck STATIC
    ${EXFAT_ROOT}/lib/libexfat.c
    ${EXFAT_ROOT}/lib/exfat_fs.c
    ${EXFAT_ROOT}/lib/exfat_dir.c
    ${EXFAT_ROOT}/lib/utils.c
    ${EXFAT_ROOT}/fsck/fsck.c
    ${EXFAT_ROOT}/fsck/repair.c
)
target_include_directories(exfatprogs_fsck PUBLIC
    ${EXFAT_ROOT}/include
    ${EXFAT_ROOT}/fsck
)
target_compile_definitions(exfatprogs_fsck PRIVATE
    _GNU_SOURCE
    _FILE_OFFSET_BITS=64
)
target_compile_options(exfatprogs_fsck PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
)

file(GLOB NTFS_SOURCES ${NTFS_DIR}/libntfs-3g/*.c)
list(FILTER NTFS_SOURCES EXCLUDE REGEX ".*win32_io\\.c$")

add_library(usb_native SHARED
    native-lib.cpp
    usb_block.c
    fat_engine.c
    fat_jni.cpp
    ${FATFS_DIR}/ff.c
    ${FATFS_DIR}/ffunicode.c
    ${FATFS_DIR}/ffsystem.c
    ${FATFS_DIR}/diskio.c
    exfat_fsck_jni.cpp
    ntfs_engine.c
    ntfs_jni.cpp
    ${NTFS_SOURCES}
)

target_include_directories(usb_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${NTFS_DIR}/libntfs-3g
    ${NTFS_DIR}/include
    ${NTFS_DIR}/include/ntfs-3g
    ${FATFS_DIR}
    ${EXFAT_ROOT}/include
    ${EXFAT_ROOT}/fsck
)

target_compile_definitions(usb_native PRIVATE
    HAVE_CONFIG_H=1
    _GNU_SOURCE
    _FILE_OFFSET_BITS=64
)

find_package(Threads REQUIRED)

if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(usb_native
        exfatprogs_fsck
        ${log-lib}
        Threads::Threads
    )
    target_link_options(usb_native PRIVATE "-Wl,-z,max-page-size=16384")
else()
    target_link_libraries(usb_native
        exfatprogs_fsck
        Threads::Threads
    )
endif()

if(NOT ANDROID)
    add_executable(exfat_fsck_host_test
        host/exfat_fsck_host_test.c
        usb_block.c
    )
    target_include_directories(exfat_fsck_host_test PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${EXFAT_ROOT}/include
        ${EXFAT_ROOT}/fsck
    )
    target_link_libraries(exfat_fsck_host_test
        exfatprogs_fsck
    )
endif()
