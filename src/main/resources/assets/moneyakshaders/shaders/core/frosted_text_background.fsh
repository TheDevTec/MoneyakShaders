#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 tint = vertexColor * ColorModulator;
    if (tint.a < 0.02) discard;

    // The snapshot is a completed preceding world frame, so this 9-tap filter is
    // feedback-safe while still giving water, glass and entities a real frosted look.
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler0, 0));
    vec2 px = 8.0 / vec2(textureSize(Sampler0, 0));
    vec3 blur = texture(Sampler0, uv).rgb * 0.24;
    blur += texture(Sampler0, uv + vec2( px.x, 0.0)).rgb * 0.12;
    blur += texture(Sampler0, uv + vec2(-px.x, 0.0)).rgb * 0.12;
    blur += texture(Sampler0, uv + vec2(0.0,  px.y)).rgb * 0.12;
    blur += texture(Sampler0, uv + vec2(0.0, -px.y)).rgb * 0.12;
    blur += texture(Sampler0, uv + vec2( px.x,  px.y)).rgb * 0.07;
    blur += texture(Sampler0, uv + vec2(-px.x,  px.y)).rgb * 0.07;
    blur += texture(Sampler0, uv + vec2( px.x, -px.y)).rgb * 0.07;
    blur += texture(Sampler0, uv + vec2(-px.x, -px.y)).rgb * 0.07;
    vec3 frosted = mix(blur, tint.rgb, 0.08);
    vec4 color = vec4(frosted, max(tint.a, 0.58));
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart,
            FogRenderDistanceEnd, FogColor);
}
