#define GL_SILENCE_DEPRECATION
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl3.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

/* Runs the production resolve shader against float and fixed-point main depth.
 * clang peel_depth_handoff.c -framework OpenGL -o /tmp/peel-handoff
 * /tmp/peel-handoff path/to/ksglib_peel_resolve.fsh
 * No Minecraft process, CPU query ring, or screenshot timing is involved.
 */
#define W 5
static GLuint compile(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint ok;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096]; glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        fprintf(stderr, "%s\n", log); exit(2);
    }
    return shader;
}
static GLuint program(const char *fragment) {
    const char *vertex = "#version 150\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}";
    GLuint vs = compile(GL_VERTEX_SHADER, vertex), fs = compile(GL_FRAGMENT_SHADER, fragment);
    GLuint result = glCreateProgram();
    glAttachShader(result, vs); glAttachShader(result, fs); glLinkProgram(result);
    glDeleteShader(vs); glDeleteShader(fs);
    GLint ok; glGetProgramiv(result, GL_LINK_STATUS, &ok);
    if (!ok) exit(3);
    return result;
}
static GLuint texture(GLint format, GLenum pixels, const float *data) {
    GLuint id; glGenTextures(1, &id); glBindTexture(GL_TEXTURE_2D, id);
    glTexImage2D(GL_TEXTURE_2D, 0, format, W, 1, 0, pixels, GL_FLOAT, data);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    return id;
}
static GLuint framebuffer(GLuint color, GLuint depth) {
    GLuint id; glGenFramebuffers(1, &id); glBindFramebuffer(GL_FRAMEBUFFER, id);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
    glDrawBuffer(color ? GL_COLOR_ATTACHMENT0 : GL_NONE);
    glReadBuffer(color ? GL_COLOR_ATTACHMENT0 : GL_NONE);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) exit(4);
    return id;
}
static int failures;
static void expect(float actual, float expected, const char *label, int pixel) {
    if (fabsf(actual - expected) > 0.00001f) {
        fprintf(stderr, "%s pixel=%d actual=%g expected=%g\n", label, pixel, actual, expected);
        failures++;
    }
}
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    FILE *file = fopen(argv[1], "rb"); if (!file) return 2;
    fseek(file, 0, SEEK_END); long size = ftell(file); rewind(file);
    char *source = calloc((size_t)size + 1, 1);
    if (fread(source, 1, (size_t)size, file) != (size_t)size) return 2;
    fclose(file);
    CGLPixelFormatAttribute attrs[] = {kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core, 0};
    CGLPixelFormatObj pf; CGLContextObj ctx; GLint count;
    if (CGLChoosePixelFormat(attrs, &pf, &count) || CGLCreateContext(pf, NULL, &ctx)) return 2;
    CGLSetCurrentContext(ctx);
    GLuint resolve = program(source); free(source);
    GLuint cloud = program("#version 150\nuniform float Depth;out vec4 fragColor;void main(){gl_FragDepth=Depth;fragColor=vec4(1,0,0,1);}");
    GLuint vao; glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    glViewport(0, 0, W, 1); glEnable(GL_CULL_FACE);
    const GLenum formats[] = {GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32F};
    for (int format = 0; format < 2; format++) {
        glActiveTexture(GL_TEXTURE0);
        float layer[] = {0,.2f,0,.5f, 0,0,0,0, 0,.2f,0,.5f, 0,.2f,0,.5f, 0,0,0,0};
        float firstDepth[] = {.4f,1,.4f,.6f,1};
        float opaqueDepth[] = {.8f,.8f,.3f,.8f,1};
        GLuint layerTex = texture(GL_RGBA16F, GL_RGBA, layer);
        GLuint firstTex = texture(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT, firstDepth);
        GLuint nearestTex = texture(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT, NULL);
        GLuint mainDepth = texture(formats[format], GL_DEPTH_COMPONENT, opaqueDepth);
        GLuint mainColor = texture(GL_RGBA32F, GL_RGBA, NULL);
        GLuint firstFbo = framebuffer(0, firstTex), nearestFbo = framebuffer(0, nearestTex);
        GLuint mainFbo = framebuffer(mainColor, mainDepth);
        glClearColor(.2f,.2f,.2f,1); glClear(GL_COLOR_BUFFER_BIT);

        // Pin the first layer, then reuse/clear the ping-pong source.
        glBindFramebuffer(GL_READ_FRAMEBUFFER, firstFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, nearestFbo);
        glBlitFramebuffer(0,0,W,1,0,0,W,1,GL_DEPTH_BUFFER_BIT,GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, firstFbo);
        glDepthMask(GL_TRUE); glClearDepth(1); glClear(GL_DEPTH_BUFFER_BIT);

        // Mimic color accumulation's state and restore it for ordinary water.
        glDisable(GL_DEPTH_TEST); glDepthMask(GL_FALSE);
        glBindFramebuffer(GL_FRAMEBUFFER, mainFbo);
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(GL_TRUE);
        glEnable(GL_SCISSOR_TEST); glScissor(1,0,1,1);
        glUseProgram(cloud); glUniform1f(glGetUniformLocation(cloud,"Depth"),.5f);
        glDrawArrays(GL_TRIANGLES,0,3); glDisable(GL_SCISSOR_TEST);

        glUseProgram(resolve);
        glUniform1i(glGetUniformLocation(resolve,"Layer"),0);
        glUniform1i(glGetUniformLocation(resolve,"NearestDepth"),6);
        glUniform1i(glGetUniformLocation(resolve,"WriteDepth"),1);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,layerTex);
        glActiveTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_2D,nearestTex);
        glEnable(GL_BLEND); glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glDrawArrays(GL_TRIANGLES,0,3); glDisable(GL_BLEND);

        float depths[W], colors[W*4];
        glReadPixels(0,0,W,1,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
        const float expected[] = {.4f,.5f,.3f,.6f,1};
        for (int p=0;p<W;p++) expect(depths[p],expected[p],"depth handoff",p);
        glReadPixels(0,0,W,1,GL_RGBA,GL_FLOAT,colors);
        expect(colors[0],.1f,"premultiplied resolve",0);
        expect(colors[2*4],.2f,"opaque foreground preserved",2);

        // Far cloud may draw only in the uncovered sky pixel, not over water/model.
        glUseProgram(cloud); glUniform1f(glGetUniformLocation(cloud,"Depth"),.7f);
        glDrawArrays(GL_TRIANGLES,0,3);
        glReadPixels(0,0,W,1,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
        for (int p=0;p<W;p++) expect(depths[p],p==4?.7f:expected[p],"rear cloud",p);
        // A genuinely nearer cloud must still pass.
        glUniform1f(glGetUniformLocation(cloud,"Depth"),.2f); glDrawArrays(GL_TRIANGLES,0,3);
        glReadPixels(0,0,W,1,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
        for (int p=0;p<W;p++) expect(depths[p],.2f,"front cloud",p);
        printf("main depth=%s: handoff/empty coverage/foreground/rear cloud/front cloud checked\n",format?"float32":"fixed24");
        GLuint textures[] = {layerTex,firstTex,nearestTex,mainDepth,mainColor}; glDeleteTextures(5,textures);
        GLuint fbos[] = {firstFbo,nearestFbo,mainFbo}; glDeleteFramebuffers(3,fbos);
    }
    GLenum error=glGetError(); printf("GL error=0x%x failures=%d\n",error,failures);
    CGLSetCurrentContext(NULL); CGLDestroyContext(ctx); CGLDestroyPixelFormat(pf);
    return failures || error != GL_NO_ERROR;
}
