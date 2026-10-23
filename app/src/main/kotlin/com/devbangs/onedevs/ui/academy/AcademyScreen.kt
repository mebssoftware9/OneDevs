package com.devbangs.onedevs.ui.academy

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.devbangs.onedevs.BuildConfig
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.academy.CapstoneProgress
import com.devbangs.onedevs.data.academy.ClaimOutcome
import com.devbangs.onedevs.data.academy.Credential
import com.devbangs.onedevs.data.academy.Mastery
import com.devbangs.onedevs.data.academy.Pathway
import com.devbangs.onedevs.data.academy.Progress
import com.devbangs.onedevs.data.academy.Standing
import com.devbangs.onedevs.data.academy.openOn
import com.devbangs.onedevs.data.plans.Plan
import com.devbangs.onedevs.data.plans.Tier
import com.devbangs.onedevs.ui.components.BrandedLoading
import com.devbangs.onedevs.ui.components.FilterPills
import com.devbangs.onedevs.ui.screens.BadgeList
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlinx.coroutines.launch

/**
 * The Academy tab: learning on one side, the record of it on the other.
 *
 * Learn is a pathway, not a library. The top of it answers the only question
 * someone opening it has -- what do I do now -- and the list beneath is the
 * whole pathway in order, so it is clear how far there is to go. The capstone
 * closes it, measured from OneDevs' own records of the learner's app in a
 * closed test, which is the thing the rest of the app exists for.
 *
 * Record is what this account has shown it can do: credentials from the
 * Academy and badges from testing, side by side.
 */
@Composable
fun AcademyScreen(
    onUnit: (String) -> Unit,
    onReview: () -> Unit,
    onPlans: () -> Unit,
    onLaunches: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as OneDevsApplication
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pathway by app.academy.pathway.collectAsState()
    val unreadable by app.academy.unreadable.collectAsState()
    val evidence by app.academy.evidence.collectAsState()
    val plan by app.plans.plan.collectAsState()
    val capstone by app.academy.capstone.collectAsState()
    val credentials by app.academy.credentials.collectAsState()
    val scope = rememberCoroutineScope()

    // Coming back to the tab is when a review may have come due, or a tester
    // may have pushed the capstone along.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        tick++
        scope.launch {
            app.academy.sync()
            app.academy.refresh()
        }
    }
    val now = remember(evidence, tick) { System.currentTimeMillis() }

    Column(modifier = modifier.fillMaxSize()) {
        FilterPills(
            labels = listOf(stringResource(R.string.acad_tab_learn), stringResource(R.string.acad_tab_record)),
            selected = tab,
            onSelect = { tab = it },
            icons = listOf(R.drawable.ic_graduation_cap, R.drawable.ic_certificate),
            fillWidth = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        )
        val p = pathway
        if (p == null) {
            if (unreadable) Unreadable() else Box(Modifier.weight(1f)) { BrandedLoading() }
            return@Column
        }
        val progress = remember(p, evidence, now) { Mastery.progress(p, evidence, now) }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            if (tab == 0) {
                Learn(p, progress, plan, capstone, onUnit, onReview, onPlans, onLaunches)
            } else {
                Record(p, progress, plan, capstone, credentials, onPlans)
            }
        }
    }
}

@Composable
private fun Learn(
    pathway: Pathway,
    progress: Map<String, Progress>,
    plan: Plan?,
    capstone: CapstoneProgress?,
    onUnit: (String) -> Unit,
    onReview: () -> Unit,
    onPlans: () -> Unit,
    onLaunches: () -> Unit,
) {
    val open = { c: com.devbangs.onedevs.data.academy.Competency -> c.openOn(plan) }
    // Reviews have their own button beside this one, so the main action is
    // always the next lesson not yet verified.
    val next = Mastery.next(pathway, progress) { open(it) && progress[it.id]?.standing != Standing.ReviewDue }
    val verified = pathway.competencies.count { progress[it.id]?.verified == true }
    val due = pathway.competencies.count { open(it) && progress[it.id]?.standing == Standing.ReviewDue }
    val started = progress.values.any { it.answeredAny }

    Body(stringResource(R.string.acad_intro), muted = true)
    PathwayHero(
        title = pathway.title,
        summary = pathway.summary,
        verified = verified,
        total = pathway.competencies.size,
        action = next?.let {
            stringResource(if (started) R.string.acad_continue else R.string.acad_start, it.title)
        },
        dueCount = due,
        onAction = { next?.let { onUnit(it.id) } },
        onReview = onReview,
    )
    if (plan?.tier != Tier.Premium && plan?.tier != Tier.Pro) UpgradeCard(onPlans)

    Heading(stringResource(R.string.acad_pathway_heading))
    pathway.competencies.forEachIndexed { i, c ->
        CompetencyRow(i + 1, c, progress[c.id], open(c)) { onUnit(c.id) }
    }
    val nextReview = progress.values.mapNotNull { if (it.standing == Standing.Verified) it.reviewAt else null }.minOrNull()
    if (due == 0 && nextReview != null) {
        Body(stringResource(R.string.acad_next_review, day(nextReview)), muted = true)
    }

    Heading(stringResource(R.string.acad_capstone_heading))
    CapstoneCard(pathway.capstone, capstone, onLaunches)
}

/** One credential the Record can show, and how close the learner is to it. */
private class Earnable(
    val key: String,
    val name: Int,
    val requirement: Int,
    val ready: Boolean,
    val progress: String,
)

@Composable
private fun Record(
    pathway: Pathway,
    progress: Map<String, Progress>,
    plan: Plan?,
    capstone: CapstoneProgress?,
    credentials: List<Credential>?,
    onPlans: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    val said = remember { mutableStateMapOf<String, String>() }
    val claiming = remember { mutableStateMapOf<String, Boolean>() }

    val free = pathway.competencies.filter { it.free }
    val freeDone = free.count { progress[it.id]?.verified == true }
    val allDone = pathway.competencies.count { progress[it.id]?.verified == true }
    val met = capstone?.met == true
    val capstoneLine = stringResource(
        R.string.acad_capstone_testers, capstone?.testers ?: 0, capstone?.neededTesters ?: pathway.capstone.testers,
    ) + " · " + stringResource(
        R.string.acad_capstone_days, capstone?.days ?: 0, capstone?.neededDays ?: pathway.capstone.days,
    )
    val earnable = listOf(
        Earnable(
            Credential.FOUNDATIONS, R.string.acad_cred_foundations, R.string.acad_cred_foundations_req,
            ready = freeDone == free.size,
            progress = stringResource(R.string.acad_progress, freeDone, free.size),
        ),
        Earnable(
            Credential.CAPSTONE, R.string.acad_cred_capstone, R.string.acad_cred_capstone_req,
            ready = met,
            progress = capstoneLine,
        ),
        Earnable(
            Credential.PATHWAY, R.string.acad_cred_pathway, R.string.acad_cred_pathway_req,
            ready = allDone == pathway.competencies.size && met,
            progress = stringResource(R.string.acad_progress, allDone, pathway.competencies.size) + " · " + capstoneLine,
        ),
    )
    val names = pathway.competencies.associate { it.id to it.title } + (Credential.CAPSTONE to pathway.capstone.title)

    Body(stringResource(R.string.acad_record_intro), muted = true)
    Heading(stringResource(R.string.acad_credentials_heading))
    earnable.forEach { e ->
        val issued = credentials?.firstOrNull { it.key == e.key }
        CredentialCard(
            name = stringResource(e.name),
            requirement = stringResource(e.requirement),
            issued = issued,
            progress = e.progress,
            ready = e.ready,
            claiming = claiming[e.key] == true,
            said = said[e.key],
            onClaim = {
                claiming[e.key] = true
                scope.launch {
                    said[e.key] = when (val outcome = app.academy.claim(e.key)) {
                        is ClaimOutcome.Issued -> ""
                        is ClaimOutcome.NotYet -> context.getString(
                            R.string.acad_cred_not_yet,
                            outcome.missing.joinToString(", ") { names[it] ?: it },
                        )
                        ClaimOutcome.Failed -> context.getString(R.string.acad_cred_failed)
                    }
                    claiming[e.key] = false
                }
            },
            onShare = { credential, name ->
                val link = credential.link(BuildConfig.SUPABASE_URL)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, name)
                    putExtra(Intent.EXTRA_TEXT, context.getString(R.string.acad_cred_share_text, name, link))
                }
                context.startActivity(Intent.createChooser(intent, name))
            },
        )
    }
    if (plan?.tier != Tier.Premium && plan?.tier != Tier.Pro && allDone < pathway.competencies.size) {
        UpgradeCard(onPlans)
    }
    BadgeList()
}

@Composable
private fun CredentialCard(
    name: String,
    requirement: String,
    issued: Credential?,
    progress: String,
    ready: Boolean,
    claiming: Boolean,
    said: String?,
    onClaim: () -> Unit,
    onShare: (Credential, String) -> Unit,
) {
    val accent = when {
        issued?.revoked == true -> oneDevsColors.critical
        issued != null -> oneDevsColors.live
        else -> oneDevsColors.feedback
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (issued != null) accent.solid else accent.tint),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_certificate),
                    contentDescription = null,
                    tint = if (issued != null) accent.onSolid else accent.solid,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = when {
                        issued?.revoked == true -> stringResource(R.string.acad_cred_revoked)
                        issued != null -> issued.issuedMillis?.let { stringResource(R.string.acad_cred_issued, day(it)) } ?: ""
                        else -> requirement
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (issued != null) accent.solid else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            issued != null && !issued.revoked ->
                WideButton(stringResource(R.string.acad_cred_share), { onShare(issued, name) }, filled = false)
            issued == null -> {
                Well { Text(progress, style = MaterialTheme.typography.labelMedium) }
                WideButton(
                    text = stringResource(R.string.acad_cred_claim),
                    onClick = onClaim,
                    enabled = ready && !claiming,
                )
                if (!said.isNullOrEmpty()) Body(said, muted = true)
            }
        }
    }
}

/** Shown when the lessons cannot be read at all, which only a broken install does. */
@Composable
internal fun Unreadable(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(horizontal = 20.dp)) {
        Panel { Body(stringResource(R.string.acad_unreadable)) }
    }
}
