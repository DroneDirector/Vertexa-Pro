package com.dronedirector.vertexapro.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class ModelSurfaceView(context: Context, private val controller: ViewerController) : GLSurfaceView(context) {

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastFx = 0f
    private var lastFy = 0f
    private var multi = false
    private var velocity: VelocityTracker? = null

    private val doubleTap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            controller.resetView()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = controller.onSurfaceCreated()
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = controller.onSurfaceChanged(width, height)
            override fun onDrawFrame(gl: GL10?) {
                if (controller.onDrawFrame()) requestRender()
            }
        })
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        doubleTap.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                controller.touchDown()
                lastX = e.x; lastY = e.y
                multi = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                capturePinch(e)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (e.pointerCount >= 2) {
                    val (fx, fy, span) = pinch(e)
                    if (lastSpan > 0 && span > 0) controller.zoom(span / lastSpan)
                    controller.pan(fx - lastFx, fy - lastFy)
                    lastFx = fx; lastFy = fy; lastSpan = span
                } else if (!multi) {
                    controller.orbit(e.x - lastX, e.y - lastY)
                    lastX = e.x; lastY = e.y
                } else {
                    // one finger left after a pinch: continue orbiting smoothly from here
                    lastX = e.x; lastY = e.y
                    multi = false
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount - 1 >= 2) {
                    capturePinch(e, skip = e.actionIndex)
                } else {
                    val keep = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(keep); lastY = e.getY(keep)
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.let { vt ->
                    vt.addMovement(e)
                    vt.computeCurrentVelocity(1000)
                    if (!multi && hypot(vt.xVelocity, vt.yVelocity) > 400f) {
                        controller.fling(vt.xVelocity, vt.yVelocity)
                    }
                    vt.recycle()
                }
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle(); velocity = null
            }
        }
        requestRender()
        return true
    }

    private fun capturePinch(e: MotionEvent, skip: Int = -1) {
        val (fx, fy, span) = pinch(e, skip)
        lastFx = fx; lastFy = fy; lastSpan = span
    }

    private fun pinch(e: MotionEvent, skip: Int = -1): Triple<Float, Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        if (n == 0) return Triple(0f, 0f, 0f)
        val fx = sx / n; val fy = sy / n
        var span = 0f
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            span += hypot(e.getX(i) - fx, e.getY(i) - fy)
        }
        return Triple(fx, fy, span / n)
    }

    /** 4x MSAA with a 24-bit depth buffer; falls back gracefully on devices without it. */
    private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val es3 = 0x40 // EGL_OPENGL_ES3_BIT_KHR
            val attempts = listOf(
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, es3,
                    EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE),
            )
            for (a in attempts) {
                val num = IntArray(1)
                val configs = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, a, configs, 1, num) && num[0] > 0 && configs[0] != null) {
                    return configs[0]!!
                }
            }
            throw IllegalStateException("No suitable OpenGL ES 3 configuration")
        }
    }
}
