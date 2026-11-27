// Loader for FBX, glTF/GLB, DAE, 3DS, 3MF, DXF, X, OFF, LWO, BLEND, AC, MS3D via Assimp.
// Files are read through the descriptor with pread, so nothing is copied up front;
// external files (.bin buffers, textures) are resolved through the Resolver.
#include <assimp/DefaultLogger.hpp>
#include <assimp/IOStream.hpp>
#include <assimp/LogStream.hpp>
#include <assimp/IOSystem.hpp>
#include <assimp/Importer.hpp>
#include <assimp/ProgressHandler.hpp>
#include <assimp/postprocess.h>
#include <assimp/scene.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <map>

#include <functional>

#include "anim.h"
#include "loaders.h"
#include "reader.h"

namespace vx {
namespace {

const char* kRoot = "/vxmodel/";

class FdStream : public Assimp::IOStream {
public:
    FdStream(int fd) : fd_(fd) {
        struct stat st {};
        if (fstat(fd, &st) == 0) size_ = (size_t)st.st_size;
    }
    ~FdStream() override { ::close(fd_); }
    size_t Read(void* buf, size_t sz, size_t cnt) override {
        if (!sz || !cnt) return 0;
        size_t want = sz * cnt, got = 0;
        uint8_t* out = (uint8_t*)buf;
        while (got < want) {
            ssize_t r = pread(fd_, out + got, want - got, (off_t)(pos_ + got));
            if (r < 0 && errno == EINTR) continue;
            if (r <= 0) break;
            got += (size_t)r;
        }
        pos_ += got;
        return got / sz;
    }
    size_t Write(const void*, size_t, size_t) override { return 0; }
    aiReturn Seek(size_t off, aiOrigin origin) override {
        size_t np = origin == aiOrigin_SET ? off : origin == aiOrigin_CUR ? pos_ + off : size_ + off;
        if (np > size_) return aiReturn_FAILURE;
        pos_ = np;
        return aiReturn_SUCCESS;
    }
    size_t Tell() const override { return pos_; }
    size_t FileSize() const override { return size_; }
    void Flush() override {}

private:
    int fd_;
    size_t size_ = 0, pos_ = 0;
};

std::string relName(const char* path) {
    std::string p(path);
    for (auto& c : p) if (c == '\\') c = '/';
    if (p.rfind(kRoot, 0) == 0) p = p.substr(strlen(kRoot));
    while (p.rfind("./", 0) == 0) p = p.substr(2);
    return p;
}

class FdIOSystem : public Assimp::IOSystem {
public:
    FdIOSystem(int fd, std::string name, Resolver& res) : mainFd_(fd), name_(std::move(name)), res_(res) {}
    bool Exists(const char* file) const override {
        std::string n = relName(file);
        if (n == name_) return true;
        int fd = res_.open(n);
        if (fd < 0) return false;
        ::close(fd);
        return true;
    }
    char getOsSeparator() const override { return '/'; }
    Assimp::IOStream* Open(const char* file, const char* mode) override {
        std::string n = relName(file);
        int fd = -1;
        if (n == name_) fd = dup(mainFd_);
        else fd = res_.open(n);
        if (fd < 0) return nullptr;
        return new FdStream(fd);
    }
    void Close(Assimp::IOStream* s) override { delete s; }

private:
    int mainFd_;
    std::string name_;
    Resolver& res_;
};

class Progress : public Assimp::ProgressHandler {
public:
    explicit Progress(Sink& s) : sink_(s) {}
    bool Update(float pct) override {
        if (pct >= 0) sink_.progress = pct * 0.85f;
        return !sink_.cancel.load();
    }

private:
    Sink& sink_;
};

class ReportStream : public Assimp::LogStream {
public:
    ReportStream(Sink& s, int level) : sink_(s), level_(level) {}
    void write(const char* msg) override {
        std::string m = trim(msg);
        while (!m.empty() && (m.back() == '\n' || m.back() == '\r')) m.pop_back();
        if (!m.empty() && count_++ < 60) sink_.log(level_, "Importer: " + m);
    }

private:
    Sink& sink_;
    int level_;
    int count_ = 0;
};

std::mutex gLogMu;

// Animated models keep their node hierarchy and bones (no flattening) so they can play.
std::string buildAnimated(const aiScene* sc, Sink& sink, int base) {
    auto data = std::make_shared<AnimData>();
    std::map<std::string, int> nodeIdx;
    std::vector<const aiNode*> nodePtr;
    std::function<void(const aiNode*, int)> walk = [&](const aiNode* nd, int parent) {
        int id = (int)data->nodes.size();
        data->nodes.push_back(AnimNode{nd->mName.C_Str(), parent, nd->mTransformation});
        nodePtr.push_back(nd);
        nodeIdx.emplace(nd->mName.C_Str(), id);
        for (unsigned c = 0; c < nd->mNumChildren; c++) walk(nd->mChildren[c], id);
    };
    walk(sc->mRootNode, -1);
    std::vector<aiMatrix4x4> G;
    data->evaluate(-1, 0, G);

    for (unsigned a = 0; a < sc->mNumAnimations; a++) {
        const aiAnimation* an = sc->mAnimations[a];
        AnimClip clip;
        clip.name = an->mName.length ? an->mName.C_Str() : "Animation " + std::to_string(a + 1);
        clip.duration = an->mDuration;
        clip.tps = an->mTicksPerSecond > 0 ? an->mTicksPerSecond : 25;
        for (unsigned c = 0; c < an->mNumChannels; c++) {
            const aiNodeAnim* na = an->mChannels[c];
            auto it = nodeIdx.find(na->mNodeName.C_Str());
            if (it == nodeIdx.end()) continue;
            AnimChannel ch;
            ch.node = it->second;
            for (unsigned k = 0; k < na->mNumPositionKeys; k++) ch.pos.push_back({na->mPositionKeys[k].mTime, na->mPositionKeys[k].mValue});
            for (unsigned k = 0; k < na->mNumScalingKeys; k++) ch.scl.push_back({na->mScalingKeys[k].mTime, na->mScalingKeys[k].mValue});
            for (unsigned k = 0; k < na->mNumRotationKeys; k++) ch.rot.push_back({na->mRotationKeys[k].mTime, na->mRotationKeys[k].mValue});
            clip.channels.push_back(std::move(ch));
        }
        char secs[32];
        snprintf(secs, sizeof secs, "%.1f s", clip.duration / clip.tps);
        sink.log(0, "Animation \"" + clip.name + "\": " + std::to_string(clip.channels.size()) + " animated nodes, " + secs);
        data->clips.push_back(std::move(clip));
    }

    std::vector<std::unique_ptr<Chunk>> chunks;
    std::map<std::string, int> partByName;
    for (size_t ni = 0; ni < nodePtr.size(); ni++) {
        const aiNode* nd = nodePtr[ni];
        for (unsigned mi = 0; mi < nd->mNumMeshes; mi++) {
            const aiMesh* me = sc->mMeshes[nd->mMeshes[mi]];
            if (!me->mNumVertices) continue;
            AnimInstance in;
            in.node = (int)ni;
            in.skinned = me->HasBones();
            in.bindInv = G[ni];
            in.bindInv.Inverse();
            in.basePos.assign(me->mVertices, me->mVertices + me->mNumVertices);
            if (me->HasNormals()) in.baseNrm.assign(me->mNormals, me->mNormals + me->mNumVertices);
            else in.baseNrm.assign(me->mNumVertices, aiVector3D(0, 1, 0));
            if (in.skinned) {
                in.bi.assign(me->mNumVertices, {0, 0, 0, 0});
                in.bw.assign(me->mNumVertices, {0, 0, 0, 0});
                for (unsigned b = 0; b < me->mNumBones && b < 65535; b++) {
                    const aiBone* bone = me->mBones[b];
                    auto it = nodeIdx.find(bone->mName.C_Str());
                    in.boneNode.push_back(it == nodeIdx.end() ? -1 : it->second);
                    in.boneOffset.push_back(bone->mOffsetMatrix);
                    for (unsigned w = 0; w < bone->mNumWeights; w++) {
                        unsigned vid = bone->mWeights[w].mVertexId;
                        float wt = bone->mWeights[w].mWeight;
                        if (vid >= me->mNumVertices) continue;
                        int slot = 0;
                        for (int k = 1; k < 4; k++) if (in.bw[vid][k] < in.bw[vid][slot]) slot = k;
                        if (wt > in.bw[vid][slot]) { in.bw[vid][slot] = wt; in.bi[vid][slot] = (uint16_t)b; }
                    }
                }
            }
            // bind-pose vertices in scene space
            aiMatrix3x3 nr(G[ni]);
            in.bindVerts.resize(me->mNumVertices);
            for (unsigned k = 0; k < me->mNumVertices; k++) {
                Vertex& v = in.bindVerts[k];
                aiVector3D p = G[ni] * in.basePos[k];
                aiVector3D n = nr * in.baseNrm[k];
                v.p[0] = p.x; v.p[1] = p.y; v.p[2] = p.z;
                v.n = packNormal(n.x, n.y, n.z);
                if (me->HasTextureCoords(0)) { v.uv[0] = me->mTextureCoords[0][k].x; v.uv[1] = me->mTextureCoords[0][k].y; }
                else v.uv[0] = v.uv[1] = 0;
                if (me->HasVertexColors(0)) {
                    auto& cc = me->mColors[0][k];
                    v.c = packColor(cc.r, cc.g, cc.b, cc.a);
                } else v.c = kWhite;
            }
            if (in.skinned) {
                std::vector<Vertex> sk;
                skinInstance(in, G, sk);
                in.bindVerts.swap(sk);
            }
            auto c = std::make_unique<Chunk>();
            c->material = base + (sc->mNumMaterials ? (int)me->mMaterialIndex : 0);
            std::string pname = nd->mName.length ? nd->mName.C_Str() : (me->mName.length ? me->mName.C_Str() : "Mesh");
            auto pit = partByName.find(pname);
            c->part = pit != partByName.end() ? pit->second : (partByName[pname] = sink.addPart(pname));
            c->points = me->mPrimitiveTypes == aiPrimitiveType_POINT;
            c->verts = in.bindVerts;
            for (unsigned f = 0; f < me->mNumFaces; f++) {
                const aiFace& fc = me->mFaces[f];
                if (fc.mNumIndices == 3) {
                    c->tris.insert(c->tris.end(), {fc.mIndices[0], fc.mIndices[1], fc.mIndices[2]});
                    c->lines.insert(c->lines.end(), {fc.mIndices[0], fc.mIndices[1], fc.mIndices[1], fc.mIndices[2],
                                                     fc.mIndices[2], fc.mIndices[0]});
                } else if (fc.mNumIndices == 2) {
                    c->lines.insert(c->lines.end(), {fc.mIndices[0], fc.mIndices[1]});
                }
            }
            if (!me->HasNormals()) {
                std::vector<bool> hn(c->verts.size(), false);
                computeMissingNormals(*c, hn);
                for (size_t k = 0; k < c->verts.size(); k++) in.bindVerts[k].n = c->verts[k].n;
            }
            c->animSlot = (int)data->inst.size();
            data->inst.push_back(std::move(in));
            chunks.push_back(std::move(c));
        }
    }
    sink.anim = data;  // published before the chunks that refer to it
    for (auto& c : chunks) sink.push(std::move(c));
    sink.log(0, "Animated model: " + std::to_string(data->nodes.size()) + " nodes, " + std::to_string(data->inst.size()) +
                    " mesh instances, " + std::to_string(data->clips.size()) + " animation(s).");
    if (sink.vertexCount.load() == 0) return "No geometry found in this file.";
    return "";
}

}  // namespace

std::string loadAssimp(int fd, int64_t size, const std::string& name, Sink& sink, Resolver& res) {
    std::lock_guard<std::mutex> logLock(gLogMu);  // Assimp's logger is global
    Assimp::DefaultLogger::create("", Assimp::Logger::NORMAL, 0);
    auto* warnStream = new ReportStream(sink, 1);
    auto* errStream = new ReportStream(sink, 2);
    Assimp::DefaultLogger::get()->attachStream(warnStream, Assimp::Logger::Warn);
    Assimp::DefaultLogger::get()->attachStream(errStream, Assimp::Logger::Err);
    struct LoggerGuard {
        ~LoggerGuard() { Assimp::DefaultLogger::kill(); }
    } loggerGuard;
    Assimp::Importer imp;
    imp.SetIOHandler(new FdIOSystem(fd, name, res));  // importer takes ownership
    imp.SetProgressHandler(new Progress(sink));
    imp.SetPropertyBool(AI_CONFIG_PP_PTV_NORMALIZE, false);
    imp.SetPropertyInteger(AI_CONFIG_PP_SBP_REMOVE, 0);
    const aiScene* sc = imp.ReadFile(std::string(kRoot) + name,
                                     aiProcess_Triangulate | aiProcess_JoinIdenticalVertices |
                                         aiProcess_GenSmoothNormals | aiProcess_LimitBoneWeights |
                                         aiProcess_SortByPType);
    bool animated = sc && sc->mRootNode && sc->mNumAnimations > 0;
    if (sc && sc->mRootNode && !animated) sc = imp.ApplyPostProcessing(aiProcess_PreTransformVertices);
    ::close(fd);
    if (sink.cancel.load()) return "cancelled";
    if (sc) {
        sink.log(0, "Importer read " + std::to_string(sc->mNumMeshes) + " meshes, " + std::to_string(sc->mNumMaterials) +
                        " materials, " + std::to_string(sc->mNumTextures) + " embedded textures");
        if (sc->mFlags & AI_SCENE_FLAGS_INCOMPLETE) sink.log(1, "The importer marked the scene as incomplete.");
    }
    if (!sc || !sc->mRootNode) {
        std::string e = imp.GetErrorString();
        return e.empty() ? "This file could not be read." : e;
    }

    // embedded textures
    sink.embedded.resize(sc->mNumTextures);
    for (unsigned i = 0; i < sc->mNumTextures; i++) {
        const aiTexture* t = sc->mTextures[i];
        EmbeddedTexture& et = sink.embedded[i];
        if (t->mHeight == 0) {
            et.data.assign((const uint8_t*)t->pcData, (const uint8_t*)t->pcData + t->mWidth);
        } else {
            et.width = (int)t->mWidth;
            et.height = (int)t->mHeight;
            et.data.resize((size_t)t->mWidth * t->mHeight * 4);
            for (size_t k = 0; k < (size_t)t->mWidth * t->mHeight; k++) {
                const aiTexel& x = t->pcData[k];
                et.data[k * 4] = x.r;
                et.data[k * 4 + 1] = x.g;
                et.data[k * 4 + 2] = x.b;
                et.data[k * 4 + 3] = x.a;
            }
        }
    }

    int base = sink.materialCount();
    for (unsigned i = 0; i < sc->mNumMaterials; i++) {
        const aiMaterial* am = sc->mMaterials[i];
        Material m;
        aiString nm;
        if (am->Get(AI_MATKEY_NAME, nm) == AI_SUCCESS) m.name = nm.C_Str();
        aiColor4D c;
        if (am->Get(AI_MATKEY_BASE_COLOR, c) == AI_SUCCESS || am->Get(AI_MATKEY_COLOR_DIFFUSE, c) == AI_SUCCESS) {
            m.kd[0] = c.r; m.kd[1] = c.g; m.kd[2] = c.b; m.kd[3] = 1.f;
        }
        aiString tp;
        if (am->GetTexture(aiTextureType_BASE_COLOR, 0, &tp) == AI_SUCCESS ||
            am->GetTexture(aiTextureType_DIFFUSE, 0, &tp) == AI_SUCCESS) {
            auto emb = sc->GetEmbeddedTextureAndIndex(tp.C_Str());
            if (emb.first && emb.second >= 0) m.texture = "*" + std::to_string(emb.second);
            else m.texture = relName(tp.C_Str());
        }
        sink.addMaterial(m);
    }
    if (sc->mNumMaterials == 0) {
        Material m;
        m.name = "default";
        sink.addMaterial(m);
    }

    if (animated) return buildAnimated(sc, sink, base);

    bool haveOrigin = false;
    double o[3] = {0, 0, 0};
    std::map<std::string, int> partByName;
    for (unsigned mi = 0; mi < sc->mNumMeshes; mi++) {
        if (sink.cancel.load()) return "cancelled";
        const aiMesh* me = sc->mMeshes[mi];
        if (!me->mNumVertices) continue;
        if (!haveOrigin) {
            o[0] = me->mVertices[0].x; o[1] = me->mVertices[0].y; o[2] = me->mVertices[0].z;
            sink.origin[0] = o[0]; sink.origin[1] = o[1]; sink.origin[2] = o[2];
            haveOrigin = true;
        }
        int mat = base + (sc->mNumMaterials ? (int)me->mMaterialIndex : 0);
        bool isPoints = me->mPrimitiveTypes == aiPrimitiveType_POINT;
        std::string pname = me->mName.length ? me->mName.C_Str() : "Mesh " + std::to_string(mi + 1);
        auto pit = partByName.find(pname);
        int part = pit != partByName.end() ? pit->second : (partByName[pname] = sink.addPart(pname));
        Builder b(sink, mat, isPoints, part);
        bool hn = me->HasNormals();
        auto makeV = [&](unsigned k) {
            Vertex v;
            v.p[0] = (float)(me->mVertices[k].x - o[0]);
            v.p[1] = (float)(me->mVertices[k].y - o[1]);
            v.p[2] = (float)(me->mVertices[k].z - o[2]);
            v.n = hn ? packNormal(me->mNormals[k].x, me->mNormals[k].y, me->mNormals[k].z) : packNormal(0, 1, 0);
            if (me->HasTextureCoords(0)) {
                v.uv[0] = me->mTextureCoords[0][k].x;
                v.uv[1] = me->mTextureCoords[0][k].y;
            } else v.uv[0] = v.uv[1] = 0;
            if (me->HasVertexColors(0)) {
                auto& cc = me->mColors[0][k];
                v.c = packColor(cc.r, cc.g, cc.b, cc.a);
            } else v.c = kWhite;
            return v;
        };
        for (unsigned f = 0; f < me->mNumFaces; f++) {
            const aiFace& fc = me->mFaces[f];
            if (fc.mNumIndices == 0) continue;
            b.reserveFace(fc.mNumIndices);
            uint32_t loc[3];
            unsigned n = std::min(fc.mNumIndices, 3u);
            for (unsigned j = 0; j < n; j++) loc[j] = b.vertex(fc.mIndices[j], mi, 0, makeV(fc.mIndices[j]), hn);
            if (n == 3) {
                b.tri(loc[0], loc[1], loc[2]);
                b.line(loc[0], loc[1]);
                b.line(loc[1], loc[2]);
                b.line(loc[2], loc[0]);
            } else if (n == 2) {
                b.line(loc[0], loc[1]);
            }
        }
        if (me->mNumFaces == 0) {
            for (unsigned k = 0; k < me->mNumVertices; k++) { b.reserveFace(1); b.raw(makeV(k), true); }
        }
        b.flush();
        sink.progress = 0.85f + 0.15f * (float)(mi + 1) / (float)sc->mNumMeshes;
    }
    (void)size;
    if (sink.vertexCount.load() == 0) return "No geometry found in this file.";
    return "";
}

}  // namespace vx
