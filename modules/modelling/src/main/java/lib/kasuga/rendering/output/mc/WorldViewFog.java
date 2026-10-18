package lib.kasuga.rendering.output.mc;

import lib.kasuga.mixins.modelling.WorldViewFogAccessor;

/** Vanilla's water-biome transition history belongs to a camera, not the whole frame. */
record WorldViewFog(float red, float green, float blue, int target, int previous, long changedAt) {
    static WorldViewFog initial() { return new WorldViewFog(0, 0, 0, -1, -1, -1); }
    static WorldViewFog capture() {
        return new WorldViewFog(WorldViewFogAccessor.kasuga$red(), WorldViewFogAccessor.kasuga$green(),
                WorldViewFogAccessor.kasuga$blue(), WorldViewFogAccessor.kasuga$target(),
                WorldViewFogAccessor.kasuga$previous(), WorldViewFogAccessor.kasuga$changedAt());
    }
    void install() {
        WorldViewFogAccessor.kasuga$red(red); WorldViewFogAccessor.kasuga$green(green);
        WorldViewFogAccessor.kasuga$blue(blue); WorldViewFogAccessor.kasuga$target(target);
        WorldViewFogAccessor.kasuga$previous(previous); WorldViewFogAccessor.kasuga$changedAt(changedAt);
    }
}
