#include "neosteam/binary_codec.hpp"

#include "client_structs.hpp"

#include <bit>
#include <cstring>

namespace neosteam::binary {
namespace {

template <typename T>
bool read_unsigned(Reader& input, T& output) noexcept {
    std::array<std::byte, sizeof(T)> bytes;
    if (!input.bytes(bytes)) {
        return false;
    }
    std::uint32_t value = 0;
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        value |= std::to_integer<std::uint32_t>(bytes[index]) << (8 * index);
    }
    output = static_cast<T>(value);
    return true;
}

template <typename T>
bool write_unsigned(Writer& output, T input) noexcept {
    std::array<std::byte, sizeof(T)> bytes;
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        bytes[index] = static_cast<std::byte>((input >> (8 * index)) & 0xff);
    }
    return output.bytes(bytes);
}

}  // namespace

bool Reader::bytes(std::span<std::byte> output) noexcept {
    if (output.size() > remaining()) {
        return false;
    }
    if (!output.empty()) {
        std::memmove(output.data(), data_.data(), output.size());
    }
    data_ = data_.subspan(output.size());
    return true;
}

bool Reader::u8(std::uint8_t& output) noexcept { return read_unsigned(*this, output); }
bool Reader::u16(std::uint16_t& output) noexcept { return read_unsigned(*this, output); }
bool Reader::u32(std::uint32_t& output) noexcept { return read_unsigned(*this, output); }

bool Reader::i16(std::int16_t& output) noexcept {
    std::uint16_t bits;
    if (!u16(bits)) {
        return false;
    }
    output = std::bit_cast<std::int16_t>(bits);
    return true;
}

bool Reader::f32(float& output) noexcept {
    std::uint32_t bits;
    if (!u32(bits)) {
        return false;
    }
    output = std::bit_cast<float>(bits);
    return true;
}

bool Writer::bytes(std::span<const std::byte> input) noexcept {
    if (input.size() > remaining()) {
        return false;
    }
    if (!input.empty()) {
        std::memmove(data_.data(), input.data(), input.size());
    }
    data_ = data_.subspan(input.size());
    return true;
}

bool Writer::u8(std::uint8_t input) noexcept { return write_unsigned(*this, input); }
bool Writer::u16(std::uint16_t input) noexcept { return write_unsigned(*this, input); }
bool Writer::u32(std::uint32_t input) noexcept { return write_unsigned(*this, input); }

bool Writer::i16(std::int16_t input) noexcept { return u16(std::bit_cast<std::uint16_t>(input)); }
bool Writer::f32(float input) noexcept { return u32(std::bit_cast<std::uint32_t>(input)); }

}  // namespace neosteam::binary

namespace neosteam::asset {
namespace {

template <typename T, std::size_t N>
bool byte_array(binary::Reader& input, std::array<T, N>& value) noexcept {
    return input.bytes(std::as_writable_bytes(std::span(value)));
}

template <typename T, std::size_t N>
bool byte_array(binary::Writer& output, const std::array<T, N>& value) noexcept {
    return output.bytes(std::as_bytes(std::span(value)));
}

// A single field order serves both directions. Each public operation checks the
// entire disk size before transferring the first field.
template <typename Cursor, typename Record>
bool terrain_prefix(Cursor& cursor, Record& value) noexcept {
    return cursor.remaining() >= sizeof(TerrainTablePrefixDisk) &&
           byte_array(cursor, value.magic) &&
           cursor.u32(value.tile_width) && cursor.u32(value.tile_height);
}

template <typename Cursor, typename Record>
bool terrain_tile(Cursor& cursor, Record& value) noexcept {
    return cursor.remaining() >= sizeof(TerrainTileDisk) &&
           cursor.u16(value.base_texture_index) && cursor.u8(value.base_modifier) &&
           cursor.u16(value.decal_texture_index) && cursor.u8(value.decal_modifier) &&
           cursor.u8(value.object_count_low) && cursor.u8(value.object_count_high_or_reserved);
}

template <typename Cursor, typename Record>
bool terrain_placement(Cursor& cursor, Record& value) noexcept {
    return cursor.remaining() >= sizeof(TerrainPlacementDisk) &&
           cursor.u8(value.tile_x) && cursor.u8(value.tile_y) && cursor.u8(value.tile_ordinal) &&
           cursor.u16(value.object_catalog_id) && byte_array(cursor, value.unknown_05) &&
           cursor.f32(value.position[0]) && cursor.f32(value.position[1]) && cursor.f32(value.position[2]) &&
           cursor.f32(value.angle_candidate) &&
           cursor.f32(value.axis[0]) && cursor.f32(value.axis[1]) && cursor.f32(value.axis[2]) &&
           cursor.f32(value.scale_candidate);
}

template <typename Cursor, typename Record>
bool terrain_normal(Cursor& cursor, Record& value) noexcept {
    return cursor.remaining() >= sizeof(TerrainNormalDisk) &&
           cursor.f32(value.x) && cursor.f32(value.y) && cursor.f32(value.z);
}

template <typename Cursor, typename Record>
bool sequence_prefix(Cursor& cursor, Record& value) noexcept {
    return cursor.remaining() >= sizeof(SequenceTrackSetPrefixDisk) &&
           byte_array(cursor, value.magic) && byte_array(cursor, value.marker);
}

}  // namespace

bool decode(binary::Reader& input, TerrainTablePrefix& value) noexcept { return terrain_prefix(input, value); }
bool decode(binary::Reader& input, TerrainTile& value) noexcept { return terrain_tile(input, value); }
bool decode(binary::Reader& input, TerrainPlacement& value) noexcept { return terrain_placement(input, value); }
bool decode(binary::Reader& input, TerrainNormal& value) noexcept { return terrain_normal(input, value); }
bool decode(binary::Reader& input, SequenceTrackSetPrefix& value) noexcept { return sequence_prefix(input, value); }

bool encode(binary::Writer& output, const TerrainTablePrefix& value) noexcept { return terrain_prefix(output, value); }
bool encode(binary::Writer& output, const TerrainTile& value) noexcept { return terrain_tile(output, value); }
bool encode(binary::Writer& output, const TerrainPlacement& value) noexcept { return terrain_placement(output, value); }
bool encode(binary::Writer& output, const TerrainNormal& value) noexcept { return terrain_normal(output, value); }
bool encode(binary::Writer& output, const SequenceTrackSetPrefix& value) noexcept { return sequence_prefix(output, value); }

}  // namespace neosteam::asset
