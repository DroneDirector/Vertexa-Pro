// Streaming buffered reader over a file descriptor. Works for files of any size
// (also pipes from cloud providers) and never loads the whole file into memory.
#pragma once
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cmath>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "mesh.h"

namespace vx {

class FdReader {
public:
    FdReader(int fd, int64_t size, Sink* sink) : fd_(fd), size_(size), sink_(sink) {
        buf_.resize(kBuf);
    }

    // Returns pointer to the next line (without newline, NUL terminated) or nullptr at EOF.
    char* line(size_t* lenOut = nullptr) {
        while (true) {
            char* start = buf_.data() + pos_;
            char* nl = (char*)memchr(start, '\n', end_ - pos_);
            if (nl) {
                size_t len = nl - start;
                *nl = 0;
                if (len && start[len - 1] == '\r') start[--len] = 0;
                pos_ += (nl - start) + 1;
                if (lenOut) *lenOut = len;
                return start;
            }
            if (eof_) {
                if (pos_ >= end_) return nullptr;
                // last line without newline
                if (end_ >= buf_.size()) buf_.resize(buf_.size() + 1);
                buf_[end_] = 0;
                char* s = buf_.data() + pos_;
                size_t len = end_ - pos_;
                if (len && s[len - 1] == '\r') s[--len] = 0;
                pos_ = end_;
                if (lenOut) *lenOut = len;
                return s;
            }
            refill();
        }
    }

    // Reads exactly n bytes. Returns false at EOF.
    bool read(void* dst, size_t n) {
        uint8_t* out = (uint8_t*)dst;
        while (n) {
            if (pos_ >= end_) {
                if (eof_) return false;
                refill();
                continue;
            }
            size_t take = end_ - pos_;
            if (take > n) take = n;
            memcpy(out, buf_.data() + pos_, take);
            pos_ += take;
            out += take;
            n -= take;
        }
        return true;
    }

    // Peek at the first bytes of the file (only valid before consuming).
    size_t peek(void* dst, size_t n) {
        while (end_ - pos_ < n && !eof_) refill();
        size_t take = std::min(n, end_ - pos_);
        memcpy(dst, buf_.data() + pos_, take);
        return take;
    }

    int64_t size() const { return size_; }
    int64_t offset() const { return fileOffset_ - (int64_t)(end_ - pos_); }
    bool cancelled() const { return sink_ && sink_->cancel.load(); }

private:
    static constexpr size_t kBuf = 8u << 20;

    void refill() {
        // move remaining bytes to the front
        size_t rem = end_ - pos_;
        if (pos_ > 0) {
            memmove(buf_.data(), buf_.data() + pos_, rem);
            pos_ = 0;
            end_ = rem;
        }
        if (end_ + 1 >= buf_.size()) buf_.resize(buf_.size() * 2);  // very long line
        ssize_t r;
        do {
            r = ::read(fd_, buf_.data() + end_, buf_.size() - end_ - 1);
        } while (r < 0 && errno == EINTR);
        if (r <= 0) {
            eof_ = true;
            return;
        }
        end_ += (size_t)r;
        fileOffset_ += r;
        if (sink_ && size_ > 0) sink_->progress = (float)((double)offset() / (double)size_);
    }

    int fd_;
    int64_t size_;
    Sink* sink_;
    std::vector<char> buf_;
    size_t pos_ = 0, end_ = 0;
    int64_t fileOffset_ = 0;
    bool eof_ = false;
};

// ---- fast text number parsing -------------------------------------------------

inline const char* skipWs(const char* s) {
    while (*s == ' ' || *s == '\t') s++;
    return s;
}

inline double parseDouble(const char*& s) {
    s = skipWs(s);
    const char* p = s;
    bool neg = false;
    if (*p == '-') { neg = true; p++; } else if (*p == '+') p++;
    double v = 0;
    bool digits = false;
    while (*p >= '0' && *p <= '9') { v = v * 10 + (*p - '0'); p++; digits = true; }
    if (*p == '.') {
        p++;
        double f = 0.1;
        while (*p >= '0' && *p <= '9') { v += (*p - '0') * f; f *= 0.1; p++; digits = true; }
    }
    if (digits && (*p == 'e' || *p == 'E')) {
        const char* q = p + 1;
        bool en = false;
        if (*q == '-') { en = true; q++; } else if (*q == '+') q++;
        if (*q >= '0' && *q <= '9') {
            int e = 0;
            while (*q >= '0' && *q <= '9') { e = e * 10 + (*q - '0'); q++; }
            v *= std::pow(10.0, en ? -e : e);
            p = q;
        }
    }
    if (!digits) {
        // nan/inf or garbage: skip token
        while (*p && *p != ' ' && *p != '\t') p++;
        s = p;
        return 0;
    }
    s = p;
    return neg ? -v : v;
}

inline long parseInt(const char*& s) {
    const char* p = s;
    bool neg = false;
    if (*p == '-') { neg = true; p++; } else if (*p == '+') p++;
    long v = 0;
    while (*p >= '0' && *p <= '9') { v = v * 10 + (*p - '0'); p++; }
    s = p;
    return neg ? -v : v;
}

inline bool hasToken(const char* s) {
    s = skipWs(s);
    return *s && *s != '#';
}

inline std::string trim(const std::string& in) {
    size_t a = 0, b = in.size();
    while (a < b && (in[a] == ' ' || in[a] == '\t' || in[a] == '\r' || in[a] == '"')) a++;
    while (b > a && (in[b - 1] == ' ' || in[b - 1] == '\t' || in[b - 1] == '\r' || in[b - 1] == '"')) b--;
    return in.substr(a, b - a);
}

}  // namespace vx
