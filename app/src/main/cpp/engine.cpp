#include "engine.h"

#include <unistd.h>

#include <algorithm>
#include <new>

#include "loaders.h"

namespace vx {

std::string Engine::load(int fd, int64_t size, const std::string& name, Resolver& res) {
    auto s = std::make_shared<Sink>();
    {
        std::lock_guard<std::mutex> l(mu_);
        sink_ = s;
    }
    std::string ext;
    size_t dot = name.find_last_of('.');
    if (dot != std::string::npos) ext = name.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return (char)std::tolower(c); });

    std::string err;
    try {
        if (ext == "obj") err = loadObj(fd, size, *s, res);
        else if (ext == "stl") err = loadStl(fd, size, *s);
        else if (ext == "ply") err = loadPly(fd, size, *s, res);
        else {
            err = loadAssimp(fd, size, name, *s, res);
            fd = -1;  // closed by the loader
        }
    } catch (const std::bad_alloc&) {
        err = "The device ran out of memory while opening this model.";
    } catch (const std::exception& e) {
        err = e.what();
    } catch (...) {
        err = "Unexpected error while reading the file.";
    }
    if (fd >= 0) ::close(fd);
    s->progress = 1.f;
    if (s->cancel.load()) return "cancelled";
    return err;
}

}  // namespace vx
