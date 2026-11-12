cmake_minimum_required(VERSION 3.22.1)
project(usb_multitool_native C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)

set(FATFS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/fatfs/source)
set(EXFAT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/exfatprogs)
set(NTFS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ntfs-3g)
set(NTFS_PROGS_DIR ${NTFS_DIR}/ntfsprogs)
set(LWEXT4_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/lwext4)
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

file(GLOB LWEXT4_SOURCES ${LWEXT4_DIR}/src/*.c)


option(USB_REQUIRE_ENGINES "Fail configure when third_party engines are missing" ON)
option(USB_WITH_NTFS "Build NTFS engine (requires third_party/ntfs-3g)" ON)
option(USB_WITH_LWEXT4 "Build EXT engine (requires third_party/lwext4)" ON)
option(USB_WITH_FATFS "Build FatFs engine (requires third_party/fatfs)" ON)
option(USB_WITH_SECTORFORGE "Build the SectorForge sf-core rescue engine (requires app/src/main/rust/sf-bridge)" ON)

# REQUIRE implies every WITH flag: configure must fail if any engine source is absent.
if(USB_REQUIRE_ENGINES)
    set(USB_WITH_NTFS ON CACHE BOOL "Build NTFS engine (requires third_party/ntfs-3g)" FORCE)
    set(USB_WITH_LWEXT4 ON CACHE BOOL "Build EXT engine (requires third_party/lwext4)" FORCE)
    set(USB_WITH_FATFS ON CACHE BOOL "Build FatFs engine (requires third_party/fatfs)" FORCE)
    set(USB_WITH_SECTORFORGE ON CACHE BOOL "Build the SectorForge sf-core rescue engine" FORCE)
endif()

if(NOT EXISTS "${FATFS_DIR}/ff.c")
    if(USB_REQUIRE_ENGINES OR USB_WITH_FATFS)
        message(FATAL_ERROR
            "FatFs sources missing at ${FATFS_DIR}/ff.c — run scripts/fetch-third-party.sh")
    endif()
endif()
if(USB_WITH_NTFS AND NOT NTFS_SOURCES)
    message(FATAL_ERROR
        "ntfs-3g sources missing under ${NTFS_DIR}/libntfs-3g — run scripts/fetch-third-party.sh")
endif()
if(USB_WITH_NTFS AND NOT EXISTS "${NTFS_PROGS_DIR}/mkntfs.c")
    message(FATAL_ERROR
        "ntfs-3g ntfsprogs/mkntfs.c missing — run scripts/fetch-third-party.sh")
endif()
if(USB_WITH_NTFS)
    file(STRINGS "${NTFS_PROGS_DIR}/mkntfs.c" NTFS_MKNTFS_LIB_MARKER
        REGEX "mkntfs_format_device")
    if(NOT NTFS_MKNTFS_LIB_MARKER)
        message(FATAL_ERROR
            "ntfs-3g mkntfs library patch missing — run scripts/fetch-third-party.sh")
    endif()
endif()
if(USB_WITH_LWEXT4 AND NOT LWEXT4_SOURCES)
    message(FATAL_ERROR
        "lwext4 sources missing under ${LWEXT4_DIR}/src — run scripts/fetch-third-party.sh")
endif()


set(USB_NATIVE_SOURCES
    native-lib.cpp
    usb_block.c
    exfat_fsck_jni.cpp
)
set(USB_NATIVE_INCLUDES
    ${CMAKE_CURRENT_SOURCE_DIR}
)
set(USB_NATIVE_DEFS
    _GNU_SOURCE
    _FILE_OFFSET_BITS=64
)
set(USB_NATIVE_LIBS "")

# SectorForge sf-core rescue engine: the Rust bridge is built by cargo (Gradle task
# buildSfBridge) and linked in; without it the Kotlin side reports "engine
# unavailable" instead of falling back to a different implementation.
if(USB_WITH_SECTORFORGE)
    if(EXISTS "${SF_BRIDGE_LIB}")
        list(APPEND USB_NATIVE_SOURCES sf_bridge_jni.cpp)
        list(APPEND USB_NATIVE_DEFS USB_WITH_SECTORFORGE=1)
        list(APPEND USB_NATIVE_LIBS "${SF_BRIDGE_LIB}")
    elseif(ANDROID)
        message(FATAL_ERROR
            "SectorForge bridge static library missing at '${SF_BRIDGE_LIB}' — "
            "run scripts/build-sf-bridge.sh or let Gradle's buildSfBridge task run cargo")
    else()
        message(STATUS
            "SF_BRIDGE_LIB not provided; this host build has no rescue engine")
    endif()
endif()

if(USB_WITH_FATFS)
    list(APPEND USB_NATIVE_SOURCES
        fat_engine.c
        fat_jni.cpp
        ${FATFS_DIR}/ff.c
        ${FATFS_DIR}/ffunicode.c
        ${FATFS_DIR}/ffsystem.c
        ${FATFS_DIR}/diskio.c
    )
    list(APPEND USB_NATIVE_INCLUDES ${FATFS_DIR})
endif()

if(USB_WITH_NTFS)
    # mkntfs is the real upstream formatter; MKNTFS_LIBRARY removes its CLI
    # main and MKNTFS_NO_UUID_LIB uses libntfs-3g's GUID generator instead of
    # libuuid, which the NDK does not ship.
    list(APPEND USB_NATIVE_SOURCES
        ntfs_engine.c
        ntfs_jni.cpp
        ntfs_mkfs_support.c
        ${NTFS_PROGS_DIR}/mkntfs.c
        ${NTFS_PROGS_DIR}/attrdef.c
        ${NTFS_PROGS_DIR}/boot.c
        ${NTFS_PROGS_DIR}/sd.c
        ${NTFS_SOURCES}
    )
    list(APPEND USB_NATIVE_INCLUDES
        ${NTFS_PROGS_DIR}
        ${NTFS_DIR}/libntfs-3g
        ${NTFS_DIR}/include
        ${NTFS_DIR}/include/ntfs-3g
    )
    list(APPEND USB_NATIVE_DEFS
        HAVE_CONFIG_H=1
        MKNTFS_LIBRARY=1
        MKNTFS_NO_UUID_LIB=1
    )
endif()

if(USB_WITH_LWEXT4)
    list(APPEND USB_NATIVE_SOURCES
        ext_engine.c
        ext_jni.cpp
        ${LWEXT4_SOURCES}
    )
    list(APPEND USB_NATIVE_INCLUDES ${LWEXT4_DIR}/include)
    list(APPEND USB_NATIVE_DEFS
        CONFIG_USE_DEFAULT_CFG=1
        CONFIG_DEBUG_ASSERT=0
        CONFIG_EXT4_MOUNTPOINTS_COUNT=8
    )
endif()
# Keep exfat's config.h after NTFS include paths; it lacks NTFS feature macros.
list(APPEND USB_NATIVE_INCLUDES ${EXFAT_ROOT}/include ${EXFAT_ROOT}/fsck)

add_library(usb_native SHARED ${USB_NATIVE_SOURCES})

target_include_directories(usb_native PRIVATE ${USB_NATIVE_INCLUDES})

target_compile_definitions(usb_native PRIVATE ${USB_NATIVE_DEFS})

find_package(Threads REQUIRED)

if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(usb_native
        exfatprogs_fsck
        ${log-lib}
        Threads::Threads
        dl
        m
        ${USB_NATIVE_LIBS}
    )
    target_link_options(usb_native PRIVATE "-Wl,-z,max-page-size=16384")
else()
    target_link_libraries(usb_native
        exfatprogs_fsck
        Threads::Threads
        ${USB_NATIVE_LIBS}
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

    add_executable(ext_name_utf8_host_test
        host/ext_name_utf8_host_test.c
    )
    target_include_directories(ext_name_utf8_host_test PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
    )
endif()
