cmake_minimum_required(VERSION 3.22.1)
project(usb_multitool_native)

add_library(
    usb_native
    SHARED
    native-lib.cpp
    fs_bridge.c
    ventoy_bridge.c
    f3_bridge.c
    ddrescue_bridge.c
)

# Upstream engines are recorded in third_party/*/README.md with exact URLs
# and revisions. CI fetches them before enabling the options below.
option(USB_WITH_FATFS "Link vendored FatFs" OFF)
option(USB_WITH_NTFS "Link vendored ntfs-3g" OFF)
option(USB_WITH_LWEXT4 "Link vendored lwext4" OFF)
option(USB_WITH_VENTOY "Link vendored Ventoy installer" OFF)
option(USB_WITH_F3 "Link vendored F3" OFF)
option(USB_WITH_DDRESCUE "Link vendored GNU ddrescue" OFF)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)

if(USB_WITH_FATFS)
    file(GLOB FATFS_SOURCES ${THIRD_PARTY_DIR}/fatfs/source/*.c)
    target_sources(usb_native PRIVATE ${FATFS_SOURCES})
    target_include_directories(usb_native PRIVATE ${THIRD_PARTY_DIR}/fatfs/source)
    target_compile_definitions(usb_native PRIVATE USB_WITH_FATFS=1)
endif()

if(USB_WITH_NTFS)
    target_include_directories(
        usb_native
        PRIVATE
        ${THIRD_PARTY_DIR}/ntfs-3g/include
        ${THIRD_PARTY_DIR}/ntfs-3g/libntfs-3g
    )
    target_compile_definitions(usb_native PRIVATE USB_WITH_NTFS=1)
endif()

if(USB_WITH_LWEXT4)
    target_include_directories(usb_native PRIVATE ${THIRD_PARTY_DIR}/lwext4/include)
    target_compile_definitions(usb_native PRIVATE USB_WITH_LWEXT4=1)
endif()

if(USB_WITH_VENTOY)
    target_include_directories(usb_native PRIVATE ${THIRD_PARTY_DIR}/ventoy)
    target_compile_definitions(usb_native PRIVATE USB_WITH_VENTOY=1)
endif()

if(USB_WITH_F3)
    target_include_directories(usb_native PRIVATE ${THIRD_PARTY_DIR}/f3)
    target_compile_definitions(usb_native PRIVATE USB_WITH_F3=1)
endif()

if(USB_WITH_DDRESCUE)
    target_include_directories(usb_native PRIVATE ${THIRD_PARTY_DIR}/ddrescue)
    target_compile_definitions(usb_native PRIVATE USB_WITH_DDRESCUE=1)
endif()

find_library(log-lib log)
target_link_libraries(usb_native ${log-lib})

# 16 KB page-size readiness for Android 15+.
target_link_options(usb_native PRIVATE "-Wl,-z,max-page-size=16384")
