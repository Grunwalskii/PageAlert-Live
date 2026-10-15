package com.clinscout.pagealertlive

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CrashReportsIntegrationTest {
    @Test fun savesLocalExceptionAndDeviceDetailsAndDelegatesToAndroidHandler() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val crashFile = File(context.filesDir, "last-crash.txt")
        val savedReport = crashFile.takeIf { it.exists() }?.readBytes()
        val error = IllegalStateException("Synthetic shutdown regression")
        var delegated: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, failure -> delegated = failure }
            CrashReports.install(context)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertSame(error, delegated)
            val report = CrashReports.read(context)!!
            @Suppress("DEPRECATION")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            assertTrue(report.contains("PageAlert Live $version"))
            assertTrue(report.contains(Build.MODEL))
            assertTrue(report.contains("IllegalStateException: Synthetic shutdown regression"))
            assertTrue(report.contains("savesLocalExceptionAndDeviceDetailsAndDelegatesToAndroidHandler"))
            assertTrue(report.length <= 32768)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
            if (savedReport == null) crashFile.delete() else crashFile.writeBytes(savedReport)
        }
    }
}
