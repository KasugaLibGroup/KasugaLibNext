package test.kasuga.modelling.nebula;

import java.util.Arrays;
import java.util.List;

/** CPU packing shared by the texture rows and shader counts. */
final class NebulaLightningData {
    record Counts(int lights, int segments, float peak) {}
    static Counts pack(List<NebulaSimulation.Bolt> bolts, float time, float[] lights, float[] segments) {
        Arrays.fill(lights, 0);
        Arrays.fill(segments, 0);
        int lightCount = 0, segmentCount = 0;
        float peak = 0;
        for (var bolt : bolts) {
            float energy = bolt.strength(time);
            peak = Math.max(peak, energy);
            if (energy <= .0005f) continue;
            if (lightCount < lights.length / 4) {
                int k = lightCount++ * 4;
                lights[k] = bolt.center().x; lights[k + 1] = bolt.center().y; lights[k + 2] = bolt.center().z;
                lights[k + 3] = energy;
            }
            for (var segment : bolt.segments()) {
                if (segmentCount == segments.length / 8) break;
                int k = segmentCount++ * 8;
                segments[k] = segment.start().x; segments[k + 1] = segment.start().y; segments[k + 2] = segment.start().z;
                segments[k + 3] = energy;
                segments[k + 4] = segment.end().x; segments[k + 5] = segment.end().y; segments[k + 6] = segment.end().z;
                segments[k + 7] = segment.width();
            }
        }
        return new Counts(lightCount, segmentCount, peak);
    }
}
