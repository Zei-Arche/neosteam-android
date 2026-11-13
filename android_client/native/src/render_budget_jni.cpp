#include <jni.h>

#include <array>
#include <exception>

#include "neosteam/render_budget.hpp"

namespace {
using namespace neosteam::render;

constexpr int columns = 12;
constexpr int rows = 16;
constexpr int diagnostic_count = 5;
constexpr FrameBudget preview_budget{64, 768};
constexpr std::array preview_lods{Lod{0, 1, 12}, Lod{16, 1, 4}, Lod{28, 1, 2}};

const auto& preview_scene() {
    static const auto scene = [] {
        std::array<RenderItem, columns * rows> items{};
        for (std::size_t index = 0; index < items.size(); ++index) {
            const auto x = (static_cast<double>(index % columns) - 5.5) * 2.5;
            const auto z = -static_cast<double>(index / columns) * 3.0;
            items[index] = {index, Sphere{{x, 0, z}, 1.25}, preview_lods, 42, false};
        }
        return items;
    }();
    return scene;
}
}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_org_neosteam_revival_performance_ScenePreview_planFrame(
    JNIEnv* env, jclass, jfloatArray column_major_clip, jfloat camera_x, jfloat camera_y, jfloat camera_z) {
    if (column_major_clip == nullptr || env->GetArrayLength(column_major_clip) != 16) {
        if (const auto type = env->FindClass("java/lang/IllegalArgumentException")) {
            env->ThrowNew(type, "Expected sixteen view-projection coefficients");
        }
        return nullptr;
    }

    std::array<jfloat, 16> column_major{};
    env->GetFloatArrayRegion(column_major_clip, 0, static_cast<jsize>(column_major.size()), column_major.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    try {
        Matrix4 clip{};
        for (std::size_t row = 0; row < clip.size(); ++row) {
            for (std::size_t column = 0; column < clip[row].size(); ++column) {
                clip[row][column] = column_major[column * clip.size() + row];
            }
        }
        const auto frustum = Frustum::from_clip(clip, ClipDepth::minus_one_to_one);
        static thread_local FramePlanner planner;
        const auto& plan = planner.plan(frustum, {camera_x, camera_y, camera_z}, preview_scene(), preview_budget);
        std::array<jint, diagnostic_count + preview_budget.max_draw_calls * 2> output{};
        output[0] = static_cast<jint>(plan.draw_calls);
        output[1] = static_cast<jint>(plan.triangles);
        output[2] = static_cast<jint>(plan.frustum_rejected);
        output[3] = static_cast<jint>(plan.distance_rejected);
        output[4] = static_cast<jint>(plan.budget_rejected);
        std::size_t count = diagnostic_count;
        for (const auto& submission : plan.submissions) {
            output[count++] = static_cast<jint>(submission.input_index);
            output[count++] = static_cast<jint>(submission.lod_index);
        }
        auto array = env->NewIntArray(static_cast<jsize>(count));
        if (array != nullptr) {
            env->SetIntArrayRegion(array, 0, static_cast<jsize>(count), output.data());
        }
        return array;
    } catch (const std::exception&) {
        if (const auto type = env->FindClass("java/lang/IllegalStateException")) {
            env->ThrowNew(type, "Native frame planning failed");
        }
        return nullptr;
    }
}
