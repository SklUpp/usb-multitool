cmake_minimum_required(VERSION 3.22.1)
project(usb_multitool_native)

add_library(usb_native SHARED native-lib.cpp)

find_library(log-lib log)
target_link_libraries(usb_native ${log-lib})
