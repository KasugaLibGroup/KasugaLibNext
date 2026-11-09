package lib.kasuga.uip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI
import com.lowdragmc.lowdraglib2.gui.ui.UI
import com.lowdragmc.lowdraglib2.gui.ui.UIElement
import com.lowdragmc.lowdraglib2.gui.ui.compose.*
import com.lowdragmc.lowdraglib2.gui.ui.elements.BindableValue
import com.lowdragmc.lowdraglib2.gui.ui.layout.px
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.minecraft.commands.Commands
import net.neoforged.neoforge.fluids.FluidStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Development-only: one server-authoritative model and fixed RPC slots, regardless of row count. */
class ComposeDemoMenu : PlayerUIMenuType.PlayerUIHolder {
    data class Row(val id: Int, val title: String)
    data class Model(val rows: List<Row>, val pings: Int = 0, val tickProbe: Int = 0)

    @Volatile var serverModel = Model(listOf(Row(1, "Alpha / 甲"), Row(2, "Bravo / 乙"), Row(3, "Charlie / 丙")))
        private set
    val clientModel = mutableStateOf(Model(emptyList()))
    var tickProbe = 0
    private var nextId = 4
    lateinit var host: ComposeElement
        private set
    lateinit var ui: ModularUI
        private set

    override fun createUI(player: Player): ModularUI {
        val root = UIElement().setId("compose-demo-root").addClass("panel_bg")
            .layout { it.width(360f).height(320f).paddingAll(10f).gapAll(6f) }
        // Construct on BOTH sides. Never bind/register from inside DemoContents.
        host = ComposeElement { DemoContents(clientModel.value) }
        root.addChild(BindableValue<Tag>().bind(
            DataBindingBuilder.tagS2C { encode(serverModel) }.name("model")
                .onRemoteSyncReceived { clientModel.value = decode(it as? CompoundTag) }
                .build(player.level().isClientSide)
        ).setId("model-carrier"))
        // A fixed C2S binding verifies the world host still ticks menu synchronization after rehosting.
        root.addSyncValue(DataBindingBuilder.intValC2S { value ->
            serverModel = serverModel.copy(tickProbe = value.coerceIn(0, 1000))
        }.remoteGetter { tickProbe }.name("tick-probe").build(player.level().isClientSide).syncValue)
        if (!player.level().isClientSide) {
            host.onMessage("add") {
                // Opt-in negative smoke probe. This source set is excluded from release jars.
                if (java.lang.Boolean.getBoolean("kasuga.composeDemo.dropAdd")) return@onMessage
                if (serverModel.rows.size < 12) {
                    val id = nextId++
                    serverModel = serverModel.copy(rows = serverModel.rows + Row(id, "Row $id / 新增"))
                }
            }
            host.onMessage("remove") { tag ->
                if (tag.contains("id", Tag.TAG_INT.toInt()))
                    serverModel = serverModel.copy(rows = serverModel.rows.filterNot { it.id == tag.getInt("id") })
            }
            host.onMessage("clear") { serverModel = serverModel.copy(rows = emptyList()) }
            host.onMessage("close") { player.closeContainer() }
        }
        root.addChild(host)
        // A second fixed host AFTER client-only composed widgets detects accidental RPC renumbering.
        val tail = ComposeElement {
            val server = rememberServerMessenger()
            Button(onClick = { server.send("ping") }, text = "Ping server", id = "compose-ping")
        }
        if (!player.level().isClientSide) tail.onMessage("ping") {
            serverModel = serverModel.copy(pings = serverModel.pings + 1)
        }
        root.addChild(tail)
        ui = ModularUI.of(UI.of(root, StylesheetManager.MODERN), player)
        if (player.level().isClientSide) client = this else servers[player.uuid] = this
        return ui
    }

    companion object {
        val ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ComposeUiProvider.MOD_ID, "demo")
        @JvmField @Volatile var client: ComposeDemoMenu? = null
        @JvmField val servers = ConcurrentHashMap<UUID, ComposeDemoMenu>()
        @JvmField val disposals = AtomicInteger()
        private val FONT = ResourceLocation.withDefaultNamespace("default")

        private fun encode(model: Model) = CompoundTag().apply {
            putInt("pings", model.pings)
            putInt("tickProbe", model.tickProbe)
            put("rows", ListTag().apply {
                model.rows.forEach { row -> add(CompoundTag().apply { putInt("id", row.id); putString("title", row.title) }) }
            })
        }

        private fun decode(tag: CompoundTag?): Model {
            if (tag == null) return Model(emptyList())
            val rows = tag.getList("rows", Tag.TAG_COMPOUND.toInt())
            return Model(rows.map { it as CompoundTag }.map { Row(it.getInt("id"), it.getString("title")) },
                tag.getInt("pings"), tag.getInt("tickProbe"))
        }

        @Composable private fun DemoContents(model: Model) {
            DisposableEffect(Unit) { onDispose { disposals.incrementAndGet() } }
            val server = rememberServerMessenger()
            var rotated by remember { mutableStateOf(false) }
            var showModel by remember { mutableStateOf(true) }
            Box(layout = { width(340.px); gap { all(5.px) } }) {
                Text("LDLib2 Compose / 世界双端 UI", textStyle = { font(FONT) }, id = "compose-title")
                Text("Server rows: ${model.rows.size} / pings: ${model.pings}", id = "compose-count")
                Row(layout = { gap { all(5.px) } }) {
                    Button(onClick = { server.send("add") }, text = "Add row", id = "compose-add")
                    Button(onClick = { server.send("clear") }, text = "Clear", id = "compose-clear")
                    Button(onClick = { rotated = !rotated }, text = "Rotate", id = "compose-rotate")
                    Button(onClick = { showModel = !showModel }, text = "Model", id = "compose-toggle-model")
                    FluidSlot(FluidStack.EMPTY, layout = { width(16.px); height(16.px) })
                }
                Row(layout = { height(210.px); gap { all(6.px) } }) {
                    ScrollView(layout = { width(170.px); height(210.px) }, contentLayout = { gap { all(3.px) } }) {
                        model.rows.forEach { row ->
                            key(row.id) {
                                Row(layout = { gap { all(3.px) } }, classes = "compose-row", id = "row-${row.id}") {
                                    Text(row.title, textStyle = { font(FONT) }, layout = { width(135.px) })
                                    Button(onClick = { server.send("remove") { putInt("id", row.id) } }, text = "x",
                                        layout = { width(18.px) }, id = "remove-${row.id}")
                                }
                            }
                        }
                    }
                    if (showModel) ModelPreview(
                        ResourceLocation.parse("kasuga_lib:models/gltf/maribel.glb"),
                        yaw = if (rotated) .8f else -.55f,
                        layout = { width(160.px); height(210.px) }, id = "compose-model",
                    )
                }
                Text("服务端数据 / 客户端重组", textStyle = { font(FONT) }, id = "compose-footer")
            }
        }
    }
}

@EventBusSubscriber(modid = ComposeUiProvider.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
object ComposeDemoRegistration {
    @JvmStatic @SubscribeEvent fun setup(event: FMLCommonSetupEvent) {
        event.enqueueWork { PlayerUIMenuType.register(ComposeDemoMenu.ID) { ComposeDemoMenu() } }
    }
}

@EventBusSubscriber(modid = ComposeUiProvider.MOD_ID)
object ComposeDemoCommands {
    @JvmStatic @SubscribeEvent fun commands(event: RegisterCommandsEvent) {
        event.dispatcher.register(Commands.literal("kasuga_compose").executes { context ->
            if (PlayerUIMenuType.openUI(context.source.playerOrException, ComposeDemoMenu.ID)) 1 else 0
        })
    }
    @JvmStatic @SubscribeEvent fun stopped(event: ServerStoppedEvent) { ComposeDemoMenu.servers.clear() }
}
