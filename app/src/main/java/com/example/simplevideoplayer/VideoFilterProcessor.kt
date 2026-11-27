package com.example.simplevideoplayer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
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

enum class UpconvertMode(val label: String, val strength: Float) {
    OFF("アップコンバートOFF", 0.0f),
    LIGHT("アップコンバート標準", 0.45f),
    STRONG("アップコンバート強", 0.85f)
}

data class FilterPreset(
    val label: String,
    val saturation: Float,
    val contrast: Float,
    val sharpness: Float,
    val clarity: Float,
    val blackLift: Float
) {
    companion object {
        val STANDARD = FilterPreset("標準", 1.0f, 1.0f, 0.0f, 0.0f, 0.0f)
        val OLED = FilterPreset("OLED", 1.18f, 1.18f, 0.25f, 0.20f, 0.03f)
        val ANIME = FilterPreset("アニメ", 1.28f, 1.12f, 0.65f, 0.25f, 0.01f)
        val LIVE_ACTION = FilterPreset("実写", 1.06f, 1.08f, 0.30f, 0.35f, 0.01f)
        val entries = listOf(STANDARD, OLED, ANIME, LIVE_ACTION)
    }
}

@UnstableApi
private class ImageEnhanceEffect(
    private val saturation: Float,
    private val sharpness: Float,
    private val clarity: Float,
    private val blackLift: Float,
    private val noiseStrength: Float,
    private val upconvertStrength: Float
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ImageEnhanceShaderProgram(
            useHdr = useHdr,
            saturation = saturation,
            sharpness = sharpness,
            clarity = clarity,
            blackLift = blackLift,
            noiseStrength = noiseStrength,
            upconvertStrength = upconvertStrength
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageEnhanceEffect) return false
        return saturation == other.saturation &&
            sharpness == other.sharpness &&
            clarity == other.clarity &&
            blackLift == other.blackLift &&
            noiseStrength == other.noiseStrength &&
            upconvertStrength == other.upconvertStrength
    }

    override fun hashCode(): Int {
        var result = saturation.hashCode()
        result = 31 * result + sharpness.hashCode()
        result = 31 * result + clarity.hashCode()
        result = 31 * result + blackLift.hashCode()
        result = 31 * result + noiseStrength.hashCode()
        result = 31 * result + upconvertStrength.hashCode()
        return result
    }
}

@UnstableApi
private class ImageEnhanceShaderProgram(
    useHdr: Boolean,
    private val saturation: Float,
    private val sharpness: Float,
    private val clarity: Float,
    private val blackLift: Float,
    private val noiseStrength: Float,
    private val upconvertStrength: Float
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
        uniform float uClarity;
        uniform float uBlackLift;
        uniform float uNoiseStrength;
        uniform float uUpconvert;

        // ブロックノイズ軽減（8pxマクロブロック境界の段差緩和＋エッジ保持）
        vec3 applyDeblockFilter(vec2 uv, vec2 texel, float strength) {
            vec3 c = texture(uTexSampler, uv).rgb;
            if (strength <= 0.001) return c;

            vec2 pixelCoord = uv / texel;
            vec2 blockMod = mod(pixelCoord, 8.0);
            vec2 distToBoundary = min(blockMod, 8.0 - blockMod);
            float boundaryWeightX = 1.0 - smoothstep(0.0, 2.0, distToBoundary.x);
            float boundaryWeightY = 1.0 - smoothstep(0.0, 2.0, distToBoundary.y);
            float boundaryBoost = max(boundaryWeightX, boundaryWeightY);

            vec3 n = texture(uTexSampler, uv + vec2(0.0, -texel.y)).rgb;
            vec3 s = texture(uTexSampler, uv + vec2(0.0,  texel.y)).rgb;
            vec3 w = texture(uTexSampler, uv + vec2(-texel.x, 0.0)).rgb;
            vec3 e = texture(uTexSampler, uv + vec2( texel.x, 0.0)).rgb;

            vec3 w2 = texture(uTexSampler, uv + vec2(-2.0 * texel.x, 0.0)).rgb;
            vec3 e2 = texture(uTexSampler, uv + vec2( 2.0 * texel.x, 0.0)).rgb;
            vec3 n2 = texture(uTexSampler, uv + vec2(0.0, -2.0 * texel.y)).rgb;
            vec3 s2 = texture(uTexSampler, uv + vec2(0.0,  2.0 * texel.y)).rgb;

            vec3 hAvg = (w2 + w + c + e + e2) / 5.0;
            vec3 vAvg = (n2 + n + c + s + s2) / 5.0;
            vec3 deblockAvg = mix(c, (hAvg + vAvg) * 0.5, 0.5 + 0.5 * boundaryBoost);

            float localDiff = length(deblockAvg - c);
            float edgeKeep = 1.0 - smoothstep(0.06, 0.28, localDiff);
            float effectiveBlend = clamp(strength * (0.45 + 0.55 * boundaryBoost) * edgeKeep, 0.0, 0.92);

            return mix(c, deblockAvg, effectiveBlend);
        }

        // 高画質化・アップコンバート（輪郭復元＋明瞭度＋バンディング軽減）
        vec3 applyHighQualityUpconvert(vec2 uv, vec2 texel, vec3 baseColor, float upStrength, float clarityAmt) {
            vec3 n = texture(uTexSampler, uv + vec2(0.0, -texel.y)).rgb;
            vec3 s = texture(uTexSampler, uv + vec2(0.0,  texel.y)).rgb;
            vec3 w = texture(uTexSampler, uv + vec2(-texel.x, 0.0)).rgb;
            vec3 e = texture(uTexSampler, uv + vec2( texel.x, 0.0)).rgb;

            vec3 nw = texture(uTexSampler, uv + vec2(-texel.x, -texel.y)).rgb;
            vec3 ne = texture(uTexSampler, uv + vec2( texel.x, -texel.y)).rgb;
            vec3 sw = texture(uTexSampler, uv + vec2(-texel.x,  texel.y)).rgb;
            vec3 se = texture(uTexSampler, uv + vec2( texel.x,  texel.y)).rgb;

            vec3 nearAvg = (n + s + w + e) * 0.25;
            vec3 diagAvg = (nw + ne + sw + se) * 0.25;
            vec3 wideAvg = (nearAvg + diagAvg) * 0.5;

            // バンディング軽減：ごく微小な輝度差（色帯）だけ滑らかに馴染ませる
            float bandDiff = length(wideAvg - baseColor);
            float bandMask = 1.0 - smoothstep(0.005, 0.035, bandDiff);
            vec3 result = mix(baseColor, wideAvg, bandMask * 0.22 * max(upStrength, clarityAmt));

            // アップコンバート：輪郭方向の差を強調して精細感を復元
            if (upStrength > 0.001) {
                vec3 detail = result - nearAvg;
                result = result + detail * (upStrength * 1.15);
            }

            // 明瞭度（中域コントラスト）：広域平均との差分で立体感を出す
            if (abs(clarityAmt) > 0.001) {
                vec3 midDetail = result - wideAvg;
                result = result + midDetail * (clarityAmt * 0.85);
            }

            return result;
        }

        void main() {
            vec3 deblocked = applyDeblockFilter(vTexCoords, uTexelSize, uNoiseStrength);
            vec3 hqColor = applyHighQualityUpconvert(vTexCoords, uTexelSize, deblocked, uUpconvert, uClarity);

            vec3 n = texture(uTexSampler, vTexCoords + vec2(0.0, -uTexelSize.y)).rgb;
            vec3 s = texture(uTexSampler, vTexCoords + vec2(0.0,  uTexelSize.y)).rgb;
            vec3 w = texture(uTexSampler, vTexCoords + vec2(-uTexelSize.x, 0.0)).rgb;
            vec3 e = texture(uTexSampler, vTexCoords + vec2( uTexelSize.x, 0.0)).rgb;

            // 仕上げシャープネス
            vec3 crossAvg = (n + s + w + e) * 0.25;
            vec3 sharpened = hqColor + (hqColor - crossAvg) * (uSharpness * 1.5);

            // 彩度調整
            float luma = dot(sharpened, vec3(0.2126, 0.7152, 0.0722));
            vec3 satColor = mix(vec3(luma), sharpened, uSaturation);

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
            GLES30.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val info = GLES30.glGetProgramInfoLog(prog)
                GLES30.glDeleteProgram(prog)
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

        val clrLoc = GLES20.glGetUniformLocation(program, "uClarity")
        if (clrLoc >= 0) GLES20.glUniform1f(clrLoc, clarity)

        val blkLoc = GLES20.glGetUniformLocation(program, "uBlackLift")
        if (blkLoc >= 0) GLES20.glUniform1f(blkLoc, blackLift)

        val noiseLoc = GLES20.glGetUniformLocation(program, "uNoiseStrength")
        if (noiseLoc >= 0) GLES20.glUniform1f(noiseLoc, noiseStrength)

        val upLoc = GLES20.glGetUniformLocation(program, "uUpconvert")
        if (upLoc >= 0) GLES20.glUniform1f(upLoc, upconvertStrength)

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
    clarity: Float,
    blackLift: Float,
    noiseLevel: NoiseReductionLevel,
    upconvertMode: UpconvertMode
): List<Effect> {
    val result = mutableListOf<Effect>()
    val media3Contrast = (contrast - 1.0f).coerceIn(-0.9f, 0.9f)
    if (abs(media3Contrast) > 0.005f) {
        result.add(Contrast(media3Contrast))
    }

    val needsCustomGl = abs(saturation - 1.0f) > 0.005f ||
        abs(sharpness) > 0.005f ||
        abs(clarity) > 0.005f ||
        abs(blackLift) > 0.001f ||
        noiseLevel != NoiseReductionLevel.OFF ||
        upconvertMode != UpconvertMode.OFF

    if (needsCustomGl) {
        result.add(
            ImageEnhanceEffect(
                saturation = saturation,
                sharpness = sharpness,
                clarity = clarity,
                blackLift = blackLift,
                noiseStrength = noiseLevel.strength,
                upconvertStrength = upconvertMode.strength
            )
        )
    }
    return result
}
