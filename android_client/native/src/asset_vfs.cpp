#include "neosteam/asset_vfs.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <fstream>
#include <limits>
#include <memory>
#include <utility>

#if defined(__ANDROID__)
#include <android/asset_manager.h>
#endif

namespace neosteam::asset {
namespace {

using View = std::span<const std::byte>;
namespace fs = std::filesystem;

constexpr std::size_t glb_header_size = 12;
constexpr std::size_t glb_chunk_header_size = 8;
constexpr std::uint32_t glb_magic = 0x46546c67;
constexpr std::uint32_t glb_json_type = 0x4e4f534a;
constexpr std::uint32_t glb_bin_type = 0x004e4942;
constexpr std::size_t ktx_header_size = 80;
constexpr std::size_t ktx_level_index_size = 24;
constexpr std::size_t ktx_dfd_header_size = 28;
constexpr std::uint32_t vk_rgba8_unorm = 37;
constexpr std::uint32_t vk_rgba8_srgb = 43;
constexpr std::array<std::uint8_t, 12> ktx_identifier{
    0xab, 0x4b, 0x54, 0x58, 0x20, 0x32, 0x30, 0xbb, 0x0d, 0x0a, 0x1a, 0x0a};

void require(bool valid, const char* message) {
    if (!valid) {
        throw AssetError(message);
    }
}

template <typename T>
T little_endian(View bytes, std::size_t offset) {
    T result = 0;
    for (std::size_t i = 0; i < sizeof(T); ++i) {
        result |= static_cast<T>(std::to_integer<std::uint8_t>(bytes[offset + i])) << (8 * i);
    }
    return result;
}

std::uint32_t u32(View bytes, std::size_t offset) {
    return little_endian<std::uint32_t>(bytes, offset);
}

std::uint64_t u64(View bytes, std::size_t offset) {
    return little_endian<std::uint64_t>(bytes, offset);
}

bool in_bounds(View bytes, std::uint64_t offset, std::uint64_t length) {
    return offset <= bytes.size() && length <= bytes.size() - offset;
}

std::string_view text(View bytes) {
    if (bytes.empty()) {
        return {};
    }
    return {reinterpret_cast<const char*>(bytes.data()), bytes.size()};
}

void require_asset_path(std::string_view path, std::string_view suffix) {
    constexpr std::string_view prefix = "converted/";
    require(path.starts_with(prefix) && path.ends_with(suffix), "invalid converted asset path");
    for (const unsigned char character : path) {
        require(character >= 32 && character != 127 &&
                    std::string_view{"\\:\"<>|?*"}.find(static_cast<char>(character)) ==
                        std::string_view::npos,
                "invalid character in converted asset path");
    }

    path.remove_prefix(prefix.size());
    for (;;) {
        const auto separator = path.find('/');
        const auto part = path.substr(0, separator);
        require(!part.empty() && !part.ends_with('.') && !part.ends_with(' '),
                "invalid converted asset path component");
        if (separator == std::string_view::npos) {
            break;
        }
        path.remove_prefix(separator + 1);
    }
}

void validate_orientation(View kvd) {
    bool found = false;
    for (std::size_t offset = 0; offset < kvd.size();) {
        require(in_bounds(kvd, offset, 4), "truncated KTX2 key/value record");
        const auto size = u32(kvd, offset);
        offset += 4;
        const auto padding = (4U - size % 4U) % 4U;
        require(in_bounds(kvd, offset, static_cast<std::uint64_t>(size) + padding),
                "invalid KTX2 key/value size");
        const auto record = text(kvd.subspan(offset, size));
        const auto split = record.find('\0');
        require(split != std::string_view::npos && split != 0, "invalid KTX2 key/value key");
        if (record.substr(0, split) == "KTXorientation") {
            require(!found && record.substr(split + 1) == std::string_view("rd\0", 3),
                    "expected KTX2 top-left orientation");
            found = true;
        }
        offset += static_cast<std::size_t>(size) + padding;
    }
    require(found, "missing KTX2 orientation");
}

std::uint64_t validate_ktx_metadata(View bytes, std::uint64_t index_end, bool srgb) {
    const auto dfd_offset = u32(bytes, 48);
    const auto dfd_size = u32(bytes, 52);
    const auto kvd_offset = u32(bytes, 56);
    const auto kvd_size = u32(bytes, 60);
    require(dfd_offset % 4 == 0 && dfd_offset >= index_end && dfd_size >= ktx_dfd_header_size &&
                in_bounds(bytes, dfd_offset, dfd_size), "invalid KTX2 data format descriptor range");
    const auto dfd = bytes.subspan(dfd_offset, dfd_size);
    const auto block_size = little_endian<std::uint16_t>(dfd, 10);
    require(u32(dfd, 0) == dfd_size && u32(dfd, 4) == 0 && block_size >= 24 &&
                block_size <= dfd_size - 4 && dfd[12] == std::byte{1} &&
                dfd[14] == (srgb ? std::byte{2} : std::byte{1}) && dfd[15] == std::byte{0} &&
                u32(dfd, 16) == 0 && u64(dfd, 20) == 4,
            "invalid KTX2 RGBA8 data format descriptor");
    const auto dfd_end = static_cast<std::uint64_t>(dfd_offset) + dfd_size;
    require(kvd_offset % 4 == 0 && kvd_offset >= dfd_end && kvd_size != 0 &&
                in_bounds(bytes, kvd_offset, kvd_size), "invalid KTX2 key/value range");
    validate_orientation(bytes.subspan(kvd_offset, kvd_size));
    return static_cast<std::uint64_t>(kvd_offset) + kvd_size;
}

AssetBytes read_file(const fs::path& root, std::string_view path, std::size_t max_bytes) {
    std::error_code error;
    const auto file = fs::canonical(root / fs::path(std::u8string(path.begin(), path.end())), error);
    if (error) {
        throw AssetError("asset file could not be resolved");
    }
    const auto relative = file.lexically_relative(root);
    require(!relative.empty() && !relative.is_absolute() && *relative.begin() != ".." &&
                fs::is_regular_file(file, error) && !error, "asset must be a regular file inside the VFS root");
    std::ifstream stream(file, std::ios::binary | std::ios::ate);
    require(stream.is_open(), "asset file could not be opened");
    const auto end = stream.tellg();
    require(end >= 0 && static_cast<std::uintmax_t>(end) <= max_bytes &&
                static_cast<std::uintmax_t>(end) <=
                    static_cast<std::uintmax_t>(std::numeric_limits<std::streamsize>::max()),
            "asset file exceeds read limit");
    AssetBytes bytes(static_cast<std::size_t>(end));
    stream.seekg(0);
    require(static_cast<bool>(stream), "asset file could not be rewound");
    if (!bytes.empty()) {
        stream.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
        require(static_cast<bool>(stream), "asset file was truncated during read");
    }
    require(stream.peek() == std::char_traits<char>::eof(), "asset file grew during read");
    return bytes;
}

#if defined(__ANDROID__)
AssetBytes read_android(AAssetManager* manager, std::string_view path, std::size_t max_bytes) {
    const std::string name(path);
    using Handle = std::unique_ptr<AAsset, decltype(&AAsset_close)>;
    Handle asset(AAssetManager_open(manager, name.c_str(), AASSET_MODE_STREAMING), AAsset_close);
    require(static_cast<bool>(asset), "APK asset could not be opened");
    const auto length = AAsset_getLength64(asset.get());
    require(length >= 0 && static_cast<std::uintmax_t>(length) <= max_bytes,
            "APK asset exceeds read limit");
    AssetBytes bytes(static_cast<std::size_t>(length));
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const auto count = std::min<std::size_t>(bytes.size() - offset, 64 * 1024);
        const auto read = AAsset_read(asset.get(), bytes.data() + offset, count);
        require(read > 0, "APK asset read failed or was truncated");
        offset += static_cast<std::size_t>(read);
    }
    return bytes;
}
#endif

}  // namespace

std::string_view GlbAsset::json() const noexcept {
    if (bytes_.empty()) {
        return {};
    }
    return text(View(bytes_).subspan(glb_header_size + glb_chunk_header_size, json_size_));
}

std::span<const std::byte> GlbAsset::binary() const noexcept {
    if (bytes_.empty()) {
        return {};
    }
    return View(bytes_).subspan(binary_offset_, binary_size_);
}

std::span<const std::byte> Ktx2Asset::level_bytes(std::size_t level) const {
    if (level >= levels_.size() || bytes_.empty()) {
        throw AssetError("KTX2 mip level is outside the asset");
    }
    const auto& entry = levels_[level];
    return View(bytes_).subspan(entry.offset, entry.size);
}

AssetVfs::AssetVfs(Reader reader, std::size_t max_bytes)
    : reader_(std::move(reader)), max_bytes_(max_bytes) {
    require(static_cast<bool>(reader_) && max_bytes_ > 0, "invalid asset reader or read limit");
}

AssetVfs AssetVfs::from_directory(const fs::path& root, std::size_t max_bytes) {
    std::error_code error;
    const auto canonical = fs::canonical(root, error);
    require(!error && fs::is_directory(canonical, error) && !error, "VFS root is not a directory");
    return AssetVfs(
        [canonical](std::string_view path, std::size_t limit) { return read_file(canonical, path, limit); },
        max_bytes);
}

#if defined(__ANDROID__)
AssetVfs AssetVfs::from_android_assets(AAssetManager* manager, std::size_t max_bytes) {
    require(manager != nullptr, "Android asset manager is required");
    return AssetVfs(
        [manager](std::string_view path, std::size_t limit) { return read_android(manager, path, limit); },
        max_bytes);
}
#endif

AssetBytes AssetVfs::read(std::string_view path) const {
    try {
        auto bytes = reader_(path, max_bytes_);
        require(bytes.size() <= max_bytes_, "asset exceeds read limit");
        return bytes;
    } catch (const AssetError& error) {
        throw AssetError(std::string(path) + ": " + error.what());
    }
}

std::vector<std::string> AssetVfs::indexed_assets() const {
    const auto bytes = read("asset-index.txt");
    auto lines = text(bytes);
    std::vector<std::string> assets;
    while (!lines.empty()) {
        const auto end = lines.find('\n');
        const auto line = lines.substr(0, end);
        const auto suffix = line.ends_with(".glb") ? ".glb" : line.ends_with(".ogg") ? ".ogg" : ".ktx2";
        require_asset_path(line, suffix);
        require(assets.empty() || assets.back() < line, "asset index paths must be sorted and unique");
        assets.emplace_back(line);
        if (end == std::string_view::npos) {
            break;
        }
        lines.remove_prefix(end + 1);
    }
    return assets;
}

AssetBytes AssetVfs::read_mobile_texture_sidecar(std::string_view path) const {
    require_asset_path(path, path.ends_with(".astc") ? ".astc" : ".rect");
    return read(path);
}

GlbAsset AssetVfs::load_glb(std::string_view path) const {
    require_asset_path(path, ".glb");
    GlbAsset asset;
    asset.bytes_ = read(path);
    const View bytes(asset.bytes_);
    require(bytes.size() >= glb_header_size + glb_chunk_header_size &&
                u32(bytes, 0) == glb_magic && u32(bytes, 4) == 2 && u32(bytes, 8) == bytes.size(),
            "invalid GLB v2 header or file length");
    for (std::size_t offset = glb_header_size; offset < bytes.size();) {
        require(in_bounds(bytes, offset, glb_chunk_header_size), "truncated GLB chunk header");
        const auto size = u32(bytes, offset);
        const auto type = u32(bytes, offset + 4);
        const auto data = offset + glb_chunk_header_size;
        require(size % 4 == 0 && in_bounds(bytes, data, size), "invalid GLB chunk bounds or alignment");
        if (offset == glb_header_size) {
            require(type == glb_json_type && size > 0, "GLB must begin with a JSON chunk");
            const auto json = text(bytes.subspan(data, size));
            constexpr std::string_view whitespace = " \t\r\n";
            const auto first = json.find_first_not_of(whitespace);
            const auto last = json.find_last_not_of(whitespace);
            require(first != std::string_view::npos && json[first] == '{' && json[last] == '}',
                    "expected a GLB JSON object");
            asset.json_size_ = size;
        } else if (type == glb_bin_type) {
            require(offset == glb_header_size + glb_chunk_header_size + asset.json_size_,
                    "GLB BIN chunk is out of order");
            asset.binary_offset_ = data;
            asset.binary_size_ = size;
        } else {
            require(type != glb_json_type, "duplicate GLB JSON chunk");
        }
        offset = data + size;
    }
    return asset;
}

Ktx2Asset AssetVfs::load_ktx2(std::string_view path) const {
    require_asset_path(path, ".ktx2");
    Ktx2Asset asset;
    asset.bytes_ = read(path);
    const View bytes(asset.bytes_);
    require(bytes.size() >= ktx_header_size &&
                std::equal(ktx_identifier.begin(), ktx_identifier.end(), bytes.begin(),
                           [](auto expected, auto value) { return value == std::byte{expected}; }),
            "invalid KTX2 header");
    asset.vk_format_ = u32(bytes, 12);
    asset.width_ = u32(bytes, 20);
    asset.height_ = u32(bytes, 24);
    const bool srgb = asset.vk_format_ == vk_rgba8_srgb;
    require((srgb || asset.vk_format_ == vk_rgba8_unorm) && u32(bytes, 16) == 1 &&
                asset.width_ != 0 && asset.height_ != 0 &&
                u32(bytes, 28) == 0 && u32(bytes, 32) == 0 && u32(bytes, 36) == 1 &&
                u32(bytes, 44) == 0 && u64(bytes, 64) == 0 && u64(bytes, 72) == 0,
            "expected an uncompressed 2D RGBA8 KTX2 texture");
    const auto level_count = u32(bytes, 40);
    const auto mip_max = static_cast<std::uint32_t>(std::bit_width(std::max(asset.width_, asset.height_)));
    require(level_count > 0 && level_count <= mip_max, "invalid KTX2 mip count");
    const auto index_end = ktx_header_size + level_count * ktx_level_index_size;
    const auto metadata_end = validate_ktx_metadata(bytes, index_end, srgb);
    std::uint64_t previous_offset = bytes.size();
    for (std::uint32_t level = 0; level < level_count; ++level) {
        const auto index = ktx_header_size + level * ktx_level_index_size;
        const auto offset = u64(bytes, index);
        const auto size = u64(bytes, index + 8);
        const auto width = std::max(1U, asset.width_ >> level);
        const auto height = std::max(1U, asset.height_ >> level);
        const auto pixels = static_cast<std::uint64_t>(width) * height;
        require(pixels <= bytes.size() / 4 && size == pixels * 4 && size == u64(bytes, index + 16) &&
                    offset % 4 == 0 && offset >= metadata_end && offset <= previous_offset &&
                    size <= previous_offset - offset, "invalid KTX2 mip range or byte count");
        asset.levels_.push_back({width, height, static_cast<std::size_t>(offset), static_cast<std::size_t>(size)});
        previous_offset = offset;
    }
    return asset;
}

}  // namespace neosteam::asset
