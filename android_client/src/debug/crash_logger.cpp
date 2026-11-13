#include <jni.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <signal.h>
#include <stdio.h>
#include <unistd.h>

namespace {

constexpr std::array crash_signals{SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV};
constexpr std::size_t uuid_size = 36;
constexpr char name_prefix[] = "native-";
constexpr char name_suffix[] = ".report";
constexpr char temp_suffix[] = ".tmp";
constexpr std::size_t name_size = sizeof(name_prefix) - 1 + uuid_size + sizeof(name_suffix);

std::mutex install_mutex;
std::array<struct sigaction, crash_signals.size()> previous_actions{};
char report_name[name_size]{};
char temp_name[name_size + sizeof(temp_suffix) - 1]{};
std::atomic<int> report_directory{-1};
std::atomic<std::uint32_t> recorded_events{0};
std::atomic_flag report_started = ATOMIC_FLAG_INIT;
static_assert(decltype(report_directory)::is_always_lock_free);
static_assert(decltype(recorded_events)::is_always_lock_free);

bool valid_report_name(const char* name) {
    constexpr std::size_t prefix_size = sizeof(name_prefix) - 1;
    if (std::strlen(name) != name_size - 1 || std::strncmp(name, name_prefix, prefix_size) != 0 ||
        std::strcmp(name + prefix_size + uuid_size, name_suffix) != 0) return false;
    for (std::size_t i = 0; i < uuid_size; ++i) {
        const char value = name[prefix_size + i];
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (value != '-') return false;
        } else if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
            return false;
        }
    }
    return true;
}

// These small formatters avoid the stdio formatter, allocation, and locks in the handler.
char* append_text(char* output, const char* value) {
    while (*value != '\0') *output++ = *value++;
    return output;
}

char* append_integer(char* output, std::int64_t value) {
    if (value < 0) {
        *output++ = '-';
        value = -value;
    }
    char digits[20];
    std::size_t length = 0;
    do {
        digits[length++] = static_cast<char>('0' + value % 10);
        value /= 10;
    } while (value != 0);
    while (length != 0) *output++ = digits[--length];
    return output;
}

void persist_report(int directory, int signal_number, int signal_code) {
    if (report_started.test_and_set(std::memory_order_relaxed)) return;

    char text[96];
    char* end = append_integer(append_text(text, "signal="), signal_number);
    end = append_integer(append_text(end, "\ncode="), signal_code);
    end = append_integer(append_text(end, "\nevents="), recorded_events.load(std::memory_order_relaxed));
    *end++ = '\n';

    const int fd = openat(directory, temp_name, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) return;
    const char* written = text;
    while (written < end) {
        const ssize_t count = write(fd, written, static_cast<std::size_t>(end - written));
        if (count <= 0) break;
        written += count;
    }
    const bool complete = close(fd) == 0 && written == end;
    // A complete write survives ordinary process death through the kernel's file cache.
    // Leave an incomplete write out of the upload queue.
    if (!complete || renameat(directory, temp_name, directory, report_name) != 0) {
        unlinkat(directory, temp_name, 0);
    }
}

void handle_crash(int signal_number, siginfo_t* info, void* context) {
    const int saved_errno = errno;
    const int directory = report_directory.load(std::memory_order_acquire);
    if (directory >= 0) persist_report(directory, signal_number, info == nullptr ? 0 : info->si_code);
    errno = saved_errno;

    for (std::size_t i = 0; i < crash_signals.size(); ++i) {
        if (crash_signals[i] != signal_number) continue;
        const struct sigaction& previous = previous_actions[i];
        if (previous.sa_handler == SIG_IGN) return;
        if (previous.sa_handler == SIG_DFL) {
            // Re-deliver on the crashing thread once the kernel's mask permits it.
            if (sigaction(signal_number, &previous, nullptr) == 0 && raise(signal_number) == 0) return;
            _exit(128 + signal_number);
        }
        // Forward the original thread context so Android's debuggerd retains its tombstone.
        if ((previous.sa_flags & SA_SIGINFO) != 0) {
            previous.sa_sigaction(signal_number, info, context);
        } else {
            previous.sa_handler(signal_number);
        }
        return;
    }
}

bool install_handlers(const char* directory, const char* name) {
    std::lock_guard lock(install_mutex);
    if (report_directory.load(std::memory_order_relaxed) >= 0) return true;
    if (!valid_report_name(name)) return false;
    const int fd = open(directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    for (std::size_t i = 0; i < crash_signals.size(); ++i) {
        if (sigaction(crash_signals[i], nullptr, &previous_actions[i]) != 0) {
            close(fd);
            return false;
        }
    }

    std::memcpy(report_name, name, name_size);
    std::memcpy(temp_name, name, name_size - 1);
    std::memcpy(temp_name + name_size - 1, temp_suffix, sizeof(temp_suffix));
    report_directory.store(fd, std::memory_order_release);

    bool installed = false;
    for (std::size_t i = 0; i < crash_signals.size(); ++i) {
        struct sigaction action = previous_actions[i];
        if (action.sa_handler == SIG_IGN) continue;
        action.sa_sigaction = handle_crash;
        action.sa_flags |= SA_SIGINFO | SA_ONSTACK;
        if (sigaction(crash_signals[i], &action, nullptr) == 0) installed = true;
    }
    if (!installed) {
        report_directory.store(-1, std::memory_order_release);
        close(fd);
    }
    return installed;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_neosteam_revival_diagnostics_CrashReporter_installNative(
    JNIEnv* env, jclass, jstring directory, jstring name) {
    if (directory == nullptr || name == nullptr) return JNI_FALSE;
    const char* directory_value = env->GetStringUTFChars(directory, nullptr);
    if (directory_value == nullptr) return JNI_FALSE;
    const char* name_value = env->GetStringUTFChars(name, nullptr);
    const bool installed = name_value != nullptr && install_handlers(directory_value, name_value);
    if (name_value != nullptr) env->ReleaseStringUTFChars(name, name_value);
    env->ReleaseStringUTFChars(directory, directory_value);
    return installed ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_neosteam_revival_diagnostics_CrashReporter_setNativeEvents(
    JNIEnv*, jclass, jint event_mask) {
    recorded_events.store(static_cast<std::uint32_t>(event_mask), std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_org_neosteam_revival_diagnostics_CrashReporter_crashNativeForTest(JNIEnv*, jclass) {
    std::abort();
}
