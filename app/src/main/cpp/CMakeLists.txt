cmake_minimum_required(VERSION 3.22.1)
project(ksu_native LANGUAGES C)

add_library(ksu_native SHARED native_probe.c)
target_compile_options(ksu_native PRIVATE -O2 -Wall -Wextra -Werror)
target_link_libraries(ksu_native PRIVATE log)

