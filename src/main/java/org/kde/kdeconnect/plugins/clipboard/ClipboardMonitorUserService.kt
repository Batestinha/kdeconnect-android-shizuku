package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.IBinder
import android.util.Log
import org.kde.kdeconnect_tp.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardMonitorUserService @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") context: android.content.Context? = null
) : IClipboardMonitorService.Stub() {

    @Volatile
    private var callback: IClipboardMonitorCallback? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var process: Process? = null

    @Volatile
    private var lastDeliveredClipboard: ClipboardSnapshot? = null

    private val lock = Any()

    private data class ClipboardSnapshot(
        val text: String,
        val isSensitive: Boolean,
    )

    private fun clipboardLogcatFilter(): String {
        return if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            "E ClipboardService"
        } else {
            "ClipboardService:E"
        }
    }

    private fun clipboardLogcatSince(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    override fun start(callback: IClipboardMonitorCallback?) {
        synchronized(lock) {
            this.callback = callback

            val existing = worker
            if (existing != null && existing.isAlive) {
                Log.d(TAG, "start(): worker already alive")
                return
            }

            Log.d(TAG, "start(): launching logcat watcher")
            worker = Thread(
                { runLoop() },
                "kdeconnect-clipboard-monitor"
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runLoop() {
        while (!Thread.currentThread().isInterrupted) {
            var localProcess: Process? = null

            try {
                Log.d(
                    TAG,
                    "runLoop(): starting watcher uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}"
                )

                localProcess = ProcessBuilder(
                    "logcat",
                    "-T", clipboardLogcatSince(),
                    clipboardLogcatFilter(),
                    "*:S"
                )
                    .redirectErrorStream(true)
                    .start()

                synchronized(lock) {
                    process = localProcess
                }

                BufferedReader(InputStreamReader(localProcess.inputStream)).useLines { lines ->
                    for (line in lines) {
                        if (Thread.currentThread().isInterrupted) break
                        if (!line.contains(BuildConfig.APPLICATION_ID)) continue

                        Log.d(TAG, "Observed denial line: $line")

                        val clipboard = tryReadClipboardPrivileged()
                        if (clipboard == null) {
                            Log.w(TAG, "Privileged clipboard read returned null")
                            continue
                        }

                        if (clipboard == lastDeliveredClipboard) {
                            Log.d(TAG, "Clipboard text unchanged; suppressing duplicate callback")
                            continue
                        }

                        lastDeliveredClipboard = clipboard

                        try {
                            callback?.onClipboardTextChanged(clipboard.text, clipboard.isSensitive)
                            Log.d(TAG, "Delivered clipboard text to app callback")
                        } catch (t: Throwable) {
                            Log.w(TAG, "Callback delivery failed", t)
                        }
                    }
                }

                try {
                    localProcess.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                if (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "runLoop() failure", t)
                if (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(1500)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            } finally {
                try {
                    localProcess?.destroy()
                } catch (_: Throwable) {
                }

                synchronized(lock) {
                    if (process === localProcess) {
                        process = null
                    }
                }
            }
        }
    }

    private fun tryReadClipboardPrivileged(): ClipboardSnapshot? {
        val binder = getClipboardBinder() ?: run {
            Log.w(TAG, "Clipboard binder is null")
            return null
        }

        val clipboard = getClipboardInterface(binder) ?: run {
            Log.w(TAG, "IClipboard interface is null")
            return null
        }

        val methods = clipboard.javaClass.methods
            .filter { it.name == "getPrimaryClip" }
            .sortedByDescending { it.parameterCount }

        for (pkg in listOf("com.android.shell", BuildConfig.APPLICATION_ID)) {
            for (method in methods) {
                val args = buildArgsFor(method.parameterTypes, pkg) ?: continue
                try {
                    val clip = method.invoke(clipboard, *args) as? ClipData ?: continue
                    val text = clipToText(clip) ?: continue
                    Log.d(
                        TAG,
                        "Privileged clipboard read succeeded via ${method.parameterTypes.joinToString { it.simpleName }} pkg=$pkg"
                    )
                    return ClipboardSnapshot(text, isSensitive(clip))
                } catch (_: Throwable) {
                }
            }
        }

        return null
    }

    private fun isSensitive(clip: ClipData): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false
        return clip.description.extras
            ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
    }

    private fun getClipboardBinder(): IBinder? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        return getService.invoke(null, "clipboard") as? IBinder
    }

    private fun getClipboardInterface(binder: IBinder): Any? {
        val stub = Class.forName("android.content.IClipboard\$Stub")
        val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
    }

    private fun buildArgsFor(paramTypes: Array<Class<*>>, opPkg: String): Array<Any?>? {
        val args = arrayOfNulls<Any>(paramTypes.size)

        for (i in paramTypes.indices) {
            val type = paramTypes[i]

            args[i] = when {
                type == String::class.java -> {
                    if (i == 0) opPkg else null
                }
                type.name == "int" || type == Int::class.javaObjectType -> 0
                type.name == "long" || type == Long::class.javaObjectType -> 0L
                type.name == "boolean" || type == Boolean::class.javaObjectType -> false
                !type.isPrimitive -> null
                else -> return null
            }
        }

        return args
    }

    private fun clipToText(clip: ClipData): String? {
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0)
        return item.text?.toString()
            ?: item.htmlText
            ?: item.uri?.toString()
            ?: item.intent?.toUri(0)
    }

    override fun stop() {
        synchronized(lock) {
            try {
                worker?.interrupt()
            } catch (_: Throwable) {
            }

            try {
                process?.destroy()
            } catch (_: Throwable) {
            }

            worker = null
            process = null
            callback = null
            lastDeliveredClipboard = null
            Log.d(TAG, "stop(): stopped")
        }
    }

    override fun destroy() {
        Log.d(TAG, "destroy()")
        stop()
        System.exit(0)
    }

    companion object {
        private const val TAG = "KDEClipboardService"
    }
}
