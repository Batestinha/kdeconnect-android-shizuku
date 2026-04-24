/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.kde.kdeconnect_tp.BuildConfig
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardListener {
    enum class ClipboardContentType {
        Text,
        Password,
    }

    interface ClipboardObserver {
        fun clipboardChanged(content: String, contentType: ClipboardContentType)
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()

    private val context: Context
    var currentContent: String? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set

    private lateinit var cm: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var shizukuBindingInFlight = false

    @Volatile
    private var shizukuBinderReceived = false

    @Volatile
    private var shizukuPermissionRequestInFlight = false

    @Volatile
    private var shizukuService: IClipboardMonitorService? = null

    @Volatile
    private var readLogsWorker: Thread? = null

    @Volatile
    private var readLogsProcess: Process? = null

    private val privilegedCallback = object : IClipboardMonitorCallback.Stub() {
        override fun onClipboardTextChanged(text: String?, isSensitive: Boolean) {
            if (text.isNullOrEmpty()) return

            Log.d(TAG, "Shizuku monitor delivered clipboard text")

            mainHandler.post {
                val contentType = if (isSensitive) {
                    ClipboardContentType.Password
                } else {
                    ClipboardContentType.Text
                }
                if (text == currentContent && contentType == currentContentType) return@post

                updateTimestamp = System.currentTimeMillis()
                currentContent = text
                currentContentType = contentType

                val snapshot = observers.toList()
                for (observer in snapshot) {
                    observer.clipboardChanged(text, contentType)
                }
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuBinderReceived = true
        Log.d(TAG, "Shizuku binder received")
        ensureClipboardMonitor()
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Shizuku user service connected: $name")
            shizukuBindingInFlight = false

            try {
                val remote = IClipboardMonitorService.Stub.asInterface(service)
                shizukuService = remote
                remote?.start(privilegedCallback)
                Log.d(TAG, "Requested remote clipboard monitor start")
                stopReadLogsListener()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed starting Shizuku clipboard monitor", t)
                shizukuService = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Shizuku user service disconnected: $name")
            shizukuService = null
            shizukuBindingInFlight = false
            ensureClipboardMonitor()
        }
    }

    private constructor(ctx: Context) {
        context = ctx.applicationContext

        Handler(Looper.getMainLooper()).post {
            cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)!!
            cm.addPrimaryClipChangedListener { onClipboardChanged() }
        }

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Log.d(TAG, "Registered Shizuku binder listener")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register Shizuku binder listener", t)
        }

        ensureClipboardMonitor()
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    private fun hasReadLogsPermission(): Boolean {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun isShizukuAvailableAndAuthorized(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureClipboardMonitor() {
        val hasReadLogs = hasReadLogsPermission()
        val binderReceived = shizukuBinderReceived
        val shizukuAvailable = if (binderReceived) isShizukuAvailable() else false
        val shizukuAuthorized = if (binderReceived) isShizukuAvailableAndAuthorized() else false

        Log.d(
            TAG,
            "ensureClipboardMonitor: readLogs=$hasReadLogs binderReceived=$binderReceived shizukuAvailable=$shizukuAvailable shizukuAuthorized=$shizukuAuthorized"
        )

        if (shizukuAuthorized) {
            startShizukuClipboardMonitor()
            stopReadLogsListener()
            return
        }

        if (hasReadLogs) {
            startReadLogsListener()
        }

        if (!binderReceived) {
            Log.d(TAG, "Shizuku binder not received yet")
            return
        }

        if (shizukuAvailable) {
            requestShizukuPermission()
        } else {
            Log.d(TAG, "Shizuku binder unavailable")
        }
    }

    private fun requestShizukuPermission() {
        if (shizukuPermissionRequestInFlight) {
            Log.d(TAG, "Shizuku permission request already in flight")
            return
        }

        shizukuPermissionRequestInFlight = true
        Log.d(TAG, "Requesting Shizuku permission")

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return

                Shizuku.removeRequestPermissionResultListener(this)
                shizukuPermissionRequestInFlight = false

                Log.d(TAG, "Shizuku permission result: $grantResult")

                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    startShizukuClipboardMonitor()
                    stopReadLogsListener()
                }
            }
        }

        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            shizukuPermissionRequestInFlight = false
            Log.w(TAG, "Shizuku.requestPermission failed", t)
        }
    }

    private fun clipboardLogcatFilter(): String {
        return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            "E ClipboardService"
        } else {
            "ClipboardService:E"
        }
    }

    private fun clipboardLogcatSince(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    private fun startReadLogsListener() {
        val existing = readLogsWorker
        if (existing != null && existing.isAlive) return

        readLogsWorker = Thread({
            var localProcess: Process? = null
            try {
                localProcess = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-T", clipboardLogcatSince(), clipboardLogcatFilter(), "*:S")
                )
                readLogsProcess = localProcess

                BufferedReader(InputStreamReader(localProcess.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        if (line.contains(BuildConfig.APPLICATION_ID)) {
                            Log.d(TAG, "READ_LOGS fallback observed denial; launching floating activity")
                            mainHandler.post {
                                try {
                                    context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                                } catch (t: Throwable) {
                                    Log.w(TAG, "READ_LOGS fallback launch failed", t)
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "READ_LOGS fallback watcher failed", t)
            } finally {
                try {
                    localProcess?.destroy()
                } catch (_: Throwable) {
                }

                if (readLogsProcess === localProcess) {
                    readLogsProcess = null
                }
                readLogsWorker = null
            }
        }, "kdeconnect-clipboard-readlogs").apply {
            isDaemon = true
            start()
        }

        Log.d(TAG, "Started READ_LOGS clipboard fallback")
    }

    private fun stopReadLogsListener() {
        try {
            readLogsWorker?.interrupt()
        } catch (_: Throwable) {
        }

        try {
            readLogsProcess?.destroy()
        } catch (_: Throwable) {
        }

        readLogsWorker = null
        readLogsProcess = null
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(context, ClipboardMonitorUserService::class.java)
        )
            .daemon(false)
            .tag("clipboard_monitor_v4")
            .processNameSuffix("clipboard_monitor_v4")
            .version(4)
            .debuggable(BuildConfig.DEBUG)
    }

    private fun startShizukuClipboardMonitor() {
        if (shizukuService != null || shizukuBindingInFlight) {
            Log.d(TAG, "Shizuku clipboard monitor already started/binding")
            return
        }

        shizukuBindingInFlight = true
        Log.d(TAG, "Binding Shizuku user service")

        try {
            Shizuku.bindUserService(userServiceArgs(), userServiceConnection)
        } catch (t: Throwable) {
            shizukuBindingInFlight = false
            Log.w(TAG, "Shizuku.bindUserService failed", t)
        }
    }

    fun onClipboardChanged() {
        try {
            val clip = cm.primaryClip!!
            val item = clip.getItemAt(0)
            val content = item.coerceToText(context).toString()
            val contentType = detectContentType(clip)

            if (content == currentContent && contentType == currentContentType) {
                return
            }
            updateTimestamp = System.currentTimeMillis()
            currentContent = content
            currentContentType = contentType

            for (observer in observers) {
                observer.clipboardChanged(content, contentType)
            }
        } catch (_: Exception) {
            // probably clipboard was not text or access was denied
        }
    }

    @Suppress("DEPRECATION")
    fun setText(text: String?) {
        if (this::cm.isInitialized) {
            updateTimestamp = System.currentTimeMillis()
            currentContent = text
            currentContentType = ClipboardContentType.Text
            cm.text = text
        }
    }

    companion object {
        private const val TAG = "KDEClipboard"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

        private var _instance: ClipboardListener? = null

        @JvmStatic
        fun instance(context: Context): ClipboardListener {
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }

        @JvmStatic
        fun detectContentType(clip: ClipData?): ClipboardContentType {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return ClipboardContentType.Text
            }
            if (clip?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return ClipboardContentType.Password
            }
            return ClipboardContentType.Text
        }
    }
}
