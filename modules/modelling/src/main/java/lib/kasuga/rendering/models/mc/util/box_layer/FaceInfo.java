package lib.kasuga.rendering.models.mc.util.box_layer;

import lib.kasuga.rendering.models.mc.util.Direction;
import lombok.Getter;

@Getter
public class FaceInfo {
    
    private final VertexInfo[] corners;
    private final Direction direction;
    
    public static final FaceInfo UP = new FaceInfo(
            Direction.UP, 
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MAX_Y, VertexCorner.MIN_Z),
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MAX_Y, VertexCorner.MAX_Z),
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MAX_Y, VertexCorner.MAX_Z),
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MAX_Y, VertexCorner.MIN_Z)
    );
    
    public static final FaceInfo DOWN = new FaceInfo(
            Direction.DOWN,
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MIN_Y, VertexCorner.MAX_Z),
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MIN_Y, VertexCorner.MIN_Z),
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MIN_Y, VertexCorner.MIN_Z),
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MIN_Y, VertexCorner.MAX_Z)
    );

    public static final FaceInfo NORTH = new FaceInfo(
            Direction.NORTH, 
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MAX_Y, VertexCorner.MIN_Z),
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MIN_Y, VertexCorner.MIN_Z),
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MIN_Y, VertexCorner.MIN_Z),
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MAX_Y, VertexCorner.MIN_Z)
    );

    public static final FaceInfo SOUTH = new FaceInfo(
            Direction.SOUTH,
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MAX_Y, VertexCorner.MAX_Z),
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MIN_Y, VertexCorner.MAX_Z),
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MIN_Y, VertexCorner.MAX_Z),
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MAX_Y, VertexCorner.MAX_Z)
    );


    public static final FaceInfo WEST = new FaceInfo(
            Direction.WEST,
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MAX_Y, VertexCorner.MIN_Z),
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MIN_Y, VertexCorner.MIN_Z),
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MIN_Y, VertexCorner.MAX_Z),
            new VertexInfo(VertexCorner.MIN_X, VertexCorner.MAX_Y, VertexCorner.MAX_Z)
    );

    public static final FaceInfo EAST = new FaceInfo(
            Direction.EAST,
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MAX_Y, VertexCorner.MAX_Z),
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MIN_Y, VertexCorner.MAX_Z),
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MIN_Y, VertexCorner.MIN_Z),
            new VertexInfo(VertexCorner.MAX_X, VertexCorner.MAX_Y, VertexCorner.MIN_Z)
    );

    public static FaceInfo fromFacing(Direction facing) {
        return switch (facing) {
            case UP -> UP;
            case DOWN -> DOWN;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }

    public FaceInfo(Direction direction, 
                    VertexInfo corner1, 
                    VertexInfo corner2, 
                    VertexInfo corner3, 
                    VertexInfo corner4) {
        this.direction = direction;
        corners = new VertexInfo[] {
                corner1, 
                corner2, 
                corner3, 
                corner4
        };
    }

    public UVCorner getCorner(VertexInfo info) {
        for (int i = 0; i < corners.length; i++) {
            if (corners[i].equals(info)) {
                for (UVCorner corner : UVCorner.values()) {
                    if (corner.getIndex() == i) {
                        return corner;
                    }
                }
            }
        }
        throw new IllegalArgumentException("VertexInfo not found in corners");
    }

    public VertexInfo getCorner(int index) {
        return corners[index];
    }
    
    public static record VertexInfo(VertexCorner x, VertexCorner y, VertexCorner z) {
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof VertexInfo(VertexCorner x1, VertexCorner y1, VertexCorner z1))) return false;
            return this.x == x1 && this.y == y1 && this.z == z1;
        }
    }
}
