#include "renderer.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Vertexa", __VA_ARGS__)

namespace vx {
namespace {

const double kFov = 45.0 * M_PI / 180.0;
const double kDefYaw = 0.65, kDefPitch = 0.32;

const char* kMeshVs = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec4 aNrm;
layout(location=2) in vec2 aUv;
layout(location=3) in vec4 aCol;
uniform mat4 uMV;
uniform mat4 uP;
uniform mat3 uN;
uniform float uPointSize;
out vec3 vN; out vec3 vV; out vec2 vUv; out vec4 vCol;
void main(){
  vec4 p = uMV * vec4(aPos, 1.0);
  vV = p.xyz;
  vN = uN * aNrm.xyz;
  vUv = aUv;
  vCol = aCol;
  gl_Position = uP * p;
  gl_PointSize = uPointSize;
})";

const char* kMeshFs = R"(#version 300 es
precision highp float;
in vec3 vN; in vec3 vV; in vec2 vUv; in vec4 vCol;
uniform int uShade;      // 0 textured, 1 clay, 2 flat line colour, 3 points
uniform vec4 uKd;
uniform sampler2D uTex;
uniform int uHasTex;
uniform vec3 uClay;
uniform vec4 uLine;
out vec4 o;
void main(){
  if (uShade == 2) { o = uLine; return; }
  if (uShade == 3) { o = vec4(vCol.rgb * uKd.rgb, 1.0); return; }
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
  vec3 L1 = normalize(vec3(-0.45, 0.65, 0.62));
  vec3 L2 = normalize(vec3(0.70, 0.10, 0.45));
  float d1 = max(dot(N, L1), 0.0);
  float d2 = max(dot(N, L2), 0.0);
  float hemi = 0.5 + 0.5 * N.y;
  vec3 amb = mix(vec3(0.22, 0.22, 0.25), vec3(0.42, 0.43, 0.46), hemi);
  vec3 H = normalize(L1 + V);
  float spec = pow(max(dot(N, H), 0.0), 40.0);
  float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);
  vec3 lit = base * (amb + vec3(1.0, 0.97, 0.93) * d1 * 0.80 + vec3(0.75, 0.82, 1.0) * d2 * 0.30);
  if (uShade == 1) {
    lit += vec3(spec * 0.22) + vec3(0.55, 0.75, 1.0) * rim * 0.18;
  } else {
    lit = mix(lit, base, 0.38) + vec3(spec * 0.06) + rim * 0.05;
  }
  o = vec4(lit, 1.0);
})";

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

}  // namespace

bool Renderer::surfaceCreated() {
    bool lost = hadContext_ && !gpu_.empty();
    gpu_.clear();  // old context objects are gone with the old context
    textures_.clear();
    hadContext_ = true;
    buildPrograms();
    return lost;
}

void Renderer::buildPrograms() {
    meshProg_ = link(kMeshVs, kMeshFs);
    bgProg_ = link(kBgVs, kBgFs);
    gridProg_ = link(kGridVs, kGridFs);
    glGenVertexArrays(1, &bgVao_);

    // grid: 40 cells each side, major line every 5
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
    for (auto& t : textures_) glDeleteTextures(1, &t.second);
    textures_.clear();
    std::lock_guard<std::mutex> l(mu_);
    bounds_ = Bounds();
    userMoved_ = false;
    fitted_ = false;
    lastGen_ = -1;
}

void Renderer::releaseGl() {
    clearModel();
    if (meshProg_) glDeleteProgram(meshProg_);
    if (bgProg_) glDeleteProgram(bgProg_);
    if (gridProg_) glDeleteProgram(gridProg_);
    meshProg_ = bgProg_ = gridProg_ = 0;
    if (bgVao_) glDeleteVertexArrays(1, &bgVao_);
    if (gridVao_) glDeleteVertexArrays(1, &gridVao_);
    if (gridVbo_) glDeleteBuffers(1, &gridVbo_);
    if (whiteTex_) glDeleteTextures(1, &whiteTex_);
    bgVao_ = gridVao_ = gridVbo_ = whiteTex_ = 0;
}

void Renderer::setTexture(int material, int glTex) {
    auto it = textures_.find(material);
    if (it != textures_.end() && it->second != (GLuint)glTex) glDeleteTextures(1, &it->second);
    textures_[material] = (GLuint)glTex;
}

void Renderer::setMaterials(const std::vector<Material>& mats) {
    std::lock_guard<std::mutex> l(mu_);
    materials_ = mats;
}

void Renderer::upload(std::unique_ptr<Chunk> c) {
    Gpu g;
    g.material = c->material;
    g.points = c->points;
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

void Renderer::fitCamera(Cam& out, const Bounds& b) const {
    if (!b.valid()) return;
    V3 c((b.mn[0] + b.mx[0]) / 2, (b.mn[1] + b.mx[1]) / 2, (b.mn[2] + b.mx[2]) / 2);
    double r = V3(b.mx[0] - b.mn[0], b.mx[1] - b.mn[1], b.mx[2] - b.mn[2]).len() / 2;
    if (r <= 0) r = 1;
    double aspect = (double)w_ / (double)h_;
    double fovH = 2 * std::atan(std::tan(kFov / 2) * aspect);
    double f = std::min(kFov, fovH);
    out.target = c;
    out.dist = r / std::sin(f / 2) * 1.06;
    out.yaw = kDefYaw;
    out.pitch = kDefPitch;
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
    cam_.pitch = std::clamp(cam_.pitch, -1.55, 1.55);
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
    cam_.dist = std::clamp(cam_.dist / factor, r * 0.015, r * 60);
}

void Renderer::resetView(bool animate) {
    std::lock_guard<std::mutex> l(mu_);
    pendingReset_ = true;
    pendingAnimate_ = animate;
    velYaw_ = velPitch_ = 0;
}

bool Renderer::draw(Sink* sink, double now) {
    double dt = lastTime_ > 0 ? std::clamp(now - lastTime_, 0.0, 0.1) : 0.016;
    lastTime_ = now;
    bool more = false;

    // progressive GPU upload with a per-frame time budget
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

    Cam cam;
    int mode;
    ThemeColors th;
    bool grid;
    Bounds rb;
    float pointScale;
    bool upZ;
    std::vector<Material> mats;
    {
        std::lock_guard<std::mutex> l(mu_);
        if (sink) {
            int gen = sink->generation.load();
            if (gen != lastGen_) {
                lastGen_ = gen;
                bounds_ = sink->bounds();
                materials_ = sink->materials();
                if (!userMoved_) {
                    fitCamera(cam_, rotatedBounds(bounds_));
                    fitted_ = true;
                }
            }
        }
        rb = rotatedBounds(bounds_);
        if (pendingReset_ && rb.valid()) {
            pendingReset_ = false;
            Cam target;
            fitCamera(target, rb);
            if (pendingAnimate_) {
                animFrom_ = cam_;
                // take the short way round
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
            cam_.pitch = std::clamp(cam_.pitch + velPitch_ * dt, -1.55, 1.55);
            double k = std::exp(-3.5 * dt);
            velYaw_ *= k;
            velPitch_ *= k;
            more = true;
        }
        if (autoRotate_ && animStart_ < 0) {
            cam_.yaw += 0.45 * dt;
            more = true;
        }
        cam = cam_;
        mode = mode_;
        th = theme_;
        grid = grid_;
        pointScale = pointScale_;
        upZ = upZ_;
        mats = materials_;
    }

    // ---- frame
    glViewport(0, 0, w_, h_);
    glClearColor(th.bgBottom[0], th.bgBottom[1], th.bgBottom[2], 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    glDisable(GL_DEPTH_TEST);
    glDepthMask(GL_FALSE);
    glUseProgram(bgProg_);
    glUniform3fv(glGetUniformLocation(bgProg_, "uTop"), 1, th.bgTop);
    glUniform3fv(glGetUniformLocation(bgProg_, "uBottom"), 1, th.bgBottom);
    glUniform1f(glGetUniformLocation(bgProg_, "uAspect"), (float)w_ / (float)h_);
    glBindVertexArray(bgVao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    if (!rb.valid()) {
        glBindVertexArray(0);
        return more;
    }

    // camera, with clip planes hugging the model's bounding sphere so nothing is ever cut
    V3 center((rb.mn[0] + rb.mx[0]) / 2, (rb.mn[1] + rb.mx[1]) / 2, (rb.mn[2] + rb.mx[2]) / 2);
    double radius = V3(rb.mx[0] - rb.mn[0], rb.mx[1] - rb.mn[1], rb.mx[2] - rb.mn[2]).len() / 2;
    if (radius <= 0) radius = 1;
    V3 dir(std::cos(cam.pitch) * std::sin(cam.yaw), std::sin(cam.pitch), std::cos(cam.pitch) * std::cos(cam.yaw));
    V3 eye = cam.target + dir * cam.dist;
    M4 view = lookAt(eye, cam.target, V3(0, 1, 0));
    double dc = (eye - center).len();
    double gridR = radius * 8.0;
    double far = dc + std::max(radius, gridR) * 1.05;
    double nearP = dc - radius * 1.05;
    nearP = std::max(nearP, far * 2e-5);
    M4 proj = perspective(kFov, (double)w_ / (double)h_, nearP, far);
    M4 model = upZ ? rotX(-M_PI / 2) : M4::identity();
    M4 mv = view * model;

    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);

    if (grid) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(GL_FALSE);
        drawGrid(view, proj, rb, th.grid);
        glDisable(GL_BLEND);
    }
    glDepthMask(GL_TRUE);
    glDisable(GL_CULL_FACE);  // double-sided: open or flipped meshes never look see-through

    float fmv[16], fp[16], fn[9];
    mv.toFloat(fmv);
    proj.toFloat(fp);
    // normal matrix (rotation only, view is orthonormal)
    fn[0] = fmv[0]; fn[1] = fmv[1]; fn[2] = fmv[2];
    fn[3] = fmv[4]; fn[4] = fmv[5]; fn[5] = fmv[6];
    fn[6] = fmv[8]; fn[7] = fmv[9]; fn[8] = fmv[10];

    glUseProgram(meshProg_);
    GLint uShade = glGetUniformLocation(meshProg_, "uShade");
    GLint uKd = glGetUniformLocation(meshProg_, "uKd");
    GLint uHasTex = glGetUniformLocation(meshProg_, "uHasTex");
    GLint uLine = glGetUniformLocation(meshProg_, "uLine");
    glUniformMatrix4fv(glGetUniformLocation(meshProg_, "uMV"), 1, GL_FALSE, fmv);
    glUniformMatrix4fv(glGetUniformLocation(meshProg_, "uP"), 1, GL_FALSE, fp);
    glUniformMatrix3fv(glGetUniformLocation(meshProg_, "uN"), 1, GL_FALSE, fn);
    glUniform1f(glGetUniformLocation(meshProg_, "uPointSize"), pointScale);
    glUniform3fv(glGetUniformLocation(meshProg_, "uClay"), 1, th.clay);
    glUniform1i(glGetUniformLocation(meshProg_, "uTex"), 0);
    glActiveTexture(GL_TEXTURE0);

    bool drawFill = mode != MODE_WIRE;
    bool drawLines = mode == MODE_WIRE || mode == MODE_SOLID_WIRE || mode == MODE_TEX_WIRE;
    bool textured = mode == MODE_TEXTURED || mode == MODE_TEX_WIRE;

    if (drawFill) {
        if (drawLines) {
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(1.0f, 1.0f);
        }
        for (auto& g : gpu_) {
            if (g.points || !g.triCount) continue;
            const Material* m = (g.material >= 0 && g.material < (int)mats.size()) ? &mats[g.material] : nullptr;
            float kd[4] = {0.8f, 0.8f, 0.8f, 1.f};
            if (m) memcpy(kd, m->kd, sizeof kd);
            auto tx = textures_.find(g.material);
            bool hasTex = textured && tx != textures_.end();
            glUniform1i(uShade, textured ? 0 : 1);
            glUniform4fv(uKd, 1, kd);
            glUniform1i(uHasTex, hasTex ? 1 : 0);
            glBindTexture(GL_TEXTURE_2D, hasTex ? tx->second : whiteTex_);
            glBindVertexArray(g.vao);
            glDrawElements(GL_TRIANGLES, g.triCount, GL_UNSIGNED_INT, nullptr);
        }
        glDisable(GL_POLYGON_OFFSET_FILL);
    }
    if (drawLines) {
        float lc[4];
        memcpy(lc, th.wire, sizeof lc);
        if (mode != MODE_WIRE) {
            lc[3] = 0.55f;
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        glUniform1i(uShade, 2);
        glUniform4fv(uLine, 1, lc);
        for (auto& g : gpu_) {
            if (g.points || !g.lbo) continue;
            glBindVertexArray(g.vao);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.lbo);
            glDrawElements(GL_LINES, g.lineCount, GL_UNSIGNED_INT, nullptr);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.ibo);
        }
        glDisable(GL_BLEND);
    }
    // point clouds
    for (auto& g : gpu_) {
        if (!g.points) continue;
        glUniform1i(uShade, 3);
        float kd[4] = {1, 1, 1, 1};
        if (!textured) { kd[0] = th.clay[0]; kd[1] = th.clay[1]; kd[2] = th.clay[2]; }
        if (mode == MODE_WIRE) memcpy(kd, th.wire, sizeof(float) * 3);
        glUniform4fv(uKd, 1, kd);
        glBindVertexArray(g.vao);
        glDrawArrays(GL_POINTS, 0, g.vertCount);
    }
    glBindVertexArray(0);
    return more;
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
    glUniformMatrix4fv(glGetUniformLocation(gridProg_, "uVP"), 1, GL_FALSE, f);
    glUniform3f(glGetUniformLocation(gridProg_, "uOff"), (float)cx, (float)fy, (float)cz);
    glUniform1f(glGetUniformLocation(gridProg_, "uSpacing"), (float)spacing);
    glUniform1f(glGetUniformLocation(gridProg_, "uHalf"), 40.f);
    glUniform4fv(glGetUniformLocation(gridProg_, "uColor"), 1, color);
    glBindVertexArray(gridVao_);
    glDrawArrays(GL_LINES, 0, gridCount_);
}

}  // namespace vx
