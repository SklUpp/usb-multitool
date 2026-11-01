cmake_minimum_required(VERSION 3.22.1)
project(usb_multitool_native)

add_library(usb_native SHARED
    native-lib.cpp
    usb_block.c
    ventoy_engine.c
    ventoy_bridge.c
    ../../third_party/ventoy/ventoy_crc32.c
    ../../third_party/ventoy/ventoy_util.c
)

target_include_directories(usb_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/ventoy
)

find_library(log-lib log)
target_link_libraries(usb_native ${log-lib})
