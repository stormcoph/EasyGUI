#version 150

// One direction of a separable gaussian blur: 5 linear-filtered taps ~ 9-tap gaussian.
// BlurDir is the blur axis scaled by the per-pass offset factor; SrcSize is the source
// texture size in texels.

uniform sampler2D Sampler0;
uniform vec2 BlurDir;
uniform vec2 SrcSize;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 step = BlurDir / SrcSize;
    vec3 color = texture(Sampler0, texCoord).rgb * 0.2270270270;
    color += texture(Sampler0, texCoord + step * 1.3846153846).rgb * 0.3162162162;
    color += texture(Sampler0, texCoord - step * 1.3846153846).rgb * 0.3162162162;
    color += texture(Sampler0, texCoord + step * 3.2307692308).rgb * 0.0702702703;
    color += texture(Sampler0, texCoord - step * 3.2307692308).rgb * 0.0702702703;
    fragColor = vec4(color, 1.0);
}
