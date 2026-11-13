#include "neosteam/tcp_client.hpp"

#include <arpa/inet.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <android/multinetwork.h>
#endif

#include <array>
#include <atomic>
#include <cerrno>
#include <climits>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <utility>

namespace neosteam::net {
namespace {

class Socket {
public:
    explicit Socket(int fd = -1) noexcept : fd_(fd) {}
    ~Socket() {
        if (fd_ >= 0) {
            ::close(fd_);
        }
    }
    Socket(const Socket&) = delete;
    Socket& operator=(const Socket&) = delete;
    [[nodiscard]] int get() const noexcept { return fd_; }

private:
    int fd_;
};

std::array<int, 2> make_wake_pair() {
    std::array<int, 2> pair;
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0, pair.data()) != 0) {
        throw std::system_error(errno, std::generic_category(), "TCP worker wake socket");
    }
    return pair;
}

TcpOptions validate(TcpOptions options) {
    if (options.connect_timeout.count() <= 0 || options.connect_timeout.count() > INT_MAX ||
        options.send_queue_limit == 0 || options.receive_queue_limit == 0) {
        throw std::invalid_argument("TCP timeout must be in 1..INT_MAX milliseconds and queue limits positive");
    }
    return options;
}

struct Endpoint {
    sockaddr_storage address{};
    socklen_t size{};
};

Endpoint parse_address(std::string_view address, std::uint16_t port) {
    Endpoint endpoint;
    std::array<char, INET6_ADDRSTRLEN> literal{};
    if (address.empty() || address.size() >= literal.size() ||
        address.find('\0') != std::string_view::npos || port == 0) {
        return endpoint;
    }
    std::memcpy(literal.data(), address.data(), address.size());
    sockaddr_in ipv4{};
    if (inet_pton(AF_INET, literal.data(), &ipv4.sin_addr) == 1) {
        ipv4.sin_family = AF_INET;
        ipv4.sin_port = htons(port);
        std::memcpy(&endpoint.address, &ipv4, sizeof(ipv4));
        endpoint.size = sizeof(ipv4);
        return endpoint;
    }
    sockaddr_in6 ipv6{};
    if (inet_pton(AF_INET6, literal.data(), &ipv6.sin6_addr) == 1) {
        ipv6.sin6_family = AF_INET6;
        ipv6.sin6_port = htons(port);
        std::memcpy(&endpoint.address, &ipv6, sizeof(ipv6));
        endpoint.size = sizeof(ipv6);
    }
    return endpoint;
}

std::error_code socket_error(int error) {
    return {error, std::generic_category()};
}

std::error_code pending_error(int fd) {
    int error = 0;
    socklen_t length = sizeof(error);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) != 0) {
        return socket_error(errno);
    }
    return socket_error(error);
}

TcpEvent failure(TcpOperation operation, std::error_code error) {
    return {TcpEventKind::error, operation, error};
}

}  // namespace

class TcpClient::Impl {
public:
    explicit Impl(TcpOptions options) : Impl(options, make_wake_pair()) {}

    ~Impl() {
        close();
        if (worker_.joinable()) {
            worker_.join();
        }
    }

    std::error_code start(std::string_view address, std::uint16_t port) {
#if !defined(__ANDROID__)
        if (options_.android_network_handle != 0) {
            return std::make_error_code(std::errc::operation_not_supported);
        }
#endif
        const auto endpoint = parse_address(address, port);
        if (endpoint.size == 0) {
            return std::make_error_code(std::errc::invalid_argument);
        }
        const std::scoped_lock lock(mutex_);
        if (started_) {
            return std::make_error_code(std::errc::operation_in_progress);
        }
        try {
            worker_ = std::thread([this, endpoint] { run(endpoint); });
        } catch (const std::system_error& error) {
            return error.code();
        }
        started_ = true;
        return {};
    }

    std::error_code send(std::span<const std::byte> bytes) {
        const std::scoped_lock lock(mutex_);
        if (!connected_) {
            return std::make_error_code(std::errc::not_connected);
        }
        if (bytes.size() > options_.send_queue_limit - unsent_bytes_) {
            return std::make_error_code(std::errc::no_buffer_space);
        }
        if (!bytes.empty()) {
            pending_send_.insert(pending_send_.end(), bytes.begin(), bytes.end());
            unsent_bytes_ += bytes.size();
            wake();
        }
        return {};
    }

    std::vector<TcpEvent> take_events() {
        const std::scoped_lock lock(mutex_);
        std::vector<TcpEvent> result;
        result.swap(events_);
        received_bytes_ = 0;
        return result;
    }

    void close() noexcept {
        const std::scoped_lock lock(mutex_);
        if (started_ && !finished_) {
            connected_ = false;
            stopping_.store(true);
            wake();
        }
    }

private:
    Impl(TcpOptions options, std::array<int, 2> wake_fds)
        : options_(options), wake_read_(wake_fds[0]), wake_write_(wake_fds[1]) {}

    void wake() const noexcept {
        const std::byte signal{};
        while (::send(wake_write_.get(), &signal, 1, MSG_NOSIGNAL) == -1 && errno == EINTR) {
        }
        // EAGAIN means a wakeup is already pending.
    }

    int wait_ready(int fd, short interests, int timeout_ms, short& ready) const {
        std::array<pollfd, 2> fds{{
            {wake_read_.get(), POLLIN, 0},
            {fd, interests, 0},
        }};
        const int count = ::poll(fds.data(), fds.size(), timeout_ms);
        ready = fds[1].revents;
        if (count > 0 && (fds[0].revents & POLLIN)) {
            std::array<std::byte, 256> drained;
            ::recv(wake_read_.get(), drained.data(), drained.size(), 0);
        }
        return count;
    }

    std::error_code open_connection(int fd, const Endpoint& endpoint) const {
        if (::connect(fd, reinterpret_cast<const sockaddr*>(&endpoint.address), endpoint.size) == 0) {
            return {};
        }
        if (errno != EINPROGRESS && errno != EINTR) {
            return socket_error(errno);
        }
        using Clock = std::chrono::steady_clock;
        using std::chrono::milliseconds;
        const auto deadline = Clock::now() + options_.connect_timeout;
        while (!stopping_.load()) {
            const auto wait = std::chrono::ceil<milliseconds>(deadline - Clock::now());
            if (wait.count() <= 0) {
                return std::make_error_code(std::errc::timed_out);
            }
            short ready = 0;
            if (wait_ready(fd, POLLOUT, static_cast<int>(wait.count()), ready) < 0) {
                if (errno == EINTR) {
                    continue;
                }
                return socket_error(errno);
            }
            if (ready & POLLNVAL) {
                return std::make_error_code(std::errc::bad_file_descriptor);
            }
            if (ready) {
                return pending_error(fd);
            }
        }
        return std::make_error_code(std::errc::operation_canceled);
    }

    bool queue_received(std::span<const std::byte> bytes) {
        const std::scoped_lock lock(mutex_);
        if (bytes.size() > options_.receive_queue_limit - received_bytes_) {
            return false;
        }
        if (events_.empty() || events_.back().kind != TcpEventKind::bytes) {
            events_.push_back({TcpEventKind::bytes});
        }
        auto& target = events_.back().bytes;
        target.insert(target.end(), bytes.begin(), bytes.end());
        received_bytes_ += bytes.size();
        return true;
    }

    TcpEvent pump(int fd) {
        std::array<std::byte, 16 * 1024> receive_buffer;
        std::vector<std::byte> active_send;
        std::size_t sent = 0;
        while (!stopping_.load()) {
            if (sent == active_send.size()) {
                const std::scoped_lock lock(mutex_);
                active_send.clear();
                active_send.swap(pending_send_);
                sent = 0;
            }
            short interests = POLLIN;
            if (sent < active_send.size()) {
                interests |= POLLOUT;
            }
            short ready = 0;
            if (wait_ready(fd, interests, -1, ready) < 0) {
                if (errno == EINTR) {
                    continue;
                }
                return failure(TcpOperation::poll, socket_error(errno));
            }
            if (stopping_.load()) {
                break;
            }
            if (ready & POLLNVAL) {
                return failure(TcpOperation::poll, std::make_error_code(std::errc::bad_file_descriptor));
            }
            if (ready & (POLLIN | POLLHUP | POLLERR)) {
                const auto count = ::recv(fd, receive_buffer.data(), receive_buffer.size(), 0);
                if (count == 0) {
                    return {TcpEventKind::peer_closed};
                }
                if (count > 0 && !queue_received(std::span(receive_buffer).first(static_cast<std::size_t>(count)))) {
                    return failure(TcpOperation::receive, std::make_error_code(std::errc::no_buffer_space));
                }
                if (count < 0) {
                    if (errno != EAGAIN && errno != EINTR) {
                        return failure(TcpOperation::receive, socket_error(errno));
                    }
                    if ((ready & POLLERR) && errno == EAGAIN) {
                        const auto error = pending_error(fd);
                        if (error) {
                            return failure(TcpOperation::receive, error);
                        }
                    }
                }
            }
            if ((ready & POLLOUT) && sent < active_send.size()) {
                const auto count = ::send(fd, active_send.data() + sent, active_send.size() - sent, MSG_NOSIGNAL);
                if (count > 0) {
                    const auto written = static_cast<std::size_t>(count);
                    sent += written;
                    const std::scoped_lock lock(mutex_);
                    unsent_bytes_ -= written;
                } else if (count < 0 && errno != EAGAIN && errno != EINTR) {
                    return failure(TcpOperation::send, socket_error(errno));
                }
            }
        }
        return {TcpEventKind::closed};
    }

    TcpEvent connect_and_pump(const Endpoint& endpoint) {
        const Socket socket(::socket(endpoint.address.ss_family, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0));
        if (socket.get() < 0) {
            return failure(TcpOperation::connect, socket_error(errno));
        }
        if (stopping_.load()) {
            return {TcpEventKind::closed};
        }
#if defined(__ANDROID__)
        if (options_.android_network_handle != 0 &&
            android_setsocknetwork(options_.android_network_handle, socket.get()) != 0) {
            return failure(TcpOperation::connect, socket_error(errno));
        }
#endif
        const auto error = open_connection(socket.get(), endpoint);
        if (error) {
            return failure(TcpOperation::connect, error);
        }
        {
            const std::scoped_lock lock(mutex_);
            if (stopping_.load()) {
                return {TcpEventKind::closed};
            }
            events_.push_back({TcpEventKind::connected});
            connected_ = true;
        }
        return pump(socket.get());
    }

    void run(const Endpoint& endpoint) {
        auto terminal = connect_and_pump(endpoint);
        const std::scoped_lock lock(mutex_);
        if (stopping_.load()) {
            terminal = {TcpEventKind::closed};
        }
        events_.push_back(std::move(terminal));
        connected_ = false;
        finished_ = true;
        pending_send_.clear();
        unsent_bytes_ = 0;
    }

    const TcpOptions options_;
    const Socket wake_read_;
    const Socket wake_write_;
    std::mutex mutex_;
    std::atomic_bool stopping_{false};
    bool started_{false};
    bool connected_{false};
    bool finished_{false};
    std::size_t unsent_bytes_{0};
    std::size_t received_bytes_{0};
    std::vector<std::byte> pending_send_;
    std::vector<TcpEvent> events_;
    std::thread worker_;
};

TcpClient::TcpClient(TcpOptions options) : impl_(std::make_unique<Impl>(validate(options))) {}
TcpClient::~TcpClient() = default;

std::error_code TcpClient::connect(std::string_view address, std::uint16_t port) {
    return impl_->start(address, port);
}

std::error_code TcpClient::send(std::span<const std::byte> bytes) { return impl_->send(bytes); }
std::vector<TcpEvent> TcpClient::take_events() { return impl_->take_events(); }
void TcpClient::close() noexcept { impl_->close(); }

}  // namespace neosteam::net
