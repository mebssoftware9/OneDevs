package com.devbangs.onedevs.lab

/**
 * A sentence the rules name and the screen resolves.
 *
 * The rules used to write English. That was fine while the wording changed
 * every round, and wrong the moment it settled: the app ships four languages
 * and the entire Lab was speaking one. Naming a resource instead keeps the
 * rules free of a Context, keeps them unit-testable without Android, and makes
 * a test assert which sentence was chosen rather than what it happens to say
 * this week.
 */
sealed interface Msg {

    /** A string resource, with whatever it needs formatting into it. */
    data class Str(val id: Int, val args: List<Any> = emptyList()) : Msg

    /** A quantity string. [count] picks the form and is usually also an arg. */
    data class Plural(val id: Int, val count: Int, val args: List<Any> = emptyList()) : Msg

    /**
     * Text that is not translated because it is not language: a permission's
     * own name, an ABI, a class. Translating READ_MEDIA_IMAGES would make it
     * harder to search for, not easier to read.
     */
    data class Raw(val text: String) : Msg
}

internal fun str(id: Int, vararg args: Any) = Msg.Str(id, args.toList())

internal fun plural(id: Int, count: Int, vararg args: Any) = Msg.Plural(id, count, args.toList())
