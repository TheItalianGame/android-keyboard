package org.futo.inputmethod.latin

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.UserManager
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
//import androidx.work.Configuration
import org.acra.ACRA
import org.acra.config.dialog
//import org.acra.config.httpSender
//import org.acra.sender.HttpSender
import org.acra.config.mailSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import androidx.core.content.edit
import org.acra.builder.ReportBuilder
import org.acra.config.CoreConfigurationBuilder
import org.acra.data.CrashReportDataFactory
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import org.futo.inputmethod.latin.uix.settings.LocalDataStoreCache
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.pages.copyToClipboard
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.plus

object LocalDebugLog {
    private val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    fun write(context: Context, prefix: String, text: String) {
        try {
            val filename = "$prefix-${dateFormat.format(Date())}.txt"
            val body = buildString {
                append("time=").append(Date()).append('\n')
                append("package=").append(context.packageName).append('\n')
                append("version=").append(BuildConfig.VERSION_NAME).append('\n')
                append('\n')
                append(text)
                append('\n')
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return

                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }
            } else {
                @Suppress("DEPRECATION")
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    filename
                )
                file.writeText(body)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun installJavaCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stack = StringWriter().also {
                throwable.printStackTrace(PrintWriter(it))
            }.toString()

            write(
                context,
                "futo-keyboard-java-crash",
                "thread=${thread.name}\n\n$stack"
            )

            previous?.uncaughtException(thread, throwable)
        }
    }
}

class CrashLoggingApplication : Application() /*, Configuration.Provider*/ {
    //override val workManagerConfiguration: Configuration
    //    get() = Configuration.Builder().build()

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        LocalDebugLog.installJavaCrashHandler(this)

        if(isDirectBootUnlocked) {
            try {
                if (getSharedPreferences("migrate", MODE_PRIVATE).getBoolean(
                        "wiped_work",
                        false
                    ) == false
                ) {
                    deleteDatabase("androidx.work.workdb")
                    getSharedPreferences("androidx.work.util.preferences", MODE_PRIVATE)
                        .edit { clear() }
                    getSharedPreferences("migrate", MODE_PRIVATE)
                        .edit { putBoolean("wiped_work", true) }
                }
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }

        if(BuildConfig.DEBUG) return

        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        if(userManager.isUserUnlocked) {
            println("Initializing ACRA, as user is unlocked")
            initAcra {
                reportFormat = StringFormat.JSON

                dialog {
                    text = getString(
                        //if(BuildConfig.ENABLE_ACRA) {
                        //    R.string.crashed_text
                        //} else {
                        R.string.crashed_text_email
                        //}
                    )
                    title = getString(R.string.crashed_title)
                    positiveButtonText = getString(R.string.crash_report_accept)
                    negativeButtonText = getString(R.string.crash_report_reject)
                    resTheme = android.R.style.Theme_DeviceDefault_Dialog
                }


                //if(BuildConfig.ENABLE_ACRA) {
                //    httpSender {
                //        uri = BuildConfig.ACRA_URL
                //        basicAuthLogin = BuildConfig.ACRA_USER
                //        basicAuthPassword = BuildConfig.ACRA_PASSWORD
                //        httpMethod = HttpSender.Method.POST
                //    }
                //} else {
                mailSender {
                    mailTo = "keyboard@futo.org"
                    reportAsFile = true
                    reportFileName = "Crash.txt"
                    subject = "Keyboard Crash Report"
                    body =
                        "I experienced this crash. My version: ${BuildConfig.VERSION_NAME}.\n\n(Enter details here if necessary)"
                }
                //}
            }

            acraInitialized = true
        } else {
            println("Skipping ACRA, as user is locked")
        }
    }

    companion object {
        var acraInitialized = false

        fun logPreferences(preferences: Preferences) {
            if(acraInitialized) {
                preferences.asMap().forEach {
                    ACRA.errorReporter.putCustomData(it.key.name, it.value.toString())
                }
            }
        }

        @Composable fun CopyLogsOption() {
            val data = LocalDataStoreCache.current
            val context = LocalContext.current
            NavigationItem(
                title = "Copy logs",
                subtitle = "May contain sensitive data",
                style = NavigationItemStyle.MiscNoArrow,
                navigate = {
                    val json = CrashReportDataFactory(context, CoreConfigurationBuilder().build())
                        .createCrashData(ReportBuilder().message("Copy logs").customData(
                            data!!.currPreferences.asMap().map {
                                it.key.name to it.value.toString()
                            }.toMap() + mapOf("Settings" to Settings.getInstance().current.dump())
                        ))
                        .toJSON()

                    context.copyToClipboard(json)
                },
            )
        }
    }
}
