// Streaming Wavefront OBJ + MTL loader. Handles multi-gigabyte files: the file is
// read in 8 MB blocks, faces are turned into GPU-ready chunks as they are parsed.
// Everything unusual is written to the load report so problems can be tracked down.
#include <unistd.h>

#include <map>
#include <unordered_map>

#include "loaders.h"
#include "reader.h"

namespace vx {
namespace {

std::string clip(const char* s, size_t n = 110) {
    std::string out;
    for (const char* p = s; *p && out.size() < n; p++) {
        unsigned char c = (unsigned char)*p;
        out += (c < 32 || c >= 127) ? '?' : (char)c;
    }
    if (strlen(s) > n) out += "…";
    return out;
}

std::string lastToken(const char* s) {
    // map_Kd may carry options (-s 1 1 1 -bm 0.5 ...). The file name is the rest after options.
    std::string str = trim(s);
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
    size_t i = 0;
    while (i < toks.size()) {
        auto it = opts.find(toks[i]);
        if (it == opts.end()) break;
        i += 1 + it->second;
        if (i > toks.size()) i = toks.size();
    }
    std::string out;
    for (size_t k = i; k < toks.size(); k++) {
        if (!out.empty()) out += ' ';
        out += toks[k];
    }
    if (out.empty() && !toks.empty()) out = toks.back();
    return out;
}

int parseMtl(int fd, const std::string& file, Sink& sink, std::unordered_map<std::string, int>& byName) {
    FdReader r(fd, 0, nullptr, true);
    int cur = -1, count = 0, textures = 0;
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
            count++;
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
            textures++;
        } else if (m.texture.empty() &&
                   (!strncmp(s, "map_Ka", 6) || !strncmp(s, "map_BaseColor", 13) ||
                    !strncmp(s, "map_basecolor", 13))) {
            m.texture = lastToken(s + (s[4] == 'K' || s[4] == 'k' ? 6 : 13));
            textures++;
        }
    }
    commit();
    ::close(fd);
    sink.log(0, "Material file \"" + file + "\": " + std::to_string(count) + " materials, " +
                    std::to_string(textures) + " texture references");
    if (r.utf16()) sink.log(1, "Material file is UTF-16 text; it was converted while reading.");
    return count;
}

enum FaceResult { FACE_OK, FACE_DEFER, FACE_FEW, FACE_BAD };

}  // namespace

std::string loadObj(int fd, int64_t size, Sink& sink, Resolver& res) {
    FdReader r(fd, size, &sink, true);
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

    // ---- diagnostics
    int64_t nV = 0, nVt = 0, nVn = 0, nF = 0, nL = 0, nUse = 0, nP = 0;
    int64_t skippedFew = 0, skippedBad = 0, badCorners = 0, deferredCount = 0, polys = 0, commaNums = 0;
    int64_t nanVerts = 0;
    int maxCorners = 0;
    std::map<std::string, int64_t> unknown;
    std::vector<std::string> samples;
    auto sample = [&](size_t line, const char* what, const char* text) {
        if (samples.size() < 8) samples.push_back("line " + std::to_string(line) + " (" + what + "): " + clip(text));
    };

    struct Ref { long v, t, n; };
    std::vector<Ref> face;
    std::vector<uint32_t> idx;
    std::vector<std::pair<size_t, std::string>> deferred;  // faces that point at vertices defined later
    size_t lineNo = 0;

    // Parses one face line. final=true: no more vertices will come.
    auto doFace = [&](const char* p, bool final, int mat) -> FaceResult {
        face.clear();
        long nv = (long)pos.size() / 3, nt = (long)uv.size() / 2, nn = (long)nrm.size() / 3;
        bool future = false;
        int corners = 0;
        while (true) {
            p = skipWs(p);
            if (!*p || *p == '#') break;
            Ref rf{0, 0, 0};
            const char* st = p;
            rf.v = parseInt(p);
            if (*p == '/') {
                p++;
                if (*p != '/') rf.t = parseInt(p);
                if (*p == '/') { p++; rf.n = parseInt(p); }
            }
            bool junk = (p == st);
            while (*p && *p != ' ' && *p != '\t') { p++; junk = true; }
            corners++;
            if (rf.v < 0) rf.v = nv + rf.v + 1;
            if (rf.t < 0) rf.t = nt + rf.t + 1;
            if (rf.n < 0) rf.n = nn + rf.n + 1;
            if (rf.v > nv && !final) { future = true; continue; }
            if (junk || rf.v < 1 || rf.v > nv) { badCorners++; continue; }
            if (rf.t < 1 || rf.t > nt) rf.t = 0;
            if (rf.n < 1 || rf.n > nn) rf.n = 0;
            face.push_back(rf);
        }
        if (future) return FACE_DEFER;
        if (corners > maxCorners) maxCorners = corners;
        if (face.size() < 3) return corners >= 3 ? FACE_BAD : FACE_FEW;
        if (face.size() > 4) polys++;
        Builder* b = &builderFor(mat);
        b->reserveFace(face.size());
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
            idx.push_back(b->vertex((uint32_t)rf.v, (uint32_t)rf.t, (uint32_t)rf.n, v, hn));
        }
        for (size_t k = 1; k + 1 < idx.size(); k++) b->tri(idx[0], idx[k], idx[k + 1]);
        for (size_t k = 0; k < idx.size(); k++) b->line(idx[k], idx[(k + 1) % idx.size()]);
        return FACE_OK;
    };
    int curMat = defMat;

    char* ln;
    while ((ln = r.line())) {
        ++lineNo;
        if ((lineNo & 0xFFFF) == 0 && r.cancelled()) return "cancelled";
        const char* s = ln;
        while (*s == ' ' || *s == '\t') s++;
        char c0 = s[0];
        if (!c0 || c0 == '#') continue;
        if (c0 == 'v' && (s[1] == ' ' || s[1] == '\t')) {
            nV++;
            const char* p = s + 2;
            if (strchr(p, ',')) commaNums++;
            double x = parseDouble(p), y = parseDouble(p), z = parseDouble(p);
            if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) {
                nanVerts++;
                x = y = z = 0;
            }
            if (!haveOrigin) {
                o[0] = x; o[1] = y; o[2] = z; haveOrigin = true;
                sink.origin[0] = x; sink.origin[1] = y; sink.origin[2] = z;
            }
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
        } else if (c0 == 'v' && s[1] == 't' && (s[2] == ' ' || s[2] == '\t')) {
            nVt++;
            const char* p = s + 2;
            uv.push_back((float)parseDouble(p));
            uv.push_back(hasToken(p) ? (float)parseDouble(p) : 0.f);
        } else if (c0 == 'v' && s[1] == 'n' && (s[2] == ' ' || s[2] == '\t')) {
            nVn++;
            const char* p = s + 2;
            nrm.push_back((float)parseDouble(p));
            nrm.push_back((float)parseDouble(p));
            nrm.push_back((float)parseDouble(p));
        } else if (c0 == 'f' && (s[1] == ' ' || s[1] == '\t' ||
                                 (s[1] == 'o' && (s[2] == ' ' || s[2] == '\t')))) {  // "fo" = old face keyword
            nF++;
            const char* p = s + (s[1] == 'o' ? 2 : 1);
            FaceResult fr = doFace(p, false, curMat);
            if (fr == FACE_DEFER) {
                deferred.emplace_back(lineNo, std::string(p) + "\x01" + std::to_string(curMat));
                deferredCount++;
            } else if (fr == FACE_FEW) {
                skippedFew++;
                sample(lineNo, "face with fewer than 3 corners", s);
            } else if (fr == FACE_BAD) {
                skippedBad++;
                sample(lineNo, "face with invalid vertex numbers", s);
            }
        } else if (c0 == 'l' && (s[1] == ' ' || s[1] == '\t')) {
            nL++;
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
            nUse++;
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
            curMat = mi;
        } else if (!strncmp(s, "mtllib", 6)) {
            std::string files = trim(s + 6);
            std::vector<std::string> names;
            std::string lower = files;
            for (auto& ch : lower) ch = (char)std::tolower((unsigned char)ch);
            if (lower.size() > 4 && lower.compare(lower.size() - 4, 4, ".mtl") == 0 &&
                lower.find(".mtl ") == std::string::npos) {
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
                if (mfd >= 0) parseMtl(mfd, n, sink, matByName);
                else sink.log(1, "Material file \"" + n + "\" was not found. Pick it together with the model, or open the model from its folder, to get materials and textures.");
            }
        } else if (c0 == 'p' && (s[1] == ' ' || s[1] == '\t')) {
            nP++;
        } else {
            // known harmless statements
            static const char* ok[] = {"o", "g", "s", "vp", "cstype", "deg", "curv", "curv2", "surf", "parm",
                                       "trim", "hole", "scrv", "sp", "end", "con", "mg", "bevel", "c_interp",
                                       "d_interp", "lod", "shadow_obj", "trace_obj", "ctech", "stech", "call", "csh"};
            std::string kw;
            for (const char* q = s; *q && *q != ' ' && *q != '\t' && kw.size() < 24; q++) kw += *q;
            bool known = false;
            for (const char* k : ok) if (kw == k) { known = true; break; }
            if (!known) {
                if (unknown[kw]++ == 0) sample(lineNo, "unrecognised line", s);
            }
        }
    }

    // faces that referenced vertices defined further down the file
    if (!deferred.empty()) {
        sink.log(1, std::to_string(deferredCount) + " faces refer to vertices that come later in the file; they were read in a second pass.");
        for (auto& d : deferred) {
            size_t sep = d.second.rfind('\x01');
            int mat = atoi(d.second.c_str() + sep + 1);
            d.second.resize(sep);
            FaceResult fr = doFace(d.second.c_str(), true, mat);
            if (fr == FACE_FEW || fr == FACE_BAD) {
                skippedBad++;
                sample(d.first, "face pointing past the last vertex", d.second.c_str());
            }
        }
        deferred.clear();
    }
    for (auto& kv : builders) kv.second->flush();

    // ---- report
    sink.log(0, "Lines read: " + std::to_string(lineNo));
    sink.log(0, "Vertex positions (v): " + std::to_string(nV) + " · texture coords (vt): " + std::to_string(nVt) +
                    " · normals (vn): " + std::to_string(nVn));
    sink.log(0, "Faces (f): " + std::to_string(nF) + (nL ? " · lines (l): " + std::to_string(nL) : "") +
                    (nP ? " · points (p): " + std::to_string(nP) : "") + " · material switches: " + std::to_string(nUse));
    if (maxCorners > 4) sink.log(0, std::to_string(polys) + " faces have more than 4 corners (largest: " + std::to_string(maxCorners) + "); they were split into triangles.");
    if (r.hadBom() && !r.utf16()) sink.log(0, "File starts with a UTF-8 byte-order mark (ignored).");
    if (r.utf16()) sink.log(1, "The file is saved as UTF-16 text instead of plain text; it was converted while reading.");
    if (r.crOnly()) sink.log(1, "The file uses old Mac line endings (CR only); they were converted while reading.");
    if (commaNums) sink.log(1, std::to_string(commaNums) + " vertex lines use commas; they were read as decimal commas (e.g. 0,5 = 0.5).");
    if (nanVerts) sink.log(1, std::to_string(nanVerts) + " vertices had invalid numbers (NaN/inf) and were placed at the origin.");
    if (skippedFew) sink.log(1, std::to_string(skippedFew) + " faces had fewer than 3 corners and were skipped.");
    if (skippedBad) sink.log(2, std::to_string(skippedBad) + " faces used vertex numbers that don't exist in the file and were skipped" +
                                    (badCorners ? " (" + std::to_string(badCorners) + " bad corners)." : "."));
    if (nF > 0 && nVt == 0) sink.log(0, "The model has no texture coordinates (vt), so textures can't be shown on it.");
    if (nVn == 0 && nF > 0) sink.log(0, "No normals in the file; smooth normals were calculated.");
    if (!unknown.empty()) {
        std::string kws;
        int shown = 0;
        for (auto& kv : unknown) {
            if (shown++ >= 8) { kws += ", …"; break; }
            if (!kws.empty()) kws += ", ";
            kws += "\"" + kv.first + "\" ×" + std::to_string(kv.second);
        }
        sink.log(1, "Unrecognised line types (ignored): " + kws);
    }
    for (auto& smp : samples) sink.log(1, "Example — " + smp);
    if (nV > 0 && nF == 0 && nL == 0) sink.log(1, "The file has vertices but no faces, so it is shown as a point cloud.");
    if (size > 50 * 1024 && nV < 16) {
        sink.log(2, "Only " + std::to_string(nV) + " vertices were found in a " + std::to_string(size / 1024) +
                        " KB file. The file is probably not a normal text OBJ (it may be compressed, binary, or a different format with an .obj name).");
    }

    if (sink.vertexCount.load() == 0) {
        if (!pos.empty()) {
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
