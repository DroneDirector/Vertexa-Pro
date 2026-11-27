#include <jni.h>

#include <string>

#include "engine.h"

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
                                         jobject resolver) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    std::string nm(n);
    env->ReleaseStringUTFChars(name, n);
    JniResolver res(env, resolver);
    std::string err = E(h)->load(fd, size, nm, res);
    return env->NewStringUTF(safeUtf8(err).c_str());
}

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
JNIEXPORT void JNICALL FN(nativeTouchDown)(JNIEnv*, jobject, jlong h) { E(h)->renderer.touchDown(); }
JNIEXPORT void JNICALL FN(nativeOrbit)(JNIEnv*, jobject, jlong h, jfloat dx, jfloat dy) { E(h)->renderer.orbit(dx, dy); }
JNIEXPORT void JNICALL FN(nativePan)(JNIEnv*, jobject, jlong h, jfloat dx, jfloat dy) { E(h)->renderer.pan(dx, dy); }
JNIEXPORT void JNICALL FN(nativeZoom)(JNIEnv*, jobject, jlong h, jfloat f) { E(h)->renderer.zoom(f); }
JNIEXPORT void JNICALL FN(nativeFling)(JNIEnv*, jobject, jlong h, jfloat vx, jfloat vy) { E(h)->renderer.fling(vx, vy); }
JNIEXPORT void JNICALL FN(nativeResetView)(JNIEnv*, jobject, jlong h, jboolean anim) {
    E(h)->renderer.resetView(anim);
}

}  // extern "C"
