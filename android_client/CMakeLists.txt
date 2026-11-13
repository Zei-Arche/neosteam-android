cmake_minimum_required(VERSION 3.22.1)
project(NeoSteamAndroid LANGUAGES CXX)

add_library(neosteam_core OBJECT
    native/src/asset_vfs.cpp
    native/src/binary_codec.cpp
    native/src/mobile_textures.cpp
    native/src/render_budget.cpp
    native/src/text_codec.cpp
)
target_compile_features(neosteam_core PUBLIC cxx_std_20)
set_target_properties(neosteam_core PROPERTIES
    CXX_EXTENSIONS OFF
    POSITION_INDEPENDENT_CODE ON
)
target_include_directories(neosteam_core PUBLIC native/include ../include)

if(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Threads REQUIRED)
    target_sources(neosteam_core PRIVATE native/src/tcp_client.cpp)
    target_link_libraries(neosteam_core PUBLIC Threads::Threads)
endif()

if(ANDROID)
    option(NEOSTEAM_DEBUG_DIAGNOSTICS "Build the Android debug crash capture hook" OFF)
    target_link_libraries(neosteam_core PUBLIC android)
    add_library(neosteam SHARED native/src/android_bridge.cpp native/src/chat_bridge.cpp
        native/src/mobile_texture_jni.cpp native/src/render_budget_jni.cpp)
    if(NEOSTEAM_DEBUG_DIAGNOSTICS)
        target_sources(neosteam PRIVATE src/debug/crash_logger.cpp)
    endif()
    target_link_libraries(neosteam PRIVATE neosteam_core log)
endif()

if(NOT ANDROID)
    include(CTest)
    if(BUILD_TESTING)
        add_executable(server_handshake_test ../tests/server_handshake_test.cpp)
        target_compile_features(server_handshake_test PRIVATE cxx_std_20)
        set(NEOSTEAM_GOLDEN_CODEC_DIR "" CACHE PATH "Android Engine Reconstruction checkout for Golden integration")
        if(NEOSTEAM_GOLDEN_CODEC_DIR)
            find_package(Threads REQUIRED)
            set(golden_src "${NEOSTEAM_GOLDEN_CODEC_DIR}/src")
            set(golden_keys "${NEOSTEAM_GOLDEN_CODEC_DIR}/assets/protocol_keys.bin")
            if(NOT EXISTS "${golden_keys}")
                message(FATAL_ERROR "Golden codec protocol_keys.bin is missing")
            endif()
            target_sources(server_handshake_test PRIVATE
                ../tests/server_handshake_live.cpp
                "${golden_src}/core.cpp"
                "${golden_src}/protocol.cpp"
                "${golden_src}/legacy_des.cpp"
                "${golden_src}/transport.cpp"
                "${golden_src}/login.cpp"
                "${golden_src}/scene.cpp"
                "${golden_src}/scenery.cpp"
                "${golden_src}/animation.cpp"
                "${golden_src}/movement.cpp"
                "${golden_src}/inventory.cpp"
                "${golden_src}/entities.cpp"
                "${golden_src}/nearby_models.cpp"
                "${golden_src}/remote_motion.cpp"
                "${golden_src}/resource_admission.cpp"
                "${golden_src}/texture_mips022.cpp"
            )
            target_include_directories(server_handshake_test PRIVATE "${golden_src}")
            target_compile_definitions(server_handshake_test PRIVATE
                NEOSTEAM_GOLDEN_CODEC
                NEOSTEAM_CODEC_KEYS_FILE="${golden_keys}"
            )
            target_link_libraries(server_handshake_test PRIVATE Threads::Threads)
            if(WIN32)
                target_link_libraries(server_handshake_test PRIVATE ws2_32)
            endif()
        endif()
        add_test(NAME server_handshake_fixture COMMAND server_handshake_test --fixture)
        add_test(NAME server_handshake_rejections COMMAND server_handshake_test --self-test)
        foreach(test IN ITEMS codec_test binary_codec_test vfs_test render_budget_test mobile_textures_test)
            add_executable(${test} native/tests/${test}.cpp)
            target_link_libraries(${test} PRIVATE neosteam_core)
            add_test(NAME ${test} COMMAND ${test})
        endforeach()
        if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
            add_executable(transport_test native/tests/transport_test.cpp)
            target_link_libraries(transport_test PRIVATE neosteam_core)
            add_test(NAME transport_test COMMAND transport_test)
        endif()
    endif()
endif()
