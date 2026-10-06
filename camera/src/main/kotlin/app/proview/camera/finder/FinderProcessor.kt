package app.proview.camera.finder

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import app.proview.pipeline.look.Look
import app.proview.pipeline.look.Lut3d
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Live viewfinder shader (OpenGL ES 2.0, works on Android 8+). Sits between the camera and
 * PreviewView as a CameraX effect, so PreviewView still handles rotation, cropping and the
 * rounded clip; the UI is unchanged.
 *
 * Per frame: the selected look's 3D LUT (same table the photo pipeline uses), plus the optics of
 * a waist-level finder on real glass: slight barrel distortion, lateral colour fringing, field-
 * curvature softness towards the edges, natural vignetting with a bright centre, a faint
 * ground-glass texture and Fresnel rings, and a breath of animated film grain.
 */
class FinderProcessor : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {
    private val thread = HandlerThread("finder-gl").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { handler.post(it) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var oesTexture = 0
    private var lutTexture = 0
    private var lutSize = Lut3d.SIZE

    private var input: SurfaceTexture? = null
    private var inputSize = Size(1440, 1080)
    private val outputs = LinkedHashMap<SurfaceOutput, Pair<EGLSurface, Size>>()
    private val texMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private val startMs = SystemClock.elapsedRealtime()

    @Volatile private var pendingLut: ByteArray? = Lut3d.bake(Look.DEFAULT).toTextureRgba()
    @Volatile private var grain = Look.DEFAULT.grain

    /** 0 = clean digital preview, 1 = full waist-level-finder optics. */
    @Volatile var finderStrength: Float = 1f

    /** Bakes and swaps the look's LUT (call off the main thread; baking takes ~20 ms). */
    fun setLook(look: Look) {
        pendingLut = Lut3d.bake(look).toTextureRgba()
        grain = look.grain
    }

    override fun onInputSurface(request: SurfaceRequest) {
        try {
            ensureGl()
        } catch (t: Throwable) {
            Log.e(TAG, "GL init failed", t)
            request.willNotProvideSurface()
            return
        }
        val st = SurfaceTexture(oesTexture)
        inputSize = request.resolution
        st.setDefaultBufferSize(inputSize.width, inputSize.height)
        val surface = Surface(st)
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            if (input === st) input = null
        }
        st.setOnFrameAvailableListener(this, handler)
        input = st
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        val surface = output.getSurface(executor) { event ->
            if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                outputs.remove(output)?.let { EGL14.eglDestroySurface(display, it.first) }
                output.close()
            }
        }
        try {
            ensureGl()
            val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            outputs[output] = egl to output.size
        } catch (t: Throwable) {
            Log.e(TAG, "Output surface failed", t)
            output.close()
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (st !== input) return
        try {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            pendingLut?.let { uploadLut(it); pendingLut = null }
            for ((out, pair) in outputs) {
                val (egl, size) = pair
                out.updateTransformMatrix(outMatrix, texMatrix)
                EGL14.eglMakeCurrent(display, egl, egl, context)
                GLES20.glViewport(0, 0, size.width, size.height)
                draw(size)
                EGLExt.eglPresentationTimeANDROID(display, egl, st.timestamp)
                EGL14.eglSwapBuffers(display, egl)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Frame failed", t)
        }
    }

    private fun draw(size: Size) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(loc("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        GLES20.glUniform1i(loc("uLut"), 1)
        GLES20.glUniformMatrix4fv(loc("uTexMatrix"), 1, false, outMatrix, 0)
        GLES20.glUniform1f(loc("uLutSize"), lutSize.toFloat())
        GLES20.glUniform1f(loc("uFinder"), finderStrength)
        GLES20.glUniform1f(loc("uGrain"), grain)
        GLES20.glUniform1f(loc("uAspect"), size.width.toFloat() / size.height)
        GLES20.glUniform2f(loc("uTexel"), 1f / inputSize.width, 1f / inputSize.height)
        GLES20.glUniform1f(loc("uTime"), ((SystemClock.elapsedRealtime() - startMs) % 100_000L) / 1000f)

        val pos = loc("aPos", attribute = true)
        val uv = loc("aUv", attribute = true)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, QUAD_UV)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private val locations = HashMap<String, Int>()
    private fun loc(name: String, attribute: Boolean = false): Int = locations.getOrPut(name) {
        if (attribute) GLES20.glGetAttribLocation(program, name) else GLES20.glGetUniformLocation(program, name)
    }

    private fun uploadLut(rgba: ByteArray) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        val buf = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder()).put(rgba)
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lutSize * lutSize, lutSize, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
        )
    }

    private fun ensureGl() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

        program = buildProgram(VERTEX, FRAGMENT)
        val tex = IntArray(2)
        GLES20.glGenTextures(2, tex, 0)
        oesTexture = tex[0]
        lutTexture = tex[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        pendingLut?.let { uploadLut(it); pendingLut = null }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    /** CameraX effect wrapper: applies this processor to the preview stream only. */
    inner class Effect : CameraEffect(PREVIEW, executor, this@FinderProcessor, { t -> Log.e(TAG, "Effect error", t) })

    companion object {
        private const val TAG = "FinderProcessor"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v).also { it.position(0) }

        private val QUAD = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val QUAD_UV = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private const val VERTEX = """
attribute vec4 aPos;
attribute vec2 aUv;
varying highp vec2 vUv;
void main() {
    gl_Position = aPos;
    vUv = aUv;
}
"""

        private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying highp vec2 vUv;
uniform samplerExternalOES uTex;
uniform sampler2D uLut;
uniform highp mat4 uTexMatrix;
uniform highp float uLutSize;
uniform float uFinder;
uniform float uGrain;
uniform float uAspect;
uniform highp vec2 uTexel;
uniform highp float uTime;

highp vec2 tex(highp vec2 uv) {
    return (uTexMatrix * vec4(clamp(uv, 0.0, 1.0), 0.0, 1.0)).xy;
}

vec3 sampleBlur(highp vec2 uv, highp vec2 o) {
    vec3 c = texture2D(uTex, tex(uv)).rgb * 0.4;
    c += texture2D(uTex, tex(uv + vec2(o.x, o.y))).rgb * 0.15;
    c += texture2D(uTex, tex(uv + vec2(-o.x, o.y))).rgb * 0.15;
    c += texture2D(uTex, tex(uv + vec2(o.x, -o.y))).rgb * 0.15;
    c += texture2D(uTex, tex(uv - o)).rgb * 0.15;
    return c;
}

vec3 lut(vec3 c) {
    highp float n = uLutSize - 1.0;
    highp float b = c.b * n;
    highp float b0 = floor(b);
    highp float b1 = min(b0 + 1.0, n);
    highp float t = b - b0;
    highp float w = uLutSize * uLutSize;
    highp vec2 p0 = vec2((b0 * uLutSize + c.r * n + 0.5) / w, (c.g * n + 0.5) / uLutSize);
    highp vec2 p1 = vec2((b1 * uLutSize + c.r * n + 0.5) / w, (c.g * n + 0.5) / uLutSize);
    return mix(texture2D(uLut, p0).rgb, texture2D(uLut, p1).rgb, t);
}

highp float hash(highp vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    highp vec2 c = vUv - 0.5;
    highp vec2 ca = vec2(c.x * uAspect, c.y);
    // Normalised radius squared: 0 at the centre, 1 in the corners.
    highp float r2 = dot(ca, ca) / (0.25 * uAspect * uAspect + 0.25);

    // Barrel distortion, scaled so the corners stay inside the frame.
    float k = 0.035 * uFinder;
    highp vec2 d = c * (1.0 + k * r2) / (1.0 + k);
    // Lateral colour: red focuses slightly larger, blue slightly smaller, at the edges.
    float fringe = 0.0035 * uFinder * r2;
    // Field curvature: the edges fall out of focus, as on a real ground glass.
    highp vec2 o = uTexel * (3.0 * uFinder * r2 * r2 + 0.001);
    vec3 col;
    col.r = sampleBlur(0.5 + d * (1.0 + fringe), o).r;
    col.g = sampleBlur(0.5 + d, o).g;
    col.b = sampleBlur(0.5 + d * (1.0 - fringe), o).b;

    // Natural vignetting with the bright centre of a condenser-less ground glass.
    float vig = 1.0 - uFinder * 0.42 * pow(r2, 1.35);
    vig *= 1.0 + uFinder * 0.05 * (1.0 - r2);
    col *= vig;

    col = lut(clamp(col, 0.0, 1.0));

    // Ground glass: a static, very fine texture and faint Fresnel rings.
    float glass = (hash(gl_FragCoord.xy) - 0.5) * 0.035 * uFinder;
    float rings = sin(sqrt(r2) * 140.0) * 0.006 * uFinder;
    // Film grain breathing gently, from the look.
    float grain = (hash(gl_FragCoord.xy + fract(uTime) * 61.0) - 0.5) * uGrain * 1.2;
    col += glass + rings + grain;

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
    }
}
