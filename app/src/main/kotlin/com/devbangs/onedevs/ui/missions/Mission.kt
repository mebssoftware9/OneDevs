package com.devbangs.onedevs.ui.missions

/**
 * The numbers a mission runs on, in one place because every one of them is a
 * product decision and none of them should be typed into copy twice.
 */
object MissionRules {
    /**
     * Google requires twelve testers opted in continuously for fourteen days
     * for affected personal developer accounts. A group of sixteen means every
     * member has fifteen others testing theirs, which leaves three dropouts of
     * margin before anyone falls under the line.
     */
    const val SLOTS = 16
    const val WINDOW_DAYS = 14
    const val TESTERS_REQUIRED = 12

    /** Tasks a member is asked to complete each day, across the whole group. */
    const val DAILY_TASKS = 3

    /**
     * The share of daily tasks a member has to complete to stay in good
     * standing, and how many days they can fall under it before their app is
     * pulled from the group.
     *
     * This counts completed tasks and submitted feedback, never app opens.
     * Opens are the metric that is easiest to hit and means least, and the
     * production-access questionnaire asks what testers did and what changed
     * because of it -- a group that only opened apps has nothing to answer.
     */
    const val DAILY_THRESHOLD = 0.7f
    const val GRACE_DAYS = 3
}

/** Where a mission is in its life. */
enum class MissionStage {
    /** Still filling. Nothing starts until every slot is taken. */
    Gathering,

    /** Full, running, counting days. */
    Running,

    /** The window has elapsed. Not "approved" -- see [Mission]. */
    Elapsed,
}

/**
 * A group of developers testing each other's apps for the window.
 *
 * [day] counts what OneDevs has observed and nothing else. Google decides a
 * closed test on its own record of who was opted in and for how long, and this
 * is not that record. At the end the stage is [MissionStage.Elapsed], never
 * approved, because OneDevs is not in a position to know and saying otherwise
 * would be the one lie this product cannot afford.
 */
data class Mission(
    val id: String,
    val name: String,
    val joined: Int,
    val day: Int,
    val tasksDone: Int,
    val tasksTotal: Int,
    val payout: Int,
    val member: Boolean,
) {
    val stage: MissionStage
        get() = when {
            joined < MissionRules.SLOTS -> MissionStage.Gathering
            day >= MissionRules.WINDOW_DAYS -> MissionStage.Elapsed
            else -> MissionStage.Running
        }

    /** Free slots. Zero means the next join starts the clock. */
    val open: Int get() = (MissionRules.SLOTS - joined).coerceAtLeast(0)

    /**
     * Testers this member would have if the group holds: everyone else in it.
     * Stated as a fact about the group, not as a prediction about Google.
     */
    val coTesters: Int get() = (joined - 1).coerceAtLeast(0)

    val meetsRequirement: Boolean get() = coTesters >= MissionRules.TESTERS_REQUIRED
}
