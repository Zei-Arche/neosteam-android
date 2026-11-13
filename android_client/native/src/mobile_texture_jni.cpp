#include "neosteam/mobile_textures.hpp"

#include <android/asset_manager_jni.h>
#include <jni.h>

#include <array>
#include <cstdint>
#include <exception>
#include <limits>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_neosteam_revival_performance_ScenePreview_loadAtlas(JNIEnv* env, jclass, jobject manager,
                                                            jboolean supports_astc) {
    if (manager == nullptr) return nullptr;
    try {
        const auto vfs = neosteam::asset::AssetVfs::from_android_assets(
            AAssetManager_fromJava(env, manager), 20U * 1024U * 1024U);
        const auto atlas = neosteam::asset::load_preview_atlas(vfs, supports_astc == JNI_TRUE);
        const auto& texture = atlas.texture;
        const auto& tile = atlas.first_tile;
        const std::array<std::uint32_t, 7> fields{
            texture.width(), texture.height(), texture.gl_internal_format(),
            tile[0], tile[1], tile[2], tile[3],
        };
        std::array<jbyte, fields.size() * 4> header{};
        for (std::size_t index = 0; index < fields.size(); ++index) {
            for (unsigned shift = 0; shift < 4; ++shift) {
                header[index * 4 + shift] = static_cast<jbyte>(fields[index] >> (shift * 8));
            }
        }
        const auto pixels = texture.level_bytes(0);
        if (pixels.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max()) - header.size()) {
            return nullptr;
        }
        const auto result = env->NewByteArray(static_cast<jsize>(header.size() + pixels.size()));
        if (result == nullptr) return nullptr;
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(header.size()), header.data());
        if (!env->ExceptionCheck()) {
            env->SetByteArrayRegion(result, static_cast<jsize>(header.size()),
                static_cast<jsize>(pixels.size()), reinterpret_cast<const jbyte*>(pixels.data()));
        }
        return result;
    } catch (const std::exception&) {
        return nullptr;  // Optional atlas. The renderer supplies its untextured fallback.
    }
}
