#pragma once
#include <GLES3/gl3.h>

#include <array>
#include <atomic>
#include <cmath>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

#include "anim.h"
#include "mesh.h"
#include "vmath.h"

namespace vx {

struct ExportOptions;

enum Mode {
    MODE_TEXTURED = 0,
    MODE_SOLID = 1,
    MODE_WIRE = 2,         // every edge, see-through
    MODE_SOLID_WIRE = 3,
    MODE_TEX_WIRE = 4,
    MODE_WIRE_CLEAN = 5,   // wireframe only, hidden edges removed
};

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
    void releaseGl();  // deletes every GL object; call with the context current
    void setTexture(int material, int glTex);
    /** Renders the current view off-screen at w x h into out (RGBA, bottom row first). */
    bool snapshot(int w, int h, bool transparent, uint8_t* out);
    /** Turntable video: renders one frame into the currently bound surface, rotated by angle radians. */
    void beginTurntable();
    void turntableFrame(double angle, int w, int h);
    void endTurntable();

    // ---- editing (worker thread; uses the in-memory copy of the geometry)
    struct CutRequest {
        std::vector<float> points;  // lasso corners in model space (x, y, z) * n
        int mode = 0;               // 0 cut through, 1 surface only, 2 one wall, 3 through the first object only
        std::string name;
        int mirrorAxis = -1;        // 0/1/2: cut the mirror image across the model's centre on that axis
        int onlyPart = -1;          // >= 0: cut only this part
        bool keepUndo = true;       // false for the second half of a mirrored cut (undo covers both)
    };
    struct CutResult {
        int part = -1;
        int64_t triangles = 0, capTriangles = 0;
        std::string error;
    };
    bool canEdit() { std::lock_guard<std::mutex> l(dataMu_); return keepCpu_ && !cpu_.empty() && !animated_; }
    CutResult cut(const CutRequest& req, Sink* sink);
    bool undoCut(Sink* sink);
    bool redoCut(Sink* sink);
    bool hasUndo() { std::lock_guard<std::mutex> l(dataMu_); return !undo_.empty(); }
    /** Ray from a screen point; returns true and the model-space hit point. */
    bool raycast(float sx, float sy, float out[3]);
    /** Surface point under a screen position, snapped to a nearby corner or edge if within snapPx. */
    bool snapPoint(float sx, float sy, float snapPx, bool corners, bool edges, float out[3]);
    /** Screen points to model space on the plane through the model's centre facing the camera. */
    void unproject(const float* xy, int n, float* out);
    /** Projects model-space points to screen pixels (NaN when behind the camera). */
    void project(const float* in, int n, float* out);
    /** Saves the geometry held in memory (including edits). */
    std::string exportMemory(const ExportOptions& opt, Sink* sink);
    struct CutCam;
    M4 projection(const Frame& f, int w, int h, double nearP, double far) const;
    bool cutCamera(CutCam& cc);
    void deletePart(int part, Sink* sink);
    /** 0 / 1 / 2: the axis the model is most symmetric across (through its centre). */
    int detectMirrorAxis();
    /** Pixels per model unit at the model's centre in the current view. */
    double pixelsPerUnit();
    /** Writes a binary glTF with one node per part, pivots from [pivots] (x,y,z per part, NaN = part centre). */
    std::string exportGlb(int fd, const std::vector<float>& pivots, const std::vector<std::vector<uint8_t>>& images,
                          const std::vector<std::string>& mimes, const std::vector<uint8_t>& hidden, Sink* sink);
    void setCpuBudget(size_t bytes) { std::lock_guard<std::mutex> l(dataMu_); cpuBudget_ = bytes; }

    // ---- animation (any thread)
    std::vector<std::string> animClips();   // "name|seconds"
    void setAnim(int clip, bool playing) { std::lock_guard<std::mutex> l(mu_); animClip_ = clip; animPlaying_ = playing; animDirty_ = true; }
    void seekAnim(double seconds) { std::lock_guard<std::mutex> l(mu_); animTime_ = std::max(0.0, seconds); animDirty_ = true; }
    double animTime() { std::lock_guard<std::mutex> l(mu_); return animTime_; }
    void setAnimSpeed(double s) { std::lock_guard<std::mutex> l(mu_); animSpeed_ = s; }

    // ---- saved views (any thread): target x, y, z, yaw, pitch, distance
    std::array<double, 6> camera() {
        std::lock_guard<std::mutex> l(mu_);
        return {cam_.target.x, cam_.target.y, cam_.target.z, cam_.yaw, cam_.pitch, cam_.dist};
    }
    void setCamera(const std::array<double, 6>& c, bool animate) {
        std::lock_guard<std::mutex> l(mu_);
        Cam t;
        t.target = V3(c[0], c[1], c[2]);
        t.yaw = c[3];
        t.pitch = c[4];
        t.dist = c[5] > 0 ? c[5] : cam_.dist;
        velYaw_ = velPitch_ = 0;
        userMoved_ = true;
        pendingReset_ = false;
        if (animate) {
            animFrom_ = cam_;
            double d = std::fmod(animFrom_.yaw - t.yaw, 2 * M_PI);
            if (d > M_PI) d -= 2 * M_PI;
            if (d < -M_PI) d += 2 * M_PI;
            animFrom_.yaw = t.yaw + d;
            animTo_ = t;
            animStart_ = lastTime_;
        } else {
            cam_ = t;
            animStart_ = -1;
        }
    }

    // ---- camera presets, flat view, hinges (any thread)
    void setOrtho(bool o) { std::lock_guard<std::mutex> l(mu_); ortho_ = o; }
    void viewPreset(int v);  // 0 top, 1 bottom, 2 front, 3 back, 4 left, 5 right
    /** Rotates a part about the hinge a→b by deg degrees (NaN removes the hinge). */
    void setPartHinge(int part, const float* a, const float* b, float deg);

    // any thread
    void setMode(int m) { std::lock_guard<std::mutex> l(mu_); mode_ = m; }
    void setTheme(const ThemeColors& t) { std::lock_guard<std::mutex> l(mu_); theme_ = t; }
    void setGrid(bool g) { std::lock_guard<std::mutex> l(mu_); grid_ = g; }
    void setAutoRotate(bool a) { std::lock_guard<std::mutex> l(mu_); autoRotate_ = a; }
    void setUpAxisZ(bool z);
    void setPointScale(float s) { std::lock_guard<std::mutex> l(mu_); pointScale_ = s; }
    void setPointColorMode(int m) { std::lock_guard<std::mutex> l(mu_); pointMode_ = m; }
    void setLighting(int preset) { std::lock_guard<std::mutex> l(mu_); lighting_ = preset; }
    void setPartHidden(int part, bool hidden);
    void setAllPartsHidden(bool hidden);
    void isolatePart(int part);
    void selectPart(int part) { std::lock_guard<std::mutex> l(mu_); selected_ = part; }
    void focusPart(int part) { std::lock_guard<std::mutex> l(mu_); pendingFocus_ = part; }
    void requestPick(float x, float y);
    int pickResult() { return pickResult_.load(); }  // -2 pending, -1 nothing, else part index
    void touchDown();
    void orbit(float dx, float dy);
    void pan(float dx, float dy);
    void zoom(float factor);
    void fling(float vx, float vy);
    void resetView(bool animate);

private:
    struct Gpu {
        GLuint vao = 0, vbo = 0, ibo = 0, lbo = 0;
        GLsizei triCount = 0, lineCount = 0, vertCount = 0;
        int material = 0;
        int part = 0;
        bool points = false;
        int animSlot = -1;
        bool hasXf = false;
        float xf[16];
    };
    struct Cam {
        V3 target;
        double yaw = 0.6, pitch = 0.35, dist = 1;
    };
    struct Frame {
        Cam cam;
        int mode = 0;
        ThemeColors th;
        bool grid = true;
        Bounds rb;
        float pointScale = 2.f;
        int pointMode = 0;
        int lighting = 0;
        bool upZ = false;
        int selected = -1;
        bool ortho = false;
        std::map<int, M4> partXf;
        std::vector<uint8_t> hidden;
        std::vector<Material> mats;
    };

    void upload(std::unique_ptr<Chunk> c);
    void uploadGpu(const Chunk& c);
    void recomputeParts(Sink* sink);  // call with dataMu_ held
    void fitCamera(Cam& out, const Bounds& b, bool keepAngles = false) const;
    Bounds rotatedBounds(const Bounds& b) const;
    void buildPrograms();
    Frame frameState();  // call with mu_ held
    void renderScene(const Frame& f, int w, int h, bool background, bool allowGrid);
    void renderPick(const Frame& f, int x, int y);
    bool partHidden(const Frame& f, int part) const {
        return part >= 0 && part < (int)f.hidden.size() && f.hidden[part];
    }
    void drawGrid(const M4& view, const M4& proj, const Bounds& rb, const float* color);

    std::mutex mu_;
    int mode_ = MODE_TEXTURED;
    ThemeColors theme_;
    bool grid_ = true;
    bool autoRotate_ = false;
    bool upZ_ = false;
    float pointScale_ = 2.f;
    int pointMode_ = 0;
    int lighting_ = 0;
    int selected_ = -1;
    int pendingFocus_ = -1;
    bool ortho_ = false;
    std::map<int, M4> partXf_;
    std::vector<uint8_t> hidden_;
    std::vector<PartInfo> parts_;

    Cam cam_;
    Cam animFrom_, animTo_;
    double animStart_ = -1, animDur_ = 0.65;
    bool pendingReset_ = false, pendingAnimate_ = false;
    double velYaw_ = 0, velPitch_ = 0;
    bool userMoved_ = false;
    int lastGen_ = -1;
    double lastTime_ = 0;
    double turnBase_ = 0;
    bool turntable_ = false;

    bool pickPending_ = false;
    float pickX_ = 0, pickY_ = 0;
    std::atomic<int> pickResult_{-1};

    int w_ = 1, h_ = 1;
    bool hadContext_ = false;
    GLuint meshProg_ = 0, bgProg_ = 0, gridProg_ = 0, pickProg_ = 0;
    GLuint bgVao_ = 0, gridVao_ = 0, gridVbo_ = 0;
    GLsizei gridCount_ = 0;
    GLuint whiteTex_ = 0;
    GLuint pickFbo_ = 0, pickColor_ = 0, pickDepth_ = 0;
    int pickW_ = 0, pickH_ = 0;
    std::vector<Gpu> gpu_;
    std::mutex dataMu_;
    std::vector<std::shared_ptr<Chunk>> cpu_;   // geometry kept for editing and saving
    std::vector<std::shared_ptr<Chunk>> undo_;  // geometry before the last cut
    std::vector<std::shared_ptr<Chunk>> redo_;  // geometry after an undone cut
    bool keepCpu_ = true;
    bool animated_ = false;
    std::shared_ptr<AnimData> anim_;
    int animClip_ = 0;
    bool animPlaying_ = false, animDirty_ = false;
    double animTime_ = 0, animSpeed_ = 1;
    void applyAnim(int clip, double seconds);
    bool rebuildGpu_ = false;
    size_t cpuBytes_ = 0;
    size_t cpuBudget_ = (size_t)1500 << 20;
    std::map<int, GLuint> textures_;
    std::vector<Material> materials_;
    Bounds bounds_;
};

}  // namespace vx
