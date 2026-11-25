package lib.kasuga.registration;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Getter
@RequiredArgsConstructor
public class CreativeTabContentRegistration {

    private final ResourceKey<CreativeModeTab> tabKey;
    private final CreativeModeTab tab;
    private final BuildCreativeModeTabContentsEvent event;
}
