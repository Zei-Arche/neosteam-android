#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>

// Verified Data/ records for gameplay presentation. All multibyte disk fields
// are little-endian. Read fields from bounded byte spans into aligned values.
// The supplied NeoSteam.c has no verified live entity ABI or server wire schema.
namespace neosteam::asset {

#pragma pack(push, 1)

struct TerrainTablePrefixDisk {
    char magic[32];
    std::uint32_t tile_width;
    std::uint32_t tile_height;
};

struct TerrainTileDisk {
    std::uint16_t base_texture_index;
    std::uint8_t base_modifier;
    std::uint16_t decal_texture_index;
    std::uint8_t decal_modifier;
    std::uint8_t object_count_low;
    std::uint8_t object_count_high_or_reserved;
};

struct TerrainPlacementDisk {
    std::uint8_t tile_x;
    std::uint8_t tile_y;
    std::uint8_t tile_ordinal;
    std::uint16_t object_catalog_id;
    std::uint8_t unknown_05[2];
    float position[3];
    float angle_candidate;
    float axis[3];
    float scale_candidate;
};

struct TerrainNormalDisk {
    float x;
    float y;
    float z;
};

struct SequenceTrackSetPrefixDisk {
    char magic[21];
    std::uint8_t marker[4];
};

#pragma pack(pop)

static_assert(sizeof(float) == 4 && std::numeric_limits<float>::is_iec559);
static_assert(sizeof(TerrainTablePrefixDisk) == 0x28);
static_assert(offsetof(TerrainTablePrefixDisk, tile_width) == 0x20);
static_assert(offsetof(TerrainTablePrefixDisk, tile_height) == 0x24);
static_assert(sizeof(TerrainTileDisk) == 8);
static_assert(offsetof(TerrainTileDisk, decal_texture_index) == 0x03);
static_assert(offsetof(TerrainTileDisk, object_count_low) == 0x06);
static_assert(sizeof(TerrainPlacementDisk) == 39);
static_assert(offsetof(TerrainPlacementDisk, object_catalog_id) == 0x03);
static_assert(offsetof(TerrainPlacementDisk, position) == 0x07);
static_assert(offsetof(TerrainPlacementDisk, angle_candidate) == 0x13);
static_assert(offsetof(TerrainPlacementDisk, axis) == 0x17);
static_assert(offsetof(TerrainPlacementDisk, scale_candidate) == 0x23);
static_assert(sizeof(TerrainNormalDisk) == 12);
static_assert(offsetof(TerrainNormalDisk, z) == 0x08);
static_assert(sizeof(SequenceTrackSetPrefixDisk) == 25);
static_assert(offsetof(SequenceTrackSetPrefixDisk, marker) == 0x15);

}  // namespace neosteam::asset
