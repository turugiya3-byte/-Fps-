package com.example.simplevideoplayer

import android.content.Context
import android.opengl.GLES30
import androidx.media3.common.Effect
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

data class FilterPreset(
    val label: String,
    val saturation: Float,
    val contrast: Float,
    val sharpness: Float,
    val blackLift: Float
) {
    companion object {
        val STANDARD = FilterPreset("標準", 1.0f, 1.0f, 0.0f, 0.0f)
        val OLED = FilterPreset("OLED", 1.18f, 1.18f, 0.25f, 0.03f)
        val ANIME = FilterPreset("アニメ", 1.28f, 1.12f, 0.65f, 0.01f)
        val LIVE_ACTION = FilterPreset("実写", 1.06f, 1.08f, 0.30f, 0.01f)
        val entries = listOf(STANDARD, OLED, ANIME, LIVE_ACTION)
    }
}

@UnstableApi
class CustomGlslVideoEffect(
    private val context: Context,
    private val saturation: Float,
    private val contrast: Float,
    private val sharpness: Float,
    private val blackLift: Float
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CustomGlslShaderProgram(
            appContext = this.context,
            saturation = saturation,
            contrast = contrast,
            sharpness = sharpness,
            blackLift = blackLift
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomGlslVideoEffect) return false
        return saturation == other.saturation &&
            contrast == other.contrast &&
            sharpness == other.sharpness &&
            blackLift == other.blackLift
    }

    override fun hashCode(): Int {
        var result = saturation.hashCode()
        result = 31 * result + contrast.hashCode()
        result = 31 * result + sharpness.hashCode()
        result = 31 * result + blackLift.hashCode()
        return result
    }
}

@UnstableApi
private class CustomGlslShaderProgram(
    appContext: Context,
    private val saturation: Float,
    private val contrast: Float,
    private val sharpness: Float,
    private val blackLift: Float
) : GlShaderProgram {

    private var listener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener { }
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}

    private var programId = 0
    private var quadBuffer: FloatBuffer? = null
    private var inputWidth = 1280
    private var inputHeight = 720
    private var outputTexId = 0
    private var outputFboId = 0

    init {
        try {
            val vertSrc = "#version 300 es\n" +
                "in vec4 aPosition;\n" +
                "in vec2 aTexCoord;\n" +
                "out vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = aPosition;\n" +
                "    vTexCoord = aTexCoord;\n" +
                "}\n"
            val fragSrc = appContext.assets.open("shaders/video_filter.glsl")
                .bufferedReader().use { it.readText() }

            val vs = compile(GLES30.GL_VERTEX_SHADER, vertSrc)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
            programId = GLES30.glCreateProgram().also { prog ->
                GLES30.glAttachShader(prog, vs)
                GLES30.glAttachShader(prog, fs)
                GLES30.glBindAttribLocation(prog, 0, "aPosition")
                GLES30.glBindAttribLocation(prog, 1, "aTexCoord")
                GLES30.glLinkProgram(prog)
                val linked = IntArray(1)
                GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linked, 0)
                if (linked[0] == 0) {
                    val log = GLES30.glGetProgramInfoLog(prog)
                    GLES30.glDeleteProgram(prog)
                    throw RuntimeException("Program link error: $log")
                }
            }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)

            val quad = floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            )
            quadBuffer = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(quad)
                    position(0)
                }

            val fbos = IntArray(1)
            GLES30.glGenFramebuffers(1, fbos, 0)
            outputFboId = fbos[0]
        } catch (e: Exception) {
            errorListener.onError(VideoFrameProcessingException(e))
        }
    }

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        this.listener = inputListener
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        this.outputListener = outputListener
    }

    override fun setErrorListener(errorListener: GlShaderProgram.ErrorListener) {
        this.errorListener = errorListener
    }

    override fun queueInputFrame(
        inputTexture: GlTextureInfo,
        presentationTimeUs: Long
    ) {
        try {
            if (inputTexture.width > 0 && inputTexture.height > 0) {
                if (inputWidth != inputTexture.width || inputHeight != inputTexture.height || outputTexId == 0) {
                    inputWidth = inputTexture.width
                    inputHeight = inputTexture.height
                    ensureOutputTexture(inputWidth, inputHeight)
                }
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFboId)
            GLES30.glViewport(0, 0, inputWidth, inputHeight)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            GLES30.glUseProgram(programId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture.texId)
            val texLoc = GLES30.glGetUniformLocation(programId, "uTexSampler")
            if (texLoc >= 0) GLES30.glUniform1i(texLoc, 0)

            val texelLoc = GLES30.glGetUniformLocation(programId, "uTexelSize")
            if (texelLoc >= 0) GLES30.glUniform2f(texelLoc, 1f / inputWidth, 1f / inputHeight)

            val satLoc = GLES30.glGetUniformLocation(programId, "uSaturation")
            if (satLoc >= 0) GLES30.glUniform1f(satLoc, saturation)

            val conLoc = GLES30.glGetUniformLocation(programId, "uContrast")
            if (conLoc >= 0) GLES30.glUniform1f(conLoc, contrast)

            val shpLoc = GLES30.glGetUniformLocation(programId, "uSharpness")
            if (shpLoc >= 0) GLES30.glUniform1f(shpLoc, sharpness)

            val blkLoc = GLES30.glGetUniformLocation(programId, "uBlackLift")
            if (blkLoc >= 0) GLES30.glUniform1f(blkLoc, blackLift)

            quadBuffer?.let { buf ->
                buf.position(0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, buf)

                buf.position(2)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, buf)

                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

                GLES30.glDisableVertexAttribArray(0)
                GLES30.glDisableVertexAttribArray(1)
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            val outInfo = GlTextureInfo(
                outputTexId,
                outputFboId,
                -1,
                inputWidth,
                inputHeight
            )
            outputListener.onOutputFrameAvailable(outInfo, presentationTimeUs)
            listener.onInputFrameProcessed(inputTexture)
        } catch (e: Exception) {
            errorListener.onError(VideoFrameProcessingException(e))
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
    }

    override fun signalEndOfCurrentInputStream() {
        outputListener.onCurrentOutputStreamEnded()
    }

    override fun flush() {
        listener.onFlushCommandReceived()
    }

    override fun release() {
        if (outputTexId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(outputTexId), 0)
            outputTexId = 0
        }
        if (outputFboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(outputFboId), 0)
            outputFboId = 0
        }
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun ensureOutputTexture(w: Int, h: Int) {
        if (outputTexId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(outputTexId), 0)
        }
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        outputTexId = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTexId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, outputTexId, 0
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile error: $log")
        }
        return shader
    }
}

fun buildVideoEffectsList(
    context: Context,
    saturation: Float,
    contrast: Float,
    sharpness: Float,
    blackLift: Float
): List<Effect> {
    val isDefault = abs(saturation - 1.0f) < 0.005f &&
        abs(contrast - 1.0f) < 0.005f &&
        abs(sharpness) < 0.005f &&
        abs(blackLift) < 0.001f
    if (isDefault) return emptyList()
    return listOf(
        CustomGlslVideoEffect(
            context = context,
            saturation = saturation,
            contrast = contrast,
            sharpness = sharpness,
            blackLift = blackLift
        )
    )
}
