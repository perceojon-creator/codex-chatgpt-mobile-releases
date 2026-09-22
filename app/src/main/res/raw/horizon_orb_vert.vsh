#version 300 es

out vec3 generatedCoordinate;
out vec2 sdfPosition;

const vec2 positions[4] = vec2[](
    vec2(-1.0, -1.0),
    vec2(1.0, -1.0),
    vec2(-1.0, 1.0),
    vec2(1.0, 1.0)
);

void main() {
    vec2 position = positions[gl_VertexID];
    generatedCoordinate = vec3(position * 0.5 + 0.5, 0.5);
    sdfPosition = position;
    gl_Position = vec4(position, 0.0, 1.0);
}
