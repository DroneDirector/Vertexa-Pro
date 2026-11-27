// Streaming STL loader (binary and ASCII). Flat shaded, one vertex per triangle corner.
#include "loaders.h"
#include "reader.h"

namespace vx {
namespace {

struct StlEmitter {
    Sink& sink;
    Builder b;
    bool haveOrigin = false;
    double o[3] = {0, 0, 0};
    explicit StlEmitter(Sink& s, int mat) : sink(s), b(s, mat) {}

    void tri(const float* n, const float v[3][3], uint32_t color) {
        if (!haveOrigin) {
            for (int k = 0; k < 3; k++) o[k] = v[0][k];
            for (int k = 0; k < 3; k++) sink.origin[k] = o[k];
            haveOrigin = true;
        }
        float p[3][3];
        for (int i = 0; i < 3; i++)
            for (int k = 0; k < 3; k++) p[i][k] = (float)((double)v[i][k] - o[k]);
        float e1[3] = {p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]};
        float e2[3] = {p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]};
        float fn[3] = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2],
                       e1[0] * e2[1] - e1[1] * e2[0]};
        float l = fn[0] * fn[0] + fn[1] * fn[1] + fn[2] * fn[2];
        uint32_t pn = l > 1e-30f ? packNormal(fn[0], fn[1], fn[2]) : packNormal(n[0], n[1], n[2]);
        b.reserveFace(3);
        uint32_t idx[3];
        for (int i = 0; i < 3; i++) {
            Vertex vt;
            vt.p[0] = p[i][0]; vt.p[1] = p[i][1]; vt.p[2] = p[i][2];
            vt.n = pn;
            vt.uv[0] = vt.uv[1] = 0;
            vt.c = color;
            idx[i] = b.raw(vt, true);
        }
        b.tri(idx[0], idx[1], idx[2]);
        b.line(idx[0], idx[1]);
        b.line(idx[1], idx[2]);
        b.line(idx[2], idx[0]);
    }
};

}  // namespace

std::string loadStl(int fd, int64_t size, Sink& sink) {
    FdReader r(fd, size, &sink);
    Material m;
    m.name = "STL";
    int mat = sink.addMaterial(m);

    uint8_t head[84] = {0};
    size_t got = r.peek(head, 84);
    bool binary = false;
    uint32_t count = 0;
    if (got == 84) {
        memcpy(&count, head + 80, 4);
        if (size > 0 && (int64_t)84 + (int64_t)count * 50 == size) binary = true;
        else if (strncmp((const char*)head, "solid", 5) != 0) binary = true;
    }
    StlEmitter em(sink, mat);
    sink.log(0, binary ? "Binary STL, header says " + std::to_string(count) + " triangles" : "ASCII (text) STL");
    if (binary && size > 0 && (int64_t)84 + (int64_t)count * 50 != size)
        sink.log(1, "The triangle count in the header doesn't match the file size (" + std::to_string(size) +
                        " bytes); reading until the end of the file.");

    if (binary) {
        r.read(head, 84);
        uint8_t rec[50];
        // Materialise-style colour: header contains "COLOR=" ; per facet bit 15 set = use default
        for (uint32_t t = 0; size <= 0 || t < count; t++) {
            if ((t & 0xFFFF) == 0 && r.cancelled()) return "cancelled";
            if (!r.read(rec, 50)) break;
            float f[12];
            memcpy(f, rec, 48);
            uint16_t attr;
            memcpy(&attr, rec + 48, 2);
            uint32_t color = kWhite;
            if (attr & 0x8000) {  // VisCAM / SolidView colour (5:5:5)
                float rr = ((attr) & 31) / 31.f, gg = ((attr >> 5) & 31) / 31.f,
                      bb = ((attr >> 10) & 31) / 31.f;
                color = packColor(rr, gg, bb);
            }
            float v[3][3] = {{f[3], f[4], f[5]}, {f[6], f[7], f[8]}, {f[9], f[10], f[11]}};
            em.tri(f, v, color);
        }
    } else {
        float n[3] = {0, 0, 1};
        float v[3][3];
        int vi = 0;
        size_t lineNo = 0;
        char* ln;
        while ((ln = r.line())) {
            if ((++lineNo & 0xFFFF) == 0 && r.cancelled()) return "cancelled";
            const char* s = skipWs(ln);
            if (!strncmp(s, "facet", 5)) {
                const char* p = strstr(s, "normal");
                if (p) {
                    p += 6;
                    n[0] = (float)parseDouble(p);
                    n[1] = (float)parseDouble(p);
                    n[2] = (float)parseDouble(p);
                }
                vi = 0;
            } else if (!strncmp(s, "vertex", 6)) {
                const char* p = s + 6;
                if (vi < 3) {
                    v[vi][0] = (float)parseDouble(p);
                    v[vi][1] = (float)parseDouble(p);
                    v[vi][2] = (float)parseDouble(p);
                }
                vi++;
                if (vi == 3) em.tri(n, v, kWhite);
            }
        }
    }
    em.b.flush();
    sink.log(0, "Triangles read: " + std::to_string(sink.triangleCount.load()));
    if (binary && (int64_t)sink.triangleCount.load() < (int64_t)count)
        sink.log(1, "The file ended early: " + std::to_string(count - sink.triangleCount.load()) + " triangles are missing (file may be cut off).");
    if (sink.vertexCount.load() == 0) return "No triangles found in this STL file.";
    return "";
}

}  // namespace vx
