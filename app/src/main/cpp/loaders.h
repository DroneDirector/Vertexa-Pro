#pragma once
#include <string>
#include "mesh.h"

namespace vx {
// Each loader returns an empty string on success or an error message.
std::string loadObj(int fd, int64_t size, Sink& sink, Resolver& res, bool splitLoose);
std::string loadStl(int fd, int64_t size, Sink& sink);
std::string loadPly(int fd, int64_t size, Sink& sink, Resolver& res);
std::string loadAssimp(int fd, int64_t size, const std::string& name, Sink& sink, Resolver& res);
}  // namespace vx
