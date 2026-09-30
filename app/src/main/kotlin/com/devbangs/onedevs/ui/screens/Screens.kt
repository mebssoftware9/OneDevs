package com.devbangs.onedevs.ui.screens

// Thin destinations for now: each moves to its own file once it carries real
// state. Nothing here fabricates data — screens show what is actually known,
// which before the data layer exists is nothing.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.listings.Channel
import com.devbangs.onedevs.data.usage.deviceId
import com.devbangs.onedevs.ui.badges.BadgeCatalogue
import com.devbangs.onedevs.ui.badges.BadgeGroupCard
import com.devbangs.onedevs.ui.board.BoardRow
import com.devbangs.onedevs.ui.board.LiveCard
import com.devbangs.onedevs.ui.components.ActionCard
import com.devbangs.onedevs.ui.components.BrandedLoading
import com.devbangs.onedevs.ui.components.EmptyState
import com.devbangs.onedevs.ui.components.FilterPills
import com.devbangs.onedevs.ui.components.IconBadge
import com.devbangs.onedevs.ui.lab.LabHome
import com.devbangs.onedevs.ui.launch.LaunchRow
import com.devbangs.onedevs.ui.profile.AccountCard
import com.devbangs.onedevs.ui.settings.AboutGroup
import com.devbangs.onedevs.ui.settings.AppSettingsGroup
import com.devbangs.onedevs.ui.settings.DeviceGroup
import com.devbangs.onedevs.ui.settings.ProfileHeader
import com.devbangs.onedevs.ui.theme.oneDevsColors

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
fun BoardScreen(
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var category by rememberSaveable { mutableStateOf(BoardCategory.Testing) }
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication

    // The Board comes from BoardStore: shown at once from the last read (on
    // disk across launches), refreshed behind it in one request, and not
    // re-read on resume if it is under a minute old. Coming back from the
    // Play Store is the most common thing a tester does; it no longer costs
    // three round trips.
    //
    // null is still "not read yet", which has to look different from an
    // empty board or a new platform looks broken on its first day.
    val home by app.board.board.collectAsState()
    val testing = home?.testing
    val live = home?.live
    val stats = home?.stats

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { app.board.refresh(deviceId(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    // Nothing on screen and the read failed: try again shortly rather than
    // leaving the spinner up until the next resume.
    val failed by app.board.failed.collectAsState()
    LaunchedEffect(failed, tick) {
        if (failed && home == null) {
            kotlinx.coroutines.delay(5_000)
            tick++
        }
    }

    val shown = if (category == BoardCategory.Testing) testing else live

    Column(modifier = modifier.fillMaxSize()) {
        LiveCard(
            active = stats?.liveNow,
            trend = stats?.pulse.orEmpty().map { it.testers.toFloat() },
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
        when {
            shown == null -> Box(modifier = Modifier.weight(1f)) { BrandedLoading() }

            shown.isEmpty() -> Box(modifier = Modifier.weight(1f)) {
                EmptyState(
                    title = stringResource(category.emptyTitle),
                    body = stringResource(category.emptyBody),
                ) { IconBadge(painterResource(R.drawable.ic_squares_four)) }
            }

            else -> {
                Text(
                    text = stringResource(category.heading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(
                        start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp,
                    ),
                )
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(shown, key = { it.id }) { listing ->
                        // Opens the listing, not the store. A tester decides
                        // from what the developer wrote, not from a Play page
                        // they were dropped on without being asked.
                        BoardRow(listing = listing, onClick = { onOpen(listing.id) })
                    }
                }
            }
        }
    }
}

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

/**
 * No page title. The tab beneath already says where you are, and repeating it
 * in 32sp costs a third of the first screenful to say it twice. The line that
 * does work -- what this place is for -- runs directly under the top bar.
 */
@Composable
fun LaunchesScreen(
    onAdd: (Channel) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(LaunchFilter.All) }
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.launches_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp),
        )
        // Two cards, side by side, no scroll. There were three, and the third
        // -- creating a mission -- now has a button of its own on the Missions
        // screen, which is where someone goes to think about missions. Two fit
        // a phone without moving, and a row that does not move needs no drift
        // to advertise that it can: everything on it is already visible.
        Row(
            horizontalArrangement = Arrangement.spacedBy(CardGap),
            modifier = Modifier
                .height(IntrinsicSize.Max)
                .padding(horizontal = 20.dp),
        ) {
            ActionCard(
                modifier = Modifier.weight(1f),
                accent = oneDevsColors.testing,
                icon = painterResource(R.drawable.ic_users_three),
                title = stringResource(R.string.launch_testing_title),
                body = stringResource(R.string.launch_testing_body),
                status = stringResource(R.string.launch_testing_status),
                available = true,
                footerIcon = painterResource(R.drawable.ic_users_three),
                footerLabel = stringResource(R.string.launch_testing_footer),
                onClick = { onAdd(Channel.Testing) },
            )
            ActionCard(
                modifier = Modifier.weight(1f),
                accent = oneDevsColors.live,
                icon = painterResource(R.drawable.ic_globe),
                title = stringResource(R.string.launch_live_title),
                body = stringResource(R.string.launch_live_body),
                status = stringResource(R.string.launch_live_status),
                available = true,
                footerIcon = painterResource(R.drawable.ic_globe),
                footerLabel = stringResource(R.string.launch_live_footer),
                onClick = { onAdd(Channel.Live) },
            )
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
        // Listings are the developer's own records, read straight from the
        // store rather than through a ViewModel: there is no state here beyond
        // the flow, and a ViewModel that only forwards one is a layer to
        // maintain in exchange for nothing.
        val app = LocalContext.current.applicationContext as OneDevsApplication
        val listings by app.listings.listings.collectAsStateWithLifecycle(emptyList())
        val mine = listings.filter {
            when (filter) {
                LaunchFilter.All -> true
                LaunchFilter.Testing -> it.channel == Channel.Testing
                LaunchFilter.Live -> it.channel == Channel.Live
            }
        }
        if (mine.isEmpty()) {
            Box(modifier = Modifier.weight(1f)) {
                EmptyState(
                    title = stringResource(filter.emptyTitle),
                    body = stringResource(filter.emptyBody),
                ) { IconBadge(painterResource(R.drawable.ic_rocket_launch)) }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(mine, key = { it.id }) { listing ->
                    LaunchRow(listing = listing, onOpen = { onOpen(listing.id) })
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
    ) {
        AccountCard()
        AppSettingsGroup()
        DeviceGroup()
        AboutGroup()
    }
}

@Composable
fun LabScreen(modifier: Modifier = Modifier) {
    LabHome(modifier)
}

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
