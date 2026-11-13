#include "neosteam/render_budget.hpp"

#include <algorithm>
#include <cmath>

namespace neosteam::render {
namespace {

using Plane = std::array<double, 4>;

bool finite(Vec3 point) {
    return std::isfinite(point.x) && std::isfinite(point.y) && std::isfinite(point.z);
}

bool valid(const Sphere& sphere) {
    return finite(sphere.center) && std::isfinite(sphere.radius) && sphere.radius >= 0;
}

Plane combine(const Plane& a, const Plane& b, double sign) {
    return {a[0] + sign * b[0], a[1] + sign * b[1], a[2] + sign * b[2], a[3] + sign * b[3]};
}

Plane normalize(Plane plane) {
    const double length = std::hypot(plane[0], plane[1], plane[2]);
    if (!std::isfinite(length) || length == 0 || !std::isfinite(plane[3])) {
        return {};
    }
    for (auto& coefficient : plane) {
        coefficient /= length;
    }
    return plane;
}

bool outside(const Plane& plane, const Sphere& sphere, double normal_length) {
    const auto& c = sphere.center;
    const double x = plane[0] * c.x;
    const double y = plane[1] * c.y;
    const double z = plane[2] * c.z;
    const double distance = x + y + z + plane[3];
    const double support = sphere.radius * normal_length;
    // Keep a tangent bound when extraction and GPU float transforms round differently.
    constexpr double slack = 8 * std::numeric_limits<float>::epsilon();
    const double scale = std::abs(x) + std::abs(y) + std::abs(z) + std::abs(plane[3]) + support;
    return distance + support < -slack * scale;
}

double edge_distance(const Sphere& sphere, Vec3 camera) {
    if (!finite(camera)) {
        return 0;
    }
    const Vec3 center = sphere.center;
    const double dx = center.x - camera.x;
    const double dy = center.y - camera.y;
    const double dz = center.z - camera.z;
    // Typical game coordinates are in a small finite range, so the squared
    // norm fits in double. The scaled fallback also covers wide coordinate inputs.
    const double squared = dx * dx + dy * dy + dz * dz;
    const double distance = std::isfinite(squared) ? std::sqrt(squared) : std::hypot(dx, dy, dz);
    return std::max(0.0, distance - sphere.radius);
}

}  // namespace

Frustum Frustum::from_clip(const Matrix4& clip, ClipDepth depth) {
    Frustum frustum;
    frustum.planes_ = {
        normalize(combine(clip[3], clip[0], 1)), normalize(combine(clip[3], clip[0], -1)),
        normalize(combine(clip[3], clip[1], 1)), normalize(combine(clip[3], clip[1], -1)),
        normalize(depth == ClipDepth::zero_to_one ? clip[2] : combine(clip[3], clip[2], 1)),
        normalize(combine(clip[3], clip[2], -1)),
    };
    return frustum;
}

bool Frustum::intersects(const Sphere& sphere) const {
    return !valid(sphere) || std::ranges::none_of(planes_, [&](const Plane& plane) {
        return outside(plane, sphere, 1);
    });
}

bool Frustum::intersects_local(const Sphere& sphere, const Matrix4& world_from_local) const {
    if (!valid(sphere)) {
        return true;
    }
    for (const auto& world_plane : planes_) {
        Plane local_plane{};
        for (std::size_t column = 0; column < local_plane.size(); ++column) {
            for (std::size_t row = 0; row < world_plane.size(); ++row) {
                local_plane[column] += world_plane[row] * world_from_local[row][column];
            }
        }
        if (outside(local_plane, sphere, std::hypot(local_plane[0], local_plane[1], local_plane[2]))) {
            return false;
        }
    }
    return true;
}

const FramePlan& FramePlanner::plan(const Frustum& frustum, Vec3 camera,
                                    std::span<const RenderItem> items, FrameBudget budget) {
    plan_.submissions.clear();
    plan_.draw_calls = 0;
    plan_.triangles = 0;
    plan_.frustum_rejected = 0;
    plan_.distance_rejected = 0;
    plan_.budget_rejected = 0;
    candidates_.clear();
    candidates_.reserve(items.size());

    for (std::size_t index = 0; index < items.size(); ++index) {
        const auto& item = items[index];
        const bool bounded = item.bounds && valid(*item.bounds);
        if (bounded && !frustum.intersects(*item.bounds)) {
            ++plan_.frustum_rejected;
            continue;
        }
        const double distance = bounded ? edge_distance(*item.bounds, camera) : 0;
        if (bounded && distance > item.max_distance) {
            ++plan_.distance_rejected;
            continue;
        }
        if (item.lods.empty()) {
            continue;
        }
        std::size_t lod_index = 0;
        while (lod_index + 1 < item.lods.size() && distance >= item.lods[lod_index + 1].min_distance) {
            ++lod_index;
        }
        candidates_.push_back({index, lod_index, distance});
    }

    std::ranges::sort(candidates_, [&](const Candidate& a, const Candidate& b) {
        const auto& left = items[a.input_index];
        const auto& right = items[b.input_index];
        if (left.important != right.important) {
            return left.important;
        }
        if (a.distance != b.distance) {
            return a.distance < b.distance;
        }
        return a.input_index < b.input_index;
    });

    plan_.submissions.reserve(std::min<std::size_t>(budget.max_draw_calls, candidates_.size()));
    for (const auto& candidate : candidates_) {
        const auto& item = items[candidate.input_index];
        bool submitted = false;
        for (std::size_t index = candidate.lod_index; index < item.lods.size(); ++index) {
            const auto& lod = item.lods[index];
            if (lod.draw_calls == 0 || lod.draw_calls > budget.max_draw_calls - plan_.draw_calls ||
                lod.triangles > budget.max_triangles - plan_.triangles) {
                continue;
            }
            plan_.draw_calls += lod.draw_calls;
            plan_.triangles += lod.triangles;
            plan_.submissions.push_back({item.id, candidate.input_index, index});
            submitted = true;
            break;
        }
        if (!submitted) {
            ++plan_.budget_rejected;
        }
    }
    std::ranges::sort(plan_.submissions, {}, &Submission::input_index);
    return plan_;
}

}  // namespace neosteam::render
