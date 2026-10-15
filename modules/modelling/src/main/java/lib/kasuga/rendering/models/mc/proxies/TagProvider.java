package lib.kasuga.rendering.models.mc.proxies;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.Set;
import java.util.stream.Stream;

public class TagProvider {

    protected final Set<String> tags;

    public TagProvider(Stream<TagKey<?>> tags) {
        this.tags = tags.map(TagKey::location).map(ResourceLocation::toString).collect(java.util.stream.Collectors.toSet());
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }
}
