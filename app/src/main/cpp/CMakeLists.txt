cmake_minimum_required(VERSION 3.22.1)
project("esp32flasher")

# AprilTag3 detector (16h5 only)
add_subdirectory(apriltag)

set(ESF_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/esp-serial-flasher)

# esp-serial-flasher core sources and stubs
file(GLOB ESF_STUB_SOURCES ${ESF_ROOT}/src/stubs/*.c)

set(ESF_SOURCES
    ${ESF_ROOT}/src/esp_loader.c
    ${ESF_ROOT}/src/esp_targets.c
    ${ESF_ROOT}/src/md5_hash.c
    ${ESF_ROOT}/src/protocol_serial.c
    ${ESF_ROOT}/src/protocol_spi.c
    ${ESF_ROOT}/src/protocol_sdio.c
    ${ESF_ROOT}/src/protocol_uart.c
    ${ESF_ROOT}/src/slip.c
    ${ESF_STUB_SOURCES}
)

add_library(esp32flasher SHARED
    ${ESF_SOURCES}
    android_usb_cdc_port.c
    esp32_flasher_jni.cpp
)

target_include_directories(esp32flasher PRIVATE
    ${ESF_ROOT}/include
    ${ESF_ROOT}/private_include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(esp32flasher ${log-lib})

# AprilTag JNI shared library
add_library(apriltag_jni SHARED
    apriltag_jni.cpp
)

target_include_directories(apriltag_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(apriltag_jni
    apriltag
    ${log-lib}
    ${jnigraphics-lib}
)
