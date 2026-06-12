#version 150

// Composites the pre-blurred framebuffer (Sampler0) into arbitrary GUI geometry by
// sampling at the on-screen fragment position, so pose transforms "see through"
// correctly. Vertex RGB is the tint color, vertex A is shape coverage (the feather
// ring fades it to 0); TintAlpha is how strongly the tint covers the blur.

uniform sampler2D Sampler0;
uniform vec2 ScreenSize;
uniform vec4 ColorModulator;
uniform float TintAlpha;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (vertexColor.a == 0.0) {
        discard;
    }
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec3 blurred = texture(Sampler0, uv).rgb;
    vec3 color = mix(blurred, vertexColor.rgb, TintAlpha);
    fragColor = vec4(color, vertexColor.a) * ColorModulator;
}
