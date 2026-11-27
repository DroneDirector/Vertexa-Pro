// Binary glTF (GLB) export: one node per part with its pivot as the node origin, so parts such
// as flaps or doors can be rotated / animated directly in Blender, Unreal or the MSFS tools.
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <sstream>

#include "renderer.h"

namespace vx {
namespace {

std::string jsonEscape(const std::string& s) {
    std::string o;
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += (char)c; }
        else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
        else o += (char)c;
    }
    return o;
}

void unpackN3(uint32_t n, float out[3]) {
    for (int i = 0; i < 3; i++) {
        int v = (int)((n >> (i * 10)) & 0x3FF);
        if (v & 0x200) v -= 0x400;
        out[i] = std::max(-1.f, v / 511.f);
    }
    float l = std::sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
    if (l > 0) for (int i = 0; i < 3; i++) out[i] /= l;
}

struct Bin {
    std::vector<uint8_t> data;
    size_t add(const void* p, size_t n) {
        while (data.size() % 4) data.push_back(0);
        size_t off = data.size();
        data.insert(data.end(), (const uint8_t*)p, (const uint8_t*)p + n);
        return off;
    }
};

bool writeAll(int fd, const void* p, size_t n) {
    const uint8_t* b = (const uint8_t*)p;
    while (n) {
        ssize_t w = ::write(fd, b, n);
        if (w <= 0) return false;
        b += w;
        n -= (size_t)w;
    }
    return true;
}

}  // namespace

std::string Renderer::exportGlb(int fd, const std::vector<float>& pivots, const std::vector<std::vector<uint8_t>>& images,
                                const std::vector<std::string>& mimes, const std::vector<uint8_t>& hidden, Sink* sink) {
    if (!sink) { ::close(fd); return "No model loaded."; }
    bool upZ;
    {
        std::lock_guard<std::mutex> l(mu_);
        upZ = upZ_;
    }
    std::vector<PartInfo> parts = sink->parts();
    std::vector<Material> mats = sink->materials();
    const double* org = sink->origin;
    // to glTF space (Y up): Z-up models are turned upright
    auto toOut = [&](double x, double y, double z, double out[3]) {
        if (upZ) { out[0] = x; out[1] = z; out[2] = -y; }
        else { out[0] = x; out[1] = y; out[2] = z; }
    };

    struct Prim { int material; std::vector<float> pos, nrm, uv; std::vector<uint8_t> col; std::vector<uint32_t> idx; bool hasCol = false; };
    std::map<int, std::map<int, Prim>> byPart;  // part -> material -> primitive
    {
        std::lock_guard<std::mutex> dl(dataMu_);
        if (!keepCpu_) { ::close(fd); return "no-memory-copy"; }
        for (auto& cp : cpu_) {
            const Chunk& c = *cp;
            if (c.points || c.tris.empty()) continue;
            if (c.part >= 0 && c.part < (int)hidden.size() && hidden[c.part]) continue;
            Prim& pr = byPart[c.part][c.material];
            pr.material = c.material;
            uint32_t base = (uint32_t)(pr.pos.size() / 3);
            for (auto& v : c.verts) {
                double o[3];
                toOut(v.p[0] + org[0], v.p[1] + org[1], v.p[2] + org[2], o);
                pr.pos.insert(pr.pos.end(), {(float)o[0], (float)o[1], (float)o[2]});  // pivot subtracted later
                float n[3];
                unpackN3(v.n, n);
                double no[3];
                toOut(n[0], n[1], n[2], no);
                pr.nrm.insert(pr.nrm.end(), {(float)no[0], (float)no[1], (float)no[2]});
                pr.uv.insert(pr.uv.end(), {v.uv[0], 1.f - v.uv[1]});
                pr.col.insert(pr.col.end(), {(uint8_t)(v.c & 255), (uint8_t)((v.c >> 8) & 255), (uint8_t)((v.c >> 16) & 255), (uint8_t)((v.c >> 24) & 255)});
                if (v.c != kWhite) pr.hasCol = true;
            }
            for (uint32_t i : c.tris) pr.idx.push_back(base + i);
        }
    }
    if (byPart.empty()) { ::close(fd); return "Nothing visible to save."; }

    Bin bin;
    std::ostringstream bv, acc, meshes, nodes, matsJ, imagesJ, texJ;
    acc.precision(9);
    nodes.precision(12);
    matsJ.precision(6);
    int nBv = 0, nAcc = 0, nMesh = 0, nNode = 0;
    auto addView = [&](size_t off, size_t len, int target) {
        if (nBv) bv << ",";
        bv << "{\"buffer\":0,\"byteOffset\":" << off << ",\"byteLength\":" << len;
        if (target) bv << ",\"target\":" << target;
        bv << "}";
        return nBv++;
    };
    auto addAcc = [&](int view, int compType, size_t count, const char* type, bool normalized, const float* mn, const float* mx) {
        if (nAcc) acc << ",";
        acc << "{\"bufferView\":" << view << ",\"componentType\":" << compType << ",\"count\":" << count << ",\"type\":\"" << type << "\"";
        if (normalized) acc << ",\"normalized\":true";
        if (mn && mx) acc << ",\"min\":[" << mn[0] << "," << mn[1] << "," << mn[2] << "],\"max\":[" << mx[0] << "," << mx[1] << "," << mx[2] << "]";
        acc << "}";
        return nAcc++;
    };

    // materials and embedded images
    std::map<int, int> matIndex;  // our material -> glTF material
    int nMat = 0, nImg = 0;
    auto materialFor = [&](int m) {
        auto it = matIndex.find(m);
        if (it != matIndex.end()) return it->second;
        float kd[4] = {0.8f, 0.8f, 0.8f, 1.f};
        std::string name = m < 0 ? "cut_edge" : (m < (int)mats.size() && !mats[m].name.empty() ? mats[m].name : "material_" + std::to_string(m));
        if (m >= 0 && m < (int)mats.size()) memcpy(kd, mats[m].kd, sizeof kd);
        int tex = -1;
        if (m >= 0 && m < (int)images.size() && !images[m].empty()) {
            size_t off = bin.add(images[m].data(), images[m].size());
            int view = addView(off, images[m].size(), 0);
            if (nImg) { imagesJ << ","; texJ << ","; }
            std::string mime = m < (int)mimes.size() && !mimes[m].empty() ? mimes[m] : "image/png";
            imagesJ << "{\"bufferView\":" << view << ",\"mimeType\":\"" << mime << "\"}";
            texJ << "{\"source\":" << nImg << ",\"sampler\":0}";
            tex = nImg++;
            kd[0] = kd[1] = kd[2] = 1.f;
        }
        if (nMat) matsJ << ",";
        matsJ << "{\"name\":\"" << jsonEscape(name) << "\",\"doubleSided\":true,\"pbrMetallicRoughness\":{\"baseColorFactor\":["
              << kd[0] << "," << kd[1] << "," << kd[2] << "," << kd[3] << "],\"metallicFactor\":0,\"roughnessFactor\":0.8";
        if (tex >= 0) matsJ << ",\"baseColorTexture\":{\"index\":" << tex << "}";
        matsJ << "}}";
        matIndex[m] = nMat;
        return nMat++;
    };

    std::ostringstream sceneNodes;
    for (auto& pk : byPart) {
        int part = pk.first;
        // pivot: given hinge point, or the part's centre
        double piv[3];
        bool given = part >= 0 && (size_t)(part * 3 + 2) < pivots.size() && !std::isnan(pivots[part * 3]);
        if (given) {
            toOut(pivots[part * 3] + org[0], pivots[part * 3 + 1] + org[1], pivots[part * 3 + 2] + org[2], piv);
        } else {
            double mn[3] = {1e300, 1e300, 1e300}, mx[3] = {-1e300, -1e300, -1e300};
            for (auto& mk : pk.second)
                for (size_t i = 0; i + 2 < mk.second.pos.size(); i += 3)
                    for (int k = 0; k < 3; k++) { mn[k] = std::min(mn[k], (double)mk.second.pos[i + k]); mx[k] = std::max(mx[k], (double)mk.second.pos[i + k]); }
            for (int k = 0; k < 3; k++) piv[k] = (mn[k] + mx[k]) / 2;
        }
        std::ostringstream prims;
        int nPrim = 0;
        for (auto& mk : pk.second) {
            Prim& pr = mk.second;
            float mn[3] = {1e30f, 1e30f, 1e30f}, mx[3] = {-1e30f, -1e30f, -1e30f};
            for (size_t i = 0; i + 2 < pr.pos.size(); i += 3)
                for (int k = 0; k < 3; k++) {
                    pr.pos[i + k] = (float)(pr.pos[i + k] - piv[k]);
                    mn[k] = std::min(mn[k], pr.pos[i + k]);
                    mx[k] = std::max(mx[k], pr.pos[i + k]);
                }
            size_t count = pr.pos.size() / 3;
            int aPos = addAcc(addView(bin.add(pr.pos.data(), pr.pos.size() * 4), pr.pos.size() * 4, 34962), 5126, count, "VEC3", false, mn, mx);
            int aNrm = addAcc(addView(bin.add(pr.nrm.data(), pr.nrm.size() * 4), pr.nrm.size() * 4, 34962), 5126, count, "VEC3", false, nullptr, nullptr);
            int aUv = addAcc(addView(bin.add(pr.uv.data(), pr.uv.size() * 4), pr.uv.size() * 4, 34962), 5126, count, "VEC2", false, nullptr, nullptr);
            int aCol = -1;
            if (pr.hasCol) aCol = addAcc(addView(bin.add(pr.col.data(), pr.col.size()), pr.col.size(), 34962), 5121, count, "VEC4", true, nullptr, nullptr);
            int aIdx = addAcc(addView(bin.add(pr.idx.data(), pr.idx.size() * 4), pr.idx.size() * 4, 34963), 5125, pr.idx.size(), "SCALAR", false, nullptr, nullptr);
            if (nPrim++) prims << ",";
            prims << "{\"attributes\":{\"POSITION\":" << aPos << ",\"NORMAL\":" << aNrm << ",\"TEXCOORD_0\":" << aUv;
            if (aCol >= 0) prims << ",\"COLOR_0\":" << aCol;
            prims << "},\"indices\":" << aIdx << ",\"material\":" << materialFor(pr.material) << "}";
            std::vector<float>().swap(pr.pos);
            std::vector<float>().swap(pr.nrm);
            std::vector<float>().swap(pr.uv);
            std::vector<uint8_t>().swap(pr.col);
            std::vector<uint32_t>().swap(pr.idx);
        }
        std::string pname = part >= 0 && part < (int)parts.size() ? parts[part].name : "Model";
        if (nMesh) meshes << ",";
        meshes << "{\"name\":\"" << jsonEscape(pname) << "\",\"primitives\":[" << prims.str() << "]}";
        if (nNode) { nodes << ","; sceneNodes << ","; }
        nodes << "{\"name\":\"" << jsonEscape(pname) << "\",\"mesh\":" << nMesh << ",\"translation\":[" << piv[0] << "," << piv[1] << "," << piv[2] << "]}";
        sceneNodes << nNode;
        nMesh++;
        nNode++;
    }

    std::ostringstream js;
    js.precision(9);
    js << "{\"asset\":{\"version\":\"2.0\",\"generator\":\"Vertexa Pro\"},\"scene\":0,\"scenes\":[{\"nodes\":[" << sceneNodes.str()
       << "]}],\"nodes\":[" << nodes.str() << "],\"meshes\":[" << meshes.str() << "],\"materials\":[" << matsJ.str()
       << "],\"accessors\":[" << acc.str() << "],\"bufferViews\":[" << bv.str() << "],\"buffers\":[{\"byteLength\":"
       << ((bin.data.size() + 3) & ~(size_t)3) << "}]";
    if (nImg) js << ",\"images\":[" << imagesJ.str() << "],\"textures\":[" << texJ.str()
                 << "],\"samplers\":[{\"wrapS\":10497,\"wrapT\":10497}]";
    js << "}";
    std::string json = js.str();
    while (json.size() % 4) json += ' ';
    while (bin.data.size() % 4) bin.data.push_back(0);

    uint32_t total = 12 + 8 + (uint32_t)json.size() + 8 + (uint32_t)bin.data.size();
    uint32_t header[3] = {0x46546C67u, 2u, total};
    uint32_t jh[2] = {(uint32_t)json.size(), 0x4E4F534Au};
    uint32_t bh[2] = {(uint32_t)bin.data.size(), 0x004E4942u};
    bool ok = writeAll(fd, header, 12) && writeAll(fd, jh, 8) && writeAll(fd, json.data(), json.size()) &&
              writeAll(fd, bh, 8) && writeAll(fd, bin.data.data(), bin.data.size());
    ::close(fd);
    return ok ? "" : "Writing failed — the storage may be full.";
}

}  // namespace vx
