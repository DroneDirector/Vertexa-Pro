#include <unordered_set>
#include <array>
// Cut tool: separates the region inside a lasso into a new part, exactly along the lasso.
//
// The lasso corners are points on the model's surface. Each pair of neighbouring corners,
// together with the eye, defines a plane; every triangle crossing a lasso edge is split along
// that plane, so the cut follows the drawn line exactly whatever the mesh density. Pieces are
// classified inside / outside by projecting them onto the screen. Depending on the mode the
// whole inside region (cut through), only the nearest surface (surface) or the nearest wall's
// outer + inner skin (one wall) becomes the new part. Open cut edges are sealed on both sides.
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <unordered_map>

#include "exporter.h"
#include "renderer.h"

namespace vx {

struct Renderer::CutCam {
    M4 mvp;
    double R[9];  // model-view rotation (row-major, view = R * model + t)
    V3 eye, fwd;
    double W = 1, H = 1, tanHalf = 1, aspect = 1, radius = 1;
    bool ortho = false;
    double hw = 1;  // flat view: half height of the view in model units
    V3 right, up;
    V3 center;
    /** Ray through a screen point (origin, direction) in model space. */
    void ray(double sx, double sy, V3& o, V3& d) const {
        double ndcX = 2.0 * sx / W - 1.0, ndcY = 1.0 - 2.0 * sy / H;
        if (ortho) {
            o = eye + right * (ndcX * hw * aspect) + up * (ndcY * hw);
            d = fwd;
            return;
        }
        o = eye;
        d = (right * (ndcX * tanHalf * aspect) + up * (ndcY * tanHalf) + fwd).norm();
    }
};

namespace {

const double kFovC = 45.0 * M_PI / 180.0;

inline uint64_t mix64(uint64_t h) {
    h ^= h >> 33; h *= 0xff51afd7ed558ccdULL; h ^= h >> 33; h *= 0xc4ceb9fe1a85ec53ULL; h ^= h >> 33;
    return h;
}

struct PosKey {
    uint32_t x, y, z;
    bool operator==(const PosKey& o) const { return x == o.x && y == o.y && z == o.z; }
};
struct PosKeyHash {
    size_t operator()(const PosKey& k) const {
        return (size_t)mix64(((uint64_t)k.x << 32) ^ ((uint64_t)k.y * 0x9E3779B97F4A7C15ULL) ^ k.z);
    }
};
PosKey keyOf(const float* p) {
    PosKey k;
    float q[3] = {p[0] == 0.f ? 0.f : p[0], p[1] == 0.f ? 0.f : p[1], p[2] == 0.f ? 0.f : p[2]};  // -0 == 0
    memcpy(&k.x, &q[0], 4);
    memcpy(&k.y, &q[1], 4);
    memcpy(&k.z, &q[2], 4);
    return k;
}

void unpackN(uint32_t n, double out[3]) {
    for (int i = 0; i < 3; i++) {
        int v = (int)((n >> (i * 10)) & 0x3FF);
        if (v & 0x200) v -= 0x400;
        out[i] = v / 511.0;
    }
}

struct CV {
    Vertex v;
    int idx;  // index in the chunk's original vertices, or -1 for a new vertex
    bool onCut;
};

bool lessPos(const Vertex& a, const Vertex& b) {
    for (int i = 0; i < 3; i++) {
        if (a.p[i] < b.p[i]) return true;
        if (a.p[i] > b.p[i]) return false;
    }
    return false;
}

// Interpolates in a canonical order so the same edge gives a bit-identical point from either side.
CV lerpCV(const CV& a0, const CV& b0, double t) {
    const CV* a = &a0;
    const CV* b = &b0;
    if (lessPos(b0.v, a0.v)) { std::swap(a, b); t = 1 - t; }
    CV r;
    r.idx = -1;
    r.onCut = true;
    for (int i = 0; i < 3; i++) r.v.p[i] = (float)(a->v.p[i] + (b->v.p[i] - a->v.p[i]) * t);
    for (int i = 0; i < 2; i++) r.v.uv[i] = (float)(a->v.uv[i] + (b->v.uv[i] - a->v.uv[i]) * t);
    double na[3], nb[3];
    unpackN(a->v.n, na);
    unpackN(b->v.n, nb);
    r.v.n = packNormal((float)(na[0] + (nb[0] - na[0]) * t), (float)(na[1] + (nb[1] - na[1]) * t),
                       (float)(na[2] + (nb[2] - na[2]) * t));
    uint32_t c = 0;
    for (int k = 0; k < 4; k++) {
        double ca = (a->v.c >> (8 * k)) & 255, cb = (b->v.c >> (8 * k)) & 255;
        c |= ((uint32_t)std::lround(ca + (cb - ca) * t) & 255u) << (8 * k);
    }
    r.v.c = c;
    return r;
}

struct Plane {
    V3 n;
    double d;
    double dist(const float* p) const { return n.x * p[0] + n.y * p[1] + n.z * p[2] + d; }
};

void splitPoly(const std::vector<CV>& poly, const Plane& pl, double eps, std::vector<CV>& front, std::vector<CV>& back) {
    front.clear();
    back.clear();
    size_t n = poly.size();
    std::vector<double> d(n);
    for (size_t i = 0; i < n; i++) {
        d[i] = pl.dist(poly[i].v.p);
        if (std::fabs(d[i]) < eps) d[i] = 0;
    }
    for (size_t i = 0; i < n; i++) {
        const CV& a = poly[i];
        const CV& b = poly[(i + 1) % n];
        double da = d[i], db = d[(i + 1) % n];
        if (da >= 0) front.push_back(a);
        if (da <= 0) back.push_back(a);
        if (da == 0) {
            front.back().onCut = true;
            back.back().onCut = true;
        }
        if ((da > 0 && db < 0) || (da < 0 && db > 0)) {
            CV m = lerpCV(a, b, da / (da - db));
            front.push_back(m);
            back.push_back(m);
        }
    }
    if (front.size() < 3) front.clear();
    if (back.size() < 3) back.clear();
}

bool pointInPoly(double x, double y, const std::vector<double>& poly) {
    bool in = false;
    size_t n = poly.size() / 2;
    for (size_t i = 0, j = n - 1; i < n; j = i++) {
        double xi = poly[i * 2], yi = poly[i * 2 + 1], xj = poly[j * 2], yj = poly[j * 2 + 1];
        if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi + 1e-300) + xi)) in = !in;
    }
    return in;
}

// Ear clipping of a simple 2D polygon; returns triangles as indices into pts.
std::vector<int> earClip(const std::vector<double>& xy) {
    int n = (int)xy.size() / 2;
    std::vector<int> out, idx(n);
    if (n < 3) return out;
    double area = 0;
    for (int i = 0; i < n; i++) {
        int j = (i + 1) % n;
        area += xy[i * 2] * xy[j * 2 + 1] - xy[j * 2] * xy[i * 2 + 1];
    }
    for (int i = 0; i < n; i++) idx[i] = area >= 0 ? i : n - 1 - i;
    auto X = [&](int i) { return xy[i * 2]; };
    auto Y = [&](int i) { return xy[i * 2 + 1]; };
    auto cross = [&](int a, int b, int c) { return (X(b) - X(a)) * (Y(c) - Y(a)) - (Y(b) - Y(a)) * (X(c) - X(a)); };
    int guard = 0;
    while (idx.size() > 3 && guard < n * n) {
        guard++;
        bool clipped = false;
        int m = (int)idx.size();
        for (int i = 0; i < m; i++) {
            int a = idx[(i + m - 1) % m], b = idx[i], c = idx[(i + 1) % m];
            if (cross(a, b, c) <= 1e-14) continue;  // reflex or degenerate
            bool inside = false;
            for (int k = 0; k < m && !inside; k++) {
                int q = idx[k];
                if (q == a || q == b || q == c) continue;
                if (cross(a, b, q) >= 0 && cross(b, c, q) >= 0 && cross(c, a, q) >= 0) inside = true;
            }
            if (inside) continue;
            out.insert(out.end(), {a, b, c});
            idx.erase(idx.begin() + i);
            clipped = true;
            break;
        }
        if (!clipped) {
            // numerically stuck: fan the rest
            for (size_t k = 1; k + 1 < idx.size(); k++) out.insert(out.end(), {idx[0], idx[k], idx[k + 1]});
            return out;
        }
    }
    if (idx.size() == 3) out.insert(out.end(), {idx[0], idx[1], idx[2]});
    return out;
}

// Turns lists of indexed triangles over a shared vertex array into a compact chunk.
std::unique_ptr<Chunk> buildChunk(const std::vector<Vertex>& verts, const std::vector<uint32_t>& tris, int material, int part) {
    if (tris.empty()) return nullptr;
    auto c = std::make_unique<Chunk>();
    c->material = material;
    c->part = part;
    std::unordered_map<uint32_t, uint32_t> remap;
    remap.reserve(tris.size());
    c->tris.reserve(tris.size());
    for (uint32_t i : tris) {
        auto it = remap.find(i);
        uint32_t ni;
        if (it == remap.end()) {
            ni = (uint32_t)c->verts.size();
            remap.emplace(i, ni);
            c->verts.push_back(verts[i]);
        } else ni = it->second;
        c->tris.push_back(ni);
    }
    c->lines.reserve(c->tris.size() * 2);
    for (size_t t = 0; t + 2 < c->tris.size(); t += 3) {
        uint32_t a = c->tris[t], b = c->tris[t + 1], d = c->tris[t + 2];
        c->lines.insert(c->lines.end(), {a, b, b, d, d, a});
    }
    return c;
}

}  // namespace

bool Renderer::cutCamera(CutCam& cc) {
    Frame f;
    {
        std::lock_guard<std::mutex> l(mu_);
        f = frameState();
    }
    const Bounds& rb = f.rb;
    if (!rb.valid()) return false;
    V3 center((rb.mn[0] + rb.mx[0]) / 2, (rb.mn[1] + rb.mx[1]) / 2, (rb.mn[2] + rb.mx[2]) / 2);
    double radius = V3(rb.mx[0] - rb.mn[0], rb.mx[1] - rb.mn[1], rb.mx[2] - rb.mn[2]).len() / 2;
    if (radius <= 0) radius = 1;
    const Cam& cam = f.cam;
    V3 dir(std::cos(cam.pitch) * std::sin(cam.yaw), std::sin(cam.pitch), std::cos(cam.pitch) * std::cos(cam.yaw));
    V3 eyeW = cam.target + dir * cam.dist;
    M4 view = lookAt(eyeW, cam.target, V3(0, 1, 0));
    double dc = (eyeW - center).len();
    double far = dc + radius * 8.4;
    double nearP = std::max(dc - radius * 1.05, far * 2e-5);
    if (f.ortho) nearP = std::max(dc - radius * 1.5, far * 1e-4);
    M4 proj = projection(f, w_, h_, nearP, far);
    M4 mv = view * (f.upZ ? rotX(-M_PI / 2) : M4::identity());
    cc.mvp = proj * mv;
    // column-major mv: R[row][col] = m[col*4+row]
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++) cc.R[r * 3 + c] = mv.m[c * 4 + r];
    double t[3] = {mv.m[12], mv.m[13], mv.m[14]};
    // eye in model space = -R^T t ; forward = R^T (0,0,-1)
    cc.eye = V3(-(cc.R[0] * t[0] + cc.R[3] * t[1] + cc.R[6] * t[2]), -(cc.R[1] * t[0] + cc.R[4] * t[1] + cc.R[7] * t[2]),
                -(cc.R[2] * t[0] + cc.R[5] * t[1] + cc.R[8] * t[2]));
    cc.fwd = V3(-cc.R[6], -cc.R[7], -cc.R[8]).norm();
    cc.W = w_;
    cc.H = h_;
    cc.tanHalf = std::tan(kFovC / 2);
    cc.aspect = (double)w_ / (double)h_;
    cc.radius = radius;
    cc.ortho = f.ortho;
    cc.hw = cam.dist * cc.tanHalf;
    cc.right = V3(cc.R[0], cc.R[1], cc.R[2]);
    cc.up = V3(cc.R[3], cc.R[4], cc.R[5]);
    {
        std::lock_guard<std::mutex> l(mu_);
        const Bounds& b = bounds_;
        cc.center = b.valid() ? V3((b.mn[0] + b.mx[0]) / 2, (b.mn[1] + b.mx[1]) / 2, (b.mn[2] + b.mx[2]) / 2) : V3();
    }
    return true;
}

double Renderer::pixelsPerUnit() {
    CutCam cc;
    if (!cutCamera(cc)) return 1;
    if (cc.ortho) return cc.H / (2 * cc.hw);
    double depth = std::max(1e-9, (cc.center - cc.eye).dot(cc.fwd));
    return cc.H / (2 * depth * cc.tanHalf);
}

static bool projectCC(const Renderer::CutCam& cc, const float* p, double& sx, double& sy) {
    const double* m = cc.mvp.m;
    double x = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12];
    double y = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13];
    double w = m[3] * p[0] + m[7] * p[1] + m[11] * p[2] + m[15];
    if (w <= 1e-12) return false;
    sx = (x / w * 0.5 + 0.5) * cc.W;
    sy = (1.0 - (y / w * 0.5 + 0.5)) * cc.H;
    return true;
}

void Renderer::project(const float* in, int n, float* out) {
    CutCam cc;
    if (!cutCamera(cc)) {
        for (int i = 0; i < n * 2; i++) out[i] = NAN;
        return;
    }
    for (int i = 0; i < n; i++) {
        double sx, sy;
        if (projectCC(cc, in + i * 3, sx, sy)) { out[i * 2] = (float)sx; out[i * 2 + 1] = (float)sy; }
        else { out[i * 2] = NAN; out[i * 2 + 1] = NAN; }
    }
}

bool Renderer::raycast(float sx, float sy, float outP[3]) {
    return snapPoint(sx, sy, 0.f, false, false, outP);
}

void Renderer::unproject(const float* xy, int n, float* out) {
    CutCam cc;
    if (!cutCamera(cc)) {
        for (int i = 0; i < n * 3; i++) out[i] = 0;
        return;
    }
    for (int i = 0; i < n; i++) {
        V3 o, d;
        cc.ray(xy[i * 2], xy[i * 2 + 1], o, d);
        double t = (cc.center - o).dot(cc.fwd) / std::max(1e-9, d.dot(cc.fwd));
        V3 p = o + d * t;
        out[i * 3] = (float)p.x;
        out[i * 3 + 1] = (float)p.y;
        out[i * 3 + 2] = (float)p.z;
    }
}

bool Renderer::snapPoint(float sx, float sy, float snapPx, bool corners, bool edges, float outP[3]) {
    CutCam cc;
    if (!cutCamera(cc)) return false;
    V3 o, d;
    cc.ray(sx, sy, o, d);
    std::vector<uint8_t> hidden;
    {
        std::lock_guard<std::mutex> l(mu_);
        hidden = hidden_;
    }
    std::lock_guard<std::mutex> dl(dataMu_);
    // distance along a ray to the nearest visible triangle (1e300 when nothing is hit)
    auto rayNearest = [&](const V3& ro, const V3& rd) {
        double bt = 1e300;
        for (auto& cp : cpu_) {
            const Chunk& c = *cp;
            if (c.points) continue;
            if (c.part >= 0 && c.part < (int)hidden.size() && hidden[c.part]) continue;
            for (size_t t = 0; t + 2 < c.tris.size(); t += 3) {
                const float* a = c.verts[c.tris[t]].p;
                const float* b = c.verts[c.tris[t + 1]].p;
                const float* e = c.verts[c.tris[t + 2]].p;
                V3 A(a[0], a[1], a[2]);
                V3 e1 = V3(b[0], b[1], b[2]) - A, e2 = V3(e[0], e[1], e[2]) - A;
                V3 pp = rd.cross(e2);
                double det = e1.dot(pp);
                if (std::fabs(det) < 1e-20) continue;
                double inv = 1.0 / det;
                V3 sv = ro - A;
                double u = sv.dot(pp) * inv;
                if (u < 0 || u > 1) continue;
                V3 q = sv.cross(e1);
                double v = rd.dot(q) * inv;
                if (v < 0 || u + v > 1) continue;
                double tt = e2.dot(q) * inv;
                if (tt > 0 && tt < bt) bt = tt;
            }
        }
        return bt;
    };
    double best = 1e300;
    V3 hitTri[3];
    for (auto& cp : cpu_) {
        const Chunk& c = *cp;
        if (c.points) continue;
        if (c.part >= 0 && c.part < (int)hidden.size() && hidden[c.part]) continue;
        for (size_t t = 0; t + 2 < c.tris.size(); t += 3) {
            const float* a = c.verts[c.tris[t]].p;
            const float* b = c.verts[c.tris[t + 1]].p;
            const float* e = c.verts[c.tris[t + 2]].p;
            V3 A(a[0], a[1], a[2]);
            V3 e1 = V3(b[0], b[1], b[2]) - A, e2 = V3(e[0], e[1], e[2]) - A;
            V3 p = d.cross(e2);
            double det = e1.dot(p);
            if (std::fabs(det) < 1e-20) continue;
            double inv = 1.0 / det;
            V3 s = o - A;
            double u = s.dot(p) * inv;
            if (u < 0 || u > 1) continue;
            V3 q = s.cross(e1);
            double v = d.dot(q) * inv;
            if (v < 0 || u + v > 1) continue;
            double tt = e2.dot(q) * inv;
            if (tt > 0 && tt < best) {
                best = tt;
                hitTri[0] = V3(a[0], a[1], a[2]);
                hitTri[1] = V3(b[0], b[1], b[2]);
                hitTri[2] = V3(e[0], e[1], e[2]);
            }
        }
    }
    if (best >= 1e299) return false;
    V3 hit = o + d * best;
    outP[0] = (float)hit.x;
    outP[1] = (float)hit.y;
    outP[2] = (float)hit.z;
    if (snapPx <= 0 || (!corners && !edges)) return true;

    double hitDepth = (hit - o).dot(cc.fwd);
    double tol = cc.radius * 0.03;
    double bestPx = snapPx;
    V3 snapped = hit;
    bool found = false;
    auto screenDist = [&](const V3& p, double& px) {
        float f[3] = {(float)p.x, (float)p.y, (float)p.z};
        double qx, qy;
        if (!projectCC(cc, f, qx, qy)) return false;
        px = std::hypot(qx - sx, qy - sy);
        return true;
    };
    if (corners) {
        // nearest mesh corner on the surface you're looking at: facing the camera, close in depth to the hit,
        // and not hidden behind anything (checked with a ray) — never the skin underneath
        auto unpackN = [](uint32_t n) {
            auto q = [](uint32_t v) { int i = (int)(v & 0x3FF); if (i & 0x200) i -= 0x400; return i / 511.0; };
            return V3(q(n), q(n >> 10), q(n >> 20));
        };
        struct CandV { double px; V3 p; };
        std::vector<CandV> cands;
        double ctol = cc.radius * 0.012;
        for (auto& cp : cpu_) {
            const Chunk& c = *cp;
            if (c.points) continue;
            if (c.part >= 0 && c.part < (int)hidden.size() && hidden[c.part]) continue;
            for (auto& v : c.verts) {
                V3 p(v.p[0], v.p[1], v.p[2]);
                if (std::fabs((p - o).dot(cc.fwd) - hitDepth) > ctol) continue;
                V3 view = cc.ortho ? cc.fwd : (p - cc.eye).norm();
                if (unpackN(v.n).dot(view) > 0.05) continue;  // faces away: the far side
                double px;
                if (screenDist(p, px) && px < bestPx) cands.push_back({px, p});
            }
        }
        std::sort(cands.begin(), cands.end(), [](const CandV& a, const CandV& b) { return a.px < b.px; });
        for (size_t k = 0; k < cands.size() && k < 4 && !found; k++) {
            float f3[3] = {(float)cands[k].p.x, (float)cands[k].p.y, (float)cands[k].p.z};
            double qx, qy;
            if (!projectCC(cc, f3, qx, qy)) continue;
            V3 o2, d2;
            cc.ray(qx, qy, o2, d2);
            double t2 = rayNearest(o2, d2);
            double want = (cands[k].p - o2).dot(d2);
            if (t2 < 1e299 && std::fabs(t2 - want) > ctol * 0.5) continue;  // something is in front of it
            snapped = cands[k].p;
            bestPx = cands[k].px;
            found = true;
        }
    }
    if (!found && edges) {
        // nearest point on the edges of the triangle that was hit
        for (int k = 0; k < 3; k++) {
            V3 a = hitTri[k], b = hitTri[(k + 1) % 3];
            V3 ab = b - a;
            double L2 = ab.dot(ab);
            double t = L2 > 0 ? std::clamp((hit - a).dot(ab) / L2, 0.0, 1.0) : 0.0;
            V3 p = a + ab * t;
            double px;
            if (screenDist(p, px) && px < bestPx) { bestPx = px; snapped = p; found = true; }
        }
    }
    outP[0] = (float)snapped.x;
    outP[1] = (float)snapped.y;
    outP[2] = (float)snapped.z;
    return true;
}

void Renderer::recomputeParts(Sink* sink) {
    if (!sink) return;
    std::vector<PartInfo> stats(sink->parts().size());
    for (auto& cp : cpu_) {
        const Chunk& c = *cp;
        if (c.part < 0) continue;
        if (c.part >= (int)stats.size()) stats.resize(c.part + 1);
        PartInfo& pi = stats[c.part];
        pi.triangles += (int64_t)c.tris.size() / 3;
        pi.vertices += (int64_t)c.verts.size();
        for (auto& v : c.verts) pi.bounds.add(v.p);
    }
    sink->setPartStats(stats);
}

Renderer::CutResult Renderer::cut(const CutRequest& req, Sink* sink) {
    CutResult res;
    if (!sink) { res.error = "No model loaded."; return res; }
    size_t n = req.points.size() / 3;
    if (n < 3) { res.error = "Place at least 3 points."; return res; }
    CutCam cc;
    if (!cutCamera(cc)) { res.error = "No model loaded."; return res; }
    CutRequest mreq;
    const CutRequest* rq = &req;
    if (req.mirrorAxis >= 0 && req.mirrorAxis < 3) {
        // The mirrored cut is the same cut seen through a mirror: reflect the camera and the outline.
        Bounds b;
        {
            std::lock_guard<std::mutex> l(mu_);
            b = bounds_;
        }
        if (!b.valid()) { res.error = "No model loaded."; return res; }
        int ax = req.mirrorAxis;
        double c = (b.mn[ax] + b.mx[ax]) / 2;
        M4 m = M4::identity();
        m.m[ax * 5] = -1;
        m.m[12 + ax] = 2 * c;
        cc.mvp = cc.mvp * m;
        double* e = ax == 0 ? &cc.eye.x : ax == 1 ? &cc.eye.y : &cc.eye.z;
        *e = 2 * c - *e;
        double* fw = ax == 0 ? &cc.fwd.x : ax == 1 ? &cc.fwd.y : &cc.fwd.z;
        *fw = -*fw;
        mreq = req;
        for (size_t i = 0; i < n; i++) mreq.points[i * 3 + ax] = (float)(2 * c - mreq.points[i * 3 + ax]);
        for (size_t k = 0; k + 4 <= mreq.erase.size(); k += 4) mreq.erase[k + ax] = (float)(2 * c - mreq.erase[k + ax]);
        rq = &mreq;
    }
    const CutRequest& R = *rq;

    // lasso on screen + cutting planes through the eye
    std::vector<double> lasso(n * 2);
    for (size_t i = 0; i < n; i++) {
        double sx, sy;
        if (!projectCC(cc, &R.points[i * 3], sx, sy)) {
            res.error = "Some points are behind the camera. Turn the view so every point is visible, then try again.";
            return res;
        }
        lasso[i * 2] = sx;
        lasso[i * 2 + 1] = sy;
    }
    std::vector<Plane> planes(n);
    std::vector<double> segBox(n * 4);
    double lminx = 1e300, lminy = 1e300, lmaxx = -1e300, lmaxy = -1e300;
    std::vector<double> cum(n + 1, 0.0);
    for (size_t i = 0; i < n; i++) {
        size_t j = (i + 1) % n;
        V3 a(R.points[i * 3] - cc.eye.x, R.points[i * 3 + 1] - cc.eye.y, R.points[i * 3 + 2] - cc.eye.z);
        V3 b(R.points[j * 3] - cc.eye.x, R.points[j * 3 + 1] - cc.eye.y, R.points[j * 3 + 2] - cc.eye.z);
        V3 nn;
        if (cc.ortho) {
            // flat view: walls run straight along the view direction
            V3 A(R.points[i * 3], R.points[i * 3 + 1], R.points[i * 3 + 2]);
            V3 B(R.points[j * 3], R.points[j * 3 + 1], R.points[j * 3 + 2]);
            nn = (B - A).cross(cc.fwd).norm();
            planes[i].n = nn;
            planes[i].d = -nn.dot(A);
        } else {
            nn = a.cross(b).norm();
            planes[i].n = nn;
            planes[i].d = -nn.dot(cc.eye);
        }
        segBox[i * 4] = std::min(lasso[i * 2], lasso[j * 2]);
        segBox[i * 4 + 1] = std::min(lasso[i * 2 + 1], lasso[j * 2 + 1]);
        segBox[i * 4 + 2] = std::max(lasso[i * 2], lasso[j * 2]);
        segBox[i * 4 + 3] = std::max(lasso[i * 2 + 1], lasso[j * 2 + 1]);
        lminx = std::min(lminx, lasso[i * 2]); lmaxx = std::max(lmaxx, lasso[i * 2]);
        lminy = std::min(lminy, lasso[i * 2 + 1]); lmaxy = std::max(lmaxy, lasso[i * 2 + 1]);
        cum[i + 1] = cum[i] + std::hypot(lasso[j * 2] - lasso[i * 2], lasso[j * 2 + 1] - lasso[i * 2 + 1]);
    }
    const double perimeter = std::max(cum[n], 1e-9);
    const double eps = cc.radius * 1e-7;
    // depth window along the view (front 0 → back 1 of the model)
    bool limitDepth = req.depthLo > 0.001f || req.depthHi < 0.999f;
    double dLo = -1e300, dHi = 1e300;
    if (limitDepth) {
        Bounds b;
        {
            std::lock_guard<std::mutex> l(mu_);
            b = bounds_;
        }
        double mn = 1e300, mx = -1e300;
        for (int k = 0; k < 8; k++) {
            V3 c(k & 1 ? b.mx[0] : b.mn[0], k & 2 ? b.mx[1] : b.mn[1], k & 4 ? b.mx[2] : b.mn[2]);
            double d = (c - cc.eye).dot(cc.fwd);
            mn = std::min(mn, d);
            mx = std::max(mx, d);
        }
        dLo = mn + (mx - mn) * req.depthLo;
        dHi = mn + (mx - mn) * req.depthHi;
    }
    auto erased = [&](const float* p) {
        for (size_t k = 0; k + 4 <= R.erase.size(); k += 4) {
            double dx = p[0] - R.erase[k], dy = p[1] - R.erase[k + 1], dz = p[2] - R.erase[k + 2];
            if (dx * dx + dy * dy + dz * dz <= (double)R.erase[k + 3] * R.erase[k + 3]) return true;
        }
        return false;
    };
    auto inDepth = [&](const float* p) {
        if (erased(p)) return false;
        if (R.slabAxis >= 0 && R.slabAxis < 3 && (p[R.slabAxis] < R.slabLo || p[R.slabAxis] > R.slabHi)) return false;
        if (!limitDepth) return true;
        double d = (V3(p[0], p[1], p[2]) - cc.eye).dot(cc.fwd);
        return d >= dLo && d <= dHi;
    };

    std::vector<uint8_t> hidden;
    {
        std::lock_guard<std::mutex> l(mu_);
        hidden = hidden_;
    }
    int newPart = req.previewOnly ? -1 : sink->addPart(req.name.empty() ? "Cut part" : req.name);

    std::lock_guard<std::mutex> dl(dataMu_);
    if (!keepCpu_ || cpu_.empty()) {
        res.error = "This model is too large to edit on this device.";
        return res;
    }

    struct Work {
        std::shared_ptr<Chunk> src;
        std::vector<Vertex> verts;      // original + new vertices
        std::vector<uint8_t> onCut;
        std::vector<uint32_t> keep, cut;
    };
    std::vector<Work> works;
    std::vector<std::shared_ptr<Chunk>> untouched;

    std::vector<CV> poly, front, back;
    std::vector<std::vector<CV>> polys, next;
    for (auto& cp : cpu_) {
        const Chunk& c = *cp;
        bool skip = c.points || c.tris.empty() || (c.part >= 0 && c.part < (int)hidden.size() && hidden[c.part]) ||
                    (req.onlyPart >= 0 && c.part != req.onlyPart);
        if (!skip) {
            // quick reject: chunk entirely outside the lasso box
            double cminx = 1e300, cminy = 1e300, cmaxx = -1e300, cmaxy = -1e300;
            bool behind = false;
            for (auto& v : c.verts) {
                double sx, sy;
                if (!projectCC(cc, v.p, sx, sy)) { behind = true; break; }
                cminx = std::min(cminx, sx); cmaxx = std::max(cmaxx, sx);
                cminy = std::min(cminy, sy); cmaxy = std::max(cmaxy, sy);
            }
            if (!behind && (cmaxx < lminx || cminx > lmaxx || cmaxy < lminy || cminy > lmaxy)) skip = true;
        }
        if (skip) { untouched.push_back(cp); continue; }

        Work w;
        w.src = cp;
        w.verts = c.verts;
        w.onCut.assign(c.verts.size(), 0);
        std::vector<double> sxy(c.verts.size() * 2);
        std::vector<uint8_t> ok(c.verts.size());
        for (size_t i = 0; i < c.verts.size(); i++) ok[i] = projectCC(cc, c.verts[i].p, sxy[i * 2], sxy[i * 2 + 1]);
        std::unordered_map<uint64_t, uint32_t> newIdx;
        auto addVert = [&](const CV& v) -> uint32_t {
            if (v.idx >= 0) {
                if (v.onCut) w.onCut[v.idx] = 1;
                return (uint32_t)v.idx;
            }
            uint64_t h = 1469598103934665603ULL;
            const unsigned char* b = (const unsigned char*)&v.v;
            for (size_t k = 0; k < sizeof(Vertex); k++) { h ^= b[k]; h *= 1099511628211ULL; }
            auto it = newIdx.find(h);
            if (it != newIdx.end()) return it->second;
            uint32_t id = (uint32_t)w.verts.size();
            w.verts.push_back(v.v);
            w.onCut.push_back(1);
            newIdx.emplace(h, id);
            return id;
        };
        for (size_t t = 0; t + 2 < c.tris.size(); t += 3) {
            uint32_t ia = c.tris[t], ib = c.tris[t + 1], ic = c.tris[t + 2];
            if (!ok[ia] || !ok[ib] || !ok[ic]) { w.keep.insert(w.keep.end(), {ia, ib, ic}); continue; }
            double tminx = std::min({sxy[ia * 2], sxy[ib * 2], sxy[ic * 2]});
            double tmaxx = std::max({sxy[ia * 2], sxy[ib * 2], sxy[ic * 2]});
            double tminy = std::min({sxy[ia * 2 + 1], sxy[ib * 2 + 1], sxy[ic * 2 + 1]});
            double tmaxy = std::max({sxy[ia * 2 + 1], sxy[ib * 2 + 1], sxy[ic * 2 + 1]});
            if (tmaxx < lminx || tminx > lmaxx || tmaxy < lminy || tminy > lmaxy) {
                w.keep.insert(w.keep.end(), {ia, ib, ic});
                continue;
            }
            polys.clear();
            poly = {CV{c.verts[ia], (int)ia, false}, CV{c.verts[ib], (int)ib, false}, CV{c.verts[ic], (int)ic, false}};
            polys.push_back(poly);
            for (size_t e = 0; e < n; e++) {
                if (segBox[e * 4 + 2] < tminx || segBox[e * 4] > tmaxx || segBox[e * 4 + 3] < tminy || segBox[e * 4 + 1] > tmaxy) continue;
                next.clear();
                for (auto& pp : polys) {
                    splitPoly(pp, planes[e], eps, front, back);
                    if (!front.empty()) next.push_back(front);
                    if (!back.empty()) next.push_back(back);
                }
                polys.swap(next);
            }
            for (auto& pp : polys) {
                double cx = 0, cy = 0, cz = 0;
                for (auto& v : pp) { cx += v.v.p[0]; cy += v.v.p[1]; cz += v.v.p[2]; }
                float cen[3] = {(float)(cx / pp.size()), (float)(cy / pp.size()), (float)(cz / pp.size())};
                double px, py;
                bool inside = projectCC(cc, cen, px, py) && pointInPoly(px, py, lasso) && inDepth(cen);
                std::vector<uint32_t>& dst = inside ? w.cut : w.keep;
                uint32_t i0 = addVert(pp[0]);
                for (size_t k = 1; k + 1 < pp.size(); k++) {
                    uint32_t i1 = addVert(pp[k]), i2 = addVert(pp[k + 1]);
                    dst.insert(dst.end(), {i0, i1, i2});
                }
            }
        }
        if (w.cut.empty()) { untouched.push_back(cp); continue; }
        works.push_back(std::move(w));
    }
    if (works.empty()) {
        res.error = "Nothing of the model is inside the outline.";
        return res;
    }

    // ---- welded positions across all touched chunks
    std::unordered_map<PosKey, uint32_t, PosKeyHash> posId;
    std::vector<V3> posOf;
    auto pid = [&](const float* p) {
        auto k = keyOf(p);
        auto it = posId.find(k);
        if (it != posId.end()) return it->second;
        uint32_t id = (uint32_t)posOf.size();
        posId.emplace(k, id);
        posOf.emplace_back(p[0], p[1], p[2]);
        return id;
    };

    // ---- surface / one wall: keep only the nearest connected layer(s) inside the outline
    if (req.mode != 0) {
        struct T { uint32_t w, t; };
        std::vector<T> tris;
        for (uint32_t wi = 0; wi < works.size(); wi++)
            for (uint32_t t = 0; t + 2 < works[wi].cut.size(); t += 3) tris.push_back({wi, t});
        std::vector<uint32_t> parent(tris.size());
        for (size_t i = 0; i < parent.size(); i++) parent[i] = (uint32_t)i;
        auto find = [&](uint32_t x) {
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
            return x;
        };
        std::unordered_map<uint32_t, uint32_t> firstTri;  // position id -> a triangle using it
        for (uint32_t i = 0; i < tris.size(); i++) {
            const Work& w = works[tris[i].w];
            for (int k = 0; k < 3; k++) {
                uint32_t p = pid(w.verts[w.cut[tris[i].t + k]].p);
                auto it = firstTri.find(p);
                if (it == firstTri.end()) firstTri.emplace(p, i);
                else {
                    uint32_t a = find(it->second), b = find(i);
                    if (a != b) parent[b] = a;
                }
            }
        }
        struct Comp { double sum = 0, mn = 1e300, mx = -1e300; int64_t count = 0; };
        std::unordered_map<uint32_t, Comp> comp;
        for (uint32_t i = 0; i < tris.size(); i++) {
            const Work& w = works[tris[i].w];
            auto& e = comp[find(i)];
            for (int k = 0; k < 3; k++) {
                const float* p = w.verts[w.cut[tris[i].t + k]].p;
                double dd = (V3(p[0], p[1], p[2]) - cc.eye).dot(cc.fwd);
                e.sum += dd / 3;
                e.mn = std::min(e.mn, dd);
                e.mx = std::max(e.mx, dd);
            }
            e.count++;
        }
        struct Ord { double mean, mn, mx; uint32_t root; };
        std::vector<Ord> order;
        for (auto& kv : comp) {
            if (kv.second.count < 2 && comp.size() > 1) continue;  // ignore specks
            order.push_back({kv.second.sum / kv.second.count, kv.second.mn, kv.second.mx, kv.first});
        }
        std::sort(order.begin(), order.end(), [&](const Ord& a, const Ord& b) {
            return req.mode == 3 ? a.mn < b.mn : a.mean < b.mean;
        });
        std::unordered_map<uint32_t, bool> chosen;
        if (!order.empty()) chosen[order[0].root] = true;
        if (req.mode == 2 && order.size() > 1 && order[1].mean - order[0].mean < cc.radius * 0.12)
            chosen[order[1].root] = true;
        if (req.mode == 3) {
            // the first object: every layer that touches the nearest one in depth (top + bottom skin of a wing),
            // stopping at the first clear gap (e.g. the other wing or the fuselage behind)
            double reach = order.empty() ? 0 : order[0].mx;
            for (size_t k = 1; k < order.size(); k++) {
                if (order[k].mn - reach > cc.radius * 0.04) break;
                chosen[order[k].root] = true;
                reach = std::max(reach, order[k].mx);
            }
        }
        std::vector<std::vector<uint32_t>> newCut(works.size());
        for (uint32_t i = 0; i < tris.size(); i++) {
            Work& w = works[tris[i].w];
            const uint32_t* tr = &w.cut[tris[i].t];
            if (chosen.count(find(i))) newCut[tris[i].w].insert(newCut[tris[i].w].end(), tr, tr + 3);
            else w.keep.insert(w.keep.end(), tr, tr + 3);
        }
        for (size_t wi = 0; wi < works.size(); wi++) works[wi].cut.swap(newCut[wi]);
    }

    if (req.previewOnly) {
        // the exact pieces, split along the outline, shown in orange; nothing is changed
        std::vector<Vertex> pv;
        for (auto& w : works) for (uint32_t i : w.cut) pv.push_back(w.verts[i]);
        res.triangles = (int64_t)pv.size() / 3;
        pmVerts_.swap(pv);
        pmDirty_ = true;
        preview_.clear();
        previewDirty_ = true;
        res.part = 0;
        return res;
    }

    // ---- seal: find open cut edges of the new part and cap them
    std::unordered_map<uint64_t, int> edgeCount;
    std::unordered_map<uint64_t, uint8_t> edgeOnCut;
    for (auto& w : works) {
        for (size_t t = 0; t + 2 < w.cut.size(); t += 3) {
            for (int k = 0; k < 3; k++) {
                uint32_t a = w.cut[t + k], b = w.cut[t + (k + 1) % 3];
                uint32_t pa = pid(w.verts[a].p), pb = pid(w.verts[b].p);
                if (pa == pb) continue;
                uint64_t key = pa < pb ? ((uint64_t)pa << 32 | pb) : ((uint64_t)pb << 32 | pa);
                edgeCount[key]++;
                if (w.onCut[a] && w.onCut[b]) edgeOnCut[key] = 1;
            }
        }
    }
    std::unordered_map<uint32_t, std::vector<uint32_t>> adj;
    for (auto& kv : edgeCount) {
        if (kv.second != 1 || !edgeOnCut.count(kv.first)) continue;
        uint32_t a = (uint32_t)(kv.first >> 32), b = (uint32_t)(kv.first & 0xFFFFFFFF);
        adj[a].push_back(b);
        adj[b].push_back(a);
    }
    std::vector<std::vector<uint32_t>> loops;
    {
        std::unordered_map<uint64_t, bool> used;
        auto ek = [](uint32_t a, uint32_t b) { return a < b ? ((uint64_t)a << 32 | b) : ((uint64_t)b << 32 | a); };
        for (auto& kv : adj) {
            for (uint32_t nb : kv.second) {
                if (used[ek(kv.first, nb)]) continue;
                std::vector<uint32_t> loop{kv.first};
                uint32_t prev = kv.first, cur = nb;
                used[ek(prev, cur)] = true;
                int guard = 0;
                while (cur != kv.first && guard++ < 2000000) {
                    loop.push_back(cur);
                    uint32_t nxt = UINT32_MAX;
                    for (uint32_t c2 : adj[cur]) {
                        if (!used[ek(cur, c2)]) { nxt = c2; break; }
                    }
                    if (nxt == UINT32_MAX) break;
                    used[ek(cur, nxt)] = true;
                    prev = cur;
                    cur = nxt;
                }
                if (cur == kv.first && loop.size() >= 3) loops.push_back(std::move(loop));
            }
        }
    }

    // loop vertices in (position along the outline, depth) space
    auto uvOf = [&](const V3& p, double& u, double& dep) {
        float fp[3] = {(float)p.x, (float)p.y, (float)p.z};
        double sx = 0, sy = 0;
        projectCC(cc, fp, sx, sy);
        double best = 1e300;
        u = 0;
        for (size_t i = 0; i < n; i++) {
            size_t j = (i + 1) % n;
            double ax = lasso[i * 2], ay = lasso[i * 2 + 1], bx = lasso[j * 2] - ax, by = lasso[j * 2 + 1] - ay;
            double L2 = bx * bx + by * by;
            double t = L2 > 0 ? std::clamp(((sx - ax) * bx + (sy - ay) * by) / L2, 0.0, 1.0) : 0.0;
            double dx = sx - (ax + bx * t), dy = sy - (ay + by * t);
            double d2 = dx * dx + dy * dy;
            if (d2 < best) { best = d2; u = cum[i] + t * (cum[i + 1] - cum[i]); }
        }
        dep = (p - cc.eye).dot(cc.fwd);
    };
    std::vector<std::array<V3, 3>> caps;
    struct LoopInfo { std::vector<double> u, d; double meanD; bool wrapped; };
    std::vector<LoopInfo> infos(loops.size());
    double dmin = 1e300, dmax = -1e300;
    for (size_t li = 0; li < loops.size(); li++) {
        LoopInfo& L = infos[li];
        double wind = 0, dsum = 0;
        for (size_t k = 0; k < loops[li].size(); k++) {
            double u, d;
            uvOf(posOf[loops[li][k]], u, d);
            L.u.push_back(u);
            L.d.push_back(d);
            dsum += d;
            dmin = std::min(dmin, d);
            dmax = std::max(dmax, d);
            if (k > 0) {
                double du = u - L.u[k - 1];
                if (du > perimeter / 2) du -= perimeter;
                if (du < -perimeter / 2) du += perimeter;
                wind += du;
            }
        }
        double du = L.u[0] - L.u.back();
        if (du > perimeter / 2) du -= perimeter;
        if (du < -perimeter / 2) du += perimeter;
        wind += du;
        L.meanD = dsum / loops[li].size();
        L.wrapped = std::fabs(wind) > perimeter * 0.5;
        if (wind < 0) {  // make every loop run forward along the outline
            std::reverse(loops[li].begin(), loops[li].end());
            std::reverse(L.u.begin(), L.u.end());
            std::reverse(L.d.begin(), L.d.end());
        }
    }
    double dscale = std::max(dmax - dmin, 1e-9);
    std::vector<size_t> wrapped;
    for (size_t li = 0; li < loops.size(); li++) {
        if (loops[li].size() > 20000) continue;
        if (infos[li].wrapped) { wrapped.push_back(li); continue; }
        std::vector<double> xy;
        for (size_t k = 0; k < loops[li].size(); k++) {
            xy.push_back(infos[li].u[k] / perimeter);
            xy.push_back((infos[li].d[k] - dmin) / dscale);
        }
        auto tri = earClip(xy);
        for (size_t t = 0; t + 2 < tri.size(); t += 3)
            caps.push_back({posOf[loops[li][tri[t]]], posOf[loops[li][tri[t + 1]]], posOf[loops[li][tri[t + 2]]]});
    }
    // loops that go all the way round the outline are paired by depth and joined as a band
    std::sort(wrapped.begin(), wrapped.end(), [&](size_t a, size_t b) { return infos[a].meanD < infos[b].meanD; });
    for (size_t k = 0; k + 1 < wrapped.size(); k += 2) {
        auto& A = loops[wrapped[k]];
        auto& B = loops[wrapped[k + 1]];
        auto unwrap = [&](const std::vector<double>& u, std::vector<double>& out, size_t& start) {
            start = (size_t)(std::min_element(u.begin(), u.end()) - u.begin());
            out.resize(u.size() + 1);
            double base = u[start];
            for (size_t i = 0; i <= u.size(); i++) {
                double v = u[(start + i) % u.size()] - base;
                while (v < 0) v += perimeter;
                if (i > 0 && v + 1e-9 < out[i - 1]) v = out[i - 1];
                out[i] = (i == u.size()) ? perimeter : v;
            }
        };
        std::vector<double> ua, ub;
        size_t sa, sb;
        unwrap(infos[wrapped[k]].u, ua, sa);
        unwrap(infos[wrapped[k + 1]].u, ub, sb);
        size_t m = A.size(), q = B.size(), i = 0, j = 0;
        auto pa = [&](size_t x) { return posOf[A[(sa + x) % m]]; };
        auto pb = [&](size_t x) { return posOf[B[(sb + x) % q]]; };
        while (i < m || j < q) {
            bool advanceA = j >= q || (i < m && ua[i + 1] <= ub[j + 1]);
            if (advanceA) { caps.push_back({pa(i), pa(i + 1), pb(j)}); i++; }
            else { caps.push_back({pa(i), pb(j + 1), pb(j)}); j++; }
        }
    }

    // ---- write back: rebuilt chunks + caps, previous geometry kept for undo
    std::vector<std::shared_ptr<Chunk>> result = untouched;
    int64_t cutTris = 0;
    int keepPartForCaps = works[0].src->part;
    for (auto& w : works) {
        const Chunk& src = *w.src;
        if (auto k = buildChunk(w.verts, w.keep, src.material, src.part)) result.push_back(std::shared_ptr<Chunk>(k.release()));
        cutTris += (int64_t)w.cut.size() / 3;
        if (auto c = buildChunk(w.verts, w.cut, src.material, newPart)) result.push_back(std::shared_ptr<Chunk>(c.release()));
    }
    if (!caps.empty()) {
        // the same seal goes on the new part and on the opening left in the model (facing the other way)
        for (int side = 0; side < 2; side++) {
            std::vector<Vertex> cv;
            std::vector<uint32_t> ct;
            for (auto& tri : caps) {
                V3 e1 = tri[1] - tri[0], e2 = tri[2] - tri[0];
                V3 nn = e1.cross(e2);
                if (nn.len() <= 0) continue;
                if (side == 1) nn = nn * -1.0;
                uint32_t pn = packNormal((float)nn.x, (float)nn.y, (float)nn.z);
                for (int kk = 0; kk < 3; kk++) {
                    const V3& p = tri[side == 1 ? 2 - kk : kk];
                    Vertex v;
                    v.p[0] = (float)p.x; v.p[1] = (float)p.y; v.p[2] = (float)p.z;
                    v.n = pn;
                    v.uv[0] = v.uv[1] = 0;
                    v.c = kWhite;
                    ct.push_back((uint32_t)cv.size());
                    cv.push_back(v);
                }
            }
            if (auto c = buildChunk(cv, ct, -1, side == 0 ? newPart : keepPartForCaps))
                result.push_back(std::shared_ptr<Chunk>(c.release()));
        }
    }
    if (req.keepUndo) undo_ = cpu_;
    redo_.clear();
    parentPart_[newPart] = keepPartForCaps;
    cpu_.swap(result);
    rebuildGpu_ = true;
    recomputeParts(sink);
    res.part = newPart;
    res.triangles = cutTris;
    res.capTriangles = (int64_t)caps.size();
    sink->log(0, "Cut \"" + req.name + "\": " + std::to_string(cutTris) + " triangles moved to the new part, " +
                     std::to_string(caps.size()) + " triangles added to seal the cut.");
    return res;
}

void Renderer::previewCut(const CutRequest& req) {
    size_t n = req.points.size() / 3;
    std::vector<std::vector<uint32_t>> out;
    CutCam cc;
    if (n >= 3 && cutCamera(cc)) {
        std::vector<double> lasso(n * 2);
        bool ok = true;
        double lminx = 1e300, lminy = 1e300, lmaxx = -1e300, lmaxy = -1e300;
        for (size_t i = 0; i < n && ok; i++) {
            ok = projectCC(cc, &req.points[i * 3], lasso[i * 2], lasso[i * 2 + 1]);
            lminx = std::min(lminx, lasso[i * 2]); lmaxx = std::max(lmaxx, lasso[i * 2]);
            lminy = std::min(lminy, lasso[i * 2 + 1]); lmaxy = std::max(lmaxy, lasso[i * 2 + 1]);
        }
        if (ok) {
            bool limitDepth = req.depthLo > 0.001f || req.depthHi < 0.999f;
            double dLo = -1e300, dHi = 1e300;
            std::vector<uint8_t> hidden;
            {
                std::lock_guard<std::mutex> l(mu_);
                hidden = hidden_;
                if (limitDepth && bounds_.valid()) {
                    double mn = 1e300, mx = -1e300;
                    for (int k = 0; k < 8; k++) {
                        V3 c(k & 1 ? bounds_.mx[0] : bounds_.mn[0], k & 2 ? bounds_.mx[1] : bounds_.mn[1], k & 4 ? bounds_.mx[2] : bounds_.mn[2]);
                        double d = (c - cc.eye).dot(cc.fwd);
                        mn = std::min(mn, d);
                        mx = std::max(mx, d);
                    }
                    dLo = mn + (mx - mn) * req.depthLo;
                    dHi = mn + (mx - mn) * req.depthHi;
                }
            }
            std::lock_guard<std::mutex> dl(dataMu_);
            out.resize(cpu_.size());
            for (size_t ci = 0; ci < cpu_.size(); ci++) {
                const Chunk& c = *cpu_[ci];
                if (c.points || c.tris.empty()) continue;
                if (c.part >= 0 && c.part < (int)hidden.size() && hidden[c.part]) continue;
                if (req.onlyPart >= 0 && c.part != req.onlyPart) continue;
                for (size_t t = 0; t + 2 < c.tris.size(); t += 3) {
                    const float* a = c.verts[c.tris[t]].p;
                    const float* b = c.verts[c.tris[t + 1]].p;
                    const float* d = c.verts[c.tris[t + 2]].p;
                    float cen[3] = {(a[0] + b[0] + d[0]) / 3, (a[1] + b[1] + d[1]) / 3, (a[2] + b[2] + d[2]) / 3};
                    double px, py;
                    if (!projectCC(cc, cen, px, py)) continue;
                    if (px < lminx || px > lmaxx || py < lminy || py > lmaxy) continue;
                    if (!pointInPoly(px, py, lasso)) continue;
                    if (limitDepth) {
                        double dd = (V3(cen[0], cen[1], cen[2]) - cc.eye).dot(cc.fwd);
                        if (dd < dLo || dd > dHi) continue;
                    }
                    bool gone = false;
                    for (size_t k = 0; k + 4 <= req.erase.size() && !gone; k += 4) {
                        double dx = cen[0] - req.erase[k], dy = cen[1] - req.erase[k + 1], dz = cen[2] - req.erase[k + 2];
                        if (dx * dx + dy * dy + dz * dz <= (double)req.erase[k + 3] * req.erase[k + 3]) gone = true;
                    }
                    if (gone) continue;
                    out[ci].insert(out[ci].end(), {c.tris[t], c.tris[t + 1], c.tris[t + 2]});
                }
            }
            preview_.swap(out);
            previewDirty_ = true;
            return;
        }
    }
    std::lock_guard<std::mutex> dl(dataMu_);
    preview_.clear();
    previewDirty_ = true;
}

void Renderer::clearExactPreview() {
    std::lock_guard<std::mutex> dl(dataMu_);
    pmVerts_.clear();
    pmDirty_ = true;
}

void Renderer::deletePart(int part, Sink* sink) {
    std::lock_guard<std::mutex> dl(dataMu_);
    std::vector<std::shared_ptr<Chunk>> keep;
    for (auto& c : cpu_) if (c->part != part) keep.push_back(c);
    cpu_.swap(keep);
    rebuildGpu_ = true;
    recomputeParts(sink);
}

double Renderer::mirrorCenter(int ax) {
    std::lock_guard<std::mutex> l(mu_);
    if (!bounds_.valid() || ax < 0 || ax > 2) return 0;
    return (bounds_.mn[ax] + bounds_.mx[ax]) / 2;
}

int Renderer::detectMirrorAxis() {
    Bounds b;
    {
        std::lock_guard<std::mutex> l(mu_);
        b = bounds_;
    }
    if (!b.valid()) return 0;
    std::lock_guard<std::mutex> dl(dataMu_);
    // voxel grid of surface vertices; score each axis by how many mirrored samples land near a vertex
    double size = std::max({b.mx[0] - b.mn[0], b.mx[1] - b.mn[1], b.mx[2] - b.mn[2]});
    if (size <= 0) return 0;
    double cell = size / 96.0;
    std::unordered_map<uint64_t, uint8_t> grid;
    auto key = [&](double x, double y, double z) {
        int64_t i = (int64_t)std::floor((x - b.mn[0]) / cell), j = (int64_t)std::floor((y - b.mn[1]) / cell),
                k = (int64_t)std::floor((z - b.mn[2]) / cell);
        return (uint64_t)((i & 0x1FFFFF) | ((j & 0x1FFFFF) << 21) | ((k & 0x1FFFFF) << 42));
    };
    std::vector<V3> samples;
    size_t total = 0;
    for (auto& c : cpu_) total += c->verts.size();
    size_t stride = std::max<size_t>(1, total / 4000);
    size_t idx = 0;
    for (auto& c : cpu_) {
        for (auto& v : c->verts) {
            grid[key(v.p[0], v.p[1], v.p[2])] = 1;
            if (idx++ % stride == 0) samples.emplace_back(v.p[0], v.p[1], v.p[2]);
        }
    }
    int bestAxis = 0;
    double bestScore = -1;
    for (int ax = 0; ax < 3; ax++) {
        double c = (b.mn[ax] + b.mx[ax]) / 2;
        int hit = 0;
        for (auto& p : samples) {
            V3 m = p;
            double* v = ax == 0 ? &m.x : ax == 1 ? &m.y : &m.z;
            *v = 2 * c - *v;
            bool found = false;
            for (int dx = -1; dx <= 1 && !found; dx++)
                for (int dy = -1; dy <= 1 && !found; dy++)
                    for (int dz = -1; dz <= 1 && !found; dz++)
                        if (grid.count(key(m.x + dx * cell, m.y + dy * cell, m.z + dz * cell))) found = true;
            if (found) hit++;
        }
        double score = (double)hit / std::max<size_t>(1, samples.size());
        if (score > bestScore) { bestScore = score; bestAxis = ax; }
    }
    return bestAxis;
}

bool Renderer::undoCut(Sink* sink) {
    std::lock_guard<std::mutex> dl(dataMu_);
    if (undo_.empty()) return false;
    redo_ = cpu_;
    cpu_.swap(undo_);
    undo_.clear();
    rebuildGpu_ = true;
    recomputeParts(sink);
    return true;
}

bool Renderer::redoCut(Sink* sink) {
    std::lock_guard<std::mutex> dl(dataMu_);
    if (redo_.empty()) return false;
    undo_ = cpu_;
    cpu_.swap(redo_);
    redo_.clear();
    rebuildGpu_ = true;
    recomputeParts(sink);
    return true;
}

std::string Renderer::exportMemory(const ExportOptions& opt, Sink* sink) {
    ChunkWriter w(opt);
    if (!w.begin()) {
        if (opt.mtlFd >= 0) ::close(opt.mtlFd);
        return "Could not write the output file.";
    }
    std::vector<PartInfo> parts = sink ? sink->parts() : std::vector<PartInfo>();
    std::vector<Material> mats = sink ? sink->materials() : std::vector<Material>();
    double origin[3] = {0, 0, 0};
    if (sink) memcpy(origin, sink->origin, sizeof origin);
    {
        std::lock_guard<std::mutex> dl(dataMu_);
        if (!keepCpu_) {
            w.finish(mats, false);
            return "no-memory-copy";
        }
        for (auto& c : cpu_) w.write(*c, origin, parts, mats);
    }
    return w.finish(mats, true);
}


// ======================================================================== brush selection
void Renderer::ensureSel_() {
    bool ok = tsel_.size() == cpu_.size() && vsel_.size() == cpu_.size();
    for (size_t i = 0; ok && i < cpu_.size(); i++)
        ok = tsel_[i].size() == cpu_[i]->tris.size() / 3 && vsel_[i].size() == cpu_[i]->verts.size();
    if (ok) return;
    tsel_.assign(cpu_.size(), {});
    vsel_.assign(cpu_.size(), {});
    for (size_t i = 0; i < cpu_.size(); i++) {
        tsel_[i].assign(cpu_[i]->tris.size() / 3, 0);
        vsel_[i].assign(cpu_[i]->verts.size(), 0);
    }
}

void Renderer::selToPreview_() {
    std::vector<std::vector<uint32_t>> out(cpu_.size());
    for (size_t ci = 0; ci < cpu_.size(); ci++) {
        const Chunk& c = *cpu_[ci];
        for (size_t t = 0; t * 3 + 2 < c.tris.size(); t++) {
            const uint32_t* tr = &c.tris[t * 3];
            if (tsel_[ci][t] || (vsel_[ci][tr[0]] && vsel_[ci][tr[1]] && vsel_[ci][tr[2]]))
                out[ci].insert(out[ci].end(), {tr[0], tr[1], tr[2]});
        }
    }
    preview_.swap(out);
    previewDirty_ = true;
}

static int64_t countSel(const std::vector<std::vector<uint32_t>>& pv) {
    int64_t n = 0;
    for (auto& v : pv) n += (int64_t)v.size() / 3;
    return n;
}

int64_t Renderer::brushSelect(float sx, float sy, float r, bool add, bool vertexMode, bool xray) {
    CutCam cc;
    if (!cutCamera(cc) || r <= 0) return selectionCount();
    std::vector<uint8_t> hidden;
    double diag = 1;
    {
        std::lock_guard<std::mutex> l(mu_);
        hidden = hidden_;
        if (bounds_.valid()) {
            double dx = bounds_.mx[0] - bounds_.mn[0], dy = bounds_.mx[1] - bounds_.mn[1], dz = bounds_.mx[2] - bounds_.mn[2];
            diag = std::sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
    std::lock_guard<std::mutex> dl(dataMu_);
    if (!keepCpu_ || cpu_.empty()) return 0;
    ensureSel_();
    const int G = 32;
    const double x0 = sx - r, y0 = sy - r, cell = 2.0 * r / G, eps = diag * 0.004;
    std::vector<double> zb(G * G, 1e300);
    auto depthOf = [&](const float* p) { return (V3(p[0], p[1], p[2]) - cc.eye).dot(cc.fwd); };
    struct Cand { uint32_t ci, t; };
    std::vector<Cand> cands;
    for (size_t ci = 0; ci < cpu_.size(); ci++) {
        const Chunk& c = *cpu_[ci];
        if (c.points || c.tris.empty()) continue;
        if (c.part >= 0 && c.part < (int)hidden.size() && hidden[c.part]) continue;
        for (size_t t = 0; t * 3 + 2 < c.tris.size(); t++) {
            double px[3], py[3], pd[3];
            bool ok = true;
            for (int k = 0; k < 3 && ok; k++) {
                const float* p = c.verts[c.tris[t * 3 + k]].p;
                ok = projectCC(cc, p, px[k], py[k]);
                pd[k] = depthOf(p);
            }
            if (!ok) continue;
            double mnx = std::min({px[0], px[1], px[2]}), mxx = std::max({px[0], px[1], px[2]});
            double mny = std::min({py[0], py[1], py[2]}), mxy = std::max({py[0], py[1], py[2]});
            if (mxx < x0 || mnx > sx + r || mxy < y0 || mny > sy + r) continue;
            cands.push_back({(uint32_t)ci, (uint32_t)t});
            if (xray) continue;
            // small depth buffer over the brush: what's in front hides what's behind
            double den = (py[1] - py[2]) * (px[0] - px[2]) + (px[2] - px[1]) * (py[0] - py[2]);
            if (std::fabs(den) < 1e-12) continue;
            int gx0 = std::max(0, (int)std::floor((mnx - x0) / cell)), gx1 = std::min(G - 1, (int)std::floor((mxx - x0) / cell));
            int gy0 = std::max(0, (int)std::floor((mny - y0) / cell)), gy1 = std::min(G - 1, (int)std::floor((mxy - y0) / cell));
            for (int gy = gy0; gy <= gy1; gy++)
                for (int gx = gx0; gx <= gx1; gx++) {
                    double cx = x0 + (gx + 0.5) * cell, cy = y0 + (gy + 0.5) * cell;
                    double w0 = ((py[1] - py[2]) * (cx - px[2]) + (px[2] - px[1]) * (cy - py[2])) / den;
                    double w1 = ((py[2] - py[0]) * (cx - px[2]) + (px[0] - px[2]) * (cy - py[2])) / den;
                    double w2 = 1 - w0 - w1;
                    if (w0 < -0.01 || w1 < -0.01 || w2 < -0.01) continue;
                    double d = w0 * pd[0] + w1 * pd[1] + w2 * pd[2];
                    double& z = zb[gy * G + gx];
                    if (d < z) z = d;
                }
        }
    }
    auto visible = [&](double x, double y, double d) {
        if (xray) return true;
        int gx = std::clamp((int)std::floor((x - x0) / cell), 0, G - 1), gy = std::clamp((int)std::floor((y - y0) / cell), 0, G - 1);
        double z = zb[gy * G + gx];
        return z >= 1e299 || d <= z + eps;
    };
    const double r2 = (double)r * r;
    for (auto& cd : cands) {
        const Chunk& c = *cpu_[cd.ci];
        const uint32_t* tr = &c.tris[cd.t * 3];
        if (!vertexMode) {
            const float* a = c.verts[tr[0]].p; const float* b = c.verts[tr[1]].p; const float* e = c.verts[tr[2]].p;
            float cen[3] = {(a[0] + b[0] + e[0]) / 3, (a[1] + b[1] + e[1]) / 3, (a[2] + b[2] + e[2]) / 3};
            double x, y;
            if (!projectCC(cc, cen, x, y)) continue;
            if ((x - sx) * (x - sx) + (y - sy) * (y - sy) > r2 || !visible(x, y, depthOf(cen))) continue;
            tsel_[cd.ci][cd.t] = add ? 1 : 0;
            if (!add) for (int k = 0; k < 3; k++) vsel_[cd.ci][tr[k]] = 0;
        } else {
            for (int k = 0; k < 3; k++) {
                const float* p = c.verts[tr[k]].p;
                double x, y;
                if (!projectCC(cc, p, x, y)) continue;
                if ((x - sx) * (x - sx) + (y - sy) * (y - sy) > r2 || !visible(x, y, depthOf(p))) continue;
                vsel_[cd.ci][tr[k]] = add ? 1 : 0;
                if (!add) tsel_[cd.ci][cd.t] = 0;
            }
        }
    }
    selToPreview_();
    return countSel(preview_);
}

int64_t Renderer::selectionFromPreview() {
    std::lock_guard<std::mutex> dl(dataMu_);
    if (!keepCpu_ || cpu_.empty()) return 0;
    ensureSel_();
    for (size_t ci = 0; ci < cpu_.size() && ci < preview_.size(); ci++) {
        const auto& pv = preview_[ci];
        const auto& tris = cpu_[ci]->tris;
        size_t k = 0;
        for (size_t t = 0; t * 3 + 2 < tris.size() && k + 2 < pv.size(); t++)
            if (pv[k] == tris[t * 3] && pv[k + 1] == tris[t * 3 + 1] && pv[k + 2] == tris[t * 3 + 2]) { tsel_[ci][t] = 1; k += 3; }
    }
    selToPreview_();
    return countSel(preview_);
}

void Renderer::clearSelection() {
    std::lock_guard<std::mutex> dl(dataMu_);
    tsel_.clear();
    vsel_.clear();
    preview_.clear();
    previewDirty_ = true;
}

int64_t Renderer::selectionCount() {
    std::lock_guard<std::mutex> dl(dataMu_);
    if (tsel_.size() != cpu_.size()) return 0;
    return countSel(preview_);
}

int Renderer::separateSelection(const std::string& name, Sink* sink, int64_t& moved) {
    moved = 0;
    if (!sink) return -1;
    std::lock_guard<std::mutex> dl(dataMu_);
    if (!keepCpu_ || cpu_.empty() || tsel_.size() != cpu_.size()) return -1;
    int64_t any = countSel(preview_);
    if (any == 0) return -1;
    int newPart = sink->addPart(name.empty() ? "Selected part" : name);
    std::unordered_map<int, int64_t> fromCount;
    std::vector<std::shared_ptr<Chunk>> result;
    for (size_t ci = 0; ci < cpu_.size(); ci++) {
        const Chunk& c = *cpu_[ci];
        std::vector<uint32_t> keep, cut;
        for (size_t t = 0; t * 3 + 2 < c.tris.size(); t++) {
            const uint32_t* tr = &c.tris[t * 3];
            bool s = tsel_[ci][t] || (vsel_[ci][tr[0]] && vsel_[ci][tr[1]] && vsel_[ci][tr[2]]);
            (s ? cut : keep).insert((s ? cut : keep).end(), {tr[0], tr[1], tr[2]});
        }
        if (cut.empty()) { result.push_back(cpu_[ci]); continue; }
        moved += (int64_t)cut.size() / 3;
        fromCount[c.part] += (int64_t)cut.size();
        if (auto k = buildChunk(c.verts, keep, c.material, c.part)) result.push_back(std::shared_ptr<Chunk>(k.release()));
        if (auto n = buildChunk(c.verts, cut, c.material, newPart)) result.push_back(std::shared_ptr<Chunk>(n.release()));
    }
    {
        int from = 0;
        int64_t most = -1;
        for (auto& kv : fromCount) if (kv.second > most) { most = kv.second; from = kv.first; }
        parentPart_[newPart] = from;
    }
    undo_ = cpu_;
    redo_.clear();
    cpu_.swap(result);
    tsel_.clear();
    vsel_.clear();
    preview_.clear();
    previewDirty_ = true;
    rebuildGpu_ = true;
    recomputeParts(sink);
    sink->log(0, "Selection \"" + name + "\": " + std::to_string(moved) + " triangles moved to a new part.");
    return newPart;
}

namespace {
struct QKey {
    int64_t x, y, z;
    bool operator==(const QKey& o) const { return x == o.x && y == o.y && z == o.z; }
};
struct QKeyHash {
    size_t operator()(const QKey& k) const {
        return (size_t)(k.x * 73856093LL ^ k.y * 19349663LL ^ k.z * 83492791LL);
    }
};
}  // namespace

int64_t Renderer::partsTouch(int a, int b) {
    double q = 1;
    {
        std::lock_guard<std::mutex> l(mu_);
        if (bounds_.valid()) {
            double s = std::max({bounds_.mx[0] - bounds_.mn[0], bounds_.mx[1] - bounds_.mn[1], bounds_.mx[2] - bounds_.mn[2]});
            q = s > 0 ? 2e6 / s : 1;
        }
    }
    std::lock_guard<std::mutex> dl(dataMu_);
    auto key = [&](const float* p) { return QKey{(int64_t)std::llround(p[0] * q), (int64_t)std::llround(p[1] * q), (int64_t)std::llround(p[2] * q)}; };
    std::unordered_set<QKey, QKeyHash> sa;
    for (auto& c : cpu_) if (c->part == a && !c->points) for (auto& v : c->verts) sa.insert(key(v.p));
    if (sa.empty()) return 0;
    std::unordered_set<QKey, QKeyHash> hit;
    for (auto& c : cpu_) if (c->part == b && !c->points) for (auto& v : c->verts) { QKey k = key(v.p); if (sa.count(k)) hit.insert(k); }
    return (int64_t)hit.size();
}

bool Renderer::mergeParts(int a, int b, bool connect, Sink* sink, int64_t& seals, int64_t& welded) {
    seals = 0;
    welded = 0;
    if (!sink || a == b || a < 0 || b < 0) return false;
    double q = 1;
    {
        std::lock_guard<std::mutex> l(mu_);
        if (bounds_.valid()) {
            double s = std::max({bounds_.mx[0] - bounds_.mn[0], bounds_.mx[1] - bounds_.mn[1], bounds_.mx[2] - bounds_.mn[2]});
            q = s > 0 ? 2e6 / s : 1;
        }
    }
    std::lock_guard<std::mutex> dl(dataMu_);
    if (!keepCpu_ || cpu_.empty()) return false;
    auto key = [&](const float* p) { return QKey{(int64_t)std::llround(p[0] * q), (int64_t)std::llround(p[1] * q), (int64_t)std::llround(p[2] * q)}; };
    std::vector<std::shared_ptr<Chunk>> result, merged;
    for (auto& c : cpu_) {
        if (c->part != a && c->part != b) { result.push_back(c); continue; }
        auto n = std::make_shared<Chunk>(*c);
        n->part = a;
        n->animSlot = c->animSlot;
        merged.push_back(n);
    }
    if (connect) {
        // 1. the seals a cut put on both sides of the opening face each other: remove both
        auto triKey = [&](const Chunk& c, size_t t) {
            std::array<QKey, 3> k = {key(c.verts[c.tris[t]].p), key(c.verts[c.tris[t + 1]].p), key(c.verts[c.tris[t + 2]].p)};
            std::sort(k.begin(), k.end(), [](const QKey& x, const QKey& y) { return x.x != y.x ? x.x < y.x : x.y != y.y ? x.y < y.y : x.z < y.z; });
            return k;
        };
        struct AH { size_t operator()(const std::array<QKey, 3>& k) const { QKeyHash h; return h(k[0]) * 31 ^ h(k[1]) * 17 ^ h(k[2]); } };
        std::unordered_map<std::array<QKey, 3>, int, AH> count;
        for (size_t i = 0; i < cpu_.size(); i++) {
            const Chunk& c = *cpu_[i];
            if ((c.part != a && c.part != b) || c.material != -1) continue;
            for (size_t t = 0; t + 2 < c.tris.size(); t += 3) count[triKey(c, t)] |= (c.part == a ? 1 : 2);
        }
        for (auto& m : merged) {
            if (m->material != -1) continue;
            std::vector<uint32_t> keep;
            for (size_t t = 0; t + 2 < m->tris.size(); t += 3) {
                auto it = count.find(triKey(*m, t));
                if (it != count.end() && it->second == 3) { seals++; continue; }
                keep.insert(keep.end(), {m->tris[t], m->tris[t + 1], m->tris[t + 2]});
            }
            if (keep.size() != m->tris.size()) {
                auto nc = buildChunk(m->verts, keep, m->material, a);
                if (nc) m = std::shared_ptr<Chunk>(nc.release());
                else m.reset();
            }
        }
        merged.erase(std::remove(merged.begin(), merged.end(), nullptr), merged.end());
        // 2. chunks with the same material become one mesh; identical seam vertices are welded
        std::vector<std::shared_ptr<Chunk>> joined;
        std::vector<bool> used(merged.size(), false);
        for (size_t i = 0; i < merged.size(); i++) {
            if (used[i]) continue;
            used[i] = true;
            if (merged[i]->points || merged[i]->animSlot >= 0) { joined.push_back(merged[i]); continue; }
            std::vector<Vertex> verts;
            std::vector<uint32_t> tris;
            auto add = [&](const Chunk& c) {
                uint32_t base = (uint32_t)verts.size();
                verts.insert(verts.end(), c.verts.begin(), c.verts.end());
                for (uint32_t t : c.tris) tris.push_back(base + t);
            };
            add(*merged[i]);
            for (size_t j = i + 1; j < merged.size(); j++) {
                if (used[j] || merged[j]->material != merged[i]->material || merged[j]->points || merged[j]->animSlot >= 0) continue;
                if (verts.size() + merged[j]->verts.size() > 1000000) continue;
                used[j] = true;
                add(*merged[j]);
            }
            // weld: same position, normal, uv and colour
            std::unordered_map<std::string, uint32_t> seen;
            seen.reserve(verts.size());
            std::vector<uint32_t> remap(verts.size());
            std::vector<Vertex> out;
            out.reserve(verts.size());
            for (size_t v = 0; v < verts.size(); v++) {
                std::string k((const char*)&verts[v], sizeof(Vertex));
                auto it = seen.find(k);
                if (it != seen.end()) { remap[v] = it->second; welded++; continue; }
                remap[v] = (uint32_t)out.size();
                seen.emplace(std::move(k), remap[v]);
                out.push_back(verts[v]);
            }
            for (auto& t : tris) t = remap[t];
            std::vector<uint32_t> all(tris.size());
            for (size_t t = 0; t < tris.size(); t++) all[t] = tris[t];
            if (auto nc = buildChunk(out, all, merged[i]->material, a)) joined.push_back(std::shared_ptr<Chunk>(nc.release()));
        }
        merged.swap(joined);
    }
    for (auto& m : merged) result.push_back(m);
    undo_ = cpu_;
    redo_.clear();
    cpu_.swap(result);
    tsel_.clear();
    vsel_.clear();
    preview_.clear();
    previewDirty_ = true;
    rebuildGpu_ = true;
    recomputeParts(sink);
    sink->log(0, std::string(connect ? "Connected" : "Combined") + " two parts" +
                     (connect ? ": " + std::to_string(seals) + " seal triangles removed, " + std::to_string(welded) + " seam vertices welded." : "."));
    return true;
}

int64_t Renderer::editPartSelection(int part) {
    std::lock_guard<std::mutex> dl(dataMu_);
    if (!keepCpu_ || cpu_.empty()) return 0;
    tsel_.clear();
    vsel_.clear();
    ensureSel_();
    for (size_t ci = 0; ci < cpu_.size(); ci++)
        if (cpu_[ci]->part == part && !cpu_[ci]->points) std::fill(tsel_[ci].begin(), tsel_[ci].end(), 1);
    selToPreview_();
    return countSel(preview_);
}

bool Renderer::applyPartEdit(int part, Sink* sink, int64_t& out, int64_t& in) {
    out = 0;
    in = 0;
    if (!sink || part < 0) return false;
    std::lock_guard<std::mutex> dl(dataMu_);
    if (!keepCpu_ || cpu_.empty() || tsel_.size() != cpu_.size()) return false;
    int parent = -1;
    auto pit = parentPart_.find(part);
    if (pit != parentPart_.end()) parent = pit->second;
    if (parent < 0 || parent == part) {
        // not made by a cut here: erased triangles go to the biggest other part
        std::unordered_map<int, int64_t> cnt;
        for (auto& c : cpu_) if (c->part != part) cnt[c->part] += (int64_t)c->tris.size();
        int64_t most = -1;
        parent = 0;
        for (auto& kv : cnt) if (kv.second > most) { most = kv.second; parent = kv.first; }
    }
    std::vector<std::shared_ptr<Chunk>> result;
    for (size_t ci = 0; ci < cpu_.size(); ci++) {
        const Chunk& c = *cpu_[ci];
        if (c.points || c.tris.empty()) { result.push_back(cpu_[ci]); continue; }
        std::vector<uint32_t> stay, move;
        for (size_t t = 0; t * 3 + 2 < c.tris.size(); t++) {
            const uint32_t* tr = &c.tris[t * 3];
            bool s = tsel_[ci][t] || (vsel_[ci][tr[0]] && vsel_[ci][tr[1]] && vsel_[ci][tr[2]]);
            bool mv = c.part == part ? !s : s;  // erased from the part / painted onto it
            std::vector<uint32_t>& dst = mv ? move : stay;
            dst.insert(dst.end(), {tr[0], tr[1], tr[2]});
        }
        if (move.empty()) { result.push_back(cpu_[ci]); continue; }
        int target = c.part == part ? parent : part;
        (c.part == part ? out : in) += (int64_t)move.size() / 3;
        if (auto k = buildChunk(c.verts, stay, c.material, c.part)) result.push_back(std::shared_ptr<Chunk>(k.release()));
        if (auto m = buildChunk(c.verts, move, c.material, target)) result.push_back(std::shared_ptr<Chunk>(m.release()));
    }
    undo_ = cpu_;
    redo_.clear();
    cpu_.swap(result);
    tsel_.clear();
    vsel_.clear();
    preview_.clear();
    previewDirty_ = true;
    rebuildGpu_ = true;
    recomputeParts(sink);
    sink->log(0, "Brush edit: " + std::to_string(out) + " triangles taken off the part, " + std::to_string(in) + " added.");
    return true;
}

// ======================================================================== aircraft tools
// Finds the wings by slicing the model across the span: for every station the wing is the section
// that continues the one found at ~60 % of the half-span (where there is nothing but wing), so
// engines, gear and tailplane are left out. Output layout (doubles):
//   lenAxis, vertAxis, tailSign, upSign, spanCenter, halfPos, halfNeg, N,
//   then for side +1 and side -1, N+1 stations each: eta, found, Lle, Lte, vlo, vhi
std::vector<double> Renderer::analyzeWings(int span, int N) {
    std::vector<double> out;
    Bounds b;
    std::vector<uint8_t> hidden;
    {
        std::lock_guard<std::mutex> l(mu_);
        b = bounds_;
        hidden = hidden_;
    }
    if (!b.valid() || span < 0 || span > 2 || N < 8) return out;
    int o1 = (span + 1) % 3, o2 = (span + 2) % 3;
    int len = (b.mx[o1] - b.mn[o1]) >= (b.mx[o2] - b.mn[o2]) ? o1 : o2;
    int vert = len == o1 ? o2 : o1;
    double cS = (b.mn[span] + b.mx[span]) / 2;
    double halfP = b.mx[span] - cS, halfN = cS - b.mn[span];
    double Lmn = b.mn[len], Lmx = b.mx[len], Llen = Lmx - Lmn;
    std::lock_guard<std::mutex> dl(dataMu_);
    if (cpu_.empty()) return out;
    auto visible = [&](const Chunk& c) {
        return !c.points && !c.tris.empty() && !(c.part >= 0 && c.part < (int)hidden.size() && hidden[c.part]);
    };
    // tail: the end with the tall fin; up: the side the fin points to
    double hiMin = 1e300, hiMax = -1e300, loMin = 1e300, loMax = -1e300, fusSum = 0;
    int64_t fusN = 0;
    for (auto& cp : cpu_) {
        if (!visible(*cp)) continue;
        for (auto& v : cp->verts) {
            double L = v.p[len], V = v.p[vert];
            if (L > Lmx - 0.15 * Llen) { hiMin = std::min(hiMin, V); hiMax = std::max(hiMax, V); }
            if (L < Lmn + 0.15 * Llen) { loMin = std::min(loMin, V); loMax = std::max(loMax, V); }
            if (std::fabs(v.p[span] - cS) < 0.04 * std::max(halfP, halfN)) { fusSum += V; fusN++; }
        }
    }
    int tailSign = (hiMax - hiMin) >= (loMax - loMin) ? 1 : -1;
    double vFus = fusN ? fusSum / fusN : (b.mn[vert] + b.mx[vert]) / 2;
    double tMin = tailSign > 0 ? hiMin : loMin, tMax = tailSign > 0 ? hiMax : loMax;
    int upSign = (tMax - vFus) >= (vFus - tMin) ? 1 : -1;

    // slices: points (L, V) where triangles cross each station plane
    std::vector<std::vector<std::pair<double, double>>> pts[2];
    pts[0].resize(N + 1);
    pts[1].resize(N + 1);
    for (auto& cp : cpu_) {
        const Chunk& c = *cp;
        if (!visible(c)) continue;
        for (size_t t = 0; t + 2 < c.tris.size(); t += 3) {
            const float* P[3] = {c.verts[c.tris[t]].p, c.verts[c.tris[t + 1]].p, c.verts[c.tris[t + 2]].p};
            double smin = std::min({P[0][span], P[1][span], P[2][span]}), smax = std::max({P[0][span], P[1][span], P[2][span]});
            for (int side = 0; side < 2; side++) {
                double half = side == 0 ? halfP : halfN;
                if (half <= 0) continue;
                double s = side == 0 ? 1.0 : -1.0;
                // eta range covered by this triangle
                double e0 = (smin - cS) * s / half, e1 = (smax - cS) * s / half;
                if (e0 > e1) std::swap(e0, e1);
                int k0 = std::max(1, (int)std::ceil(e0 * N)), k1 = std::min(N - 1, (int)std::floor(e1 * N));
                for (int k = k0; k <= k1; k++) {
                    double y = cS + s * half * k / N;
                    for (int e = 0; e < 3; e++) {
                        const float* A = P[e];
                        const float* B = P[(e + 1) % 3];
                        double da = A[span] - y, db = B[span] - y;
                        if ((da < 0) == (db < 0) || da == db) continue;
                        double tt = da / (da - db);
                        pts[side][k].push_back({A[len] + (B[len] - A[len]) * tt, A[vert] + (B[vert] - A[vert]) * tt});
                    }
                }
            }
        }
    }
    out = {(double)len, (double)vert, (double)tailSign, (double)upSign, cS, halfP, halfN, (double)N};
    for (int side = 0; side < 2; side++) {
        double half = side == 0 ? halfP : halfN;
        double cell = std::max(half, 1e-9) * 0.012;
        struct Cl { double l0 = 1e300, l1 = -1e300, v0 = 1e300, v1 = -1e300; std::vector<int> idx; };
        auto clusters = [&](const std::vector<std::pair<double, double>>& P) {
            std::unordered_map<int64_t, int> grid;
            auto key = [&](int64_t gx, int64_t gy) { return ((gx + (1LL << 20)) << 21) | (gy + (1LL << 20)); };
            std::vector<int64_t> gk(P.size());
            for (size_t i = 0; i < P.size(); i++) {
                int64_t gx = (int64_t)std::floor(P[i].first / cell), gy = (int64_t)std::floor(P[i].second / cell);
                gk[i] = key(gx, gy);
                grid.emplace(gk[i], -1);
            }
            int nc = 0;
            std::unordered_map<int64_t, int> label;
            for (auto& kv : grid) {
                if (label.count(kv.first)) continue;
                std::vector<int64_t> stack{kv.first};
                label[kv.first] = nc;
                while (!stack.empty()) {
                    int64_t k = stack.back();
                    stack.pop_back();
                    int64_t gx = (k >> 21) - (1LL << 20);
                    int64_t gy = (k & ((1LL << 21) - 1)) - (1LL << 20);
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dy = -1; dy <= 1; dy++) {
                            int64_t nk = key(gx + dx, gy + dy);
                            if (grid.count(nk) && !label.count(nk)) { label[nk] = nc; stack.push_back(nk); }
                        }
                }
                nc++;
            }
            std::vector<Cl> cl(nc);
            for (size_t i = 0; i < P.size(); i++) {
                Cl& c = cl[label[gk[i]]];
                c.l0 = std::min(c.l0, P[i].first); c.l1 = std::max(c.l1, P[i].first);
                c.v0 = std::min(c.v0, P[i].second); c.v1 = std::max(c.v1, P[i].second);
                c.idx.push_back((int)i);
            }
            return cl;
        };
        struct St { bool found = false; double le = 0, te = 0, vlo = 0, vhi = 0; };
        std::vector<St> st(N + 1);
        auto measure = [&](const std::vector<std::pair<double, double>>& P, const Cl& c, St& s) {
            double le = tailSign > 0 ? c.l0 : c.l1, te = tailSign > 0 ? c.l1 : c.l0;
            double chord = std::fabs(te - le);
            if (chord <= 0) return;
            // skin band near the trailing edge: the dense run of heights around the median
            std::vector<double> vs;
            for (int i : c.idx) if (std::fabs(P[i].first - te) < 0.4 * chord) vs.push_back(P[i].second);
            if (vs.size() < 2) return;
            std::sort(vs.begin(), vs.end());
            size_t m = vs.size() / 2, lo = m, hi = m;
            double gap = 0.03 * chord + cell * 0.5;
            while (lo > 0 && vs[lo] - vs[lo - 1] < gap) lo--;
            while (hi + 1 < vs.size() && vs[hi + 1] - vs[hi] < gap) hi++;
            s.vlo = vs[lo];
            s.vhi = vs[hi];
            // trailing edge measured on the skin only (fairings below don't count)
            double teSkin = le;
            for (int i : c.idx) {
                if (P[i].second < s.vlo - gap || P[i].second > s.vhi + gap) continue;
                teSkin = tailSign > 0 ? std::max(teSkin, P[i].first) : std::min(teSkin, P[i].first);
            }
            s.le = le;
            s.te = teSkin;
            s.found = true;
        };
        int k0 = (int)std::lround(0.6 * N);
        auto cl0 = clusters(pts[side][k0]);
        int best = -1;
        double bestChord = 0;
        for (size_t i = 0; i < cl0.size(); i++)
            if (cl0[i].l1 - cl0[i].l0 > bestChord) { bestChord = cl0[i].l1 - cl0[i].l0; best = (int)i; }
        if (best >= 0) {
            measure(pts[side][k0], cl0[best], st[k0]);
            for (int dir = -1; dir <= 1; dir += 2) {
                St ref = st[k0];
                for (int k = k0 + dir; k >= 1 && k <= N - 1; k += dir) {
                    if (dir < 0 && (double)k / N < 0.07) break;
                    auto cl = clusters(pts[side][k]);
                    int bi = -1;
                    double bs = 0;
                    double rl0 = std::min(ref.le, ref.te), rl1 = std::max(ref.le, ref.te);
                    double rv = (ref.vlo + ref.vhi) / 2;
                    for (size_t i = 0; i < cl.size(); i++) {
                        double ov = std::min(cl[i].l1, rl1) - std::max(cl[i].l0, rl0);
                        if (ov <= 0) continue;
                        double dv = (rv < cl[i].v0) ? cl[i].v0 - rv : (rv > cl[i].v1 ? rv - cl[i].v1 : 0);
                        double sc = ov - dv * 2;
                        if (sc > bs) { bs = sc; bi = (int)i; }
                    }
                    if (bi < 0) continue;
                    measure(pts[side][k], cl[bi], st[k]);
                    if (st[k].found) ref = st[k];
                }
            }
        }
        // smooth out engines / fairings: median of 5 for chord and trailing edge
        std::vector<St> sm = st;
        for (int k = 1; k < N; k++) {
            if (!st[k].found) continue;
            std::vector<double> ch, te;
            for (int j = std::max(1, k - 2); j <= std::min(N - 1, k + 2); j++)
                if (st[j].found) { ch.push_back(std::fabs(st[j].te - st[j].le)); te.push_back(st[j].te); }
            std::sort(ch.begin(), ch.end());
            std::sort(te.begin(), te.end());
            double c = ch[ch.size() / 2];
            sm[k].te = te[te.size() / 2];
            sm[k].le = sm[k].te - tailSign * c;
        }
        for (int k = 0; k <= N; k++) {
            const St& s = sm[k];
            out.insert(out.end(), {(double)k / N, s.found ? 1.0 : 0.0, s.le, s.te, s.vlo, s.vhi});
        }
    }
    return out;
}

bool Renderer::scaleModel(double f, Sink* sink) {
    if (!sink || !(f > 0) || !std::isfinite(f)) return false;
    {
        std::lock_guard<std::mutex> dl(dataMu_);
        if (!keepCpu_ || cpu_.empty()) return false;
        std::vector<std::shared_ptr<Chunk>> result;
        for (auto& c : cpu_) {
            auto n = std::make_shared<Chunk>(*c);
            for (auto& v : n->verts) { v.p[0] = (float)(v.p[0] * f); v.p[1] = (float)(v.p[1] * f); v.p[2] = (float)(v.p[2] * f); }
            result.push_back(n);
        }
        cpu_.swap(result);
        undo_.clear();
        redo_.clear();
        tsel_.clear();
        vsel_.clear();
        preview_.clear();
        previewDirty_ = true;
        pmVerts_.clear();
        pmDirty_ = true;
        rebuildGpu_ = true;
        recomputeParts(sink);
    }
    sink->scaleAll(f);
    {
        std::lock_guard<std::mutex> l(mu_);
        bounds_ = sink->bounds();
    }
    return true;
}

}  // namespace vx
