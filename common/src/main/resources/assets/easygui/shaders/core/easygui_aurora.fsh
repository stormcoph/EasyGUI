#version 150

// Slowly drifting multi-hue gradient ("aurora"). UVs span the rectangle; the vertex
// color multiplies the result so tints and the anti-aliasing feather work as usual.

uniform vec4 ColorModulator;
uniform float Time;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 uv = texCoord0;
    float t = Time * 0.45;

    vec3 blue = vec3(0.357, 0.549, 1.0);
    vec3 violet = vec3(0.73, 0.42, 1.0);
    vec3 teal = vec3(0.25, 0.88, 0.82);

    float w1 = 0.5 + 0.5 * sin(uv.x * 4.0 + t);
    float w2 = 0.5 + 0.5 * sin(uv.x * 7.0 - t * 1.3 + sin(uv.y * 3.0 + t * 0.7) * 1.2);
    vec3 color = mix(mix(blue, violet, w1), teal, w2 * 0.5);
    // Gentle shimmer
    color *= 0.92 + 0.08 * sin(uv.x * 20.0 - t * 3.0);

    fragColor = vec4(color, 1.0) * vertexColor * ColorModulator;
}
