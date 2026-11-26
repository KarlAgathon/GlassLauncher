package com.glass.launcher

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class BackupSafetyTest {
    @Test fun validatesBeforeWritingAndPreservesLocalSecret() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "backup_test_" + UUID.randomUUID()
        val directory = File(target.cacheDir, prefix).apply { mkdir() }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences(prefix + name, mode)
        }
        val settings = context.getSharedPreferences("glass", Context.MODE_PRIVATE)
        val global = context.getSharedPreferences("glass_global", Context.MODE_PRIVATE)
        val wallpaper = File(directory, "wallpaper").apply { writeText("old") }
        settings.edit().putString("city", "old").commit()
        global.edit().putString("ghToken", "local").commit()
        val candidate = File(directory, "candidate.json")
        try {
            candidate.writeText("{broken")
            assertTrue(runCatching { Backup.restore(context, candidate) }.isFailure)
            assertEquals("old", wallpaper.readText())
            candidate.writeText("""{"format":1,"prefs":{"glass_global":{},"glass":{}},"files":{"icons/../escape":"eA=="}}""")
            assertTrue(runCatching { Backup.restore(context, candidate) }.isFailure)
            assertEquals("old", settings.getString("city", null))
            candidate.writeText("""{"format":1,"prefs":{"glass_global":{"ghToken":{"t":"s","v":"import"}},"glass":{"city":{"t":"s","v":"new"}}},"files":{"wallpaper":"bmV3"}}""")
            Backup.restore(context, candidate)
            assertEquals("new", settings.getString("city", null))
            assertEquals("new", wallpaper.readText())
            assertEquals("local", global.getString("ghToken", null))

            val journal = File(directory, ".restore-journal")
            File(journal, "previous").mkdirs()
            File(journal, "previous/wallpaper").writeText("recovered")
            File(journal, "rollback.json").writeText("""{"glass":{"city":{"t":"s","v":"recovered"}}}""")
            Backup.recover(context)
            assertEquals("recovered", settings.getString("city", null))
            assertEquals("recovered", wallpaper.readText())
            assertFalse(journal.exists())
        } finally {
            settings.edit().clear().commit()
            global.edit().clear().commit()
            directory.deleteRecursively()
        }
    }
}
