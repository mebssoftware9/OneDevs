package com.devbangs.onedevs.ui.missions

/**
 * Missions with something in them.
 *
 * Debug only, on the same terms as the board samples: there is no backend, a
 * list cannot be judged empty, and a release build that invented groups
 * someone had joined would be lying about their Play standing, which is the
 * one thing this app must never do.
 */
internal val SampleMissions = listOf(
    Mission("m-01", "Mission Ardent", joined = 14, day = 0, tasksDone = 0, tasksTotal = 0, payout = 120, member = false),
    Mission("m-02", "Mission Bellwether", joined = 16, day = 6, tasksDone = 11, tasksTotal = 18, payout = 120, member = true),
    Mission("m-03", "Mission Cinder", joined = 9, day = 0, tasksDone = 0, tasksTotal = 0, payout = 100, member = false),
    Mission("m-04", "Mission Dovetail", joined = 16, day = 14, tasksDone = 40, tasksTotal = 42, payout = 140, member = true),
)
