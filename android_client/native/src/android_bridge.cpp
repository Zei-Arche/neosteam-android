#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdint>
#include <exception>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "neosteam/asset_vfs.hpp"
#include "neosteam/text_codec.hpp"

namespace {

std::array<std::uint8_t, 28> read_prefix(AAssetManager* manager, const std::string& path,
                                         std::size_t count) {
    const std::unique_ptr<AAsset, decltype(&AAsset_close)> stream(
        AAssetManager_open(manager, path.c_str(), AASSET_MODE_STREAMING), &AAsset_close);
    std::array<std::uint8_t, 28> prefix{};
    if (!stream || count > prefix.size()) throw std::runtime_error("Missing asset header");
    std::size_t offset = 0;
    while (offset < count) {
        const int read = AAsset_read(stream.get(), prefix.data() + offset, count - offset);
        if (read <= 0) throw std::runtime_error("Truncated asset header");
        offset += static_cast<std::size_t>(read);
    }
    return prefix;
}

std::array<std::uint8_t, 28> read_prefix(int fd, std::size_t count) {
    std::array<std::uint8_t, 28> prefix{};
    if (count > prefix.size()) throw std::runtime_error("Invalid asset header size");
    std::size_t offset = 0;
    while (offset < count) {
        const auto read = pread(fd, prefix.data() + offset, count - offset, static_cast<off_t>(offset));
        if (read < 0 && errno == EINTR) continue;
        if (read <= 0) throw std::runtime_error("Truncated downloaded asset header");
        offset += static_cast<std::size_t>(read);
    }
    return prefix;
}

std::uint32_t u32(const std::array<std::uint8_t, 28>& prefix, std::size_t offset) {
    std::uint32_t result = 0;
    for (std::size_t byte = 0; byte < 4; ++byte) {
        result |= static_cast<std::uint32_t>(prefix[offset + byte]) << (8 * byte);
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_neosteam_revival_MainActivity_inspectNativeCore(
    JNIEnv* env, jclass, jobject asset_manager, jint glb_fd, jint ktx_fd, jint downloaded_count) {
    try {
        if (neosteam::text::decode_cp949("\xb0\xa1") != "\xea\xb0\x80") {
            return env->NewStringUTF("NeoSteam CP949 check failed");
        }

        auto* manager = AAssetManager_fromJava(env, asset_manager);
        if (manager == nullptr) {
            return env->NewStringUTF("NeoSteam asset manager unavailable");
        }
        const auto vfs = neosteam::asset::AssetVfs::from_android_assets(manager);
        const std::unique_ptr<AAsset, decltype(&AAsset_close)> index(
            AAssetManager_open(manager, "asset-index.txt", AASSET_MODE_STREAMING), &AAsset_close);
        const auto paths = index ? vfs.indexed_assets() : std::vector<std::string>{};
        const auto glb = std::ranges::find_if(paths, [](const auto& path) { return path.ends_with(".glb"); });
        const auto ktx = std::ranges::find_if(paths, [](const auto& path) { return path.ends_with(".ktx2"); });
        std::string status = "NeoSteam native core ready";
        if (downloaded_count >= 0) {
            status += "\nDownloaded assets: " + std::to_string(downloaded_count);
        } else {
            status += index ? "\nIndexed assets: " + std::to_string(paths.size()) : "\nNo converted assets packaged";
        }
        if (glb_fd >= 0 || glb != paths.end()) {
            const auto header = glb_fd >= 0 ? read_prefix(glb_fd, 20) : read_prefix(manager, *glb, 20);
            if (u32(header, 0) != 0x46546c67 || u32(header, 4) != 2 ||
                u32(header, 16) != 0x4e4f534a || u32(header, 8) < 20 ||
                u32(header, 12) > u32(header, 8) - 20) {
                throw std::runtime_error("Invalid GLB startup header");
            }
            status += "\nGLB JSON bytes: " + std::to_string(u32(header, 12));
        }
        if (ktx_fd >= 0 || ktx != paths.end()) {
            const auto header = ktx_fd >= 0 ? read_prefix(ktx_fd, 28) : read_prefix(manager, *ktx, 28);
            constexpr std::array<std::uint8_t, 12> identifier{
                0xab, 0x4b, 0x54, 0x58, 0x20, 0x32, 0x30, 0xbb, 0x0d, 0x0a, 0x1a, 0x0a};
            if (!std::equal(identifier.begin(), identifier.end(), header.begin()) ||
                u32(header, 20) == 0 || u32(header, 24) == 0) {
                throw std::runtime_error("Invalid KTX2 startup header");
            }
            status += "\nKTX2: " + std::to_string(u32(header, 20)) + " x " + std::to_string(u32(header, 24));
        }
        return env->NewStringUTF(status.c_str());
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, "NeoSteam", "%s", error.what());
        return env->NewStringUTF("NeoSteam native asset check failed");
    }
}
