package lib.kasuga.rendering.models.mc.util.box_layer;

public enum VertexCorner {

    MIN_X, MIN_Y, MIN_Z,
    MAX_X, MAX_Y, MAX_Z;

    public boolean isMin() {
        return this == MIN_X || this == MIN_Y || this == MIN_Z;
    }

    public boolean isMax() {
        return this == MAX_X || this == MAX_Y || this == MAX_Z;
    }

    public boolean isX() {
        return this == MIN_X || this == MAX_X;
    }

    public boolean isY() {
        return this == MIN_Y || this == MAX_Y;
    }

    public boolean isZ() {
        return this == MIN_Z || this == MAX_Z;
    }
}
