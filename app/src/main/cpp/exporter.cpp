// Streams a model straight from its loader into an OBJ (+MTL) or binary STL file,
// chunk by chunk, so even multi-gigabyte models can be converted.
#include "exporter.h"

#include <unistd.h>

#include <cstdio>
#include <set>

#include "loaders.h"

namespace vx {
namespace {

void unpackNormal(uint32_t n, float out[3]) {
    for (int i = 0; i < 3; i++) {
        int v = (int)((n >> (i * 10)) & 0x3FF);
        if (v & 0x200) v -= 0x400;
        out[i] = std::max(-1.f, v / 511.f);
    }
}

std::string clean(const std::string& s, const char* fallback) {
    std::string o;
    for (char c : s) o += (c == ' ' || c == '\t' || c == '\n' || c == '\r') ? '_' : c;
    return o.empty() ? std::string(fallback) : o;
}

}  // namespace

std::string runLoader(int fd, int64_t size, const std::string& name, Sink& s, Resolver& res, bool splitLoose) {
    std::string ext;
    size_t dot = name.find_last_of('.');
    if (dot != std::string::npos) ext = name.substr(dot + 1);
    for (auto& c : ext) c = (char)std::tolower((unsigned char)c);
    std::string err;
    try {
        if (ext == "obj") err = loadObj(fd, size, s, res, splitLoose);
        else if (ext == "stl") err = loadStl(fd, size, s);
        else if (ext == "ply") err = loadPly(fd, size, s, res);
        else {
            err = loadAssimp(fd, size, name, s, res);
            fd = -1;
        }
    } catch (const std::bad_alloc&) {
        err = "The device ran out of memory while reading this model.";
    } catch (const std::exception& e) {
        err = e.what();
    } catch (...) {
        err = "Unexpected error while reading the file.";
    }
    if (fd >= 0) ::close(fd);
    return err;
}

std::string exportModel(int inFd, int64_t size, const std::string& name, Resolver& res, const ExportOptions& opt,
                        std::atomic<float>& progress, std::atomic<bool>& cancel) {
    Sink s;
    FILE* out = fdopen(opt.outFd, "wb");
    if (!out) {
        ::close(inFd);
        ::close(opt.outFd);
        if (opt.mtlFd >= 0) ::close(opt.mtlFd);
        return "Could not write the output file.";
    }
    std::vector<char> buf(4 << 20);
    setvbuf(out, buf.data(), _IOFBF, buf.size());

    auto hidden = [&](int part) {
        return part >= 0 && part < (int)opt.hidden.size() && opt.hidden[part];
    };
    int64_t vbase = 0;
    uint32_t stlCount = 0;
    int lastPart = -1, lastMat = -1;
    std::set<int> usedMats;
    bool writeErr = false;

    if (opt.format == ExportOptions::OBJ) {
        fprintf(out, "# Exported by Vertexa Pro\n");
        if (!opt.mtlName.empty()) fprintf(out, "mtllib %s\n", opt.mtlName.c_str());
    } else {
        char header[80] = {0};
        snprintf(header, sizeof header, "Exported by Vertexa Pro");
        fwrite(header, 1, 80, out);
        fwrite(&stlCount, 4, 1, out);
    }

    s.consumer = [&](std::unique_ptr<Chunk> c) {
        if (hidden(c->part) || writeErr) return;
        const double* o = s.origin;
        if (opt.format == ExportOptions::OBJ) {
            if (c->part != lastPart) {
                auto parts = s.parts();
                std::string pn = c->part < (int)parts.size() ? parts[c->part].name : "Model";
                fprintf(out, "g %s\n", clean(pn, "Model").c_str());
                lastPart = c->part;
                lastMat = -1;
            }
            if (c->material != lastMat && !c->points) {
                auto mats = s.materials();
                std::string mn = c->material < (int)mats.size() ? mats[c->material].name : "";
                if (mn.empty()) mn = "material_" + std::to_string(c->material);
                fprintf(out, "usemtl %s\n", clean(mn, "material").c_str());
                lastMat = c->material;
                usedMats.insert(c->material);
            }
            for (auto& v : c->verts) {
                if (v.c != kWhite) {
                    fprintf(out, "v %.9g %.9g %.9g %.4f %.4f %.4f\n", v.p[0] + o[0], v.p[1] + o[1], v.p[2] + o[2],
                            (v.c & 255) / 255.0, ((v.c >> 8) & 255) / 255.0, ((v.c >> 16) & 255) / 255.0);
                } else {
                    fprintf(out, "v %.9g %.9g %.9g\n", v.p[0] + o[0], v.p[1] + o[1], v.p[2] + o[2]);
                }
            }
            if (!c->points) {
                for (auto& v : c->verts) fprintf(out, "vt %.7g %.7g\n", v.uv[0], v.uv[1]);
                float n[3];
                for (auto& v : c->verts) {
                    unpackNormal(v.n, n);
                    fprintf(out, "vn %.4f %.4f %.4f\n", n[0], n[1], n[2]);
                }
                for (size_t t = 0; t + 2 < c->tris.size(); t += 3) {
                    int64_t a = vbase + c->tris[t] + 1, b = vbase + c->tris[t + 1] + 1, d = vbase + c->tris[t + 2] + 1;
                    fprintf(out, "f %lld/%lld/%lld %lld/%lld/%lld %lld/%lld/%lld\n", (long long)a, (long long)a, (long long)a,
                            (long long)b, (long long)b, (long long)b, (long long)d, (long long)d, (long long)d);
                }
                if (c->tris.empty()) {
                    for (size_t l = 0; l + 1 < c->lines.size(); l += 2)
                        fprintf(out, "l %lld %lld\n", (long long)(vbase + c->lines[l] + 1), (long long)(vbase + c->lines[l + 1] + 1));
                }
            }
            vbase += (int64_t)c->verts.size();
        } else {
            for (size_t t = 0; t + 2 < c->tris.size(); t += 3) {
                const float* p0 = c->verts[c->tris[t]].p;
                const float* p1 = c->verts[c->tris[t + 1]].p;
                const float* p2 = c->verts[c->tris[t + 2]].p;
                float e1[3] = {p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]};
                float e2[3] = {p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]};
                float rec[12];
                rec[0] = e1[1] * e2[2] - e1[2] * e2[1];
                rec[1] = e1[2] * e2[0] - e1[0] * e2[2];
                rec[2] = e1[0] * e2[1] - e1[1] * e2[0];
                float l = std::sqrt(rec[0] * rec[0] + rec[1] * rec[1] + rec[2] * rec[2]);
                if (l > 0) { rec[0] /= l; rec[1] /= l; rec[2] /= l; }
                const float* ps[3] = {p0, p1, p2};
                for (int k = 0; k < 3; k++)
                    for (int j = 0; j < 3; j++) rec[3 + k * 3 + j] = (float)(ps[k][j] + o[j]);
                uint16_t attr = 0;
                fwrite(rec, 4, 12, out);
                fwrite(&attr, 2, 1, out);
                stlCount++;
            }
        }
        if (ferror(out)) writeErr = true;
        progress = s.progress.load() * 0.98f;
    };

    std::thread watcher;  // forwards cancel to the loader
    std::atomic<bool> done{false};
    watcher = std::thread([&]() {
        while (!done.load()) {
            if (cancel.load()) s.cancel = true;
            usleep(50000);
        }
    });
    std::string err = runLoader(inFd, size, name, s, res, opt.splitLoose);
    done = true;
    watcher.join();

    fflush(out);
    if (!err.empty()) {
        fclose(out);
        if (opt.mtlFd >= 0) ::close(opt.mtlFd);
        return err == "cancelled" ? err : "Reading failed: " + err;
    }
    if (writeErr) {
        fclose(out);
        if (opt.mtlFd >= 0) ::close(opt.mtlFd);
        return "Writing failed — the storage may be full.";
    }
    if (opt.format == ExportOptions::STL) {
        int fd = fileno(out);
        if (pwrite(fd, &stlCount, 4, 80) != 4) {
            fclose(out);
            return "The STL was written, but its triangle count could not be updated (the storage location doesn't allow it).";
        }
    }
    fclose(out);

    if (opt.format == ExportOptions::OBJ && opt.mtlFd >= 0) {
        FILE* m = fdopen(opt.mtlFd, "w");
        if (m) {
            fprintf(m, "# Exported by Vertexa Pro\n");
            auto mats = s.materials();
            for (int i : usedMats) {
                std::string mn = i < (int)mats.size() ? mats[i].name : "";
                if (mn.empty()) mn = "material_" + std::to_string(i);
                fprintf(m, "\nnewmtl %s\n", clean(mn, "material").c_str());
                const float* kd = i < (int)mats.size() ? mats[i].kd : nullptr;
                fprintf(m, "Kd %.4f %.4f %.4f\n", kd ? kd[0] : 0.8f, kd ? kd[1] : 0.8f, kd ? kd[2] : 0.8f);
                fprintf(m, "d %.4f\n", kd ? kd[3] : 1.f);
                if (i < (int)opt.textures.size() && !opt.textures[i].empty()) fprintf(m, "map_Kd %s\n", opt.textures[i].c_str());
            }
            fclose(m);
        } else {
            ::close(opt.mtlFd);
        }
    }
    progress = 1.f;
    return "";
}

}  // namespace vx
