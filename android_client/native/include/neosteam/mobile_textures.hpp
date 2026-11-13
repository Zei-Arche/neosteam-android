#pragma once

#include "neosteam/asset_vfs.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string_view>
#include <variant>

namespace neosteam::asset {

class AstcTexture {
public:
    [[nodiscard]] static AstcTexture load(AssetBytes bytes, bool srgb);
    [[nodiscard]] std::uint32_t width() const noexcept { return width_; }
    [[nodiscard]] std::uint32_t height() const noexcept { return height_; }
    [[nodiscard]] bool srgb() const noexcept { return srgb_; }
    [[nodiscard]] std::span<const std::byte> blocks() const noexcept;

private:
    AssetBytes bytes_;
    std::uint32_t width_{};
    std::uint32_t height_{};
    bool srgb_{};
};

class MobileTexture {
public:
    explicit MobileTexture(Ktx2Asset texture);
    explicit MobileTexture(AstcTexture texture);

    [[nodiscard]] std::uint32_t width() const noexcept;
    [[nodiscard]] std::uint32_t height() const noexcept;
    [[nodiscard]] std::uint32_t gl_internal_format() const noexcept;
    [[nodiscard]] std::uint32_t vk_format() const noexcept;
    [[nodiscard]] bool compressed() const noexcept;
    [[nodiscard]] std::size_t level_count() const noexcept;
    [[nodiscard]] std::span<const std::byte> level_bytes(std::size_t index) const;

private:
    std::variant<Ktx2Asset, AstcTexture> data_;
};

// The caller supplies observed device format support. ASTC sidecars are for
// explicitly single-level atlases and must match the expected extent and profile.
[[nodiscard]] MobileTexture load_mobile_texture(
    const AssetVfs& vfs, std::string_view rgba_ktx2_path, bool supports_astc, bool srgb,
    std::array<std::uint32_t, 2> expected_extent);

struct MobileAtlas {
    MobileTexture texture;
    std::array<std::uint32_t, 4> first_tile;  // top-left x, y, width, height
};

inline constexpr std::string_view preview_atlas_path = "converted/mobile/preview.ktx2";
inline constexpr std::string_view preview_atlas_rect_path = "converted/mobile/preview.rect";

[[nodiscard]] MobileAtlas load_preview_atlas(const AssetVfs& vfs, bool supports_astc);

}  // namespace neosteam::asset
