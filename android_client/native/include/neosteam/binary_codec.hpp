#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace neosteam::binary {

// Bounded little-endian payload primitives. A failed operation leaves the cursor
// and destination unchanged. Framing belongs to the caller.
class Reader {
public:
    explicit Reader(std::span<const std::byte> data) noexcept : data_(data) {}

    [[nodiscard]] std::size_t remaining() const noexcept { return data_.size(); }
    bool bytes(std::span<std::byte> output) noexcept;
    bool u8(std::uint8_t& output) noexcept;
    bool u16(std::uint16_t& output) noexcept;
    bool i16(std::int16_t& output) noexcept;
    bool u32(std::uint32_t& output) noexcept;
    bool f32(float& output) noexcept;

private:
    std::span<const std::byte> data_;
};

class Writer {
public:
    explicit Writer(std::span<std::byte> data) noexcept : data_(data) {}

    [[nodiscard]] std::size_t remaining() const noexcept { return data_.size(); }
    bool bytes(std::span<const std::byte> input) noexcept;
    bool u8(std::uint8_t input) noexcept;
    bool u16(std::uint16_t input) noexcept;
    bool i16(std::int16_t input) noexcept;
    bool u32(std::uint32_t input) noexcept;
    bool f32(float input) noexcept;

private:
    std::span<std::byte> data_;
};

}  // namespace neosteam::binary

namespace neosteam::asset {

// Aligned runtime values for the verified Data/ records in client_structs.hpp.
// The codec preserves stored bits. Asset loaders validate magic and semantics.
struct TerrainTablePrefix {
    std::array<char, 32> magic{};
    std::uint32_t tile_width{};
    std::uint32_t tile_height{};
};

struct TerrainTile {
    std::uint16_t base_texture_index{};
    std::uint8_t base_modifier{};
    std::uint16_t decal_texture_index{};
    std::uint8_t decal_modifier{};
    std::uint8_t object_count_low{};
    std::uint8_t object_count_high_or_reserved{};
};

struct TerrainPlacement {
    std::uint8_t tile_x{};
    std::uint8_t tile_y{};
    std::uint8_t tile_ordinal{};
    std::uint16_t object_catalog_id{};
    std::array<std::uint8_t, 2> unknown_05{};
    std::array<float, 3> position{};
    float angle_candidate{};
    std::array<float, 3> axis{};
    float scale_candidate{};
};

struct TerrainNormal {
    float x{};
    float y{};
    float z{};
};

struct SequenceTrackSetPrefix {
    std::array<char, 21> magic{};
    std::array<std::uint8_t, 4> marker{};
};

bool decode(binary::Reader& input, TerrainTablePrefix& value) noexcept;
bool decode(binary::Reader& input, TerrainTile& value) noexcept;
bool decode(binary::Reader& input, TerrainPlacement& value) noexcept;
bool decode(binary::Reader& input, TerrainNormal& value) noexcept;
bool decode(binary::Reader& input, SequenceTrackSetPrefix& value) noexcept;

bool encode(binary::Writer& output, const TerrainTablePrefix& value) noexcept;
bool encode(binary::Writer& output, const TerrainTile& value) noexcept;
bool encode(binary::Writer& output, const TerrainPlacement& value) noexcept;
bool encode(binary::Writer& output, const TerrainNormal& value) noexcept;
bool encode(binary::Writer& output, const SequenceTrackSetPrefix& value) noexcept;

}  // namespace neosteam::asset
