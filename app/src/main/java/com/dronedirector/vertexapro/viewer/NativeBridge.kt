package com.dronedirector.vertexapro.viewer

object NativeBridge {
    init {
        System.loadLibrary("vertexa")
    }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(h: Long)
    @JvmStatic external fun nativeLoad(h: Long, fd: Int, size: Long, name: String, resolver: SiblingResolver): String
    @JvmStatic external fun nativeCancel(h: Long)
    @JvmStatic external fun nativeProgress(h: Long): Float
    @JvmStatic external fun nativeStats(h: Long): LongArray
    @JvmStatic external fun nativeMaterialTextures(h: Long): Array<String>
    @JvmStatic external fun nativeEmbeddedSize(h: Long, idx: Int): IntArray
    @JvmStatic external fun nativeEmbeddedData(h: Long, idx: Int): ByteArray?
    @JvmStatic external fun nativeBounds(h: Long): DoubleArray

    // GL thread only
    @JvmStatic external fun nativeSurfaceCreated(h: Long): Boolean
    @JvmStatic external fun nativeSurfaceChanged(h: Long, w: Int, hgt: Int)
    @JvmStatic external fun nativeDraw(h: Long, nanos: Long): Boolean
    @JvmStatic external fun nativeClearModel(h: Long)
    @JvmStatic external fun nativeSetTexture(h: Long, material: Int, tex: Int)

    // any thread
    @JvmStatic external fun nativeSetMode(h: Long, mode: Int)
    @JvmStatic external fun nativeSetGrid(h: Long, on: Boolean)
    @JvmStatic external fun nativeSetAutoRotate(h: Long, on: Boolean)
    @JvmStatic external fun nativeSetUpAxisZ(h: Long, z: Boolean)
    @JvmStatic external fun nativeSetPointScale(h: Long, s: Float)
    @JvmStatic external fun nativeSetTheme(h: Long, colors: FloatArray)
    @JvmStatic external fun nativeTouchDown(h: Long)
    @JvmStatic external fun nativeOrbit(h: Long, dx: Float, dy: Float)
    @JvmStatic external fun nativePan(h: Long, dx: Float, dy: Float)
    @JvmStatic external fun nativeZoom(h: Long, factor: Float)
    @JvmStatic external fun nativeFling(h: Long, vx: Float, vy: Float)
    @JvmStatic external fun nativeResetView(h: Long, animate: Boolean)
}
