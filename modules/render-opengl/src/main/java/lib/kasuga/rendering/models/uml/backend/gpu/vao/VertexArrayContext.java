package lib.kasuga.rendering.models.uml.backend.gpu.vao;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;
import org.lwjgl.opengl.GL30;

public class VertexArrayContext implements GpuContext<VertexArrayObject> {

    private int previousVao = -1;

    @Override
    public void enter(VertexArrayObject object) {
        if (!isEmpty() && previousVao != object.getVaoId()) {
            throw new IllegalStateException("A different VAO is already bound in the context.");
        }
        previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glBindVertexArray(object.getVaoId());
    }

    @Override
    public void exit(VertexArrayObject object) {
        if (!isEmpty()) {
            GL30.glBindVertexArray(previousVao);
            previousVao = -1;
        }
    }

    @Override
    public boolean isEmpty() {
        return previousVao == -1;
    }
}
