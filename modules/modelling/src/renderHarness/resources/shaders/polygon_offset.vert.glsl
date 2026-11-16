#version 150
uniform mat4 TestProjection;
uniform float TestDistance, TestSlope;
uniform vec4 TestColor;
out float vertexDistance;
out vec4 vertexColor, lightMapColor, overlayColor;
out vec2 texCoord0, textureUV;
flat out vec4 textureBounds;
flat out float alphaCutoff;
out vec3 viewPos, viewNormal;
out mat3 TBN;
out vec3 viewLight0_Direction, viewLight1_Direction;
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vec2 q = p * 2.0 - 1.0;
    gl_Position = TestProjection * vec4(q * TestDistance,
            -TestDistance + q.x * TestSlope, 1.0);
    texCoord0 = textureUV = p;
    textureBounds = vec4(0, 0, 1, 1);
    vertexDistance = 0;
    vertexColor = vec4(1, 1, 1, TestColor.a);
    lightMapColor = vec4(1);
    overlayColor = vec4(TestColor.rgb, 0);
    alphaCutoff = .5;
    viewPos = vec3(0, 0, -2);
    viewNormal = vec3(0, 0, 1);
    TBN = mat3(1);
    viewLight0_Direction = vec3(0, 0, 1);
    viewLight1_Direction = vec3(0, 1, 0);
}
