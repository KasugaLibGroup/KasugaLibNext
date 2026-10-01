package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static lib.kasuga.rendering.models.mc.backend.GlFixture.*;

final class RenderRegressionSuite {
    private final Path output;

    RenderRegressionSuite(Path output) { this.output = output; }

    void depthPrecision() {
        try (var gl = new GlFixture()) {
            int size = 128;
            int[] depth = new int[2], color = new int[2], fbo = new int[2];
            for (int i = 0; i < 2; i++) {
                depth[i] = gl.texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, size, size, null);
                color[i] = gl.texture(GL30.GL_R32F, GL11.GL_RED, size, size, null);
                fbo[i] = gl.framebuffer(color[i], depth[i]);
            }
            int shader = gl.program("""
                    #version 150
                    uniform float base;
                    void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
                        gl_Position=vec4(p*2-1,base+p.x*.00003+p.y*.00007,1);}
                    """, """
                    #version 150
                    uniform sampler2D previous;out float color;
                    void main(){if(gl_FragCoord.z<=texelFetch(previous,ivec2(gl_FragCoord.xy),0).r)discard;
                        gl_FragDepth=gl_FragCoord.z;color=gl_FragCoord.z;}
                    """);
            int[] queries = new int[4];
            GL15.glGenQueries(queries);
            try {
                GL20.glUseProgram(shader);
                integer(shader, "previous", 0);
                GL11.glViewport(0, 0, size, size);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LESS);
                for (int useColor = 0; useColor < 2; useColor++) {
                    for (int test = 0; test < 8; test++) {
                        int previous = 0, current = 1;
                        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[previous]);
                        GL11.glClearDepth(0);
                        GL11.glClearColor(0, 0, 0, 0);
                        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                        scalar(shader, "base", .95f + test * .006f);
                        for (int layer = 0; layer < 4; layer++) {
                            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[current]);
                            GL11.glClearDepth(1);
                            GL11.glClearColor(1, 0, 0, 0);
                            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                            bindTexture(0, useColor == 0 ? depth[previous] : color[previous]);
                            GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, queries[layer]);
                            draw();
                            GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
                            if (layer == 0) {
                                float[] stored = read(size, size, GL11.GL_DEPTH_COMPONENT, 1);
                                float[] fragment = read(size, size, GL11.GL_RED, 1);
                                for (int pixel = 0; pixel < stored.length; pixel++) {
                                    expect(stored[pixel], fragment[pixel], 0, "explicit depth/fragment identity");
                                }
                            }
                            int swap = previous; previous = current; current = swap;
                        }
                        expect(GL15.glGetQueryObjecti(queries[0], GL15.GL_QUERY_RESULT), size * size, 0, "first layer coverage");
                        for (int layer = 1; layer < 4; layer++) {
                            expect(GL15.glGetQueryObjecti(queries[layer], GL15.GL_QUERY_RESULT), 0, 0, "single surface repeated");
                        }
                    }
                }
            } finally {
                GL15.glDeleteQueries(queries);
            }
        }
    }

    void depthHandoff() throws Exception {
        try (var gl = new GlFixture()) {
            int resolve = gl.program(resource("/assets/kasuga_lib/shaders/core/ksglib_peel_resolve.fsh"));
            int cloud = gl.program("#version 150\nuniform float Depth;out vec4 fragColor;"
                    + "void main(){gl_FragDepth=Depth;fragColor=vec4(1,0,0,1);}");
            for (int format : new int[]{GL14.GL_DEPTH_COMPONENT24, GL30.GL_DEPTH_COMPONENT32F}) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                int layer = gl.texture(GL30.GL_RGBA16F, GL11.GL_RGBA, 5, 1,
                        new float[]{0,.2f,0,.5f, 0,0,0,0, 0,.2f,0,.5f, 0,.2f,0,.5f, 0,0,0,0});
                int first = gl.texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, 5, 1, new float[]{.4f,1,.4f,.6f,1});
                int nearest = gl.texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, 5, 1, null);
                int mainDepth = gl.texture(format, GL11.GL_DEPTH_COMPONENT, 5, 1, new float[]{.8f,.8f,.3f,.8f,1});
                int mainColor = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 5, 1, null);
                int firstFbo = gl.framebuffer(0, first), nearestFbo = gl.framebuffer(0, nearest);
                int mainFbo = gl.framebuffer(mainColor, mainDepth);
                GL11.glViewport(0, 0, 5, 1);
                GL11.glClearColor(.2f, .2f, .2f, 1);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, firstFbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, nearestFbo);
                GL30.glBlitFramebuffer(0,0,5,1,0,0,5,1,GL11.GL_DEPTH_BUFFER_BIT,GL11.GL_NEAREST);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, firstFbo);
                GL11.glDepthMask(true);
                GL11.glClearDepth(1);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(1,0,1,1);
                GL20.glUseProgram(cloud);
                scalar(cloud, "Depth", .5f);
                draw();
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL20.glUseProgram(resolve);
                integer(resolve, "Layer", 0);
                integer(resolve, "NearestDepth", 6);
                integer(resolve, "WriteDepth", 1);
                bindTexture(0, layer);
                bindTexture(6, nearest);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                draw();
                GL11.glDisable(GL11.GL_BLEND);
                float[] expected = {.4f,.5f,.3f,.6f,1};
                float[] depths = read(5, 1, GL11.GL_DEPTH_COMPONENT, 1);
                for (int pixel = 0; pixel < 5; pixel++) expect(depths[pixel], expected[pixel], 1e-5, "depth handoff " + format);
                float[] colors = read(5, 1, GL11.GL_RGBA, 4);
                expect(colors[0], .1, 1e-5, "premultiplied resolve");
                expect(colors[8], .2, 1e-5, "foreground preserved");
                GL20.glUseProgram(cloud);
                scalar(cloud, "Depth", .7f);
                draw();
                depths = read(5,1,GL11.GL_DEPTH_COMPONENT,1);
                for (int pixel=0;pixel<5;pixel++) expect(depths[pixel], pixel==4?.7f:expected[pixel], 1e-5, "rear cloud");
                scalar(cloud, "Depth", .2f);
                draw();
                for (float depth : read(5,1,GL11.GL_DEPTH_COMPONENT,1)) expect(depth,.2,1e-5,"front cloud");
            }
        }
    }

    void conditionalPeel() throws Exception {
        int[][] scenes = {{0,0,0,0,0,0,0,0}, {2,2,2,2,2,2,2,2}, {3,3,3,3,3,3,3,3},
                {4,4,4,4,4,4,4,4}, {5,5,5,5,5,5,5,5}, {0,2,4,5,32,1,0,4},
                {40,32,31,16,5,4,3,1}, {0,0,0,0,0,0,0,0}, {1,0,0,1,0,0,0,0}, {0,0,0,0,0,0,0,0}};
        for (int colorFormat : new int[]{GL30.GL_RGBA32F, GL30.GL_RGBA16F}) {
            for (int batch : new int[]{1,3,4,16}) {
                try (var scene = new PeelScene(8, 1, colorFormat)) {
                    for (int frame = 0; frame < scenes.length; frame++) {
                        scene.render(scenes[frame], batch);
                        float[] actual = read(8, 1, GL11.GL_RGBA, 4);
                        float[] expected = reference(scenes[frame]);
                        try {
                            for (int i = 0; i < actual.length; i++) {
                                // FP16 blending rounds after every layer. With alpha=1/8,
                                // a one-ULP rounding error is amplified by at most 8;
                                // 2^-11 * 8 plus the source-color quantization is < .0045.
                                // FP32 independently checks the exact layer count/order.
                                expect(actual[i], expected[i], colorFormat == GL30.GL_RGBA16F ? .0045 : .00001,
                                        "production loop batch=" + batch + " frame=" + frame + " channel=" + i);
                            }
                        } catch (AssertionError failure) {
                            image(output.resolve("peel-actual.png"),8,1,actual);
                            image(output.resolve("peel-expected.png"),8,1,expected);
                            throw failure;
                        }
                    }
                }
            }
        }
    }

    private static float[] reference(int[] counts) {
        float[] expected = new float[counts.length * 4];
        for (int pixel = 0; pixel < counts.length; pixel++) {
            for (int layer = 0; layer < Math.min(counts[pixel],32); layer++) {
                int offset = pixel * 4;
                float contribution = (1 - expected[offset + 3]) * .125f;
                expected[offset] += contribution * (layer + 1) / 64f;
                expected[offset + 1] += contribution * (pixel + 1) / 10f;
                expected[offset + 2] += contribution * .4f;
                expected[offset + 3] += contribution;
            }
        }
        return expected;
    }

    void productionPrograms() throws Exception {
        try (var gl = new GlFixture()) {
            for (String name : List.of("ksglib_main", "ksglib_global_batch")) {
                String vertex = vertexSource("/assets/kasuga_lib/shaders/core/" + name + ".vsh");
                Map<String,Integer> attributes = new LinkedHashMap<>();
                var names = RenderState.UML_VERTEX_FORMAT.getElementAttributeNames();
                for (int i=0;i<names.size();i++) attributes.put(names.get(i),i);
                int program = lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram.link(vertex,mainFragment(),attributes,new String[0]);
                try {
                    int binding = GL20.glGetAttribLocation(program,"BoneBindingType");
                    if (binding != names.indexOf("BoneBindingType")) throw new AssertionError("Production binding location mismatch");
                } finally {GL20.glDeleteProgram(program);}
            }
            gl.program(resource("/assets/kasuga_lib/shaders/core/ksglib_oit_composite.vsh"),
                    resource("/assets/kasuga_lib/shaders/core/ksglib_oit_composite.fsh"));
        }
    }

    void alphaPasses() throws Exception {
        try (var gl = new GlFixture()) {
            int width = 64;
            int shader = gl.program("""
                    #version 150
                    uniform float testAlpha,testDistance;
                    out float vertexDistance;out vec4 vertexColor,lightMapColor,overlayColor;
                    out vec2 texCoord0,textureUV;flat out vec4 textureBounds;flat out float alphaCutoff;
                    out vec3 viewPos,viewNormal;out mat3 TBN;out vec3 viewLight0_Direction,viewLight1_Direction;
                    void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);
                        texCoord0=textureUV=p;textureBounds=vec4(0,0,1,1);vertexDistance=testDistance;
                        vertexColor=vec4(1,1,1,testAlpha);lightMapColor=vec4(1);overlayColor=vec4(0,0,0,1);
                        alphaCutoff=.5;viewPos=vec3(.5,0,-2);viewNormal=vec3(0,0,1);TBN=mat3(1);
                        viewLight0_Direction=vec3(0,0,1);viewLight1_Direction=vec3(0,1,0);}
                    """, mainFragment());
            int outputTexture = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, width,1,null);
            int footprint = gl.texture(GL30.GL_R8, GL11.GL_RED,width,1,null);
            int fbo = gl.framebuffer(outputTexture,0);
            float[] albedo = new float[width*4];
            float[] alphas = {0,1f/255,2f/255,.25f,.5f,.9f,.999f,1};
            for (int i=0;i<width;i++) {
                albedo[i*4]=.8f;albedo[i*4+1]=.4f;albedo[i*4+2]=.2f;albedo[i*4+3]=alphas[i/8];
            }
            bindTexture(0,gl.texture(GL30.GL_RGBA32F,GL11.GL_RGBA,width,1,albedo));
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            gl.texture(GL30.GL_RGBA32F,GL11.GL_RGBA,1,1,new float[]{.5f,.5f,1,.5f});
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            gl.texture(GL30.GL_RGBA32F,GL11.GL_RGBA,1,1,new float[]{.6f,.04f,0,0});
            GL20.glUseProgram(shader);
            integer(shader,"Sampler0",0);integer(shader,"ksg_NormalMap",1);integer(shader,"ksg_SpecularMap",2);
            integer(shader,"ksg_AlphaMode",2);integer(shader,"ksg_ParallaxSamples",4);
            scalar(shader,"FogStart",10);scalar(shader,"FogEnd",50);
            scalar(shader,"ksg_AmbientLightEnhancement",1.5f);scalar(shader,"ksg_StylizedShadingStrength",.65f);
            GL20.glUniform4f(GL20.glGetUniformLocation(shader,"FogColor"),.6f,.7f,.8f,.9f);
            GL11.glViewport(0,0,width,1);
            for (int fade=0;fade<4;fade++) for (int fog=0;fog<3;fog++) for (int parallax=0;parallax<2;parallax++) {
                scalar(shader,"testAlpha",(fade&1)==0?1:.5f);scalar(shader,"testDistance",fog*40);
                scalar(shader,"ksg_ParallaxScale",parallax==0?0:.02f);
                GL20.glUniform4f(GL20.glGetUniformLocation(shader,"ColorModulator"),1,1,1,(fade&2)==0?1:.5f);
                float[] reference = alphaDraw(shader,0,fbo,outputTexture,width);
                float[] reveal = alphaDraw(shader,2,fbo,outputTexture,width);
                float[] mask = alphaDraw(shader,5,fbo,footprint,width);
                for (int i=0;i<width;i++) {
                    float alpha=reference[i*4+3];
                    boolean contributes=alpha>1f/255 && alpha<1;
                    expect(reveal[i*4+3],contributes?alpha:-1,1e-6,"production revealage alpha");
                    expect(mask[i*4],contributes?1:0,0,"production R8 footprint");
                }
            }
        }
    }

    private float[] alphaDraw(int shader,int mode,int fbo,int texture,int width) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
        integer(shader,"ksg_OitMode",mode);
        GL11.glClearColor(-1,-1,-1,-1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        draw();
        return read(width,1,GL11.GL_RGBA,4);
    }

    void targetLifecycle() {
        try (var gl = new GlFixture();var target = new PeelTarget()) {
            var depth=OitDepthFormat.matching(32,GL11.GL_FLOAT,0);
            target.ensure(8,8,true,depth);
            int initial=target.framebuffer;
            target.ensure(8,8,true,depth);
            expect(target.framebuffer,initial,0,"stable target reused");
            target.ensure(16,8,true,depth);
            expect(target.width,16,0,"resize width");
            target.clear(1);
            int finalFbo=target.framebuffer;
            target.close();
            if(GL30.glIsFramebuffer(finalFbo)) throw new AssertionError("target leaked after close");
            target.close();
            try {
                GlslProgram.link(GlslProgram.FULLSCREEN_VERTEX,"#version 150\ninvalid shader");
                throw new AssertionError("invalid shader compiled");
            } catch (IllegalStateException expected) {
                if(!expected.getMessage().contains("compile failed")) throw expected;
            }
        }
    }

    Map<String,Object> measure(StandaloneRenderHarness.Options options,long window) throws Exception {
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("scope","shared-production-peel-loop with deterministic synthetic geometry");
        report.put("warmup",options.warmup());
        report.put("cpuPrepare", "not measured: synthetic geometry has no model preparation");
        report.put("pixelReadback", "after measurement only");
        List<Long> cpu=new ArrayList<>(), gpu=new ArrayList<>();
        boolean timer=GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
        report.put("gpuTimerSupported",timer);
        long submitted=0,draws=0,busy=0,queries=0;
        int measured=0;
        int[] counts={2,4,6,8,2,4,6,8};
        try(var scene=new PeelScene(options.width(),options.height(),GL30.GL_RGBA16F)) {
            int[] timers=timer?new int[8]:new int[0];
            boolean[] pending=new boolean[timers.length];
            if(timer) GL15.glGenQueries(timers);
            try {
                GLFW.glfwSwapInterval(options.mode().equals("preview")?1:0);
                for(int frame=0;!GLFW.glfwWindowShouldClose(window)
                        && (options.frames()==0 || frame<options.frames()+options.warmup());frame++) {
                    boolean measuring=frame>=options.warmup();
                    int slot=timer?frame%timers.length:-1;
                    if(timer && pending[slot] && GL15.glGetQueryObjecti(timers[slot],GL15.GL_QUERY_RESULT_AVAILABLE)!=0) {
                        gpu.add(GL33.glGetQueryObjectui64(timers[slot],GL15.GL_QUERY_RESULT));pending[slot]=false;
                    }
                    boolean timed=measuring && timer && !pending[slot];
                    if(timed) GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timers[slot]);
                    long start=System.nanoTime();
                    var result=scene.render(counts,4);
                    long elapsed=System.nanoTime()-start;
                    if(timed) {GL15.glEndQuery(GL33.GL_TIME_ELAPSED);pending[slot]=true;}
                    if(measuring) {
                        cpu.add(elapsed);measured++;submitted+=result.submittedLayers();
                        draws+=scene.geometryDraws;busy+=result.ringBusy()?1:0;queries+=result.issuedQueries();
                    }
                    if(options.mode().equals("preview")) {
                        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,scene.accumulation.framebuffer);
                        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,0);
                        int[] framebufferWidth = {0}, framebufferHeight = {0};
                        GLFW.glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
                        GL30.glBlitFramebuffer(0,0,options.width(),options.height(),0,0,framebufferWidth[0],framebufferHeight[0],
                                GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
                        GLFW.glfwSwapBuffers(window);
                        if(GLFW.glfwGetKey(window,GLFW.GLFW_KEY_ESCAPE)==GLFW.GLFW_PRESS) GLFW.glfwSetWindowShouldClose(window,true);
                    }
                    GLFW.glfwPollEvents();
                }
                // Completion/readback is outside every measured CPU submission interval.
                GL11.glFinish();
                for(int i=0;i<timers.length;i++) if(pending[i]) gpu.add(GL33.glGetQueryObjectui64(timers[i],GL15.GL_QUERY_RESULT));
                scene.accumulation.bind();
                image(output.resolve("scene.png"),options.width(),options.height(),read(options.width(),options.height(),GL11.GL_RGBA,4));
            } finally {
                if(timer) GL15.glDeleteQueries(timers);
            }
        }
        report.put("frames",measured);report.put("cpuSubmit",statistics(cpu));
        report.put("gpu",timer?statistics(gpu):"unsupported");
        report.put("submittedLayers",submitted);report.put("geometryDrawsSubmitted",draws);report.put("occlusionQueriesIssued",queries);report.put("queryRingBusyFrames",busy);
        report.put("readbackIncludedInTiming",false);
        return report;
    }

    private static Map<String,Object> statistics(List<Long> samples) {
        Map<String,Object> result=new LinkedHashMap<>();
        long[] sorted=samples.stream().mapToLong(Long::longValue).sorted().toArray();
        result.put("samples",sorted.length);
        for(var percentile:Map.of("p50",.5,"p95",.95,"p99",.99).entrySet()) {
            result.put(percentile.getKey()+"Ms",sorted.length==0?null:sorted[(int)Math.ceil(sorted.length*percentile.getValue())-1]/1e6);
        }
        return result;
    }

    private static final class PeelScene implements AutoCloseable {
        final GlFixture gl=new GlFixture();
        PeelTarget previous=new PeelTarget(),current=new PeelTarget();
        final PeelTarget accumulation=new PeelTarget(),nearest=new PeelTarget();
        final PeelLayerLoop loop=new PeelLayerLoop();
        final int width,height,peel,resolve;
        int geometryDraws;

        PeelScene(int width,int height,int colorFormat) throws Exception {
            this.width=width;this.height=height;
            try {
                var depth=OitDepthFormat.matching(32,GL11.GL_FLOAT,0);
                previous.ensure(width,height,colorFormat,depth);current.ensure(width,height,colorFormat,depth);
                accumulation.ensure(width,height,colorFormat,null);nearest.ensure(width,height,false,depth);
                peel=gl.program("""
                        #version 150
                        uniform float Depth;uniform vec4 Color;uniform sampler2D Previous;out vec4 F;
                        void main(){gl_FragDepth=Depth;
                            if(Depth<=texelFetch(Previous,ivec2(gl_FragCoord.xy),0).r)discard;
                            F=vec4(Color.rgb*Color.a,Color.a);}
                        """);
                resolve=gl.program(resource("/assets/kasuga_lib/shaders/core/ksglib_peel_resolve.fsh"));
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        PeelLayerLoop.Result render(int[] counts,int batch) {
            geometryDraws=0;
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            previous.clear(0);accumulation.clear(1);
            return loop.render(32,batch,new PeelLayerLoop.Layers() {
                @Override public void clear() {current.clear(1);}
                @Override public void draw(int layer) {
                    GL11.glDisable(GL11.GL_BLEND);GL11.glEnable(GL11.GL_DEPTH_TEST);
                    GL11.glDepthFunc(GL11.GL_LESS);GL11.glDepthMask(true);
                    GL20.glUseProgram(peel);integer(peel,"Previous",0);bindTexture(0,previous.depth);
                    GL11.glEnable(GL11.GL_SCISSOR_TEST);
                    try {
                        for(int pixel=0;pixel<counts.length;pixel++) {
                            int start=pixel*width/counts.length,end=(pixel+1)*width/counts.length;
                            GL11.glScissor(start,0,end-start,height);
                            for(int fragment=counts[pixel]-1;fragment>=0;fragment--) {
                                scalar(peel,"Depth",(fragment+1)/64f);
                                GL20.glUniform4f(GL20.glGetUniformLocation(peel,"Color"),(fragment+1)/64f,(pixel+1)/10f,.4f,.125f);
                                GlFixture.draw();geometryDraws++;
                            }
                        }
                    } finally {GL11.glDisable(GL11.GL_SCISSOR_TEST);}
                }
                @Override public void pinNearestDepth() {nearest.copyDepth(current.framebuffer);}
                @Override public void accumulate() {
                    accumulation.bind();GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);
                    GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_ONE_MINUS_DST_ALPHA,GL11.GL_ONE);
                    GL20.glUseProgram(resolve);integer(resolve,"Layer",0);integer(resolve,"WriteDepth",0);
                    bindTexture(0,current.color);GlFixture.draw();
                }
                @Override public void swap() {PeelTarget swap=previous;previous=current;current=swap;}
            });
        }

        @Override public void close() {
            loop.close();previous.close();current.close();accumulation.close();nearest.close();gl.close();
        }
    }
}
