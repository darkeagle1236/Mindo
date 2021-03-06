package com.otaliastudios.opengl.surface


import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.otaliastudios.opengl.core.Egl
import com.otaliastudios.opengl.core.EglCore
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Common base class for EGL surfaces.
 * There can be multiple base surfaces associated with a single [EglCore] object.
 */
open class EglSurface internal constructor(protected var eglCore: EglCore) {

    /**
     * Creates an offscreen surface.
     */
    internal constructor(eglCore: EglCore, width: Int, height: Int): this(eglCore) {
        eglSurface = eglCore.createOffscreenSurface(width, height)
        this.width = width
        this.height = height
    }

    /**
     * Creates a window surface.
     */
    internal constructor(eglCore: EglCore, surface: Surface): this(eglCore) {
        eglSurface = eglCore.createWindowSurface(surface)
        // Don't cache width/height here, because the size of the underlying surface can change
        // width = eglCore.querySurface(eglSurface, EGL14.EGL_WIDTH);
        // height = eglCore.querySurface(eglSurface, EGL14.EGL_HEIGHT);
    }

    /**
     * Creates a window surface.
     */
    internal constructor(eglCore: EglCore, surfaceTexture: SurfaceTexture): this(eglCore) {
        eglSurface = eglCore.createWindowSurface(surfaceTexture)
        // Same as above
    }

    protected var eglSurface = EGL14.EGL_NO_SURFACE
    private var width = -1
    private var height = -1

    /**
     * Returns the surface's width, in pixels.
     *
     * If this is called on a window surface, and the underlying surface is in the process
     * of changing size, we may not see the new size right away (e.g. in the "surfaceChanged"
     * callback).  The size should match after the next buffer swap.
     */
    fun getWidth(): Int {
        return if (width < 0) {
            eglCore.querySurface(eglSurface, EGL14.EGL_WIDTH)
        } else {
            width
        }
    }

    /**
     * Returns the surface's height, in pixels.
     */
    fun getHeight(): Int {
        return if (height < 0) {
            eglCore.querySurface(eglSurface, EGL14.EGL_HEIGHT)
        } else {
            height
        }
    }

    /**
     * Release the EGL surface.
     */
    open fun release() {
        eglCore.releaseSurface(eglSurface)
        eglSurface = EGL14.EGL_NO_SURFACE
        height = -1
        width = -1
    }

    /**
     * Whether this surface is current on the
     * attached [EglCore].
     */
    fun isCurrent(): Boolean {
        return eglCore.isSurfaceCurrent(eglSurface)
    }

    /**
     * Makes our EGL context and surface current.
     */
    fun makeCurrent() {
        eglCore.makeSurfaceCurrent(eglSurface)
    }

    /**
     * Makes our EGL context and surface current for drawing,
     * using the supplied surface for reading.
     */
    fun makeCurrent(readSurface: EglSurface) {
        eglCore.makeSurfaceCurrent(eglSurface, readSurface.eglSurface)
    }

    /**
     * Makes no surface current for the attached [eglCore].
     */
    fun makeNothingCurrent() {
        eglCore.makeNoSurfaceCurrent()
    }

    /**
     * Sends the presentation time stamp to EGL.
     * [nsecs] is the timestamp in nanoseconds.
     */
    fun setPresentationTime(nsecs: Long) {
        eglCore.setSurfacePresentationTime(eglSurface, nsecs)
    }

    /**
     * Saves the EGL surface to the given output stream.
     * Expects that this object's EGL surface is current.
     */
    fun saveFrameToOutputStream(stream: OutputStream, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG) {
        if (!isCurrent()) throw RuntimeException("Expected EGL context/surface is not current")
        // glReadPixels fills in a "direct" ByteBuffer with what is essentially big-endian RGBA
        // data (i.e. a byte of red, followed by a byte of green...).  While the Bitmap
        // constructor that takes an int[] wants little-endian ARGB (blue/red swapped), the
        // Bitmap "copy pixels" method wants the same format GL provides.
        //
        // Making this even more interesting is the upside-down nature of GL, which means
        // our output will look upside down relative to what appears on screen if the
        // typical GL conventions are used.
        val width = getWidth()
        val height = getHeight()
        val buf = ByteBuffer.allocateDirect(width * height * 4)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        Egl.check("glReadPixels")
        buf.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buf)
        bitmap.compress(format, 90, stream)
        bitmap.recycle()
    }

    /**
     * Saves the EGL surface to a file.
     * Expects that this object's EGL surface is current.
     */
    @Throws(IOException::class)
    fun saveFrameToFile(file: File, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG) {
        var stream: BufferedOutputStream? = null
        try {
            stream = BufferedOutputStream(FileOutputStream(file.toString()))
            saveFrameToOutputStream(stream, format)
        } finally {
            stream?.close()
        }
    }

    /**
     * Saves the EGL surface to given format.
     * Expects that this object's EGL surface is current.
     */
    fun saveFrameToByteArray(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val stream = ByteArrayOutputStream()
        stream.use {
            saveFrameToOutputStream(it, format)
            return it.toByteArray()
        }
    }

    companion object {
        protected val TAG = EglSurface::class.java.simpleName

        /**
         * Set releaseSurface to true if you want the Surface to be released when release() is
         * called.  This is convenient, but can interfere with framework classes that expect to
         * manage the Surface themselves (e.g. if you release a SurfaceView's Surface, the
         * surfaceDestroyed() callback won't fire).
         */
        @JvmOverloads
        @JvmStatic
        fun createWindowSurface(eglCore: EglCore, surface: Surface, releaseSurface: Boolean = false): EglWindowSurface {
            return EglWindowSurface(eglCore, surface, releaseSurface)
        }

        @JvmStatic
        fun createWindowSurface(eglCore: EglCore, surfaceTexture: SurfaceTexture): EglWindowSurface {
            return EglWindowSurface(eglCore, surfaceTexture)
        }

        @JvmStatic
        fun createOffscreenSurface(eglCore: EglCore, width: Int, height: Int): EglSurface {
            return EglSurface(eglCore, width, height)
        }
    }
}