#include <jni.h>

#include <algorithm>
#include <string>

#include "engine.h"
#include "exporter.h"

using namespace vx;

namespace {

Engine* E(jlong h) { return reinterpret_cast<Engine*>(h); }

// JNI needs valid (modified) UTF-8; replace anything else with '?'.
std::string safeUtf8(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        unsigned char c = (unsigned char)in[i];
        int n = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : 0;
        if (c == 0 || n == 0 || i + n > in.size()) { out += '?'; i++; continue; }
        bool ok = true;
        for (int k = 1; k < n; k++) if (((unsigned char)in[i + k] >> 6) != 2) ok = false;
        if (!ok) { out += '?'; i++; continue; }
        out.append(in, i, n);
        i += n;
    }
    return out;
}

class JniResolver : public Resolver {
public:
    JniResolver(JNIEnv* env, jobject obj) : env_(env), obj_(obj) {
        if (obj_) {
            jclass c = env_->GetObjectClass(obj_);
            mid_ = env_->GetMethodID(c, "open", "(Ljava/lang/String;)I");
            env_->DeleteLocalRef(c);
        }
    }
    int open(const std::string& name) override {
        if (!obj_ || !mid_) return -1;
        jstring s = env_->NewStringUTF(name.c_str());
        jint fd = env_->CallIntMethod(obj_, mid_, s);
        env_->DeleteLocalRef(s);
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            return -1;
        }
        return fd;
    }

private:
    JNIEnv* env_;
    jobject obj_;
    jmethodID mid_ = nullptr;
};

}  // namespace

#define FN(name) Java_com_dronedirector_vertexapro_viewer_NativeBridge_##name

extern "C" {

JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv*, jobject) { return reinterpret_cast<jlong>(new Engine()); }

JNIEXPORT void JNICALL FN(nativeDestroy)(JNIEnv*, jobject, jlong h) { delete E(h); }

JNIEXPORT jstring JNICALL FN(nativeLoad)(JNIEnv* env, jobject, jlong h, jint fd, jlong size, jstring name,
                                         jobject resolver, jboolean split) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    std::string nm(n);
    env->ReleaseStringUTFChars(name, n);
    JniResolver res(env, resolver);
    std::string err = E(h)->load(fd, size, nm, res, split);
    return env->NewStringUTF(safeUtf8(err).c_str());
}

JNIEXPORT jobjectArray JNICALL FN(nativeParts)(JNIEnv* env, jobject, jlong h) {
    auto s = E(h)->sink();
    std::vector<PartInfo> parts;
    if (s) parts = s->parts();
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)parts.size(), sc, nullptr);
    for (size_t i = 0; i < parts.size(); i++) {
        jstring js = env->NewStringUTF(safeUtf8(parts[i].name).c_str());
        env->SetObjectArrayElement(arr, (jsize)i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

JNIEXPORT jlongArray JNICALL FN(nativePartTriangles)(JNIEnv* env, jobject, jlong h) {
    auto s = E(h)->sink();
    std::vector<PartInfo> parts;
    if (s) parts = s->parts();
    std::vector<jlong> v(parts.size());
    for (size_t i = 0; i < parts.size(); i++) v[i] = parts[i].triangles > 0 ? parts[i].triangles : -parts[i].vertices;
    jlongArray a = env->NewLongArray((jsize)v.size());
    env->SetLongArrayRegion(a, 0, (jsize)v.size(), v.data());
    return a;
}

JNIEXPORT jstring JNICALL FN(nativeExport)(JNIEnv* env, jobject, jlong h, jint inFd, jlong size, jstring name,
                                           jobject resolver, jint format, jint outFd, jint mtlFd, jstring mtlName,
                                           jbooleanArray hidden, jobjectArray textures, jboolean split) {
    auto str = [&](jstring js) {
        if (!js) return std::string();
        const char* c = env->GetStringUTFChars(js, nullptr);
        std::string r(c);
        env->ReleaseStringUTFChars(js, c);
        return r;
    };
    ExportOptions opt;
    opt.format = format;
    opt.outFd = outFd;
    opt.mtlFd = mtlFd;
    opt.mtlName = str(mtlName);
    opt.splitLoose = split;
    if (hidden) {
        jsize n = env->GetArrayLength(hidden);
        std::vector<jboolean> b(n);
        if (n) env->GetBooleanArrayRegion(hidden, 0, n, b.data());
        for (auto x : b) opt.hidden.push_back(x ? 1 : 0);
    }
    if (textures) {
        jsize n = env->GetArrayLength(textures);
        for (jsize i = 0; i < n; i++) {
            auto js = (jstring)env->GetObjectArrayElement(textures, i);
            opt.textures.push_back(str(js));
            if (js) env->DeleteLocalRef(js);
        }
    }
    JniResolver res(env, resolver);
    Engine* e = E(h);
    e->exportProgress = 0.f;
    e->exportCancel = false;
    std::string err = exportModel(inFd, size, str(name), res, opt, e->exportProgress, e->exportCancel);
    return env->NewStringUTF(safeUtf8(err).c_str());
}

JNIEXPORT jobjectArray JNICALL FN(nativeAnimClips)(JNIEnv* env, jobject, jlong h) {
    auto clips = E(h)->renderer.animClips();
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)clips.size(), sc, nullptr);
    for (size_t i = 0; i < clips.size(); i++) {
        jstring js = env->NewStringUTF(safeUtf8(clips[i]).c_str());
        env->SetObjectArrayElement(arr, (jsize)i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}
JNIEXPORT void JNICALL FN(nativeAnimPlay)(JNIEnv*, jobject, jlong h, jint clip, jboolean play) { E(h)->renderer.setAnim(clip, play); }
JNIEXPORT void JNICALL FN(nativeAnimSeek)(JNIEnv*, jobject, jlong h, jdouble t) { E(h)->renderer.seekAnim(t); }
JNIEXPORT jdouble JNICALL FN(nativeAnimTime)(JNIEnv*, jobject, jlong h) { return E(h)->renderer.animTime(); }
JNIEXPORT void JNICALL FN(nativeAnimSpeed)(JNIEnv*, jobject, jlong h, jdouble s) { E(h)->renderer.setAnimSpeed(s); }

JNIEXPORT jdoubleArray JNICALL FN(nativeGetCamera)(JNIEnv* env, jobject, jlong h) {
    auto c = E(h)->renderer.camera();
    jdoubleArray a = env->NewDoubleArray(6);
    env->SetDoubleArrayRegion(a, 0, 6, c.data());
    return a;
}
JNIEXPORT void JNICALL FN(nativeSetCamera)(JNIEnv* env, jobject, jlong h, jdoubleArray arr, jboolean animate) {
    if (env->GetArrayLength(arr) < 6) return;
    std::array<double, 6> c;
    env->GetDoubleArrayRegion(arr, 0, 6, c.data());
    E(h)->renderer.setCamera(c, animate);
}

JNIEXPORT jboolean JNICALL FN(nativeCanEdit)(JNIEnv*, jobject, jlong h) { return E(h)->renderer.canEdit(); }
JNIEXPORT jboolean JNICALL FN(nativeHasUndo)(JNIEnv*, jobject, jlong h) { return E(h)->renderer.hasUndo(); }
JNIEXPORT void JNICALL FN(nativeSetCpuBudget)(JNIEnv*, jobject, jlong h, jlong bytes) {
    E(h)->renderer.setCpuBudget((size_t)std::max<jlong>(bytes, 64L << 20));
}

JNIEXPORT jfloatArray JNICALL FN(nativeRaycast)(JNIEnv* env, jobject, jlong h, jfloat x, jfloat y) {
    float p[3];
    if (!E(h)->renderer.raycast(x, y, p)) return nullptr;
    jfloatArray a = env->NewFloatArray(3);
    env->SetFloatArrayRegion(a, 0, 3, p);
    return a;
}

JNIEXPORT jfloatArray JNICALL FN(nativeProject)(JNIEnv* env, jobject, jlong h, jfloatArray pts) {
    jsize n = env->GetArrayLength(pts) / 3;
    std::vector<float> in(n * 3), out(n * 2);
    if (n) env->GetFloatArrayRegion(pts, 0, n * 3, in.data());
    E(h)->renderer.project(in.data(), n, out.data());
    jfloatArray a = env->NewFloatArray(n * 2);
    if (n) env->SetFloatArrayRegion(a, 0, n * 2, out.data());
    return a;
}

JNIEXPORT jfloatArray JNICALL FN(nativeSnap)(JNIEnv* env, jobject, jlong h, jfloat x, jfloat y, jfloat px,
                                             jboolean corners, jboolean edges) {
    float p[3];
    if (!E(h)->renderer.snapPoint(x, y, px, corners, edges, p)) return nullptr;
    jfloatArray a = env->NewFloatArray(3);
    env->SetFloatArrayRegion(a, 0, 3, p);
    return a;
}

JNIEXPORT jfloatArray JNICALL FN(nativeUnproject)(JNIEnv* env, jobject, jlong h, jfloatArray xy) {
    jsize n = env->GetArrayLength(xy) / 2;
    std::vector<float> in(n * 2), out(n * 3);
    if (n) env->GetFloatArrayRegion(xy, 0, n * 2, in.data());
    E(h)->renderer.unproject(in.data(), n, out.data());
    jfloatArray a = env->NewFloatArray(n * 3);
    if (n) env->SetFloatArrayRegion(a, 0, n * 3, out.data());
    return a;
}

JNIEXPORT void JNICALL FN(nativeSetOrtho)(JNIEnv*, jobject, jlong h, jboolean o) { E(h)->renderer.setOrtho(o); }
JNIEXPORT void JNICALL FN(nativeViewPreset)(JNIEnv*, jobject, jlong h, jint v) { E(h)->renderer.viewPreset(v); }
JNIEXPORT void JNICALL FN(nativeSetPartHinge)(JNIEnv* env, jobject, jlong h, jint part, jfloatArray a, jfloatArray b, jfloat deg) {
    float pa[3] = {0, 0, 0}, pb[3] = {0, 0, 0};
    if (a && env->GetArrayLength(a) >= 3) env->GetFloatArrayRegion(a, 0, 3, pa);
    if (b && env->GetArrayLength(b) >= 3) env->GetFloatArrayRegion(b, 0, 3, pb);
    E(h)->renderer.setPartHinge(part, pa, pb, deg);
}
JNIEXPORT void JNICALL FN(nativeDeletePart)(JNIEnv*, jobject, jlong h, jint part) {
    auto s = E(h)->sink();
    E(h)->renderer.deletePart(part, s.get());
}
JNIEXPORT jint JNICALL FN(nativeDetectMirrorAxis)(JNIEnv*, jobject, jlong h) { return E(h)->renderer.detectMirrorAxis(); }
JNIEXPORT jdouble JNICALL FN(nativePixelsPerUnit)(JNIEnv*, jobject, jlong h) { return E(h)->renderer.pixelsPerUnit(); }

JNIEXPORT jstring JNICALL FN(nativeExportGlb)(JNIEnv* env, jobject, jlong h, jint fd, jfloatArray pivots,
                                              jobjectArray images, jobjectArray mimes, jbooleanArray hidden) {
    std::vector<float> pv;
    if (pivots) {
        jsize n = env->GetArrayLength(pivots);
        pv.resize(n);
        if (n) env->GetFloatArrayRegion(pivots, 0, n, pv.data());
    }
    std::vector<std::vector<uint8_t>> imgs;
    std::vector<std::string> mm;
    if (images) {
        jsize n = env->GetArrayLength(images);
        for (jsize i = 0; i < n; i++) {
            auto arr = (jbyteArray)env->GetObjectArrayElement(images, i);
            std::vector<uint8_t> d;
            if (arr) {
                jsize len = env->GetArrayLength(arr);
                d.resize(len);
                if (len) env->GetByteArrayRegion(arr, 0, len, (jbyte*)d.data());
                env->DeleteLocalRef(arr);
            }
            imgs.push_back(std::move(d));
            std::string m;
            if (mimes && i < env->GetArrayLength(mimes)) {
                auto js = (jstring)env->GetObjectArrayElement(mimes, i);
                if (js) {
                    const char* c = env->GetStringUTFChars(js, nullptr);
                    m = c;
                    env->ReleaseStringUTFChars(js, c);
                    env->DeleteLocalRef(js);
                }
            }
            mm.push_back(m);
        }
    }
    std::vector<uint8_t> hid;
    if (hidden) {
        jsize n = env->GetArrayLength(hidden);
        std::vector<jboolean> b(n);
        if (n) env->GetBooleanArrayRegion(hidden, 0, n, b.data());
        for (auto x : b) hid.push_back(x ? 1 : 0);
    }
    auto s = E(h)->sink();
    std::string err = E(h)->renderer.exportGlb(fd, pv, imgs, mm, hid, s.get());
    return env->NewStringUTF(safeUtf8(err).c_str());
}

static std::vector<float> floats(JNIEnv* env, jfloatArray a) {
    std::vector<float> v;
    if (!a) return v;
    jsize n = env->GetArrayLength(a);
    v.resize(n);
    if (n) env->GetFloatArrayRegion(a, 0, n, v.data());
    return v;
}

JNIEXPORT void JNICALL FN(nativePreviewCut)(JNIEnv* env, jobject, jlong h, jfloatArray pts, jfloat lo, jfloat hi, jfloatArray erase,
                                            jint mode, jboolean exact) {
    Renderer::CutRequest req;
    req.erase = floats(env, erase);
    if (pts) {
        jsize n = env->GetArrayLength(pts);
        req.points.resize(n);
        if (n) env->GetFloatArrayRegion(pts, 0, n, req.points.data());
    }
    req.depthLo = lo;
    req.depthHi = hi;
    if (exact && req.points.size() >= 9) {
        // the real cut, dry run: shows exactly what will be cut, split along the outline
        req.mode = mode;
        req.previewOnly = true;
        req.keepUndo = false;
        auto s = E(h)->sink();
        auto r = E(h)->renderer.cut(req, s.get());
        if (!r.error.empty()) E(h)->renderer.clearExactPreview();
        return;
    }
    E(h)->renderer.clearExactPreview();
    E(h)->renderer.previewCut(req);
}

JNIEXPORT jstring JNICALL FN(nativeCut)(JNIEnv* env, jobject, jlong h, jfloatArray pts, jint mode, jstring name,
                                        jint mirrorAxis, jboolean keepUndo, jint onlyPart, jfloat depthLo, jfloat depthHi,
                                        jfloatArray erase, jint slabAxis, jfloat slabLo, jfloat slabHi) {
    Renderer::CutRequest req;
    req.slabAxis = slabAxis;
    req.slabLo = slabLo;
    req.slabHi = slabHi;
    req.erase = floats(env, erase);
    req.onlyPart = onlyPart;
    req.depthLo = depthLo;
    req.depthHi = depthHi;
    req.mirrorAxis = mirrorAxis;
    req.keepUndo = keepUndo;
    jsize n = env->GetArrayLength(pts);
    req.points.resize(n);
    if (n) env->GetFloatArrayRegion(pts, 0, n, req.points.data());
    req.mode = mode;
    const char* c = env->GetStringUTFChars(name, nullptr);
    req.name = c;
    env->ReleaseStringUTFChars(name, c);
    auto s = E(h)->sink();
    Renderer::CutResult r;
    try {
        r = E(h)->renderer.cut(req, s.get());
    } catch (const std::bad_alloc&) {
        r.error = "The device ran out of memory while cutting.";
    }
    std::string out = r.error.empty()
        ? std::to_string(r.part) + "|" + std::to_string(r.triangles) + "|" + std::to_string(r.capTriangles)
        : "ERR|" + r.error;
    return env->NewStringUTF(safeUtf8(out).c_str());
}

JNIEXPORT jboolean JNICALL FN(nativeRedoCut)(JNIEnv*, jobject, jlong h) {
    auto s = E(h)->sink();
    return E(h)->renderer.redoCut(s.get());
}

JNIEXPORT jboolean JNICALL FN(nativeUndoCut)(JNIEnv*, jobject, jlong h) {
    auto s = E(h)->sink();
    return E(h)->renderer.undoCut(s.get());
}

JNIEXPORT void JNICALL FN(nativeRenamePart)(JNIEnv* env, jobject, jlong h, jint i, jstring name) {
    auto s = E(h)->sink();
    if (!s) return;
    const char* c = env->GetStringUTFChars(name, nullptr);
    s->renamePart(i, c);
    env->ReleaseStringUTFChars(name, c);
}

JNIEXPORT jstring JNICALL FN(nativeExportMemory)(JNIEnv* env, jobject, jlong h, jint format, jint outFd, jint mtlFd,
                                                 jstring mtlName, jbooleanArray hidden, jobjectArray textures,
                                                 jint onlyPart) {
    ExportOptions opt;
    opt.format = format;
    opt.outFd = outFd;
    opt.mtlFd = mtlFd;
    opt.onlyPart = onlyPart;
    if (mtlName) {
        const char* c = env->GetStringUTFChars(mtlName, nullptr);
        opt.mtlName = c;
        env->ReleaseStringUTFChars(mtlName, c);
    }
    if (hidden) {
        jsize n = env->GetArrayLength(hidden);
        std::vector<jboolean> b(n);
        if (n) env->GetBooleanArrayRegion(hidden, 0, n, b.data());
        for (auto x : b) opt.hidden.push_back(x ? 1 : 0);
    }
    if (textures) {
        jsize n = env->GetArrayLength(textures);
        for (jsize i = 0; i < n; i++) {
            auto js = (jstring)env->GetObjectArrayElement(textures, i);
            std::string t;
            if (js) {
                const char* c = env->GetStringUTFChars(js, nullptr);
                t = c;
                env->ReleaseStringUTFChars(js, c);
                env->DeleteLocalRef(js);
            }
            opt.textures.push_back(t);
        }
    }
    auto s = E(h)->sink();
    std::string err = E(h)->renderer.exportMemory(opt, s.get());
    return env->NewStringUTF(safeUtf8(err).c_str());
}

JNIEXPORT jfloat JNICALL FN(nativeExportProgress)(JNIEnv*, jobject, jlong h) { return E(h)->exportProgress.load(); }
JNIEXPORT void JNICALL FN(nativeExportCancel)(JNIEnv*, jobject, jlong h) { E(h)->exportCancel = true; }

JNIEXPORT void JNICALL FN(nativeCancel)(JNIEnv*, jobject, jlong h) { E(h)->cancel(); }

JNIEXPORT jfloat JNICALL FN(nativeProgress)(JNIEnv*, jobject, jlong h) {
    auto s = E(h)->sink();
    return s ? s->progress.load() : 0.f;
}

JNIEXPORT jlongArray JNICALL FN(nativeStats)(JNIEnv* env, jobject, jlong h) {
    auto s = E(h)->sink();
    jlong v[6] = {0, 0, 0, 0, 0, 0};
    if (s) {
        v[0] = s->vertexCount.load();
        v[1] = s->triangleCount.load();
        v[2] = s->pointCount.load();
        v[3] = s->chunkCount.load();
        v[4] = s->materialCount();
        v[5] = (jlong)s->pending();
    }
    jlongArray a = env->NewLongArray(6);
    env->SetLongArrayRegion(a, 0, 6, v);
    return a;
}

JNIEXPORT jobjectArray JNICALL FN(nativeMaterialTextures)(JNIEnv* env, jobject, jlong h) {
    auto s = E(h)->sink();
    std::vector<Material> mats;
    if (s) mats = s->materials();
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)mats.size(), sc, nullptr);
    for (size_t i = 0; i < mats.size(); i++) {
        jstring js = env->NewStringUTF(safeUtf8(mats[i].texture).c_str());
        env->SetObjectArrayElement(arr, (jsize)i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL FN(nativeReport)(JNIEnv* env, jobject, jlong h) {
    auto s = E(h)->sink();
    std::vector<std::string> lines;
    if (s) lines = s->logs();
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)lines.size(), sc, nullptr);
    for (size_t i = 0; i < lines.size(); i++) {
        jstring js = env->NewStringUTF(safeUtf8(lines[i]).c_str());
        env->SetObjectArrayElement(arr, (jsize)i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

JNIEXPORT jintArray JNICALL FN(nativeEmbeddedSize)(JNIEnv* env, jobject, jlong h, jint idx) {
    auto s = E(h)->sink();
    jint v[2] = {-1, -1};
    if (s && idx >= 0 && idx < (jint)s->embedded.size()) {
        v[0] = s->embedded[idx].width;
        v[1] = s->embedded[idx].height;
    }
    jintArray a = env->NewIntArray(2);
    env->SetIntArrayRegion(a, 0, 2, v);
    return a;
}

JNIEXPORT jbyteArray JNICALL FN(nativeEmbeddedData)(JNIEnv* env, jobject, jlong h, jint idx) {
    auto s = E(h)->sink();
    if (!s || idx < 0 || idx >= (jint)s->embedded.size()) return nullptr;
    auto& d = s->embedded[idx].data;
    jbyteArray a = env->NewByteArray((jsize)d.size());
    if (!a) return nullptr;
    env->SetByteArrayRegion(a, 0, (jsize)d.size(), (const jbyte*)d.data());
    return a;
}

JNIEXPORT jdoubleArray JNICALL FN(nativeBounds)(JNIEnv* env, jobject, jlong h) {
    auto s = E(h)->sink();
    jdouble v[6] = {0, 0, 0, 0, 0, 0};
    if (s) {
        Bounds b = s->bounds();
        if (b.valid())
            for (int i = 0; i < 3; i++) { v[i] = b.mx[i] - b.mn[i]; v[3 + i] = s->origin[i]; }
    }
    jdoubleArray a = env->NewDoubleArray(6);
    env->SetDoubleArrayRegion(a, 0, 6, v);
    return a;
}

// ---- GL thread
JNIEXPORT jboolean JNICALL FN(nativeSurfaceCreated)(JNIEnv*, jobject, jlong h) {
    return E(h)->renderer.surfaceCreated() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL FN(nativeSurfaceChanged)(JNIEnv*, jobject, jlong h, jint w, jint hh) {
    E(h)->renderer.surfaceChanged(w, hh);
}
JNIEXPORT jboolean JNICALL FN(nativeDraw)(JNIEnv*, jobject, jlong h, jlong nanos) {
    auto s = E(h)->sink();
    return E(h)->renderer.draw(s.get(), (double)nanos * 1e-9) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL FN(nativeClearModel)(JNIEnv*, jobject, jlong h) {
    E(h)->resetSink();
    E(h)->renderer.clearModel();
}
JNIEXPORT jboolean JNICALL FN(nativeSnapshot)(JNIEnv* env, jobject, jlong h, jint w, jint hh, jboolean transparent,
                                               jobject buffer) {
    auto* out = (uint8_t*)env->GetDirectBufferAddress(buffer);
    if (!out || env->GetDirectBufferCapacity(buffer) < (jlong)w * hh * 4) return JNI_FALSE;
    return E(h)->renderer.snapshot(w, hh, transparent, out) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jlong JNICALL FN(nativeBrushSelect)(JNIEnv*, jobject, jlong h, jfloat x, jfloat y, jfloat r, jboolean add, jboolean vertex, jboolean xray) {
    return E(h)->renderer.brushSelect(x, y, r, add, vertex, xray);
}
JNIEXPORT jlong JNICALL FN(nativeSelectionFromPreview)(JNIEnv*, jobject, jlong h) { return E(h)->renderer.selectionFromPreview(); }
JNIEXPORT void JNICALL FN(nativeClearSelection)(JNIEnv*, jobject, jlong h) { E(h)->renderer.clearSelection(); }
JNIEXPORT jstring JNICALL FN(nativeSeparateSelection)(JNIEnv* env, jobject, jlong h, jstring name) {
    const char* c = env->GetStringUTFChars(name, nullptr);
    std::string n = c ? c : "";
    if (c) env->ReleaseStringUTFChars(name, c);
    auto s = E(h)->sink();
    int64_t tris = 0;
    int part = E(h)->renderer.separateSelection(n, s.get(), tris);
    std::string r = part < 0 ? std::string("ERR|Select some of the model with the brush first.") : std::to_string(part) + "|" + std::to_string(tris) + "|0";
    return env->NewStringUTF(r.c_str());
}
JNIEXPORT jlongArray JNICALL FN(nativeMergeParts)(JNIEnv* env, jobject, jlong h, jint a, jint b, jboolean connect) {
    auto s = E(h)->sink();
    int64_t seals = 0, welded = 0;
    bool ok = E(h)->renderer.mergeParts(a, b, connect, s.get(), seals, welded);
    jlong v[3] = {ok ? 1 : 0, (jlong)seals, (jlong)welded};
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, v);
    return arr;
}
JNIEXPORT jlong JNICALL FN(nativeEditPartSelection)(JNIEnv*, jobject, jlong h, jint part) { return E(h)->renderer.editPartSelection(part); }
JNIEXPORT jlongArray JNICALL FN(nativeApplyPartEdit)(JNIEnv* env, jobject, jlong h, jint part) {
    auto s = E(h)->sink();
    int64_t out = 0, in = 0;
    bool ok = E(h)->renderer.applyPartEdit(part, s.get(), out, in);
    jlong v[3] = {ok ? 1 : 0, (jlong)out, (jlong)in};
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, v);
    return arr;
}
JNIEXPORT jdoubleArray JNICALL FN(nativeAnalyzeWings)(JNIEnv* env, jobject, jlong h, jint span, jint n) {
    std::vector<double> v = E(h)->renderer.analyzeWings(span, n);
    jdoubleArray a = env->NewDoubleArray((jsize)v.size());
    if (!v.empty()) env->SetDoubleArrayRegion(a, 0, (jsize)v.size(), v.data());
    return a;
}
JNIEXPORT jboolean JNICALL FN(nativeScaleModel)(JNIEnv*, jobject, jlong h, jdouble f) {
    auto s = E(h)->sink();
    return E(h)->renderer.scaleModel(f, s.get()) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jlong JNICALL FN(nativePartsTouch)(JNIEnv*, jobject, jlong h, jint a, jint b) { return E(h)->renderer.partsTouch(a, b); }
JNIEXPORT jfloatArray JNICALL FN(nativeMirrorPoints)(JNIEnv* env, jobject, jlong h, jfloatArray pts, jint ax) {
    std::vector<float> p = floats(env, pts);
    if (ax >= 0 && ax < 3) {
        double c = E(h)->renderer.mirrorCenter(ax);
        for (size_t i = 0; i + 2 < p.size(); i += 3) p[i + ax] = (float)(2 * c - p[i + ax]);
    }
    jfloatArray a = env->NewFloatArray((jsize)p.size());
    if (!p.empty()) env->SetFloatArrayRegion(a, 0, (jsize)p.size(), p.data());
    return a;
}
JNIEXPORT jboolean JNICALL FN(nativeAnalysisImage)(JNIEnv* env, jobject, jlong h, jint w, jint hh, jint lighting, jobject buffer) {
    auto* out = (uint8_t*)env->GetDirectBufferAddress(buffer);
    if (!out || env->GetDirectBufferCapacity(buffer) < (jlong)w * hh * 4) return JNI_FALSE;
    return E(h)->renderer.snapshot(w, hh, true, out, lighting) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL FN(nativeBeginTurntable)(JNIEnv*, jobject, jlong h) { E(h)->renderer.beginTurntable(); }
JNIEXPORT void JNICALL FN(nativeTurntableFrame)(JNIEnv*, jobject, jlong h, jdouble a, jint w, jint hh) {
    E(h)->renderer.turntableFrame(a, w, hh);
}
JNIEXPORT void JNICALL FN(nativeEndTurntable)(JNIEnv*, jobject, jlong h) { E(h)->renderer.endTurntable(); }
JNIEXPORT void JNICALL FN(nativeReleaseGl)(JNIEnv*, jobject, jlong h) { E(h)->renderer.releaseGl(); }
JNIEXPORT void JNICALL FN(nativeSetTexture)(JNIEnv*, jobject, jlong h, jint mat, jint tex) {
    E(h)->renderer.setTexture(mat, tex);
}

// ---- settings / input (any thread)
JNIEXPORT void JNICALL FN(nativeSetMode)(JNIEnv*, jobject, jlong h, jint m) { E(h)->renderer.setMode(m); }
JNIEXPORT void JNICALL FN(nativeSetGrid)(JNIEnv*, jobject, jlong h, jboolean g) { E(h)->renderer.setGrid(g); }
JNIEXPORT void JNICALL FN(nativeSetAutoRotate)(JNIEnv*, jobject, jlong h, jboolean a) {
    E(h)->renderer.setAutoRotate(a);
}
JNIEXPORT void JNICALL FN(nativeSetUpAxisZ)(JNIEnv*, jobject, jlong h, jboolean z) { E(h)->renderer.setUpAxisZ(z); }
JNIEXPORT void JNICALL FN(nativeSetPointScale)(JNIEnv*, jobject, jlong h, jfloat s) {
    E(h)->renderer.setPointScale(s);
}
JNIEXPORT void JNICALL FN(nativeSetTheme)(JNIEnv* env, jobject, jlong h, jfloatArray arr) {
    if (env->GetArrayLength(arr) < 17) return;
    jfloat f[17];
    env->GetFloatArrayRegion(arr, 0, 17, f);
    ThemeColors t;
    memcpy(t.bgTop, f, 12);
    memcpy(t.bgBottom, f + 3, 12);
    memcpy(t.grid, f + 6, 16);
    memcpy(t.wire, f + 10, 16);
    memcpy(t.clay, f + 14, 12);
    E(h)->renderer.setTheme(t);
}
JNIEXPORT void JNICALL FN(nativeSetPointColorMode)(JNIEnv*, jobject, jlong h, jint m) { E(h)->renderer.setPointColorMode(m); }
JNIEXPORT void JNICALL FN(nativeSetLighting)(JNIEnv*, jobject, jlong h, jint p) { E(h)->renderer.setLighting(p); }
JNIEXPORT void JNICALL FN(nativeSetPartHidden)(JNIEnv*, jobject, jlong h, jint i, jboolean b) { E(h)->renderer.setPartHidden(i, b); }
JNIEXPORT void JNICALL FN(nativeSetAllPartsHidden)(JNIEnv*, jobject, jlong h, jboolean b) { E(h)->renderer.setAllPartsHidden(b); }
JNIEXPORT void JNICALL FN(nativeIsolatePart)(JNIEnv*, jobject, jlong h, jint i) { E(h)->renderer.isolatePart(i); }
JNIEXPORT void JNICALL FN(nativeSelectPart)(JNIEnv*, jobject, jlong h, jint i) { E(h)->renderer.selectPart(i); }
JNIEXPORT void JNICALL FN(nativeFocusPart)(JNIEnv*, jobject, jlong h, jint i) { E(h)->renderer.focusPart(i); }
JNIEXPORT void JNICALL FN(nativeRequestPick)(JNIEnv*, jobject, jlong h, jfloat x, jfloat y) { E(h)->renderer.requestPick(x, y); }
JNIEXPORT jint JNICALL FN(nativePickResult)(JNIEnv*, jobject, jlong h) { return E(h)->renderer.pickResult(); }
JNIEXPORT void JNICALL FN(nativeTouchDown)(JNIEnv*, jobject, jlong h) { E(h)->renderer.touchDown(); }
JNIEXPORT void JNICALL FN(nativeOrbit)(JNIEnv*, jobject, jlong h, jfloat dx, jfloat dy) { E(h)->renderer.orbit(dx, dy); }
JNIEXPORT void JNICALL FN(nativePan)(JNIEnv*, jobject, jlong h, jfloat dx, jfloat dy) { E(h)->renderer.pan(dx, dy); }
JNIEXPORT void JNICALL FN(nativeZoom)(JNIEnv*, jobject, jlong h, jfloat f) { E(h)->renderer.zoom(f); }
JNIEXPORT void JNICALL FN(nativeFling)(JNIEnv*, jobject, jlong h, jfloat vx, jfloat vy) { E(h)->renderer.fling(vx, vy); }
JNIEXPORT void JNICALL FN(nativeResetView)(JNIEnv*, jobject, jlong h, jboolean anim) {
    E(h)->renderer.resetView(anim);
}

}  // extern "C"
