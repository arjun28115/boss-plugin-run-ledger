package ai.rever.boss.plugin.dynamic.runledger

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

class RunLedgerDynamicPlugin : DynamicPlugin {
    override val pluginId = "ai.rever.boss.plugin.dynamic.runledger"
    override val displayName = "Run Ledger"
    override val version = "0.1.0"
    override val description =
        "Records what produced each result: the command, the commit, the uncommitted diff, " +
            "and the output files, rescued out of scratch directories before they are cleaned."
    override val author = "Arjun Singla"
    override val url = "https://github.com/arjun28115/boss-plugin-run-ledger"

    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(RunLedgerInfo) { componentContext, panelInfo ->
            RunLedgerComponent(componentContext, panelInfo, context)
        }

        // The same ledger, asked by the agent rather than read by a person. The panel answers
        // "what produced this" to whoever is looking at it; an agent about to change the code is
        // the caller most likely to need that answer and the least able to read a panel.
        //
        // projectPath is passed as a lambda, not a value: the provider is registered once at load
        // and a call can arrive long after, by which time the open project may have changed.
        context.registerMcpToolProvider(
            RunLedgerMcpToolProvider(providerId = pluginId) { context.projectPath },
        )
    }

    override fun dispose() {
        // Nothing to release. Runs are child processes owned by the launcher's coroutines, which
        // the host cancels with pluginScope, and the ledger itself is a file. The MCP provider is
        // unregistered by the host on disable/unload; calling unregisterMcpToolProvider here would
        // be symmetry rather than necessity.
    }
}
