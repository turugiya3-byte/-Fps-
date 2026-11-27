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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

enum class NoiseReductionLevel(val label: String, val strength: Float) {
    OFF("ノイズOFF", 0.0f),
    SMALL("ノイズ小", 0.35f),
    MEDIUM("ノイズ中", 0.65f),
    LARGE("ノイズ大", 1.0f)
}

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
private class ImageEnhanceEffect(
    private val saturation: Float,
    private val sharpness: Float,
    private val blackLift: Float,
    private val noiseStrength: Float,
    private val aiHdrEnabled: Boolean
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ImageEnhanceShaderProgram(
            useHdr = useHdr,
            saturation = saturation,
            sharpness = sharpness,
            blackLift = blackLift,
            noiseStrength = noiseStrength,
            aiHdrEnabled = aiHdrEnabled
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageEnhanceEffect) return false
        return saturation == other.saturation &&
            sharpness == other.sharpness &&
            blackLift == other.blackLift &&
            noiseStrength == other.noiseStrength &&
            aiHdrEnabled == other.aiHdrEnabled
    }

    override fun hashCode(): Int {
        var result = saturation.hashCode()
        result = 31 * result + sharpness.hashCode()
        result = 31 * result + blackLift.hashCode()
        result = 31 * result + noiseStrength.hashCode()
        result = 31 * result + aiHdrEnabled.hashCode()
        return result
    }
}

@UnstableApi
private class ImageEnhanceShaderProgram(
    useHdr: Boolean,
    private val saturation: Float,
    private val sharpness: Float,
    private val blackLift: Float,
    private val noiseStrength: Float,
    private val aiHdrEnabled: Boolean
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
        uniform float uNoiseStrength;
        uniform float uAiHdr;

        void main() {
            vec3 c = texture(uTexSampler, vTexCoords).rgb;
            vec3 n = texture(uTexSampler, vTexCoords + vec2(0.0, -uTexelSize.y)).rgb;
            vec3 s = texture(uTexSampler, vTexCoords + vec2(0.0,  uTexelSize.y)).rgb;
            vec3 w = texture(uTexSampler, vTexCoords + vec2(-uTexelSize.x, 0.0)).rgb;
            vec3 e = texture(uTexSampler, vTexCoords + vec2( uTexelSize.x, 0.0)).rgb;

            vec3 nw = texture(uTexSampler, vTexCoords + vec2(-uTexelSize.x, -uTexelSize.y)).rgb;
            vec3 ne = texture(uTexSampler, vTexCoords + vec2( uTexelSize.x, -uTexelSize.y)).rgb;
            vec3 sw = texture(uTexSampler, vTexCoords + vec2(-uTexelSize.x,  uTexelSize.y)).rgb;
            vec3 se = texture(uTexSampler, vTexCoords + vec2( uTexelSize.x,  uTexelSize.y)).rgb;

            // 9点平均によるノイズ軽減（エッジ差が大きい画素は重みを下げて輪郭を保持）
            vec3 box9 = (c + n + s + w + e + nw + ne + sw + se) / 9.0;
            float diffMag = length(box9 - c);
            float edgePreserve = clamp(1.0 - diffMag * 3.5, 0.15, 1.0);
            vec3 denoised = mix(c, box9, clamp(uNoiseStrength * 0.85 * edgePreserve, 0.0, 0.85));

            // シャープネス適用
            vec3 crossAvg = (n + s + w + e) * 0.25;
            vec3 sharpened = denoised + (denoised - crossAvg) * (uSharpness * 1.5);

            // AI HDR トーンマッピング（暗部持ち上げ＋S字コントラスト＋彩度ブースト）
            vec3 hdrAdjusted = sharpened;
            if (uAiHdr > 0.5) {
                vec3 shadowLift = pow(clamp(hdrAdjusted, 0.0, 1.0), vec3(0.78));
                vec3 sCurve = shadowLift / (shadowLift + vec3(0.18)) * 1.18;
                float hdrLuma = dot(sCurve, vec3(0.2126, 0.7152, 0.0722));
                hdrAdjusted = mix(vec3(hdrLuma), sCurve, 1.22);
            }

            // 彩度調整
            float luma = dot(hdrAdjusted, vec3(0.2126, 0.7152, 0.0722));
            vec3 satColor = mix(vec3(luma), hdrAdjusted, uSaturation);

            // 黒浮き抑制
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
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val info = GLES30.glGetProgramInfoLog(prog)
                GLES30.glDeleteProgram(prog)
                throw RuntimeException("Link failed: $info")
            }
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
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
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId)

        val texLoc = GLES30.glGetUniformLocation(program, "uTexSampler")
        if (texLoc >= 0) GLES30.glUniform1i(texLoc, 0)

        val texelLoc = GLES30.glGetUniformLocation(program, "uTexelSize")
        if (texelLoc >= 0) GLES30.glUniform2f(texelLoc, 1f / texWidth, 1f / texHeight)

        val satLoc = GLES30.glGetUniformLocation(program, "uSaturation")
        if (satLoc >= 0) GLES30.glUniform1f(satLoc, saturation)

        val shpLoc = GLES30.glGetUniformLocation(program, "uSharpness")
        if (shpLoc >= 0) GLES30.glUniform1f(shpLoc, sharpness)

        val blkLoc = GLES30.glGetUniformLocation(program, "uBlackLift")
        if (blkLoc >= 0) GLES30.glUniform1f(blkLoc, blackLift)

        val noiseLoc = GLES30.glGetUniformLocation(program, "uNoiseStrength")
        if (noiseLoc >= 0) GLES30.glUniform1f(noiseLoc, noiseStrength)

        val hdrLoc = GLES30.glGetUniformLocation(program, "uAiHdr")
        if (hdrLoc >= 0) GLES30.glUniform1f(hdrLoc, if (aiHdrEnabled) 1.0f else 0.0f)

        quadBuffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    override fun release() {
        super.release()
        if (program != 0) {
            GLES30.glDeleteProgram(program)
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
    blackLift: Float,
    noiseLevel: NoiseReductionLevel,
    aiHdrEnabled: Boolean
): List<Effect> {
    val result = mutableListOf<Effect>()
    val media3Contrast = (contrast - 1.0f).coerceIn(-0.9f, 0.9f)
    if (abs(media3Contrast) > 0.005f) {
        result.add(Contrast(media3Contrast))
    }

    val needsCustomGl = abs(saturation - 1.0f) > 0.005f ||
        abs(sharpness) > 0.005f ||
        abs(blackLift) > 0.001f ||
        noiseLevel != NoiseReductionLevel.OFF ||
        aiHdrEnabled

    if (needsCustomGl) {
        result.add(
            ImageEnhanceEffect(
                saturation = saturation,
                sharpness = sharpness,
                blackLift = blackLift,
                noiseStrength = noiseLevel.strength,
                aiHdrEnabled = aiHdrEnabled
            )
        )
    }
    return result
}
