#include <neosteam/text_codec.hpp>

#include <array>
#include <cstdint>
#include <iterator>

namespace neosteam::text {
namespace {

constexpr unsigned char first_lead = 0x81;
constexpr unsigned char last_lead = 0xfd;
constexpr unsigned char first_trail = 0x41;
constexpr unsigned char last_trail = 0xfe;

constexpr std::uint16_t double_byte_map[][last_trail - first_trail + 1] = {
#include "cp949_pairs.inc"
};
static_assert(std::size(double_byte_map) == last_lead - first_lead + 1);

std::uint16_t lookup_pair(unsigned char lead, unsigned char trail) noexcept {
    if (lead < first_lead || lead > last_lead || trail < first_trail || trail > last_trail) {
        return 0;
    }
    return double_byte_map[lead - first_lead][trail - first_trail];
}

void append_non_ascii_utf8(std::string& output, std::uint16_t scalar) {
    // Every mapped non-ASCII scalar is in the BMP and outside the surrogate range.
    if (scalar < 0x800) {
        output.push_back(static_cast<char>(0xc0 | (scalar >> 6)));
    } else {
        output.push_back(static_cast<char>(0xe0 | (scalar >> 12)));
        output.push_back(static_cast<char>(0x80 | ((scalar >> 6) & 0x3f)));
    }
    output.push_back(static_cast<char>(0x80 | (scalar & 0x3f)));
}

const std::array<std::uint16_t, 0x10000>& encoding_map() {
    static const auto map = [] {
        std::array<std::uint16_t, 0x10000> result{};
        for (unsigned lead = first_lead; lead <= last_lead; ++lead) {
            for (unsigned trail = first_trail; trail <= last_trail; ++trail) {
                const auto scalar = double_byte_map[lead - first_lead][trail - first_trail];
                if (scalar != 0 && result[scalar] == 0) {
                    result[scalar] = static_cast<std::uint16_t>((lead << 8) | trail);
                }
            }
        }
        return result;
    }();
    return map;
}

std::uint32_t read_non_ascii_utf8(std::string_view input, std::size_t& offset) {
    const auto start = offset;
    const auto lead = static_cast<unsigned char>(input[start]);
    unsigned width;
    unsigned scalar;
    if (lead >= 0xc2 && lead <= 0xdf) {
        width = 2;
        scalar = lead & 0x1f;
    } else if (lead >= 0xe0 && lead <= 0xef) {
        width = 3;
        scalar = lead & 0x0f;
    } else if (lead >= 0xf0 && lead <= 0xf4) {
        width = 4;
        scalar = lead & 0x07;
    } else {
        throw Cp949EncodeError(start, Cp949EncodeFailure::invalid_utf8);
    }
    if (width > input.size() - start) {
        throw Cp949EncodeError(start, Cp949EncodeFailure::invalid_utf8);
    }
    for (unsigned index = 1; index < width; ++index) {
        const auto byte = static_cast<unsigned char>(input[start + index]);
        if ((byte & 0xc0) != 0x80) {
            throw Cp949EncodeError(start, Cp949EncodeFailure::invalid_utf8);
        }
        scalar = (scalar << 6) | (byte & 0x3f);
    }
    constexpr std::array minimum{0u, 0u, 0x80u, 0x800u, 0x10000u};
    if (scalar < minimum[width] || (scalar >= 0xd800 && scalar <= 0xdfff) || scalar > 0x10ffff) {
        throw Cp949EncodeError(start, Cp949EncodeFailure::invalid_utf8);
    }
    offset += width;
    return scalar;
}

}  // namespace

Cp949DecodeError::Cp949DecodeError(std::size_t offset)
    : std::invalid_argument("Invalid CP949 sequence at byte " + std::to_string(offset)),
      offset_(offset) {}

Cp949EncodeError::Cp949EncodeError(std::size_t offset, Cp949EncodeFailure reason)
    : std::invalid_argument(std::string(reason == Cp949EncodeFailure::invalid_utf8
          ? "Invalid UTF-8 at byte " : "Character cannot be encoded in CP949 at UTF-8 byte ")
          + std::to_string(offset)),
      offset_(offset), reason_(reason) {}

std::string decode_cp949(std::string_view bytes, Cp949ErrorPolicy policy) {
    std::string output;
    output.reserve(bytes.size());

    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const auto lead = static_cast<unsigned char>(bytes[offset]);
        if (lead <= 0x7f) {
            output.push_back(bytes[offset++]);
            continue;
        }

        const auto scalar = offset + 1 < bytes.size()
                                ? lookup_pair(lead, static_cast<unsigned char>(bytes[offset + 1]))
                                : std::uint16_t{0};
        if (scalar != 0) {
            append_non_ascii_utf8(output, scalar);
            offset += 2;
            continue;
        }

        if (policy != Cp949ErrorPolicy::replace) {
            throw Cp949DecodeError(offset);
        }
        append_non_ascii_utf8(output, 0xfffd);
        ++offset;
    }
    return output;
}

std::string encode_cp949(std::string_view utf8) {
    std::string output;
    output.reserve(utf8.size());
    std::size_t offset = 0;
    while (offset < utf8.size()) {
        const auto byte = static_cast<unsigned char>(utf8[offset]);
        if (byte <= 0x7f) {
            output.push_back(utf8[offset++]);
            continue;
        }
        const auto start = offset;
        const auto scalar = read_non_ascii_utf8(utf8, offset);
        const auto& map = encoding_map();
        const auto code = scalar < map.size() ? map[scalar] : std::uint16_t{0};
        if (code == 0) {
            throw Cp949EncodeError(start, Cp949EncodeFailure::unmappable);
        }
        output.push_back(static_cast<char>(code >> 8));
        output.push_back(static_cast<char>(code & 0xff));
    }
    return output;
}

}  // namespace neosteam::text
