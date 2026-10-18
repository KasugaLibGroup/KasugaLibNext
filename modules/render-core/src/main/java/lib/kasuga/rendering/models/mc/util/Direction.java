package lib.kasuga.rendering.models.mc.util;

import org.joml.Vector3f;

/**
 * Pure axis directions. The historic package name is retained for caller
 * compatibility; this type has no Minecraft or graphics API dependency.
 */
public enum Direction {

    NORTH, SOUTH, EAST, WEST, UP, DOWN;

    @Override
    public String toString() {
        return switch (this) {
            case UP -> "up";
            case DOWN -> "down";
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST -> "east";
            case WEST -> "west";
        };
    }

    public static Direction fromString(String str) {
        for (Direction dir : Direction.values()) {
            if (dir.toString().equalsIgnoreCase(str)) {
                return dir;
            }
        }
        throw new IllegalArgumentException("No enum constant for string: " + str);
    }

    public Vector3f toVec3f() {
        return switch (this) {
            case UP -> new Vector3f(0, 1, 0);
            case DOWN -> new Vector3f(0, -1, 0);
            case NORTH -> new Vector3f(0, 0, -1);
            case SOUTH -> new Vector3f(0, 0, 1);
            case EAST -> new Vector3f(1, 0, 0);
            case WEST -> new Vector3f(-1, 0, 0);
        };
    }
}
