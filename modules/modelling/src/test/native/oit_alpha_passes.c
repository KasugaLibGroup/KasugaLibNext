#define GL_SILENCE_DEPRECATION
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl3.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* GPU regression for the production alpha-only revealage and R8 footprint.
 * clang oit_alpha_passes.c -framework OpenGL -o /tmp/oit-alpha-passes
 * /tmp/oit-alpha-passes path/to/ksglib_main.fsh path/to/minecraft/fog.glsl
 * Extract fog.glsl from the development Minecraft resources jar. This test
 * compares alpha against the fully shaded path, including parallax and fog.
 */
#define W 64
static char *read_file(const char *path) {
    FILE *file = fopen(path, "rb");
    if (!file) { perror(path); exit(2); }
    fseek(file, 0, SEEK_END); long size = ftell(file); rewind(file);
    char *source = calloc((size_t) size + 1, 1);
    if (!source || fread(source, 1, (size_t) size, file) != (size_t) size) exit(2);
    fclose(file);
    return source;
}
static GLuint compile(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL); glCompileShader(shader);
    GLint ok; glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096]; glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        fprintf(stderr, "%s\n", log); exit(3);
    }
    return shader;
}
static GLuint texture(GLint format, int width, const float *data) {
    GLuint result; glGenTextures(1, &result); glBindTexture(GL_TEXTURE_2D, result);
    glTexImage2D(GL_TEXTURE_2D, 0, format, width, 1, 0, GL_RGBA, GL_FLOAT, data);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return result;
}
static void attach(GLuint texture) {
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) exit(4);
}
static void integer(GLuint program, const char *name, int value) {
    glUniform1i(glGetUniformLocation(program, name), value);
}
static void scalar(GLuint program, const char *name, float value) {
    glUniform1f(glGetUniformLocation(program, name), value);
}
static void draw(GLuint program, int mode, float *pixels) {
    integer(program, "ksg_OitMode", mode);
    glClearColor(-1, -1, -1, -1); glClear(GL_COLOR_BUFFER_BIT);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glReadPixels(0, 0, W, 1, GL_RGBA, GL_FLOAT, pixels);
}
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    char *fragment = read_file(argv[1]), *fog = read_file(argv[2]);
    const char *import = "#moj_import <fog.glsl>";
    char *position = strstr(fragment, import);
    if (!position) return 2;
    char *fogBody = strncmp(fog, "#version", 8) == 0 ? strchr(fog, '\n') + 1 : fog;
    size_t head = (size_t) (position - fragment);
    char *expanded = calloc(strlen(fragment) + strlen(fog) + 1, 1);
    memcpy(expanded, fragment, head);
    strcat(expanded, fogBody); strcat(expanded, position + strlen(import));

    CGLPixelFormatAttribute attributes[] = {kCGLPFAOpenGLProfile,
        (CGLPixelFormatAttribute) kCGLOGLPVersion_3_2_Core, 0};
    CGLPixelFormatObj format; CGLContextObj context; GLint count;
    if (CGLChoosePixelFormat(attributes, &format, &count)
            || CGLCreateContext(format, NULL, &context)) {
        fprintf(stderr, "Cannot create CGL context\n"); return 1;
    }
    CGLSetCurrentContext(context);
    const char *vertex = "#version 150\n"
        "uniform float testAlpha, testDistance;"
        "out float vertexDistance;out vec4 vertexColor,lightMapColor,overlayColor;"
        "out vec2 texCoord0,textureUV;flat out vec4 textureBounds;flat out float alphaCutoff;"
        "out vec3 viewPos,viewNormal;out mat3 TBN;out vec3 viewLight0_Direction,viewLight1_Direction;"
        "void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);"
        "gl_Position=vec4(p*2-1,0,1);texCoord0=textureUV=p;textureBounds=vec4(0,0,1,1);"
        "vertexDistance=testDistance;vertexColor=vec4(1,1,1,testAlpha);"
        "lightMapColor=vec4(1);overlayColor=vec4(0,0,0,1);alphaCutoff=.5;"
        "viewPos=vec3(.5,0,-2);viewNormal=vec3(0,0,1);TBN=mat3(1);"
        "viewLight0_Direction=vec3(0,0,1);viewLight1_Direction=vec3(0,1,0);}";
    GLuint vs = compile(GL_VERTEX_SHADER, vertex), fs = compile(GL_FRAGMENT_SHADER, expanded);
    GLuint program = glCreateProgram();
    glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
    GLint linked; glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[4096]; glGetProgramInfoLog(program, sizeof(log), NULL, log);
        fprintf(stderr, "%s\n", log); return 3;
    }
    glUseProgram(program);
    GLuint vao, framebuffer;
    glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    glGenFramebuffers(1, &framebuffer); glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    GLuint output = texture(GL_RGBA32F, W, NULL), footprint = texture(GL_R8, W, NULL);
    float albedo[W * 4];
    const float alphas[] = {0, 1.0f / 255, 2.0f / 255, .25f, .5f, .9f, .999f, 1};
    for (int i = 0; i < W; i++) {
        albedo[i * 4] = .8f; albedo[i * 4 + 1] = .4f; albedo[i * 4 + 2] = .2f;
        albedo[i * 4 + 3] = alphas[i / 8];
    }
    glActiveTexture(GL_TEXTURE0); texture(GL_RGBA32F, W, albedo);
    float normal[] = {.5f, .5f, 1, .5f}, specular[] = {.6f, .04f, 0, 0};
    glActiveTexture(GL_TEXTURE1); texture(GL_RGBA32F, 1, normal);
    glActiveTexture(GL_TEXTURE2); texture(GL_RGBA32F, 1, specular);
    integer(program, "Sampler0", 0); integer(program, "ksg_NormalMap", 1);
    integer(program, "ksg_SpecularMap", 2); integer(program, "ksg_AlphaMode", 2);
    integer(program, "ksg_ParallaxSamples", 4);
    scalar(program, "FogStart", 10); scalar(program, "FogEnd", 50);
    scalar(program, "ksg_AmbientLightEnhancement", 1.5f);
    scalar(program, "ksg_StylizedShadingStrength", .65f);
    glUniform4f(glGetUniformLocation(program, "FogColor"), .6f, .7f, .8f, .9f);
    glViewport(0, 0, W, 1); glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND);
    float reference[W * 4], revealage[W * 4], mask[W * 4];
    int failures = 0, cases = 0;
    for (int fade = 0; fade < 4; fade++) for (int fogCase = 0; fogCase < 3; fogCase++)
    for (int parallax = 0; parallax < 2; parallax++) {
        scalar(program, "testAlpha", fade & 1 ? .5f : 1);
        scalar(program, "testDistance", fogCase * 40.0f);
        scalar(program, "ksg_ParallaxScale", parallax ? .02f : 0);
        glUniform4f(glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, fade & 2 ? .5f : 1);
        attach(output); draw(program, 0, reference); draw(program, 2, revealage);
        attach(footprint); draw(program, 5, mask);
        for (int i = 0; i < W; i++) {
            float alpha = reference[i * 4 + 3];
            int contributes = alpha > 1.0f / 255 && alpha < 1;
            float expected = contributes ? alpha : -1;
            if (fabsf(revealage[i * 4 + 3] - expected) > 1e-6f
                    || mask[i * 4] != (contributes ? 1.0f : 0.0f)) {
                fprintf(stderr, "fade=%d fog=%d parallax=%d pixel=%d alpha=%g reveal=%g mask=%g\n",
                    fade, fogCase, parallax, i, alpha, revealage[i * 4 + 3], mask[i * 4]);
                failures++;
            }
        }
        cases++;
    }
    GLenum error = glGetError();
    printf("production alpha passes: cases=%d pixels=%d failures=%d GL error=0x%x\n",
        cases, cases * W, failures, error);
    CGLSetCurrentContext(NULL); CGLDestroyContext(context); CGLDestroyPixelFormat(format);
    free(fragment); free(fog); free(expanded);
    return failures != 0 || error != GL_NO_ERROR;
}
