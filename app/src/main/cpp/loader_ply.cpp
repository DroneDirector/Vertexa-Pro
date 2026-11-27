// Streaming PLY loader: ASCII, binary little/big endian. Meshes and point clouds
// (point clouds are streamed straight to the GPU queue, so any size works).
#include <unistd.h>

#include "loaders.h"
#include "reader.h"

namespace vx {
namespace {

enum class T { I8, U8, I16, U16, I32, U32, F32, F64, Bad };

T typeOf(const std::string& s) {
    if (s == "char" || s == "int8") return T::I8;
    if (s == "uchar" || s == "uint8") return T::U8;
    if (s == "short" || s == "int16") return T::I16;
    if (s == "ushort" || s == "uint16") return T::U16;
    if (s == "int" || s == "int32") return T::I32;
    if (s == "uint" || s == "uint32") return T::U32;
    if (s == "float" || s == "float32") return T::F32;
    if (s == "double" || s == "float64") return T::F64;
    return T::Bad;
}
int sizeOf(T t) {
    switch (t) {
        case T::I8: case T::U8: return 1;
        case T::I16: case T::U16: return 2;
        case T::I32: case T::U32: case T::F32: return 4;
        case T::F64: return 8;
        default: return 0;
    }
}

struct Prop {
    std::string name;
    T type = T::F32;
    bool list = false;
    T countType = T::U8;
};
struct Element {
    std::string name;
    int64_t count = 0;
    std::vector<Prop> props;
};

class Values {
public:
    Values(FdReader& r, int fmt) : r_(r), fmt_(fmt) {}  // 0 ascii, 1 LE, 2 BE
    bool ok = true;
    double get(T t) {
        if (fmt_ == 0) {
            while (true) {
                if (cur_ && hasToken(cur_)) return parseDouble(cur_);
                cur_ = r_.line();
                if (!cur_) { ok = false; return 0; }
            }
        }
        uint8_t b[8];
        int n = sizeOf(t);
        if (!r_.read(b, n)) { ok = false; return 0; }
        if (fmt_ == 2) std::reverse(b, b + n);
        switch (t) {
            case T::I8: return (double)(int8_t)b[0];
            case T::U8: return (double)b[0];
            case T::I16: { int16_t v; memcpy(&v, b, 2); return v; }
            case T::U16: { uint16_t v; memcpy(&v, b, 2); return v; }
            case T::I32: { int32_t v; memcpy(&v, b, 4); return v; }
            case T::U32: { uint32_t v; memcpy(&v, b, 4); return v; }
            case T::F32: { float v; memcpy(&v, b, 4); return v; }
            case T::F64: { double v; memcpy(&v, b, 8); return v; }
            default: return 0;
        }
    }
    // ASCII: each element sits on its own line; drop leftovers.
    void endElement() { if (fmt_ == 0) cur_ = nullptr; }

private:
    FdReader& r_;
    int fmt_;
    const char* cur_ = nullptr;
};

float colorScale(T t) {
    if (t == T::F32 || t == T::F64) return 1.f;
    if (t == T::U16 || t == T::I16) return 1.f / 65535.f;
    return 1.f / 255.f;
}

}  // namespace

std::string loadPly(int fd, int64_t size, Sink& sink, Resolver& res) {
    FdReader r(fd, size, &sink);
    char* ln = r.line();
    if (!ln || strncmp(ln, "ply", 3) != 0) return "Not a valid PLY file.";
    int fmt = -1;
    std::vector<Element> els;
    std::string textureFile;
    while ((ln = r.line())) {
        const char* s = skipWs(ln);
        if (!strncmp(s, "end_header", 10)) break;
        std::vector<std::string> tk;
        {
            std::string t;
            for (const char* p = s; *p; p++) {
                if (*p == ' ' || *p == '\t') { if (!t.empty()) tk.push_back(t); t.clear(); }
                else t += *p;
            }
            if (!t.empty()) tk.push_back(t);
        }
        if (tk.empty()) continue;
        if (tk[0] == "format" && tk.size() > 1) {
            fmt = tk[1] == "ascii" ? 0 : tk[1] == "binary_little_endian" ? 1 : tk[1] == "binary_big_endian" ? 2 : -1;
        } else if (tk[0] == "element" && tk.size() > 2) {
            Element e;
            e.name = tk[1];
            e.count = atoll(tk[2].c_str());
            els.push_back(e);
        } else if (tk[0] == "property" && !els.empty()) {
            Prop p;
            if (tk.size() > 4 && tk[1] == "list") {
                p.list = true;
                p.countType = typeOf(tk[2]);
                p.type = typeOf(tk[3]);
                p.name = tk[4];
            } else if (tk.size() > 2) {
                p.type = typeOf(tk[1]);
                p.name = tk[2];
            }
            if (p.type == T::Bad || p.countType == T::Bad) return "Unsupported PLY property type.";
            els.back().props.push_back(p);
        } else if (tk[0] == "comment" && tk.size() > 2 &&
                   (tk[1] == "TextureFile" || tk[1] == "texturefile")) {
            textureFile = trim(std::string(s).substr(std::string(s).find(tk[1]) + tk[1].size()));
        }
    }
    if (fmt < 0) return "Unknown PLY format.";
    sink.log(0, std::string("PLY format: ") + (fmt == 0 ? "ASCII" : fmt == 1 ? "binary little-endian" : "binary big-endian"));
    for (auto& e : els) {
        std::string props;
        for (auto& pr : e.props) props += (props.empty() ? "" : ", ") + pr.name;
        sink.log(0, "Element \"" + e.name + "\" × " + std::to_string(e.count) + " (" + props + ")");
    }
    if (!textureFile.empty()) sink.log(0, "Texture named in header: " + textureFile);

    int64_t faceCount = 0;
    for (auto& e : els) if (e.name == "face") faceCount += e.count;

    Material m;
    m.name = "PLY";
    m.texture = textureFile;
    int mat = sink.addMaterial(m);
    Values val(r, fmt);

    std::vector<Vertex> verts;
    std::vector<bool> hasNormal;
    bool anyNormals = false;
    bool haveOrigin = false;
    double o[3] = {0, 0, 0};
    std::unique_ptr<Builder> points;
    if (faceCount == 0) points = std::make_unique<Builder>(sink, mat, true);
    std::unique_ptr<Builder> mesh;

    std::vector<double> pv;
    std::vector<uint32_t> fidx;
    std::vector<float> ftc;

    for (auto& e : els) {
        if (e.name == "vertex") {
            int ix = -1, iy = -1, iz = -1, inx = -1, iny = -1, inz = -1, iu = -1, iv = -1, ir = -1, ig = -1, ib = -1, ia = -1;
            for (int i = 0; i < (int)e.props.size(); i++) {
                const std::string& n = e.props[i].name;
                if (n == "x") ix = i; else if (n == "y") iy = i; else if (n == "z") iz = i;
                else if (n == "nx") inx = i; else if (n == "ny") iny = i; else if (n == "nz") inz = i;
                else if (n == "u" || n == "s" || n == "texture_u" || n == "texture_s") iu = i;
                else if (n == "v" || n == "t" || n == "texture_v" || n == "texture_t") iv = i;
                else if (n == "red" || n == "diffuse_red" || n == "r") ir = i;
                else if (n == "green" || n == "diffuse_green" || n == "g") ig = i;
                else if (n == "blue" || n == "diffuse_blue" || n == "b") ib = i;
                else if (n == "alpha" || n == "diffuse_alpha" || n == "a") ia = i;
            }
            if (ix < 0 || iy < 0 || iz < 0) return "PLY vertices have no x/y/z.";
            bool hn = inx >= 0 && iny >= 0 && inz >= 0;
            anyNormals = hn;
            if (!points) {
                verts.reserve((size_t)e.count);
            }
            pv.resize(e.props.size());
            for (int64_t k = 0; k < e.count; k++) {
                if ((k & 0xFFFF) == 0 && r.cancelled()) return "cancelled";
                for (size_t i = 0; i < e.props.size(); i++) {
                    auto& p = e.props[i];
                    if (p.list) {
                        int cnt = (int)val.get(p.countType);
                        for (int j = 0; j < cnt; j++) val.get(p.type);
                        pv[i] = 0;
                    } else pv[i] = val.get(p.type);
                }
                val.endElement();
                if (!val.ok) break;
                if (!haveOrigin) {
                    o[0] = pv[ix]; o[1] = pv[iy]; o[2] = pv[iz];
                    sink.origin[0] = o[0]; sink.origin[1] = o[1]; sink.origin[2] = o[2];
                    haveOrigin = true;
                }
                Vertex v;
                v.p[0] = (float)(pv[ix] - o[0]);
                v.p[1] = (float)(pv[iy] - o[1]);
                v.p[2] = (float)(pv[iz] - o[2]);
                v.n = hn ? packNormal((float)pv[inx], (float)pv[iny], (float)pv[inz]) : packNormal(0, 1, 0);
                v.uv[0] = iu >= 0 ? (float)pv[iu] : 0.f;
                v.uv[1] = iv >= 0 ? (float)pv[iv] : 0.f;
                if (ir >= 0 && ig >= 0 && ib >= 0) {
                    float sc = colorScale(e.props[ir].type);
                    v.c = packColor((float)pv[ir] * sc, (float)pv[ig] * sc, (float)pv[ib] * sc,
                                    ia >= 0 ? (float)pv[ia] * colorScale(e.props[ia].type) : 1.f);
                } else v.c = kWhite;
                if (points) {
                    points->reserveFace(1);
                    points->raw(v, true);
                } else {
                    verts.push_back(v);
                }
            }
            if (points) points->flush();
        } else if (e.name == "face") {
            if (!mesh) mesh = std::make_unique<Builder>(sink, mat);
            int ii = -1, it = -1;
            for (int i = 0; i < (int)e.props.size(); i++) {
                const std::string& n = e.props[i].name;
                if (e.props[i].list && (n == "vertex_indices" || n == "vertex_index")) ii = i;
                else if (e.props[i].list && (n == "texcoord" || n == "texcoords")) it = i;
            }
            for (int64_t k = 0; k < e.count; k++) {
                if ((k & 0xFFFF) == 0 && r.cancelled()) return "cancelled";
                fidx.clear();
                ftc.clear();
                for (int i = 0; i < (int)e.props.size(); i++) {
                    auto& p = e.props[i];
                    if (p.list) {
                        int cnt = (int)val.get(p.countType);
                        for (int j = 0; j < cnt; j++) {
                            double d = val.get(p.type);
                            if (i == ii) fidx.push_back((uint32_t)(int64_t)d);
                            else if (i == it) ftc.push_back((float)d);
                        }
                    } else val.get(p.type);
                }
                val.endElement();
                if (!val.ok) break;
                if (fidx.size() < 3) continue;
                bool okIdx = true;
                for (uint32_t x : fidx) if (x >= verts.size()) { okIdx = false; break; }
                if (!okIdx) continue;
                bool perCornerUv = ftc.size() >= fidx.size() * 2;
                mesh->reserveFace(fidx.size());
                uint32_t loc[64];
                size_t n = std::min<size_t>(fidx.size(), 64);
                for (size_t j = 0; j < n; j++) {
                    if (perCornerUv) {
                        Vertex v = verts[fidx[j]];
                        v.uv[0] = ftc[j * 2];
                        v.uv[1] = ftc[j * 2 + 1];
                        // de-dup on (vertex, quantised uv)
                        uint32_t qu, qv;
                        memcpy(&qu, &v.uv[0], 4);
                        memcpy(&qv, &v.uv[1], 4);
                        loc[j] = mesh->vertex(fidx[j], qu, qv ^ 0x5bd1e995u, v, anyNormals);
                    } else {
                        loc[j] = mesh->vertex(fidx[j], 0, 0, verts[fidx[j]], anyNormals);
                    }
                }
                for (size_t j = 1; j + 1 < n; j++) mesh->tri(loc[0], loc[j], loc[j + 1]);
                for (size_t j = 0; j < n; j++) mesh->line(loc[j], loc[(j + 1) % n]);
            }
        } else {
            // skip unknown element
            for (int64_t k = 0; k < e.count; k++) {
                for (auto& p : e.props) {
                    if (p.list) {
                        int cnt = (int)val.get(p.countType);
                        for (int j = 0; j < cnt; j++) val.get(p.type);
                    } else val.get(p.type);
                }
                val.endElement();
                if (!val.ok) break;
            }
        }
        if (!val.ok) break;
    }
    if (mesh) mesh->flush();
    if (!val.ok) sink.log(1, "The file ended before all elements listed in the header were read (file may be cut off).");
    if (faceCount == 0) sink.log(0, "No faces: shown as a point cloud.");
    if (sink.vertexCount.load() == 0 && !verts.empty()) {
        // vertices but no usable faces -> show as points
        Builder pb(sink, mat, true);
        for (auto& v : verts) { pb.reserveFace(1); pb.raw(v, true); }
        pb.flush();
    }
    if (sink.vertexCount.load() == 0) return "No geometry found in this PLY file.";
    (void)res;
    return "";
}

}  // namespace vx
