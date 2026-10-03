package lib.kasuga.rendering.models.uml.backend.gpu.program;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

public class GlslProgramContext implements GpuContext<GlslProgram> {

    private int previousProgram = -1;

    @Override
    public void enter(GlslProgram object) {
        if (!isEmpty() && previousProgram != object.getProgramId()) {
            throw new IllegalStateException("A different program is already in use in the context.");
        }
        previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(object.getProgramId());
    }

    @Override
    public void exit(GlslProgram object) {
        if (!isEmpty()) {
            GL20.glUseProgram(previousProgram);
            previousProgram = -1;
        } else {
            throw new IllegalStateException("No program was entered in the context.");
        }
    }

    @Override
    public boolean isEmpty() {
        return previousProgram != -1;
    }
}
