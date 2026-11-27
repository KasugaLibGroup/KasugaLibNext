package lib.kasuga.geometry;

final class GeometrySupport {
    static final float PIXELS_PER_BLOCK = 16.0f;
    static final float MAX_PIXEL_COORDINATE = 65_536.0f;
    static final float MAX_BLOCK_COORDINATE = 4096.0f;

    private GeometrySupport() {
    }

    static float blockCoordinate(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new GeometryException(name + " must be finite: " + value);
        }
        if (Math.abs(value) > MAX_BLOCK_COORDINATE) {
            throw new GeometryException(name + " is outside the supported block range: " + value);
        }
        return normalizeZero(value);
    }

    static void validateBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        requirePositive(minX, maxX, "X");
        requirePositive(minY, maxY, "Y");
        requirePositive(minZ, maxZ, "Z");
    }

    static void requirePositive(float min, float max, String axis) {
        if (!(min < max)) {
            throw new GeometryException(axis + " box extent must be positive: [" + min + ", " + max + ']');
        }
    }

    static float checkedScale(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new GeometryException(name + " must be finite and positive: " + value);
        }
        float narrowed = (float) value;
        if (!Float.isFinite(narrowed) || narrowed <= 0.0f) {
            throw new GeometryException(name + " is not a representable positive float: " + value);
        }
        return narrowed;
    }

    static float pixelToBlock(double value, String name) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_PIXEL_COORDINATE) {
            throw new GeometryException(name + " must be finite and within +/-"
                    + MAX_PIXEL_COORDINATE + " pixels: " + value);
        }
        float narrowed = (float) value;
        if (!Float.isFinite(narrowed) || Math.abs(narrowed) > MAX_PIXEL_COORDINATE) {
            throw new GeometryException(name + " cannot be represented as a supported float pixel: "
                    + value);
        }
        float block = narrowed / PIXELS_PER_BLOCK;
        if (!Float.isFinite(block) || Math.abs(block) > MAX_BLOCK_COORDINATE) {
            throw new GeometryException(name + " is outside the supported block range after conversion: "
                    + block);
        }
        return normalizeZero(block);
    }

    static float finiteResult(double value, String name) {
        float result = (float) value;
        if (!Float.isFinite(result) || Math.abs(result) > MAX_BLOCK_COORDINATE) {
            throw new GeometryException(name + " overflowed or left the supported range: " + value);
        }
        return normalizeZero(result);
    }

    static float normalizeZero(float value) {
        return value == 0.0f ? 0.0f : value;
    }
}
