cmake_minimum_required(VERSION 3.22.1)
project(terminator_pty)

add_library(terminator-pty SHARED pty.c)

find_library(log-lib log)

target_link_libraries(terminator-pty ${log-lib})
