#pragma once

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string_view>
#include <system_error>
#include <vector>

namespace neosteam::net {

enum class TcpEventKind { connected, bytes, peer_closed, closed, error };
enum class TcpOperation { none, connect, send, receive, poll };

struct TcpEvent {
    TcpEventKind kind;
    TcpOperation operation{TcpOperation::none};
    std::error_code error{};
    std::vector<std::byte> bytes{};
};

struct TcpOptions {
    std::chrono::milliseconds connect_timeout{5000};
    std::size_t send_queue_limit{1024 * 1024};
    std::size_t receive_queue_limit{1024 * 1024};
    // Android Network.getNetworkHandle(), or zero for the system default.
    std::uint64_t android_network_handle{};
};

// One asynchronous Android/Linux TCP session. IPv4 and IPv6 literals are
// accepted, with hostname resolution left to the caller. Received events are
// ordered stream chunks that may split or combine application frames. Peer EOF
// ends the session after any bytes already queued for delivery.
class TcpClient {
public:
    explicit TcpClient(TcpOptions options = {});
    ~TcpClient();

    TcpClient(const TcpClient&) = delete;
    TcpClient& operator=(const TcpClient&) = delete;

    // A successful return means the attempt was queued. Observe events for the result.
    [[nodiscard]] std::error_code connect(std::string_view address, std::uint16_t port);

    // Queues a complete copy or returns an error without accepting bytes. A
    // successful enqueue does not acknowledge delivery to the peer.
    [[nodiscard]] std::error_code send(std::span<const std::byte> bytes);
    [[nodiscard]] std::vector<TcpEvent> take_events();

    // Requests cancellation without joining. Unsent data is discarded. The
    // destructor cancels and joins the worker before freeing the session.
    void close() noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace neosteam::net
