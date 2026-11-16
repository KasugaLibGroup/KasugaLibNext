package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.DepthBias;
import lib.kasuga.rendering.models.uml.backend.gpu.PolygonOffsetScope;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import static lib.kasuga.rendering.models.mc.backend.GlFixture.*;

/** Real depth attachments, the unmodified production fragment shader, and the production scope. */
final class PolygonOffsetRegression {
    private static final int SIZE = 16;
    private static final float[] BASE = {1, 0, 0, 1};
    private static final float[] OVERLAY = {0, 0, 1, .375f};
    private static final float[] FOREGROUND = {0, 1, 0, .625f};

    private PolygonOffsetRegression() {}

    static void pixels() throws Exception {
        State original = State.capture();
        try (var gl = new GlFixture()) {
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(0, 0);
            int program = gl.program(resource("/shaders/polygon_offset.vert.glsl"), mainFragment());
            int albedo = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 1, 1, new float[]{0, 0, 0, 1});
            int normal = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 1, 1, new float[]{.5f, .5f, 1, 1});
            int specular = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 1, 1, new float[]{.5f, .04f, 0, 0});
            bindTexture(0, albedo);
            bindTexture(1, normal);
            bindTexture(2, specular);
            GL20.glUseProgram(program);
            integer(program, "Sampler0", 0);
            integer(program, "ksg_NormalMap", 1);
            integer(program, "ksg_SpecularMap", 2);
            integer(program, "ksg_AlphaMode", 2);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, 1);
            scalar(program, "FogStart", 10);
            scalar(program, "FogEnd", 50);
            GL11.glViewport(0, 0, SIZE, SIZE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LESS);
            GL11.glDepthRange(0, 1);
            for (int format : new int[]{GL14.GL_DEPTH_COMPONENT24, GL30.GL_DEPTH_COMPONENT32F}) {
                int color = gl.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, SIZE, SIZE, null);
                int depth = gl.texture(format, GL11.GL_DEPTH_COMPONENT, SIZE, SIZE, null);
                gl.framebuffer(color, depth);
                // Target allocation binds textures; never sample the attached depth image as material data.
                bindTexture(0, albedo);
                bindTexture(1, normal);
                bindTexture(2, specular);
                for (boolean perspective : new boolean[]{false, true}) {
                    float near = .1f, far = 10;
                    float[] projection = perspective
                            ? new float[]{1,0,0,0, 0,1,0,0, 0,0,-(far+near)/(far-near),-1, 0,0,-2*far*near/(far-near),0}
                            : new float[]{.5f,0,0,0, 0,.5f,0,0, 0,0,-2/(far-near),0, 0,0,-(far+near)/(far-near),1};
                    GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "TestProjection"), false, projection);
                    for (float slope : new float[]{0, .08f}) {
                        scalar(program, "TestSlope", slope);
                        String label = (format == GL14.GL_DEPTH_COMPONENT24 ? "fixed24" : "float32")
                                + "/" + (perspective ? "perspective" : "orthographic") + "/slope=" + slope;
                        clear();
                        surface(program, 2, BASE, DepthBias.NONE);
                        float[] baseDepth = read(SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, 1);
                        for (DepthBias bias : new DepthBias[]{DepthBias.NONE, DepthBias.DECAL,
                                new DepthBias(1, 1), slope == 0 ? new DepthBias(0, -4) : new DepthBias(-1, 0)}) {
                            clear();
                            surface(program, 2, OVERLAY, bias);
                            float[] offsetDepth = read(SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, 1);
                            String scenario = label + "/" + bias;
                            for (int y = 5; y < 11; y++) for (int x = 5; x < 11; x++) {
                                int pixel = y * SIZE + x;
                                float delta = offsetDepth[pixel] - baseDepth[pixel];
                                if (!bias.enabled()) expect(delta, 0, 0, scenario + " NONE depth identity");
                                else if (bias.units() <= 0 && !(delta < 0))
                                    throw new AssertionError(scenario + " forward bias must reduce production gl_FragDepth: delta=" + delta);
                                else if (bias.units() > 0 && !(delta > 0))
                                    throw new AssertionError(scenario + " positive bias must increase production gl_FragDepth: delta=" + delta);
                                if (!Float.isFinite(delta) || Math.abs(delta) > .02f)
                                    throw new AssertionError(scenario + " excessive/nonfinite depth offset: " + delta);
                            }
                            for (boolean overlayFirst : new boolean[]{false, true}) {
                                clear();
                                coplanar(program, bias, overlayFirst);
                                boolean overlayWins = bias.enabled() ? bias.units() <= 0 : overlayFirst;
                                verify(overlayWins ? OVERLAY : BASE, overlayWins ? offsetDepth : baseDepth,
                                        scenario + "/overlayFirst=" + overlayFirst);
                            }
                            if (bias.equals(DepthBias.DECAL)) {
                                clear();
                                surface(program, 1.5f, FOREGROUND, DepthBias.NONE);
                                float[] foregroundDepth = read(SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, 1);
                                for (boolean foregroundFirst : new boolean[]{false, true}) {
                                    for (boolean overlayFirst : new boolean[]{false, true}) {
                                        clear();
                                        if (foregroundFirst) surface(program, 1.5f, FOREGROUND, DepthBias.NONE);
                                        coplanar(program, bias, overlayFirst);
                                        if (!foregroundFirst) surface(program, 1.5f, FOREGROUND, DepthBias.NONE);
                                        verify(FOREGROUND, foregroundDepth, scenario + "/foregroundFirst="
                                                + foregroundFirst + "/overlayFirst=" + overlayFirst);
                                    }
                                }
                                clear();
                                surface(program, 2, BASE, DepthBias.NONE);
                                GL11.glEnable(GL11.GL_BLEND);
                                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                                        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                                GL11.glDepthMask(false);
                                surface(program, 2, OVERLAY, bias);
                                verify(new float[]{.625f, 0, .375f, 1}, baseDepth, scenario + "/source-over-no-depth-write");
                                GL11.glDisable(GL11.GL_BLEND);
                                GL11.glDepthMask(true);
                            }
                            noError(scenario);
                            System.out.println("  depth-bias pixels PASS " + scenario);
                        }
                    }
                }
            }
        } finally {
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
            original.restore();
        }
    }

    private static void coplanar(int program, DepthBias bias, boolean overlayFirst) {
        if (overlayFirst) surface(program, 2, OVERLAY, bias);
        surface(program, 2, BASE, DepthBias.NONE);
        if (!overlayFirst) surface(program, 2, OVERLAY, bias);
    }

    private static void surface(int program, float distance, float[] color, DepthBias bias) {
        scalar(program, "TestDistance", distance);
        GL20.glUniform4fv(GL20.glGetUniformLocation(program, "TestColor"), color);
        try (var ignored = PolygonOffsetScope.apply(bias)) {
            draw();
        }
    }

    private static void clear() {
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClearDepth(1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    private static void verify(float[] expectedColor, float[] expectedDepth, String label) {
        float[] color = read(SIZE, SIZE, GL11.GL_RGBA, 4);
        float[] depth = read(SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, 1);
        // An interior tile avoids testing triangle clipping at the viewport's border.
        for (int y = 5; y < 11; y++) for (int x = 5; x < 11; x++) {
            int pixel = y * SIZE + x;
            for (int c = 0; c < 4; c++)
                expect(color[pixel * 4 + c], expectedColor[c], 1e-6, label + " RGBA[" + c + "]");
            expect(depth[pixel], expectedDepth[pixel], 0, label + " stored depth");
        }
    }

    static void state() {
        State original = State.capture();
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                State initial = new State(enabled, 2.25f, -3.5f);
                initial.restore();
                var outer = PolygonOffsetScope.apply(DepthBias.DECAL);
                try {
                    new State(true, -1, -1).expectCurrent("outer scope applied");
                    try (var inner = PolygonOffsetScope.apply(new DepthBias(4.5f, -6.25f))) {
                        new State(true, 4.5f, -6.25f).expectCurrent("inner scope applied");
                    }
                    new State(true, -1, -1).expectCurrent("inner restored outer");
                    RuntimeException sentinel = new RuntimeException("intentional draw failure");
                    try {
                        try (var inner = PolygonOffsetScope.apply(new DepthBias(-7.5f, 8.25f))) {
                            new State(true, -7.5f, 8.25f).expectCurrent("exceptional scope applied");
                            throw sentinel;
                        }
                    } catch (RuntimeException actual) {
                        if (actual != sentinel) throw actual;
                    }
                    new State(true, -1, -1).expectCurrent("exception restored outer");
                    try (var none = PolygonOffsetScope.apply(DepthBias.NONE)) {
                        new State(true, -1, -1).expectCurrent("NONE preserves outer");
                    }
                    new State(true, -1, -1).expectCurrent("NONE close preserves outer");
                } finally {
                    outer.close();
                }
                initial.expectCurrent("outer restored initial enable/factor/units");
                State intervening = new State(!enabled, -11.5f, 12.25f);
                intervening.restore();
                outer.close();
                intervening.expectCurrent("idempotent close preserves intervening state");
                try (var next = PolygonOffsetScope.apply(new DepthBias(0, 5))) {
                    outer.close();
                    new State(true, 0, 5).expectCurrent("stale close preserves new scope");
                }
                intervening.expectCurrent("next scope restored independently");
            }
            // LWJGL cannot dispatch any GL call with the thread's capabilities detached.
            var capabilities = GL.getCapabilities();
            GL.setCapabilities(null);
            try {
                var none = PolygonOffsetScope.apply(DepthBias.NONE);
                none.close();
                none.close();
                PolygonOffsetScope.apply(new DepthBias(-0.0f, 0)).close();
            } finally {
                GL.setCapabilities(capabilities);
            }
            noError("polygon-offset nested/exception/idempotent/NONE restoration");
        } finally {
            original.restore();
        }
    }

    private record State(boolean enabled, float factor, float units) {
        static State capture() {
            return new State(GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL),
                    GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR), GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS));
        }

        void restore() {
            GL11.glPolygonOffset(factor, units);
            if (enabled) GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            else GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        }

        void expectCurrent(String label) {
            State actual = capture();
            if (!equals(actual)) throw new AssertionError(label + ": actual=" + actual + ", expected=" + this);
        }
    }
}
