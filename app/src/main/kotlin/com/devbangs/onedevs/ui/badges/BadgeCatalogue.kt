package com.devbangs.onedevs.ui.badges

import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.Accent
import com.devbangs.onedevs.ui.theme.OneDevsColors

/**
 * One badge: what it is called and what earns it.
 *
 * Resource ids are plain Ints rather than @StringRes/@DrawableRes; the
 * annotations would be the only thing this module pulls androidx.annotation in
 * for, and every one of these is read straight into a stringResource call.
 */
internal data class BadgeSpec(val icon: Int, val name: Int, val requirement: Int)

/**
 * A group of badges that are earned by the same kind of work.
 *
 * [accent] is a lambda rather than a colour because the catalogue is plain data
 * and the accents only exist inside a composition. The group picks which one it
 * wants; the card resolves it when it draws.
 */
internal data class BadgeGroup(
    val icon: Int,
    val name: Int,
    val tagline: Int,
    val accent: (OneDevsColors) -> Accent,
    val badges: List<BadgeSpec>,
)

/**
 * Every badge OneDevs can award, and what each one takes.
 *
 * This is a catalogue, not state: it is the same for a developer who has earned
 * none of them and one who has earned all of them, so it lives here as data
 * rather than arriving from a server. What a given account has actually earned
 * is the set passed alongside it, which is empty until there are accounts.
 *
 * One accent per group rather than one per badge. The mockups colour all
 * nineteen individually, which would mean nineteen hues in a palette built on
 * five; the group carries the colour and earned-or-not carries the rest.
 * Verification borrows the live accent because both are the same green idea:
 * this has been checked and it holds.
 */
internal val BadgeCatalogue = listOf(
    BadgeGroup(
        icon = R.drawable.ic_flask,
        name = R.string.badge_group_testing,
        tagline = R.string.badge_group_testing_tagline,
        accent = { it.testing },
        badges = listOf(
            BadgeSpec(R.drawable.ic_seal_check, R.string.badge_first_test, R.string.badge_first_test_req),
            BadgeSpec(R.drawable.ic_test_tube, R.string.badge_tester, R.string.badge_tester_req),
            BadgeSpec(R.drawable.ic_flask, R.string.badge_dedicated, R.string.badge_dedicated_req),
            BadgeSpec(R.drawable.ic_trophy, R.string.badge_veteran, R.string.badge_veteran_req),
            BadgeSpec(R.drawable.ic_fire, R.string.badge_streak, R.string.badge_streak_req),
        ),
    ),
    BadgeGroup(
        icon = R.drawable.ic_chat_circle_dots,
        name = R.string.badge_group_feedback,
        tagline = R.string.badge_group_feedback_tagline,
        accent = { it.feedback },
        badges = listOf(
            BadgeSpec(R.drawable.ic_lightbulb, R.string.badge_useful, R.string.badge_useful_req),
            BadgeSpec(R.drawable.ic_bug, R.string.badge_bug_hunter, R.string.badge_bug_hunter_req),
            BadgeSpec(R.drawable.ic_target, R.string.badge_detail, R.string.badge_detail_req),
        ),
    ),
    BadgeGroup(
        icon = R.drawable.ic_users_three,
        name = R.string.badge_group_community,
        tagline = R.string.badge_group_community_tagline,
        accent = { it.community },
        badges = listOf(
            BadgeSpec(R.drawable.ic_handshake, R.string.badge_builder, R.string.badge_builder_req),
            BadgeSpec(R.drawable.ic_users, R.string.badge_helper, R.string.badge_helper_req),
            BadgeSpec(R.drawable.ic_star, R.string.badge_contributor, R.string.badge_contributor_req),
        ),
    ),
    BadgeGroup(
        icon = R.drawable.ic_rocket_launch,
        name = R.string.badge_group_creator,
        tagline = R.string.badge_group_creator_tagline,
        accent = { it.mission },
        badges = listOf(
            BadgeSpec(R.drawable.ic_rocket_launch, R.string.badge_creator, R.string.badge_creator_req),
            BadgeSpec(R.drawable.ic_clipboard_text, R.string.badge_campaign, R.string.badge_campaign_req),
            BadgeSpec(R.drawable.ic_trophy, R.string.badge_trusted_creator, R.string.badge_trusted_creator_req),
        ),
    ),
    BadgeGroup(
        icon = R.drawable.ic_shield_check,
        name = R.string.badge_group_trust,
        tagline = R.string.badge_group_trust_tagline,
        accent = { it.live },
        badges = listOf(
            BadgeSpec(R.drawable.ic_device_mobile, R.string.badge_device, R.string.badge_device_req),
            BadgeSpec(R.drawable.ic_user_check, R.string.badge_account, R.string.badge_account_req),
            BadgeSpec(R.drawable.ic_shield_check, R.string.badge_reliable, R.string.badge_reliable_req),
            BadgeSpec(R.drawable.ic_lock_open, R.string.badge_eligible, R.string.badge_eligible_req),
        ),
    ),
)
