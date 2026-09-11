package ai.rever.boss.plugin.dynamic.runledger

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginContext
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

class RunLedgerComponent(
    componentContext: ComponentContext,
    override val panelInfo: PanelInfo,
    private val pluginContext: PluginContext,
) : PanelComponentWithUI, ComponentContext by componentContext {

    private val viewModel = RunLedgerViewModel(
        projectPath = pluginContext.projectPath,
        scope = pluginContext.pluginScope,
    )

    init {
        viewModel.refresh()
    }

    @Composable
    override fun Content() {
        RunLedgerContent(
            viewModel = viewModel,
            onCopy = { text -> pluginContext.clipboardProvider?.setText(text) },
        )
    }
}
