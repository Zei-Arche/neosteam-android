#include <jni.h>

#include <exception>
#include <string>

#include <neosteam/text_codec.hpp>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_neosteam_revival_gameplay_ChatSession_encodeCp949Bytes(
    JNIEnv* env, jclass, jbyteArray utf8) {
    try {
        const auto length = env->GetArrayLength(utf8);
        std::string input(static_cast<std::size_t>(length), '\0');
        if (length > 0) {
            env->GetByteArrayRegion(utf8, 0, length, reinterpret_cast<jbyte*>(input.data()));
            if (env->ExceptionCheck()) {
                return nullptr;
            }
        }
        const auto encoded = neosteam::text::encode_cp949(input);
        // CP949 never uses more bytes than the input UTF-8 representation.
        const auto encoded_length = static_cast<jsize>(encoded.size());
        auto result = env->NewByteArray(encoded_length);
        if (result != nullptr && encoded_length > 0) {
            env->SetByteArrayRegion(result, 0, encoded_length, reinterpret_cast<const jbyte*>(encoded.data()));
        }
        return result;
    } catch (const neosteam::text::Cp949EncodeError& error) {
        const auto type = env->FindClass("java/lang/IllegalArgumentException");
        if (type != nullptr) {
            env->ThrowNew(type, error.what());
        }
        return nullptr;
    } catch (const std::exception&) {
        const auto type = env->FindClass("java/lang/IllegalStateException");
        if (type != nullptr) {
            env->ThrowNew(type, "Legacy chat encoding failed");
        }
        return nullptr;
    }
}
