package lib.kasuga.rendering.models.mc.util.box_layer;

import lib.kasuga.rendering.models.mc.util.Direction;
import lombok.Getter;
import org.joml.Vector2f;

public enum UVCorner {

    LEFT_TOP(0), RIGHT_TOP(1), RIGHT_DOWN(2), LEFT_DOWN(3);

    @Getter
    private final int index;

    UVCorner(int index) {
        this.index = index;
    }

    public Vector2f getUVPosition(int offset, Vector2f uvOrg, Vector2f uvSize) {
        int idx = (this.index + offset) % 4;
        UVCorner corner = getCorner(idx);
        switch (corner) {
            case LEFT_TOP -> {
                return new Vector2f(uvOrg.x, uvOrg.y);
            }
            case LEFT_DOWN -> {
                return new Vector2f(uvOrg.x, uvOrg.y + uvSize.y);
            }
            case RIGHT_TOP -> {
                return new Vector2f(uvOrg.x + uvSize.x, uvOrg.y);
            }
            case RIGHT_DOWN -> {
                return new Vector2f(uvOrg.x + uvSize.x, uvOrg.y + uvSize.y);
            }
            default -> throw new IllegalStateException("Unexpected value: " + this);
        }
    }

    public static UVCorner getCorner(int index) {
        return switch (index) {
            case 0 -> LEFT_TOP;
            case 1 -> RIGHT_TOP;
            case 2 -> RIGHT_DOWN;
            case 3 -> LEFT_DOWN;
            default -> throw new IllegalStateException("Unexpected value: " + index);
        };
    }

    public static boolean isLeft(UVCorner corner) {
        return (corner == LEFT_TOP || corner == LEFT_DOWN);
    }

    public static boolean isTop(UVCorner corner) {
        return (corner == LEFT_TOP || corner == RIGHT_TOP);
    }

    public static UVCorner getCorner(FaceInfo.VertexInfo vertexInfo, Direction direction) {
        VertexCorner x = vertexInfo.x();
        VertexCorner y = vertexInfo.y();
        VertexCorner z = vertexInfo.z();
        switch (direction) {
            case DOWN, UP -> {
                if (x == VertexCorner.MAX_X) {
                    if (z == VertexCorner.MAX_Z) {
                        return LEFT_TOP;
                    } else {
                        return LEFT_DOWN;
                    }
                } else {
                    if (z == VertexCorner.MAX_Z) {
                        return RIGHT_TOP;
                    } else {
                        return RIGHT_DOWN;
                    }
                }
            }
            case NORTH -> {
                if (x == VertexCorner.MAX_X) {
                    if (y == VertexCorner.MAX_Y) {
                        return LEFT_TOP;
                    } else {
                        return LEFT_DOWN;
                    }
                } else {
                    if (y == VertexCorner.MAX_Y) {
                        return RIGHT_TOP;
                    } else {
                        return RIGHT_DOWN;
                    }
                }
            }
            case SOUTH -> {
                if (x == VertexCorner.MAX_X) {
                    if (y == VertexCorner.MAX_Y) {
                        return RIGHT_TOP;
                    } else {
                        return RIGHT_DOWN;
                    }
                } else {
                    if (y == VertexCorner.MAX_Y) {
                        return LEFT_TOP;
                    } else {
                        return LEFT_DOWN;
                    }
                }
            }
            case WEST -> {
                if (z == VertexCorner.MIN_Z) {
                    if (y == VertexCorner.MAX_Y) {
                        return LEFT_TOP;
                    } else {
                        return LEFT_DOWN;
                    }
                } else {
                    if (y == VertexCorner.MAX_Y) {
                        return RIGHT_TOP;
                    } else {
                        return RIGHT_DOWN;
                    }
                }
            }
            default -> {
                if (z == VertexCorner.MIN_Z) {
                    if (y == VertexCorner.MAX_Y) {
                        return RIGHT_TOP;
                    } else {
                        return RIGHT_DOWN;
                    }
                } else {
                    if (y == VertexCorner.MAX_Y) {
                        return LEFT_TOP;
                    } else {
                        return LEFT_DOWN;
                    }
                }
            }
        }
    }
}
