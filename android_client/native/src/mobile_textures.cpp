#include "neosteam/mobile_textures.hpp"

#include <string>
#include <utility>

namespace neosteam::asset {
namespace {

constexpr std::size_t astc_header_bytes = 16;
constexpr std::uint32_t vk_rgba8_srgb = 43;
constexpr std::uint32_t vk_rgba8_unorm = 37;

std::uint32_t read_le(std::span<const std::byte> bytes, std::size_t offset, unsigned count) {
    std::uint32_t value = 0;
    for (unsigned index = 0; index < count; ++index) {
        value |= std::to_integer<std::uint32_t>(bytes[offset + index]) << (8 * index);
    }
    return value;
}

}  // namespace

AstcTexture AstcTexture::load(AssetBytes bytes, bool srgb) {
    if (bytes.size() < astc_header_bytes || read_le(bytes, 0, 4) != 0x5ca1ab13 ||
        read_le(bytes, 4, 3) != 0x010606 || read_le(bytes, 13, 3) != 1) {
        throw AssetError("expected a 2D ASTC 6x6 texture");
    }
    const auto width = read_le(bytes, 7, 3);
    const auto height = read_le(bytes, 10, 3);
    const std::uint64_t expected_blocks = std::uint64_t((width + 5) / 6) * ((height + 5) / 6);
    if (width == 0 || height == 0 || expected_blocks * 16 != bytes.size() - astc_header_bytes) {
        throw AssetError("invalid ASTC extent or block count");
    }
    AstcTexture texture;
    texture.bytes_ = std::move(bytes);
    texture.width_ = width;
    texture.height_ = height;
    texture.srgb_ = srgb;
    return texture;
}

std::span<const std::byte> AstcTexture::blocks() const noexcept {
    if (bytes_.empty()) return {};
    return std::span<const std::byte>(bytes_).subspan(astc_header_bytes);
}

MobileTexture::MobileTexture(Ktx2Asset texture) : data_(std::move(texture)) {}
MobileTexture::MobileTexture(AstcTexture texture) : data_(std::move(texture)) {}

std::uint32_t MobileTexture::width() const noexcept {
    return std::visit([](const auto& texture) { return texture.width(); }, data_);
}

std::uint32_t MobileTexture::height() const noexcept {
    return std::visit([](const auto& texture) { return texture.height(); }, data_);
}

std::uint32_t MobileTexture::gl_internal_format() const noexcept {
    if (const auto* texture = std::get_if<AstcTexture>(&data_)) {
        return texture->srgb() ? 0x93d4 : 0x93b4;
    }
    return vk_format() == vk_rgba8_srgb ? 0x8c43 : 0x8058;
}

std::uint32_t MobileTexture::vk_format() const noexcept {
    if (const auto* texture = std::get_if<AstcTexture>(&data_)) {
        return texture->srgb() ? 166 : 165;
    }
    return std::get<Ktx2Asset>(data_).vk_format();
}

bool MobileTexture::compressed() const noexcept {
    return std::holds_alternative<AstcTexture>(data_);
}

std::size_t MobileTexture::level_count() const noexcept {
    const auto* texture = std::get_if<Ktx2Asset>(&data_);
    return texture ? texture->levels().size() : 1;
}

std::span<const std::byte> MobileTexture::level_bytes(std::size_t index) const {
    if (const auto* texture = std::get_if<Ktx2Asset>(&data_)) {
        return texture->level_bytes(index);
    }
    if (index != 0) throw AssetError("ASTC atlas contains only the base level");
    return std::get<AstcTexture>(data_).blocks();
}

MobileTexture load_mobile_texture(const AssetVfs& vfs, std::string_view rgba_ktx2_path,
                                  bool supports_astc, bool srgb,
                                  std::array<std::uint32_t, 2> expected_extent) {
    if (!rgba_ktx2_path.ends_with(".ktx2") || expected_extent[0] == 0 || expected_extent[1] == 0) {
        throw AssetError("invalid mobile atlas texture key or expected extent");
    }
    if (supports_astc) {
        const auto sidecar = std::string(rgba_ktx2_path) + (srgb ? ".srgb.astc" : ".linear.astc");
        try {
            auto texture = AstcTexture::load(vfs.read_mobile_texture_sidecar(sidecar), srgb);
            if (texture.width() == expected_extent[0] && texture.height() == expected_extent[1]) {
                return MobileTexture(std::move(texture));
            }
        } catch (const AssetError&) {
            // Missing or malformed optional compression must leave the base atlas usable.
        }
    }
    auto texture = vfs.load_ktx2(rgba_ktx2_path);
    if (texture.width() != expected_extent[0] || texture.height() != expected_extent[1] ||
        texture.levels().size() != 1 || texture.vk_format() != (srgb ? vk_rgba8_srgb : vk_rgba8_unorm)) {
        throw AssetError("fallback atlas extent, mip count or color profile differs");
    }
    return MobileTexture(std::move(texture));
}

MobileAtlas load_preview_atlas(const AssetVfs& vfs, bool supports_astc) {
    const auto sidecar = vfs.read_mobile_texture_sidecar(preview_atlas_rect_path);
    if (sidecar.size() != 28) throw AssetError("invalid preview atlas descriptor size");
    const auto width = read_le(sidecar, 0, 4);
    const auto height = read_le(sidecar, 4, 4);
    const auto format = read_le(sidecar, 8, 4);
    const auto x = read_le(sidecar, 12, 4);
    const auto y = read_le(sidecar, 16, 4);
    const auto tile_width = read_le(sidecar, 20, 4);
    const auto tile_height = read_le(sidecar, 24, 4);
    if (width == 0 || height == 0 || width > 2048 || height > 2048 ||
        (format != vk_rgba8_unorm && format != vk_rgba8_srgb) ||
        x >= width || y >= height || tile_width == 0 || tile_height == 0 ||
        tile_width > width - x || tile_height > height - y) {
        throw AssetError("invalid preview atlas extent, color profile or tile");
    }
    return {load_mobile_texture(vfs, preview_atlas_path, supports_astc, format == vk_rgba8_srgb,
                                {width, height}),
            {x, y, tile_width, tile_height}};
}

}  // namespace neosteam::asset
