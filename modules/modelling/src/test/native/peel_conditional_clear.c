#define GL_SILENCE_DEPRECATION
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl3.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

/* macOS GPU regression for LayeredTransparency's conditional clears.
 * clang -Wall -Wextra -Werror peel_conditional_clear.c -framework OpenGL -o /tmp/peel-conditional-clear
 * /tmp/peel-conditional-clear
 *
 * Reuses the same targets across populated/empty frames, queues all 32 layers
 * without CPU query waits, and compares both variants against source-over.
 * Every layer clears, peels actual geometry against the previous depth, and
 * resolves. Only the last layer's geometry in each batch is queried.
 */
#define WIDTH 8
#define LIMIT 32
static int failures;

typedef struct {
    GLuint color[2], depth[2], framebuffer[2];
    GLuint accumulation, accumulationFramebuffer, queries[LIMIT];
} Targets;

static GLuint compile(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint ok;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        fprintf(stderr, "%s\n", log);
        exit(2);
    }
    return shader;
}

static GLuint program(const char *fragment) {
    const char *vertex = "#version 150\n"
        "void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);"
        "gl_Position=vec4(p*2-1,0,1);}";
    GLuint vs = compile(GL_VERTEX_SHADER, vertex), fs = compile(GL_FRAGMENT_SHADER, fragment);
    GLuint result = glCreateProgram();
    glAttachShader(result, vs); glAttachShader(result, fs); glLinkProgram(result);
    glDeleteShader(vs); glDeleteShader(fs);
    GLint ok;
    glGetProgramiv(result, GL_LINK_STATUS, &ok);
    if (!ok) exit(3);
    return result;
}

static GLuint texture(GLenum internal, GLenum format) {
    GLuint result;
    glGenTextures(1, &result); glBindTexture(GL_TEXTURE_2D, result);
    glTexImage2D(GL_TEXTURE_2D, 0, internal, WIDTH, 1, 0, format, GL_FLOAT, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    return result;
}

static GLuint framebuffer(GLuint color, GLuint depth) {
    GLuint result;
    glGenFramebuffers(1, &result); glBindFramebuffer(GL_FRAMEBUFFER, result);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) exit(4);
    return result;
}

static void createTargets(Targets *targets) {
    for (int i = 0; i < 2; i++) {
        targets->color[i] = texture(GL_RGBA32F, GL_RGBA);
        targets->depth[i] = texture(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT);
        targets->framebuffer[i] = framebuffer(targets->color[i], targets->depth[i]);
    }
    targets->accumulation = texture(GL_RGBA32F, GL_RGBA);
    targets->accumulationFramebuffer = framebuffer(targets->accumulation, 0);
    glGenQueries(LIMIT, targets->queries);
}

static void closeTargets(Targets *targets) {
    glDeleteQueries(LIMIT, targets->queries);
    glDeleteTextures(2, targets->color); glDeleteTextures(2, targets->depth);
    glDeleteFramebuffers(2, targets->framebuffer);
    glDeleteTextures(1, &targets->accumulation);
    glDeleteFramebuffers(1, &targets->accumulationFramebuffer);
}

static void clearLayer(void) {
    glClearColor(0, 0, 0, 0); glClearDepth(1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
}

static void render(Targets *targets, GLuint peel, GLuint resolve, int batch,
                   int conditionalClear, const int counts[WIDTH], float output[WIDTH * 4]) {
    GLint pixelUniform = glGetUniformLocation(peel, "Pixel");
    GLint depthUniform = glGetUniformLocation(peel, "Depth");
    GLint colorUniform = glGetUniformLocation(peel, "Color");
    glViewport(0, 0, WIDTH, 1);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_BLEND); glDepthMask(GL_TRUE);
    glBindFramebuffer(GL_FRAMEBUFFER, targets->accumulationFramebuffer);
    glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
    int previous = 0, current = 1;
    glBindFramebuffer(GL_FRAMEBUFFER, targets->framebuffer[previous]);
    // Color intentionally retains earlier frames, as in the production path.
    glClearDepth(0); glClear(GL_DEPTH_BUFFER_BIT);

    for (int layer = 0; layer < LIMIT; layer++) {
        int group = layer / batch;
        int probe = (layer + 1) % batch == 0 || layer + 1 == LIMIT;
        glBindFramebuffer(GL_FRAMEBUFFER, targets->framebuffer[current]);
        glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS); glDepthMask(GL_TRUE);
        if (!conditionalClear) clearLayer();
        if (group > 0) glBeginConditionalRender(targets->queries[group - 1], GL_QUERY_WAIT);
        if (conditionalClear) clearLayer();
        if (probe) glBeginQuery(GL_SAMPLES_PASSED, targets->queries[group]);
        glUseProgram(peel);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, targets->depth[previous]);
        // Submit all surfaces in reverse order. Depth testing must choose the
        // nearest surviving surface independently of submission order.
        for (int pixel = 0; pixel < WIDTH; pixel++) {
            glUniform1i(pixelUniform, pixel);
            for (int fragment = counts[pixel] - 1; fragment >= 0; fragment--) {
                glUniform1f(depthUniform, (fragment + 1) / 64.0f);
                glUniform4f(colorUniform, (fragment + 1) / 64.0f, (pixel + 1) / 10.0f, .4f, .125f);
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }
        }
        if (probe) glEndQuery(GL_SAMPLES_PASSED);
        glBindFramebuffer(GL_FRAMEBUFFER, targets->accumulationFramebuffer);
        glDisable(GL_DEPTH_TEST); glDepthMask(GL_FALSE); glEnable(GL_BLEND);
        glBlendFunc(GL_ONE_MINUS_DST_ALPHA, GL_ONE);
        glUseProgram(resolve);
        glBindTexture(GL_TEXTURE_2D, targets->color[current]);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        if (group > 0) glEndConditionalRender();
        int swap = previous; previous = current; current = swap;
    }
    // Read only after the complete frame has been submitted, never in the
    // hot loop. Queries issued under a false predicate must still return zero.
    int groups = (LIMIT + batch - 1) / batch;
    for (int group = 0; group < groups; group++) {
        int lastLayer = (group + 1) * batch - 1;
        if (lastLayer >= LIMIT) lastLayer = LIMIT - 1;
        int visible = 0;
        for (int pixel = 0; pixel < WIDTH; pixel++) visible |= counts[pixel] > lastLayer;
        GLuint samples;
        glGetQueryObjectuiv(targets->queries[group], GL_QUERY_RESULT, &samples);
        if ((samples != 0) != visible) {
            fprintf(stderr, "query batch=%d group=%d conditionalClear=%d samples=%u expectedVisible=%d\n",
                batch, group, conditionalClear, samples, visible);
            failures++;
        }
    }
    glReadPixels(0, 0, WIDTH, 1, GL_RGBA, GL_FLOAT, output);
}

static void expect(float actual, double expected, int batch, int frame, int component, const char *label) {
    if (!isfinite(actual) || fabs(actual - expected) > .00001) {
        fprintf(stderr, "%s batch=%d frame=%d component=%d actual=%g expected=%g\n",
            label, batch, frame, component, actual, expected);
        failures++;
    }
}

int main(void) {
    CGLPixelFormatAttribute attributes[] = {kCGLPFAOpenGLProfile,
        (CGLPixelFormatAttribute) kCGLOGLPVersion_3_2_Core, 0};
    CGLPixelFormatObj format;
    CGLContextObj context;
    GLint count;
    if (CGLChoosePixelFormat(attributes, &format, &count)
            || CGLCreateContext(format, NULL, &context)) return 2;
    CGLSetCurrentContext(context);
    GLuint peel = program("#version 150\n"
        "uniform int Pixel;uniform float Depth;uniform vec4 Color;uniform sampler2D Previous;out vec4 F;"
        "void main(){gl_FragDepth=Depth;if(int(gl_FragCoord.x)!=Pixel)discard;"
        "if(Depth<=texelFetch(Previous,ivec2(gl_FragCoord.xy),0).r)discard;"
        "F=vec4(Color.rgb*Color.a,Color.a);}");
    GLuint resolve = program("#version 150\n"
        "uniform sampler2D Layer;out vec4 F;"
        "void main(){F=texelFetch(Layer,ivec2(gl_FragCoord.xy),0);}");
    GLuint vao;
    glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    const int batches[] = {1, 3, 4, 16};
    const int scenes[][WIDTH] = {
        {0}, {2, 2, 2, 2, 2, 2, 2, 2}, {4, 4, 4, 4, 4, 4, 4, 4},
        {5, 5, 5, 5, 5, 5, 5, 5}, {0, 2, 4, 5, 32, 1, 0, 4},
        {40, 32, 31, 16, 5, 4, 3, 1}, {0}, {1, 0, 0, 1, 0, 0, 0, 0}, {0}
    };
    for (int b = 0; b < 4; b++) {
        Targets baseline, optimized;
        createTargets(&baseline); createTargets(&optimized);
        for (unsigned frame = 0; frame < sizeof(scenes) / sizeof(scenes[0]); frame++) {
            float old[WIDTH * 4], modern[WIDTH * 4];
            render(&baseline, peel, resolve, batches[b], 0, scenes[frame], old);
            render(&optimized, peel, resolve, batches[b], 1, scenes[frame], modern);
            for (int pixel = 0; pixel < WIDTH; pixel++) {
                double expected[4] = {0};
                int layers = scenes[frame][pixel] < LIMIT ? scenes[frame][pixel] : LIMIT;
                for (int layer = 0; layer < layers; layer++) {
                    double contribution = (1 - expected[3]) * .125;
                    expected[0] += contribution * (layer + 1) / 64.0;
                    expected[1] += contribution * (pixel + 1) / 10.0;
                    expected[2] += contribution * .4;
                    expected[3] += contribution;
                }
                for (int channel = 0; channel < 4; channel++) {
                    int index = pixel * 4 + channel;
                    expect(modern[index], old[index], batches[b], frame, index, "old/new");
                    expect(modern[index], expected[channel], batches[b], frame, index, "source-over");
                }
            }
        }
        closeTargets(&baseline); closeTargets(&optimized);
        printf("batch=%d: 9 reused-target frames, layer cap and conditional query results checked\n", batches[b]);
    }
    GLenum error = glGetError();
    printf("GL error=0x%x failures=%d\n", error, failures);
    glDeleteProgram(peel); glDeleteProgram(resolve); glDeleteVertexArrays(1, &vao);
    CGLSetCurrentContext(NULL); CGLDestroyContext(context); CGLDestroyPixelFormat(format);
    return failures != 0 || error != GL_NO_ERROR;
}
