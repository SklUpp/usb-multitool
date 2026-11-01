cmake_minimum_required(VERSION 3.22.1)
project(usb_multitool_native)

set(FATFS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/fatfs/source)

add_library(usb_native SHARED
    native-lib.cpp
    usb_block.c
    fat_engine.c
    fat_jni.cpp
    ${FATFS_DIR}/ff.c
    ${FATFS_DIR}/ffunicode.c
    ${FATFS_DIR}/ffsystem.c
    ${FATFS_DIR}/diskio.c
)

target_include_directories(usb_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${FATFS_DIR}
)


find_library(log-lib log)
target_link_libraries(usb_native ${log-lib})
