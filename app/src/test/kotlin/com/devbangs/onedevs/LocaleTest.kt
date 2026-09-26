package com.devbangs.onedevs

import com.devbangs.onedevs.settings.AppLanguage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A language lives in three places: the enum the picker reads, the
 * locales_config the system picker reads, and a res/values folder holding the
 * actual strings. Nothing links them, so any two can agree while the third
 * does not -- and the failure is a picker offering a language the app cannot
 * speak, which looks like a bug in the translation rather than in the wiring.
 */
class LocaleWiringTest {

    /**
     * Gradle's working directory for a test is its own business -- the module
     * dir under one configuration and the root under another -- so this walks
     * up until it finds the folder rather than assuming which one it started
     * in. The first version assumed, and failed on the first real run.
     */
    private val res: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/res").takeIf(File::isDirectory) ?: File(it, "src/main/res") }
        .first(File::isDirectory)

    private val declared: List<String>
        get() = AppLanguage.entries.map { it.tag }.filter { it.isNotEmpty() }

    /**
     * English is the source language, so its strings are the defaults in
     * values/ rather than a values-en/ of their own. Android resolves an
     * English device to those, and a values-en would be a second copy to keep
     * in step with the first.
     */
    private val source = "en"

    private fun stringsFor(tag: String): File =
        if (tag == source) File(res, "values/strings.xml") else File(res, "values-${tag.replace("-", "-r")}/strings.xml")

    @Test
    fun `every language in the picker has strings behind it`() {
        declared.forEach {
            assertTrue("no strings file behind $it", stringsFor(it).isFile)
        }
    }

    @Test
    fun `every language in the picker is in locales_config`() {
        val config = File(res, "xml/locales_config.xml").readText()
        declared.forEach {
            assertTrue("$it is missing from locales_config", """android:name="$it"""" in config)
        }
    }

    @Test
    fun `locales_config lists nothing the picker does not offer`() {
        val config = File(res, "xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([\w-]+)"""").findAll(config).map { it.groupValues[1] }
        listed.forEach {
            assertTrue("locales_config offers $it but AppLanguage does not", it in declared)
        }
    }

    @Test
    fun `the default is the absence of a choice, and comes first`() {
        assertEquals(AppLanguage.System, AppLanguage.entries.first())
        assertEquals("", AppLanguage.System.tag)
        assertEquals(1, AppLanguage.entries.count { it.tag.isEmpty() })
    }

    @Test
    fun `the device decides unless someone says otherwise`() {
        // System first and System default means Android matches the phone's
        // languages against what ships here. A phone set to French opens in
        // French with nobody choosing anything, which is the behaviour people
        // expect and the one they never thank you for.
        assertEquals(AppLanguage.System, AppLanguage.entries.first())
    }

    @Test
    fun `tags are well formed BCP 47`() {
        val shape = Regex("[a-z]{2}(-[A-Z]{2})?")
        declared.forEach { assertTrue("$it is not a language tag", shape.matches(it)) }
    }

    @Test
    fun `languages name themselves`() {
        // Español, not "Spanish". Someone hunting for their language is
        // hunting for the word they would write.
        assertEquals("Español", AppLanguage.Spanish.label)
        assertEquals("English", AppLanguage.English.label)
        assertEquals("Français", AppLanguage.French.label)
        assertEquals("Português (Brasil)", AppLanguage.Portuguese.label)
    }
}
