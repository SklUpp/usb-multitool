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

# Upstream engines are fetched by scripts/fetch-third-party.sh in CI.
# Local builds keep USB_WITH_* OFF and never invent success.
option(USB_WITH_FATFS "Link vendored FatFs" OFF)
option(USB_WITH_NTFS "Link vendored ntfs-3g" OFF)
option(USB_WITH_LWEXT4 "Link vendored lwext4" OFF)
option(USB_WITH_VENTOY "Link vendored Ventoy installer" OFF)
option(USB_WITH_F3 "Link vendored F3" OFF)
option(USB_WITH_DDRESCUE "Link vendored GNU ddrescue" OFF)

# When ON, CMake fails if a required engine tree is absent (CI contract).
option(USB_REQUIRE_ENGINES "Fail configure when USB_WITH_* source trees missing" OFF)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)

function(usb_require_engine FLAG_NAME DIR_REL MARKER)
    if(${FLAG_NAME})
        set(_path "${THIRD_PARTY_DIR}/${DIR_REL}/${MARKER}")
        if(NOT EXISTS "${_path}")
            if(USB_REQUIRE_ENGINES)
                message(FATAL_ERROR
                    "${FLAG_NAME}=ON but ${_path} missing. "
                    "Run scripts/fetch-third-party.sh before enabling engines.")
            else()
                message(WARNING
                    "${FLAG_NAME}=ON but ${_path} missing; disabling ${FLAG_NAME}.")
                set(${FLAG_NAME} OFF PARENT_SCOPE)
            endif()
        endif()
    endif()
endfunction()

usb_require_engine(USB_WITH_FATFS fatfs source/ff.c)
usb_require_engine(USB_WITH_NTFS ntfs-3g libntfs-3g/ntfs.c)
usb_require_engine(USB_WITH_LWEXT4 lwext4 include/ext4.h)
usb_require_engine(USB_WITH_VENTOY ventoy INSTALL)
usb_require_engine(USB_WITH_F3 f3 f3probe.c)
usb_require_engine(USB_WITH_DDRESCUE ddrescue mapfile.cc)

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
