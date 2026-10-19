cmake_minimum_required(VERSION 3.22.1)
project(neigh C)
add_library(neigh SHARED neigh.c)
target_link_libraries(neigh log)
