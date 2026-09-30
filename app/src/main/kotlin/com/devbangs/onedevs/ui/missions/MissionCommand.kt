package com.devbangs.onedevs.ui.missions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.quietly
import com.devbangs.onedevs.data.missions.MissionMessage
import com.devbangs.onedevs.ui.components.BrandedLoading
import com.devbangs.onedevs.ui.components.DevBotMark
import com.devbangs.onedevs.ui.components.EmptyState
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often an open room asks for new lines. */
private const val POLL_MS = 4_000L
private const val MAX_LENGTH = 500

/**
 * Mission Command: one room for the whole mission.
 *
 * Not a set of private chats. Everyone in the mission reads the same room,
 * which is where a group sorts itself out -- "my link is fixed", "day 3,
 * everyone still in?" -- and the server posts joins and the start into it, so
 * it doubles as the mission's log.
 *
 * It polls while the screen is in front, quietly: a room left open is
 * background work and never shows "Slow connection".
 */
@Composable
fun MissionCommandScreen(missionId: String, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()

    var messages by remember(missionId) { mutableStateOf<List<MissionMessage>>(emptyList()) }
    var loaded by remember(missionId) { mutableStateOf(false) }
    var draft by rememberSaveable(missionId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    val list = rememberLazyListState()

    suspend fun pull() {
        val after = messages.lastOrNull()?.id ?: 0L
        val fresh = quietly(quiet = loaded) { app.missions.feed(missionId, after) } ?: return
        if (fresh.isNotEmpty()) messages = (messages + fresh).distinctBy { it.id }
        loaded = true
    }

    var visible by remember { mutableStateOf(false) }
    LifecycleResumeEffect(missionId) {
        visible = true
        onPauseOrDispose { visible = false }
    }
    LaunchedEffect(missionId, visible) {
        while (visible) {
            pull()
            delay(POLL_MS)
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Text(
            text = stringResource(R.string.mission_command),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
        )
        Text(
            text = stringResource(R.string.mission_command_members_only),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            when {
                !loaded -> BrandedLoading()
                messages.isEmpty() -> EmptyState(
                    title = stringResource(R.string.mission_command),
                    body = stringResource(R.string.mission_command_empty),
                ) { DevBotMark() }
                else -> LazyColumn(
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(messages, key = { it.id }) { Line(it) }
                }
            }
        }
        problem?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = oneDevsColors.critical.solid,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(MAX_LENGTH) },
                placeholder = { Text(stringResource(R.string.mission_command_hint)) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val canSend = draft.isNotBlank() && !sending
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .clickable(enabled = canSend) {
                        val body = draft.trim()
                        sending = true
                        problem = null
                        scope.launch {
                            val ack = app.missions.post(missionId, body)
                            sending = false
                            when {
                                ack == null -> problem = R.string.mission_command_failed
                                ack.ok -> {
                                    draft = ""
                                    pull()
                                }
                                ack.reason == "slow_down" -> problem = R.string.mission_command_slow
                                else -> problem = R.string.mission_command_failed
                            }
                        }
                    },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_caret_up),
                    contentDescription = stringResource(R.string.mission_command_send),
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun Line(message: MissionMessage) {
    val scheme = MaterialTheme.colorScheme
    when (message.kind) {
        "join", "start" -> Text(
            text = if (message.kind == "join") {
                stringResource(R.string.mission_command_joined, message.author.orEmpty(), message.body)
            } else {
                stringResource(R.string.mission_command_started, message.body)
            },
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )

        else -> Box(
            contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                if (!message.mine) {
                    Text(
                        text = listOfNotNull(
                            message.author,
                            message.seat?.let { stringResource(R.string.mission_seat_label, it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = oneDevsColors.community.solid,
                        modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                    )
                }
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.mine) scheme.onPrimary else scheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (message.mine) scheme.primary else scheme.surfaceContainerHigh)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
