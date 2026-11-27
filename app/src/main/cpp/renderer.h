#pragma once
#include <GLES3/gl3.h>

#include <map>
#include <memory>
#include <mutex>
#include <vector>

#include "mesh.h"
#include "vmath.h"

namespace vx {

enum Mode { MODE_TEXTURED = 0, MODE_SOLID = 1, MODE_WIRE = 2, MODE_SOLID_WIRE = 3, MODE_TEX_WIRE = 4 };

struct ThemeColors {
    float bgTop[3] = {0.07f, 0.08f, 0.11f};
    float bgBottom[3] = {0.02f, 0.02f, 0.04f};
    float grid[4] = {0.45f, 0.55f, 0.75f, 0.35f};
    float wire[4] = {0.30f, 0.85f, 1.0f, 1.0f};
    float clay[3] = {0.78f, 0.78f, 0.80f};
};

class Renderer {
public:
    // GL thread
    bool surfaceCreated();  // returns true if a previous context had model data (needs reload)
    void surfaceChanged(int w, int h);
    bool draw(Sink* sink, double nowSec);  // returns true when another frame is needed
    void clearModel();
    void setTexture(int material, int glTex);

    // any thread
    void setMode(int m) { std::lock_guard<std::mutex> l(mu_); mode_ = m; }
    void setTheme(const ThemeColors& t) { std::lock_guard<std::mutex> l(mu_); theme_ = t; }
    void setGrid(bool g) { std::lock_guard<std::mutex> l(mu_); grid_ = g; }
    void setAutoRotate(bool a) { std::lock_guard<std::mutex> l(mu_); autoRotate_ = a; }
    void setUpAxisZ(bool z);
    void setPointScale(float s) { std::lock_guard<std::mutex> l(mu_); pointScale_ = s; }
    void touchDown();
    void orbit(float dx, float dy);
    void pan(float dx, float dy);
    void zoom(float factor);
    void fling(float vx, float vy);
    void resetView(bool animate);
    void setMaterials(const std::vector<Material>& mats);

private:
    struct Gpu {
        GLuint vao = 0, vbo = 0, ibo = 0, lbo = 0;
        GLsizei triCount = 0, lineCount = 0, vertCount = 0;
        int material = 0;
        bool points = false;
    };
    struct Cam {
        V3 target;
        double yaw = 0.6, pitch = 0.35, dist = 1;
    };

    void upload(std::unique_ptr<Chunk> c);
    void fitCamera(Cam& out, const Bounds& b) const;
    Bounds rotatedBounds(const Bounds& b) const;
    void buildPrograms();
    void drawGrid(const M4& view, const M4& proj, const Bounds& rb, const float* color);

    std::mutex mu_;
    int mode_ = MODE_TEXTURED;
    ThemeColors theme_;
    bool grid_ = true;
    bool autoRotate_ = false;
    bool upZ_ = false;
    float pointScale_ = 2.f;

    Cam cam_;
    Cam animFrom_, animTo_;
    double animStart_ = -1, animDur_ = 0.65;
    bool pendingReset_ = false, pendingAnimate_ = false;
    double velYaw_ = 0, velPitch_ = 0;
    bool userMoved_ = false;
    int lastGen_ = -1;
    double lastTime_ = 0;
    bool fitted_ = false;

    int w_ = 1, h_ = 1;
    bool hadContext_ = false;
    GLuint meshProg_ = 0, bgProg_ = 0, gridProg_ = 0;
    GLuint bgVao_ = 0, gridVao_ = 0, gridVbo_ = 0;
    GLsizei gridCount_ = 0;
    GLuint whiteTex_ = 0;
    std::vector<Gpu> gpu_;
    std::map<int, GLuint> textures_;
    std::vector<Material> materials_;
    Bounds bounds_;
};

}  // namespace vx
