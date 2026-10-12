cmake_minimum_required(VERSION 3.22.1)

project(termux C)

add_library(termux SHARED termux.c)

# Mirrors the flags upstream Termux used with ndk-build
# (-Wl,--gc-sections is a *link* flag, so it is not mixed into compile options).
target_compile_options(termux PRIVATE
        -std=c11
        -Wall
        -Wextra
        -Werror
        -Os
        -fno-stack-protector
        -D_GNU_SOURCE)

target_link_options(termux PRIVATE -Wl,--gc-sections)
