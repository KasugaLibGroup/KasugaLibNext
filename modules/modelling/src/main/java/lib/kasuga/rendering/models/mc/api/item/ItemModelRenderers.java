package lib.kasuga.rendering.models.mc.api.item;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Item-side entry of the model system: maps an {@link Item} to a model of the shared pipeline and
 * answers the client's "draw this with your own renderer" question.
 *
 * <h2>How an item gets the pipeline</h2>
 * <ol>
 *     <li>the content registers the model here ({@link #register});</li>
 *     <li>its item model JSON is {@code {"parent": "minecraft:builtin/entity"}}, which vanilla bakes into a
 *     {@code BuiltInModel} with {@code isCustomRenderer() == true};</li>
 *     <li>on {@code RegisterClientExtensionsEvent} the content registers {@link #clientExtensions()} for
 *     that item, so {@code ItemRenderer} calls our {@link ItemModelRenderer#renderByItem}</li>
 * </ol>
 *
 * <p>Registering the model and the client extension is the content's job (its item id is its own); this
 * class only holds the mapping and the shared renderer. The renderer is created lazily on the render
 * thread, so registering mappings before client setup is safe.
 *
 * <p>This path covers GUI, held and dropped item display. It is independent from the world path: the
 * item instance is bound with {@code VANILLA_RENDERER} scheduling so the world pass never draws it.
 */
public final class ItemModelRenderers {

    /** A model reference: pipeline resource id plus the optional MMD archive entry name. */
    public record Binding(ResourceLocation model, @Nullable String modelName) {
    }

    private static final Map<Item, Binding> BINDINGS = new IdentityHashMap<>();
    private static final IClientItemExtensions EXTENSION = new IClientItemExtensions() {
        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            return renderer();
        }
    };

    @Nullable
    private static volatile ItemModelRenderer renderer;

    private ItemModelRenderers() {
    }

    /** Maps an item to a pipeline model. Call before {@code RegisterClientExtensionsEvent}. */
    public static void register(Item item, ResourceLocation model, @Nullable String modelName) {
        BINDINGS.put(item, new Binding(model, modelName));
    }

    /** The model bound to this item, or {@code null} when it does not use the pipeline. */
    @Nullable
    public static Binding binding(Item item) {
        return BINDINGS.get(item);
    }

    /** Items currently mapped; used by diagnostics and by reload handling. */
    public static Set<Item> items() {
        return Collections.unmodifiableSet(BINDINGS.keySet());
    }

    /**
     * The client extension to hand to {@code RegisterClientExtensionsEvent#registerItem}. One shared
     * instance serves every mapped item; the per-item model comes from {@link #binding}.
     */
    public static IClientItemExtensions clientExtensions() {
        return EXTENSION;
    }

    /** The shared renderer; created on first use (render thread). */
    public static ItemModelRenderer renderer() {
        ItemModelRenderer local = renderer;
        if (local == null) {
            synchronized (ItemModelRenderers.class) {
                local = renderer;
                if (local == null) {
                    local = new ItemModelRenderer();
                    renderer = local;
                }
            }
        }
        return local;
    }
}
