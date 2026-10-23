package com.devbangs.onedevs.ui.academy

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.academy.Competency
import com.devbangs.onedevs.data.academy.GateResult
import com.devbangs.onedevs.data.academy.Gates
import com.devbangs.onedevs.data.academy.Mastery
import com.devbangs.onedevs.data.academy.Progress
import com.devbangs.onedevs.data.academy.QuestionState
import com.devbangs.onedevs.data.academy.Standing
import com.devbangs.onedevs.data.academy.openOn
import com.devbangs.onedevs.data.plans.LabClaim
import com.devbangs.onedevs.lab.ApkAnalyzer
import com.devbangs.onedevs.ui.components.BrandedLoading
import com.devbangs.onedevs.ui.lab.CheckList
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the last gate check said. */
private sealed interface GateState {
    data object Idle : GateState
    data object Working : GateState
    data class Checked(val packageName: String, val result: GateResult) : GateState
    data class Problem(val message: Int, val arg: Int? = null) : GateState
}

private val APK_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")

/**
 * One competency: what it lets you do, the idea, a worked example, practice,
 * and -- for the competencies about a build -- a gate on the learner's own
 * APK, read by the same code as the Lab.
 *
 * A locked lesson shows its outcome and the first paragraph, so the learner
 * can judge what the plan would open, and nothing after that.
 */
@Composable
fun UnitScreen(
    competencyId: String,
    onPractice: () -> Unit,
    onPlans: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val pathway by app.academy.pathway.collectAsState()
    val evidence by app.academy.evidence.collectAsState()
    val plan by app.plans.plan.collectAsState()
    val scope = rememberCoroutineScope()
    var gate by remember { mutableStateOf<GateState>(GateState.Idle) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val now = remember(evidence, tick) { System.currentTimeMillis() }

    val p = pathway
    val competency = p?.competency(competencyId)
    if (p == null || competency == null) {
        BrandedLoading(modifier)
        return
    }
    val index = p.competencies.indexOf(competency) + 1
    val all = remember(p, evidence, now) { Mastery.progress(p, evidence, now) }
    val progress = all.getValue(competency.id)
    val open = competency.openOn(plan)
    val unfinished = p.competencies.filter { it.id in competency.requires && all[it.id]?.verified != true }

    fun check(uri: Uri) {
        val spec = competency.gate ?: return
        gate = GateState.Working
        scope.launch {
            val analysis = withContext(Dispatchers.IO) { ApkAnalyzer.analyze(context, uri) }.getOrNull()
            if (analysis == null) {
                gate = GateState.Problem(R.string.acad_unit_gate_unreadable)
                return@launch
            }
            val packageName = analysis.report.packageName
            // Only an app this account's Lab keeps can carry a gate, which is
            // what the server checks before it counts one.
            gate = when (val claim = app.plans.claimLabApp(packageName)) {
                is LabClaim.Upgrade -> GateState.Problem(R.string.acad_unit_gate_lab_full, claim.limit)
                LabClaim.Unknown -> GateState.Problem(R.string.acad_unit_gate_unknown)
                LabClaim.Allowed -> {
                    val result = Gates.evaluate(spec.artifact, analysis.report)
                    app.academy.gate(competency.id, packageName, result.passed)
                    GateState.Checked(packageName, result)
                }
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) check(uri)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
    ) {
        Header(index, competency, progress, open)
        if (open) Status(progress, unfinished)

        Heading(stringResource(R.string.acad_unit_model))
        Panel {
            (if (open) competency.model else competency.model.take(1)).forEach { Body(it) }
        }
        if (!open) {
            Body(stringResource(R.string.acad_unit_locked), muted = true)
            UpgradeCard(onPlans)
            return@Column
        }

        Heading(stringResource(R.string.acad_unit_example))
        Panel {
            CodeLines(competency.example.code)
            competency.example.notes.forEach { Body("·  $it", muted = true) }
        }

        WideButton(
            text = stringResource(if (progress.answeredAny) R.string.acad_unit_practice_again else R.string.acad_unit_practice),
            onClick = onPractice,
        )

        competency.gate?.let { spec ->
            Heading(stringResource(R.string.acad_unit_gate))
            Panel {
                Body(spec.task)
                when (val g = gate) {
                    // Passed before, on this phone or another: say on what.
                    GateState.Idle -> evidence.lastOrNull { it.competency == competency.id && it.artifact && it.correct }
                        ?.packageName?.let {
                            Text(
                                text = stringResource(R.string.acad_unit_gate_passed, it),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = oneDevsColors.live.solid,
                            )
                        }
                    GateState.Working -> Body(stringResource(R.string.acad_unit_gate_working), muted = true)
                    is GateState.Problem -> Body(
                        if (g.arg != null) stringResource(g.message, g.arg) else stringResource(g.message),
                        muted = true,
                    )
                    is GateState.Checked -> {
                        val accent = if (g.result.passed) oneDevsColors.live else oneDevsColors.critical
                        Text(
                            text = if (g.result.passed) {
                                stringResource(R.string.acad_unit_gate_passed, g.packageName)
                            } else {
                                stringResource(R.string.acad_unit_gate_failed)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = accent.solid,
                        )
                        CheckList(g.result.checks)
                    }
                }
                WideButton(
                    text = stringResource(R.string.acad_unit_gate_pick),
                    onClick = { pick.launch(APK_TYPES) },
                    filled = progress.gatePassed != true,
                    enabled = gate != GateState.Working,
                )
            }
        }
    }
}

@Composable
private fun Header(index: Int, competency: Competency, progress: Progress, open: Boolean) {
    val accent = progress.standing.accent(open)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$index", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent.solid)
            Spacer(Modifier.width(10.dp))
            Text(competency.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(
            text = stringResource(R.string.acad_minutes, competency.minutes) + " · " + standingText(progress, open),
            style = MaterialTheme.typography.labelMedium,
            color = accent.solid,
        )
        Well {
            Text(
                stringResource(R.string.acad_unit_outcome),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Body(competency.outcome)
        }
    }
}

/** Where the learner stands, in sentences rather than a score. */
@Composable
private fun Status(progress: Progress, unfinished: List<Competency>) {
    val lines = buildList {
        if (progress.answeredAny) {
            add(
                stringResource(
                    R.string.acad_unit_secure,
                    progress.secureFormative + progress.secureSummative,
                    progress.formative + progress.summative,
                ),
            )
        }
        progress.questions.filter { it.state == QuestionState.TooSoon }.mapNotNull { it.countsFrom }.minOrNull()?.let {
            add(stringResource(R.string.acad_unit_counts_from, clock(it)))
        }
        val verifiedAt = progress.verifiedAt
        val reviewAt = progress.reviewAt
        when {
            progress.standing == Standing.ReviewDue -> add(stringResource(R.string.acad_unit_review_due))
            verifiedAt != null && reviewAt != null ->
                add(stringResource(R.string.acad_unit_verified_since, day(verifiedAt), day(reviewAt)))
        }
        if (unfinished.isNotEmpty() && !progress.verified) {
            add(stringResource(R.string.acad_unit_requires, unfinished.joinToString(", ") { it.title }))
        }
    }
    if (lines.isEmpty()) return
    Panel { lines.forEach { Body(it, muted = true) } }
}
