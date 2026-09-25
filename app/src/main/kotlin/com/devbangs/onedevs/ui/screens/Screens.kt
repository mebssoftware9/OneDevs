package com.devbangs.onedevs.ui.screens

// Thin destinations for now: each moves to its own file once it carries real
// state. Nothing here fabricates data — screens show what is actually known,
// which before the data layer exists is nothing.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.devbangs.onedevs.BuildConfig
import com.devbangs.onedevs.ui.badges.BadgeCatalogue
import com.devbangs.onedevs.ui.badges.BadgeGroupCard
import com.devbangs.onedevs.ui.components.ActionCard
import com.devbangs.onedevs.ui.components.ActionCardWidth
import com.devbangs.onedevs.ui.components.DevBotMark
import com.devbangs.onedevs.ui.components.EmptyState
import com.devbangs.onedevs.ui.board.LiveAppRow
import com.devbangs.onedevs.ui.board.LiveApps
import com.devbangs.onedevs.ui.board.LiveCard
import com.devbangs.onedevs.ui.board.openPlayListing
import com.devbangs.onedevs.ui.board.SampleActive
import com.devbangs.onedevs.ui.board.SampleTestingApps
import com.devbangs.onedevs.ui.board.SampleTrend
import com.devbangs.onedevs.ui.board.TestAppRow
import com.devbangs.onedevs.ui.components.FilterPills
import com.devbangs.onedevs.ui.components.IconBadge
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The two things a developer can be on the board for. Testing is the 14-day
 * closed test -- the reason OneDevs exists; Early Users is an app that has
 * already gone live and wants its first real ones. Different work, different
 * reward, so they are categories rather than a filter over one list.
 */
private enum class BoardCategory(
    val label: Int,
    val icon: Int,
    val heading: Int,
    val emptyTitle: Int,
    val emptyBody: Int,
) {
    Testing(
        R.string.board_cat_testing,
        R.drawable.ic_flask,
        R.string.board_apps_heading,
        R.string.board_empty_title,
        R.string.board_empty_body,
    ),
    EarlyUsers(
        R.string.board_cat_early,
        R.drawable.ic_users_three,
        R.string.board_early_heading,
        R.string.board_empty_early_title,
        R.string.board_empty_early_body,
    ),
}

/**
 * Activity first, then the apps waiting for it.
 *
 * The live card is the top of the screen because it answers the question
 * someone opening OneDevs is actually asking -- is anybody here -- before it
 * asks anything of them. It draws whether or not there is a number to put in
 * it; what changes is that the pill does not claim to be live and the count
 * shows a dash instead of a zero.
 *
 * The rows are Play's shape, not the mockup's: icon, name, one metadata line,
 * and the reason to tap on the right. The mockup gives each app a card with a
 * description and its own button, which is a fine way to show five apps and a
 * poor way to scan forty.
 *
 * Apps come from the samples in a debug build and from nothing in a release
 * one. There is no backend, and an invented board is worse than an empty one,
 * so BuildConfig.DEBUG decides rather than a constant anyone has to remember.
 */
@Composable
fun BoardScreen(modifier: Modifier = Modifier) {
    var category by rememberSaveable { mutableStateOf(BoardCategory.Testing) }
    // Testing has no backend, so it is samples in debug and empty in release.
    // Live Apps does not need one: these are real listings with real package
    // names, and the deep link into Play works with nothing behind it.
    val testing = if (BuildConfig.DEBUG) SampleTestingApps else emptyList()
    val context = LocalContext.current
    val active = if (BuildConfig.DEBUG) SampleActive else null
    val trend = if (BuildConfig.DEBUG) SampleTrend else emptyList()
    Column(modifier = modifier.fillMaxSize()) {
        LiveCard(
            active = active,
            trend = trend,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
        )
        FilterPills(
            labels = BoardCategory.entries.map { stringResource(it.label) },
            selected = category.ordinal,
            onSelect = { category = BoardCategory.entries[it] },
            icons = BoardCategory.entries.map { it.icon },
            fillWidth = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp),
        )
        val empty = if (category == BoardCategory.Testing) testing.isEmpty() else LiveApps.isEmpty()
        if (empty) {
            Box(modifier = Modifier.weight(1f)) {
                EmptyState(
                    title = stringResource(category.emptyTitle),
                    body = stringResource(category.emptyBody),
                ) { IconBadge(painterResource(R.drawable.ic_squares_four)) }
            }
        } else {
            Text(
                text = stringResource(category.heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (category == BoardCategory.Testing) {
                    items(testing, key = { it.name }) { app -> TestAppRow(app = app, onClick = {}) }
                } else {
                    items(LiveApps, key = { it.packageName }) { app ->
                        LiveAppRow(app = app, onClick = { openPlayListing(context, app.packageName) })
                    }
                }
            }
        }
    }
}

@Composable
fun MissionsScreen(modifier: Modifier = Modifier) = EmptyState(
    title = stringResource(R.string.missions_empty_title),
    body = stringResource(R.string.missions_empty_body),
    modifier = modifier,
) { DevBotMark() }

/**
 * What My launches is narrowed to. Each carries its own empty copy, so the
 * control does something real before there is a list for it to filter.
 *
 * The resource ids are plain Ints rather than @StringRes: the annotation would
 * be the only thing this module pulls androidx.annotation in for.
 */
private enum class LaunchFilter(val label: Int, val emptyTitle: Int, val emptyBody: Int) {
    All(R.string.filter_all, R.string.launches_empty_title, R.string.launches_empty_body),
    Testing(
        R.string.filter_testing,
        R.string.launches_empty_testing_title,
        R.string.launches_empty_testing_body,
    ),
    Live(
        R.string.filter_live,
        R.string.launches_empty_live_title,
        R.string.launches_empty_live_body,
    ),
}

/** Gap between action cards, and the number the loop's period is built from. */
private val CardGap = 12.dp

/** How far the row travels per second while it is drifting. */
private val DriftPerSecond = 52.dp

/**
 * No page title. The tab beneath already says where you are, and repeating it
 * in 32sp costs a third of the first screenful to say it twice. The line that
 * does work -- what this place is for -- runs directly under the top bar.
 */
@Composable
fun LaunchesScreen(modifier: Modifier = Modifier) {
    var filter by rememberSaveable { mutableStateOf(LaunchFilter.All) }
    val cardScroll = rememberScrollState()
    // Stops on the first touch anywhere on the screen and does not come back.
    // Watching the Initial pointer pass means a tap on a card counts the same
    // as a drag on the row: anyone who has started reading has already said
    // they do not need to be shown that the row moves.
    var touched by rememberSaveable { mutableStateOf(false) }

    // One direction, forever. The row holds the three actions twice, so after
    // travelling exactly one set it can be put back to where it started and the
    // pixels do not change -- A B C A B C, wrapped where the second A sits on
    // the first. A pendulum was the wrong shape: reversing is an event, and an
    // event is the thing you cannot read past.
    val pitchPx = with(LocalDensity.current) { (ActionCardWidth + CardGap).toPx() }
    val setPx = pitchPx * 3
    val speedPx = with(LocalDensity.current) { DriftPerSecond.toPx() }
    LaunchedEffect(touched) {
        if (touched) return@LaunchedEffect
        delay(600)
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Driven by elapsed time, not by frame count, so it travels at the
            // same speed on a 60Hz panel and a 120Hz one.
            val seconds = (now - previous) / 1_000_000_000f
            previous = now
            if (cardScroll.value >= setPx) cardScroll.scrollTo(cardScroll.value - setPx.roundToInt())
            cardScroll.scrollBy(speedPx * seconds)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    awaitPointerEvent(PointerEventPass.Initial)
                    touched = true
                }
            },
    ) {
        Text(
            text = stringResource(R.string.launches_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 18.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(CardGap),
            modifier = Modifier
                .horizontalScroll(cardScroll)
                // Intrinsic height, so the three cards match the tallest of them
                // and their status rows sit on one line across the set.
                .height(IntrinsicSize.Max)
                .padding(horizontal = 20.dp),
        ) {
            // A card leaving by the left edge shrinks and fades out, so the
            // wrap reads as one leaving while its copy arrives rather than as a
            // jump. Read straight from the scroll value inside a graphicsLayer
            // block, which defers to the draw phase: a card sliding away is not
            // rebuilding its text every frame.
            val passing: (Int) -> Modifier = { index ->
                Modifier.graphicsLayer {
                    val t = ((cardScroll.value - index * pitchPx) / pitchPx).coerceIn(0f, 1f)
                    scaleX = 1f - 0.14f * t
                    scaleY = 1f - 0.14f * t
                    alpha = 1f - 0.9f * t
                }
            }

            // Twice, so the wrap has something identical to land on.
            LaunchActions(0, passing)
            LaunchActions(3, passing)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.launches_mine_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                // The heading yields before the filters do. At a large font
                // scale the pair stops fitting one line on a 320dp screen, and
                // a wrapped heading is better than a clipped control.
                modifier = Modifier.weight(1f),
            )
            FilterPills(
                labels = LaunchFilter.entries.map { stringResource(it.label) },
                selected = filter.ordinal,
                onSelect = { filter = LaunchFilter.entries[it] },
            )
        }
        // The filters have no list to narrow yet, but they are not decoration:
        // each one says what would be here, so choosing Live tells you what Live
        // would hold rather than repeating the same sentence three times. When
        // there are launches, the same selection narrows them.
        Box(modifier = Modifier.weight(1f)) {
            EmptyState(
                title = stringResource(filter.emptyTitle),
                body = stringResource(filter.emptyBody),
            ) { IconBadge(painterResource(R.drawable.ic_rocket_launch)) }
        }
    }
}

/**
 * The three actions, in order. Emitted twice by the row so the loop has an
 * identical set to wrap onto; [first] is the index the leftmost of the three
 * occupies in the row, which is what [passing] needs to know where it sits.
 *
 * available is hard-coded until account state exists. Eligibility is a real
 * rule -- missions cost DevCoins and new accounts cannot run them -- but
 * nothing computes it yet, so these values are presentation standing in for a
 * check that has still to be written.
 */
@Composable
private fun LaunchActions(first: Int, passing: (Int) -> Modifier) {
    ActionCard(
        modifier = passing(first),
        accent = oneDevsColors.testing,
        icon = painterResource(R.drawable.ic_users_three),
        title = stringResource(R.string.launch_testing_title),
        body = stringResource(R.string.launch_testing_body),
        status = stringResource(R.string.launch_testing_status),
        available = true,
        footerIcon = painterResource(R.drawable.ic_users_three),
        footerLabel = stringResource(R.string.launch_testing_footer),
        onClick = {},
    )
    ActionCard(
        modifier = passing(first + 1),
        accent = oneDevsColors.mission,
        icon = painterResource(R.drawable.ic_target),
        title = stringResource(R.string.launch_mission_title),
        body = stringResource(R.string.launch_mission_body),
        status = stringResource(R.string.launch_mission_status),
        available = false,
        footerIcon = painterResource(R.drawable.ic_coins),
        footerLabel = stringResource(R.string.launch_mission_footer),
        onClick = {},
    )
    ActionCard(
        modifier = passing(first + 2),
        accent = oneDevsColors.live,
        icon = painterResource(R.drawable.ic_globe),
        title = stringResource(R.string.launch_live_title),
        body = stringResource(R.string.launch_live_body),
        status = stringResource(R.string.launch_live_status),
        available = true,
        footerIcon = painterResource(R.drawable.ic_globe),
        footerLabel = stringResource(R.string.launch_live_footer),
        onClick = {},
    )
}

@Composable
fun LabScreen(modifier: Modifier = Modifier) = EmptyState(
    title = stringResource(R.string.lab_empty_title),
    body = stringResource(R.string.lab_empty_body),
    modifier = modifier,
) { IconBadge(painterResource(R.drawable.ic_flask)) }

/**
 * The catalogue, not a profile. Every badge OneDevs awards is listed with what
 * earns it, whether or not any of them have been: a developer deciding whether
 * this is worth their time should be able to see what is on offer before they
 * have an account.
 *
 * earned is empty because nothing computes it yet. It is a parameter of the
 * card rather than a constant inside it, so the day there are accounts this
 * screen passes a real set and nothing else changes.
 */
@Composable
fun BadgeScreen(modifier: Modifier = Modifier) {
    val earned = emptySet<Int>()
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.badge_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        Text(
            text = stringResource(R.string.badge_your_badges),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        BadgeCatalogue.forEach { group -> BadgeGroupCard(group, earned) }
    }
}

@Composable
fun WalletScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_devcoin_hero),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Text(
                text = stringResource(R.string.wallet_empty_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.wallet_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}
