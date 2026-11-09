package lib.kasuga.uip.compose

import androidx.compose.runtime.Composable
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI
import com.lowdragmc.lowdraglib2.gui.ui.compose.composeUI
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager
import lib.kasuga.rendering.models.mc.backend.ui.UIBackend
import lib.kasuga.uip.ldlib.LdElement
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import org.joml.Matrix4fc

/** Client-local composition. Server-backed UIs use the normal menu path followed by LdMenuElement. */
@OnlyIn(Dist.CLIENT)
object ComposeWorldUi {
    @JvmStatic
    fun attach(
        backend: UIBackend,
        width: Int,
        height: Int,
        localToWorld: Matrix4fc,
        stylesheet: ResourceLocation = StylesheetManager.MODERN,
        content: @Composable () -> Unit,
    ): LdElement {
        RenderSystem.assertOnRenderThread()
        require(width > 0 && height > 0) { "World UI dimensions must be positive" }
        check(Minecraft.getInstance().level != null) { "World UI requires an active world" }
        val ui = ModularUI.of(composeUI(stylesheet, content = content))
        try {
            return LdElement.attach(backend, ui, width, height, localToWorld)
        } catch (failure: RuntimeException) {
            ui.onRemoved()
            ui.setScreen(null)
            throw failure
        }
    }
}
