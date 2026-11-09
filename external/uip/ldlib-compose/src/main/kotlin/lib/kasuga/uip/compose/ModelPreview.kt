package lib.kasuga.uip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.lowdragmc.lowdraglib2.gui.ui.compose.LayoutBlock
import com.lowdragmc.lowdraglib2.gui.ui.compose.UIElementNode
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview
import lib.kasuga.uip.ldlib.ModelPreviewElement
import net.minecraft.resources.ResourceLocation

/** A composition-owned model preview; removal or an asset-key change releases its private GPU state. */
@Composable
fun ModelPreview(
    model: ResourceLocation,
    modelName: String? = null,
    yaw: Float = -.55f,
    pitch: Float = .25f,
    zoom: Float = 1f,
    layout: LayoutBlock? = null,
    id: String? = null,
) {
    key(model, modelName) {
        UIElementNode(
            factory = { ModelPreviewElement(GuiModelPreview(model, modelName)) },
            layout = layout,
            id = id,
            update = { set(Triple(yaw, pitch, zoom)) { preview().setView(it.first, it.second, it.third) } },
        )
    }
}
