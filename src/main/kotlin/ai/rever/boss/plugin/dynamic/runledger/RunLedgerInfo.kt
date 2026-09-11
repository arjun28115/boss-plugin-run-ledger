package ai.rever.boss.plugin.dynamic.runledger

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.BookOpen

object RunLedgerInfo : PanelInfo {
    override val id = PanelId("run-ledger", 40)
    override val displayName = "Run Ledger"
    override val icon = FeatherIcons.BookOpen
    override val defaultSlotPosition = left.top.bottom
}
