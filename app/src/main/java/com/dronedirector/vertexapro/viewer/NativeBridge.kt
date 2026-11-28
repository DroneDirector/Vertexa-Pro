package com.dronedirector.vertexapro.viewer

object NativeBridge {
    init {
        System.loadLibrary("vertexa")
    }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(h: Long)
    @JvmStatic external fun nativeLoad(h: Long, fd: Int, size: Long, name: String, resolver: SiblingResolver, split: Boolean): String
    @JvmStatic external fun nativeCancel(h: Long)
    @JvmStatic external fun nativeProgress(h: Long): Float
    @JvmStatic external fun nativeStats(h: Long): LongArray
    @JvmStatic external fun nativeMaterialTextures(h: Long): Array<String>
    @JvmStatic external fun nativeEmbeddedSize(h: Long, idx: Int): IntArray
    @JvmStatic external fun nativeEmbeddedData(h: Long, idx: Int): ByteArray?
    @JvmStatic external fun nativeBounds(h: Long): DoubleArray
    @JvmStatic external fun nativeReport(h: Long): Array<String>
    @JvmStatic external fun nativeParts(h: Long): Array<String>
    @JvmStatic external fun nativePartTriangles(h: Long): LongArray
    @JvmStatic external fun nativeExport(
        h: Long, inFd: Int, size: Long, name: String, resolver: SiblingResolver, format: Int,
        outFd: Int, mtlFd: Int, mtlName: String, hidden: BooleanArray, textures: Array<String>, split: Boolean,
    ): String
    @JvmStatic external fun nativeExportMemory(
        h: Long, format: Int, outFd: Int, mtlFd: Int, mtlName: String, hidden: BooleanArray, textures: Array<String>, onlyPart: Int,
    ): String
    @JvmStatic external fun nativeAnimClips(h: Long): Array<String>
    @JvmStatic external fun nativeAnimPlay(h: Long, clip: Int, play: Boolean)
    @JvmStatic external fun nativeAnimSeek(h: Long, seconds: Double)
    @JvmStatic external fun nativeAnimTime(h: Long): Double
    @JvmStatic external fun nativeAnimSpeed(h: Long, speed: Double)
    @JvmStatic external fun nativeGetCamera(h: Long): DoubleArray
    @JvmStatic external fun nativeSetCamera(h: Long, cam: DoubleArray, animate: Boolean)
    @JvmStatic external fun nativeCanEdit(h: Long): Boolean
    @JvmStatic external fun nativeHasUndo(h: Long): Boolean
    @JvmStatic external fun nativeSetCpuBudget(h: Long, bytes: Long)
    @JvmStatic external fun nativeRaycast(h: Long, x: Float, y: Float): FloatArray?
    @JvmStatic external fun nativeProject(h: Long, pts: FloatArray): FloatArray
    @JvmStatic external fun nativeCut(
        h: Long, pts: FloatArray, mode: Int, name: String, mirrorAxis: Int, keepUndo: Boolean, onlyPart: Int, depthLo: Float, depthHi: Float,
        erase: FloatArray?, slabAxis: Int, slabLo: Float, slabHi: Float,
    ): String
    @JvmStatic external fun nativePreviewCut(h: Long, pts: FloatArray?, depthLo: Float, depthHi: Float, erase: FloatArray?, mode: Int, exact: Boolean)
    @JvmStatic external fun nativeBrushSelect(h: Long, x: Float, y: Float, radius: Float, add: Boolean, vertex: Boolean, xray: Boolean, shape: Int): Long
    @JvmStatic external fun nativeMoveSelection(h: Long, dx: Float, dy: Float, begin: Boolean): Long
    @JvmStatic external fun nativeSelectionFromPreview(h: Long): Long
    @JvmStatic external fun nativeClearSelection(h: Long)
    @JvmStatic external fun nativeSeparateSelection(h: Long, name: String): String
    @JvmStatic external fun nativeMergeParts(h: Long, a: Int, b: Int, connect: Boolean): LongArray
    @JvmStatic external fun nativeEditPartSelection(h: Long, part: Int): Long
    @JvmStatic external fun nativeApplyPartEdit(h: Long, part: Int): LongArray
    @JvmStatic external fun nativeAnalyzeWings(h: Long, spanAxis: Int, stations: Int): DoubleArray
    @JvmStatic external fun nativeScaleModel(h: Long, factor: Double): Boolean
    @JvmStatic external fun nativePartsTouch(h: Long, a: Int, b: Int): Long
    @JvmStatic external fun nativeMirrorPoints(h: Long, pts: FloatArray, axis: Int): FloatArray
    @JvmStatic external fun nativeSetOrtho(h: Long, on: Boolean)
    @JvmStatic external fun nativeViewPreset(h: Long, preset: Int)
    @JvmStatic external fun nativeSetPartHinge(h: Long, part: Int, a: FloatArray, b: FloatArray, deg: Float)
    @JvmStatic external fun nativeDeletePart(h: Long, part: Int)
    @JvmStatic external fun nativeDetectMirrorAxis(h: Long): Int
    @JvmStatic external fun nativePixelsPerUnit(h: Long): Double
    @JvmStatic external fun nativeExportGlb(h: Long, fd: Int, pivots: FloatArray, images: Array<ByteArray?>, mimes: Array<String>, hidden: BooleanArray): String
    @JvmStatic external fun nativeSnap(h: Long, x: Float, y: Float, px: Float, corners: Boolean, edges: Boolean): FloatArray?
    @JvmStatic external fun nativeUnproject(h: Long, xy: FloatArray): FloatArray
    @JvmStatic external fun nativeUndoCut(h: Long): Boolean
    @JvmStatic external fun nativeRedoCut(h: Long): Boolean
    @JvmStatic external fun nativeRenamePart(h: Long, part: Int, name: String)
    @JvmStatic external fun nativeExportProgress(h: Long): Float
    @JvmStatic external fun nativeExportCancel(h: Long)

    // GL thread only
    @JvmStatic external fun nativeSurfaceCreated(h: Long): Boolean
    @JvmStatic external fun nativeSurfaceChanged(h: Long, w: Int, hgt: Int)
    @JvmStatic external fun nativeDraw(h: Long, nanos: Long): Boolean
    @JvmStatic external fun nativeClearModel(h: Long)
    @JvmStatic external fun nativeReleaseGl(h: Long)
    @JvmStatic external fun nativeSnapshot(h: Long, w: Int, hgt: Int, transparent: Boolean, out: java.nio.ByteBuffer): Boolean
    @JvmStatic external fun nativeAnalysisImage(h: Long, w: Int, hgt: Int, lighting: Int, out: java.nio.ByteBuffer): Boolean
    @JvmStatic external fun nativeBeginTurntable(h: Long)
    @JvmStatic external fun nativeTurntableFrame(h: Long, angle: Double, w: Int, hgt: Int)
    @JvmStatic external fun nativeEndTurntable(h: Long)
    @JvmStatic external fun nativeSetTexture(h: Long, material: Int, tex: Int)

    // any thread
    @JvmStatic external fun nativeSetMode(h: Long, mode: Int)
    @JvmStatic external fun nativeSetGrid(h: Long, on: Boolean)
    @JvmStatic external fun nativeSetAutoRotate(h: Long, on: Boolean)
    @JvmStatic external fun nativeSetUpAxisZ(h: Long, z: Boolean)
    @JvmStatic external fun nativeSetPointScale(h: Long, s: Float)
    @JvmStatic external fun nativeSetTheme(h: Long, colors: FloatArray)
    @JvmStatic external fun nativeSetPointColorMode(h: Long, mode: Int)
    @JvmStatic external fun nativeSetLighting(h: Long, preset: Int)
    @JvmStatic external fun nativeSetPartHidden(h: Long, part: Int, hidden: Boolean)
    @JvmStatic external fun nativeSetAllPartsHidden(h: Long, hidden: Boolean)
    @JvmStatic external fun nativeIsolatePart(h: Long, part: Int)
    @JvmStatic external fun nativeSelectPart(h: Long, part: Int)
    @JvmStatic external fun nativeFocusPart(h: Long, part: Int)
    @JvmStatic external fun nativeRequestPick(h: Long, x: Float, y: Float)
    @JvmStatic external fun nativePickResult(h: Long): Int
    @JvmStatic external fun nativeTouchDown(h: Long)
    @JvmStatic external fun nativeOrbit(h: Long, dx: Float, dy: Float)
    @JvmStatic external fun nativePan(h: Long, dx: Float, dy: Float)
    @JvmStatic external fun nativeZoom(h: Long, factor: Float)
    @JvmStatic external fun nativeFling(h: Long, vx: Float, vy: Float)
    @JvmStatic external fun nativeResetView(h: Long, animate: Boolean)
}
