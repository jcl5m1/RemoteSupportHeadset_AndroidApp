cmake_minimum_required(VERSION 3.22.1)

# Minimal static library for AprilTag 16h5 detection.
# Only the core detector, common utilities, and tag16h5 family are built.

aux_source_directory(${CMAKE_CURRENT_SOURCE_DIR}/common APRILTAG_COMMON_SRC)

set(APRILTAG_SRCS
    ${CMAKE_CURRENT_SOURCE_DIR}/apriltag.c
    ${CMAKE_CURRENT_SOURCE_DIR}/apriltag_pose.c
    ${CMAKE_CURRENT_SOURCE_DIR}/apriltag_quad_thresh.c
    ${CMAKE_CURRENT_SOURCE_DIR}/tag16h5.c
    ${APRILTAG_COMMON_SRC}
)

add_library(apriltag STATIC ${APRILTAG_SRCS})

set_target_properties(apriltag PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    C_STANDARD 99
)

target_include_directories(apriltag PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)
find_library(m-lib m)

target_link_libraries(apriltag
    ${log-lib}
    ${m-lib}
)
