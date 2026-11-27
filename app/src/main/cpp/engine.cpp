#include "engine.h"

#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <new>

#include "exporter.h"
#include "loaders.h"

namespace vx {

std::string Engine::load(int fd, int64_t size, const std::string& name, Resolver& res, bool splitLoose) {
    auto s = std::make_shared<Sink>();
    {
        std::lock_guard<std::mutex> l(mu_);
        sink_ = s;
    }
    std::string ext;
    size_t dot = name.find_last_of('.');
    if (dot != std::string::npos) ext = name.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return (char)std::tolower(c); });

    s->log(0, "File: " + name + (size > 0 ? " (" + std::to_string(size) + " bytes)" : " (size unknown)"));
    s->log(0, std::string("Reader: ") + (ext == "obj" ? "Vertexa streaming OBJ" : ext == "stl" ? "Vertexa streaming STL" :
                                          ext == "ply" ? "Vertexa streaming PLY" : "Assimp (" + ext + ")"));
    std::string err = runLoader(fd, size, name, *s, res, splitLoose);
    s->progress = 1.f;
    s->log(0, "Result: " + std::to_string(s->vertexCount.load()) + " vertices, " + std::to_string(s->triangleCount.load()) +
                  " triangles, " + std::to_string(s->pointCount.load()) + " points, " + std::to_string(s->materialCount()) +
                  " materials, " + std::to_string(s->chunkCount.load()) + " GPU chunks");
    Bounds b = s->bounds();
    if (b.valid()) {
        double dx = b.mx[0] - b.mn[0], dy = b.mx[1] - b.mn[1], dz = b.mx[2] - b.mn[2];
        char buf[160];
        snprintf(buf, sizeof buf, "Model size: %.4g × %.4g × %.4g (offset %.6g, %.6g, %.6g)", dx, dy, dz,
                 s->origin[0], s->origin[1], s->origin[2]);
        s->log(0, buf);
        if (dx == 0 && dy == 0 && dz == 0) s->log(2, "All vertices are at the same position, so nothing visible can be drawn.");
    }
    if (!err.empty() && err != "cancelled") s->log(2, "Failed: " + err);
    if (s->cancel.load()) return "cancelled";
    return err;
}

}  // namespace vx
