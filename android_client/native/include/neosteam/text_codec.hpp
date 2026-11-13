#pragma once

#include <cstddef>
#include <stdexcept>
#include <string>
#include <string_view>

namespace neosteam::text {

enum class Cp949ErrorPolicy { strict, replace };
enum class Cp949EncodeFailure { invalid_utf8, unmappable };

class Cp949DecodeError : public std::invalid_argument {
public:
    explicit Cp949DecodeError(std::size_t offset);

    [[nodiscard]] std::size_t offset() const noexcept { return offset_; }

private:
    std::size_t offset_;
};

class Cp949EncodeError : public std::invalid_argument {
public:
    Cp949EncodeError(std::size_t offset, Cp949EncodeFailure reason);
    [[nodiscard]] std::size_t offset() const noexcept { return offset_; }
    [[nodiscard]] Cp949EncodeFailure reason() const noexcept { return reason_; }

private:
    std::size_t offset_;
    Cp949EncodeFailure reason_;
};

// Preserves ASCII bytes, including embedded NULs. Strict decoding reports the
// zero-based offset of the first malformed byte. Replacement consumes one
// malformed byte as U+FFFD and resumes at the next byte.
[[nodiscard]] std::string decode_cp949(
    std::string_view bytes, Cp949ErrorPolicy policy = Cp949ErrorPolicy::strict);

// Converts well-formed UTF-8 using the same Microsoft CP949 mapping as decode.
// Invalid UTF-8 and unrepresentable scalars report the byte offset of the
// sequence start. Bytes are length-delimited and no terminator is appended.
[[nodiscard]] std::string encode_cp949(std::string_view utf8);

}  // namespace neosteam::text
