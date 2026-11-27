// Shared mesh data structures: loaders produce Chunks, the renderer consumes them.
#pragma once
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace vx {

// 28 bytes per vertex. Normal packed as GL_INT_2_10_10_10_REV.
struct Vertex {
    float p[3];
    uint32_t n;
    float uv[2];
    uint32_t c;  // RGBA8
};

inline uint32_t packNormal(float x, float y, float z) {
    float len = std::sqrt(x * x + y * y + z * z);
    if (len > 1e-20f) { x /= len; y /= len; z /= len; } else { x = 0; y = 1; z = 0; }
    auto q = [](float v) -> uint32_t {
        int i = (int)std::lround(v * 511.0f);
        if (i > 511) i = 511;
        if (i < -511) i = -511;
        return (uint32_t)i & 0x3FFu;
    };
    return q(x) | (q(y) << 10) | (q(z) << 20);
}

inline uint32_t packColor(float r, float g, float b, float a = 1.f) {
    auto q = [](float v) -> uint32_t {
        if (v < 0) v = 0;
        if (v > 1) v = 1;
        return (uint32_t)std::lround(v * 255.f);
    };
    return q(r) | (q(g) << 8) | (q(b) << 16) | (q(a) << 24);
}

constexpr uint32_t kWhite = 0xFFFFFFFFu;

struct Chunk {
    int material = 0;
    bool points = false;
    std::vector<Vertex> verts;
    std::vector<uint32_t> tris;
    std::vector<uint32_t> lines;
};

struct Material {
    std::string name;
    float kd[4] = {0.8f, 0.8f, 0.8f, 1.f};
    std::string texture;  // file name, or "*N" for embedded texture N
};

struct EmbeddedTexture {
    int width = 0, height = 0;   // 0,0 => compressed (png/jpg bytes)
    std::vector<uint8_t> data;   // compressed bytes or RGBA8 pixels
};

struct Bounds {
    double mn[3] = {1e300, 1e300, 1e300};
    double mx[3] = {-1e300, -1e300, -1e300};
    bool valid() const { return mn[0] <= mx[0]; }
    void add(const float* p) {
        for (int i = 0; i < 3; i++) {
            if (p[i] < mn[i]) mn[i] = p[i];
            if (p[i] > mx[i]) mx[i] = p[i];
        }
    }
    void add(const Bounds& b) {
        if (!b.valid()) return;
        for (int i = 0; i < 3; i++) {
            if (b.mn[i] < mn[i]) mn[i] = b.mn[i];
            if (b.mx[i] > mx[i]) mx[i] = b.mx[i];
        }
    }
};

// Callback for opening sibling files (MTL, textures, .bin). Returns fd or -1.
struct Resolver {
    virtual ~Resolver() = default;
    virtual int open(const std::string& name) = 0;
};

// Thread-safe hand-off between the loader thread and the GL thread.
class Sink {
public:
    std::atomic<bool> cancel{false};
    std::atomic<float> progress{0.f};
    std::atomic<int64_t> vertexCount{0}, triangleCount{0}, pointCount{0};
    std::atomic<int> chunkCount{0};
    std::atomic<int> generation{0};  // increments when bounds change

    void push(std::unique_ptr<Chunk> c) {
        if (!c || (c->verts.empty())) return;
        Bounds b;
        for (auto& v : c->verts) b.add(v.p);
        vertexCount += (int64_t)c->verts.size();
        triangleCount += (int64_t)c->tris.size() / 3;
        if (c->points) pointCount += (int64_t)c->verts.size();
        chunkCount++;
        std::lock_guard<std::mutex> lk(m_);
        bounds_.add(b);
        queue_.push_back(std::move(c));
        generation++;
    }
    std::unique_ptr<Chunk> pop() {
        std::lock_guard<std::mutex> lk(m_);
        if (queue_.empty()) return nullptr;
        auto c = std::move(queue_.front());
        queue_.pop_front();
        return c;
    }
    size_t pending() {
        std::lock_guard<std::mutex> lk(m_);
        return queue_.size();
    }
    Bounds bounds() {
        std::lock_guard<std::mutex> lk(m_);
        return bounds_;
    }
    int addMaterial(const Material& mat) {
        std::lock_guard<std::mutex> lk(m_);
        materials_.push_back(mat);
        return (int)materials_.size() - 1;
    }
    std::vector<Material> materials() {
        std::lock_guard<std::mutex> lk(m_);
        return materials_;
    }
    void setMaterial(int i, const Material& mat) {
        std::lock_guard<std::mutex> lk(m_);
        if (i >= 0 && i < (int)materials_.size()) materials_[i] = mat;
    }
    int materialCount() {
        std::lock_guard<std::mutex> lk(m_);
        return (int)materials_.size();
    }
    std::vector<EmbeddedTexture> embedded;  // written by loader before finish only
    double origin[3] = {0, 0, 0};

    // ---- load report: 0 = info, 1 = warning, 2 = error
    void log(int level, const std::string& text) {
        std::lock_guard<std::mutex> lk(m_);
        if (logs_.size() < 600) logs_.push_back(std::to_string(level) + "|" + text);
    }
    std::vector<std::string> logs() {
        std::lock_guard<std::mutex> lk(m_);
        return logs_;
    }

private:
    std::mutex m_;
    std::deque<std::unique_ptr<Chunk>> queue_;
    std::vector<Material> materials_;
    std::vector<std::string> logs_;
    Bounds bounds_;
};

// Builds indexed chunks with vertex de-duplication (open addressing hash map).
class Builder {
public:
    static constexpr size_t kMaxVerts = 1u << 20;

    Builder(Sink& sink, int material, bool points = false)
        : sink_(sink), material_(material), points_(points) {}
    ~Builder() = default;

    // key: up to three 32-bit ids identifying a unique vertex (e.g. v/vt/vn)
    uint32_t vertex(uint32_t k0, uint32_t k1, uint32_t k2, const Vertex& v, bool hasNormal) {
        ensure();
        if (table_.empty()) rehash(4096);
        uint64_t h = hash(k0, k1, k2);
        size_t mask = table_.size() - 1;
        size_t i = (size_t)h & mask;
        while (true) {
            Slot& s = table_[i];
            if (s.idx == UINT32_MAX) {
                uint32_t idx = (uint32_t)cur_->verts.size();
                s = {k0, k1, k2, idx};
                cur_->verts.push_back(v);
                hasN_.push_back(hasNormal);
                used_++;
                if (used_ * 2 > table_.size()) rehash(table_.size() * 2);
                return idx;
            }
            if (s.k0 == k0 && s.k1 == k1 && s.k2 == k2) return s.idx;
            i = (i + 1) & mask;
        }
    }
    // Vertex without de-duplication.
    uint32_t raw(const Vertex& v, bool hasNormal) {
        ensure();
        cur_->verts.push_back(v);
        hasN_.push_back(hasNormal);
        return (uint32_t)cur_->verts.size() - 1;
    }
    void tri(uint32_t a, uint32_t b, uint32_t c) {
        cur_->tris.push_back(a);
        cur_->tris.push_back(b);
        cur_->tris.push_back(c);
    }
    void line(uint32_t a, uint32_t b) {
        cur_->lines.push_back(a);
        cur_->lines.push_back(b);
    }
    // Call before starting a face of n vertices: flushes if the chunk is full.
    void reserveFace(size_t n) {
        if (cur_ && cur_->verts.size() + n > kMaxVerts) flush();
    }
    void flush();

private:
    struct Slot {
        uint32_t k0, k1, k2, idx;
    };
    static uint64_t hash(uint32_t a, uint32_t b, uint32_t c) {
        uint64_t h = (uint64_t)a * 0x9E3779B97F4A7C15ull;
        h ^= ((uint64_t)b + 0x632BE59BD9B4E019ull) * 0xC2B2AE3D27D4EB4Full;
        h ^= ((uint64_t)c + 0x85EBCA77C2B2AE63ull) * 0x165667B19E3779F9ull;
        h ^= h >> 29;
        return h;
    }
    void ensure() {
        if (!cur_) {
            cur_ = std::make_unique<Chunk>();
            cur_->material = material_;
            cur_->points = points_;
            cur_->verts.reserve(1 << 14);
        }
    }
    void rehash(size_t n) {
        std::vector<Slot> old;
        old.swap(table_);
        table_.assign(n, Slot{0, 0, 0, UINT32_MAX});
        size_t mask = n - 1;
        for (auto& s : old) {
            if (s.idx == UINT32_MAX) continue;
            size_t i = (size_t)hash(s.k0, s.k1, s.k2) & mask;
            while (table_[i].idx != UINT32_MAX) i = (i + 1) & mask;
            table_[i] = s;
        }
    }

    Sink& sink_;
    int material_;
    bool points_;
    std::unique_ptr<Chunk> cur_;
    std::vector<Slot> table_;
    std::vector<bool> hasN_;
    size_t used_ = 0;
};

// Fills in smooth normals for vertices that had none, using area-weighted face normals.
inline void computeMissingNormals(Chunk& c, const std::vector<bool>& hasN) {
    bool any = false;
    for (bool b : hasN) if (!b) { any = true; break; }
    if (!any || c.tris.empty()) return;
    std::vector<float> acc(c.verts.size() * 3, 0.f);
    for (size_t t = 0; t + 2 < c.tris.size(); t += 3) {
        uint32_t i0 = c.tris[t], i1 = c.tris[t + 1], i2 = c.tris[t + 2];
        const float* a = c.verts[i0].p;
        const float* b = c.verts[i1].p;
        const float* d = c.verts[i2].p;
        float e1[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        float e2[3] = {d[0] - a[0], d[1] - a[1], d[2] - a[2]};
        float n[3] = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2],
                      e1[0] * e2[1] - e1[1] * e2[0]};
        for (uint32_t idx : {i0, i1, i2}) {
            acc[idx * 3] += n[0];
            acc[idx * 3 + 1] += n[1];
            acc[idx * 3 + 2] += n[2];
        }
    }
    for (size_t i = 0; i < c.verts.size(); i++) {
        if (i < hasN.size() && hasN[i]) continue;
        c.verts[i].n = packNormal(acc[i * 3], acc[i * 3 + 1], acc[i * 3 + 2]);
    }
}

inline void Builder::flush() {
    if (!cur_) return;
    if (!points_) computeMissingNormals(*cur_, hasN_);
    sink_.push(std::move(cur_));
    cur_.reset();
    hasN_.clear();
    table_.clear();
    table_.shrink_to_fit();
    used_ = 0;
}

}  // namespace vx
