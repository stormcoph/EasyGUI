#version 150

// Animated liquid: domain-warped fractal noise (fbm warping fbm) read as a slowly
// churning fluid surface, shaded with a three-stop palette plus a drifting gloss band.
//
// ColorA = deep liquid, ColorB = body, ColorC = surface highlight. Speed/Scale tune the
// motion and feature size; UVs span the filled rectangle. The vertex color multiplies
// the result, so tints, global alpha fades, and the anti-aliasing feather all behave.

uniform vec4 ColorModulator;
uniform float Time;
uniform vec3 ColorA;
uniform vec3 ColorB;
uniform vec3 ColorC;
uniform float Speed;
uniform float Scale;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; i++) {
        value += amplitude * noise(p);
        p = p * 2.03 + vec2(17.0, 9.2);
        amplitude *= 0.5;
    }
    return value;
}

void main() {
    float t = Time * 0.22 * Speed;
    vec2 uv = texCoord0 * vec2(3.0, 2.0) * Scale;

    // First warp layer drifts with time, second warps through the first.
    vec2 q = vec2(fbm(uv + vec2(0.0, t * 0.7)),
                  fbm(uv + vec2(5.2, 1.3) - vec2(t * 0.4, 0.0)));
    vec2 r = vec2(fbm(uv + 2.4 * q + vec2(1.7, 9.2) + vec2(t * 0.35, 0.0)),
                  fbm(uv + 2.1 * q + vec2(8.3, 2.8) - vec2(0.0, t * 0.3)));
    float f = fbm(uv + 2.0 * r);

    vec3 color = mix(ColorA, ColorB, smoothstep(0.15, 0.78, f));
    color = mix(color, ColorC, smoothstep(0.58, 0.95, f * f + 0.35 * r.x));

    // Glossy band sliding over the surface
    float gloss = smoothstep(0.5, 1.0, q.y * r.x * 2.2);
    color += ColorC * gloss * 0.3;

    fragColor = vec4(color, 1.0) * vertexColor * ColorModulator;
}
