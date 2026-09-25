package com.devbangs.onedevs.ui.board

import com.devbangs.onedevs.R

/**
 * What the board looks like with something on it.
 *
 * Debug only, and gated by BuildConfig.DEBUG rather than by a constant someone
 * has to remember to flip. There is no backend yet, and a list component cannot
 * be judged against an empty list -- but a release build that invents apps and
 * a developer count would be lying to its users, so the compiler decides.
 *
 * Every name here is made up. Putting real third-party apps in with invented
 * ratings would be a different and worse problem.
 */
internal val SampleTestingApps = listOf(
    BoardApp("FitTrack", "Health", "4.2", "46 MB", 25, R.drawable.ic_pulse, 0),
    BoardApp("TaskFlow", "Productivity", "4.5", "32 MB", 25, R.drawable.ic_list_checks, 1),
    BoardApp("HabitCraft", "Lifestyle", "4.2", "51 MB", 25, R.drawable.ic_fire, 2),
    BoardApp("NoteNest", "Notes", "4.1", "28 MB", 25, R.drawable.ic_clipboard_text, 3),
    BoardApp("BudgetBee", "Finance", "4.4", "42 MB", 25, R.drawable.ic_coins, 4),
)

/** Apps already published, looking for early users rather than testers. */
internal val SampleEarlyApps = listOf(
    BoardApp("PhotoFix", "Photography", "4.2", "37 MB", 15, R.drawable.ic_camera, 0),
    BoardApp("StudyMate", "Education", "4.3", "66 MB", 15, R.drawable.ic_graduation_cap, 1),
    BoardApp("SkyNotes", "Productivity", "4.4", "24 MB", 15, R.drawable.ic_clipboard_text, 2),
)

/** A day's worth of shape for the sparkline. Debug only, same reasoning. */
internal val SampleTrend =
    listOf(38f, 41f, 37f, 44f, 49f, 46f, 52f, 58f, 55f, 61f, 59f, 66f, 71f, 68f, 74f, 79f)

/** What the sample board reports as active. Debug only, same reasoning. */
internal const val SampleActive = 1284
