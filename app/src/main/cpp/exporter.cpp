// Streams a model into an OBJ (+MTL) or binary STL file, chunk by chunk, so even
// multi-gigabyte models can be converted. Also used to save edited models from memory.
#include "exporter.h"

#include <unistd.h>

#include <cmath>

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

std::string matName(int i, const std::vector<Material>& mats) {
    if (i < 0) return "cut_edge";
    std::string mn = i < (int)mats.size() ? mats[i].name : "";
    if (mn.empty()) mn = "material_" + std::to_string(i);
    return clean(mn, "material");
}

}  // namespace

bool ChunkWriter::begin() {
    out_ = fdopen(opt_.outFd, "wb");
    if (!out_) {
        ::close(opt_.outFd);
        err_ = true;
        return false;
    }
    buf_.resize(4 << 20);
    setvbuf(out_, buf_.data(), _IOFBF, buf_.size());
    if (opt_.format == ExportOptions::OBJ) {
        fprintf(out_, "# Exported by Vertexa Pro\n");
        if (!opt_.mtlName.empty()) fprintf(out_, "mtllib %s\n", opt_.mtlName.c_str());
    } else {
        char header[80] = {0};
        snprintf(header, sizeof header, "Exported by Vertexa Pro");
        fwrite(header, 1, 80, out_);
        fwrite(&stlCount_, 4, 1, out_);
    }
    return true;
}

void ChunkWriter::write(const Chunk& c, const double* o, const std::vector<PartInfo>& parts,
                        const std::vector<Material>& mats) {
    if (err_ || !out_) return;
    if (c.part >= 0 && c.part < (int)opt_.hidden.size() && opt_.hidden[c.part]) return;
    if (opt_.onlyPart >= 0 && c.part != opt_.onlyPart) return;
    FILE* out = out_;
    if (opt_.format == ExportOptions::OBJ) {
        if (c.part != lastPart_) {
            std::string pn = c.part >= 0 && c.part < (int)parts.size() ? parts[c.part].name : "Model";
            fprintf(out, "g %s\n", clean(pn, "Model").c_str());
            lastPart_ = c.part;
            lastMat_ = -2;
        }
        if (c.material != lastMat_ && !c.points) {
            fprintf(out, "usemtl %s\n", matName(c.material, mats).c_str());
            lastMat_ = c.material;
            used_.insert(c.material);
        }
        for (auto& v : c.verts) {
            if (v.c != kWhite) {
                fprintf(out, "v %.9g %.9g %.9g %.4f %.4f %.4f\n", v.p[0] + o[0], v.p[1] + o[1], v.p[2] + o[2],
                        (v.c & 255) / 255.0, ((v.c >> 8) & 255) / 255.0, ((v.c >> 16) & 255) / 255.0);
            } else {
                fprintf(out, "v %.9g %.9g %.9g\n", v.p[0] + o[0], v.p[1] + o[1], v.p[2] + o[2]);
            }
        }
        if (!c.points) {
            for (auto& v : c.verts) fprintf(out, "vt %.7g %.7g\n", v.uv[0], v.uv[1]);
            float n[3];
            for (auto& v : c.verts) {
                unpackNormal(v.n, n);
                fprintf(out, "vn %.4f %.4f %.4f\n", n[0], n[1], n[2]);
            }
            for (size_t t = 0; t + 2 < c.tris.size(); t += 3) {
                long long a = vbase_ + c.tris[t] + 1, b = vbase_ + c.tris[t + 1] + 1, d = vbase_ + c.tris[t + 2] + 1;
                fprintf(out, "f %lld/%lld/%lld %lld/%lld/%lld %lld/%lld/%lld\n", a, a, a, b, b, b, d, d, d);
            }
            if (c.tris.empty()) {
                for (size_t l = 0; l + 1 < c.lines.size(); l += 2)
                    fprintf(out, "l %lld %lld\n", (long long)(vbase_ + c.lines[l] + 1), (long long)(vbase_ + c.lines[l + 1] + 1));
            }
        }
        vbase_ += (int64_t)c.verts.size();
    } else {
        for (size_t t = 0; t + 2 < c.tris.size(); t += 3) {
            const float* p0 = c.verts[c.tris[t]].p;
            const float* p1 = c.verts[c.tris[t + 1]].p;
            const float* p2 = c.verts[c.tris[t + 2]].p;
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
            stlCount_++;
        }
    }
    if (ferror(out)) err_ = true;
}

std::string ChunkWriter::finish(const std::vector<Material>& mats, bool ok) {
    std::string result;
    if (out_) {
        fflush(out_);
        if (ok && err_) result = "Writing failed — the storage may be full.";
        if (ok && !err_ && opt_.format == ExportOptions::STL) {
            if (pwrite(fileno(out_), &stlCount_, 4, 80) != 4)
                result = "The STL was written, but its triangle count could not be updated (the storage location doesn't allow it).";
        }
        fclose(out_);
        out_ = nullptr;
    } else if (ok) {
        result = "Could not write the output file.";
    }
    if (opt_.mtlFd >= 0) {
        if (ok && result.empty() && opt_.format == ExportOptions::OBJ) {
            FILE* m = fdopen(opt_.mtlFd, "w");
            if (m) {
                fprintf(m, "# Exported by Vertexa Pro\n");
                for (int i : used_) {
                    fprintf(m, "\nnewmtl %s\n", matName(i, mats).c_str());
                    const float* kd = i >= 0 && i < (int)mats.size() ? mats[i].kd : nullptr;
                    fprintf(m, "Kd %.4f %.4f %.4f\n", kd ? kd[0] : 0.8f, kd ? kd[1] : 0.8f, kd ? kd[2] : 0.8f);
                    fprintf(m, "d %.4f\n", kd ? kd[3] : 1.f);
                    if (i >= 0 && i < (int)opt_.textures.size() && !opt_.textures[i].empty())
                        fprintf(m, "map_Kd %s\n", opt_.textures[i].c_str());
                }
                fclose(m);
            } else {
                ::close(opt_.mtlFd);
            }
        } else {
            ::close(opt_.mtlFd);
        }
        opt_.mtlFd = -1;
    }
    return result;
}

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
    ChunkWriter w(opt);
    if (!w.begin()) {
        ::close(inFd);
        if (opt.mtlFd >= 0) ::close(opt.mtlFd);
        return "Could not write the output file.";
    }
    s.consumer = [&](std::unique_ptr<Chunk> c) {
        w.write(*c, s.origin, s.parts(), s.materials());
        progress = s.progress.load() * 0.98f;
    };
    std::atomic<bool> done{false};
    std::thread watcher([&]() {
        while (!done.load()) {
            if (cancel.load()) s.cancel = true;
            usleep(50000);
        }
    });
    std::string err = runLoader(inFd, size, name, s, res, opt.splitLoose);
    done = true;
    watcher.join();
    std::string fin = w.finish(s.materials(), err.empty());
    if (!err.empty()) return err == "cancelled" ? err : "Reading failed: " + err;
    if (!fin.empty()) return fin;
    progress = 1.f;
    return "";
}

}  // namespace vx
