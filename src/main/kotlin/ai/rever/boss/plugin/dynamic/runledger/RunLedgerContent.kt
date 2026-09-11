package ai.rever.boss.plugin.dynamic.runledger

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

@Composable
fun RunLedgerContent(
    viewModel: RunLedgerViewModel,
    onCopy: (String) -> Unit,
) {
    BossTheme {
        val state by viewModel.state.collectAsState()
        val draft by viewModel.draft.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BossThemeColors.BackgroundColor)
                .padding(8.dp),
        ) {
            if (!viewModel.hasProject) {
                EmptyNotice(
                    title = "No project open",
                    detail = "A run ledger belongs to a project. Open one and its history " +
                        "appears here.",
                )
                return@Column
            }

            Header(
                ledgerLocation = viewModel.ledgerLocation(),
                onRefresh = viewModel::refresh,
                onCopyLocation = { viewModel.ledgerLocation()?.let(onCopy) },
            )

            state.error?.let { Banner("Could not read the ledger: $it", BossThemeColors.ErrorColor) }
            if (state.unreadableLines > 0) {
                Banner(
                    "${state.unreadableLines} ledger line(s) could not be read and were skipped.",
                    BossThemeColors.ErrorColor,
                )
            }

            NewRunForm(
                draft = draft,
                onChange = viewModel::updateDraft,
                onLaunch = { viewModel.launchDraft() },
            )

            Spacer(Modifier.height(8.dp))

            if (state.runs.isEmpty()) {
                EmptyNotice(
                    title = "No runs recorded yet",
                    detail = "Launch a command above. The ledger stores what it ran, the commit " +
                        "it ran against, and the files it produced.",
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(state.runs, key = { it.id }) { run ->
                    RunRow(
                        run = run,
                        expanded = run.id == state.selectedId,
                        onClick = { viewModel.select(run.id) },
                    )
                    if (run.id == state.selectedId) {
                        RunDetail(
                            run = run,
                            runDirectory = viewModel.runDirectory(run.id),
                            isRunning = run.id in state.runningIds,
                            onStop = { viewModel.stop(run.id) },
                            onReuse = { viewModel.loadIntoDraft(run) },
                            onCopy = onCopy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(ledgerLocation: String?, onRefresh: () -> Unit, onCopyLocation: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Run Ledger",
            color = BossThemeColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        if (ledgerLocation != null) {
            Text(
                ".boss/run-ledger",
                color = BossThemeColors.TextMuted,
                style = Mono,
                modifier = Modifier.clickable { onCopyLocation() },
            )
        }
        Spacer(Modifier.weight(1f))
        SmallButton("Refresh", onClick = onRefresh)
    }
}

@Composable
private fun NewRunForm(
    draft: RunDraft,
    onChange: ((RunDraft) -> RunDraft) -> Unit,
    onLaunch: () -> Boolean,
) {
    var expanded by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
            .background(BossThemeColors.SurfaceColor, RoundedCornerShape(4.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "New run",
                color = BossThemeColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            Spacer(Modifier.weight(1f))
            if (expanded) {
                SmallButton("Launch", enabled = draft.canLaunch) { onLaunch() }
            }
        }

        if (!expanded) return@Column

        Spacer(Modifier.height(6.dp))
        Field("command", draft.command, "python train.py --lr 3e-4") { value ->
            onChange { it.copy(command = value) }
        }
        Field("label", draft.label, "defaults to the command") { value ->
            onChange { it.copy(label = value) }
        }
        Field("outputs", draft.artifacts, "outputs/*.json, figures/*.png") { value ->
            onChange { it.copy(artifacts = value) }
        }
        Field("record env", draft.captureEnv, "CUDA_VISIBLE_DEVICES, SEED") { value ->
            onChange { it.copy(captureEnv = value) }
        }
        Text(
            "Only the environment variables you name are stored, so a shared ledger cannot " +
                "collect credentials by accident.",
            color = BossThemeColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = BossThemeColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.width(72.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 4.dp),
        ) {
            if (value.isEmpty()) {
                Text(placeholder, color = BossThemeColors.TextMuted, style = Mono)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Mono.copy(color = BossThemeColors.TextPrimary),
                cursorBrush = SolidColor(BossThemeColors.TextPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RunRow(run: RunRecord, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (expanded) BossThemeColors.SurfaceColor else Color.Transparent,
                RoundedCornerShape(3.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(statusColor(run.status), CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            run.label,
            color = BossThemeColors.TextPrimary,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        run.git?.let { git ->
            Text(
                git.sha.take(7) + if (git.dirty) "+" else "",
                color = if (git.dirty) BossThemeColors.ErrorColor else BossThemeColors.TextMuted,
                style = Mono,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(durationLabel(run), color = BossThemeColors.TextMuted, style = Mono)
    }
}

@Composable
private fun RunDetail(
    run: RunRecord,
    runDirectory: String?,
    isRunning: Boolean,
    onStop: () -> Unit,
    onReuse: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor, RoundedCornerShape(3.dp))
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
    ) {
        DetailLine("command", run.command, onCopy)
        DetailLine("started", run.startedAt, onCopy)
        run.exitCode?.let { DetailLine("exit", it.toString(), onCopy) }

        run.git?.let { git ->
            DetailLine("commit", git.sha + (git.branch?.let { " on $it" } ?: ""), onCopy)
            if (git.dirty) {
                // The point of the whole plugin: say plainly that the commit is not the whole
                // story, and name the file that completes it.
                DetailLine(
                    "tree",
                    "${git.dirtyFileCount} uncommitted file(s). " +
                        (git.patchFile?.let { "Diff stored as $it" } ?: "Diff could not be stored"),
                    onCopy,
                )
            }
        }

        run.env.forEach { (key, value) -> DetailLine(key, value, onCopy) }

        if (run.artifacts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            run.artifacts.forEach { artifact ->
                DetailLine(
                    artifactOutcomeLabel(artifact),
                    artifact.storedPath ?: artifact.detail ?: artifact.declared,
                    onCopy,
                )
            }
        }

        runDirectory?.let { DetailLine("stored in", it, onCopy) }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Load into new run", onClick = onReuse)
            if (isRunning) SmallButton("Stop", onClick = onStop)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, onCopy: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            color = BossThemeColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.width(76.dp),
        )
        Text(
            value,
            color = BossThemeColors.TextSecondary,
            style = Mono,
            modifier = Modifier.weight(1f).clickable { onCopy(value) },
        )
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun EmptyNotice(title: String, detail: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(title, color = BossThemeColors.TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(detail, color = BossThemeColors.TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
        fontSize = 10.sp,
        modifier = Modifier
            .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(3.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

private fun statusColor(status: RunStatus): Color = when (status) {
    RunStatus.SUCCEEDED -> BossThemeColors.SuccessColor
    RunStatus.FAILED -> BossThemeColors.ErrorColor
    RunStatus.RUNNING -> BossThemeColors.TextSecondary
    RunStatus.UNKNOWN -> BossThemeColors.TextMuted
}

private fun artifactOutcomeLabel(artifact: RescuedArtifact): String = when (artifact.outcome) {
    RescueOutcome.RESCUED -> "kept"
    RescueOutcome.ABSENT -> "missing"
    RescueOutcome.OVER_BUDGET -> "too large"
    RescueOutcome.FAILED -> "copy failed"
}

/** Human duration, or the reason there is not one yet. */
internal fun durationLabel(run: RunRecord): String {
    if (run.status == RunStatus.RUNNING) return "running"
    val ms = run.durationMs() ?: return ""
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
