#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>
#include <span>
#include <vector>

namespace neosteam::render {

struct Vec3 {
    double x{};
    double y{};
    double z{};
};

struct Sphere {
    Vec3 center;
    double radius{};
};

// Row-major storage, multiplied by a column vector.
using Matrix4 = std::array<std::array<double, 4>, 4>;

enum class ClipDepth { minus_one_to_one, zero_to_one };

class Frustum {
public:
    // Use the same view-projection transform and depth convention as the draw pass.
    [[nodiscard]] static Frustum from_clip(const Matrix4& clip_from_world, ClipDepth depth);
    [[nodiscard]] bool intersects(const Sphere& world_sphere) const;
    // The affine transform may contain nonuniform scale or shear.
    [[nodiscard]] bool intersects_local(const Sphere& local_sphere,
                                        const Matrix4& world_from_local) const;

private:
    std::array<std::array<double, 4>, 6> planes_{};
};

struct Lod {
    double min_distance{};  // World distance to the nearest point on the bound.
    std::uint32_t draw_calls{};
    std::uint64_t triangles{};
};

struct RenderItem {
    std::uint64_t id{};
    // Omit when a conservative posed bound is unavailable.
    std::optional<Sphere> bounds;
    // Supply fine-to-coarse order, with increasing minimum distances.
    std::span<const Lod> lods;
    double max_distance{std::numeric_limits<double>::infinity()};
    bool important{};
};

struct FrameBudget {
    std::uint32_t max_draw_calls{};
    std::uint64_t max_triangles{};
};

struct Submission {
    std::uint64_t id{};
    std::size_t input_index{};
    std::size_t lod_index{};
};

struct FramePlan {
    std::vector<Submission> submissions;
    std::uint32_t draw_calls{};
    std::uint64_t triangles{};
    std::size_t frustum_rejected{};
    std::size_t distance_rejected{};
    std::size_t budget_rejected{};
};

class FramePlanner {
public:
    // Results are in input order so existing material or transparency ordering
    // survives the budget selection. The next call reuses this result and its storage.
    [[nodiscard]] const FramePlan& plan(const Frustum& frustum, Vec3 camera,
                                        std::span<const RenderItem> items, FrameBudget budget);

private:
    struct Candidate {
        std::size_t input_index;
        std::size_t lod_index;
        double distance;
    };

    std::vector<Candidate> candidates_;
    FramePlan plan_;
};

}  // namespace neosteam::render
