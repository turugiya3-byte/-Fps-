#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uSaturation;
uniform float uContrast;
uniform float uSharpness;
uniform float uBlackLift;

void main() {
    vec3 baseColor = texture(uTexSampler, vTexCoord).rgb;

    vec3 n = texture(uTexSampler, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
    vec3 s = texture(uTexSampler, vTexCoord + vec2(0.0,  uTexelSize.y)).rgb;
    vec3 w = texture(uTexSampler, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
    vec3 e = texture(uTexSampler, vTexCoord + vec2( uTexelSize.x, 0.0)).rgb;
    vec3 neighborAvg = (n + s + w + e) * 0.25;
    vec3 sharpened = baseColor + (baseColor - neighborAvg) * (uSharpness * 1.6);

    vec3 contrasted = (sharpened - 0.5) * uContrast + 0.5;

    float luma = dot(contrasted, vec3(0.2126, 0.7152, 0.0722));
    vec3 saturated = mix(vec3(luma), contrasted, uSaturation);

    vec3 adjusted = max(saturated - vec3(uBlackLift), vec3(0.0));
    if (uBlackLift > 0.001) {
        adjusted = adjusted / max(1.0 - uBlackLift, 0.05);
    }

    fragColor = vec4(clamp(adjusted, 0.0, 1.0), 1.0);
}
