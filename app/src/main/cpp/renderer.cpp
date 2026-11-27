#include "renderer.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstdio>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Vertexa", __VA_ARGS__)

namespace vx {
namespace {

const double kFov = 45.0 * M_PI / 180.0;
const double kDefYaw = 0.65, kDefPitch = 0.32;

// Studio lighting presets (directions in view space).
struct Lighting {
    float l1[3], c1[3], l2[3], c2[3], sky[3], ground[3], spec, rim, flat;
};
const Lighting kLights[] = {
    // 0 Studio
    {{-0.45f, 0.65f, 0.62f}, {0.80f, 0.78f, 0.74f}, {0.70f, 0.10f, 0.45f}, {0.22f, 0.25f, 0.30f},
     {0.42f, 0.43f, 0.46f}, {0.22f, 0.22f, 0.25f}, 0.22f, 0.18f, 0.f},
    // 1 Soft box
    {{0.0f, 0.5f, 0.85f}, {0.55f, 0.55f, 0.55f}, {-0.6f, 0.2f, 0.5f}, {0.30f, 0.30f, 0.32f},
     {0.60f, 0.60f, 0.62f}, {0.45f, 0.45f, 0.47f}, 0.08f, 0.08f, 0.f},
    // 2 Dramatic
    {{-0.85f, 0.45f, 0.25f}, {1.15f, 1.0f, 0.85f}, {0.9f, 0.1f, -0.35f}, {0.35f, 0.45f, 0.70f},
     {0.12f, 0.12f, 0.14f}, {0.05f, 0.05f, 0.06f}, 0.35f, 0.45f, 0.f},
    // 3 Outdoor sun
    {{0.30f, 0.85f, 0.40f}, {1.0f, 0.95f, 0.85f}, {0.0f, 1.0f, 0.0f}, {0.20f, 0.26f, 0.36f},
     {0.45f, 0.52f, 0.65f}, {0.25f, 0.22f, 0.18f}, 0.20f, 0.05f, 0.f},
    // 4 Rim light
    {{0.10f, 0.30f, -0.90f}, {0.90f, 0.90f, 1.0f}, {-0.40f, 0.40f, 0.80f}, {0.25f, 0.25f, 0.27f},
     {0.18f, 0.18f, 0.20f}, {0.10f, 0.10f, 0.11f}, 0.30f, 0.60f, 0.f},
    // 5 Flat (no shading)
    {{0, 0, 1}, {0, 0, 0}, {0, 0, 1}, {0, 0, 0}, {1, 1, 1}, {1, 1, 1}, 0.f, 0.f, 1.f},
};
const int kLightCount = sizeof(kLights) / sizeof(kLights[0]);

const char* kMeshVs = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec4 aNrm;
layout(location=2) in vec2 aUv;
layout(location=3) in vec4 aCol;
uniform mat4 uMV;
uniform mat4 uP;
uniform mat4 uM;
uniform mat3 uN;
uniform float uPointSize;
out vec3 vN; out vec3 vV; out vec2 vUv; out vec4 vCol; out float vH;
void main(){
  vec4 p = uMV * vec4(aPos, 1.0);
  vV = p.xyz;
  vN = uN * aNrm.xyz;
  vUv = aUv;
  vCol = aCol;
  vH = (uM * vec4(aPos, 1.0)).y;
  gl_Position = uP * p;
  gl_PointSize = uPointSize;
})";

const char* kMeshFs = R"(#version 300 es
precision highp float;
in vec3 vN; in vec3 vV; in vec2 vUv; in vec4 vCol; in float vH;
uniform int uShade;      // 0 textured, 1 clay, 2 flat line colour, 3 points
uniform vec4 uKd;
uniform sampler2D uTex;
uniform int uHasTex;
uniform vec3 uClay;
uniform vec4 uLine;
uniform vec3 uL1; uniform vec3 uC1; uniform vec3 uL2; uniform vec3 uC2;
uniform vec3 uSky; uniform vec3 uGround;
uniform float uSpec; uniform float uRim; uniform float uFlat;
uniform float uSel; uniform vec3 uSelColor;
uniform int uPointMode; uniform vec2 uHRange;
out vec4 o;
vec3 heat(float t){
  t = clamp(t, 0.0, 1.0);
  return clamp(vec3(1.5 - abs(4.0*t - 3.0), 1.5 - abs(4.0*t - 2.0), 1.5 - abs(4.0*t - 1.0)), 0.0, 1.0);
}
void main(){
  if (uShade == 2) { o = vec4(mix(uLine.rgb, uSelColor, uSel * 0.8), uLine.a); return; }
  if (uShade == 3) {
    vec2 pc = gl_PointCoord * 2.0 - 1.0;
    if (dot(pc, pc) > 1.0) discard;
    vec3 c;
    if (uPointMode == 1) c = uKd.rgb;
    else if (uPointMode == 2) c = heat((vH - uHRange.x) / max(uHRange.y - uHRange.x, 1e-9));
    else c = vCol.rgb * uKd.rgb;
    o = vec4(mix(c, uSelColor, uSel * 0.55), 1.0);
    return;
  }
  vec3 N = normalize(vN);
  if (!gl_FrontFacing) N = -N;
  vec3 V = normalize(-vV);
  vec3 base;
  if (uShade == 0) {
    base = vCol.rgb;
    if (uHasTex == 1) base *= texture(uTex, vec2(vUv.x, 1.0 - vUv.y)).rgb;
    else base *= uKd.rgb;
  } else {
    base = uClay * vCol.rgb;
  }
  vec3 L1 = normalize(uL1);
  vec3 L2 = normalize(uL2);
  float d1 = max(dot(N, L1), 0.0);
  float d2 = max(dot(N, L2), 0.0);
  float hemi = 0.5 + 0.5 * N.y;
  vec3 amb = mix(uGround, uSky, hemi);
  vec3 H = normalize(L1 + V);
  float spec = pow(max(dot(N, H), 0.0), 40.0);
  float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);
  vec3 lit = base * (amb + uC1 * d1 + uC2 * d2);
  if (uShade == 1) {
    lit += vec3(spec * uSpec) + vec3(0.55, 0.75, 1.0) * rim * uRim;
  } else {
    lit = mix(lit, base, 0.38) + vec3(spec * uSpec * 0.3) + rim * uRim * 0.25;
  }
  lit = mix(lit, base, uFlat);
  lit = mix(lit, uSelColor, uSel * (0.30 + 0.25 * rim));
  o = vec4(lit, 1.0);
})";

const char* kPickVs = R"(#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uMV; uniform mat4 uP; uniform float uPointSize;
void main(){ gl_Position = uP * (uMV * vec4(aPos, 1.0)); gl_PointSize = uPointSize; })";

const char* kPickFs = R"(#version 300 es
precision highp float;
uniform vec4 uId;
out vec4 o;
void main(){ o = uId; })";

const char* kBgVs = R"(#version 300 es
out vec2 vP;
void main(){
  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
  vP = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";

const char* kBgFs = R"(#version 300 es
precision mediump float;
in vec2 vP;
uniform vec3 uTop; uniform vec3 uBottom; uniform float uAspect;
out vec4 o;
void main(){
  vec3 c = mix(uBottom, uTop, smoothstep(0.0, 1.0, vP.y));
  vec2 q = (vP - vec2(0.5, 0.58)) * vec2(uAspect, 1.0);
  float glow = exp(-dot(q, q) * 3.2);
  c += (uTop - uBottom) * glow * 0.55;
  float vig = smoothstep(1.25, 0.35, length((vP - 0.5) * vec2(uAspect, 1.0)));
  c *= mix(0.82, 1.0, vig);
  o = vec4(c, 1.0);
})";

const char* kGridVs = R"(#version 300 es
layout(location=0) in vec3 aG;   // x, z in grid units, weight
uniform mat4 uVP; uniform vec3 uOff; uniform float uSpacing; uniform float uHalf;
out float vW; out float vF;
void main(){
  vec3 p = vec3(aG.x * uSpacing + uOff.x, uOff.y, aG.y * uSpacing + uOff.z);
  vW = aG.z;
  vF = length(aG.xy) / uHalf;
  gl_Position = uVP * vec4(p, 1.0);
})";

const char* kGridFs = R"(#version 300 es
precision mediump float;
in float vW; in float vF;
uniform vec4 uColor;
out vec4 o;
void main(){
  float a = uColor.a * mix(0.45, 1.0, vW) * (1.0 - smoothstep(0.35, 1.0, vF));
  o = vec4(uColor.rgb, a);
})";

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        LOGE("shader: %s", log);
    }
    return s;
}
GLuint link(const char* vs, const char* fs) {
    GLuint p = glCreateProgram();
    GLuint a = compile(GL_VERTEX_SHADER, vs), b = compile(GL_FRAGMENT_SHADER, fs);
    glAttachShader(p, a);
    glAttachShader(p, b);
    glLinkProgram(p);
    glDeleteShader(a);
    glDeleteShader(b);
    return p;
}

double easeInOut(double t) { return t < 0.5 ? 4 * t * t * t : 1 - std::pow(-2 * t + 2, 3) / 2; }

GLint U(GLuint p, const char* n) { return glGetUniformLocation(p, n); }

}  // namespace

// ---------------------------------------------------------------- setup

bool Renderer::surfaceCreated() {
    bool lost = hadContext_ && !gpu_.empty();
    gpu_.clear();  // old context objects are gone with the old context
    textures_.clear();
    pickFbo_ = pickColor_ = pickDepth_ = 0;
    pickW_ = pickH_ = 0;
    hadContext_ = true;
    buildPrograms();
    return lost;
}

void Renderer::buildPrograms() {
    meshProg_ = link(kMeshVs, kMeshFs);
    bgProg_ = link(kBgVs, kBgFs);
    gridProg_ = link(kGridVs, kGridFs);
    pickProg_ = link(kPickVs, kPickFs);
    glGenVertexArrays(1, &bgVao_);

    const int N = 40;
    std::vector<float> g;
    for (int i = -N; i <= N; i++) {
        float w = (i % 5 == 0) ? 1.f : 0.f;
        g.insert(g.end(), {(float)i, (float)-N, w, (float)i, (float)N, w});
        g.insert(g.end(), {(float)-N, (float)i, w, (float)N, (float)i, w});
    }
    gridCount_ = (GLsizei)(g.size() / 3);
    glGenVertexArrays(1, &gridVao_);
    glBindVertexArray(gridVao_);
    glGenBuffers(1, &gridVbo_);
    glBindBuffer(GL_ARRAY_BUFFER, gridVbo_);
    glBufferData(GL_ARRAY_BUFFER, g.size() * 4, g.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
    glBindVertexArray(0);

    glGenTextures(1, &whiteTex_);
    glBindTexture(GL_TEXTURE_2D, whiteTex_);
    uint32_t white = 0xFFFFFFFF;
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, &white);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
}

void Renderer::surfaceChanged(int w, int h) {
    w_ = std::max(1, w);
    h_ = std::max(1, h);
    glViewport(0, 0, w_, h_);
    std::lock_guard<std::mutex> l(mu_);
    if (!userMoved_ && bounds_.valid()) fitCamera(cam_, rotatedBounds(bounds_));
}

void Renderer::clearModel() {
    for (auto& g : gpu_) {
        glDeleteVertexArrays(1, &g.vao);
        GLuint b[3] = {g.vbo, g.ibo, g.lbo};
        glDeleteBuffers(3, b);
    }
    gpu_.clear();
    {
        std::lock_guard<std::mutex> dl(dataMu_);
        cpu_.clear();
        undo_.clear();
        redo_.clear();
        cpuBytes_ = 0;
        keepCpu_ = true;
        rebuildGpu_ = false;
        animated_ = false;
    }
    anim_.reset();
    std::vector<GLuint> uniq;
    for (auto& t : textures_)
        if (std::find(uniq.begin(), uniq.end(), t.second) == uniq.end()) uniq.push_back(t.second);
    for (GLuint t : uniq) glDeleteTextures(1, &t);
    textures_.clear();
    std::lock_guard<std::mutex> l(mu_);
    bounds_ = Bounds();
    parts_.clear();
    partXf_.clear();
    hidden_.clear();
    selected_ = -1;
    userMoved_ = false;
    lastGen_ = -1;
}

void Renderer::releaseGl() {
    clearModel();
    if (meshProg_) glDeleteProgram(meshProg_);
    if (bgProg_) glDeleteProgram(bgProg_);
    if (gridProg_) glDeleteProgram(gridProg_);
    if (pickProg_) glDeleteProgram(pickProg_);
    meshProg_ = bgProg_ = gridProg_ = pickProg_ = 0;
    if (bgVao_) glDeleteVertexArrays(1, &bgVao_);
    if (gridVao_) glDeleteVertexArrays(1, &gridVao_);
    if (gridVbo_) glDeleteBuffers(1, &gridVbo_);
    if (whiteTex_) glDeleteTextures(1, &whiteTex_);
    if (pickFbo_) glDeleteFramebuffers(1, &pickFbo_);
    if (pickColor_) glDeleteRenderbuffers(1, &pickColor_);
    if (pickDepth_) glDeleteRenderbuffers(1, &pickDepth_);
    bgVao_ = gridVao_ = gridVbo_ = whiteTex_ = pickFbo_ = pickColor_ = pickDepth_ = 0;
}

void Renderer::setTexture(int material, int glTex) {
    textures_[material] = (GLuint)glTex;
}

void Renderer::upload(std::unique_ptr<Chunk> c) {
    uploadGpu(*c);
    std::lock_guard<std::mutex> l(dataMu_);
    if (c->animSlot >= 0) animated_ = true;
    if (!keepCpu_) return;
    size_t bytes = c->verts.size() * sizeof(Vertex) + (c->tris.size() + c->lines.size()) * 4;
    if (cpuBytes_ + bytes > cpuBudget_) {
        keepCpu_ = false;  // too big to keep a second copy: viewing still works, editing doesn't
        cpu_.clear();
        undo_.clear();
        redo_.clear();
        cpuBytes_ = 0;
        return;
    }
    cpuBytes_ += bytes;
    cpu_.push_back(std::shared_ptr<Chunk>(c.release()));
}

void Renderer::uploadGpu(const Chunk& cc) {
    const Chunk* c = &cc;
    Gpu g;
    g.material = c->material;
    g.part = c->part;
    g.points = c->points;
    g.animSlot = c->animSlot;
    g.vertCount = (GLsizei)c->verts.size();
    g.triCount = (GLsizei)c->tris.size();
    g.lineCount = (GLsizei)c->lines.size();
    glGenVertexArrays(1, &g.vao);
    glBindVertexArray(g.vao);
    glGenBuffers(1, &g.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
    glBufferData(GL_ARRAY_BUFFER, c->verts.size() * sizeof(Vertex), c->verts.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Vertex), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_INT_2_10_10_10_REV, GL_TRUE, sizeof(Vertex), (void*)12);
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), (void*)16);
    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, GL_TRUE, sizeof(Vertex), (void*)24);
    if (!c->tris.empty()) {
        glGenBuffers(1, &g.ibo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, c->tris.size() * 4, c->tris.data(), GL_STATIC_DRAW);
    }
    glBindVertexArray(0);
    if (!c->lines.empty()) {
        glGenBuffers(1, &g.lbo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.lbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, c->lines.size() * 4, c->lines.data(), GL_STATIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }
    gpu_.push_back(g);
}

// ---------------------------------------------------------------- camera

Bounds Renderer::rotatedBounds(const Bounds& b) const {
    if (!b.valid() || !upZ_) return b;
    M4 m = rotX(-M_PI / 2);
    Bounds r;
    for (int i = 0; i < 8; i++) {
        V3 p(i & 1 ? b.mx[0] : b.mn[0], i & 2 ? b.mx[1] : b.mn[1], i & 4 ? b.mx[2] : b.mn[2]);
        V3 q = xform(m, p);
        float f[3] = {(float)q.x, (float)q.y, (float)q.z};
        r.add(f);
    }
    return r;
}

void Renderer::fitCamera(Cam& out, const Bounds& b, bool keepAngles) const {
    if (!b.valid()) return;
    V3 c((b.mn[0] + b.mx[0]) / 2, (b.mn[1] + b.mx[1]) / 2, (b.mn[2] + b.mx[2]) / 2);
    double r = V3(b.mx[0] - b.mn[0], b.mx[1] - b.mn[1], b.mx[2] - b.mn[2]).len() / 2;
    if (r <= 0) r = 1;
    double aspect = (double)w_ / (double)h_;
    double fovH = 2 * std::atan(std::tan(kFov / 2) * aspect);
    double f = std::min(kFov, fovH);
    out.target = c;
    out.dist = r / std::sin(f / 2) * 1.06;
    if (!keepAngles) {
        out.yaw = kDefYaw;
        out.pitch = kDefPitch;
    }
}

void Renderer::setUpAxisZ(bool z) {
    std::lock_guard<std::mutex> l(mu_);
    if (upZ_ == z) return;
    upZ_ = z;
    pendingReset_ = true;
    pendingAnimate_ = true;
}

void Renderer::touchDown() {
    std::lock_guard<std::mutex> l(mu_);
    velYaw_ = velPitch_ = 0;
    animStart_ = -1;
    userMoved_ = true;
}

void Renderer::orbit(float dx, float dy) {
    std::lock_guard<std::mutex> l(mu_);
    userMoved_ = true;
    cam_.yaw -= dx / (double)w_ * M_PI * 1.6;
    cam_.pitch += dy / (double)h_ * M_PI * 1.0;
    cam_.pitch = std::clamp(cam_.pitch, -1.569, 1.569);
}

void Renderer::fling(float vx, float vy) {
    std::lock_guard<std::mutex> l(mu_);
    velYaw_ = -vx / (double)w_ * M_PI * 1.6;
    velPitch_ = vy / (double)h_ * M_PI * 1.0;
}

void Renderer::pan(float dx, float dy) {
    std::lock_guard<std::mutex> l(mu_);
    userMoved_ = true;
    V3 dir(std::cos(cam_.pitch) * std::sin(cam_.yaw), std::sin(cam_.pitch), std::cos(cam_.pitch) * std::cos(cam_.yaw));
    V3 right = V3(0, 1, 0).cross(dir).norm();
    V3 up = dir.cross(right).norm();
    double k = 2 * cam_.dist * std::tan(kFov / 2) / (double)h_;
    cam_.target = cam_.target - right * (dx * k) + up * (dy * k);
}

void Renderer::zoom(float factor) {
    std::lock_guard<std::mutex> l(mu_);
    userMoved_ = true;
    if (factor <= 0) return;
    Bounds rb = rotatedBounds(bounds_);
    double r = rb.valid() ? V3(rb.mx[0] - rb.mn[0], rb.mx[1] - rb.mn[1], rb.mx[2] - rb.mn[2]).len() / 2 : 1;
    if (r <= 0) r = 1;
    cam_.dist = std::clamp(cam_.dist / factor, r * 0.002, r * 60);
}

void Renderer::resetView(bool animate) {
    std::lock_guard<std::mutex> l(mu_);
    pendingReset_ = true;
    pendingAnimate_ = animate;
    velYaw_ = velPitch_ = 0;
}

M4 Renderer::projection(const Frame& f, int w, int h, double nearP, double far) const {
    double aspect = (double)w / (double)h;
    if (!f.ortho) return perspective(kFov, aspect, nearP, far);
    double hw = f.cam.dist * std::tan(kFov / 2);
    return ortho(-hw * aspect, hw * aspect, -hw, hw, nearP, far);
}

void Renderer::viewPreset(int v) {
    std::lock_guard<std::mutex> l(mu_);
    Cam t = cam_;
    const double up = 1.569;
    switch (v) {
        case 0: t.yaw = 0; t.pitch = up; break;          // top
        case 1: t.yaw = 0; t.pitch = -up; break;         // bottom
        case 2: t.yaw = 0; t.pitch = 0; break;           // front
        case 3: t.yaw = M_PI; t.pitch = 0; break;        // back
        case 4: t.yaw = -M_PI / 2; t.pitch = 0; break;   // left
        default: t.yaw = M_PI / 2; t.pitch = 0; break;   // right
    }
    Bounds rb = rotatedBounds(bounds_);
    if (rb.valid()) {
        Cam fit;
        fitCamera(fit, rb, true);
        t.target = fit.target;
        t.dist = fit.dist;
    }
    animFrom_ = cam_;
    double d = std::fmod(animFrom_.yaw - t.yaw, 2 * M_PI);
    if (d > M_PI) d -= 2 * M_PI;
    if (d < -M_PI) d += 2 * M_PI;
    animFrom_.yaw = t.yaw + d;
    animTo_ = t;
    animStart_ = lastTime_;
    velYaw_ = velPitch_ = 0;
    userMoved_ = true;
}

void Renderer::setPartHinge(int part, const float* a, const float* b, float deg) {
    std::lock_guard<std::mutex> l(mu_);
    if (std::isnan(deg)) {
        partXf_.erase(part);
        return;
    }
    V3 A(a[0], a[1], a[2]), B(b[0], b[1], b[2]);
    if ((B - A).len() <= 0) return;
    partXf_[part] = rotateAbout(A, B - A, deg * M_PI / 180.0);
}

// ---------------------------------------------------------------- parts

void Renderer::setPartHidden(int part, bool hidden) {
    std::lock_guard<std::mutex> l(mu_);
    if (part < 0) return;
    if ((int)hidden_.size() <= part) hidden_.resize(part + 1, 0);
    hidden_[part] = hidden ? 1 : 0;
}

void Renderer::setAllPartsHidden(bool hidden) {
    std::lock_guard<std::mutex> l(mu_);
    hidden_.assign(std::max(parts_.size(), hidden_.size()), hidden ? 1 : 0);
}

void Renderer::isolatePart(int part) {
    std::lock_guard<std::mutex> l(mu_);
    hidden_.assign(std::max(parts_.size(), hidden_.size()), 1);
    if (part >= 0 && part < (int)hidden_.size()) hidden_[part] = 0;
}

void Renderer::requestPick(float x, float y) {
    std::lock_guard<std::mutex> l(mu_);
    pickX_ = x;
    pickY_ = y;
    pickPending_ = true;
    pickResult_ = -2;
}

// ---------------------------------------------------------------- frame

Renderer::Frame Renderer::frameState() {
    Frame f;
    f.cam = cam_;
    f.mode = mode_;
    f.th = theme_;
    f.grid = grid_;
    f.rb = rotatedBounds(bounds_);
    f.pointScale = pointScale_;
    f.pointMode = pointMode_;
    f.lighting = lighting_;
    f.upZ = upZ_;
    f.selected = selected_;
    f.ortho = ortho_;
    f.partXf = partXf_;
    f.hidden = hidden_;
    f.mats = materials_;
    return f;
}

bool Renderer::draw(Sink* sink, double now) {
    double dt = lastTime_ > 0 ? std::clamp(now - lastTime_, 0.0, 0.1) : 0.016;
    lastTime_ = now;
    bool more = false;

    {
        std::unique_lock<std::mutex> dl(dataMu_, std::try_to_lock);
        if (dl.owns_lock() && rebuildGpu_) {
            for (auto& g : gpu_) {
                glDeleteVertexArrays(1, &g.vao);
                GLuint b[3] = {g.vbo, g.ibo, g.lbo};
                glDeleteBuffers(3, b);
            }
            gpu_.clear();
            for (auto& c : cpu_) uploadGpu(*c);
            rebuildGpu_ = false;
            more = true;
        }
    }
    if (sink) {
        auto t0 = std::chrono::steady_clock::now();
        int n = 0;
        while (n < 12) {
            auto c = sink->pop();
            if (!c) break;
            upload(std::move(c));
            n++;
            if (std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count() > 0.008) break;
        }
        if (sink->pending() > 0) more = true;
    }

    Frame f;
    bool doPick = false;
    float px = 0, py = 0;
    {
        std::lock_guard<std::mutex> l(mu_);
        if (sink) {
            int gen = sink->generation.load();
            if (gen != lastGen_) {
                lastGen_ = gen;
                bounds_ = sink->bounds();
                materials_ = sink->materials();
                parts_ = sink->parts();
                if (sink->anim && !anim_) { anim_ = sink->anim; animDirty_ = true; }
                if (hidden_.size() < parts_.size()) hidden_.resize(parts_.size(), 0);
                if (!userMoved_) fitCamera(cam_, rotatedBounds(bounds_));
            }
        }
        Bounds rb = rotatedBounds(bounds_);
        if (pendingReset_ && rb.valid()) {
            pendingReset_ = false;
            Cam target;
            fitCamera(target, rb);
            if (pendingAnimate_) {
                animFrom_ = cam_;
                double d = std::fmod(animFrom_.yaw - target.yaw, 2 * M_PI);
                if (d > M_PI) d -= 2 * M_PI;
                if (d < -M_PI) d += 2 * M_PI;
                animFrom_.yaw = target.yaw + d;
                animTo_ = target;
                animStart_ = now;
            } else {
                cam_ = target;
            }
            userMoved_ = false;
        }
        if (pendingFocus_ >= 0) {
            int pi = pendingFocus_;
            pendingFocus_ = -1;
            if (pi < (int)parts_.size() && parts_[pi].bounds.valid()) {
                Cam target = cam_;
                fitCamera(target, rotatedBounds(parts_[pi].bounds), true);
                animFrom_ = cam_;
                animTo_ = target;
                animStart_ = now;
                userMoved_ = true;
            }
        }
        if (animStart_ >= 0) {
            double t = std::clamp((now - animStart_) / animDur_, 0.0, 1.0);
            double e = easeInOut(t);
            cam_.target = animFrom_.target + (animTo_.target - animFrom_.target) * e;
            cam_.dist = std::exp(std::log(animFrom_.dist) + (std::log(animTo_.dist) - std::log(animFrom_.dist)) * e);
            cam_.yaw = animFrom_.yaw + (animTo_.yaw - animFrom_.yaw) * e;
            cam_.pitch = animFrom_.pitch + (animTo_.pitch - animFrom_.pitch) * e;
            if (t >= 1) animStart_ = -1;
            more = true;
        }
        if (std::fabs(velYaw_) > 1e-3 || std::fabs(velPitch_) > 1e-3) {
            cam_.yaw += velYaw_ * dt;
            cam_.pitch = std::clamp(cam_.pitch + velPitch_ * dt, -1.569, 1.569);
            double k = std::exp(-3.5 * dt);
            velYaw_ *= k;
            velPitch_ *= k;
            more = true;
        }
        if (autoRotate_ && animStart_ < 0 && !turntable_) {
            cam_.yaw += 0.45 * dt;
            more = true;
        }
        if (anim_) {
            if (animPlaying_) animTime_ += dt * animSpeed_;
        }
        f = frameState();
        if (pickPending_) {
            doPick = true;
            pickPending_ = false;
            px = pickX_;
            py = pickY_;
        }
    }

    if (anim_) {
        int clip;
        double t;
        bool play, dirty;
        {
            std::lock_guard<std::mutex> l(mu_);
            clip = animClip_;
            t = animTime_;
            play = animPlaying_;
            dirty = animDirty_ || play;
            animDirty_ = false;
        }
        if (dirty) applyAnim(clip, t);
        if (play) more = true;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    renderScene(f, w_, h_, true, true);
    if (doPick) {
        renderPick(f, (int)px, h_ - 1 - (int)py);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, w_, h_);
    }
    return more;
}

void Renderer::renderScene(const Frame& f, int w, int h, bool background, bool allowGrid) {
    const ThemeColors& th = f.th;
    glViewport(0, 0, w, h);
    if (background) glClearColor(th.bgBottom[0], th.bgBottom[1], th.bgBottom[2], 1);
    else glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    glDisable(GL_DEPTH_TEST);
    glDepthMask(GL_FALSE);
    if (background) {
        glUseProgram(bgProg_);
        glUniform3fv(U(bgProg_, "uTop"), 1, th.bgTop);
        glUniform3fv(U(bgProg_, "uBottom"), 1, th.bgBottom);
        glUniform1f(U(bgProg_, "uAspect"), (float)w / (float)h);
        glBindVertexArray(bgVao_);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }
    const Bounds& rb = f.rb;
    if (!rb.valid()) {
        glBindVertexArray(0);
        return;
    }

    V3 center((rb.mn[0] + rb.mx[0]) / 2, (rb.mn[1] + rb.mx[1]) / 2, (rb.mn[2] + rb.mx[2]) / 2);
    double radius = V3(rb.mx[0] - rb.mn[0], rb.mx[1] - rb.mn[1], rb.mx[2] - rb.mn[2]).len() / 2;
    if (radius <= 0) radius = 1;
    const Cam& cam = f.cam;
    V3 dir(std::cos(cam.pitch) * std::sin(cam.yaw), std::sin(cam.pitch), std::cos(cam.pitch) * std::cos(cam.yaw));
    V3 eye = cam.target + dir * cam.dist;
    M4 view = lookAt(eye, cam.target, V3(0, 1, 0));
    double dc = (eye - center).len();
    double far = dc + radius * 8.0 * 1.05;
    double nearP = std::max(dc - radius * 1.05, far * 2e-5);
    if (f.ortho) nearP = std::max(dc - radius * 1.5, far * 1e-4);
    M4 proj = projection(f, w, h, nearP, far);
    M4 model = f.upZ ? rotX(-M_PI / 2) : M4::identity();
    M4 mv = view * model;

    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);

    if (f.grid && allowGrid) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        drawGrid(view, proj, rb, th.grid);
        glDisable(GL_BLEND);
    }
    glDepthMask(GL_TRUE);
    glDisable(GL_CULL_FACE);  // double-sided: open or flipped meshes never look see-through

    float fmv[16], fp[16], fm[16], fn[9];
    mv.toFloat(fmv);
    proj.toFloat(fp);
    model.toFloat(fm);
    fn[0] = fmv[0]; fn[1] = fmv[1]; fn[2] = fmv[2];
    fn[3] = fmv[4]; fn[4] = fmv[5]; fn[5] = fmv[6];
    fn[6] = fmv[8]; fn[7] = fmv[9]; fn[8] = fmv[10];

    const Lighting& L = kLights[std::clamp(f.lighting, 0, kLightCount - 1)];
    const GLuint P = meshProg_;
    glUseProgram(P);
    GLint uShade = U(P, "uShade"), uKd = U(P, "uKd"), uHasTex = U(P, "uHasTex"), uLine = U(P, "uLine"), uSel = U(P, "uSel");
    GLint uMVl = U(P, "uMV"), uNl = U(P, "uN");
    auto setXf = [&](const Gpu& g) {
        auto pit = f.partXf.find(g.part);
        if (!g.hasXf && pit == f.partXf.end()) {
            glUniformMatrix4fv(uMVl, 1, GL_FALSE, fmv);
            glUniformMatrix3fv(uNl, 1, GL_FALSE, fn);
            return;
        }
        M4 x = M4::identity();
        if (pit != f.partXf.end()) x = pit->second;
        if (g.hasXf) {
            M4 ax;
            for (int i = 0; i < 16; i++) ax.m[i] = g.xf[i];
            x = x * ax;
        }
        M4 m2 = mv * x;
        float a[16], nn[9];
        m2.toFloat(a);
        nn[0] = a[0]; nn[1] = a[1]; nn[2] = a[2]; nn[3] = a[4]; nn[4] = a[5]; nn[5] = a[6]; nn[6] = a[8]; nn[7] = a[9]; nn[8] = a[10];
        glUniformMatrix4fv(uMVl, 1, GL_FALSE, a);
        glUniformMatrix3fv(uNl, 1, GL_FALSE, nn);
    };
    glUniformMatrix4fv(U(P, "uMV"), 1, GL_FALSE, fmv);
    glUniformMatrix4fv(U(P, "uP"), 1, GL_FALSE, fp);
    glUniformMatrix4fv(U(P, "uM"), 1, GL_FALSE, fm);
    glUniformMatrix3fv(U(P, "uN"), 1, GL_FALSE, fn);
    glUniform1f(U(P, "uPointSize"), f.pointScale * ((float)w / (float)std::max(1, w_)));
    glUniform3fv(U(P, "uClay"), 1, th.clay);
    glUniform1i(U(P, "uTex"), 0);
    glUniform3fv(U(P, "uL1"), 1, L.l1);
    glUniform3fv(U(P, "uC1"), 1, L.c1);
    glUniform3fv(U(P, "uL2"), 1, L.l2);
    glUniform3fv(U(P, "uC2"), 1, L.c2);
    glUniform3fv(U(P, "uSky"), 1, L.sky);
    glUniform3fv(U(P, "uGround"), 1, L.ground);
    glUniform1f(U(P, "uSpec"), L.spec);
    glUniform1f(U(P, "uRim"), L.rim);
    glUniform1f(U(P, "uFlat"), L.flat);
    glUniform3f(U(P, "uSelColor"), 1.0f, 0.62f, 0.15f);
    glUniform1i(U(P, "uPointMode"), f.pointMode);
    glUniform2f(U(P, "uHRange"), (float)rb.mn[1], (float)rb.mx[1]);
    glActiveTexture(GL_TEXTURE0);

    int mode = f.mode;
    bool drawFill = mode != MODE_WIRE;
    bool depthOnlyFill = mode == MODE_WIRE_CLEAN;
    bool drawLines = mode == MODE_WIRE || mode == MODE_SOLID_WIRE || mode == MODE_TEX_WIRE || mode == MODE_WIRE_CLEAN;
    bool textured = mode == MODE_TEXTURED || mode == MODE_TEX_WIRE;

    if (drawFill) {
        if (drawLines) {
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(1.0f, 1.0f);
        }
        if (depthOnlyFill) glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        for (auto& g : gpu_) {
            if (g.points || !g.triCount || partHidden(f, g.part)) continue;
            const Material* m = (g.material >= 0 && g.material < (int)f.mats.size()) ? &f.mats[g.material] : nullptr;
            float kd[4] = {0.8f, 0.8f, 0.8f, 1.f};
            if (m) memcpy(kd, m->kd, sizeof kd);
            auto tx = textures_.find(g.material);
            bool hasTex = textured && tx != textures_.end();
            glUniform1i(uShade, textured ? 0 : 1);
            glUniform4fv(uKd, 1, kd);
            glUniform1i(uHasTex, hasTex ? 1 : 0);
            glUniform1f(uSel, g.part == f.selected ? 1.f : 0.f);
            glBindTexture(GL_TEXTURE_2D, hasTex ? tx->second : whiteTex_);
            setXf(g);
            glBindVertexArray(g.vao);
            glDrawElements(GL_TRIANGLES, g.triCount, GL_UNSIGNED_INT, nullptr);
        }
        if (depthOnlyFill) glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glDisable(GL_POLYGON_OFFSET_FILL);
    }
    if (drawLines) {
        float lc[4];
        memcpy(lc, th.wire, sizeof lc);
        bool blend = mode == MODE_SOLID_WIRE || mode == MODE_TEX_WIRE;
        if (blend) {
            lc[3] = 0.55f;
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        glUniform1i(uShade, 2);
        glUniform4fv(uLine, 1, lc);
        for (auto& g : gpu_) {
            if (g.points || !g.lbo || partHidden(f, g.part)) continue;
            glUniform1f(uSel, g.part == f.selected ? 1.f : 0.f);
            setXf(g);
            glBindVertexArray(g.vao);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.lbo);
            glDrawElements(GL_LINES, g.lineCount, GL_UNSIGNED_INT, nullptr);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.ibo);
        }
        glDisable(GL_BLEND);
    }
    for (auto& g : gpu_) {
        if (!g.points || partHidden(f, g.part)) continue;
        glUniform1i(uShade, 3);
        float kd[4] = {1, 1, 1, 1};
        if (!textured || f.pointMode == 1) { kd[0] = th.clay[0]; kd[1] = th.clay[1]; kd[2] = th.clay[2]; }
        if (mode == MODE_WIRE || mode == MODE_WIRE_CLEAN) memcpy(kd, th.wire, sizeof(float) * 3);
        glUniform4fv(uKd, 1, kd);
        glUniform1f(uSel, g.part == f.selected ? 1.f : 0.f);
        setXf(g);
        glBindVertexArray(g.vao);
        glDrawArrays(GL_POINTS, 0, g.vertCount);
    }
    glBindVertexArray(0);
}

void Renderer::applyAnim(int clip, double seconds) {
    if (!anim_) return;
    std::vector<aiMatrix4x4> G;
    anim_->evaluate(clip, seconds, G);
    std::vector<Vertex> tmp;
    for (auto& g : gpu_) {
        if (g.animSlot < 0 || g.animSlot >= (int)anim_->inst.size()) continue;
        const AnimInstance& in = anim_->inst[g.animSlot];
        if (in.skinned) {
            skinInstance(in, G, tmp);
            glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, (GLsizeiptr)(tmp.size() * sizeof(Vertex)), tmp.data());
            g.hasXf = false;
        } else if (in.node >= 0 && in.node < (int)G.size()) {
            aiMatrix4x4 m = G[in.node] * in.bindInv;
            const float* r = &m.a1;  // row-major
            for (int row = 0; row < 4; row++)
                for (int col = 0; col < 4; col++) g.xf[col * 4 + row] = r[row * 4 + col];
            g.hasXf = true;
        }
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

std::vector<std::string> Renderer::animClips() {
    std::vector<std::string> out;
    std::lock_guard<std::mutex> l(mu_);
    if (!anim_) return out;
    for (size_t i = 0; i < anim_->clips.size(); i++) {
        char buf[48];
        snprintf(buf, sizeof buf, "|%.3f", anim_->clipSeconds((int)i));
        out.push_back(anim_->clips[i].name + buf);
    }
    return out;
}

void Renderer::renderPick(const Frame& f, int x, int y) {
    if (x < 0 || y < 0 || x >= w_ || y >= h_) { pickResult_ = -1; return; }
    if (!pickFbo_ || pickW_ != w_ || pickH_ != h_) {
        if (pickFbo_) glDeleteFramebuffers(1, &pickFbo_);
        if (pickColor_) glDeleteRenderbuffers(1, &pickColor_);
        if (pickDepth_) glDeleteRenderbuffers(1, &pickDepth_);
        glGenFramebuffers(1, &pickFbo_);
        glGenRenderbuffers(1, &pickColor_);
        glGenRenderbuffers(1, &pickDepth_);
        glBindRenderbuffer(GL_RENDERBUFFER, pickColor_);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w_, h_);
        glBindRenderbuffer(GL_RENDERBUFFER, pickDepth_);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w_, h_);
        glBindFramebuffer(GL_FRAMEBUFFER, pickFbo_);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, pickColor_);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, pickDepth_);
        pickW_ = w_;
        pickH_ = h_;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, pickFbo_);
    glViewport(0, 0, w_, h_);
    glEnable(GL_SCISSOR_TEST);
    glScissor(x, y, 1, 1);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);

    const Bounds& rb = f.rb;
    if (rb.valid()) {
        V3 center((rb.mn[0] + rb.mx[0]) / 2, (rb.mn[1] + rb.mx[1]) / 2, (rb.mn[2] + rb.mx[2]) / 2);
        double radius = V3(rb.mx[0] - rb.mn[0], rb.mx[1] - rb.mn[1], rb.mx[2] - rb.mn[2]).len() / 2;
        if (radius <= 0) radius = 1;
        const Cam& cam = f.cam;
        V3 dir(std::cos(cam.pitch) * std::sin(cam.yaw), std::sin(cam.pitch), std::cos(cam.pitch) * std::cos(cam.yaw));
        V3 eye = cam.target + dir * cam.dist;
        M4 view = lookAt(eye, cam.target, V3(0, 1, 0));
        double dc = (eye - center).len();
        double far = dc + radius * 8.0 * 1.05;
        double nearP = std::max(dc - radius * 1.05, far * 2e-5);
        if (f.ortho) nearP = std::max(dc - radius * 1.5, far * 1e-4);
        M4 proj = projection(f, w_, h_, nearP, far);
        M4 mv = view * (f.upZ ? rotX(-M_PI / 2) : M4::identity());
        float fmv[16], fp[16];
        mv.toFloat(fmv);
        proj.toFloat(fp);
        glUseProgram(pickProg_);
        glUniformMatrix4fv(U(pickProg_, "uMV"), 1, GL_FALSE, fmv);
        glUniformMatrix4fv(U(pickProg_, "uP"), 1, GL_FALSE, fp);
        glUniform1f(U(pickProg_, "uPointSize"), std::max(6.f, f.pointScale * 3.f));
        GLint uId = U(pickProg_, "uId");
        for (auto& g : gpu_) {
            if (partHidden(f, g.part)) continue;
            int id = g.part + 1;
            glUniform4f(uId, (id & 255) / 255.f, ((id >> 8) & 255) / 255.f, ((id >> 16) & 255) / 255.f, 1.f);
            auto pit = f.partXf.find(g.part);
            if (g.hasXf || pit != f.partXf.end()) {
                M4 x = pit != f.partXf.end() ? pit->second : M4::identity();
                if (g.hasXf) {
                    M4 ax;
                    for (int i = 0; i < 16; i++) ax.m[i] = g.xf[i];
                    x = x * ax;
                }
                float a[16];
                (mv * x).toFloat(a);
                glUniformMatrix4fv(U(pickProg_, "uMV"), 1, GL_FALSE, a);
            } else {
                glUniformMatrix4fv(U(pickProg_, "uMV"), 1, GL_FALSE, fmv);
            }
            glBindVertexArray(g.vao);
            if (g.points) glDrawArrays(GL_POINTS, 0, g.vertCount);
            else if (g.triCount) glDrawElements(GL_TRIANGLES, g.triCount, GL_UNSIGNED_INT, nullptr);
            else if (g.lbo) {
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.lbo);
                glDrawElements(GL_LINES, g.lineCount, GL_UNSIGNED_INT, nullptr);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.ibo);
            }
        }
        glBindVertexArray(0);
    }
    uint8_t px[4] = {0, 0, 0, 0};
    glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    glDisable(GL_SCISSOR_TEST);
    int id = px[0] | (px[1] << 8) | (px[2] << 16);
    pickResult_ = id - 1;  // -1 when nothing was hit
}

// ---------------------------------------------------------------- capture

bool Renderer::snapshot(int w, int h, bool transparent, uint8_t* out) {
    Frame f;
    {
        std::lock_guard<std::mutex> l(mu_);
        f = frameState();
        f.selected = -1;
    }
    GLuint fbo = 0, color = 0, depth = 0;
    glGenFramebuffers(1, &fbo);
    glGenRenderbuffers(1, &color);
    glGenRenderbuffers(1, &depth);
    glBindRenderbuffer(GL_RENDERBUFFER, color);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
    glBindRenderbuffer(GL_RENDERBUFFER, depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (ok) {
        renderScene(f, w, h, !transparent, !transparent);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteRenderbuffers(1, &color);
    glDeleteRenderbuffers(1, &depth);
    glViewport(0, 0, w_, h_);
    return ok;
}

void Renderer::beginTurntable() {
    std::lock_guard<std::mutex> l(mu_);
    turntable_ = true;
    turnBase_ = cam_.yaw;
    velYaw_ = velPitch_ = 0;
    animStart_ = -1;
}

void Renderer::turntableFrame(double angle, int w, int h) {
    Frame f;
    {
        std::lock_guard<std::mutex> l(mu_);
        f = frameState();
        f.selected = -1;
        f.cam.yaw = turnBase_ + angle;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    renderScene(f, w, h, true, true);
}

void Renderer::endTurntable() {
    std::lock_guard<std::mutex> l(mu_);
    turntable_ = false;
}

void Renderer::drawGrid(const M4& view, const M4& proj, const Bounds& rb, const float* color) {
    double sx = rb.mx[0] - rb.mn[0], sz = rb.mx[2] - rb.mn[2];
    double r = V3(sx, rb.mx[1] - rb.mn[1], sz).len() / 2;
    if (r <= 0) return;
    double spacing = std::pow(10.0, std::floor(std::log10(r * 1.5 / 8.0)));
    if (r * 1.5 / spacing > 30) spacing *= 5;
    else if (r * 1.5 / spacing > 12) spacing *= 2;
    double cx = std::round(((rb.mn[0] + rb.mx[0]) / 2) / spacing) * spacing;
    double cz = std::round(((rb.mn[2] + rb.mx[2]) / 2) / spacing) * spacing;
    double fy = rb.mn[1] - r * 0.002;
    M4 vp = proj * view;
    float f[16];
    vp.toFloat(f);
    glUseProgram(gridProg_);
    glUniformMatrix4fv(U(gridProg_, "uVP"), 1, GL_FALSE, f);
    glUniform3f(U(gridProg_, "uOff"), (float)cx, (float)fy, (float)cz);
    glUniform1f(U(gridProg_, "uSpacing"), (float)spacing);
    glUniform1f(U(gridProg_, "uHalf"), 40.f);
    glUniform4fv(U(gridProg_, "uColor"), 1, color);
    glBindVertexArray(gridVao_);
    glDrawArrays(GL_LINES, 0, gridCount_);
}

}  // namespace vx
