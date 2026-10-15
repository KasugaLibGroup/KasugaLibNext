package lib.kasuga.rendering.effect.particle;

import org.joml.Vector3f;

import java.util.Arrays;
import java.util.Objects;

/**
 * Reusable CPU-side particle index sorter for conventional alpha blending.
 *
 * <p>The result is ordered from farthest to nearest relative to the camera. Storage grows only
 * when the particle count exceeds the previous high-water mark.</p>
 */
public final class ParticleDepthSorter {
    private int[] order = new int[0];
    private float[] distanceSquared = new float[0];
    private final Vector3f position = new Vector3f();
    private int size;

    public void sortBackToFront(
            ParticleInstanceBuffer instances,
            float cameraX,
            float cameraY,
            float cameraZ
    ) {
        Objects.requireNonNull(instances, "instances");
        size = instances.size();
        ensureCapacity(size);
        for (int index = 0; index < size; index++) {
            instances.position(index, position);
            float x = position.x - cameraX;
            float y = position.y - cameraY;
            float z = position.z - cameraZ;
            order[index] = index;
            distanceSquared[index] = x * x + y * y + z * z;
        }
        if (size > 1) quickSort(0, size - 1);
    }

    public int size() {
        return size;
    }

    public int indexAt(int sortedIndex) {
        if (sortedIndex < 0 || sortedIndex >= size) {
            throw new IndexOutOfBoundsException("index=" + sortedIndex + ", size=" + size);
        }
        return order[sortedIndex];
    }

    public int capacity() {
        return order.length;
    }

    private void ensureCapacity(int required) {
        if (required <= order.length) return;
        int capacity = Math.max(required, Math.max(16, order.length * 2));
        order = Arrays.copyOf(order, capacity);
        distanceSquared = Arrays.copyOf(distanceSquared, capacity);
    }

    private void quickSort(int low, int high) {
        int left = low;
        int right = high;
        int pivot = order[(low + high) >>> 1];
        while (left <= right) {
            while (compare(order[left], pivot) < 0) left++;
            while (compare(order[right], pivot) > 0) right--;
            if (left <= right) {
                int value = order[left];
                order[left] = order[right];
                order[right] = value;
                left++;
                right--;
            }
        }
        if (low < right) quickSort(low, right);
        if (left < high) quickSort(left, high);
    }

    private int compare(int first, int second) {
        int distance = Float.compare(distanceSquared[second], distanceSquared[first]);
        return distance != 0 ? distance : Integer.compare(first, second);
    }
}
