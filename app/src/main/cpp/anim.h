// Animation data for animated FBX / glTF / DAE models: node hierarchy, rigid node animation
// and skeletal skinning. Evaluated on the GL thread every frame while playing.
#pragma once
#include <assimp/matrix3x3.h>
#include <assimp/matrix4x4.h>
#include <assimp/quaternion.h>
#include <assimp/vector3.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <string>
#include <vector>

#include "mesh.h"

namespace vx {

struct AnimKeyV { double t; aiVector3D v; };
struct AnimKeyQ { double t; aiQuaternion q; };

struct AnimChannel {
    int node = -1;
    std::vector<AnimKeyV> pos, scl;
    std::vector<AnimKeyQ> rot;
};

struct AnimClip {
    std::string name;
    double duration = 0;  // ticks
    double tps = 25;      // ticks per second
    std::vector<AnimChannel> channels;
};

struct AnimNode {
    std::string name;
    int parent = -1;
    aiMatrix4x4 local;
};

struct AnimInstance {
    int node = -1;
    bool skinned = false;
    aiMatrix4x4 bindInv;                       // rigid: inverse of the node's bind-pose global transform
    std::vector<int> boneNode;                 // skinned: node driving each bone
    std::vector<aiMatrix4x4> boneOffset;
    std::vector<std::array<uint16_t, 4>> bi;   // per vertex: up to 4 bones
    std::vector<std::array<float, 4>> bw;
    std::vector<aiVector3D> basePos, baseNrm;  // mesh space
    std::vector<Vertex> bindVerts;             // scene space, with uv / colour
};

struct AnimData {
    std::vector<AnimNode> nodes;
    std::vector<AnimClip> clips;
    std::vector<AnimInstance> inst;

    double clipSeconds(int c) const {
        if (c < 0 || c >= (int)clips.size()) return 0;
        double tps = clips[c].tps > 0 ? clips[c].tps : 25;
        return clips[c].duration / tps;
    }

    /** Global transforms of every node at time t (seconds) of clip c. */
    void evaluate(int c, double seconds, std::vector<aiMatrix4x4>& global) const {
        std::vector<aiMatrix4x4> local(nodes.size());
        for (size_t i = 0; i < nodes.size(); i++) local[i] = nodes[i].local;
        if (c >= 0 && c < (int)clips.size()) {
            const AnimClip& clip = clips[c];
            double tps = clip.tps > 0 ? clip.tps : 25;
            double t = clip.duration > 0 ? std::fmod(seconds * tps, clip.duration) : 0;
            for (const auto& ch : clip.channels) {
                if (ch.node < 0) continue;
                aiVector3D s(1, 1, 1), p(0, 0, 0);
                aiQuaternion q;
                // fall back to the node's own transform for missing tracks
                aiVector3D ds, dp;
                aiQuaternion dq;
                nodes[ch.node].local.Decompose(ds, dq, dp);
                s = ch.scl.empty() ? ds : lerpV(ch.scl, t);
                p = ch.pos.empty() ? dp : lerpV(ch.pos, t);
                q = ch.rot.empty() ? dq : slerpQ(ch.rot, t);
                local[ch.node] = aiMatrix4x4(s, q, p);
            }
        }
        global.resize(nodes.size());
        for (size_t i = 0; i < nodes.size(); i++) {
            int par = nodes[i].parent;
            global[i] = par >= 0 ? global[par] * local[i] : local[i];  // parents always come first
        }
    }

private:
    static aiVector3D lerpV(const std::vector<AnimKeyV>& k, double t) {
        if (k.size() == 1 || t <= k.front().t) return k.front().v;
        if (t >= k.back().t) return k.back().v;
        size_t hi = 1;
        while (hi < k.size() && k[hi].t < t) hi++;
        const auto& a = k[hi - 1];
        const auto& b = k[hi];
        float f = (float)((t - a.t) / std::max(1e-12, b.t - a.t));
        return a.v + (b.v - a.v) * f;
    }
    static aiQuaternion slerpQ(const std::vector<AnimKeyQ>& k, double t) {
        if (k.size() == 1 || t <= k.front().t) return k.front().q;
        if (t >= k.back().t) return k.back().q;
        size_t hi = 1;
        while (hi < k.size() && k[hi].t < t) hi++;
        const auto& a = k[hi - 1];
        const auto& b = k[hi];
        float f = (float)((t - a.t) / std::max(1e-12, b.t - a.t));
        aiQuaternion out;
        aiQuaternion::Interpolate(out, a.q, b.q, f);
        return out.Normalize();
    }
};

/** Skins (or rigidly places) an instance's bind vertices with the given node transforms. */
inline void skinInstance(const AnimInstance& in, const std::vector<aiMatrix4x4>& G, std::vector<Vertex>& out) {
    out = in.bindVerts;
    if (!in.skinned) return;
    std::vector<aiMatrix4x4> B(in.boneNode.size());
    for (size_t j = 0; j < B.size(); j++)
        B[j] = in.boneNode[j] >= 0 && in.boneNode[j] < (int)G.size() ? G[in.boneNode[j]] * in.boneOffset[j] : aiMatrix4x4();
    aiMatrix4x4 nodeG = in.node >= 0 && in.node < (int)G.size() ? G[in.node] : aiMatrix4x4();
    for (size_t v = 0; v < out.size() && v < in.basePos.size(); v++) {
        const aiVector3D& p = in.basePos[v];
        const aiVector3D& n = in.baseNrm[v];
        aiVector3D rp(0, 0, 0), rn(0, 0, 0);
        float wsum = 0;
        for (int k = 0; k < 4; k++) {
            float w = in.bw[v][k];
            if (w <= 0) continue;
            const aiMatrix4x4& m = B[in.bi[v][k]];
            rp += (m * p) * w;
            aiMatrix3x3 r(m);
            rn += (r * n) * w;
            wsum += w;
        }
        if (wsum <= 0) {
            rp = nodeG * p;
            rn = aiMatrix3x3(nodeG) * n;
        }
        out[v].p[0] = rp.x; out[v].p[1] = rp.y; out[v].p[2] = rp.z;
        out[v].n = packNormal(rn.x, rn.y, rn.z);
    }
}

}  // namespace vx
