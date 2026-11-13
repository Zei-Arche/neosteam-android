#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#if defined(__ANDROID__)
struct AAssetManager;
#endif

namespace neosteam::asset {

using AssetBytes = std::vector<std::byte>;

class AssetError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

class GlbAsset {
public:
    // Container chunks. The glTF scene reader owns JSON and accessor validation.
    [[nodiscard]] std::string_view json() const noexcept;
    [[nodiscard]] std::span<const std::byte> binary() const noexcept;

private:
    friend class AssetVfs;
    AssetBytes bytes_;
    std::size_t json_size_{};
    std::size_t binary_offset_{};
    std::size_t binary_size_{};
};

struct Ktx2Level {
    std::uint32_t width;
    std::uint32_t height;
    std::size_t offset;
    std::size_t size;
};

class Ktx2Asset {
public:
    [[nodiscard]] std::uint32_t width() const noexcept { return width_; }
    [[nodiscard]] std::uint32_t height() const noexcept { return height_; }
    [[nodiscard]] std::uint32_t vk_format() const noexcept { return vk_format_; }
    [[nodiscard]] std::span<const Ktx2Level> levels() const noexcept { return levels_; }
    [[nodiscard]] std::span<const std::byte> level_bytes(std::size_t level) const;

private:
    friend class AssetVfs;
    AssetBytes bytes_;
    std::vector<Ktx2Level> levels_;
    std::uint32_t width_{};
    std::uint32_t height_{};
    std::uint32_t vk_format_{};
};

class AssetVfs {
public:
    static constexpr std::size_t default_max_asset_bytes = 256U * 1024U * 1024U;
    using Reader = std::function<AssetBytes(std::string_view path, std::size_t max_bytes)>;

    explicit AssetVfs(Reader reader, std::size_t max_bytes = default_max_asset_bytes);
    [[nodiscard]] static AssetVfs from_directory(
        const std::filesystem::path& root, std::size_t max_bytes = default_max_asset_bytes);
#if defined(__ANDROID__)
    // Keep the Java AssetManager alive for the lifetime of this VFS.
    [[nodiscard]] static AssetVfs from_android_assets(
        AAssetManager* manager, std::size_t max_bytes = default_max_asset_bytes);
#endif

    // Paths follow the conversion manifest and start with converted/.
    [[nodiscard]] GlbAsset load_glb(std::string_view path) const;
    [[nodiscard]] Ktx2Asset load_ktx2(std::string_view path) const;
    [[nodiscard]] AssetBytes read_mobile_texture_sidecar(std::string_view path) const;
    [[nodiscard]] std::vector<std::string> indexed_assets() const;

private:
    [[nodiscard]] AssetBytes read(std::string_view path) const;

    Reader reader_;
    std::size_t max_bytes_;
};

}  // namespace neosteam::asset
