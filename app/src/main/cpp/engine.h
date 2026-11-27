#pragma once
#include <atomic>
#include <memory>
#include <mutex>
#include <string>

#include "mesh.h"
#include "renderer.h"

namespace vx {

class Engine {
public:
    Renderer renderer;

    std::string load(int fd, int64_t size, const std::string& name, Resolver& res, bool splitLoose = false);
    void cancel() {
        auto s = sink();
        if (s) s->cancel = true;
    }
    std::shared_ptr<Sink> sink() {
        std::lock_guard<std::mutex> l(mu_);
        return sink_;
    }
    void resetSink() {
        std::lock_guard<std::mutex> l(mu_);
        sink_.reset();
    }

    std::atomic<float> exportProgress{0.f};
    std::atomic<bool> exportCancel{false};

private:
    std::mutex mu_;
    std::shared_ptr<Sink> sink_;
};

}  // namespace vx
