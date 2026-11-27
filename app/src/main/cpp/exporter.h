#pragma once
#include <atomic>
#include <cstdio>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "mesh.h"

namespace vx {

struct ExportOptions {
    enum Format { OBJ = 0, STL = 1 };
    int format = OBJ;
    int outFd = -1;
    int mtlFd = -1;                    // OBJ only; -1 = no material file
    std::string mtlName;               // file name written into the OBJ's mtllib line
    std::vector<uint8_t> hidden;       // parts to leave out
    std::vector<std::string> textures; // per material: texture file name for map_Kd ("" = none)
    bool splitLoose = false;           // must match how the parts were made in the viewer
    int onlyPart = -1;                 // >= 0: write just this part (one STL per part)
};

/** Writes chunks to OBJ (+MTL) or binary STL as they arrive. */
class ChunkWriter {
public:
    explicit ChunkWriter(const ExportOptions& o) : opt_(o) {}
    bool begin();
    void write(const Chunk& c, const double* origin, const std::vector<PartInfo>& parts,
               const std::vector<Material>& mats);
    /** Finishes the files and closes every descriptor. Returns "" on success. */
    std::string finish(const std::vector<Material>& mats, bool ok);
    bool failed() const { return err_; }

private:
    ExportOptions opt_;
    FILE* out_ = nullptr;
    std::vector<char> buf_;
    int64_t vbase_ = 0;
    uint32_t stlCount_ = 0;
    int lastPart_ = -1, lastMat_ = -2;
    std::set<int> used_;
    bool err_ = false;
};

/** Runs the right loader for the file name. Closes fd. */
std::string runLoader(int fd, int64_t size, const std::string& name, Sink& s, Resolver& res, bool splitLoose);

/** Converts a model file; closes every fd it is given. Returns "" on success. */
std::string exportModel(int inFd, int64_t size, const std::string& name, Resolver& res, const ExportOptions& opt,
                        std::atomic<float>& progress, std::atomic<bool>& cancel);

}  // namespace vx
