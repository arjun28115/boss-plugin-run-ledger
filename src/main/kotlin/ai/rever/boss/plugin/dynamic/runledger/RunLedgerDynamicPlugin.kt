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
    }

    override fun dispose() {
        // Nothing to release. Runs are child processes owned by the launcher's coroutines, which
        // the host cancels with pluginScope, and the ledger itself is a file.
    }
}
