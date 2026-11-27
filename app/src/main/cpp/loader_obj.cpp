// Streaming Wavefront OBJ + MTL loader. Handles multi-gigabyte files: the file is
// read in 8 MB blocks, faces are turned into GPU-ready chunks as they are parsed.
#include <unistd.h>

#include <map>
#include <unordered_map>

#include "loaders.h"
#include "reader.h"

namespace vx {
namespace {

std::string lastToken(const char* s) {
    // map_Kd may carry options (-s 1 1 1 -bm 0.5 ...). The file name is the rest after options.
    std::string str = trim(s);
    // Strip known options with arguments
    size_t i = 0;
    std::string out;
    std::vector<std::string> toks;
    std::string cur;
    for (char c : str) {
        if (c == ' ' || c == '\t') {
            if (!cur.empty()) { toks.push_back(cur); cur.clear(); }
        } else cur += c;
    }
    if (!cur.empty()) toks.push_back(cur);
    static const std::map<std::string, int> opts = {
        {"-blendu", 1}, {"-blendv", 1}, {"-bm", 1}, {"-boost", 1}, {"-cc", 1}, {"-clamp", 1},
        {"-imfchan", 1}, {"-mm", 2}, {"-o", 3}, {"-s", 3}, {"-t", 3}, {"-texres", 1}, {"-type", 1}};
    while (i < toks.size()) {
        auto it = opts.find(toks[i]);
        if (it == opts.end()) break;
        i += 1 + it->second;
        // -o / -s / -t take 1..3 numbers; back off if we skipped into the file name
        if (i > toks.size()) i = toks.size();
    }
    for (size_t k = i; k < toks.size(); k++) {
        if (!out.empty()) out += ' ';
        out += toks[k];
    }
    if (out.empty() && !toks.empty()) out = toks.back();
    return out;
}

void parseMtl(int fd, Sink& sink, std::unordered_map<std::string, int>& byName) {
    FdReader r(fd, 0, nullptr);
    int cur = -1;
    Material m;
    auto commit = [&]() {
        if (cur >= 0) sink.setMaterial(cur, m);
    };
    char* ln;
    while ((ln = r.line())) {
        const char* s = skipWs(ln);
        if (!strncmp(s, "newmtl", 6) && (s[6] == ' ' || s[6] == '\t')) {
            commit();
            std::string name = trim(s + 7);
            m = Material();
            m.name = name;
            auto it = byName.find(name);
            if (it != byName.end()) cur = it->second;
            else {
                cur = sink.addMaterial(m);
                byName[name] = cur;
            }
        } else if (cur < 0) {
            continue;
        } else if (s[0] == 'K' && s[1] == 'd' && (s[2] == ' ' || s[2] == '\t')) {
            const char* p = s + 2;
            m.kd[0] = (float)parseDouble(p);
            m.kd[1] = hasToken(p) ? (float)parseDouble(p) : m.kd[0];
            m.kd[2] = hasToken(p) ? (float)parseDouble(p) : m.kd[0];
        } else if (s[0] == 'd' && (s[1] == ' ' || s[1] == '\t')) {
            const char* p = s + 1;
            m.kd[3] = (float)parseDouble(p);
        } else if (!strncmp(s, "map_Kd", 6) || !strncmp(s, "map_kd", 6)) {
            m.texture = lastToken(s + 6);
        } else if (m.texture.empty() &&
                   (!strncmp(s, "map_Ka", 6) || !strncmp(s, "map_BaseColor", 13) ||
                    !strncmp(s, "map_basecolor", 13))) {
            m.texture = lastToken(s + (s[4] == 'K' || s[4] == 'k' ? 6 : 13));
        }
    }
    commit();
    ::close(fd);
}

}  // namespace

std::string loadObj(int fd, int64_t size, Sink& sink, Resolver& res) {
    FdReader r(fd, size, &sink);
    std::vector<float> pos;     // xyz relative to origin
    std::vector<uint32_t> col;  // optional per-position colour
    std::vector<float> uv, nrm;
    pos.reserve(1 << 20);
    bool haveOrigin = false;
    double o[3] = {0, 0, 0};

    std::unordered_map<std::string, int> matByName;
    Material def;
    def.name = "default";
    int defMat = sink.addMaterial(def);
    std::map<int, std::unique_ptr<Builder>> builders;
    auto builderFor = [&](int mat) -> Builder& {
        auto& b = builders[mat];
        if (!b) b = std::make_unique<Builder>(sink, mat);
        return *b;
    };
    Builder* cur = &builderFor(defMat);

    struct Ref { long v, t, n; };
    std::vector<Ref> face;
    std::vector<uint32_t> idx;
    size_t lineNo = 0;

    char* ln;
    while ((ln = r.line())) {
        if ((++lineNo & 0xFFFF) == 0 && r.cancelled()) return "cancelled";
        const char* s = ln;
        while (*s == ' ' || *s == '\t') s++;
        char c0 = s[0];
        if (c0 == 'v') {
            char c1 = s[1];
            if (c1 == ' ' || c1 == '\t') {
                const char* p = s + 2;
                double x = parseDouble(p), y = parseDouble(p), z = parseDouble(p);
                if (!haveOrigin) { o[0] = x; o[1] = y; o[2] = z; haveOrigin = true;
                    sink.origin[0] = x; sink.origin[1] = y; sink.origin[2] = z; }
                pos.push_back((float)(x - o[0]));
                pos.push_back((float)(y - o[1]));
                pos.push_back((float)(z - o[2]));
                if (hasToken(p)) {
                    double a = parseDouble(p);
                    if (hasToken(p)) {  // r g b (extension)
                        double g = parseDouble(p), b = parseDouble(p);
                        if (col.size() < pos.size() / 3 - 1) col.resize(pos.size() / 3 - 1, kWhite);
                        if (a > 1.0 || g > 1.0 || b > 1.0) { a /= 255.0; g /= 255.0; b /= 255.0; }
                        col.push_back(packColor((float)a, (float)g, (float)b));
                    }
                }
            } else if (c1 == 't') {
                const char* p = s + 2;
                uv.push_back((float)parseDouble(p));
                uv.push_back(hasToken(p) ? (float)parseDouble(p) : 0.f);
            } else if (c1 == 'n') {
                const char* p = s + 2;
                nrm.push_back((float)parseDouble(p));
                nrm.push_back((float)parseDouble(p));
                nrm.push_back((float)parseDouble(p));
            }
        } else if (c0 == 'f' && (s[1] == ' ' || s[1] == '\t')) {
            face.clear();
            const char* p = s + 1;
            long nv = (long)pos.size() / 3, nt = (long)uv.size() / 2, nn = (long)nrm.size() / 3;
            while (true) {
                p = skipWs(p);
                if (!*p || *p == '#') break;
                Ref rf{0, 0, 0};
                rf.v = parseInt(p);
                if (*p == '/') {
                    p++;
                    if (*p != '/') rf.t = parseInt(p);
                    if (*p == '/') { p++; rf.n = parseInt(p); }
                }
                while (*p && *p != ' ' && *p != '\t') p++;
                if (rf.v < 0) rf.v = nv + rf.v + 1;
                if (rf.t < 0) rf.t = nt + rf.t + 1;
                if (rf.n < 0) rf.n = nn + rf.n + 1;
                if (rf.v < 1 || rf.v > nv) continue;
                if (rf.t < 1 || rf.t > nt) rf.t = 0;
                if (rf.n < 1 || rf.n > nn) rf.n = 0;
                face.push_back(rf);
            }
            if (face.size() < 3) continue;
            cur->reserveFace(face.size());
            idx.clear();
            for (auto& rf : face) {
                Vertex v;
                size_t pi = (size_t)(rf.v - 1) * 3;
                v.p[0] = pos[pi]; v.p[1] = pos[pi + 1]; v.p[2] = pos[pi + 2];
                bool hn = rf.n > 0;
                if (hn) {
                    size_t ni = (size_t)(rf.n - 1) * 3;
                    v.n = packNormal(nrm[ni], nrm[ni + 1], nrm[ni + 2]);
                } else v.n = packNormal(0, 1, 0);
                if (rf.t > 0) {
                    size_t ti = (size_t)(rf.t - 1) * 2;
                    v.uv[0] = uv[ti]; v.uv[1] = uv[ti + 1];
                } else { v.uv[0] = 0; v.uv[1] = 0; }
                v.c = ((size_t)(rf.v - 1) < col.size()) ? col[rf.v - 1] : kWhite;
                idx.push_back(cur->vertex((uint32_t)rf.v, (uint32_t)rf.t, (uint32_t)rf.n, v, hn));
            }
            for (size_t k = 1; k + 1 < idx.size(); k++) cur->tri(idx[0], idx[k], idx[k + 1]);
            for (size_t k = 0; k < idx.size(); k++) cur->line(idx[k], idx[(k + 1) % idx.size()]);
        } else if (c0 == 'l' && (s[1] == ' ' || s[1] == '\t')) {
            const char* p = s + 1;
            long nv = (long)pos.size() / 3;
            std::vector<long> vs;
            while (hasToken(p)) {
                p = skipWs(p);
                long v = parseInt(p);
                while (*p && *p != ' ' && *p != '\t') p++;
                if (v < 0) v = nv + v + 1;
                if (v >= 1 && v <= nv) vs.push_back(v);
            }
            if (vs.size() < 2) continue;
            cur->reserveFace(vs.size());
            uint32_t prev = UINT32_MAX;
            for (long v : vs) {
                Vertex vt{};
                size_t pi = (size_t)(v - 1) * 3;
                vt.p[0] = pos[pi]; vt.p[1] = pos[pi + 1]; vt.p[2] = pos[pi + 2];
                vt.n = packNormal(0, 1, 0);
                vt.c = kWhite;
                uint32_t i = cur->vertex((uint32_t)v, 0xFFFFFFF0u, 0, vt, true);
                if (prev != UINT32_MAX) cur->line(prev, i);
                prev = i;
            }
        } else if (!strncmp(s, "usemtl", 6)) {
            std::string name = trim(s + 6);
            auto it = matByName.find(name);
            int mi;
            if (it == matByName.end()) {
                Material m;
                m.name = name;
                mi = sink.addMaterial(m);
                matByName[name] = mi;
            } else mi = it->second;
            cur = &builderFor(mi);
        } else if (!strncmp(s, "mtllib", 6)) {
            std::string files = trim(s + 6);
            // usually one file; names with spaces are kept whole if they end in .mtl
            std::vector<std::string> names;
            if (files.size() > 4 && (files.compare(files.size() - 4, 4, ".mtl") == 0 ||
                                     files.compare(files.size() - 4, 4, ".MTL") == 0) &&
                files.find(".mtl ") == std::string::npos && files.find(".MTL ") == std::string::npos) {
                names.push_back(files);
            } else {
                std::string t;
                for (char ch : files) {
                    if (ch == ' ' || ch == '\t') { if (!t.empty()) names.push_back(t); t.clear(); }
                    else t += ch;
                }
                if (!t.empty()) names.push_back(t);
            }
            for (auto& n : names) {
                int mfd = res.open(n);
                if (mfd >= 0) parseMtl(mfd, sink, matByName);
            }
        }
    }
    for (auto& kv : builders) kv.second->flush();
    if (sink.vertexCount.load() == 0) {
        if (!pos.empty()) {
            // OBJ that only contains vertices: show as a point cloud
            Builder pb(sink, defMat, true);
            size_t n = pos.size() / 3;
            for (size_t i = 0; i < n; i++) {
                pb.reserveFace(1);
                Vertex v{};
                v.p[0] = pos[i * 3]; v.p[1] = pos[i * 3 + 1]; v.p[2] = pos[i * 3 + 2];
                v.n = packNormal(0, 1, 0);
                v.c = i < col.size() ? col[i] : kWhite;
                pb.raw(v, true);
            }
            pb.flush();
            return "";
        }
        return "No geometry found in this OBJ file.";
    }
    return "";
}

}  // namespace vx
