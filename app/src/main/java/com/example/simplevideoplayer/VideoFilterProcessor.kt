package com.example.simplevideoplayer

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.Contrast
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.RgbFilter
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
private class SaturationAndSharpnessEffect(
    private val saturation: Float,
    private val sharpness: Float,
    private val blackLift: Float
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return SaturationSharpnessProgram(
            useHdr = useHdr,
            saturation = saturation,
            sharpness = sharpness,
            blackLift = blackLift
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SaturationAndSharpnessEffect) return false
        return saturation == other.saturation &&
            sharpness == other.sharpness &&
            blackLift == other.blackLift
    }

    override fun hashCode(): Int {
        var result = saturation.hashCode()
        result = 31 * result + sharpness.hashCode()
        result = 31 * result + blackLift.hashCode()
        return result
    }
}

@UnstableApi
private class SaturationSharpnessProgram(
    useHdr: Boolean,
    private val saturation: Float,
    private val sharpness: Float,
    private val blackLift: Float
) : BaseGlShaderProgram(useHdr, 1) {

    private val vertexShaderCode = """
        #version 300 es
        in vec4 aFramePosition;
        in vec2 aTexCoords;
        out vec2 vTexCoords;
        void main() {
            gl_Position = aFramePosition;
            vTexCoords = aTexCoords;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #version 300 es
        precision highp float;
        in vec2 vTexCoords;
        out vec4 outColor;
        uniform sampler2D uTexSampler;
        uniform vec2 uTexelSize;
        uniform float uSaturation;
        uniform float uSharpness;
        uniform float uBlackLift;

        void main() {
            vec3 c = texture(uTexSampler, vTexCoords).rgb;
            vec3 n = texture(uTexSampler, vTexCoords + vec2(0.0, -uTexelSize.y)).rgb;
            vec3 s = texture(uTexSampler, vTexCoords + vec2(0.0,  uTexelSize.y)).rgb;
            vec3 w = texture(uTexSampler, vTexCoords + vec2(-uTexelSize.x, 0.0)).rgb;
            vec3 e = texture(uTexSampler, vTexCoords + vec2( uTexelSize.x, 0.0)).rgb;

            vec3 avgNeighbor = (n + s + w + e) * 0.25;
            vec3 sharpened = c + (c - avgNeighbor) * (uSharpness * 1.5);

            float luma = dot(sharpened, vec3(0.2126, 0.7152, 0.0722));
            vec3 satColor = mix(vec3(luma), sharpened, uSaturation);

            vec3 lifted = max(satColor - vec3(uBlackLift), vec3(0.0));
            if (uBlackLift > 0.001) {
                lifted = lifted / max(1.0 - uBlackLift, 0.05);
            }

            outColor = vec4(clamp(lifted, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    private var program = 0
    private var texWidth = 1280
    private var texHeight = 720
    private val quadBuffer: FloatBuffer

    init {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )
        quadBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
        try {
            setupProgram()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun setupProgram() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glBindAttribLocation(prog, 0, "aFramePosition")
            GLES20.glBindAttribLocation(prog, 1, "aTexCoords")
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val info = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                throw RuntimeException("Link failed: $info")
            }
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Compile failed: $info")
        }
        return shader
    }

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        texWidth = inputWidth.coerceAtLeast(1)
        texHeight = inputHeight.coerceAtLeast(1)
        return androidx.media3.common.util.Size(texWidth, texHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)

        val texLoc = GLES20.glGetUniformLocation(program, "uTexSampler")
        if (texLoc >= 0) GLES20.glUniform1i(texLoc, 0)

        val texelLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
        if (texelLoc >= 0) GLES20.glUniform2f(texelLoc, 1f / texWidth, 1f / texHeight)

        val satLoc = GLES20.glGetUniformLocation(program, "uSaturation")
        if (satLoc >= 0) GLES20.glUniform1f(satLoc, saturation)

        val shpLoc = GLES20.glGetUniformLocation(program, "uSharpness")
        if (shpLoc >= 0) GLES20.glUniform1f(shpLoc, sharpness)

        val blkLoc = GLES20.glGetUniformLocation(program, "uBlackLift")
        if (blkLoc >= 0) GLES20.glUniform1f(blkLoc, blackLift)

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
    }

    override fun release() {
        super.release()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }
}

@UnstableApi
fun buildVideoEffectsList(
    context: Context,
    saturation: Float,
    contrast: Float,
    sharpness: Float,
    blackLift: Float
): List<Effect> {
    val result = mutableListOf<Effect>()
    // Media3 標準の Contrast は -1.0f〜1.0f（0.0f が標準）のため変換
    val media3Contrast = (contrast - 1.0f).coerceIn(-0.9f, 0.9f)
    if (abs(media3Contrast) > 0.005f) {
        result.add(Contrast(media3Contrast))
    }

    val needsCustomGl = abs(saturation - 1.0f) > 0.005f ||
        abs(sharpness) > 0.005f ||
        abs(blackLift) > 0.001f

    if (needsCustomGl) {
        result.add(
            SaturationAndSharpnessEffect(
                saturation = saturation,
                sharpness = sharpness,
                blackLift = blackLift
            )
        )
    }
    return result
}
