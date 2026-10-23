package com.devbangs.onedevs.ui.academy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.academy.Asked
import com.devbangs.onedevs.data.academy.Mastery
import com.devbangs.onedevs.data.academy.Pathway
import com.devbangs.onedevs.data.academy.QuestionKind
import com.devbangs.onedevs.data.academy.openOn
import com.devbangs.onedevs.ui.components.BrandedLoading
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * Where a session is: the questions (which grow by one when a question is
 * missed and asked again), which one is showing, and what has been done to it.
 */
private data class Session(
    val items: List<Asked>,
    val index: Int = 0,
    val pick: Int? = null,
    val hints: Int = 0,
    val checked: Boolean = false,
    val firstTry: Int = 0,
    val counted: Int = 0,
    val usedHints: Boolean = false,
    val missed: Boolean = false,
) {
    val current: Asked? get() = items.getOrNull(index)
    val done: Boolean get() = index >= items.size
}

/**
 * A session survives rotation as plain text: each question by competency,
 * id, variant and choice order. The pathway turns that back into questions.
 */
private fun sessionSaver(pathway: Pathway) = Saver<MutableState<Session>, String>(
    save = { state ->
        val s = state.value
        val items = s.items.joinToString(";") { a ->
            listOf(a.competency, a.question.id, a.variantIndex, a.order.joinToString(","), a.retry).joinToString("|")
        }
        listOf(
            items, s.index, s.pick ?: -1, s.hints, s.checked, s.firstTry, s.counted, s.usedHints, s.missed,
        ).joinToString("\n")
    },
    restore = { text ->
        val f = text.split("\n")
        val items = f[0].split(";").filter { it.isNotEmpty() }.mapNotNull { row ->
            val (competency, id, variant, order, retry) = row.split("|")
            val question = pathway.competency(competency)?.questions?.firstOrNull { it.id == id } ?: return@mapNotNull null
            Asked(
                competency = competency,
                question = question,
                variantIndex = variant.toInt(),
                order = order.split(",").filter { it.isNotEmpty() }.map { it.toInt() },
                retry = retry.toBoolean(),
            )
        }
        mutableStateOf(
            Session(
                items = items,
                index = f[1].toInt(),
                pick = f[2].toInt().takeIf { it >= 0 },
                hints = f[3].toInt(),
                checked = f[4].toBoolean(),
                firstTry = f[5].toInt(),
                counted = f[6].toInt(),
                usedHints = f[7].toBoolean(),
                missed = f[8].toBoolean(),
            ),
        )
    },
)

/**
 * Practice for one competency, or -- with no competency -- a review across
 * every competency that is owed one.
 *
 * Questions come one at a time. A prediction is committed before anything is
 * revealed; hints come a step at a time and are recorded; and a miss is asked
 * again at the end in another variant, which helps the learning but cannot
 * count until ten minutes have passed. The rules are said at the end rather
 * than discovered.
 */
@Composable
fun PracticeScreen(competencyId: String?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as OneDevsApplication
    val pathway by app.academy.pathway.collectAsState()
    val p = pathway
    if (p == null) {
        BrandedLoading(modifier)
        return
    }
    val evidence = app.academy.evidence.collectAsState().value
    val plan by app.plans.plan.collectAsState()
    val scope = rememberCoroutineScope()
    val random = remember { Random(System.nanoTime()) }

    var session by rememberSaveable(competencyId, saver = sessionSaver(p)) {
        val now = System.currentTimeMillis()
        val items = if (competencyId != null) {
            p.competency(competencyId)?.takeIf { it.openOn(plan) }?.let { Mastery.practice(it, evidence, now, random) }.orEmpty()
        } else {
            Mastery.review(p, evidence, now, random) { it.openOn(plan) }
        }
        mutableStateOf(Session(items))
    }
    val title = competencyId?.let { p.competency(it)?.title } ?: stringResource(R.string.acad_review_title)

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!session.done) {
                Text(
                    stringResource(R.string.acad_q_count, session.index + 1, session.items.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Bar(
            fraction = if (session.items.isEmpty()) 1f else session.index / session.items.size.toFloat(),
            color = MaterialTheme.colorScheme.primary,
            track = oneDevsColors.brandTint,
        )

        val asked = session.current
        when {
            session.items.isEmpty() -> {
                Panel { Body(stringResource(R.string.acad_review_empty)) }
                WideButton(stringResource(R.string.acad_done), onDone)
            }
            asked == null -> Summary(session, onDone)
            else -> Question(
                asked = asked,
                session = session,
                onPick = { if (!session.checked) session = session.copy(pick = it) },
                onHint = {
                    val more = (session.hints + 1).coerceAtMost(asked.question.hints.size)
                    session = session.copy(hints = more, usedHints = true)
                },
                onCheck = {
                    val pick = session.pick ?: return@Question
                    val right = asked.isRight(pick)
                    val hinted = session.hints > 0
                    scope.launch { app.academy.answer(asked, pick, hinted) }
                    session = session.copy(
                        checked = true,
                        items = if (!right && !asked.retry) session.items + Mastery.retry(asked, random) else session.items,
                        firstTry = session.firstTry + if (right && !hinted && !asked.retry) 1 else 0,
                        counted = session.counted + if (asked.retry) 0 else 1,
                        missed = session.missed || !right,
                    )
                },
                onNext = {
                    session = session.copy(index = session.index + 1, pick = null, hints = 0, checked = false)
                },
            )
        }
    }
}

@Composable
private fun Question(
    asked: Asked,
    session: Session,
    onPick: (Int) -> Unit,
    onHint: () -> Unit,
    onCheck: () -> Unit,
    onNext: () -> Unit,
) {
    val variant = asked.variant
    val checked = session.checked
    val right = checked && session.pick != null && asked.isRight(session.pick)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindChip(asked.question.kind)
            if (asked.retry) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.acad_q_retry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(variant.prompt, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (variant.picksLine) {
            Body(stringResource(R.string.acad_tap_line), muted = true)
            CodeLines(
                code = variant.code,
                onLine = if (checked) null else onPick,
                picked = session.pick,
                answer = if (checked) variant.answer else null,
            )
        } else {
            if (variant.code.isNotEmpty()) CodeLines(variant.code)
            asked.order.forEachIndexed { shown, original ->
                Choice(
                    text = variant.choices[original],
                    selected = session.pick == shown,
                    correct = checked && original == variant.answer,
                    wrong = checked && session.pick == shown && original != variant.answer,
                    onClick = { if (!checked) onPick(shown) },
                )
            }
        }
        asked.question.hints.take(session.hints).forEachIndexed { i, hint ->
            Well {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        painterResource(R.drawable.ic_lightbulb),
                        null,
                        tint = oneDevsColors.caution.solid,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            stringResource(R.string.acad_hint_n, i + 1, asked.question.hints.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Body(hint)
                    }
                }
            }
        }
    }
    if (checked) {
        val accent = if (right) oneDevsColors.live else oneDevsColors.critical
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(accent.tint)
                .padding(16.dp),
        ) {
            Text(
                stringResource(if (right) R.string.acad_right else R.string.acad_wrong),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent.solid,
            )
            Body(variant.why)
        }
        val last = session.index == session.items.lastIndex
        WideButton(stringResource(if (last) R.string.acad_finish else R.string.acad_next), onNext)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (session.hints < asked.question.hints.size) {
                WideButton(stringResource(R.string.acad_hint), onHint, Modifier.weight(1f), filled = false)
            }
            WideButton(stringResource(R.string.acad_check), onCheck, Modifier.weight(1f), enabled = session.pick != null)
        }
    }
}

@Composable
private fun KindChip(kind: QuestionKind) {
    val accent = if (kind.summative) oneDevsColors.mission else oneDevsColors.testing
    Text(
        text = stringResource(
            when (kind) {
                QuestionKind.Concept -> R.string.acad_kind_concept
                QuestionKind.Predict -> R.string.acad_kind_predict
                QuestionKind.Debug -> R.string.acad_kind_debug
                QuestionKind.Transfer -> R.string.acad_kind_transfer
            },
        ),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = accent.solid,
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.tint)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** One answer to choose: a well, brandTint when chosen, green or red once checked. */
@Composable
private fun Choice(text: String, selected: Boolean, correct: Boolean, wrong: Boolean, onClick: () -> Unit) {
    val fill = when {
        correct -> oneDevsColors.live.tint
        wrong -> oneDevsColors.critical.tint
        selected -> oneDevsColors.brandTint
        else -> oneDevsColors.well
    }
    val ink = when {
        correct -> oneDevsColors.live.solid
        wrong -> oneDevsColors.critical.solid
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || correct) FontWeight.SemiBold else FontWeight.Normal,
            color = ink,
        )
    }
}

@Composable
private fun Summary(session: Session, onDone: () -> Unit) {
    Panel {
        Text(stringResource(R.string.acad_session_done), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Body(stringResource(R.string.acad_session_score, session.firstTry, session.counted))
        if (session.usedHints) Body(stringResource(R.string.acad_session_hinted_note), muted = true)
        if (session.missed) Body(stringResource(R.string.acad_session_retry_note), muted = true)
    }
    WideButton(stringResource(R.string.acad_done), onDone)
}
