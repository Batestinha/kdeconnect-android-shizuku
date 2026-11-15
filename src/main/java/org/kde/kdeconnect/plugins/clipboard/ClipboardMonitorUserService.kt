package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Clipboard monitor running inside a privileged Shizuku user service (shell UID).
 *
 * Modern Android (11+) only delivers ClipboardManager change notifications to
 * callers that are themselves allowed to read the clipboard, so an app in the
 * background never learns that the clipboard changed. The shell identity *is*
 * allowed to read it in the background (android.permission.READ_CLIPBOARD_IN_BACKGROUND),
 * therefore this service registers its own IOnPrimaryClipChangedListener as
 * "com.android.shell" instead of relying on the app's listener, and reads the
 * clipboard whenever the notification arrives.
 *
 * A low-frequency poll acts as a safety net on devices where the privileged
 * listener cannot be registered (or never fires). Duplicates are suppressed.
 */
class ClipboardMonitorUserService @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") context: android.content.Context? = null
) : IClipboardMonitorService.Stub() {

    @Volatile
    private var callback: IClipboardMonitorCallback? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var clipboardInterface: Any? = null

    @Volatile
    private var listenerInstalled = false

    @Volatile
    private var lastDeliveredClipboard: ClipboardSnapshot? = null

    private val lock = Any()

    private data class ClipboardSnapshot(
        val text: String,
        val isSensitive: Boolean,
    )

    override fun start(callback: IClipboardMonitorCallback?) {
        synchronized(lock) {
            this.callback = callback

            val existing = worker
            if (existing != null && existing.isAlive) {
                Log.d(TAG, "start(): worker already alive, callback updated")
                return
            }

            running = true
            worker = Thread(
                { runLoop() },
                "kdeconnect-clipboard-monitor"
            ).apply {
                isDaemon = true
                start()
            }

            Log.d(TAG, "start(): privileged clipboard monitor started")
        }
    }

    private fun runLoop() {
        Log.d(
            TAG,
            "runLoop(): starting privileged monitor uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}"
        )

        var clipboard = openClipboard()
        if (clipboard != null) {
            clipboardInterface = clipboard
            listenerInstalled = tryRegisterClipboardListener(clipboard)
            Log.d(TAG, "runLoop(): listener registered=$listenerInstalled")
            // Treat the current clipboard as already known: only later changes are propagated.
            lastDeliveredClipboard = tryReadClipboardPrivileged(clipboard)
        } else {
            Log.w(TAG, "runLoop(): clipboard service not available yet")
        }

        while (running) {
            if (clipboard == null) {
                sleepQuietly(RETRY_INTERVAL_MS)
                if (!running) break
                clipboard = openClipboard()
                if (clipboard == null) continue
                clipboardInterface = clipboard
                listenerInstalled = tryRegisterClipboardListener(clipboard)
                Log.d(TAG, "runLoop(): clipboard service acquired, listener=$listenerInstalled")
                lastDeliveredClipboard = tryReadClipboardPrivileged(clipboard)
                continue
            }

            sleepQuietly(if (listenerInstalled) SAFETY_POLL_INTERVAL_MS else FALLBACK_POLL_INTERVAL_MS)
            if (!running) break

            val snapshot = tryReadClipboardPrivileged(clipboard)
            if (snapshot != null) deliver(snapshot)
        }

        Log.d(TAG, "runLoop(): stopped")
    }

    private fun deliver(snapshot: ClipboardSnapshot) {
        val cb = callback ?: return
        if (snapshot == lastDeliveredClipboard) return
        lastDeliveredClipboard = snapshot
        try {
            cb.onClipboardTextChanged(snapshot.text, snapshot.isSensitive)
            Log.d(
                TAG,
                "delivered clipboard text (length=${snapshot.text.length}, sensitive=${snapshot.isSensitive})"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Callback delivery failed", t)
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Registers [IOnPrimaryClipChangedListener] with the shell package name so the
     * system dispatches clipboard changes to this process, exactly like scrcpy does.
     */
    private fun tryRegisterClipboardListener(clipboard: Any): Boolean {
        return try {
            val listenerInterface = Class.forName("android.content.IOnPrimaryClipChangedListener")

            val localBinder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == TRANSACTION_CLIP_CHANGED) {
                        val current = clipboardInterface
                        if (current != null) {
                            val snapshot = tryReadClipboardPrivileged(current)
                            if (snapshot != null) deliver(snapshot)
                        }
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }

            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "asBinder" -> localBinder
                    "toString" -> "KDEConnectClipboardListener"
                    "hashCode" -> System.identityHashCode(localBinder)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }

            val listener = Proxy.newProxyInstance(
                listenerInterface.classLoader ?: javaClass.classLoader,
                arrayOf<Class<*>>(listenerInterface),
                handler
            )

            val addMethod = clipboard.javaClass.methods
                .filter { it.name == "addPrimaryClipChangedListener" && it.parameterCount > 0 }
                .minByOrNull { it.parameterCount }
                ?: return false

            val methodArgs = buildArgsFor(addMethod.parameterTypes) ?: return false
            methodArgs[0] = listener

            addMethod.invoke(clipboard, *methodArgs)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Privileged clipboard listener registration failed", t)
            false
        }
    }

    private fun openClipboard(): Any? {
        val binder = getClipboardBinder() ?: return null
        return getClipboardInterface(binder)
    }

    private fun tryReadClipboardPrivileged(clipboard: Any): ClipboardSnapshot? {
        val methods = clipboard.javaClass.methods
            .filter { it.name == "getPrimaryClip" }
            .sortedByDescending { it.parameterCount }

        for (method in methods) {
            val args = buildArgsFor(method.parameterTypes) ?: continue
            try {
                val clip = method.invoke(clipboard, *args) as? ClipData ?: continue
                val text = clipToText(clip) ?: continue
                return ClipboardSnapshot(text, isSensitive(clip))
            } catch (t: Throwable) {
                Log.d(TAG, "getPrimaryClip(${method.parameterTypes.joinToString { it.simpleName }}) failed: $t")
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

    /**
     * Fills a binder argument list: the first String argument is the shell package
     * name (the caller identity that is allowed to read the clipboard in the
     * background), every other String is null, ints are 0 (user 0 / default
     * display device) and object arguments stay null.
     */
    private fun buildArgsFor(paramTypes: Array<Class<*>>): Array<Any?>? {
        val args = arrayOfNulls<Any>(paramTypes.size)
        var stringIndex = 0

        for (i in paramTypes.indices) {
            val type = paramTypes[i]
            args[i] = when {
                type == String::class.java -> if (stringIndex++ == 0) SHELL_PACKAGE else null
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Boolean::class.javaPrimitiveType -> false
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
            running = false
            try {
                worker?.interrupt()
            } catch (_: Throwable) {
            }

            worker = null
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
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val TRANSACTION_CLIP_CHANGED = 1
        private const val SAFETY_POLL_INTERVAL_MS = 2000L
        private const val FALLBACK_POLL_INTERVAL_MS = 700L
        private const val RETRY_INTERVAL_MS = 1000L
    }
}
