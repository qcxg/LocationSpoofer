package com.shiraka.locatiobprovid.xposed

import io.github.libxposed.api.XposedInterface
import android.net.Uri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONObject
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.lang.reflect.Member
import com.shiraka.locatiobprovid.utils.EnvironmentCoveragePolicy

// --- Legacy Compatibility Layer ---
abstract class XC_MethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) {}
    open fun afterHookedMethod(param: MethodHookParam) {}

    class MethodHookParam {
        var method: Member? = null
        var thisObject: Any? = null
        var args: Array<Any?> = emptyArray()
        var returnEarly = false
        var result: Any? = null
            set(value) {
                field = value
                returnEarly = true
            }
        var throwable: Throwable? = null
            set(value) {
                field = value
                returnEarly = true
            }
    }
}

object XposedHelpers {
    lateinit var module: XposedModule

    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        return Class.forName(className, false, classLoader ?: ClassLoader.getSystemClassLoader())
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return try { findClass(className, classLoader) } catch (e: Throwable) { null }
    }

    fun getObjectField(obj: Any, fieldName: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(obj)
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException(fieldName)
    }

    fun setObjectField(obj: Any, fieldName: String, value: Any?) {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException(fieldName)
    }

    fun setIntField(obj: Any, fieldName: String, value: Int) { setObjectField(obj, fieldName, value) }
    fun setDoubleField(obj: Any, fieldName: String, value: Double) { setObjectField(obj, fieldName, value) }
    fun setFloatField(obj: Any, fieldName: String, value: Float) { setObjectField(obj, fieldName, value) }
    fun setBooleanField(obj: Any, fieldName: String, value: Boolean) { setObjectField(obj, fieldName, value) }
    fun setLongField(obj: Any, fieldName: String, value: Long) { setObjectField(obj, fieldName, value) }

    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (m in clazz.declaredMethods) {
                if (m.name == methodName && m.parameterCount == args.size) {
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                }
            }
            clazz = clazz.superclass
        }
        throw NoSuchMethodException(methodName)
    }

    fun tryCallMethodDeep(obj: Any, methodName: String, vararg args: Any?): Boolean {
        val visited = HashSet<Class<*>>()
        val stack = ArrayDeque<Class<*>>()
        stack.add(obj.javaClass)
        while (stack.isNotEmpty()) {
            val clazz = stack.removeFirst()
            if (!visited.add(clazz)) continue
            for (m in clazz.declaredMethods) {
                if (m.name != methodName || m.parameterCount != args.size) continue
                val types = m.parameterTypes
                var compatible = true
                for (i in args.indices) {
                    if (!isCompatibleParameter(types[i], args[i])) {
                        compatible = false
                        break
                    }
                }
                if (!compatible) continue
                try {
                    m.isAccessible = true
                    m.invoke(obj, *args)
                    return true
                } catch (_: Throwable) {
                }
            }
            clazz.superclass?.let { stack.add(it) }
            clazz.interfaces.forEach { stack.add(it) }
        }
        return false
    }

    private fun isCompatibleParameter(parameterType: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !parameterType.isPrimitive
        val argType = arg.javaClass
        if (parameterType.isAssignableFrom(argType)) return true
        if (!parameterType.isPrimitive) return false
        return when (parameterType) {
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Long.TYPE -> arg is Long
            java.lang.Boolean.TYPE -> arg is Boolean
            java.lang.Float.TYPE -> arg is Float
            java.lang.Double.TYPE -> arg is Double
            java.lang.Short.TYPE -> arg is Short
            java.lang.Byte.TYPE -> arg is Byte
            java.lang.Character.TYPE -> arg is Char
            else -> false
        }
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == methodName && m.parameterCount == args.size && java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                    m.isAccessible = true
                    return m.invoke(null, *args)
                }
            }
            c = c.superclass
        }
        throw NoSuchMethodException(methodName)
    }

    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(methodName, *parameterTypes)
                m.isAccessible = true
                return m
            } catch (e: NoSuchMethodException) {
                c = c.superclass
            }
        }
        throw NoSuchMethodException(methodName)
    }

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        for (c in clazz.declaredConstructors) {
            if (c.parameterCount == args.size) {
                c.isAccessible = true
                return c.newInstance(*args)
            }
        }
        throw NoSuchMethodException("Constructor for " + clazz.name + " not found")
    }

    fun findAndHookMethod(className: String, classLoader: ClassLoader?, methodName: String, vararg args: Any?) {
        try {
            val clazz = findClass(className, classLoader)
            findAndHookMethod(clazz, methodName, *args)
        } catch (e: Throwable) {
            // log
        }
    }

    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg args: Any?) {
        val hookIndex = args.indexOfLast { it is XC_MethodHook }
        if (hookIndex == -1) return
        val callback = args[hookIndex] as XC_MethodHook
        val paramTypes = args.slice(0 until hookIndex).map {
            when (it) {
                is Class<*> -> it
                is String -> findClass(it, clazz.classLoader)
                else -> throw IllegalArgumentException("Invalid argument type")
            }
        }.toTypedArray()

        val method = findMethodExact(clazz, methodName, *paramTypes)
        hookMethod(method, callback)
    }

    fun hookMethod(executable: java.lang.reflect.Executable, callback: XC_MethodHook) {
        module.hook(executable).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
            override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                val param = XC_MethodHook.MethodHookParam().apply {
                    this.method = executable
                    this.thisObject = chain.thisObject
                    this.args = chain.args.toTypedArray()
                }

                val beforeArgs = param.args.copyOf()
                val beforeResult = param.result
                val beforeThrowable = param.throwable
                val beforeReturnEarly = param.returnEarly
                try {
                    callback.beforeHookedMethod(param)
                } catch (e: Throwable) {
                    // A compatibility callback is module code, not part of the hooked method.
                    // Never turn a callback bug into an exception in the host process (and, in
                    // particular, never let it crash system_server). Restore the complete state
                    // from before the callback and continue with the original method.
                    param.args = beforeArgs
                    param.result = beforeResult
                    param.throwable = beforeThrowable
                    param.returnEarly = beforeReturnEarly
                    logCallbackFailure("before", executable, e)
                }

                if (!param.returnEarly) {
                    try {
                        param.result = chain.proceed(param.args)
                    } catch (e: Throwable) {
                        param.throwable = e
                    }
                }

                val afterArgs = param.args.copyOf()
                val afterResult = param.result
                val afterThrowable = param.throwable
                val afterReturnEarly = param.returnEarly
                try {
                    callback.afterHookedMethod(param)
                } catch (e: Throwable) {
                    // Preserve the original method's result/throwable when an after callback
                    // fails. This matches the isolation expected from Xposed callbacks.
                    param.args = afterArgs
                    param.result = afterResult
                    param.throwable = afterThrowable
                    param.returnEarly = afterReturnEarly
                    logCallbackFailure("after", executable, e)
                }

                if (param.throwable != null) throw param.throwable!!
                return param.result
            }
        })
    }

    private fun logCallbackFailure(
        phase: String,
        executable: java.lang.reflect.Executable,
        error: Throwable
    ) {
        try {
            val message = "Hook $phase callback failed for ${executable.declaringClass.name}.${executable.name}"
            XposedBridge.logEvery(
                "callback-failure:$phase:${executable.declaringClass.name}.${executable.name}:${error.javaClass.name}",
                "$message: ${error.javaClass.simpleName}: ${error.message}",
                60_000L
            )
        } catch (_: Throwable) {
            // Logging must never become another host-process failure path.
        }
    }
}

object XposedBridge {
    private val openCellLogLastTimes = ConcurrentHashMap<String, Long>()
    private val generalLogLastTimes = ConcurrentHashMap<String, Long>()
    private val dynamicLogToken = Regex("-?\\d+(?:\\.\\d+)?")
    private const val MAX_LOG_KEYS = 256

    fun log(msg: String) {
        android.util.Log.i("LocationSpoofer_Xposed", msg)
    }
    fun logEvery(key: String, msg: String, intervalMs: Long = 60_000L) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = generalLogLastTimes[key]
        if (last == null || now - last >= intervalMs) {
            if (generalLogLastTimes.size >= MAX_LOG_KEYS && last == null) {
                generalLogLastTimes.clear()
            }
            generalLogLastTimes[key] = now
            log(msg)
        }
    }
    fun logOpenCellId(msg: String) {
        val key = dynamicLogToken.replace(msg, "#").take(160)
        logOpenCellIdEvery("auto:$key", msg, 60_000L)
    }
    fun logOpenCellId(msg: String, t: Throwable) {
        val key = dynamicLogToken.replace(msg, "#").take(120)
        logOpenCellIdEvery(
            "error:$key:${t.javaClass.name}",
            "$msg: ${t.javaClass.simpleName}: ${t.message}",
            60_000L
        )
    }
    fun logOpenCellIdEvery(key: String, msg: String, intervalMs: Long = 60_000L) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = openCellLogLastTimes[key]
        if (last == null || now - last >= intervalMs) {
            if (openCellLogLastTimes.size >= MAX_LOG_KEYS && last == null) {
                openCellLogLastTimes.clear()
            }
            openCellLogLastTimes[key] = now
            android.util.Log.d("OpenCellID", "[XposedCell] $msg")
        }
    }
    fun log(t: Throwable) {
        val site = t.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}" } ?: "unknown"
        logEvery(
            "throwable:${t.javaClass.name}:$site",
            "[LocationSpoofer] ${t.javaClass.simpleName}: ${t.message}",
            60_000L
        )
    }
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook) {
        for (m in clazz.declaredMethods) {
            if (m.name == methodName) {
                if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
                XposedHelpers.hookMethod(m, callback)
            }
        }
    }
}

class LocationHooker : XposedModule() {
    private val allowedHookPackages = setOf(
        "android",
        "system",
        "com.google.android.gms",
        "com.android.location.fused",
        "com.android.gpstest",
        "make.more.r2d2.cellular_z.play"
    )
    private val allowedGmsProcesses = setOf(
        "com.google.android.gms",
        "com.google.android.gms.persistent"
    )

    init {
        XposedHelpers.module = this
    }

    @Volatile
    private var xsharedPrefs: Any? = null
    @Volatile
    private var xsharedPreferencesInitializationAttempted = false

    private val nmeaTimers = ConcurrentHashMap<Any, java.util.Timer>()
    private val nmeaListenerProxies = ConcurrentHashMap<Any, Any>()
    private val hookedCallbackClasses = ConcurrentHashMap<Class<*>, Boolean>()
    private val dynamicallyHookedMethods = ConcurrentHashMap<java.lang.reflect.Executable, Boolean>()
    @Volatile
    private var currentPackageName: String = ""
    private val processHookLock = Any()
    @Volatile
    private var processHooksInstalled = false

    private fun hookDynamicMethodOnce(
        clazz: Class<*>,
        methodName: String,
        parameterMatcher: (java.lang.reflect.Method) -> Boolean,
        callback: XC_MethodHook
    ) {
        val candidates = ArrayList<java.lang.reflect.Method>()
        var cursor: Class<*>? = clazz
        while (cursor != null && cursor != Any::class.java) {
            val current = cursor
            if (current.name == "android.telephony.PhoneStateListener" ||
                current.name == "android.telephony.TelephonyCallback"
            ) break
            val currentMatches = runCatching { current.declaredMethods }
                .getOrDefault(emptyArray())
                .filter { method ->
                    method.name == methodName &&
                        !java.lang.reflect.Modifier.isAbstract(method.modifiers) &&
                        runCatching { parameterMatcher(method) }.getOrDefault(false)
                }
            if (currentMatches.isNotEmpty()) {
                // Virtual dispatch enters the nearest concrete override. Hooking every parent
                // implementation as well only broadens the interception surface and can apply
                // the same rewrite twice when an override calls super.
                candidates += currentMatches
                break
            }
            cursor = current.superclass
        }
        for (method in candidates) {
            if (dynamicallyHookedMethods.putIfAbsent(method, true) == null) {
                try {
                    XposedHelpers.hookMethod(method, callback)
                } catch (error: Throwable) {
                    dynamicallyHookedMethods.remove(method, true)
                    XposedBridge.logOpenCellIdEvery(
                        "dynamic-hook:${method.declaringClass.name}.${method.name}:${error.javaClass.name}",
                        "Dynamic callback hook failed for ${method.declaringClass.name}.${method.name}: ${error.javaClass.simpleName}: ${error.message}",
                        60_000L
                    )
                }
            }
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // Nothing here for now
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        val classLoader = param.classLoader
        handleLoadPackage(pkg, classLoader)
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (!claimProcessHookInstallation("android")) return
        XposedBridge.log("[LocationSpoofer] Hooking system_server")
        installProcessHooks(param.classLoader, "android")
    }
    
    // --- Original Logic ---


    companion object {

        private const val VERBOSE_CELL_BUILD_LOGS = false
        private const val EARTH_RADIUS_METERS = 6378137.0
        private const val DEFAULT_JITTER_RADIUS_METERS = 30.0
        private const val MIN_JITTER_RADIUS_METERS = 1.0
        private const val MAX_JITTER_RADIUS_METERS = 80.0
    }

    fun handleLoadPackage(pkg: String, classLoader: ClassLoader) {
        // 宿主App自报平安
        if (pkg == "com.shiraka.locatiobprovid") {
            return // 宿主App不需要注入定位Hook
        }
        // LSPosed keeps a user's previous scope selection across module updates.
        // Enforce the stable scope in code so an old broad selection cannot keep
        // injecting radio hooks into unrelated applications.
        if (pkg !in allowedHookPackages) return
        if (!claimProcessHookInstallation(pkg)) return
        XposedBridge.log("[LocationSpoofer] Hooking package: $pkg")
        XposedBridge.logOpenCellId("handleLoadPackage pkg=$pkg classLoader=$classLoader")
        installProcessHooks(classLoader, pkg)
    }

    private fun claimProcessHookInstallation(pkg: String): Boolean {
        synchronized(processHookLock) {
            if (processHooksInstalled) {
                XposedBridge.logEvery(
                    "processHooksAlreadyInstalled:${android.os.Process.myPid()}",
                    "[LocationSpoofer] skipping duplicate hook installation for $pkg in pid=${android.os.Process.myPid()} (owner=$currentPackageName)"
                )
                return false
            }
            processHooksInstalled = true
            currentPackageName = pkg
            return true
        }
    }

    private fun installProcessHooks(
        classLoader: ClassLoader,
        pkg: String
    ) {
        // Framework/infrastructure processes must never receive client-side radio or
        // connectivity hooks. Those hooks cannot rewrite Binder results for unscoped apps,
        // but they can corrupt the framework's own Wi-Fi/telephony/network decisions.
        if (pkg == "android" || pkg == "system") {
            hookSystemLocationProviderPipeline(classLoader, pkg)
            return
        }

        // Telephony and NetworkStack are infrastructure rather than consumers. Client-side
        // TelephonyManager/WifiManager hooks belong in GMS, fused, and explicitly scoped
        // diagnostic/consumer processes only.
        if (pkg == "com.android.phone" || pkg == "com.android.networkstack") return
        if (pkg == "com.google.android.gms" && currentProcessName() !in allowedGmsProcesses) return

        hookLocationAPIs(classLoader, pkg)
        hookWifiEnvironment(classLoader)
        hookCellEnvironment(classLoader)
        hookGnssStatus(classLoader)
        // Magnetic-field sensor rewriting is intentionally not part of the stable pipeline.
        // It can lower compass confidence when the physical sensor calibration disagrees with
        // the calculated geomagnetic model, so keep compass data untouched by default.
    }

    private fun currentProcessName(): String {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.app.Application.getProcessName()
            } else {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentProcessName")
                    .invoke(null) as? String
            }
        }
            .getOrNull()
            .orEmpty()
    }

    /**
     * ★ 反检测: 隐藏Xposed环境,防止反作弊SDK检测到Hook
     *
     * 设计原则:
     * 1. 只使用精确匹配,绝不使用宽泛的contains/startsWith,避免误杀正常类
     * 2. 不Hook ClassLoader.loadClass的宽泛模式(会导致App卡死)
     * 3. 不Hook BufferedReader.readLine(开销巨大)
     * 4. 不Hook File.exists/Runtime.exec(干扰正常功能)
     */
    private fun hookAntiDetection(classLoader: ClassLoader) {

        // ── 1. 堆栈帧过滤 ──
        // 反作弊SDK通过getStackTrace()检查调用链,发现Xposed帧即判定为Hook环境
        // 只过滤精确匹配的Xposed类名,不影响正常堆栈
        val xposedClassNames = setOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XC_MethodHook",
            "de.robv.android.xposed.XC_MethodReplacement",
            "de.robv.android.xposed.XposedHelpers",
            "de.robv.android.xposed.XC_MethodHook\$MethodHookParam",
            "io.github.libxposed.api.XposedModule",
            "io.github.libxposed.api.XposedInterface",
            "io.github.libxposed.api.XposedModuleInterface",
            "org.lsposed.manager.MainApplication",
            "io.github.lsposed.manager.App"
        )

        try {
            XposedHelpers.findAndHookMethod(
                "java.lang.Throwable", classLoader, "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rawStackTrace = param.result as? Array<*> ?: return
                        val stackTrace = rawStackTrace.filterIsInstance<StackTraceElement>()
                        val filtered = stackTrace.filter { elem ->
                            elem.className !in xposedClassNames
                        }.toTypedArray()
                        if (filtered.size != rawStackTrace.size) {
                            param.result = filtered
                        }
                    }
                })
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                "java.lang.Thread", classLoader, "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rawStackTrace = param.result as? Array<*> ?: return
                        val stackTrace = rawStackTrace.filterIsInstance<StackTraceElement>()
                        val filtered = stackTrace.filter { elem ->
                            elem.className !in xposedClassNames
                        }.toTypedArray()
                        if (filtered.size != rawStackTrace.size) {
                            param.result = filtered
                        }
                    }
                })
        } catch (_: Throwable) {}

        // ── 2. Class.forName 精确匹配 ──
        // 反作弊SDK通过Class.forName()尝试加载Xposed类,成功则判定为Hook环境
        // 使用精确匹配(不是contains),只拦截已知Xposed类名
        try {
            XposedHelpers.findAndHookMethod(
                "java.lang.Class", classLoader, "forName",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                ClassLoader::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        if (className in xposedClassNames) {
                            throw ClassNotFoundException()
                        }
                    }
                })
        } catch (_: Throwable) {
            // 降级: 尝试2参数版本
            try {
                XposedHelpers.findAndHookMethod(
                    "java.lang.Class", classLoader, "forName",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val className = param.args[0] as? String ?: return
                            if (className in xposedClassNames) {
                                throw ClassNotFoundException()
                            }
                        }
                    })
            } catch (_: Throwable) {}
        }

        // ── 3. ClassLoader.loadClass 精确匹配 ──
        // 同样使用精确匹配,只拦截已知Xposed类名
        // loadClass被调用频率很高,精确匹配确保零误杀
        try {
            XposedHelpers.findAndHookMethod(
                "java.lang.ClassLoader", classLoader, "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        if (className in xposedClassNames) {
                            throw ClassNotFoundException()
                        }
                    }
                })
        } catch (_: Throwable) {}

// ── 4. 拦截 AppOpsManager 的 OP_MOCK_LOCATION (58) ──
        // 很多深度定制系统（如 MIUI）和硬核反作弊会检查 AppOps 权限
        try {
            val appOpsHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // AppOps is a broad hot API. Reject every unrelated operation before
                    // consulting even the in-memory spoofing state.
                    val opArg = param.args.getOrNull(0)
                    val isMockOp = if (opArg is Int) {
                        opArg == 58 // OP_MOCK_LOCATION
                    } else if (opArg is String) {
                        opArg == "android:mock_location"
                    } else false
                    if (!isMockOp) return
                    val config = readConfig() ?: return
                    if (!shouldSpoofLocation(config)) return
                    // MODE_IGNORED = 1, MODE_ERRORED = 2
                    param.result = 1 // MODE_IGNORED，让对方以为我们没有被授权模拟位置
                }
            }
            
            val appOpsClass = XposedHelpers.findClass("android.app.AppOpsManager", classLoader)
            try { XposedBridge.hookAllMethods(appOpsClass, "checkOp", appOpsHook) } catch (e: Throwable) {}
            try { XposedBridge.hookAllMethods(appOpsClass, "checkOpNoThrow", appOpsHook) } catch (e: Throwable) {}
            try { XposedBridge.hookAllMethods(appOpsClass, "noteOp", appOpsHook) } catch (e: Throwable) {}
            try { XposedBridge.hookAllMethods(appOpsClass, "noteOpNoThrow", appOpsHook) } catch (e: Throwable) {}
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 5. 拦截 Settings.Secure 的 mock_location 开关查询 ──
        try {
            val secureHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Settings.Secure serves many unrelated settings. Only the two legacy
                    // mock-location keys are relevant to this anti-detection hook.
                    val name = param.args.getOrNull(1) as? String ?: return
                    if (name != "mock_location" && name != "allow_mock_location") return
                    val config = readConfig() ?: return
                    if (!shouldSpoofLocation(config)) return
                    if (param.method?.name == "getInt") {
                        param.result = 0
                    } else if (param.method?.name == "getString") {
                        param.result = "0"
                    }
                }
            }
            
            val secureClass = XposedHelpers.findClass("android.provider.Settings\$Secure", classLoader)
            try { XposedHelpers.findAndHookMethod(secureClass, "getInt", android.content.ContentResolver::class.java, String::class.java, secureHook) } catch (e: Throwable) {}
            try { XposedHelpers.findAndHookMethod(secureClass, "getInt", android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType, secureHook) } catch (e: Throwable) {}
            try { XposedHelpers.findAndHookMethod(secureClass, "getString", android.content.ContentResolver::class.java, String::class.java, secureHook) } catch (e: Throwable) {}
        } catch (e: Throwable) { XposedBridge.log(e) }

        XposedBridge.log("[LocationSpoofer] Anti-detection hooks installed")
    }

    private var startTimestamp = System.currentTimeMillis()

    // ── GCJ-02 → WGS-84 转换（Xposed模块运行在目标App进程，必须自带转换代码）──
    private val GCJ_A = 6378245.0
    private val GCJ_EE = 0.00669342162296594

    private fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        if (gcjLng < 72.004 || gcjLng > 137.8347 || gcjLat < 0.8293 || gcjLat > 55.8271)
            return Pair(gcjLat, gcjLng)
        val dLat = gcjTransformLat(gcjLng - 105.0, gcjLat - 35.0)
        val dLng = gcjTransformLng(gcjLng - 105.0, gcjLat - 35.0)
        val radLat = gcjLat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - GCJ_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val mLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI)
        val mLng = (dLng * 180.0) / (GCJ_A / sqrtMagic * cos(radLat) * Math.PI)
        return Pair(gcjLat - mLat, gcjLng - mLng)
    }

    private fun gcjTransformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun gcjTransformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    // ── GCJ-02 → BD-09 转换(百度坐标系) ──
    //
    // BD-09是百度在GCJ-02基础上施加的二次偏移坐标系。百度地图/百度定位SDK(BDLocation)
    // 内部期望接收BD-09坐标,若直接传入GCJ-02会产生约100-500米的固定偏移。
    //
    // 算法原理:
    // 1. 将GCJ-02坐标解释为以(0,0)为中心的直角坐标(x=lng, y=lat)
    // 2. 施加百度公开的偏移常量(x偏移0.0065度, y偏移0.006度)
    // 3. 将偏移后的直角坐标转为极坐标(r, theta),其中r=sqrt(x^2+y^2), theta=atan2(y,x)
    // 4. 对极角theta叠加一个与r相关的微小旋转量: theta += BD_PI * sin(r * BD_PI) * 0.000003
    //    BD_PI = pi * 3000/180 ≈ 52.3598..., 这是百度定义的旋转频率系数
    // 5. 对极径r叠加微小伸缩: r += BD_PI * cos(r * BD_PI) * 0.00002
    // 6. 将修正后的极坐标转回直角坐标,即为BD-09经纬度
    //
    // 为何不能省略此转换:
    // BDLocation.getLatitude()被Hook后如果返回GCJ-02坐标,百度SDK内部不会再做转换,
    // 直接将该值作为BD-09渲染到地图上,导致显示位置相对真实位置偏移数百米。

    /** 百度坐标系旋转频率常量: pi * 3000 / 180 */
    private val BD_PI = Math.PI * 3000.0 / 180.0

    /**
     * GCJ-02坐标转BD-09坐标
     *
     * @param gcjLat GCJ-02纬度(高德/腾讯坐标系)
     * @param gcjLng GCJ-02经度
     * @return Pair(BD-09纬度, BD-09经度)
     */
    private fun gcj02ToBd09(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        val x = gcjLng
        val y = gcjLat
        val z = sqrt(x * x + y * y) + 0.00002 * sin(y * BD_PI)
        val theta = Math.atan2(y, x) + 0.000003 * cos(x * BD_PI)
        val bdLng = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
        return Pair(bdLat, bdLng)
    }

    /**
     * 高斯随机游走状态(Xposed进程内独立维护)
     * 使用Ornstein-Uhlenbeck过程: X(t+dt) = X(t) + sigma*sqrt(dt)*N(0,1) - alpha*X(t)*dt
     * 产生白噪声频谱,FFT检测无法发现单频峰
     */
    private val rng = Random()
    private var hookDriftNorthMeters = 0.0
    private var hookDriftEastMeters = 0.0
    private var hookAccuracyDrift = 0.0
    private var hookLastCallTime = 0L
    private var cachedJitterAtElapsedMs = 0L
    private var cachedJitterBaseLat = Double.NaN
    private var cachedJitterBaseLng = Double.NaN
    private var cachedJitterRadius = DEFAULT_JITTER_RADIUS_METERS
    private var cachedJitterSpeed = "MEDIUM"
    private var cachedJitterRfCellEnforced = true
    private var cachedJitteredLocation = Pair(0.0, 0.0)
    private var lastLoggedJitterSpeed = ""
    private val systemLocationHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val systemProviderManagers = ConcurrentHashMap<String, Any>()
    private val systemInjectorLock = Any()
    @Volatile
    private var systemInjectorStarted = false
    @Volatile
    private var systemInjectorTick: Runnable? = null
    private var systemInjectedReportCount = 0L
    // Active GnssStatus injector timers keyed by callback identity hash
    private val gnssStatusInjectors = ConcurrentHashMap<Int, java.util.Timer>()
    private var lastSpoofedWallTimeMs = 0L
    private var lastSpoofedElapsedRealtimeNanos = 0L
    @Volatile
    private var lastSystemLocationCheckAt = 0L
    @Volatile
    private var lastSystemLocationEnabled = false
    @Volatile
    private var cachedProcessContext: android.content.Context? = null
    private data class SpoofedTimestamps(
        val wallTimeMs: Long,
        val elapsedRealtimeNanos: Long
    )

    /** One complete fix. It is created once at the framework pre-filter boundary. */
    private data class SpoofedLocationSample(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val altitude: Double,
        val timestamps: SpoofedTimestamps,
        val provider: String,
        val hasMotion: Boolean,
        val speed: Float,
        val bearing: Float
    )

    private fun nextSpoofedTimestamps(): SpoofedTimestamps {
        synchronized(this) {
            val rawWall = System.currentTimeMillis()
            val rawElapsed = android.os.SystemClock.elapsedRealtimeNanos()
            // Never manufacture a timestamp in the future. Equal timestamps are valid for
            // different providers reported in the same system tick.
            val wall = maxOf(rawWall, lastSpoofedWallTimeMs)
            val elapsed = maxOf(rawElapsed, lastSpoofedElapsedRealtimeNanos)
            lastSpoofedWallTimeMs = wall
            lastSpoofedElapsedRealtimeNanos = elapsed
            return SpoofedTimestamps(wall, elapsed)
        }
    }

    private fun isInfrastructurePackage(pkg: String = currentPackageName): Boolean {
        return pkg == "android" ||
            pkg == "system" ||
            pkg == "com.android.phone" ||
            pkg == "com.android.location.fused" ||
            pkg == "com.android.networkstack" ||
            pkg == "com.android.ons" ||
            pkg.startsWith("com.google.android.gms")
    }

    private fun isGnssDiagnosticPackage(pkg: String = currentPackageName): Boolean {
        return pkg == "make.more.r2d2.cellular_z.play" ||
            pkg == "com.android.gpstest"
    }

    private fun shouldActivelyInjectClientGnss(pkg: String = currentPackageName): Boolean {
        return !isInfrastructurePackage(pkg)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun shouldInstallLegacyMapSdkHooks(pkg: String = currentPackageName): Boolean {
        return false
    }

    private fun standardLocationCoordinates(config: JSONObject): Pair<Double, Double> {
        // Android LocationManager, GNSS and GMS all use WGS-84. Per-app GCJ/BD conversion is
        // only valid at an explicit map-SDK boundary and must never enter system_server.
        return Pair(
            config.optDouble("wgs84_lat", config.optDouble("lat", 0.0)),
            config.optDouble("wgs84_lng", config.optDouble("lng", 0.0))
        )
    }

    private fun createLocationSample(
        config: JSONObject,
        provider: String,
        timestamps: SpoofedTimestamps
    ): SpoofedLocationSample {
        val enableJitter = config.optBoolean("enable_jitter", true)
        // Resolve/jitter in the app's stored coordinate domain first, because
        // EnvironmentCoveragePolicy and the RF database use that same domain.
        // Only then convert the complete sample to Android's WGS-84 contract.
        val appBase = Pair(
            config.optDouble("lat", 0.0),
            config.optDouble("lng", 0.0)
        )
        val jitteredApp = getJitteredLocation(appBase.first, appBase.second)
        val jittered = gcj02ToWgs84(jitteredApp.first, jitteredApp.second)
        val baseAltitude = config.optDouble("altitude", 0.0)
        val altitude = if (enableJitter && baseAltitude > 0.0) {
            baseAltitude + (rng.nextDouble() - 0.5)
        } else {
            baseAltitude
        }
        val hasMotion = hasSpoofedMotion(config)
        return SpoofedLocationSample(
            latitude = jittered.first,
            longitude = jittered.second,
            accuracy = if (enableJitter) getJitteredAccuracy() else 8f,
            altitude = altitude,
            timestamps = timestamps,
            provider = provider,
            hasMotion = hasMotion,
            speed = if (hasMotion) spoofedSpeed(config) else 0f,
            bearing = if (hasMotion) spoofedBearing(config) else 0f
        )
    }

    private fun getJitteredLocation(baseLat: Double, baseLng: Double): Pair<Double, Double> {
        val enableJitter = lastConfig?.optBoolean("enable_jitter", true) ?: true
        if (!enableJitter) {
            // Park every OU component while jitter is disabled. Keeping the old drift would
            // make the first fix after re-enabling jump back toward a previous random point.
            hookDriftNorthMeters = 0.0
            hookDriftEastMeters = 0.0
            hookAccuracyDrift = 0.0
            hookLastCallTime = 0L
            cachedJitterAtElapsedMs = 0L
            cachedJitterBaseLat = baseLat
            cachedJitterBaseLng = baseLng
            cachedJitterRfCellEnforced = shouldConstrainJitterToRfCell()
            cachedJitteredLocation = Pair(baseLat, baseLng)
            return cachedJitteredLocation
        }
        val jitterRadius = jitterRadiusMeters()
        val profile = jitterProfile()
        val enforceRfCell = shouldConstrainJitterToRfCell()
        if (lastLoggedJitterSpeed != profile.speedName) {
            lastLoggedJitterSpeed = profile.speedName
            XposedBridge.log(
                "[LocationSpoofer] jitter profile=${profile.speedName} " +
                    "hold=${profile.sampleIntervalMs}ms timeScale=${profile.timeScale}"
            )
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (
            now - cachedJitterAtElapsedMs < profile.sampleIntervalMs &&
            cachedJitterBaseLat == baseLat &&
            cachedJitterBaseLng == baseLng &&
            cachedJitterRadius == jitterRadius &&
            cachedJitterSpeed == profile.speedName &&
            cachedJitterRfCellEnforced == enforceRfCell
        ) {
            return cachedJitteredLocation
        }

        val realDt = if (hookLastCallTime > 0) {
            ((now - hookLastCallTime) / 1000.0).coerceIn(0.01, 5.0)
        } else 1.0
        hookLastCallTime = now
        // Scaling both diffusion and mean reversion changes travel speed while preserving the
        // same long-run spatial envelope for every speed profile.
        val dt = (realDt * profile.timeScale).coerceIn(0.01, 5.0)

        val sigma = jitterRadius / profile.sigmaDivisor
        val alpha = profile.pullback
        
        hookDriftNorthMeters += sigma * sqrt(dt) * rng.nextGaussian() - alpha * hookDriftNorthMeters * dt
        hookDriftEastMeters += sigma * sqrt(dt) * rng.nextGaussian() - alpha * hookDriftEastMeters * dt

        val driftDistance = sqrt(hookDriftNorthMeters * hookDriftNorthMeters + hookDriftEastMeters * hookDriftEastMeters)
        if (driftDistance > jitterRadius && driftDistance > 0.0) {
            val scale = jitterRadius / driftDistance
            hookDriftNorthMeters *= scale
            hookDriftEastMeters *= scale
        }

        val latRad = Math.toRadians(baseLat)
        val lngCos = cos(latRad).let { if (abs(it) < 1e-6) 1e-6 else it }
        val jittered = constrainJitterToCoverageCell(baseLat, baseLng, lngCos, enforceRfCell)
        cachedJitterAtElapsedMs = now
        cachedJitterBaseLat = baseLat
        cachedJitterBaseLng = baseLng
        cachedJitterRadius = jitterRadius
        cachedJitterSpeed = profile.speedName
        cachedJitterRfCellEnforced = enforceRfCell
        cachedJitteredLocation = jittered
        return jittered
    }

    /**
     * RF payloads are tagged to the base coordinate's fixed hex. When any RF
     * switch is enabled, keep the final OU sample inside that exact cell so a
     * delivered coordinate can never be paired with a neighbouring-cell payload.
     */
    private fun constrainJitterToCoverageCell(
        baseLat: Double,
        baseLng: Double,
        longitudeCosine: Double,
        enforceCell: Boolean
    ): Pair<Double, Double> {
        fun pointAt(scale: Double): Pair<Double, Double> = Pair(
            baseLat + Math.toDegrees(hookDriftNorthMeters * scale / EARTH_RADIUS_METERS),
            EnvironmentCoveragePolicy.normalizeLongitude(
                baseLng + Math.toDegrees(
                    hookDriftEastMeters * scale / (EARTH_RADIUS_METERS * longitudeCosine)
                )
            )
        )

        val candidate = pointAt(1.0)
        if (!enforceCell) return candidate
        val baseCell = EnvironmentCoveragePolicy.cellFor(baseLat, baseLng) ?: return candidate
        if (EnvironmentCoveragePolicy.cellFor(candidate.first, candidate.second) == baseCell) {
            return candidate
        }

        var inside = 0.0
        var outside = 1.0
        repeat(28) {
            val middle = (inside + outside) / 2.0
            val point = pointAt(middle)
            if (EnvironmentCoveragePolicy.cellFor(point.first, point.second) == baseCell) {
                inside = middle
            } else {
                outside = middle
            }
        }
        // Stay a tiny distance inside the edge to avoid alternating cells from
        // floating-point rounding, then feed the bounded state back into the OU process.
        val safeScale = (inside * 0.999_999).coerceIn(0.0, 1.0)
        hookDriftNorthMeters *= safeScale
        hookDriftEastMeters *= safeScale
        return pointAt(1.0)
    }

    private fun shouldConstrainJitterToRfCell(): Boolean {
        val config = lastConfig
        return config?.optBoolean("mock_wifi", false) == true ||
            config?.optBoolean("mock_cell", false) == true
    }

    private fun getJitteredAccuracy(): Float {
        val jitterRadius = jitterRadiusMeters()
        hookAccuracyDrift += 0.5 * rng.nextGaussian() - 0.03 * hookAccuracyDrift
        val baseAccuracy = (jitterRadius * 0.65).coerceIn(8.0, 50.0)
        return (baseAccuracy + hookAccuracyDrift).coerceIn(3.0, 55.0).toFloat()
    }

    private fun jitterRadiusMeters(): Double {
        return (lastConfig?.optDouble("jitter_radius_meters", DEFAULT_JITTER_RADIUS_METERS)
            ?: DEFAULT_JITTER_RADIUS_METERS).coerceIn(MIN_JITTER_RADIUS_METERS, MAX_JITTER_RADIUS_METERS)
    }

    private data class JitterProfile(
        val speedName: String,
        val sampleIntervalMs: Long,
        val sigmaDivisor: Double,
        val pullback: Double,
        val timeScale: Double
    )

    private fun jitterProfile(): JitterProfile {
        val speed = lastConfig?.optString("jitter_speed", "MEDIUM")?.uppercase() ?: "MEDIUM"
        return when (speed) {
            // Framework reports stay fresh at 1 Hz. The profile controls how often the
            // coordinate sample advances and how far its OU clock advances, so the three
            // choices remain visibly distinct without changing timestamps/provider cadence.
            "SLOW" -> JitterProfile("SLOW", 2_800L, 6.7, 0.10, 0.12)
            "FAST" -> JitterProfile("FAST", 800L, 6.7, 0.10, 2.50)
            else -> JitterProfile("MEDIUM", 1_500L, 6.7, 0.10, 0.65)
        }
    }


    private fun hookLocationAPIs(classLoader: ClassLoader, currentPkg: String) {
        try {
            hookSystemLocationProviderPipeline(classLoader, currentPkg)
            // Standard Location values are written once in system_server before framework
            // cache/filter/delivery. Public getters and Parcel are deliberately left untouched.
            // ★ 核心反检测：抹除 isFromMockProvider 标志位（strategy:100 的根本来源）
            val antiMockHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (shouldSpoofLocation(config)) {
                        param.result = false
                    }
                }
            }
            // Android 6~11: isFromMockProvider()
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "isFromMockProvider",
                antiMockHook
            )
            // Android 12+: isMock()
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.Location",
                    classLoader,
                    "isMock",
                    antiMockHook
                )
            } catch (e: Throwable) { /* API < 31 没有此方法 */
            }

            // Some clients inspect the legacy secure setting directly. Keep this narrow:
            // unrelated Settings.Secure traffic must never touch the config cache.
            try {
                val mockLocationSettingHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(1) as? String ?: return
                        if (key != "mock_location" && key != "allow_mock_location") return
                        val config = readConfig() ?: return
                        if (!shouldSpoofLocation(config)) return
                        param.result = if (param.method?.name == "getString") "0" else 0
                    }
                }
                val secureClass = XposedHelpers.findClass(
                    "android.provider.Settings\$Secure",
                    classLoader
                )
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        secureClass,
                        "getInt",
                        android.content.ContentResolver::class.java,
                        String::class.java,
                        mockLocationSettingHook
                    )
                }
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        secureClass,
                        "getInt",
                        android.content.ContentResolver::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        mockLocationSettingHook
                    )
                }
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        secureClass,
                        "getString",
                        android.content.ContentResolver::class.java,
                        String::class.java,
                        mockLocationSettingHook
                    )
                }
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // ★ 拦截 getProvider：将 "mock" / "test" 提供者名隐藏，换成 "gps"
            XposedHelpers.findAndHookMethod(
                "android.location.Location", classLoader, "getProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldDeliverLocation(config)) {
                            val provider = param.result as? String ?: return
                            if (provider.contains("mock", ignoreCase = true) ||
                                provider.contains("test", ignoreCase = true) ||
                                provider.contains("fake", ignoreCase = true)
                            ) {
                                param.result = android.location.LocationManager.GPS_PROVIDER
                            }
                        }
                    }
                })

            // ★ 拦截 LocationManager.getProviders() / getAllProviders()：移除 mock/test 提供者
            val providerListHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (shouldSpoofLocation(config)) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? MutableList<String> ?: return
                        val cleaned = list.filterNot {
                            it.contains("mock", ignoreCase = true) ||
                                    it.contains("test", ignoreCase = true) ||
                                    it.contains("fake", ignoreCase = true)
                        }.toMutableList()
                        if (!cleaned.contains(android.location.LocationManager.GPS_PROVIDER))
                            cleaned.add(android.location.LocationManager.GPS_PROVIDER)
                        param.result = cleaned
                    }
                }
            }
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", classLoader, "getProviders",
                    Boolean::class.javaPrimitiveType!!, providerListHook
                )
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", classLoader, "getAllProviders",
                    providerListHook
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // Never intercept or fulfil LocationManager requests in a client process. The
            // original registration must reach system_server so provider demand, permissions,
            // cancellation and normal Binder delivery all remain authoritative.

            // ★ NMEA-0183 报文劫持
            try {
                val addNmeaListenerHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // An inactive/off registration must reach Android byte-for-byte. A
                        // listener registered before a later start remains real until the app
                        // registers it again; this avoids a permanent per-sentence proxy cost
                        // while simulation is idle.
                        if (!shouldDeliverLocation(readConfig())) return

                        val args = param.args
                        for (i in args.indices) {
                            val arg = args[i] ?: continue
                            
                            // Check if it implements OnNmeaMessageListener
                            val isOnNmea = try {
                                val clazz = classLoader.loadClass("android.location.OnNmeaMessageListener")
                                clazz.isInstance(arg)
                            } catch (e: Exception) {
                                false
                            }
                            
                            // Check if it implements GpsStatus.NmeaListener
                            val isGpsNmea = try {
                                val clazz = classLoader.loadClass("android.location.GpsStatus\$NmeaListener")
                                clazz.isInstance(arg)
                            } catch (e: Exception) {
                                false
                            }

                            if (isOnNmea) {
                                XposedBridge.logEvery(
                                    "nmea-listener-proxy:on-message",
                                    "[GPS_Spoofer] active OnNmeaMessageListener proxy installed",
                                    60_000L
                                )
                                args[i] = createOnNmeaMessageListenerProxy(arg, classLoader)
                            } else if (isGpsNmea) {
                                XposedBridge.logEvery(
                                    "nmea-listener-proxy:legacy",
                                    "[GPS_Spoofer] active legacy NMEA listener proxy installed",
                                    60_000L
                                )
                                args[i] = createGpsStatusNmeaListenerProxy(arg, classLoader)
                            }
                        }
                    }
                }
                val locationManagerClazz = XposedHelpers.findClass("android.location.LocationManager", classLoader)
                XposedBridge.hookAllMethods(locationManagerClazz, "addNmeaListener", addNmeaListenerHook)
                
                // Hook removeNmeaListener
                XposedBridge.hookAllMethods(locationManagerClazz, "removeNmeaListener", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        for (i in param.args.indices) {
                            val arg = param.args[i]
                            if (arg != null) {
                                val timer = nmeaTimers.remove(arg)
                                timer?.cancel()
                                val proxy = nmeaListenerProxies.remove(arg)
                                if (proxy != null) {
                                    param.args[i] = proxy
                                }
                                if (timer != null || proxy != null) {
                                    XposedBridge.logEvery(
                                        "nmea-listener-proxy:removed",
                                        "[GPS_Spoofer] NMEA listener proxy removed",
                                        60_000L
                                    )
                                }
                            }
                        }
                    }
                })
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            if (!shouldInstallLegacyMapSdkHooks(currentPkg)) {
                XposedBridge.logEvery(
                    "legacyMapSdkHooksSkipped:$currentPkg",
                    "[LocationSpoofer] legacy AMap/Tencent/Baidu SDK hooks disabled for $currentPkg; using system/GMS location pipeline"
                )
                return
            }

            // ── 高德SDK专属Hook(含抖动,与原生Location保持同步) ──
            // 使用findClassIfExists安全探测: 微信小程序子进程(:appbrand0等)不加载高德SDK,
            // 直接findAndHookMethod会抛出ClassNotFoundError,中断整个hookLocationAPIs执行流。
            // findClassIfExists在类不存在时返回null而非抛异常,可安全跳过。
            val amapLocClazz = XposedHelpers.findClassIfExists(
                "com.amap.api.location.AMapLocation", classLoader
            )

            if (amapLocClazz != null) {
                XposedBridge.log("[LocationSpoofer] AMapLocation class found, installing AMap hooks")
                val amapLocClass = "com.amap.api.location.AMapLocation"

                // AMap SDK 专属 Hook（含抖动，与原生Location保持同步）
                val amapHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (shouldDeliverLocation(config)) {
                            val baseLat = config.optDouble("lat", 0.0)
                            val baseLng = config.optDouble("lng", 0.0)
                            val jittered = getJitteredLocation(baseLat, baseLng)
                            when (param.method!!.name) {
                                "getLatitude" -> param.result = jittered.first
                                "getLongitude" -> param.result = jittered.second
                            }
                        }
                    }
                }
                try {
                    XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getLatitude", amapHook)
                    XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getLongitude", amapHook)
                } catch (e: Throwable) { /* AMap SDK方法签名不匹配则跳过 */ }

                // ★★★ 高德SDK深度反检测（strategy:500 的来源）
                // mockData JSON 就是 AMapLocation.getMockData() 的返回值，直接抹零
                val amapNullHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldSpoofLocation(config)) {
                            param.result = null
                        }
                    }
                }
                val amapFalseHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldSpoofLocation(config)) {
                            param.result = false
                        }
                    }
                }
                val amapZeroHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldSpoofLocation(config)) {
                            param.result = 0
                        }
                    }
                }

                try {
                    // 1. getMockData() -> null（直接砍掉mockData字段的数据来源）
                    XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getMockData", amapNullHook)
                    // 2. getMockFlag() / getMockType() -> 0
                    try { XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getMockFlag", amapZeroHook) } catch (e: Throwable) {}
                    try { XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getMockType", amapZeroHook) } catch (e: Throwable) {}
                    // 3. isMocked() -> false（AMap SDK 12.0+ 新接口）
                    try { XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "isMocked", amapFalseHook) } catch (e: Throwable) {}
                    // 4. getErrorCode() -> 0（非0表示定位失败）
                    XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getErrorCode", amapZeroHook)
                    // 5. getLocationType() -> 动态保留网络定位类型，否则强制返回GPS类型（1）
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getLocationType",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val config = readConfig()
                                if (shouldDeliverLocation(config)) {
                                    val originalLocationType = param.result as? Int ?: 1
                                    // 高德地图SDK中：1代表GPS定位，5代表Wifi网络定位，6代表基站网络定位，12代表系统网络定位
                                    // 为了防止反作弊SDK检测到在室内（无卫星）却返回GPS定位的异常情况，
                                    // 我们需要保留原有的网络定位类型，使其显得更加真实自然。
                                    if (originalLocationType == 5 || originalLocationType == 6 || originalLocationType == 12) {
                                        param.result = originalLocationType
                                    } else {
                                        param.result = 1 // 默认强制设置为GPS定位
                                    }
                                }
                            }
                        })
                    // 6. getProvider() -> 动态保留网络提供者，否则强制返回"gps"
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getProvider",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val config = readConfig()
                                if (shouldDeliverLocation(config)) {
                                    val originalProvider = param.result as? String ?: "gps"
                                    // 同样为了真实性，如果原始提供者是网络（network）相关的，则保留原样
                                    if (originalProvider == "network" || originalProvider.contains("wifi", ignoreCase = true)) {
                                        param.result = originalProvider
                                    } else {
                                        param.result = "gps"
                                    }
                                }
                            }
                        })
                    // 7. 直接写底层 mock 相关字段（防反射读字段绕过 getter）
                    val setFieldHook = object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (shouldSpoofLocation(config)) {
                                val obj = param.thisObject ?: return
                                try { XposedHelpers.setObjectField(obj, "mockData", null) } catch (e: Throwable) {}
                                try { XposedHelpers.setIntField(obj, "mockFlag", 0) } catch (e: Throwable) {}
                                try { XposedHelpers.setIntField(obj, "mockType", 0) } catch (e: Throwable) {}
                                try { XposedHelpers.setBooleanField(obj, "isMocked", false) } catch (e: Throwable) {}
                                try { XposedHelpers.setBooleanField(obj, "mMock", false) } catch (e: Throwable) {}
                                try { XposedHelpers.setIntField(obj, "errorCode", 0) } catch (e: Throwable) {}
                            }
                        }
                    }
                    XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getLatitude", setFieldHook)
                } catch (e: Throwable) {
                    XposedBridge.log(e)
                }

                // 8. AMapLocationQualityReport 质量报告也要清零
                val qualityClazz = XposedHelpers.findClassIfExists(
                    "com.amap.api.location.AMapLocationQualityReport", classLoader
                )
                if (qualityClazz != null) {
                    try { XposedHelpers.findAndHookMethod(qualityClazz, "getMockInfo", amapNullHook) } catch (e: Throwable) {}
                    try { XposedHelpers.findAndHookMethod(qualityClazz, "isMockLocation", amapFalseHook) } catch (e: Throwable) {}
                }

                // 9. setMockEnable(false) 让高德SDK禁用自身的 mock 校验流程
                val clientClazz = XposedHelpers.findClassIfExists(
                    "com.amap.api.location.AMapLocationClient", classLoader
                )
                if (clientClazz != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            clientClazz, "setMockEnable",
                            Boolean::class.javaPrimitiveType!!,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val config = readConfig()
                                    if (shouldDeliverLocation(config)) {
                                        // 强制设为 true，让高德自己相信当前位置是真实的
                                        param.args[0] = true
                                    }
                                }
                            }
                        )
                    } catch (e: Throwable) {}
                }
            } else {
                XposedBridge.log("[LocationSpoofer] AMapLocation class not found in ${classLoader}, skipping AMap hooks")
            }

        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // ── 第三方地图SDK深度Hook(腾讯/百度) ──
        if (shouldInstallLegacyMapSdkHooks(currentPkg)) {
            hookTencentSDK(classLoader)
            hookBaiduSDK(classLoader)
        }
    }

    /**
     * 腾讯定位SDK深度Hook
     *
     * 架构分析:
     * TencentLocation在腾讯SDK中是一个**接口(interface)**,不是具体类。
     * 其方法签名为: public interface TencentLocation { double getLatitude(); ... }
     * Xposed的findAndHookMethod无法Hook接口方法(接口没有方法体),
     * 必须找到实现该接口的具体类并对其进行Hook。
     *
     * 腾讯SDK常见的实现类名(不同版本可能不同):
     * - com.tencent.map.geolocation.internal.TencentLocationImpl
     * - com.tencent.map.geolocation.TencentLocationImpl
     * - 部分版本使用ProGuard混淆后类名不固定
     *
     * 策略: 先尝试已知实现类名,若均不存在则降级为hookAllMethods扫描所有实现。
     *
     * 坐标系: GCJ-02(与高德相同)
     */
    private fun hookTencentSDK(classLoader: ClassLoader) {
        // 腾讯SDK已知的实现类名(按优先级排列)
        val implCandidates = listOf(
            "com.tencent.map.geolocation.internal.TencentLocationImpl",
            "com.tencent.map.geolocation.TencentLocationImpl",
            "com.tencent.tencentmap.mapsdk.map.model.TencentLocationImpl"
        )

        // 阶段1: 尝试直接Hook已知实现类
        var hooked = false
        for (implClass in implCandidates) {
            val clazz = XposedHelpers.findClassIfExists(implClass, classLoader)
            if (clazz != null) {
                hookTencentLocationClass(clazz)
                hooked = true
                XposedBridge.log("[LocationSpoofer] TencentLocation impl found: $implClass")
                break
            }
        }

        // 阶段2: 若已知类名均不存在,尝试通过接口反向查找
        if (!hooked) {
            val interfaceClazz = XposedHelpers.findClassIfExists(
                "com.tencent.map.geolocation.TencentLocation", classLoader
            )
            if (interfaceClazz != null && interfaceClazz.isInterface) {
                // TencentLocation是接口,无法直接Hook。
                // 但腾讯SDK的定位结果最终会通过TencentLocationListener.onLocationChanged(TencentLocation)
                // 回调给App。我们Hook这个回调,在App拿到结果前篡改TencentLocation实例的字段。
                hookTencentLocationCallback(classLoader)
                hooked = true
            } else if (interfaceClazz != null) {
                // 某些版本中TencentLocation是具体类而非接口
                hookTencentLocationClass(interfaceClazz)
                hooked = true
            }
        }

        if (!hooked) {
            XposedBridge.log("[LocationSpoofer] TencentLocation SDK not found, skipped")
        }
    }

    /**
     * 对TencentLocation的具体实现类进行方法Hook
     */
    private fun hookTencentLocationClass(clazz: Class<*>) {
        val coordHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (shouldDeliverLocation(config)) {
                    val baseLat = config.optDouble("lat", 0.0)
                    val baseLng = config.optDouble("lng", 0.0)
                    val jittered = getJitteredLocation(baseLat, baseLng)
                    when (param.method!!.name) {
                        "getLatitude" -> param.result = jittered.first
                        "getLongitude" -> param.result = jittered.second
                    }
                }
            }
        }

        try {
            // hookAllMethods: 不管方法签名如何变化,只要方法名匹配就Hook
            XposedBridge.hookAllMethods(clazz, "getLatitude", coordHook)
            XposedBridge.hookAllMethods(clazz, "getLongitude", coordHook)
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] TencentLocation class hook failed: $e")
            return
        }

        // 动态保留网络定位提供者标识，避免室内强行返回GPS引发风控检测
        try {
            XposedBridge.hookAllMethods(clazz, "getProvider", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (shouldDeliverLocation(config)) {
                        val originalProvider = param.result as? String ?: "gps"
                        // 腾讯地图SDK的定位提供者通常也是"gps"或者"network"
                        if (originalProvider == "network" || originalProvider.contains("wifi", ignoreCase = true)) {
                            param.result = originalProvider
                        } else {
                            param.result = "gps" // 默认强制修改为GPS定位
                        }
                    }
                }
            })
        } catch (e: Throwable) { /* 忽略 */ }

        // getAccuracy -> 抖动精度
        try {
            XposedBridge.hookAllMethods(clazz, "getAccuracy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (shouldDeliverLocation(config)) {
                        param.result = getJitteredAccuracy()
                    }
                }
            })
        } catch (e: Throwable) { /* 忽略 */ }

        // isMockGps -> 0(非模拟)
        try {
            XposedBridge.hookAllMethods(clazz, "isMockGps", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (shouldSpoofLocation(config)) {
                        param.result = 0
                    }
                }
            })
        } catch (e: Throwable) { /* 忽略 */ }

        XposedBridge.log("[LocationSpoofer] TencentLocation hooks installed on ${clazz.name}")
    }

    /**
     * 通过拦截TencentLocationListener回调来修改坐标
     *
     * 当无法直接Hook TencentLocation实现类时的降级方案:
     * Hook TencentLocationListener.onLocationChanged(TencentLocation, int, String)回调,
     * 在回调触发时通过反射修改TencentLocation实例的内部字段。
     */
    private fun hookTencentLocationCallback(classLoader: ClassLoader) {
        val listenerClass = XposedHelpers.findClassIfExists(
            "com.tencent.map.geolocation.TencentLocationListener", classLoader
        ) ?: return

        try {
            // hookAllMethods可以Hook接口的所有实现类中的方法
            XposedBridge.hookAllMethods(
                listenerClass, "onLocationChanged",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldDeliverLocation(config)) return
                        if (param.args.isEmpty()) return

                        val tencentLoc = param.args[0] ?: return
                        val baseLat = config.optDouble("lat", 0.0)
                        val baseLng = config.optDouble("lng", 0.0)
                        val jittered = getJitteredLocation(baseLat, baseLng)

                        // 通过反射直接写入TencentLocation实现类的经纬度字段
                        try { XposedHelpers.callMethod(tencentLoc, "setLatitude", jittered.first) } catch (e: Throwable) {
                            try { XposedHelpers.setDoubleField(tencentLoc, "latitude", jittered.first) } catch (e2: Throwable) {
                                try { XposedHelpers.setDoubleField(tencentLoc, "mLatitude", jittered.first) } catch (e3: Throwable) {
                                    try { XposedHelpers.setDoubleField(tencentLoc, "a", jittered.first) } catch (e4: Throwable) {}
                                }
                            }
                        }
                        try { XposedHelpers.callMethod(tencentLoc, "setLongitude", jittered.second) } catch (e: Throwable) {
                            try { XposedHelpers.setDoubleField(tencentLoc, "longitude", jittered.second) } catch (e2: Throwable) {
                                try { XposedHelpers.setDoubleField(tencentLoc, "mLongitude", jittered.second) } catch (e3: Throwable) {
                                    try { XposedHelpers.setDoubleField(tencentLoc, "b", jittered.second) } catch (e4: Throwable) {}
                                }
                            }
                        }
                    }
                }
            )
            XposedBridge.log("[LocationSpoofer] TencentLocationListener callback hook installed")
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] TencentLocationListener hook failed: $e")
        }
    }

    /**
     * 百度定位SDK深度Hook
     *
     * 百度定位SDK的核心定位回调对象为com.baidu.location.BDLocation。
     * 百度地图使用BD-09坐标系,这是在GCJ-02基础上施加二次偏移的专有坐标系。
     *
     * 关键区别:
     * - 高德/腾讯: 使用GCJ-02,直接返回config中的lat/lng
     * - 百度: 使用BD-09,必须调用gcj02ToBd09()转换后再返回
     *
     * 双重保险策略:
     * 1. 直接Hook BDLocation.getLatitude/getLongitude(方法级拦截)
     * 2. Hook BDAbstractLocationListener.onReceiveLocation回调(回调级拦截)
     * 两者互为补充,确保无论百度SDK内部架构如何变化,BD-09坐标都能正确注入。
     */
    private fun hookBaiduSDK(classLoader: ClassLoader) {
        val baiduLocClass = "com.baidu.location.BDLocation"

        // 安全探测: 当前进程是否加载了百度定位SDK
        val baiduClazz = XposedHelpers.findClassIfExists(baiduLocClass, classLoader)
        if (baiduClazz == null) {
            XposedBridge.log("[LocationSpoofer] BDLocation class not found, skipping")
            return
        }

        // ── 方案1: 直接Hook BDLocation的Getter方法 ──
        val baiduCoordHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (shouldDeliverLocation(config)) {
                    // 动态获取当前BDLocation期望的坐标系(App可通过LocationClientOption.setCoorType设置)
                    val coorType = try {
                        XposedHelpers.callMethod(param.thisObject!!, "getCoorType") as? String
                    } catch (e: Throwable) { null }

                    val targetLat: Double
                    val targetLng: Double
                    
                    when (coorType) {
                        "bd09ll", "bd09mc", "bd09" -> {
                            targetLat = config.optDouble("bd09_lat", 0.0)
                            targetLng = config.optDouble("bd09_lng", 0.0)
                        }
                        "wgs84" -> {
                            targetLat = config.optDouble("wgs84_lat", 0.0)
                            targetLng = config.optDouble("wgs84_lng", 0.0)
                        }
                        else -> { // gcj02 或默认(中国标准坐标系)
                            targetLat = config.optDouble("lat", 0.0)
                            targetLng = config.optDouble("lng", 0.0)
                        }
                    }

                    val jittered = getJitteredLocation(targetLat, targetLng)
                    when (param.method!!.name) {
                        "getLatitude" -> param.result = jittered.first
                        "getLongitude" -> param.result = jittered.second
                    }
                }
            }
        }

        try {
            // 使用hookAllMethods: BDLocation在不同版本中可能有多个getLatitude重载
            XposedBridge.hookAllMethods(baiduClazz, "getLatitude", baiduCoordHook)
            XposedBridge.hookAllMethods(baiduClazz, "getLongitude", baiduCoordHook)

            // getLocType -> 动态保留网络定位类型，适配百度SDK的161和601类型，否则强制返回GPS定位(61)
            XposedBridge.hookAllMethods(baiduClazz, "getLocType", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (shouldDeliverLocation(config)) {
                        val originalLocationType = param.result as? Int ?: 61
                        // 百度地图SDK中：61代表GPS定位结果，161代表网络定位结果，601代表某些特殊或离线网络定位结果
                        // 为了避免在室内环境（如没有GPS信号）强行返回61导致应用侧判定为作弊，
                        // 我们直接放行原有的网络定位类型，由于经纬度已经被修改，这样显得更加真实自然。
                        if (originalLocationType == 161 || originalLocationType == 601) {
                            param.result = originalLocationType
                        } else {
                            param.result = 61 // 默认强制修改为GPS定位（61）
                        }
                    }
                }
            })

            // getRadius(精度) -> 与全局抖动精度同步
            try {
                XposedBridge.hookAllMethods(baiduClazz, "getRadius", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldDeliverLocation(config)) {
                            param.result = getJitteredAccuracy()
                        }
                    }
                })
            } catch (e: Throwable) { /* 忽略 */ }

            // getMockGps -> 0(非模拟)
            try {
                XposedBridge.hookAllMethods(baiduClazz, "getMockGps", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldDeliverLocation(config)) {
                            param.result = 0
                        }
                    }
                })
            } catch (e: Throwable) { /* 忽略 */ }

            // getSatelliteNumber -> 12-18颗
            try {
                XposedBridge.hookAllMethods(baiduClazz, "getSatelliteNumber", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldDeliverLocation(config)) {
                            param.result = 12 + rng.nextInt(7)
                        }
                    }
                })
            } catch (e: Throwable) { /* 忽略 */ }

            XposedBridge.log("[LocationSpoofer] BDLocation method hooks installed")
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] BDLocation method hook failed: $e")
        }

        // ── 方案2(补充): Hook百度定位回调,在App接收BDLocation前修改其内部字段 ──
        // BDAbstractLocationListener是百度SDK 7.0+推荐的回调基类
        val listenerCandidates = listOf(
            "com.baidu.location.BDAbstractLocationListener",
            "com.baidu.location.BDLocationListener"
        )
        for (listenerClassName in listenerCandidates) {
            val listenerClazz = XposedHelpers.findClassIfExists(listenerClassName, classLoader) ?: continue
            try {
                XposedBridge.hookAllMethods(
                    listenerClazz, "onReceiveLocation",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val config = readConfig() ?: return
                            if (!shouldDeliverLocation(config)) return
                            if (param.args.isEmpty()) return

                            val bdLoc = param.args[0] ?: return
                            
                            val coorType = try {
                                XposedHelpers.callMethod(bdLoc, "getCoorType") as? String
                            } catch (e: Throwable) { null }

                            val targetLat: Double
                            val targetLng: Double
                            when (coorType) {
                                "bd09ll", "bd09mc", "bd09" -> {
                                    targetLat = config.optDouble("bd09_lat", 0.0)
                                    targetLng = config.optDouble("bd09_lng", 0.0)
                                }
                                "wgs84" -> {
                                    targetLat = config.optDouble("wgs84_lat", 0.0)
                                    targetLng = config.optDouble("wgs84_lng", 0.0)
                                }
                                else -> { // gcj02 或默认(中国标准坐标系)
                                    targetLat = config.optDouble("lat", 0.0)
                                    targetLng = config.optDouble("lng", 0.0)
                                }
                            }
                            
                            val jittered = getJitteredLocation(targetLat, targetLng)

                            // 通过反射直接写入BDLocation实例的经纬度
                            try { XposedHelpers.callMethod(bdLoc, "setLatitude", jittered.first) } catch (e: Throwable) {
                                try { XposedHelpers.setDoubleField(bdLoc, "mLatitude", jittered.first) } catch (e2: Throwable) {}
                            }
                            try { XposedHelpers.callMethod(bdLoc, "setLongitude", jittered.second) } catch (e: Throwable) {
                                try { XposedHelpers.setDoubleField(bdLoc, "mLongitude", jittered.second) } catch (e2: Throwable) {}
                            }
                            // 动态设置定位类型：保留网络定位类型（161和601），其余强制覆盖为GPS定位（61）
                            try {
                                val currentLocationType = XposedHelpers.callMethod(bdLoc, "getLocType") as? Int ?: 61
                                // 如果当前回调原本就是网络定位，那么我们不修改类型，只替换了上面的经纬度坐标
                                if (currentLocationType != 161 && currentLocationType != 601) {
                                    XposedHelpers.callMethod(bdLoc, "setLocType", 61)
                                }
                            } catch (e: Throwable) { /* 忽略反射调用可能出现的异常 */ }
                        }
                    }
                )
                XposedBridge.log("[LocationSpoofer] $listenerClassName callback hook installed")
            } catch (e: Throwable) { /* 忽略 */ }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Wi-Fi 环境伪造 — 覆盖 WifiInfo / WifiManager / NetworkInfo
    // ══════════════════════════════════════════════════════════════════════════
    private fun selectSpoofedWifi(wifiObj: JSONObject?): JSONObject? {
        if (wifiObj != null) {
            val mode = lastConfig?.optString("wifi_connection_mode", "FIXED") ?: "FIXED"
            if (mode == "RANDOM") {
                val candidates = ArrayList<JSONObject>()
                val connected = wifiObj.optJSONObject("connectedWifi")
                if (isUsableWifiObject(connected)) candidates.add(connected!!)
                val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
                if (nearbyArray != null) {
                    for (i in 0 until nearbyArray.length()) {
                        val candidate = nearbyArray.optJSONObject(i)
                        if (isUsableWifiObject(candidate)) candidates.add(candidate!!)
                    }
                }
                if (candidates.isNotEmpty()) {
                    val seed = lastConfig?.optLong("start_timestamp", 0L)?.takeIf { it > 0L }
                        ?: lastConfig?.optLong("config_updated_at", 0L)
                        ?: System.currentTimeMillis()
                    return candidates[Math.floorMod(seed.toInt(), candidates.size)]
                }
            }
            val connected = wifiObj.optJSONObject("connectedWifi")
            if (isUsableWifiObject(connected)) return connected
            val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
            if (nearbyArray != null) {
                for (i in 0 until nearbyArray.length()) {
                    val candidate = nearbyArray.optJSONObject(i)
                    if (isUsableWifiObject(candidate)) return candidate
                }
            }
        }
        return null
    }

    private fun isUsableWifiObject(wifi: JSONObject?): Boolean {
        if (wifi == null) return false
        val bssid = wifi.optString("bssid", "")
        val ssid = wifi.optString("ssid", "")
        return bssid.isNotBlank() || (ssid.isNotBlank() && ssid != "<unknown ssid>")
    }

    private fun hasSpoofedWifiData(wifiObj: JSONObject?): Boolean {
        if (selectSpoofedWifi(wifiObj) != null) return true
        return (wifiObj?.optJSONArray("nearbyWifi")?.length() ?: 0) > 0
    }

    private fun wifiSsid(wifi: JSONObject, fallback: String = "HOME_WIFI"): String {
        val ssidVal = wifi.optString("ssid", "")
        if (ssidVal.isNotBlank() && ssidVal != "<unknown ssid>") return ssidVal
        val bssid = wifi.optString("bssid", "")
        return if (bssid.isNotBlank()) "WIFI_${bssid.takeLast(5).replace(":", "")}" else fallback
    }

    private fun wifiBssid(wifi: JSONObject): String {
        val bssid = wifi.optString("bssid", "")
        return if (bssid.isNotBlank()) bssid else "02:00:00:00:00:00"
    }

    private fun wifiRssi(wifi: JSONObject): Int {
        val level = wifi.optInt("level", Int.MIN_VALUE)
        if (level in -95..-20) return level
        val rssi = wifi.optInt("rssi", Int.MIN_VALUE)
        if (rssi in -95..-20) return rssi
        return -55
    }

    private fun applyWifiInfoFields(wifiInfo: Any, wifi: JSONObject, classLoader: ClassLoader) {
        val finalSsid = wifiSsid(wifi)
        val bssidVal = wifiBssid(wifi)
        val freqVal = wifi.optInt("frequency", 2412).takeIf { it > 0 } ?: 2412
        val levelVal = wifiRssi(wifi)
        val linkSpeedVal = wifi.optInt("linkSpeed", 150).takeIf { it > 0 } ?: 150
        val networkIdVal = wifi.optInt("networkId", 1).takeIf { it >= 0 } ?: 1
        val standardVal = wifi.optInt("wifiStandard", 4).takeIf { it > 0 } ?: 4
        val macAddressVal = wifi.optString("macAddress", "02:00:00:00:00:00")

        try { XposedHelpers.callMethod(wifiInfo, "setBSSID", bssidVal) } catch (_: Throwable) {}
        try { XposedHelpers.callMethod(wifiInfo, "setMacAddress", macAddressVal) } catch (_: Throwable) {}
        try {
            val wifiSsidClass = XposedHelpers.findClass("android.net.wifi.WifiSsid", classLoader)
            val createMethod = XposedHelpers.findMethodExact(wifiSsidClass, "createFromAsciiEncoded", String::class.java)
            XposedHelpers.setObjectField(wifiInfo, "mWifiSsid", createMethod.invoke(null, finalSsid))
        } catch (_: Throwable) {
            try { XposedHelpers.setObjectField(wifiInfo, "mSSID", "\"$finalSsid\"") } catch (_: Throwable) {}
        }
        try { XposedHelpers.setObjectField(wifiInfo, "mBSSID", bssidVal) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(wifiInfo, "mBssid", bssidVal) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(wifiInfo, "mMacAddress", macAddressVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mRssi", levelVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mRssiDbm", levelVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mFrequency", freqVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mTxLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mRxLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mNetworkId", networkIdVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mWifiStandard", standardVal) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(wifiInfo, "mTrusted", true) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(wifiInfo, "mIsUsable", true) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mScore", 60) } catch (_: Throwable) {}
        try {
            val supplicantState = XposedHelpers.findClass("android.net.wifi.SupplicantState", classLoader)
            XposedHelpers.setObjectField(wifiInfo, "mSupplicantState", supplicantState.getField("COMPLETED").get(null))
        } catch (_: Throwable) {}
    }

    private fun buildSpoofedWifiInfo(classLoader: ClassLoader, wifi: JSONObject): Any {
        val finalSsid = wifiSsid(wifi)
        val bssidVal = wifiBssid(wifi)
        val freqVal = wifi.optInt("frequency", 2412).takeIf { it > 0 } ?: 2412
        val levelVal = wifiRssi(wifi)
        val linkSpeedVal = wifi.optInt("linkSpeed", 150).takeIf { it > 0 } ?: 150
        val macAddressVal = wifi.optString("macAddress", "02:00:00:00:00:00")
        return try {
            val builderClass = XposedHelpers.findClass("android.net.wifi.WifiInfo\$Builder", classLoader)
            val builder = XposedHelpers.newInstance(builderClass)
            try { XposedHelpers.callMethod(builder, "setSsid", finalSsid) } catch (_: Throwable) {
                try { XposedHelpers.callMethod(builder, "setSsid", finalSsid.toByteArray(Charsets.UTF_8)) } catch (_: Throwable) {}
            }
            try { XposedHelpers.callMethod(builder, "setBssid", bssidVal) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(builder, "setMacAddress", macAddressVal) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(builder, "setRssi", levelVal) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(builder, "setFrequency", freqVal) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(builder, "setLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(builder, "setNetworkId", wifi.optInt("networkId", 1)) } catch (_: Throwable) {}
            val builtInfo = XposedHelpers.callMethod(builder, "build")!!
            applyWifiInfoFields(builtInfo, wifi, classLoader)
            builtInfo
        } catch (_: Throwable) {
            val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
            val info = XposedHelpers.newInstance(wifiInfoClass)
            applyWifiInfoFields(info, wifi, classLoader)
            info
        }
    }

    private fun hookWifiEnvironment(classLoader: ClassLoader) {

        // ── 1. WifiInfo getter Hook ──
        val wifiInfoHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!shouldMockWifi(config)) return
                val mockWifi = config.optBoolean("mock_wifi", true)
                val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                val connectedWifi = selectSpoofedWifi(wifiObj)
                val isConnected = connectedWifi != null
                
                when (param.method!!.name) {
                    "getBSSID" -> param.result =
                        connectedWifi?.let { wifiBssid(it) } ?: "02:00:00:00:00:00"
                    "getMacAddress" -> param.result =
                        connectedWifi?.optString("macAddress") ?: "02:00:00:00:00:00"
                    "getSSID" -> {
                        param.result = connectedWifi?.let { "\"${wifiSsid(it)}\"" } ?: "<unknown ssid>"
                    }
                    "getNetworkId" -> param.result =
                        connectedWifi?.optInt("networkId", 1) ?: -1
                    "getRssi" -> param.result =
                        connectedWifi?.let { wifiRssi(it) } ?: -127
                    "getLinkSpeed" -> param.result =
                        connectedWifi?.optInt("linkSpeed", 150) ?: -1
                    "getFrequency" -> param.result =
                        connectedWifi?.optInt("frequency", 2412) ?: -1
                    "getIpAddress" -> param.result =
                        if (isConnected) 0x6401A8C0 else 0 // 192.168.1.100 小端序
                }
            }
        }

        try {
            val wifiInfoMethods = listOf(
                "getBSSID", "getMacAddress", "getSSID", "getNetworkId",
                "getRssi", "getLinkSpeed", "getFrequency", "getIpAddress"
            )
            for (method in wifiInfoMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        "android.net.wifi.WifiInfo", classLoader, method, wifiInfoHook
                    )
                } catch (e: Throwable) { /* 部分方法在低版本可能不存在 */ }
            }
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 1b. WifiInfo.getSupplicantState() ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo", classLoader, "getSupplicantState",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        val isConnected = selectSpoofedWifi(wifiObj) != null
                        try {
                            val enumClass = XposedHelpers.findClass(
                                "android.net.wifi.SupplicantState", classLoader
                            )
                            val stateStr = if (mockWifi && isConnected) "COMPLETED" else "DISCONNECTED"
                            param.result = enumClass.getField(stateStr).get(null)
                        } catch (e: Throwable) { /* 忽略 */ }
                    }
                }
            )
        } catch (e: Throwable) { /* 忽略 */ }

        // ── 2. Wi-Fi 扫描结果伪造 (getScanResults) ──
        val realCapabilities = listOf(
            "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]",
            "[WPA2-PSK-CCMP+TKIP][RSN-PSK-CCMP+TKIP][ESS]",
            "[WPA2-PSK-CCMP][ESS][WPS]",
            "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][ESS]",
            "[RSN-PSK-CCMP][ESS]",
            "[WPA2-EAP-CCMP][RSN-EAP-CCMP][ESS]",
            "[ESS]",
            "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]",
            "[WPA2-SAE-CCMP][RSN-SAE-CCMP][ESS]",
            "[WPA2-PSK+SAE-CCMP][RSN-PSK+SAE-CCMP][ESS]"
        )

        val wifiScanHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!shouldMockWifi(config)) return
                val fakeList = java.util.ArrayList<Any>()
                val mockWifi = config.optBoolean("mock_wifi", true)
                val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                if (wifiObj != null) {
                    try {
                        val scanResultClass =
                            XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                        val baseTimestamp = android.os.SystemClock.elapsedRealtimeNanos()
                        
                        fun addFakeScanResult(wifi: org.json.JSONObject) {
                            val fakeScanResult = XposedHelpers.newInstance(scanResultClass)
                            val ssidVal = wifi.optString("ssid", "")
                            val bssidVal = wifi.optString("bssid", "")
                            val finalSsid = if (ssidVal.isEmpty() || ssidVal == "<unknown ssid>") {
                                "WIFI_${bssidVal.takeLast(5).replace(":", "")}"
                            } else {
                                ssidVal
                            }
                            XposedHelpers.setObjectField(fakeScanResult, "SSID", finalSsid)
                            XposedHelpers.setObjectField(fakeScanResult, "BSSID", bssidVal)
                            val level = wifi.optInt("level", -65)
                            XposedHelpers.setIntField(fakeScanResult, "level", level)
                            XposedHelpers.setIntField(
                                fakeScanResult, "frequency",
                                wifi.optInt("frequency", 2412)
                            )
                            XposedHelpers.setObjectField(
                                fakeScanResult, "capabilities",
                                wifi.optString("capabilities", realCapabilities.random())
                            )
                            try {
                                val offsetNanos = (rng.nextInt(200_000) * 1000L)
                                XposedHelpers.setLongField(
                                    fakeScanResult, "timestamp",
                                    (baseTimestamp - offsetNanos) / 1000
                                )
                            } catch (e: Throwable) {}
                            fakeList.add(fakeScanResult)
                        }

                        val connectedWifi = selectSpoofedWifi(wifiObj)
                        if (connectedWifi != null) {
                            addFakeScanResult(connectedWifi)
                        }

                        val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
                        if (nearbyArray != null && nearbyArray.length() > 0) {
                            for (i in 0 until nearbyArray.length()) {
                                val wifi = nearbyArray.getJSONObject(i)
                                if (connectedWifi != null && wifiBssid(wifi).equals(wifiBssid(connectedWifi), ignoreCase = true)) continue
                                addFakeScanResult(wifi)
                            }
                        }
                    } catch (e: Throwable) { /* 忽略 */ }
                }
                XposedBridge.logEvery(
                    "wifiScanResults:${fakeList.size}",
                    "[LocationSpoofer] getScanResults returning fake AP count=${fakeList.size}"
                )
                param.result = fakeList
            }
        }

        // ── 3. WifiManager 整体 Hook ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getScanResults", wifiScanHook
            )

            // getWifiState()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getWifiState",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = config.optJSONObject("wifi_json")
                        val hasWifiData = hasSpoofedWifiData(wifiObj)
                        if (mockWifi) {
                            param.result = if (hasWifiData) 3 else 1 // 3 is WIFI_STATE_ENABLED, 1 is disabled
                        }
                    }
                })

            // isWifiEnabled()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "isWifiEnabled",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = config.optJSONObject("wifi_json")
                        val hasWifiData = hasSpoofedWifiData(wifiObj)
                        if (mockWifi) {
                            param.result = hasWifiData
                        }
                    }
                })

            // getConnectionInfo() — 返回伪造的 WifiInfo 对象
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getConnectionInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        val connectedWifi = selectSpoofedWifi(wifiObj)
                        
                        if (connectedWifi != null) {
                            try {
                                param.result = buildSpoofedWifiInfo(classLoader, connectedWifi)
                            } catch (e: Throwable) { /* 忽略 */ }
                        } else {
                            try {
                                val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
                                val fakeWifiInfo = XposedHelpers.newInstance(wifiInfoClass)
                                try { XposedHelpers.setObjectField(fakeWifiInfo, "mBSSID", "02:00:00:00:00:00") } catch(e:Throwable){}
                                try { XposedHelpers.setObjectField(fakeWifiInfo, "mMacAddress", "02:00:00:00:00:00") } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mNetworkId", -1) } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mRssi", -127) } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mLinkSpeed", -1) } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mFrequency", -1) } catch(e:Throwable){}
                                param.result = fakeWifiInfo
                            } catch (e: Throwable) {}
                        }
                    }
                })

            // getConfiguredNetworks()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getConfiguredNetworks",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        param.result = java.util.ArrayList<Any>()
                    }
                })

            // getDhcpInfo()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getDhcpInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val connectedWifi = selectSpoofedWifi(config.optJSONObject("wifi_json"))
                        if (connectedWifi == null) {
                            param.result = null
                            return
                        }
                        try {
                            val dhcpClass = XposedHelpers.findClass("android.net.DhcpInfo", classLoader)
                            val dhcp = XposedHelpers.newInstance(dhcpClass)
                            XposedHelpers.setIntField(dhcp, "ipAddress", 0x6401A8C0.toInt())
                            XposedHelpers.setIntField(dhcp, "gateway", 0x0101A8C0)     // 192.168.1.1
                            XposedHelpers.setIntField(dhcp, "netmask", 0x00FFFFFF)     // 255.255.255.0
                            XposedHelpers.setIntField(dhcp, "dns1", 0x0101A8C0)        // 192.168.1.1
                            XposedHelpers.setIntField(dhcp, "dns2", 0x08080808)        // 8.8.8.8
                            XposedHelpers.setIntField(dhcp, "serverAddress", 0x0101A8C0)
                            param.result = dhcp
                        } catch (e: Throwable) { /* 忽略 */ }
                    }
                })
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 4. NetworkInfo.getExtraInfo() ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.NetworkInfo", classLoader, "getExtraInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val networkType = runCatching {
                            XposedHelpers.callMethod(param.thisObject!!, "getType") as Int
                        }.getOrNull()
                        if (networkType != 1) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        val connectedWifi = selectSpoofedWifi(wifiObj)
                        if (connectedWifi != null) {
                            param.result = "\"${wifiSsid(connectedWifi)}\""
                        } else {
                            param.result = null
                        }
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 5. WifiScanner Hook ──
        try {
            val wifiScannerClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiScanner", classLoader)
            if (wifiScannerClass != null) {
                val scannerHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        
                        param.result = null
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        fun dispatchWifiScanResults(fakeScanDataArray: Any) {
                            val candidates = param.args.filterNotNull().asReversed()
                            var delivered = false
                            for (candidate in candidates) {
                                if (XposedHelpers.tryCallMethodDeep(candidate, "onResults", fakeScanDataArray)) {
                                    delivered = true
                                    break
                                }
                            }
                            if (!delivered) {
                                for (candidate in candidates) {
                                    if (XposedHelpers.tryCallMethodDeep(candidate, "onSuccess")) {
                                        delivered = true
                                        break
                                    }
                                }
                            }
                            if (!delivered) {
                                XposedBridge.logOpenCellIdEvery(
                                    "wifiScanner:no-callback",
                                    "WifiScanner.startScan intercepted but no listener callback accepted args=${param.args.map { it?.javaClass?.name }}",
                                    30_000L
                                )
                            }
                        }
                        if (wifiObj != null) {
                            try {
                                val scanResultClass = XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                                val baseTimestamp = android.os.SystemClock.elapsedRealtimeNanos()
                                val fakeList = java.util.ArrayList<Any>()
                                
                                fun addFakeScanResult(wifi: org.json.JSONObject) {
                                    val fakeScanResult = XposedHelpers.newInstance(scanResultClass)
                                    val finalSsid = wifiSsid(wifi)
                                    val bssidVal = wifiBssid(wifi)
                                    XposedHelpers.setObjectField(fakeScanResult, "SSID", finalSsid)
                                    XposedHelpers.setObjectField(fakeScanResult, "BSSID", bssidVal)
                                    val level = wifiRssi(wifi)
                                    XposedHelpers.setIntField(fakeScanResult, "level", level)
                                    XposedHelpers.setIntField(fakeScanResult, "frequency", wifi.optInt("frequency", 2412))
                                    XposedHelpers.setObjectField(fakeScanResult, "capabilities", wifi.optString("capabilities", "[WPA2-PSK-CCMP][ESS]"))
                                    try {
                                        val offsetNanos = (rng.nextInt(200_000) * 1000L)
                                        XposedHelpers.setLongField(fakeScanResult, "timestamp", (baseTimestamp - offsetNanos) / 1000)
                                    } catch (e: Throwable) {}
                                    fakeList.add(fakeScanResult)
                                }

                                val connectedWifi = selectSpoofedWifi(wifiObj)
                                if (connectedWifi != null) {
                                    addFakeScanResult(connectedWifi)
                                }

                                val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
                                if (nearbyArray != null && nearbyArray.length() > 0) {
                                    for (i in 0 until nearbyArray.length()) {
                                        val wifi = nearbyArray.getJSONObject(i)
                                        if (connectedWifi != null && wifiBssid(wifi).equals(wifiBssid(connectedWifi), ignoreCase = true)) continue
                                        addFakeScanResult(wifi)
                                    }
                                }

                                if (fakeList.isNotEmpty()) {
                                    val scanResultArray = java.lang.reflect.Array.newInstance(scanResultClass, fakeList.size)
                                    for (i in 0 until fakeList.size) {
                                        java.lang.reflect.Array.set(scanResultArray, i, fakeList[i])
                                    }
                                    
                                    // 构造 ScanData 对象（包含 ScanResult 数组）
                                    val scanDataClass = XposedHelpers.findClass("android.net.wifi.WifiScanner\$ScanData", classLoader)
                                    val fakeScanData = XposedHelpers.newInstance(scanDataClass, 0, 0, scanResultArray)
                                    val fakeScanDataArray = java.lang.reflect.Array.newInstance(scanDataClass, 1)
                                    java.lang.reflect.Array.set(fakeScanDataArray, 0, fakeScanData)
                                    
                                    // 主动回调 Listener，把假数据塞回去
                                    dispatchWifiScanResults(fakeScanDataArray)
                                } else {
                                    val scanDataClass = XposedHelpers.findClass("android.net.wifi.WifiScanner\$ScanData", classLoader)
                                    val emptyScanData = XposedHelpers.newInstance(scanDataClass, 0, 0, java.lang.reflect.Array.newInstance(scanResultClass, 0))
                                    val fakeScanDataArray = java.lang.reflect.Array.newInstance(scanDataClass, 1)
                                    java.lang.reflect.Array.set(fakeScanDataArray, 0, emptyScanData)
                                    dispatchWifiScanResults(fakeScanDataArray)
                                }
                            } catch (e: Throwable) {
                                XposedBridge.logOpenCellIdEvery(
                                    "wifi-scanner-build:${e.javaClass.name}",
                                    "WifiScanner simulated result build failed: ${e.javaClass.simpleName}: ${e.message}",
                                    60_000L
                                )
                            }
                        } else {
                            try {
                                val scanDataClass = XposedHelpers.findClass("android.net.wifi.WifiScanner\$ScanData", classLoader)
                                val scanResultClass = XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                                val emptyScanData = XposedHelpers.newInstance(scanDataClass, 0, 0, java.lang.reflect.Array.newInstance(scanResultClass, 0))
                                val fakeScanDataArray = java.lang.reflect.Array.newInstance(scanDataClass, 1)
                                java.lang.reflect.Array.set(fakeScanDataArray, 0, emptyScanData)
                                dispatchWifiScanResults(fakeScanDataArray)
                            } catch (e: Throwable) { /* 忽略 */ }
                        }
                    }
                }
                
                // startScan(ScanSettings, ScanListener) 和重载
                XposedBridge.hookAllMethods(wifiScannerClass, "startScan", scannerHook)
            }
        } catch (e: Throwable) { XposedBridge.log(e) }

        XposedBridge.log("[LocationSpoofer] Wi-Fi environment hooks installed")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 基站/蜂窝网络环境伪造 — 覆盖 TelephonyManager / PhoneStateListener
    // ══════════════════════════════════════════════════════════════════════════
    private fun hookCellEnvironment(classLoader: ClassLoader) {
        XposedBridge.logOpenCellId("Installing cell hooks classLoader=$classLoader")

        // ── 1. 基站信息伪造（CellLocation / AllCellInfo / NeighboringCellInfo）──
        val cellHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val methodName = param.method!!.name
                val config = readConfig() ?: return
                if (!shouldMockCell(config)) return
                val cellCountForLog = config.optJSONArray("cell_json")?.length() ?: 0
                XposedBridge.logOpenCellIdEvery(
                    "$methodName:called:$cellCountForLog",
                    "$methodName called with cell simulation active; cellJsonCount=$cellCountForLog"
                )

                when (methodName) {
                    "getCellLocation" -> {
                        param.result = buildFakeCellLocation(classLoader, config)
                    }

                    "getAllCellInfo" -> {
                        try {
                            val fakeCells = buildFakeCellInfoList(classLoader, config)
                            XposedBridge.logOpenCellIdEvery(
                                "getAllCellInfo:return:${fakeCells.size}",
                                "getAllCellInfo returning fakeCells=${fakeCells.size}"
                            )
                            param.result = fakeCells
                        } catch (e: Throwable) {
                            XposedBridge.logOpenCellId("getAllCellInfo build failed: $e")
                            param.result = java.util.ArrayList<Any>()
                        }
                    }

                    "getNeighboringCellInfo" -> {
                        XposedBridge.logOpenCellId("getNeighboringCellInfo returning empty list")
                        param.result = java.util.ArrayList<Any>()
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getAllCellInfo", cellHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getCellLocation", cellHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getNeighboringCellInfo", cellHook)
            XposedBridge.logOpenCellId("Installed TelephonyManager getAllCellInfo/getCellLocation/getNeighboringCellInfo hooks")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install basic TelephonyManager cell hooks failed: $e")
        }

        // ── 2. TelephonyManager 元数据 Hook ──
        // 防止 MCC/MNC/运营商名称/网络类型泄漏真实地理位置
        // 高德用 getNetworkOperator() 验证基站数据是否与 GPS 位置地理一致
        val telephonyMetaHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val methodName = param.method!!.name
                val config = readConfig() ?: return
                if (!shouldMockCell(config)) return
                val cellArray = config.optJSONArray("cell_json")
                val cell = firstServiceCell(cellArray)
                XposedBridge.logOpenCellIdEvery(
                    "$methodName:called:${cellArray?.length() ?: 0}",
                    "$methodName called with cell simulation active; cellJsonCount=${cellArray?.length() ?: 0}"
                )
                when (methodName) {
                    "getNetworkOperator" -> param.result = cellOperatorNumeric(cell) ?: ""
                    "getNetworkOperatorName" -> param.result = cellOperatorName(cell)
                    "getNetworkCountryIso" -> param.result = ""
                    "getNetworkType", "getDataNetworkType", "getVoiceNetworkType" ->
                        param.result = cellNetworkType(cell)
                    "getDataState" -> param.result = if (cell != null) 2 else 0
                    "isDataEnabled", "isDataConnectionAllowed" -> param.result = cell != null
                    "getPhoneType" -> param.result = cellPhoneType(cell)
                    "getServiceState", "getServiceStateForSlot" ->
                        param.result = buildFakeServiceState(classLoader, cellArray)
                    "getSignalStrength" -> param.result = buildFakeSignalStrength(classLoader, config)
                }
            }
        }

        val telephonyMetaMethods = listOf(
            "getNetworkOperator", "getNetworkOperatorName", "getNetworkCountryIso",
            "getNetworkType", "getDataNetworkType", "getVoiceNetworkType", "getPhoneType",
            "getServiceState", "getServiceStateForSlot", "getSignalStrength",
            "getDataState", "isDataEnabled", "isDataConnectionAllowed"
        )
        for (method in telephonyMetaMethods) {
            try {
                XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
                    method,
                    telephonyMetaHook
                )
                XposedBridge.logOpenCellId("Installed TelephonyManager.$method hook")
            } catch (e: Throwable) {
                XposedBridge.logOpenCellId("Install TelephonyManager.$method hook failed: $e")
            }
        }

        // ── 3. PhoneStateListener 回调拦截 ──
        // 防止应用通过 TelephonyManager.listen() 的 LISTEN_CELL_INFO 回调
        // 绕过 getAllCellInfo() 的 Hook 获取真实基站数据
        try {
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", classLoader, "listen",
                "android.telephony.PhoneStateListener",
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args.firstOrNull() ?: return
                        // Keep the original registration intact. The callback method itself
                        // checks the latest config so an existing listener automatically
                        // returns to normal system data as soon as simulation stops.
                        installCellDeliveryHooks(listener, classLoader)
                    }
                }
            )
            XposedBridge.logOpenCellId("Installed TelephonyManager.listen hook")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install TelephonyManager.listen hook failed: $e")
        }

// ── 4. TelephonyManager.requestCellInfoUpdate 异步刷新拦截 (Android 10+) ──
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
                "requestCellInfoUpdate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callback = param.args.firstOrNull { candidate ->
                            candidate != null && candidate.javaClass.methods.any {
                                it.name == "onCellInfo"
                            }
                        } ?: return
                        // Let the platform perform the request. The callback is rewritten only
                        // at delivery time. For the standard Executor overload, avoid a real
                        // modem refresh while simulation is active and dispatch from memory.
                        installCellDeliveryHooks(callback, classLoader)
                        val config = readConfig() ?: return
                        if (!shouldMockCell(config)) return
                        val executor = param.args.firstOrNull {
                            it is java.util.concurrent.Executor
                        } as? java.util.concurrent.Executor ?: return
                        param.result = null
                        executor.execute {
                            val freshConfig = readConfig()?.takeIf { shouldMockCell(it) }
                            val deliveredCells = if (freshConfig != null) {
                                buildFakeCellInfoList(classLoader, freshConfig)
                            } else {
                                // This request was already intercepted while simulation was
                                // active. Complete its callback contract even if stop/off wins
                                // the race before the Executor runs; never resurrect a modem
                                // refresh merely to service an outstanding simulated request.
                                java.util.ArrayList<Any>()
                            }
                            XposedHelpers.tryCallMethodDeep(callback, "onCellInfo", deliveredCells)
                        }
                    }
                }
            )
            XposedBridge.logOpenCellId("Installed TelephonyManager.requestCellInfoUpdate hook")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install TelephonyManager.requestCellInfoUpdate hook failed: $e")
        }

        // ── 5. TelephonyCallback 拦截 (Android 12+ / API 31+) ──
        // registerTelephonyCallback 替代了旧版 listen()，
        // 通过 TelephonyCallback.CellInfoListener 接收基站变化。
        // 需要 hook 注册过程，对每个 callback 实例的 onCellInfoChanged 进行拦截。
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
                "registerTelephonyCallback",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callbackBase = runCatching {
                            XposedHelpers.findClass("android.telephony.TelephonyCallback", classLoader)
                        }.getOrNull() ?: return
                        val callback = param.args.firstOrNull { candidate ->
                            candidate != null && callbackBase.isInstance(candidate)
                        } ?: return
                        installCellDeliveryHooks(callback, classLoader)
                    }
                }
            )
            XposedBridge.logOpenCellId("Installed TelephonyManager.registerTelephonyCallback hook")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install TelephonyManager.registerTelephonyCallback hook failed: $e")
        }

        XposedBridge.logOpenCellId("Cell environment hooks installed")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 网络连接层伪造 — 覆盖 ConnectivityManager / NetworkCapabilities / NetworkInterface
    // ══════════════════════════════════════════════════════════════════════════
    private fun hookConnectivityLayer(classLoader: ClassLoader) {
        val buildFakeNetworkInfo = fun(): Any? {
            try {
                val networkInfoClass = XposedHelpers.findClass("android.net.NetworkInfo", classLoader)
                val fakeNetworkInfo = XposedHelpers.newInstance(networkInfoClass, 1, 0, "WIFI", "")
                XposedHelpers.callMethod(fakeNetworkInfo, "setIsAvailable", true)
                try {
                    val stateEnum = XposedHelpers.findClass("android.net.NetworkInfo\$State", classLoader)
                    XposedHelpers.setObjectField(fakeNetworkInfo, "mState", stateEnum.getField("CONNECTED").get(null))
                } catch (e: Throwable) { /* 忽略 */ }
                try {
                    val detailedStateEnum = XposedHelpers.findClass("android.net.NetworkInfo\$DetailedState", classLoader)
                    XposedHelpers.setObjectField(fakeNetworkInfo, "mDetailedState", detailedStateEnum.getField("CONNECTED").get(null))
                } catch (e: Throwable) { /* 忽略 */ }
                return fakeNetworkInfo
            } catch (e: Throwable) { return null }
        }

        // ── 1. 强制让系统以为连着 Wi-Fi ──
        val networkInfoHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!shouldMockWifi(config)) return
                val wifiObj = config.optJSONObject("wifi_json")
                val hasWifiData = selectSpoofedWifi(wifiObj) != null
                if (hasWifiData) {
                    val fakeInfo = buildFakeNetworkInfo()
                    if (fakeInfo != null) {
                        param.result = fakeInfo
                    }
                } else {
                    // 如果用户要求模拟 Wi-Fi，但实际上数据库里没有 Wi-Fi 数据
                    // 我们需要向系统返回 Wi-Fi 断开的状态
                    val currentInfo = param.result
                    if (currentInfo != null) {
                        try {
                            val type = XposedHelpers.callMethod(currentInfo, "getType") as Int
                            if (type == 1) { // TYPE_WIFI
                                val stateEnum = XposedHelpers.findClass("android.net.NetworkInfo\$State", classLoader)
                                XposedHelpers.setObjectField(currentInfo, "mState", stateEnum.getField("DISCONNECTED").get(null))
                                XposedHelpers.callMethod(currentInfo, "setIsAvailable", false)
                                param.result = currentInfo
                            }
                        } catch (e: Throwable) {
                        }
                    }
                }
            }
        }

        try { XposedHelpers.findAndHookMethod("android.net.ConnectivityManager", classLoader, "getActiveNetworkInfo", networkInfoHook) } catch (e: Throwable) {}
        try { XposedHelpers.findAndHookMethod("android.net.ConnectivityManager", classLoader, "getNetworkInfo", Int::class.javaPrimitiveType, networkInfoHook) } catch (e: Throwable) {}

        // ── 2. NetworkCapabilities 包含 WifiInfo ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.ConnectivityManager", classLoader,
                "getNetworkCapabilities",
                "android.net.Network",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        
                        val nc = param.result ?: return
                        try {
                            val wifiObj = config.optJSONObject("wifi_json")
                            val firstWifi = selectSpoofedWifi(wifiObj)
                            if (firstWifi != null) {
                                val fakeWifiInfo = buildSpoofedWifiInfo(classLoader, firstWifi)
                                
                                // Inject TRANSPORT_WIFI (1) into NetworkCapabilities so DevCheck sees it as Wi-Fi
                                try {
                                    val field = nc.javaClass.getDeclaredField("mTransportTypes")
                                    field.isAccessible = true
                                    val currentTypes = field.getLong(nc)
                                    field.setLong(nc, currentTypes or (1L shl 1))
                                } catch (e: Throwable) {
                                    try {
                                        XposedHelpers.callMethod(nc, "addTransportType", 1)
                                    } catch (e2: Throwable) {}
                                }
                                
                                XposedBridge.logEvery(
                                    "fakeWifiInfo:${wifiBssid(firstWifi)}",
                                    "[LocationSpoofer] fakeWifiInfo active ssid=${wifiSsid(firstWifi)} bssid=${wifiBssid(firstWifi)} rssi=${wifiRssi(firstWifi)} freq=${firstWifi.optInt("frequency", 2412)}"
                                )
                                XposedHelpers.setObjectField(nc, "mTransportInfo", fakeWifiInfo)
                            } else {
                                // 库中无 Wi-Fi 数据，移除 TransportInfo 以伪造非 Wi-Fi 环境
                                try { XposedHelpers.setObjectField(nc, "mTransportInfo", null) } catch (e: Throwable) {}
                                XposedBridge.logEvery(
                                    "fakeWifiInfo:none",
                                    "[LocationSpoofer] fakeWifiInfo: no wifi data, removed TransportInfo"
                                )
                            }
                        } catch (e: Throwable) {
                            XposedBridge.logOpenCellIdEvery(
                                "network-capabilities-wifi:${e.javaClass.name}",
                                "NetworkCapabilities Wi-Fi overlay failed: ${e.javaClass.simpleName}: ${e.message}",
                                60_000L
                            )
                        }
                    }
                }
            )
        } catch (e: Throwable) { /* 忽略 */ }

        // ── 3. NetworkInterface.getNetworkInterfaces() ──
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.NetworkInterface", classLoader, "getNetworkInterfaces",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val result = param.result as? java.util.Enumeration<*> ?: return
                        val filtered = java.util.Collections.list(result).filter { iface ->
                            val name = try {
                                (iface as java.net.NetworkInterface).name
                            } catch (e: Throwable) { "" }
                            !name.startsWith("wlan") && !name.startsWith("p2p") && !name.startsWith("swlan")
                        }
                        param.result = java.util.Collections.enumeration(filtered)
                    }
                }
            )
        } catch (e: Throwable) { /* 忽略 */ }

        XposedBridge.log("[LocationSpoofer] Connectivity layer hooks installed")
    }
    /**
     * 通过反射+Parcel机制构造CellInfoLte对象列表
     *
     * CellInfoLte/CellIdentityLte等类的构造器在Android各版本中签名不同,
     * 直接new会因API版本差异崩溃。通过反射调用内部构造器并设置字段值,
     * 兼容Android 7.0~14。
     *
     * 数据策略:
     * - Only consume cell_json populated from local scans or OpenCellID normalization.
     * - Do not generate coordinate-derived tower identities when cell_json is empty.
     * - Return an empty list in fail-closed mode so real base stations remain blocked.
     *
     * @param classLoader 目标App的ClassLoader
     * @return CellInfo objects built from cell_json only.
     */
    private fun buildFakeCellInfoList(
        classLoader: ClassLoader, config: org.json.JSONObject?
    ): java.util.ArrayList<Any> {
        val result = java.util.ArrayList<Any>()
        
        val cellArray = config?.optJSONArray("cell_json")
        XposedBridge.logOpenCellIdEvery(
            "buildFakeCellInfoList:called:${cellArray?.length() ?: 0}",
            "buildFakeCellInfoList called cellJsonCount=${cellArray?.length() ?: 0}"
        )
        if (cellArray != null && cellArray.length() > 0) {
            var gsmCount = 0
            var wcdmaCount = 0
            var lteCount = 0
            var nrCount = 0
            var firstSummary: String? = null
            for (i in 0 until cellArray.length()) {
                try {
                    val obj = cellArray.getJSONObject(i)
                    val type = explicitCellType(obj)
                    if (type == null) {
                        XposedBridge.logOpenCellId("Skipping cell_json[$i] because radio type is missing or unsupported")
                        continue
                    }
                    val isRegistered = obj.optBoolean("isRegistered", false)
                    val tacOrLac = cellAreaCode(obj, 0)
                    val ciOrCid = cellIdentityCode(obj, 0)
                    if (tacOrLac <= 0 || ciOrCid <= 0) {
                        XposedBridge.logOpenCellId("Skipping cell_json[$i] because area or identity is missing: $obj")
                        continue
                    }
                    val mcc = jsonIntValue(obj, "mcc")?.takeIf { it in 100..999 }
                    val mnc = jsonIntValue(obj, "mnc", "net")?.takeIf { it in 0..999 }
                    if (mcc == null || mnc == null) {
                        XposedBridge.logOpenCellId("Skipping cell_json[$i] because MCC/MNC is missing")
                        continue
                    }
                    when (type) {
                        "GSM" -> gsmCount++
                        "WCDMA", "UMTS" -> wcdmaCount++
                        "NR" -> nrCount++
                        else -> lteCount++
                    }
                    val pci = jsonIntValue(obj, "pci", "psc")?.takeIf { it >= 0 } ?: 0
                    val dbm = signalDbm(obj, i)
                    if (dbm == null) {
                        XposedBridge.logOpenCellIdEvery(
                            "cell-build-missing-signal:$i:$type",
                            "Skipping cell_json[$i] because signal strength is missing",
                            60_000L
                        )
                        continue
                    }
                    val rsrq = signalRsrq(obj, i)
                    val sinr = signalSinr(obj, i)
                    if (firstSummary == null) {
                        firstSummary = "$type/$mcc-$mnc area=$tacOrLac identity=$ciOrCid dbm=$dbm registered=$isRegistered"
                    }
                    if (VERBOSE_CELL_BUILD_LOGS) {
                        XposedBridge.logOpenCellId(
                            "buildFakeCellInfoList source[$i] radio=${obj.optString("radio", "")} type=$type registered=$isRegistered mcc=$mcc mnc=$mnc area=$tacOrLac identity=$ciOrCid pci=$pci dbm=$dbm"
                        )
                    }
                    
                    // 1. 寻找并构造具体的 CellInfo 派生类
                    val cellInfoClass = when (type) {
                        "GSM" -> XposedHelpers.findClass("android.telephony.CellInfoGsm", classLoader)
                        "WCDMA", "UMTS" -> XposedHelpers.findClass("android.telephony.CellInfoWcdma", classLoader)
                        "NR" -> XposedHelpers.findClass("android.telephony.CellInfoNr", classLoader)
                        else -> XposedHelpers.findClass("android.telephony.CellInfoLte", classLoader)
                    }
                    val cellInfo = XposedHelpers.newInstance(cellInfoClass)
                    
                    // 设置注册标志（Android 9 及以下用 mRegistered；Android 10+ 用 mCellConnectionStatus）
                    // CONNECTION_NONE=0, CONNECTION_PRIMARY_SERVING=1, CONNECTION_SECONDARY_SERVING=2
                    val connectionStatus = if (isRegistered) 1 else 0
                    try { XposedHelpers.setBooleanField(cellInfo, "mRegistered", isRegistered) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(cellInfo, "mCellConnectionStatus", connectionStatus) } catch (_: Throwable) {}
                    try { if (isRegistered) XposedHelpers.callMethod(cellInfo, "setRegistered", true) } catch (_: Throwable) {}

                    try { XposedHelpers.setLongField(cellInfo, "mTimeStamp", android.os.SystemClock.elapsedRealtimeNanos()) } catch (e: Throwable) {}

                    // 2. 构造 CellIdentity（尝试多种有参构造器，避免 final 字段反射问题）
                    val cellIdentityClass = when (type) {
                        "GSM" -> XposedHelpers.findClass("android.telephony.CellIdentityGsm", classLoader)
                        "WCDMA", "UMTS" -> XposedHelpers.findClass("android.telephony.CellIdentityWcdma", classLoader)
                        "NR" -> XposedHelpers.findClass("android.telephony.CellIdentityNr", classLoader)
                        else -> XposedHelpers.findClass("android.telephony.CellIdentityLte", classLoader)
                    }
                    val mccStr = mcc.toString()
                    val mncStr = if (mnc < 10) "0$mnc" else mnc.toString()
                    val cellIdentity = constructCellIdentityByType(
                        type, cellIdentityClass, mcc, mccStr, mnc, mncStr, tacOrLac, ciOrCid, pci
                    )
                    if (VERBOSE_CELL_BUILD_LOGS) {
                        XposedBridge.logOpenCellId("Built $type identity: MCC=$mcc MNC=$mnc TAC/LAC=$tacOrLac CI/CID=$ciOrCid PCI=$pci -> ${cellIdentity.javaClass.simpleName}")
                    }

                    // 验证注入是否成功（如果 getCi()/getLac() 返回 Integer.MAX_VALUE 说明注入失败）
                    try {
                        val verifyMethod = when (type) {
                            "LTE"        -> "getCi"
                            "GSM"        -> "getLac"
                            "WCDMA", "UMTS" -> "getLac"
                            "NR"         -> "getPci"
                            else         -> "getCi"
                        }
                        val readBack = XposedHelpers.callMethod(cellIdentity, verifyMethod) as? Int
                        if (readBack == Int.MAX_VALUE || readBack == -1) {
                            XposedBridge.logOpenCellId("WARNING: $type.$verifyMethod()=$readBack, identity injection may have failed")
                        } else if (VERBOSE_CELL_BUILD_LOGS) {
                            XposedBridge.logOpenCellId("VERIFY OK: $type.$verifyMethod()=$readBack")
                        }
                    } catch (_: Throwable) {}

                    // 将 CellIdentity 存入 CellInfo (兼容新老版本字段名)
                    val identityField = when (type) {
                        "GSM" -> "mCellIdentityGsm"
                        "WCDMA", "UMTS" -> "mCellIdentityWcdma"
                        "NR" -> "mCellIdentityNr"
                        else -> "mCellIdentityLte"
                    }
                    try { XposedHelpers.setObjectField(cellInfo, identityField, cellIdentity) } catch (e: Throwable) {}
                    try { XposedHelpers.setObjectField(cellInfo, "mCellIdentity", cellIdentity) } catch (e: Throwable) {}

                    // 3. 构造并配置对应的 CellSignalStrength
                    val cssClass = when (type) {
                        "GSM" -> XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", classLoader)
                        "WCDMA", "UMTS" -> XposedHelpers.findClass("android.telephony.CellSignalStrengthWcdma", classLoader)
                        "NR" -> XposedHelpers.findClass("android.telephony.CellSignalStrengthNr", classLoader)
                        else -> XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", classLoader)
                    }
                    val css = XposedHelpers.newInstance(cssClass)
                    
                    when (type) {
                        "GSM" -> {
                            val asu = ((dbm + 113) / 2).coerceIn(0, 31)
                            try { XposedHelpers.setIntField(css, "mRssi", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mGsmSignalStrength", asu) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSignalStrength", asu) } catch (e: Throwable) {}
                        }
                        "WCDMA", "UMTS" -> {
                            try { XposedHelpers.setIntField(css, "mRssi", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mRscp", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSignalStrength", dbm) } catch (e: Throwable) {}
                        }
                        "NR" -> {
                            try { XposedHelpers.setIntField(css, "mCsiRsrp", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSsRsrp", dbm) } catch (e: Throwable) {}
                            if (rsrq != null) {
                                try { XposedHelpers.setIntField(css, "mCsiRsrq", rsrq) } catch (e: Throwable) {}
                                try { XposedHelpers.setIntField(css, "mSsRsrq", rsrq) } catch (e: Throwable) {}
                            }
                            if (sinr != null) {
                                try { XposedHelpers.setIntField(css, "mCsiSinr", (sinr / 10).coerceIn(-10, 35)) } catch (e: Throwable) {}
                                try { XposedHelpers.setIntField(css, "mSsSinr", (sinr / 10).coerceIn(-10, 35)) } catch (e: Throwable) {}
                            }
                        }
                        else -> { // LTE
                            try { XposedHelpers.setIntField(css, "mRsrp", dbm) } catch (e: Throwable) {}
                            if (rsrq != null) try { XposedHelpers.setIntField(css, "mRsrq", rsrq) } catch (e: Throwable) {}
                            if (sinr != null) try { XposedHelpers.setIntField(css, "mRssnr", sinr) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSignalStrength", dbm + 113) } catch (e: Throwable) {}
                        }
                    }

                    // 将 CellSignalStrength 存入 CellInfo (兼容新老版本字段名)
                    val cssField = when (type) {
                        "GSM" -> "mCellSignalStrengthGsm"
                        "WCDMA", "UMTS" -> "mCellSignalStrengthWcdma"
                        "NR" -> "mCellSignalStrengthNr"
                        else -> "mCellSignalStrengthLte"
                    }
                    try { XposedHelpers.setObjectField(cellInfo, cssField, css) } catch (e: Throwable) {}
                    try { XposedHelpers.setObjectField(cellInfo, "mCellSignalStrength", css) } catch (e: Throwable) {}

                    result.add(cellInfo)
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellIdEvery(
                        "cell-build:$i:${e.javaClass.name}",
                        "buildFakeCellInfoList failed at cell_json[$i]: ${e.javaClass.simpleName}: ${e.message}",
                        60_000L
                    )
                }
            }
            XposedBridge.logOpenCellIdEvery(
                "buildFakeCellInfoList:return:$lteCount:$nrCount:$wcdmaCount:$gsmCount:${result.size}",
                "buildFakeCellInfoList returning ${result.size} cells from cell_json types=LTE:$lteCount NR:$nrCount WCDMA:$wcdmaCount GSM:$gsmCount first=$firstSummary"
            )
            return result
        }

        XposedBridge.logOpenCellIdEvery(
            "buildFakeCellInfoList:empty",
            "buildFakeCellInfoList has no cell_json; returning empty list",
            60_000L
        )
        return result
    }

    private fun normalizeCellTypeOrNull(rawType: String): String? =
        when (rawType.trim().uppercase(java.util.Locale.US)) {
            "GSM", "GPRS", "EDGE" -> "GSM"
            "UMTS", "WCDMA", "HSPA", "HSPAP", "HSPA+" -> "WCDMA"
            "LTE", "LTE_CA", "4G" -> "LTE"
            "NR", "NR5G", "5G" -> "NR"
            else -> null
        }

    private fun explicitCellType(cell: org.json.JSONObject?): String? {
        cell ?: return null
        val raw = cell.optString("type", cell.optString("radio", ""))
        return normalizeCellTypeOrNull(raw)
    }

    private fun normalizeCellType(rawType: String): String =
        normalizeCellTypeOrNull(rawType) ?: "UNKNOWN"

    private fun cellAreaCode(cell: org.json.JSONObject, default: Int): Int =
        positiveJsonInt(cell, "tac", "lac", "area", default = default)

    private fun cellIdentityCode(cell: org.json.JSONObject, default: Int): Int =
        positiveJsonInt(cell, "ci", "cid", "cellid", "cell", default = default)

    private fun positiveJsonInt(cell: org.json.JSONObject, vararg keys: String, default: Int): Int {
        for (key in keys) {
            if (!cell.has(key) || cell.isNull(key)) continue
            val value = cell.optInt(key, Int.MIN_VALUE)
            if (value > 0) return value
            val parsed = cell.optString(key).toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return default
    }

    private fun rawSignalDbm(cell: org.json.JSONObject): Int? = when {
            cell.optInt("dbm", Int.MIN_VALUE) in -140..-40 -> cell.optInt("dbm")
            cell.optInt("averageSignalStrength", Int.MIN_VALUE) in -140..-40 -> cell.optInt("averageSignalStrength")
            cell.optInt("signal", Int.MIN_VALUE) in -140..-40 -> cell.optInt("signal")
            else -> null
        }

    private fun signalDbm(cell: org.json.JSONObject, index: Int): Int? {
        val base = rawSignalDbm(cell) ?: return null
        return (base + signalJitterDelta(index, 0, 5)).coerceIn(-140, -40)
    }

    private fun signalRsrq(cell: org.json.JSONObject, index: Int): Int? {
        val direct = cell.optInt("rsrq", Int.MIN_VALUE)
        if (direct !in -30..-3) return null
        return (direct + signalJitterDelta(index, 1, 3)).coerceIn(-30, -3)
    }

    private fun signalSinr(cell: org.json.JSONObject, index: Int): Int? {
        val direct = cell.optInt("sinr", Int.MIN_VALUE)
        val normalized = when {
            direct in -20..40 -> direct * 10
            direct in -200..300 -> direct
            else -> return null
        }
        return (normalized + signalJitterDelta(index, 2, 30)).coerceIn(-50, 180)
    }

    private fun signalJitterDelta(index: Int, salt: Int, maxAmplitude: Int): Int {
        val config = lastConfig
        if (config?.optBoolean("signal_jitter_enabled", true) == false) return 0
        val level = config?.optInt("signal_jitter_level", 40)?.coerceIn(0, 100) ?: 40
        if (level <= 0 || maxAmplitude <= 0) return 0
        val amplitude = maxOf(1, (maxAmplitude * level / 100.0).toInt())
        val tick = System.currentTimeMillis() / 2500L
        val seed = tick + index * 97L + salt * 997L + (config?.optLong("start_timestamp", 0L) ?: 0L)
        val rng = java.util.Random(seed)
        return rng.nextInt(amplitude * 2 + 1) - amplitude
    }

    private fun firstUsableCell(cells: org.json.JSONArray?): org.json.JSONObject? {
        if (cells == null) return null
        for (index in 0 until cells.length()) {
            val cell = cells.optJSONObject(index) ?: continue
            if (cell.optBoolean("isRegistered", false) &&
                cellAreaCode(cell, 0) > 0 && cellIdentityCode(cell, 0) > 0
            ) return cell
        }
        return null
    }

    private fun firstServiceCell(cells: org.json.JSONArray?): org.json.JSONObject? {
        if (cells == null) return null
        for (index in 0 until cells.length()) {
            val cell = cells.optJSONObject(index) ?: continue
            if (cellAreaCode(cell, 0) <= 0 || cellIdentityCode(cell, 0) <= 0) continue
            if (!cell.optBoolean("isRegistered", false)) continue
            if (cellOperatorNumeric(cell) == null || explicitCellType(cell) == null) continue
            return cell
        }
        return null
    }

    private fun firstSignalCell(cells: org.json.JSONArray?): org.json.JSONObject? {
        if (cells == null) return null
        for (index in 0 until cells.length()) {
            val cell = cells.optJSONObject(index) ?: continue
            if (cellAreaCode(cell, 0) <= 0 || cellIdentityCode(cell, 0) <= 0) continue
            if (!cell.optBoolean("isRegistered", false)) continue
            if (cellOperatorNumeric(cell) == null || explicitCellType(cell) == null) continue
            if (rawSignalDbm(cell) == null) continue
            return cell
        }
        return null
    }

    private fun firstCell(config: org.json.JSONObject?): org.json.JSONObject? =
        firstUsableCell(config?.optJSONArray("cell_json"))

    private fun jsonIntValue(cell: org.json.JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!cell.has(key) || cell.isNull(key)) continue
            val value = runCatching {
                val raw = cell.get(key)
                when (raw) {
                    is Number -> raw.toInt()
                    else -> raw.toString().trim().toInt()
                }
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun cellOperatorNumeric(cell: org.json.JSONObject?): String? {
        cell ?: return null
        val mcc = jsonIntValue(cell, "mcc")?.takeIf { it in 100..999 } ?: return null
        val mnc = jsonIntValue(cell, "mnc", "net")?.takeIf { it in 0..999 } ?: return null
        val mncWidth = if (mnc >= 100) 3 else 2
        return String.format(java.util.Locale.US, "%03d%0${mncWidth}d", mcc, mnc)
    }

    private fun cellOperatorName(cell: org.json.JSONObject?): String {
        cell ?: return ""
        for (key in arrayOf("operatorName", "operator_name", "carrier")) {
            val value = cell.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return cellOperatorNumeric(cell) ?: ""
    }

    private fun cellNetworkType(cell: org.json.JSONObject?): Int {
        return when (explicitCellType(cell)) {
            "GSM" -> 16
            "WCDMA" -> 3
            "LTE" -> 13
            "NR" -> 20
            else -> 0
        }
    }

    private fun cellPhoneType(cell: org.json.JSONObject?): Int =
        if (explicitCellType(cell) != null) 1 else 0

    private fun buildFakeCellLocation(
        classLoader: ClassLoader,
        config: org.json.JSONObject?
    ): Any? {
        val cell = firstCell(config) ?: return null
        val area = cellAreaCode(cell, 0)
        val identity = cellIdentityCode(cell, 0)
        if (area <= 0 || identity <= 0) return null
        return runCatching {
            val clazz = XposedHelpers.findClass(
                "android.telephony.gsm.GsmCellLocation",
                classLoader
            )
            XposedHelpers.newInstance(clazz).also {
                XposedHelpers.callMethod(it, "setLacAndCid", area, identity)
            }
        }.getOrNull()
    }

    private fun installCellDeliveryHooks(callback: Any, classLoader: ClassLoader) {
        val callbackClass = callback.javaClass
        fun freshConfig(): org.json.JSONObject? = readConfig()?.takeIf { shouldMockCell(it) }
        fun hasParameters(method: java.lang.reflect.Method, vararg typeNames: String): Boolean =
            method.parameterTypes.map { it.name } == typeNames.toList()

        fun installCellInfoMethod(methodName: String) {
            hookDynamicMethodOnce(
                callbackClass,
                methodName,
                { hasParameters(it, "java.util.List") },
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = freshConfig() ?: return
                        if (param.args.isNotEmpty()) {
                            param.args[0] = buildFakeCellInfoList(classLoader, config)
                        } else {
                            param.result = null
                        }
                    }
                }
            )
        }
        installCellInfoMethod("onCellInfoChanged")
        installCellInfoMethod("onCellInfo")

        hookDynamicMethodOnce(
            callbackClass,
            "onCellLocationChanged",
            { hasParameters(it, "android.telephony.CellLocation") },
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = freshConfig() ?: return
                    val fake = buildFakeCellLocation(classLoader, config)
                    if (fake != null && param.args.isNotEmpty()) param.args[0] = fake else param.result = null
                }
            }
        )
        hookDynamicMethodOnce(
            callbackClass,
            "onServiceStateChanged",
            { hasParameters(it, "android.telephony.ServiceState") },
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = freshConfig() ?: return
                    val fake = buildFakeServiceState(classLoader, config.optJSONArray("cell_json"))
                    if (fake != null && param.args.isNotEmpty()) param.args[0] = fake else param.result = null
                }
            }
        )
        hookDynamicMethodOnce(
            callbackClass,
            "onSignalStrengthsChanged",
            { hasParameters(it, "android.telephony.SignalStrength") },
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = freshConfig() ?: return
                    val fake = buildFakeSignalStrength(classLoader, config)
                    if (fake != null && param.args.isNotEmpty()) param.args[0] = fake else param.result = null
                }
            }
        )
        hookDynamicMethodOnce(
            callbackClass,
            "onSignalStrengthChanged",
            { hasParameters(it, "int") },
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = freshConfig() ?: return
                    val cell = firstSignalCell(config.optJSONArray("cell_json"))
                    val dbm = cell?.let { signalDbm(it, 0) }
                    if (dbm != null && param.args.isNotEmpty()) {
                        param.args[0] = ((dbm + 113) / 2).coerceIn(0, 31)
                    } else {
                        param.result = null
                    }
                }
            }
        )

        fun suppressSensitiveCallback(methodName: String, vararg parameterTypes: String) {
            hookDynamicMethodOnce(
                callbackClass,
                methodName,
                { hasParameters(it, *parameterTypes) },
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (freshConfig() != null) param.result = null
                    }
                }
            )
        }
        suppressSensitiveCallback(
            "onRegistrationFailed",
            "android.telephony.CellIdentity",
            "java.lang.String",
            "int",
            "int",
            "int"
        )
        suppressSensitiveCallback("onBarringInfoChanged", "android.telephony.BarringInfo")
        hookDynamicMethodOnce(
            callbackClass,
            "onPhysicalChannelConfigChanged",
            { hasParameters(it, "java.util.List") },
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (freshConfig() != null && param.args.isNotEmpty()) {
                        param.args[0] = java.util.ArrayList<Any>()
                    }
                }
            }
        )
        XposedBridge.logOpenCellIdEvery(
            "cell-delivery-hooks:${callbackClass.name}",
            "Installed delivery-time cell hooks on ${callbackClass.name}",
            60_000L
        )
    }

    private fun buildFakeServiceState(classLoader: ClassLoader, cellArray: org.json.JSONArray?): Any? {
        val cell = firstServiceCell(cellArray) ?: return null
        val operator = cellOperatorNumeric(cell) ?: return null
        val operatorName = cellOperatorName(cell).ifBlank { operator }
        val networkType = cellNetworkType(cell).takeIf { it != 0 } ?: return null
        XposedBridge.logOpenCellIdEvery(
            "buildFakeServiceState:called:${cellArray?.length() ?: 0}",
            "buildFakeServiceState called cellJsonCount=${cellArray?.length() ?: 0}"
        )
        return try {
            val clazz = XposedHelpers.findClass("android.telephony.ServiceState", classLoader)
            val state = XposedHelpers.newInstance(clazz)

            try { XposedHelpers.callMethod(state, "setState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setVoiceRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setDataRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setOperatorName", operatorName, operatorName, operator) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setRoaming", false) } catch (_: Throwable) {}
            try { XposedHelpers.setBooleanField(state, "mIsManualNetworkSelection", false) } catch (_: Throwable) {}
            try {
                val nriBuilderClass = XposedHelpers.findClass("android.telephony.NetworkRegistrationInfo" + "$" + "Builder", classLoader)
                
                val psBuilder = XposedHelpers.newInstance(nriBuilderClass)
                XposedHelpers.callMethod(psBuilder, "setDomain", 2) // DOMAIN_PS = 2
                XposedHelpers.callMethod(psBuilder, "setTransportType", 1) // TRANSPORT_TYPE_WWAN = 1
                XposedHelpers.callMethod(psBuilder, "setRegistrationState", 1) // REGISTRATION_STATE_HOME = 1
                XposedHelpers.callMethod(psBuilder, "setAccessNetworkTechnology", networkType)
                val psNri = XposedHelpers.callMethod(psBuilder, "build")
                
                val csBuilder = XposedHelpers.newInstance(nriBuilderClass)
                XposedHelpers.callMethod(csBuilder, "setDomain", 1) // DOMAIN_CS = 1
                XposedHelpers.callMethod(csBuilder, "setTransportType", 1) // TRANSPORT_TYPE_WWAN = 1
                XposedHelpers.callMethod(csBuilder, "setRegistrationState", 1) // REGISTRATION_STATE_HOME = 1
                XposedHelpers.callMethod(csBuilder, "setAccessNetworkTechnology", networkType)
                val csNri = XposedHelpers.callMethod(csBuilder, "build")

                XposedHelpers.callMethod(state, "addNetworkRegistrationInfo", psNri)
                XposedHelpers.callMethod(state, "addNetworkRegistrationInfo", csNri)
            } catch (e: Throwable) {
                XposedBridge.logOpenCellIdEvery(
                    "service-state-registration:${e.javaClass.name}",
                    "ServiceState registration build failed: ${e.javaClass.simpleName}: ${e.message}",
                    60_000L
                )
            }
            try { XposedHelpers.setIntField(state, "mVoiceRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(state, "mDataRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mVoiceOperatorAlphaLong", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mVoiceOperatorAlphaShort", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mVoiceOperatorNumeric", operator) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mDataOperatorAlphaLong", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mDataOperatorAlphaShort", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mDataOperatorNumeric", operator) } catch (_: Throwable) {}
            XposedBridge.logOpenCellIdEvery(
                "buildFakeServiceState:success:$operator:$operatorName",
                "buildFakeServiceState success operator=$operator operatorName=$operatorName"
            )
            state
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("buildFakeServiceState failed", e)
            null
        }
    }

    private fun buildFakeSignalStrength(classLoader: ClassLoader, config: org.json.JSONObject?): Any? {
        val cellArray = config?.optJSONArray("cell_json")
        val first = firstSignalCell(cellArray) ?: return null
        val type = explicitCellType(first) ?: return null
        val dbm = signalDbm(first, 0) ?: return null
        val rsrq = signalRsrq(first, 0)
        val sinr = signalSinr(first, 0)
        val cellCount = cellArray?.length() ?: 0
        XposedBridge.logOpenCellIdEvery(
            "buildFakeSignalStrength:called:$type:$cellCount",
            "buildFakeSignalStrength called type=$type cellJsonCount=$cellCount"
        )
        return try {
            val clazz = XposedHelpers.findClass("android.telephony.SignalStrength", classLoader)
            val signalStrength = XposedHelpers.newInstance(clazz)
            val componentClassName = when (type) {
                "GSM" -> "android.telephony.CellSignalStrengthGsm"
                "WCDMA" -> "android.telephony.CellSignalStrengthWcdma"
                "NR" -> "android.telephony.CellSignalStrengthNr"
                else -> "android.telephony.CellSignalStrengthLte"
            }
            val component = XposedHelpers.newInstance(
                XposedHelpers.findClass(componentClassName, classLoader)
            )
            when (type) {
                "GSM" -> {
                    val gsmDbm = dbm.coerceIn(-113, -51)
                    val asu = ((gsmDbm + 113) / 2).coerceIn(0, 31)
                    try { XposedHelpers.setIntField(component, "mRssi", gsmDbm) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mGsmSignalStrength", asu) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mSignalStrength", asu) } catch (_: Throwable) {}
                }
                "WCDMA" -> {
                    try { XposedHelpers.setIntField(component, "mRssi", dbm) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mRscp", dbm) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mSignalStrength", dbm) } catch (_: Throwable) {}
                }
                "NR" -> {
                    try { XposedHelpers.setIntField(component, "mCsiRsrp", dbm) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mSsRsrp", dbm) } catch (_: Throwable) {}
                    if (rsrq != null) {
                        try { XposedHelpers.setIntField(component, "mCsiRsrq", rsrq) } catch (_: Throwable) {}
                        try { XposedHelpers.setIntField(component, "mSsRsrq", rsrq) } catch (_: Throwable) {}
                    }
                    if (sinr != null) {
                        val nrSinr = (sinr / 10).coerceIn(-10, 35)
                        try { XposedHelpers.setIntField(component, "mCsiSinr", nrSinr) } catch (_: Throwable) {}
                        try { XposedHelpers.setIntField(component, "mSsSinr", nrSinr) } catch (_: Throwable) {}
                    }
                }
                else -> {
                    try { XposedHelpers.setIntField(component, "mRsrp", dbm) } catch (_: Throwable) {}
                    if (rsrq != null) try { XposedHelpers.setIntField(component, "mRsrq", rsrq) } catch (_: Throwable) {}
                    if (sinr != null) try { XposedHelpers.setIntField(component, "mRssnr", sinr) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mSignalStrength", dbm + 113) } catch (_: Throwable) {}
                }
            }
            val signalField = when (type) {
                "GSM" -> "mGsm"
                "WCDMA" -> "mWcdma"
                "NR" -> "mNr"
                else -> "mLte"
            }
            var attached = runCatching {
                XposedHelpers.setObjectField(signalStrength, signalField, component)
                true
            }.getOrDefault(false)
            if (!attached) {
                attached = runCatching {
                    XposedHelpers.setObjectField(signalStrength, "mCellSignalStrengths", listOf(component))
                    true
                }.getOrDefault(false)
            }
            if (!attached) return null
            XposedBridge.logOpenCellIdEvery(
                "buildFakeSignalStrength:success:$type",
                "buildFakeSignalStrength success type=$type dbm=$dbm"
            )
            signalStrength
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "signal-strength-build:${e.javaClass.name}",
                "buildFakeSignalStrength failed: ${e.javaClass.simpleName}: ${e.message}",
                60_000L
            )
            null
        }
    }

    /**
     * 构造 CellIdentity 派生类，完整兼容 Android 9 ~ Android 14+。
     * 按以下优先级尝试：
     *   1. 各 Android 版本已知的有参构造器（最优，字段由构造器写入）
     *   2. sun.misc.Unsafe.allocateInstance + 反射写字段
     *      （字段初始值为 0 而非 MAX_VALUE，避免 JIT 内联问题）
     *   3. 最小参数构造器 + 默认值填充（最后手段）
     */
    private fun constructCellIdentityByType(
        type: String,
        clazz: Class<*>,
        mcc: Int, mccStr: String,
        mnc: Int, mncStr: String,
        tacOrLac: Int, ciOrCid: Int, pci: Int
    ): Any {
        val ctors = clazz.declaredConstructors.onEach { it.isAccessible = true }

        // 按参数个数匹配构造器，调用失败则返回 null
        fun tryNewInstance(vararg args: Any?): Any? = ctors
            .firstOrNull { it.parameterCount == args.size }
            ?.runCatching { newInstance(*args) }
            ?.getOrNull()

        // ── 阶段一：尝试各版本有参构造器 ──
        val identity: Any? = when (type) {
            "LTE" -> {
                // Android 9 / API 28: (int mcc, int mnc, int ci, int pci, int tac) — 5 参数
                tryNewInstance(mcc, mnc, ciOrCid, pci, tacOrLac)
                // Android 10 / API 29: (int ci, int pci, int tac, int earfcn, int bandwidth, String mcc, String mnc, String alphaLong, String alphaShort) — 9 参数
                    ?: tryNewInstance(ciOrCid, pci, tacOrLac, 0, 0, mccStr, mncStr, "", "")
                // Android 11+ / API 30+: (int ci, int pci, int tac, int earfcn, int[] bands, int bandwidth, String mcc, String mnc, String alphaLong, String alphaShort, Collection, ClosedSubscriberGroupInfo) — 12 参数
                    ?: tryNewInstance(ciOrCid, pci, tacOrLac, 0, IntArray(0), 0, mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            "GSM" -> {
                // Android 9 / API 28: (int mcc, int mnc, int lac, int cid) — 4 参数
                tryNewInstance(mcc, mnc, tacOrLac, ciOrCid)
                // Android 9 / API 28: (int mcc, int mnc, int lac, int cid, int arfcn, int bsic) — 6 参数
                    ?: tryNewInstance(mcc, mnc, tacOrLac, ciOrCid, 0, 0)
                // Android 10 / API 29: (int lac, int cid, int arfcn, int bsic, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, 0, 0, mccStr, mncStr, "", "")
                // Android 11+ / API 30+: 10 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, 0, 0, mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            "WCDMA", "UMTS" -> {
                // Android 9: (int mcc, int mnc, int lac, int cid, int psc, int uarfcn) — 6 参数
                tryNewInstance(mcc, mnc, tacOrLac, ciOrCid, pci, 0)
                // Android 10: (int lac, int cid, int psc, int uarfcn, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, pci, 0, mccStr, mncStr, "", "")
                // Android 11+: 10 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, pci, 0, mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            "NR" -> {
                // Android 11+: (int pci, int tac, long nci, int[] bands, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                tryNewInstance(pci, tacOrLac, ciOrCid.toLong(), IntArray(0), mccStr, mncStr, "", "")
                // Android 12+: 10 参数
                    ?: tryNewInstance(pci, tacOrLac, ciOrCid.toLong(), IntArray(0), mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            else -> null
        }

        if (identity != null) return identity

        // ── 阶段二：Unsafe.allocateInstance + 反射写字段 ──
        // 字段初始值为 0（非 MAX_VALUE），避免 JIT 内联问题
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
            val obj = allocate.invoke(unsafe, clazz) as Any

            // 设置类型标识（CellIdentity.mType）
            val typeInt = when (type) { "GSM" -> 1; "LTE" -> 3; "WCDMA", "UMTS" -> 4; "NR" -> 6; else -> 3 }
            try { XposedHelpers.setIntField(obj, "mType", typeInt) } catch (_: Throwable) {}
            // MCC/MNC（Int 版 Android 9，String 版 Android 10+）
            try { XposedHelpers.setIntField(obj, "mMcc", mcc) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(obj, "mMnc", mnc) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mMccStr", mccStr) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mMncStr", mncStr) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mAlphaLong", "") } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mAlphaShort", "") } catch (_: Throwable) {}

            when (type) {
                "LTE" -> {
                    try { XposedHelpers.setIntField(obj, "mCi", ciOrCid) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mTac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mPci", pci) } catch (_: Throwable) {}
                    try { XposedHelpers.setObjectField(obj, "mBands", IntArray(0)) } catch (_: Throwable) {}
                }
                "GSM" -> {
                    try { XposedHelpers.setIntField(obj, "mLac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mCid", ciOrCid) } catch (_: Throwable) {}
                }
                "WCDMA", "UMTS" -> {
                    try { XposedHelpers.setIntField(obj, "mLac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mCid", ciOrCid) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mPsc", pci) } catch (_: Throwable) {}
                }
                "NR" -> {
                    try { XposedHelpers.setIntField(obj, "mTac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setLongField(obj, "mNci", ciOrCid.toLong()) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mPci", pci) } catch (_: Throwable) {}
                    try { XposedHelpers.setObjectField(obj, "mBands", IntArray(0)) } catch (_: Throwable) {}
                }
            }
            XposedBridge.logEvery(
                "cellMockUnsafe:$type",
                "[LocationSpoofer][CellMock] Unsafe.allocateInstance active for $type"
            )
            return obj
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "cell-identity-unsafe:$type:${e.javaClass.name}",
                "CellIdentity Unsafe fallback failed type=$type: ${e.javaClass.simpleName}: ${e.message}",
                60_000L
            )
        }

        // ── 阶段三：最小参数构造器 + 安全默认值填充（绝对保底）──
        val minCtor = ctors.minByOrNull { it.parameterCount }
            ?: throw IllegalStateException("No constructors for ${clazz.name}")
        val safeArgs = minCtor.parameterTypes.map { t ->
            when {
                t == Int::class.javaPrimitiveType    -> 0
                t == Long::class.javaPrimitiveType   -> 0L
                t == Boolean::class.javaPrimitiveType -> false
                t == Float::class.javaPrimitiveType  -> 0f
                t == Double::class.javaPrimitiveType -> 0.0
                t == IntArray::class.java            -> IntArray(0)
                t == java.util.Collection::class.java || t.isAssignableFrom(java.util.ArrayList::class.java) -> emptyList<Any>()
                else -> null
            }
        }.toTypedArray()
        val fallbackObj = try {
            minCtor.newInstance(*safeArgs)
        } catch (e: Throwable) {
            throw IllegalStateException("Cannot construct ${clazz.name}: $e")
        }
        // 写字段
        try { XposedHelpers.setIntField(fallbackObj, "mMcc", mcc) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(fallbackObj, "mMnc", mnc) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(fallbackObj, "mMccStr", mccStr) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(fallbackObj, "mMncStr", mncStr) } catch (_: Throwable) {}
        when (type) {
            "LTE" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mCi", ciOrCid) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mTac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mPci", pci) } catch (_: Throwable) {}
            }
            "GSM" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mLac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mCid", ciOrCid) } catch (_: Throwable) {}
            }
            "WCDMA", "UMTS" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mLac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mCid", ciOrCid) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mPsc", pci) } catch (_: Throwable) {}
            }
            "NR" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mTac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setLongField(fallbackObj, "mNci", ciOrCid.toLong()) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mPci", pci) } catch (_: Throwable) {}
            }
        }
        XposedBridge.logEvery(
            "cellMockMinCtor:$type",
            "[LocationSpoofer][CellMock] MinCtor fallback used for $type identity"
        )
        return fallbackObj
    }

    data class SatelliteData(
        val svid: Int,
        val type: Int, // 1=GPS, 3=GLONASS, 5=BDS
        val elevation: Float,
        val azimuth: Float,
        val cn0: Float,
        val usedInFix: Boolean
    )

    private var cachedSatellites: Array<SatelliteData>? = null
    private var lastSatelliteUpdate: Long = 0L
    private var isSpoofingActiveCache: Boolean = false
    private var spoofingCountCache: Int = 0
    private var nmeaGsvPageCounter: Int = 0

    private fun updateSatelliteCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (cachedSatellites == null || now - lastSatelliteUpdate > 1000) {
            val config = readConfig()
            isSpoofingActiveCache = shouldDeliverLocation(config)
            if (isSpoofingActiveCache) {
                val count = (config?.optInt("satellite_count", 20) ?: 20).coerceIn(0, 64)
                spoofingCountCache = count
                val timeSec = now / 1000.0
                val enableJitter = config?.optBoolean("enable_jitter", true) ?: true

                val visible = ArrayList<SatelliteData>(count)
                var candidate = 0
                val maxCandidates = maxOf(240, count * 24)
                while (visible.size < count && candidate < maxCandidates) {
                    val sat = generateSatelliteData(candidate, config ?: JSONObject(), enableJitter, timeSec)
                    if (sat.elevation >= 3f) {
                        visible.add(sat)
                    }
                    candidate++
                }
                while (visible.size < count) {
                    visible.add(generateFallbackSkySatellite(visible.size, config ?: JSONObject(), enableJitter, timeSec))
                }

                val usedCount = when {
                    count <= 0 -> 0
                    count <= 4 -> count
                    else -> maxOf(4, (count * 0.65f).toInt()).coerceAtMost(count)
                }
                val sorted = visible.sortedWith(compareByDescending<SatelliteData> { it.cn0 }.thenByDescending { it.elevation })
                val usedIndices = chooseUsedSatelliteIndices(sorted, usedCount, now)
                val newCache = sorted
                    .mapIndexed { index, sat -> sat.copy(usedInFix = index in usedIndices) }
                    .toTypedArray()
                cachedSatellites = newCache
            } else {
                spoofingCountCache = 0
                cachedSatellites = emptyArray()
            }
            lastSatelliteUpdate = now
        }
    }

    private fun chooseUsedSatelliteIndices(sats: List<SatelliteData>, usedCount: Int, now: Long): Set<Int> {
        if (usedCount <= 0 || sats.isEmpty()) return emptySet()
        if (usedCount >= sats.size) return sats.indices.toSet()
        val selected = linkedSetOf<Int>()
        val rng = java.util.Random((now / 20_000L) + (lastConfig?.optLong("start_timestamp", 0L) ?: 0L))
        selected.add(0)
        if (sats.size > 1) selected.add(1)
        var guard = 0
        while (selected.size < usedCount && guard < sats.size * 12) {
            guard++
            val candidate = rng.nextInt(sats.size)
            val sat = sats[candidate]
            val quality = ((sat.cn0 - 18f) / 28f).coerceIn(0.15f, 1f)
            val lateBoost = if (candidate > usedCount) 0.18 else 0.0
            if (rng.nextDouble() < (quality * 0.72 + lateBoost).coerceIn(0.05, 0.95)) {
                selected.add(candidate)
            }
        }
        var fallback = 0
        while (selected.size < usedCount && fallback < sats.size) {
            selected.add(fallback++)
        }
        return selected
    }

    private fun getCachedSatellite(satIndex: Int): SatelliteData? {
        if (!isSpoofingActiveCache || cachedSatellites == null || cachedSatellites!!.isEmpty()) return null
        val safeIndex = satIndex % cachedSatellites!!.size
        return cachedSatellites!![safeIndex]
    }

    private fun getApiSatellite(satIndex: Int): SatelliteData? {
        updateSatelliteCacheIfNeeded()
        val sats = cachedSatellites ?: return null
        if (!isSpoofingActiveCache || satIndex !in sats.indices) return null
        return sats[satIndex]
    }

    private fun generateSatelliteData(
        satIndex: Int,
        config: JSONObject,
        enableJitter: Boolean,
        timeSec: Double
    ): SatelliteData {
        val updateIntervalSec = 4.0
        val steppedTimeSec = Math.floor((timeSec + satIndex) / updateIntervalSec) * updateIntervalSec - satIndex
        val targetLat = config.optDouble("wgs84_lat", config.optDouble("lat", 0.0))
        val targetLng = config.optDouble("wgs84_lng", config.optDouble("lng", 0.0))

        val type = when (satIndex % 10) {
            0, 1, 2, 3, 4 -> 1
            5, 6 -> 3
            else -> 5
        }
        val svid = when (type) {
            1 -> 1 + (satIndex * 7) % 32 // GPS
            3 -> 1 + (satIndex * 3) % 24 // GLONASS (GnssStatus standard is 1-24)
            else -> 1 + (satIndex * 5) % 63 // BDS
        }

        val orbitalRadiusMeters = when (type) {
            3 -> 25_510_000.0
            5 -> 27_906_000.0
            else -> 26_560_000.0
        }
        val inclinationDeg = when (type) {
            3 -> 64.8
            5 -> 55.0
            else -> 55.0
        }
        val orbitalPeriodSec = when (type) {
            3 -> 40_544.0
            5 -> 46_800.0
            else -> 43_082.0
        }

        val raan = Math.toRadians(((satIndex * 47 + type * 23) % 360).toDouble())
        val inclination = Math.toRadians(inclinationDeg)
        val meanAnomaly = Math.toRadians(((satIndex * 137 + 53) % 360).toDouble()) +
            (2.0 * Math.PI * steppedTimeSec / orbitalPeriodSec)

        val cosRaan = Math.cos(raan)
        val sinRaan = Math.sin(raan)
        val cosInc = Math.cos(inclination)
        val sinInc = Math.sin(inclination)
        val xOrb = orbitalRadiusMeters * Math.cos(meanAnomaly)
        val yOrb = orbitalRadiusMeters * Math.sin(meanAnomaly)
        val xEci = cosRaan * xOrb - sinRaan * cosInc * yOrb
        val yEci = sinRaan * xOrb + cosRaan * cosInc * yOrb
        val zEci = sinInc * yOrb

        val gmst = Math.toRadians(normalizeDegrees(280.46061837 + 360.98564736629 * (steppedTimeSec / 86400.0)))
        val cosGmst = Math.cos(gmst)
        val sinGmst = Math.sin(gmst)
        val satX = cosGmst * xEci + sinGmst * yEci
        val satY = -sinGmst * xEci + cosGmst * yEci
        val satZ = zEci

        val look = lookAnglesFromReceiver(targetLat, targetLng, satX, satY, satZ)
        val noise = if (enableJitter) {
            val dynamicRng = java.util.Random((steppedTimeSec / 3.0).toLong() + satIndex * 17L)
            (dynamicRng.nextDouble() - 0.5) * 3.0
        } else 0.0
        val cn0 = (18.0 + (look.second.coerceIn(0.0, 85.0) / 85.0) * 24.0 + noise)
            .coerceIn(12.0, 46.0)
            .toFloat()

        return SatelliteData(
            svid = svid,
            type = type,
            elevation = look.second.toFloat().coerceIn(-90f, 90f),
            azimuth = look.first.toFloat(),
            cn0 = cn0,
            usedInFix = false
        )
    }

    private fun generateFallbackSkySatellite(
        satIndex: Int,
        config: JSONObject,
        enableJitter: Boolean,
        timeSec: Double
    ): SatelliteData {
        val targetLat = config.optDouble("wgs84_lat", config.optDouble("lat", 0.0))
        val targetLng = config.optDouble("wgs84_lng", config.optDouble("lng", 0.0))
        val type = when (satIndex % 10) {
            0, 1, 2, 3, 4 -> 1
            5, 6 -> 3
            else -> 5
        }
        val svid = when (type) {
            1 -> 1 + (satIndex * 7) % 32
            3 -> 1 + (satIndex * 3) % 24
            else -> 1 + (satIndex * 5) % 63
        }
        val phase = normalizeDegrees(targetLng + targetLat * 0.37 + satIndex * 137.5 + timeSec / 240.0)
        val elevation = (18.0 + ((satIndex * 23 + Math.abs(targetLat).toInt()) % 58)).toFloat()
        val cn0Noise = if (enableJitter) {
            val rng = java.util.Random((timeSec / 4.0).toLong() + satIndex * 31L)
            (rng.nextDouble() - 0.5) * 3.0
        } else 0.0
        val cn0 = (20.0 + elevation / 90.0 * 22.0 + cn0Noise).coerceIn(14.0, 45.0).toFloat()
        return SatelliteData(svid, type, elevation, phase.toFloat(), cn0, false)
    }

    private fun lookAnglesFromReceiver(
        latDeg: Double,
        lngDeg: Double,
        satX: Double,
        satY: Double,
        satZ: Double
    ): Pair<Double, Double> {
        val lat = Math.toRadians(latDeg)
        val lng = Math.toRadians(lngDeg)
        val earthRadius = 6_378_137.0
        val recX = earthRadius * Math.cos(lat) * Math.cos(lng)
        val recY = earthRadius * Math.cos(lat) * Math.sin(lng)
        val recZ = earthRadius * Math.sin(lat)
        val dx = satX - recX
        val dy = satY - recY
        val dz = satZ - recZ
        val east = -Math.sin(lng) * dx + Math.cos(lng) * dy
        val north = -Math.sin(lat) * Math.cos(lng) * dx -
            Math.sin(lat) * Math.sin(lng) * dy +
            Math.cos(lat) * dz
        val up = Math.cos(lat) * Math.cos(lng) * dx +
            Math.cos(lat) * Math.sin(lng) * dy +
            Math.sin(lat) * dz
        val horizontal = Math.sqrt(east * east + north * north)
        val azimuth = normalizeDegrees(Math.toDegrees(Math.atan2(east, north)))
        val elevation = Math.toDegrees(Math.atan2(up, horizontal))
        return Pair(azimuth, elevation)
    }

    private fun normalizeDegrees(value: Double): Double {
        val mod = value % 360.0
        return if (mod < 0.0) mod + 360.0 else mod
    }

    private fun spoofedBearing(config: JSONObject): Float {
        val base = config.optDouble("sim_bearing", 0.0)
        val enableJitter = config.optBoolean("enable_jitter", true)
        val noise = if (enableJitter) {
            val tick = System.currentTimeMillis() / 1500L
            val rng = java.util.Random(tick + 0xBEEFL)
            (rng.nextDouble() - 0.5) * 2.0
        } else {
            0.0
        }
        return normalizeDegrees(base + noise).toFloat()
    }

    private fun spoofedSpeed(config: JSONObject): Float {
        val base = config.optDouble("sim_speed", 0.0).coerceAtLeast(0.0)
        if (base <= 0.05) return 0f
        val enableJitter = config.optBoolean("enable_jitter", true)
        val noise = if (enableJitter) {
            val tick = System.currentTimeMillis() / 1200L
            val rng = java.util.Random(tick + 0x5EEDL)
            (rng.nextDouble() - 0.5) * minOf(0.4, base * 0.08)
        } else {
            0.0
        }
        return (base + noise).coerceAtLeast(0.05).toFloat()
    }

    private fun hasSpoofedMotion(config: JSONObject): Boolean {
        return spoofedSpeed(config) > 0.1f
    }

    /**
     * 拦截GnssStatus回调,注入伪造的卫星星座数据
     *
     * 反作弊SDK通过registerGnssStatusCallback获取卫星可见数和信噪比(C/N0),
     * 若Location坐标正常但卫星数为0或信噪比全为0,则判定为模拟位置。
     *
     * 伪造策略:
     * - 可见卫星数: 12-18颗(真实室外环境的典型值)
     * - 信噪比(C/N0): 15-40 dB-Hz(真实GPS信号的典型范围)
     * - 卫星类型: GPS(1) + GLONASS(3) + BDS(5)混合星座
     */
    private fun hookGnssStatus(classLoader: ClassLoader) {
        try {
            val locationManagerClazz = XposedHelpers.findClass("android.location.LocationManager", classLoader)
            installGnssStatusGetterHooks(classLoader)
            
            // Hook addGpsStatusListener
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "addGpsStatusListener", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0]
                        if (listener != null) {
                            val clazz = listener.javaClass
                            if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
                                try {
                                    XposedBridge.hookAllMethods(clazz, "onGpsStatusChanged", object : XC_MethodHook() {
                                        private var lastCallTime = 0L
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            val config = readConfig()
                                            if (!shouldDeliverLocation(config)) return
                                            val event = param.args[0] as? Int
                                            if (event == 4) { // GPS_EVENT_SATELLITE_STATUS
                                                val now = android.os.SystemClock.elapsedRealtime()
                                                if (now - lastCallTime < 1000) {
                                                    param.result = null // Throttle
                                                } else {
                                                    lastCallTime = now
                                                }
                                            }
                                        }
                                    })
                                } catch (e: Throwable) {}
                            }
                        }
                    }
                })
            } catch (e: Throwable) { XposedBridge.log(e) }

            // Hook removeGpsStatusListener
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "removeGpsStatusListener", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // No longer needed
                    }
                })
            } catch (e: Throwable) {}

            // Hook registerGnssStatusCallback
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "registerGnssStatusCallback", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        var callbackObj: Any? = null
                        val cbClass = try {
                            classLoader.loadClass("android.location.GnssStatus\$Callback")
                        } catch (e: Exception) { null }

                        for (arg in param.args) {
                            if (arg != null && cbClass != null && cbClass.isInstance(arg)) {
                                callbackObj = arg
                                break
                            }
                        }
                        if (callbackObj != null) {
                            val clazz = callbackObj.javaClass
                            if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
                                try {
                                    XposedBridge.hookAllMethods(clazz, "onSatelliteStatusChanged", object : XC_MethodHook() {
                                        private var lastCallTime = 0L

                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            val config = readConfig()
                                            if (!shouldDeliverLocation(config)) return
                                            val now = android.os.SystemClock.elapsedRealtime()
                                            if (now - lastCallTime < 1000) {
                                                param.result = null // Throttle real callbacks
                                                return
                                            }
                                            lastCallTime = now

                                            if (param.args.isEmpty() || param.args[0] == null) return
                                            updateSatelliteCacheIfNeeded()

                                            // Do not mutate GnssStatus internals here. Android keeps parallel
                                            // arrays for CN0/elevation/azimuth/flags, and field-name drift can
                                            // leave mSvCount larger than one of those arrays. Public getter hooks
                                            // below provide a coherent satellite view without corrupting objects.
                                        }
                                    })
                                } catch (e: Throwable) {}
                            }
                            // Start an active injector so the callback is driven even when the real
                            // GPS has no satellite signal (indoor, airplane mode, etc.).
                            // Without this, onSatelliteStatusChanged is never called by the system,
                            // and satellite view apps see an empty sky regardless of our getter hooks.
                            if (shouldActivelyInjectClientGnss()) {
                                val config = readConfig()
                                if (shouldDeliverLocation(config)) {
                                    startGnssStatusInjector(callbackObj, classLoader)
                                }
                            }
                        }
                    }
                })
            } catch (e: Throwable) { XposedBridge.log(e) }

            // Hook unregisterGnssStatusCallback — cancel any active injector
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "unregisterGnssStatusCallback", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        for (arg in param.args) {
                            if (arg != null) {
                                val key = System.identityHashCode(arg)
                                gnssStatusInjectors.remove(key)?.cancel()
                            }
                        }
                    }
                })
            } catch (e: Throwable) {}

            // Hook GpsStatus.getSatellites() for legacy Apps like DevCheck
            try {
                XposedHelpers.findAndHookMethod("android.location.GpsStatus", classLoader, "getSatellites", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldDeliverLocation(config)) {
                            param.result = createSpoofedGpsSatellites(classLoader)
                        }
                    }
                })
            } catch (e: Throwable) { XposedBridge.log(e) }


            XposedBridge.log("[LocationSpoofer] GnssStatus hooks installed")
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] GnssStatus hook failed: $e")
        }
    }

    /**
     * Actively drives GnssStatus callbacks so satellite view apps see a live spoofed sky
     * even when the real GPS hardware has no signal (indoor / airplane mode).
     *
     * Strategy:
     * - Every 1 second, build a minimal GnssStatus via Builder (Android 7+) or reflection constructor.
     * - Call onSatelliteStatusChanged(gnssStatus) on the registered callback object directly.
     * - The getter hooks installed by installGnssStatusGetterHooks() rewrite the returned values
     *   (getSatelliteCount, getCn0DbHz, etc.) so the app sees spoofed satellite data.
     * - Do NOT mutate GnssStatus parallel arrays; only construct a trivially valid shell.
     */
    private fun startGnssStatusInjector(callbackObj: Any, classLoader: ClassLoader) {
        val key = System.identityHashCode(callbackObj)
        if (gnssStatusInjectors.containsKey(key)) return
        val timer = java.util.Timer("LocationSpoofer_GnssSat_${key}", true)
        gnssStatusInjectors[key] = timer
        XposedBridge.logEvery(
            "gnssInjectorStart:$currentPackageName:${callbackObj.javaClass.simpleName}",
            "[GPS_Spoofer] GnssStatus active injector started pkg=$currentPackageName cb=${callbackObj.javaClass.simpleName}"
        )
        timer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                try {
                    val config = readConfig()
                    if (!shouldDeliverLocation(config)) {
                        gnssStatusInjectors.remove(key)?.cancel()
                        return
                    }
                    updateSatelliteCacheIfNeeded()
                    if (!isSpoofingActiveCache) return
                    val satCount = cachedSatellites?.size ?: 0
                    if (satCount == 0) return

                    // Build the smallest valid GnssStatus that the getter hooks can wrap.
                    // We need to call onSatelliteStatusChanged; the argument just needs to
                    // be a GnssStatus instance — actual field values are overridden by hooks.
                    val gnssStatus = buildMinimalGnssStatus(classLoader, satCount) ?: return

                    // Directly invoke the callback — this is how the satellite view refreshes.
                    XposedHelpers.tryCallMethodDeep(callbackObj, "onSatelliteStatusChanged", gnssStatus)
                } catch (_: Throwable) {}
            }
        }, 200L, 1000L)
    }

    /**
     * Constructs a minimal GnssStatus shell with the given satellite count.
     * Tries GnssStatus.Builder (Android 7+) first, then falls back to the
     * internal constructor via reflection.
     * Returns null if no construction path works on this Android version.
     */
    private fun buildMinimalGnssStatus(classLoader: ClassLoader, satCount: Int): Any? {
        val gnssStatusClazz = XposedHelpers.findClassIfExists("android.location.GnssStatus", classLoader)
            ?: return null

        // Path 1: GnssStatus.Builder (Android 7.0+ / API 24+)
        try {
            val builderClazz = XposedHelpers.findClassIfExists("android.location.GnssStatus\$Builder", classLoader)
            if (builderClazz != null) {
                val builder = builderClazz.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
                val sats = cachedSatellites ?: return null
                for (i in 0 until minOf(satCount, sats.size)) {
                    val sat = sats[i]
                    // addSatellite(constellationType, svid, cn0DbHz, elevation, azimuth,
                    //              hasAlmanac, hasEphemeris, usedInFix)
                    try {
                        val addSat = builderClazz.getMethod(
                            "addSatellite",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType
                        )
                        addSat.invoke(builder, sat.type, sat.svid, sat.cn0,
                            sat.elevation, sat.azimuth, true, true, sat.usedInFix)
                    } catch (_: Throwable) {}
                }
                val buildMethod = builderClazz.getMethod("build")
                return buildMethod.invoke(builder)
            }
        } catch (_: Throwable) {}

        // Path 2: Internal constructor GnssStatus(int, float[], float[], float[], int[])
        // Used on Android 7.0 where the Builder may not be public.
        try {
            val sats = cachedSatellites ?: return null
            val n = minOf(satCount, sats.size)
            val svidsAndFlags = IntArray(n) { i ->
                val sat = sats[i]
                var flags = sat.type shl 3
                if (sat.usedInFix) flags = flags or 0x01
                flags = flags or 0x02 // hasEphemeris
                flags = flags or 0x04 // hasAlmanac
                (sat.svid shl 8) or flags
            }
            val cn0s   = FloatArray(n) { i -> sats[i].cn0 }
            val elvs   = FloatArray(n) { i -> sats[i].elevation }
            val azis   = FloatArray(n) { i -> sats[i].azimuth }
            for (ctor in gnssStatusClazz.declaredConstructors) {
                if (ctor.parameterCount == 5) {
                    ctor.isAccessible = true
                    return ctor.newInstance(n, cn0s, elvs, azis, svidsAndFlags)
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun installGnssStatusGetterHooks(classLoader: ClassLoader) {
        val gnssStatusClazz = XposedHelpers.findClassIfExists("android.location.GnssStatus", classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(
                gnssStatusClazz,
                "getSatelliteCount",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        updateSatelliteCacheIfNeeded()
                        if (isSpoofingActiveCache) {
                            val count = cachedSatellites?.size ?: 0
                            param.result = count
                            XposedBridge.logEvery(
                                "gnssStatusCount:$currentPackageName:$count",
                                "[GPS_Spoofer] GnssStatus.getSatelliteCount spoofed count=$count package=$currentPackageName"
                            )
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        fun hookIndexedInt(methodName: String, value: (SatelliteData) -> Int) {
            try {
                XposedHelpers.findAndHookMethod(
                    gnssStatusClazz,
                    methodName,
                    Int::class.javaPrimitiveType!!,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val sat = getApiSatellite(param.args.getOrNull(0) as? Int ?: return) ?: return
                            param.result = value(sat)
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        fun hookIndexedFloat(methodName: String, value: (SatelliteData) -> Float) {
            try {
                XposedHelpers.findAndHookMethod(
                    gnssStatusClazz,
                    methodName,
                    Int::class.javaPrimitiveType!!,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val sat = getApiSatellite(param.args.getOrNull(0) as? Int ?: return) ?: return
                            param.result = value(sat)
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        fun hookIndexedBoolean(methodName: String, value: (SatelliteData) -> Boolean) {
            try {
                XposedHelpers.findAndHookMethod(
                    gnssStatusClazz,
                    methodName,
                    Int::class.javaPrimitiveType!!,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val sat = getApiSatellite(param.args.getOrNull(0) as? Int ?: return) ?: return
                            param.result = value(sat)
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        hookIndexedInt("getSvid") { it.svid }
        hookIndexedInt("getConstellationType") { it.type }
        hookIndexedFloat("getCn0DbHz") { it.cn0 }
        hookIndexedFloat("getElevationDegrees") { it.elevation }
        hookIndexedFloat("getAzimuthDegrees") { it.azimuth }
        hookIndexedBoolean("hasEphemerisData") { true }
        hookIndexedBoolean("hasAlmanacData") { true }
        hookIndexedBoolean("usedInFix") { it.usedInFix }
        hookIndexedBoolean("hasCarrierFrequencyHz") { true }
        hookIndexedFloat("getCarrierFrequencyHz") { sat ->
            when (sat.type) {
                3 -> 1_602_000_000f
                5 -> 1_561_098_000f
                else -> 1_575_420_000f
            }
        }
        hookIndexedBoolean("hasBasebandCn0DbHz") { true }
        hookIndexedFloat("getBasebandCn0DbHz") { (it.cn0 - 1.5f).coerceAtLeast(0f) }

        try {
            XposedHelpers.findAndHookMethod(
                gnssStatusClazz,
                "getCodeType",
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sat = getApiSatellite(param.args.getOrNull(0) as? Int ?: return) ?: return
                        param.result = if (sat.type == 5) "I" else "C"
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    @Volatile
    private var lastConfig: JSONObject? = null
    @Volatile
    private var lastOpenCellConfigLogKey: String? = null
    @Volatile
    private var lastOpenCellConfigReadFailureLogTime = 0L
    private val providerConfigDebugLastTimes = ConcurrentHashMap<String, Long>()
    private val configPollingClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var configPollIntervalMs = 1_000L
    private val configPollWake = java.util.concurrent.Semaphore(0)
    @Volatile
    private var configChangeCallbackInstalled = false
    private var configChangeCallback: Runnable? = null
    @Volatile
    private var configContentObserverInstalled = false
    private val configContentObserverLock = Any()
    private var configContentObserver: android.database.ContentObserver? = null
    private val lastConfigContentObserverAttemptAt = AtomicLong(0L)
    @Volatile
    private var lastObservedRuntimeFrame: String? = null
    @Volatile
    private var lastObservedStopGeneration: String? = null
    private val localConfigPath = "/data/local/tmp/locationspoofer_config.json"
    private val systemConfigPath = "/data/system/locationspoofer_config.json"
    private val activeConfigTtlMs = 30_000L
    private val inactiveConfigPollIntervalMs = 60_000L
    private val locationDisabledPollIntervalMs = 10_000L
    @Volatile
    private var cachedBootId: String? = null
    @Volatile
    private var cachedBootIdInitialized = false
    private val bootIdLock = Any()
    @Volatile
    private var configDiskReadDisabled = false
    @Volatile
    private var cachedSystemPropertyGetMethod: java.lang.reflect.Method? = null
    private val systemPropertyMethodLock = Any()
    private val highestObservedStopGeneration = AtomicLong(0L)
    private val lastHotStopGenerationReadAt = AtomicLong(0L)
    private val configContentUri = Uri.parse("content://com.suseoaa.locationspoofer.provider")
    private val configContentObserverRetryIntervalMs = 10_000L
    private val hotStopGenerationReadIntervalMs = 250L

    private data class ConfigCandidate(
        val source: String,
        val config: JSONObject,
        val priority: Int
    ) {
        private val semanticFreshness: Long = maxOf(
            config.optLong("heartbeat_at", 0L),
            config.optLong("config_updated_at", 0L)
        )
        val freshness: Long = semanticFreshness.takeIf { it > 0L }
            ?: config.optLong("_file_modified_at", 0L)
        val generation: Long = config.optLong("config_generation", 0L)
        val inactive: Boolean = !config.optBoolean("active", false)
    }

    private data class RuntimeFrame(
        val active: Boolean,
        val snapshotGeneration: Long,
        val heartbeatUptimeMs: Long,
        val latitude: Double,
        val longitude: Double,
        val bearing: Double,
        val speed: Double
    )

    private fun parseRuntimeFrame(raw: String): RuntimeFrame? {
        val parts = raw.split('|')
        if (parts.size != 8 || parts[0] != "1") return null
        val active = when (parts[1]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val generation = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val heartbeatUptimeMs = parts[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val latitudeE7 = parts[4].toLongOrNull() ?: return null
        val longitudeE7 = parts[5].toLongOrNull() ?: return null
        val bearingCentiDegrees = parts[6].toIntOrNull() ?: return null
        val speedCentimetersPerSecond = parts[7].toIntOrNull() ?: return null
        val nowUptime = android.os.SystemClock.uptimeMillis()
        if (heartbeatUptimeMs > nowUptime + 5_000L) return null
        // Current writers always emit a real generation for both active and inactive
        // frames. Reject legacy generation zero so it cannot mask a newer snapshot on
        // a cold process start where no in-memory generation is available for comparison.
        if (generation == 0L || (active && heartbeatUptimeMs == 0L)) return null
        if (latitudeE7 !in -900_000_000L..900_000_000L ||
            longitudeE7 !in -1_800_000_000L..1_800_000_000L ||
            bearingCentiDegrees !in 0..35_999 ||
            speedCentimetersPerSecond !in 0..100_000
        ) return null
        return RuntimeFrame(
            active = active,
            snapshotGeneration = generation,
            heartbeatUptimeMs = heartbeatUptimeMs,
            latitude = latitudeE7 / 10_000_000.0,
            longitude = longitudeE7 / 10_000_000.0,
            bearing = bearingCentiDegrees / 100.0,
            speed = speedCentimetersPerSecond / 100.0
        )
    }

    private fun readStopGeneration(): Long {
        val observed = getSystemProperty("gsm.locsp.stop_generation", "0")
            .toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
        while (true) {
            val previous = highestObservedStopGeneration.get()
            if (observed <= previous) return previous
            if (highestObservedStopGeneration.compareAndSet(previous, observed)) return observed
        }
    }

    /**
     * A SystemProperties callback is process-local and may miss writes made by the root
     * helper. While an active snapshot is cached, sample only the tiny stop fence at a
     * bounded rate so STOP becomes pass-through promptly without restoring disk polling.
     * Inactive configs short-circuit before this method, so normal idle has no extra reads.
     */
    private fun readStopGenerationHotPath(): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastHotStopGenerationReadAt.get()
            if (previous != 0L && now - previous < hotStopGenerationReadIntervalMs) {
                return highestObservedStopGeneration.get()
            }
            if (lastHotStopGenerationReadAt.compareAndSet(previous, now)) {
                return readStopGeneration()
            }
        }
    }

    private fun isStoppedGeneration(generation: Long, stopGeneration: Long): Boolean {
        return stopGeneration > 0L &&
            (generation <= 0L || generation <= stopGeneration)
    }

    private fun normalizedCandidate(
        source: String,
        rawConfig: JSONObject,
        priority: Int,
        runtimeFrame: RuntimeFrame? = null,
        stopGeneration: Long = readStopGeneration()
    ): ConfigCandidate? {
        return try {
            ConfigCandidate(
                source,
                normalizeConfig(rawConfig, runtimeFrame, stopGeneration),
                priority
            )
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "config:normalize:$source:${e.javaClass.name}",
                "readConfig ignored invalid $source config: ${e.javaClass.simpleName}: ${e.message}",
                30_000L
            )
            null
        }
    }

    private fun selectFreshestConfig(candidates: List<ConfigCandidate>): ConfigCandidate? {
        return candidates.maxWithOrNull(
            compareBy<ConfigCandidate> { it.freshness }
                // At an exact freshness tie, an explicit stop is safer and represents the
                // terminal lifecycle transition. This also covers a stop written in the same
                // millisecond as the last active heartbeat.
                .thenBy { if (it.inactive) 1 else 0 }
                .thenBy { it.generation }
                .thenBy { it.priority }
        )
    }

    private fun publishConfigCandidate(
        requestSource: String,
        candidate: ConfigCandidate,
        stopGeneration: Long = readStopGeneration(),
        runtimeFrame: RuntimeFrame? = null
    ): JSONObject {
        var selectedCandidate = candidate
        val cached = lastConfig
        val cachedGeneration = cached?.optLong("config_generation", 0L) ?: 0L
        if (candidate.inactive &&
            cached?.optBoolean("active", false) == true &&
            cachedGeneration > candidate.generation &&
            (stopGeneration == 0L || cachedGeneration > stopGeneration)
        ) {
            // A timed-out older tombstone may land after a newer run. Refresh the
            // newer in-memory snapshot (including TTL validation) before deciding;
            // never let the stale inactive generation overwrite it blindly.
            shallowCopyConfig(cached)?.let { cachedCopy ->
                normalizedCandidate(
                    "memory-newer-than-${candidate.source}",
                    cachedCopy,
                    candidate.priority,
                    runtimeFrame,
                    stopGeneration
                )?.let { selectedCandidate = it }
            }
        }
        val activeChanged = lastConfig?.optBoolean("active", false) !=
            selectedCandidate.config.optBoolean("active", false)
        lastConfig = selectedCandidate.config
        configPollIntervalMs = when {
            !selectedCandidate.config.optBoolean("active", false) -> inactiveConfigPollIntervalMs
            !isSystemLocationSwitchEnabled() -> if (
                configChangeCallbackInstalled || configContentObserverInstalled
            ) {
                inactiveConfigPollIntervalMs
            } else {
                locationDisabledPollIntervalMs
            }
            // Runtime frames normally wake the poller. This fallback exists only to
            // enforce the 30-second TTL after an unexpected writer death.
            configChangeCallbackInstalled || configContentObserverInstalled ->
                activeConfigTtlMs + 1_000L
            else -> 1_000L
        }
        logOpenCellConfigLoaded(
            "$requestSource:${selectedCandidate.source}",
            selectedCandidate.config
        )
        if (activeChanged ||
            (selectedCandidate.config.optBoolean("active", false) && !systemInjectorStarted)
        ) {
            wakeSystemLocationInjector()
        }
        return selectedCandidate.config
    }

    private fun logOpenCellConfigLoaded(source: String, config: JSONObject) {
        val cellArray = config.optJSONArray("cell_json")
        val cellCount = cellArray?.length() ?: 0
        val wifiCount = config.optJSONObject("wifi_json")?.optJSONArray("nearbyWifi")?.length() ?: 0
        val logKey = "${config.optBoolean("active", false)}|${config.optBoolean("fail_closed", false)}|${config.optBoolean("mock_cell", false)}|$cellCount|$wifiCount"
        if (logKey == lastOpenCellConfigLogKey) return
        lastOpenCellConfigLogKey = logKey
        val firstCell = if (cellArray != null && cellArray.length() > 0) cellArray.optJSONObject(0) else null
        val firstSummary = if (firstCell != null) {
            val type = normalizeCellType(firstCell.optString("type", firstCell.optString("radio", "LTE")))
            val area = cellAreaCode(firstCell, 0)
            val identity = cellIdentityCode(firstCell, 0)
            "$type/${cellOperatorNumeric(firstCell) ?: "unknown"} area=$area identity=$identity"
        } else {
            "none"
        }
        XposedBridge.logOpenCellId(
            "readConfig[$source] active=${config.optBoolean("active", false)} failClosed=${config.optBoolean("fail_closed", false)} mockCell=${config.optBoolean("mock_cell", false)} wifiCount=$wifiCount cellJsonCount=$cellCount firstCell=$firstSummary"
        )
    }

    private fun configReadPaths(): Array<String> {
        return if (android.os.Process.myUid() == 1000) {
            arrayOf(systemConfigPath)
        } else {
            arrayOf(localConfigPath)
        }
    }

    private fun logConfigDebugEvery(key: String, msg: String, intervalMs: Long = 60_000L) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = providerConfigDebugLastTimes[key]
        if (last == null || now - last >= intervalMs) {
            if (providerConfigDebugLastTimes.size >= 128 && last == null) {
                providerConfigDebugLastTimes.clear()
            }
            providerConfigDebugLastTimes[key] = now
            android.util.Log.d("LocationSpoofer_Debug", msg)
        }
    }

    private fun shouldSpoofLocation(config: JSONObject?): Boolean {
        if (config?.optBoolean("active", false) != true) return false
        val stopped = isStoppedGeneration(
            config.optLong("config_generation", 0L),
            readStopGenerationHotPath()
        )
        if (stopped) requestConfigRefresh()
        return !stopped
    }

    private fun shouldDeliverLocation(config: JSONObject?): Boolean {
        return shouldSpoofLocation(config) && isSystemLocationSwitchEnabled()
    }

    private fun shouldMockWifi(config: JSONObject?): Boolean {
        return config?.optBoolean("mock_wifi", false) == true &&
            shouldDeliverLocation(config)
    }

    private fun shouldMockCell(config: JSONObject?): Boolean {
        return config?.optBoolean("mock_cell", false) == true &&
            shouldDeliverLocation(config)
    }

    private fun isSystemLocationSwitchEnabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSystemLocationCheckAt < 1_000L) {
            return lastSystemLocationEnabled
        }
        lastSystemLocationCheckAt = now
        val context = currentProcessContext()
        if (context == null) {
            lastSystemLocationEnabled = false
            return false
        }
        val managerState = runCatching {
            val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as?
                android.location.LocationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                manager?.isLocationEnabled
            } else {
                null
            }
        }.getOrNull()
        val settingsState = runCatching {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_OFF
            ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
        }.getOrNull()
        // Unknown state means do not interfere. Returning false here is fail-open
        // for normal Android behaviour because all hooks simply pass through.
        lastSystemLocationEnabled = managerState ?: settingsState ?: false
        return lastSystemLocationEnabled
    }

    private fun currentProcessContext(): android.content.Context? {
        cachedProcessContext?.let { return it }
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (_: Throwable) {
            null
        } ?: try {
            val activityThread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread")
                .invoke(null)
            activityThread?.let { XposedHelpers.callMethod(it, "getSystemContext") as? android.content.Context }
        } catch (_: Throwable) {
            null
        }
        if (context != null) cachedProcessContext = context
        return context
    }

    private fun normalizeConfig(
        config: JSONObject,
        runtimeFrame: RuntimeFrame? = null,
        stopGeneration: Long = readStopGeneration()
    ): JSONObject {
        applyRuntimePropertyOverlay(config, runtimeFrame, stopGeneration)
        if (config.optBoolean("active", false) &&
            isStoppedGeneration(config.optLong("config_generation", 0L), stopGeneration)
        ) {
            markExpiredConfigInactive(config)
        }
        if (config.opt("wifi_json") !is org.json.JSONObject) {
            config.put("wifi_json", emptyWifiPayload())
        }
        if (config.opt("cell_json") !is org.json.JSONArray) {
            config.put("cell_json", org.json.JSONArray())
        }
        config.put("bluetooth_json", org.json.JSONArray())
        if (config.optBoolean("active", false)) {
            val now = System.currentTimeMillis()
            val configBootId = config.optString("boot_id", "")
            val currentBootId = readBootId()
            if (configBootId.isNotEmpty() && currentBootId.isNotEmpty() && configBootId != currentBootId) {
                config.put("active", false)
                config.put("fail_closed", false)
                XposedBridge.logOpenCellIdEvery(
                    "config:boot-expired",
                    "readConfig disabled previous-boot active config",
                    30_000L
                )
            }
            val heartbeatAt = maxOf(
                config.optLong("heartbeat_at", 0L),
                config.optLong("config_updated_at", 0L)
            )
            val heartbeatUptime = config.optLong("heartbeat_uptime_ms", 0L)
            val heartbeatAgeMs = if (heartbeatUptime > 0L) {
                val currentUptime = android.os.SystemClock.uptimeMillis()
                if (heartbeatUptime <= currentUptime) currentUptime - heartbeatUptime else Long.MAX_VALUE
            } else if (heartbeatAt > 0L) {
                (now - heartbeatAt).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
            if (config.optBoolean("active", false) && heartbeatAgeMs == Long.MAX_VALUE) {
                markExpiredConfigInactive(config)
                XposedBridge.logOpenCellIdEvery(
                    "config:heartbeat-missing",
                    "readConfig restored pass-through because active config has no trusted heartbeat",
                    30_000L
                )
            } else if (
                config.optBoolean("active", false) &&
                heartbeatAgeMs > activeConfigTtlMs
            ) {
                markExpiredConfigInactive(config)
                XposedBridge.logOpenCellIdEvery(
                    "config:expired",
                    "readConfig restored pass-through after stale active config age=${heartbeatAgeMs}ms",
                    30_000L
                )
            }
        } else if (!config.has("fail_closed")) {
            config.put("fail_closed", false)
        }
        val lat = config.optDouble("lat", 0.0)
        val lng = config.optDouble("lng", 0.0)
        val wgs84 = gcj02ToWgs84(lat, lng)
        config.put("wgs84_lat", wgs84.first)
        config.put("wgs84_lng", wgs84.second)
        val bd09 = gcj02ToBd09(lat, lng)
        config.put("bd09_lat", bd09.first)
        config.put("bd09_lng", bd09.second)
        config.put(
            "jitter_radius_meters",
            config.optDouble("jitter_radius_meters", DEFAULT_JITTER_RADIUS_METERS)
                .coerceIn(MIN_JITTER_RADIUS_METERS, MAX_JITTER_RADIUS_METERS)
        )
        val jitterSpeed = config.optString("jitter_speed", "MEDIUM").uppercase()
        config.put("jitter_speed", if (jitterSpeed in setOf("SLOW", "MEDIUM", "FAST")) jitterSpeed else "MEDIUM")
        config.put("sim_speed", config.optDouble("sim_speed", 0.0).coerceAtLeast(0.0))
        config.put("satellite_count", config.optInt("satellite_count", 20).coerceIn(0, 64))
        config.put("mock_wifi", config.optBoolean("mock_wifi", false))
        config.put("mock_cell", config.optBoolean("mock_cell", false))
        config.put("mock_bluetooth", false)
        config.put("signal_jitter_enabled", config.optBoolean("signal_jitter_enabled", true))
        config.put("signal_jitter_level", config.optInt("signal_jitter_level", 40).coerceIn(0, 100))
        val wifiMode = config.optString("wifi_connection_mode", "FIXED").uppercase()
        config.put("wifi_connection_mode", if (wifiMode == "RANDOM") "RANDOM" else "FIXED")
        return config
    }

    private fun applyRuntimePropertyOverlay(
        config: JSONObject,
        suppliedRuntimeFrame: RuntimeFrame? = null,
        stopGeneration: Long = readStopGeneration()
    ) {
        val runtimeFrame = suppliedRuntimeFrame ?: parseRuntimeFrame(
            getSystemProperty("gsm.locsp.frame", "")
        )
        if (runtimeFrame != null) {
            val configGeneration = config.optLong("config_generation", 0L)
            if (!runtimeFrame.active) {
                // A delayed stop from an older generation must not turn off a newer
                // active snapshot. Its stop fence still blocks every older/equal run.
                if (runtimeFrame.snapshotGeneration < configGeneration &&
                    !isStoppedGeneration(configGeneration, stopGeneration)
                ) return
                markExpiredConfigInactive(config)
                return
            }
            // Likewise, ignore a delayed active frame when the loaded snapshot is
            // already newer. If both are fenced, fail open to normal platform data.
            if (runtimeFrame.snapshotGeneration < configGeneration &&
                !isStoppedGeneration(configGeneration, stopGeneration)
            ) return
            if (isStoppedGeneration(runtimeFrame.snapshotGeneration, stopGeneration)) {
                markExpiredConfigInactive(config)
                return
            }
            if (!config.optBoolean("active", false)) return
            if (configGeneration <= 0L ||
                runtimeFrame.snapshotGeneration != configGeneration
            ) return
            val previousUptime = config.optLong("heartbeat_uptime_ms", 0L)
            if (previousUptime > runtimeFrame.heartbeatUptimeMs) return
            config.put("lat", runtimeFrame.latitude)
            config.put("lng", runtimeFrame.longitude)
            config.put("sim_bearing", runtimeFrame.bearing)
            config.put("sim_speed", runtimeFrame.speed)
            config.put("heartbeat_uptime_ms", runtimeFrame.heartbeatUptimeMs)
            return
        }

        // Legacy property overlay remains as a compatibility fallback for snapshots
        // created before the compact runtime-frame protocol was introduced.
        if (!config.optBoolean("active", false)) return
        if (getSystemProperty("gsm.locsp.active", "false").lowercase() != "true") return
        val propertyHeartbeat = getSystemProperty("gsm.locsp.heartbeat", "0")
            .toLongOrNull() ?: return
        val configHeartbeat = maxOf(
            config.optLong("heartbeat_at", 0L),
            config.optLong("config_updated_at", 0L)
        )
        if (propertyHeartbeat <= configHeartbeat) return
        val propertyBootId = getSystemProperty("gsm.locsp.boot_id", "")
        val configBootId = config.optString("boot_id", "")
        if (propertyBootId.isNotEmpty() && configBootId.isNotEmpty() &&
            propertyBootId != configBootId
        ) return
        val lat = getSystemProperty("gsm.locsp.lat", "").toDoubleOrNull() ?: return
        val lng = getSystemProperty("gsm.locsp.lng", "").toDoubleOrNull() ?: return
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return
        config.put("lat", lat)
        config.put("lng", lng)
        config.put(
            "sim_bearing",
            getSystemProperty("gsm.locsp.bearing", "0").toDoubleOrNull() ?: 0.0
        )
        config.put(
            "sim_speed",
            (getSystemProperty("gsm.locsp.speed", "0").toDoubleOrNull() ?: 0.0)
                .coerceAtLeast(0.0)
        )
        config.put("heartbeat_at", propertyHeartbeat)
        config.put(
            "heartbeat_uptime_ms",
            getSystemProperty("gsm.locsp.heartbeat_uptime", "0").toLongOrNull() ?: 0L
        )
        if (propertyBootId.isNotEmpty()) config.put("boot_id", propertyBootId)
    }

    private fun emptyWifiPayload(): org.json.JSONObject = org.json.JSONObject().apply {
        put("isConnected", false)
        put("connectedWifi", org.json.JSONObject.NULL)
        put("nearbyWifi", org.json.JSONArray())
    }

    private fun safeWifiPayload(raw: String?): org.json.JSONObject = runCatching {
        org.json.JSONObject(raw ?: "")
    }.getOrElse { emptyWifiPayload() }

    private fun safeArrayPayload(raw: String?): org.json.JSONArray = runCatching {
        org.json.JSONArray(raw ?: "")
    }.getOrElse { org.json.JSONArray() }

    private fun markExpiredConfigInactive(config: JSONObject) {
        config.put("active", false)
        config.put("fail_closed", false)
        config.put(
            "wifi_json",
            org.json.JSONObject().apply {
                put("isConnected", false)
                put("connectedWifi", org.json.JSONObject.NULL)
                put("nearbyWifi", org.json.JSONArray())
            }
        )
        config.put("cell_json", org.json.JSONArray())
        config.put("bluetooth_json", org.json.JSONArray())
        // A dead writer is not an active simulation. Restore normal platform data
        // instead of keeping location/radio hooks engaged indefinitely.
        config.put("mock_wifi", false)
        config.put("mock_cell", false)
        config.put("mock_bluetooth", false)
        config.put("satellite_count", 0)
    }

    private fun readBootId(): String {
        if (cachedBootIdInitialized) return cachedBootId.orEmpty()
        synchronized(bootIdLock) {
            if (cachedBootIdInitialized) return cachedBootId.orEmpty()
            val value = try {
                File("/proc/sys/kernel/random/boot_id").readText().trim()
            } catch (_: Throwable) {
                ""
            }
            cachedBootId = value
            // Cache an empty result too. A domain denied by SELinux must not probe
            // /proc again on every 1 Hz runtime frame.
            cachedBootIdInitialized = true
            return value
        }
    }

    private fun shallowCopyConfig(config: JSONObject): JSONObject? {
        return try {
            val names = ArrayList<String>()
            val keys = config.keys()
            while (keys.hasNext()) names.add(keys.next())
            if (names.isEmpty()) JSONObject() else JSONObject(config, names.toTypedArray())
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "config:shallow-copy:${e.javaClass.name}",
                "readConfig could not copy cached config: ${e.javaClass.simpleName}: ${e.message}",
                30_000L
            )
            null
        }
    }

    private fun loadConfigFromDisk(source: String): JSONObject? {
        val candidates = ArrayList<ConfigCandidate>()
        val runtimeFrame = parseRuntimeFrame(getSystemProperty("gsm.locsp.frame", ""))
        val stopGeneration = readStopGeneration()
        val lifecycleState = getSystemProperty(
            "gsm.locsp.active",
            "__locationspoofer_property_missing__"
        ).lowercase()
        val hasNewerActiveFrame = runtimeFrame?.active == true &&
            !isStoppedGeneration(runtimeFrame.snapshotGeneration, stopGeneration)
        val publishedSnapshotGeneration = if (runtimeFrame?.active != true) {
            getSystemProperty("gsm.locsp.snapshot_generation", "0")
                .toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } else {
            0L
        }
        val inactiveFrameSuperseded = runtimeFrame?.active == false &&
            publishedSnapshotGeneration > runtimeFrame.snapshotGeneration &&
            publishedSnapshotGeneration > stopGeneration
        // Before the stop-fence protocol existed, STOP could leave active=false and a
        // generation-zero/missing frame beside a newer active snapshot. Do not let that
        // legacy property hide the snapshot before disk/provider freshness is compared.
        // Current STOP writers publish snapshot_generation == stop_generation, so they
        // never satisfy this strictly-newer test.
        val legacyLifecycleSuperseded = runtimeFrame == null &&
            lifecycleState == "false" &&
            publishedSnapshotGeneration > 0L &&
            publishedSnapshotGeneration > stopGeneration
        val hasNewerActiveEvidence = hasNewerActiveFrame ||
            inactiveFrameSuperseded ||
            legacyLifecycleSuperseded

        // The lifecycle property is the cheap stop tombstone. It wins without touching
        // files/provider unless a runtime frame proves that a strictly newer run is active.
        // This keeps idle scoped processes passive without letting a delayed old stop kill
        // a newer generation.
        if (runtimeFrame?.active == false &&
            stopGeneration > 0L &&
            !inactiveFrameSuperseded
        ) {
            val inactiveBase = lastConfig?.let(::shallowCopyConfig) ?: JSONObject()
            inactiveBase.put("active", false)
            inactiveBase.put("config_generation", runtimeFrame.snapshotGeneration)
            normalizedCandidate(
                "runtime-frame-stop",
                inactiveBase,
                500,
                runtimeFrame,
                stopGeneration
            )?.let {
                return publishConfigCandidate(source, it, stopGeneration, runtimeFrame)
            }
        }
        if (lifecycleState == "false" && !hasNewerActiveEvidence) {
            loadConfigFromProperties(stopGeneration)?.let { propertyConfig ->
                normalizedCandidate(
                    "system-properties-stop",
                    propertyConfig,
                    400,
                    runtimeFrame,
                    stopGeneration
                )?.let {
                    return publishConfigCandidate(source, it, stopGeneration, runtimeFrame)
                }
            }
        }

        if ((lifecycleState == "true" || hasNewerActiveEvidence) &&
            runtimeFrame?.active == true
        ) {
            val cached = lastConfig
            if (cached?.optBoolean("active", false) == true &&
                cached.optLong("config_generation", 0L) == runtimeFrame.snapshotGeneration
            ) {
                val cachedCopy = shallowCopyConfig(cached)
                if (cachedCopy != null) {
                    normalizedCandidate(
                        "runtime-frame",
                        cachedCopy,
                        450,
                        runtimeFrame,
                        stopGeneration
                    )?.let {
                        return publishConfigCandidate(source, it, stopGeneration, runtimeFrame)
                    }
                }
            }
        }

        val errors = ArrayList<String>()
        if (!configDiskReadDisabled) {
            for ((index, path) in configReadPaths().withIndex()) {
                try {
                    val file = File(path)
                    if (!file.exists()) {
                        errors.add("$path missing")
                        continue
                    }
                    val rawConfig = JSONObject(file.readText())
                    if (!rawConfig.has("active")) {
                        errors.add("$path missing active state")
                        continue
                    }
                    val modifiedAt = file.lastModified()
                    rawConfig.put("_file_modified_at", modifiedAt)
                    normalizedCandidate(
                        "disk:$path",
                        rawConfig,
                        200 - index,
                        runtimeFrame,
                        stopGeneration
                    )?.let(candidates::add)
                    // Each SELinux domain has one known-readable path. Never probe the other
                    // domain after a valid primary snapshot; doing so caused thousands of AVCs.
                    if (candidates.isNotEmpty()) break
                } catch (e: Throwable) {
                    val detail = "${e.javaClass.simpleName}: ${e.message}"
                    errors.add("$path $detail")
                    if (e is SecurityException ||
                        detail.contains("EACCES", ignoreCase = true) ||
                        detail.contains("Permission denied", ignoreCase = true)
                    ) {
                        // One denied open is enough evidence for this process/domain.
                        // Future generations use the provider cache instead of producing AVCs.
                        configDiskReadDisabled = true
                        break
                    }
                }
            }
        } else {
            errors.add("disk source disabled for this SELinux domain")
        }

        selectFreshestConfig(candidates)?.let { diskCandidate ->
            val shadowedByNewerActiveFrame = hasNewerActiveFrame &&
                diskCandidate.inactive &&
                runtimeFrame != null &&
                diskCandidate.generation < runtimeFrame.snapshotGeneration
            if (!shadowedByNewerActiveFrame) {
                return publishConfigCandidate(
                    source,
                    diskCandidate,
                    stopGeneration,
                    runtimeFrame
                )
            }
        }

        // When no disk source is readable, a live provider snapshot is authoritative. In
        // particular, provider active=false must not be mistaken for a missing source and then
        // replaced by an older active preference/cache value.
        if (lifecycleState == "true" || hasNewerActiveEvidence) {
            loadConfigFromContentProvider()?.let { providerConfig ->
                normalizedCandidate(
                    "content-provider",
                    providerConfig,
                    300,
                    runtimeFrame,
                    stopGeneration
                )?.let {
                    return publishConfigCandidate(source, it, stopGeneration, runtimeFrame)
                }
            }
        }

        // Disk/provider absence is exceptional. Do not touch XSharedPreferences here:
        // cross-app data probing can itself create SELinux AVCs. Properties are the
        // only reduced fallback and never carry rich RF payloads.
        loadConfigFromProperties(stopGeneration)?.let { propertyConfig ->
            normalizedCandidate(
                "system-properties",
                propertyConfig,
                90,
                runtimeFrame,
                stopGeneration
            )?.let(candidates::add)
        }

        selectFreshestConfig(candidates)?.let {
            return publishConfigCandidate(source, it, stopGeneration, runtimeFrame)
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val isPermissionDenied = errors.any { it.contains("EACCES") || it.contains("Permission denied") }
        val logIntervalMs = if (isPermissionDenied) 60_000L else 10_000L
        configPollIntervalMs = 60_000L
        if (now - lastOpenCellConfigReadFailureLogTime > logIntervalMs) {
            lastOpenCellConfigReadFailureLogTime = now
            XposedBridge.logOpenCellId("readConfig[$source] no readable config (${errors.joinToString(" | ")})")
        }

        return publishCachedConfigIfNoSource(source, stopGeneration, runtimeFrame)
    }

    private fun publishCachedConfigIfNoSource(
        source: String,
        stopGeneration: Long = readStopGeneration(),
        runtimeFrame: RuntimeFrame? = null
    ): JSONObject? {
        val cached = lastConfig ?: return null
        // normalizeConfig mutates only top-level fields. Preserve the immutable nested
        // Wi-Fi/cell payload references and avoid serializing/parsing them every frame.
        val cachedCopy = shallowCopyConfig(cached) ?: return null
        val candidate = normalizedCandidate(
            "memory-cache",
            cachedCopy,
            0,
            runtimeFrame,
            stopGeneration
        ) ?: return null
        return publishConfigCandidate(source, candidate, stopGeneration, runtimeFrame)
    }

    private fun getSystemProperty(key: String, default: String): String {
        return try {
            val method = cachedSystemPropertyGetMethod ?: synchronized(systemPropertyMethodLock) {
                cachedSystemPropertyGetMethod ?: Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .also { cachedSystemPropertyGetMethod = it }
            }
            method.invoke(null, key, default) as String
        } catch (e: Throwable) {
            default
        }
    }

    private fun requestConfigRefresh() {
        if (configPollWake.availablePermits() == 0) {
            configPollWake.release()
        }
    }

    private fun requestConfigRefreshIfRuntimeStateChanged() {
        val runtimeFrame = getSystemProperty("gsm.locsp.frame", "")
        val stopGeneration = getSystemProperty("gsm.locsp.stop_generation", "0")
        if (runtimeFrame == lastObservedRuntimeFrame &&
            stopGeneration == lastObservedStopGeneration
        ) return
        lastObservedRuntimeFrame = runtimeFrame
        lastObservedStopGeneration = stopGeneration
        requestConfigRefresh()
    }

    /**
     * Lifecycle commits notify this exported, read-only provider URI. ContentService keeps
     * the observer dormant in system_server while idle and wakes the existing background
     * config poller only for an actual start/stop/full-snapshot commit. This closes the
     * cross-process notification gap without shortening the 31/60-second safety fallback.
     */
    private fun installConfigContentObserver() {
        if (configContentObserverInstalled) return
        val context = currentProcessContext() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastConfigContentObserverAttemptAt.get()
            if (previous != 0L && now - previous < configContentObserverRetryIntervalMs) return
            if (lastConfigContentObserverAttemptAt.compareAndSet(previous, now)) break
        }
        synchronized(configContentObserverLock) {
            if (configContentObserverInstalled) return
            try {
                val observer = object : android.database.ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        requestConfigRefreshIfRuntimeStateChanged()
                    }

                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        requestConfigRefreshIfRuntimeStateChanged()
                    }
                }
                context.contentResolver.registerContentObserver(
                    configContentUri,
                    true,
                    observer
                )
                configContentObserver = observer
                configContentObserverInstalled = true
                // Cover a commit racing between the initial load and observer registration.
                requestConfigRefresh()
            } catch (e: Throwable) {
                XposedBridge.logEvery(
                    "configContentObserverUnavailable:${e.javaClass.name}",
                    "[LocationSpoofer] config content observer unavailable; using fallback poll"
                )
            }
        }
    }

    private fun installConfigChangeCallback() {
        if (configChangeCallbackInstalled) return
        try {
            lastObservedRuntimeFrame = getSystemProperty("gsm.locsp.frame", "")
            lastObservedStopGeneration = getSystemProperty("gsm.locsp.stop_generation", "0")
            val callback = Runnable {
                // SystemProperties callbacks are global, not key-specific. Keep the
                // hot callback O(1): two memory-backed reads and equality comparisons.
                requestConfigRefreshIfRuntimeStateChanged()
            }
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("addChangeCallback", Runnable::class.java)
            method.isAccessible = true
            method.invoke(null, callback)
            configChangeCallback = callback
            configChangeCallbackInstalled = true
            // Close the tiny read-before-register race: if the frame changed between
            // initial load and callback registration, this one coalesced refresh sees it.
            requestConfigRefresh()
        } catch (e: Throwable) {
            // Hidden-API availability varies by Android release. The long idle fallback poll
            // remains in place; this transition log is emitted once per process only.
            XposedBridge.logEvery(
                "configChangeCallbackUnavailable:${e.javaClass.name}",
                "[LocationSpoofer] property change callback unavailable; using idle fallback poll"
            )
        }
    }

    private fun findXSharedPreferencesClass(): Class<*>? {
        // 1. Try default Class.forName
        try {
            return Class.forName("de.robv.android.xposed.XSharedPreferences")
        } catch (_: Throwable) {}

        // 2. Try current loader parent
        try {
            val loader = LocationHooker::class.java.classLoader
            loader?.parent?.let { parent ->
                return Class.forName("de.robv.android.xposed.XSharedPreferences", true, parent)
            }
        } catch (_: Throwable) {}

        // 3. Try SystemClassLoader
        try {
            return Class.forName("de.robv.android.xposed.XSharedPreferences", true, ClassLoader.getSystemClassLoader())
        } catch (_: Throwable) {}

        // 4. Try SystemClassLoader parent
        try {
            val loader = ClassLoader.getSystemClassLoader()
            loader?.parent?.let { parent ->
                return Class.forName("de.robv.android.xposed.XSharedPreferences", true, parent)
            }
        } catch (_: Throwable) {}

        // 5. Try Thread context loader
        try {
            val loader = Thread.currentThread().contextClassLoader
            if (loader != null) {
                return Class.forName("de.robv.android.xposed.XSharedPreferences", true, loader)
            }
        } catch (_: Throwable) {}

        // 6. Search entire classloader hierarchy
        var loader = LocationHooker::class.java.classLoader
        while (loader != null) {
            try {
                return Class.forName("de.robv.android.xposed.XSharedPreferences", true, loader)
            } catch (_: Throwable) {}
            loader = loader.parent
        }

        return null
    }

    private fun initXSharedPreferences() {
        if (xsharedPreferencesInitializationAttempted) return
        xsharedPreferencesInitializationAttempted = true
        try {
            val clazz = findXSharedPreferencesClass()
            if (clazz == null) {
                logConfigDebugEvery(
                    "xshared-missing:$currentPackageName",
                    "XSharedPreferences class not found anywhere in $currentPackageName"
                )
                return
            }
            xsharedPrefs = clazz.getConstructor(String::class.java, String::class.java)
                .newInstance("com.shiraka.locatiobprovid", "locationspoofer_prefs")
            XposedHelpers.callMethod(xsharedPrefs!!, "makeWorldReadable")
            logConfigDebugEvery(
                "xshared-ready:$currentPackageName",
                "XSharedPreferences initialized successfully in $currentPackageName"
            )
        } catch (e: Throwable) {
            logConfigDebugEvery(
                "xshared-failed:$currentPackageName:${e.javaClass.simpleName}",
                "XSharedPreferences init failed in $currentPackageName: " + e.message
            )
        }
    }

    private fun loadConfigFromXSharedPreferences(): JSONObject? {
        val prefs = xsharedPrefs ?: return null
        return try {
            XposedHelpers.callMethod(prefs, "reload")
            val hasExplicitActive = runCatching {
                XposedHelpers.callMethod(prefs, "contains", "active") as Boolean
            }.getOrDefault(false)
            if (!hasExplicitActive) {
                return null
            }
            val active = XposedHelpers.callMethod(prefs, "getBoolean", "active", false) as Boolean
            logConfigDebugEvery(
                "xshared-load:$currentPackageName:$active",
                "loadConfigFromXSharedPreferences in $currentPackageName: active=$active"
            )
            val heartbeat = XposedHelpers.callMethod(prefs, "getLong", "heartbeat", 0L) as Long
            val bootId = XposedHelpers.callMethod(prefs, "getString", "boot_id", "") as String
            if (!active) {
                return JSONObject().apply {
                    put("active", false)
                    put("fail_closed", false)
                    put("heartbeat_at", 0L)
                    // ConfigManager records the lifecycle write time in this preference even
                    // for a stop, allowing it to supersede an older active disk snapshot.
                    put("config_updated_at", heartbeat)
                    put("boot_id", bootId)
                    put("wifi_json", emptyWifiPayload())
                    put("cell_json", org.json.JSONArray())
                    put("bluetooth_json", org.json.JSONArray())
                }
            }
            val lat = XposedHelpers.callMethod(prefs, "getFloat", "lat", 0f) as Float
            val lng = XposedHelpers.callMethod(prefs, "getFloat", "lng", 0f) as Float
            val alt = XposedHelpers.callMethod(prefs, "getFloat", "alt", 0f) as Float
            val bearing = XposedHelpers.callMethod(prefs, "getFloat", "bearing", 0f) as Float
            val speed = runCatching {
                XposedHelpers.callMethod(prefs, "getFloat", "speed", 0f) as Float
            }.getOrDefault(0f)
            val satCount = XposedHelpers.callMethod(prefs, "getInt", "sat_count", 20) as Int
            val enableJitter = XposedHelpers.callMethod(prefs, "getBoolean", "enable_jitter", true) as Boolean
            val jitterRadius = XposedHelpers.callMethod(prefs, "getInt", "jitter_radius_meters", DEFAULT_JITTER_RADIUS_METERS.toInt()) as Int
            val jitterSpeed = XposedHelpers.callMethod(prefs, "getString", "jitter_speed", "MEDIUM") as String
            val signalJitterEnabled = runCatching {
                XposedHelpers.callMethod(prefs, "getBoolean", "signal_jitter_enabled", true) as Boolean
            }.getOrDefault(true)
            val signalJitterLevel = runCatching {
                XposedHelpers.callMethod(prefs, "getInt", "signal_jitter_level", 40) as Int
            }.getOrDefault(40)
            val wifiConnectionMode = runCatching {
                XposedHelpers.callMethod(prefs, "getString", "wifi_connection_mode", "FIXED") as String
            }.getOrDefault("FIXED")
            val mockWifi = runCatching {
                XposedHelpers.callMethod(prefs, "getBoolean", "mock_wifi", false) as Boolean
            }.getOrDefault(false)
            val mockCell = runCatching {
                XposedHelpers.callMethod(prefs, "getBoolean", "mock_cell", false) as Boolean
            }.getOrDefault(false)
            JSONObject().apply {
                put("active", true)
                put("lat", lat.toDouble())
                put("lng", lng.toDouble())
                put("altitude", alt.toDouble())
                put("sim_bearing", bearing.toDouble())
                put("sim_speed", speed.toDouble())
                put("satellite_count", satCount)
                put("heartbeat_at", heartbeat)
                put("config_updated_at", heartbeat)
                put("boot_id", bootId)
                put("mock_cell", mockCell)
                put("mock_wifi", mockWifi)
                put("mock_bluetooth", false)
                put("enable_jitter", enableJitter)
                put("jitter_radius_meters", jitterRadius.coerceIn(1, 80))
                put("jitter_speed", jitterSpeed)
                put("signal_jitter_enabled", signalJitterEnabled)
                put("signal_jitter_level", signalJitterLevel.coerceIn(0, 100))
                put("wifi_connection_mode", wifiConnectionMode)
                put("cell_json", org.json.JSONArray())
            }
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "config:xshared:$currentPackageName:${e.javaClass.name}",
                "loadConfigFromXSharedPreferences failed in $currentPackageName: ${e.javaClass.simpleName}: ${e.message}",
                60_000L
            )
            null
        }
    }

    private fun loadConfigFromContentProvider(): JSONObject? {
        return try {
            val context = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context ?: return null
            
            val uri = Uri.parse("content://com.suseoaa.locationspoofer.provider")
            val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
            cursor.use { c ->
                if (c.moveToFirst()) {
                    val activeInt = c.getInt(c.getColumnIndexOrThrow("active"))
                    val heartbeatAt = runCatching {
                        c.getLong(c.getColumnIndexOrThrow("heartbeat_at"))
                    }.getOrDefault(0L)
                    val heartbeatUptimeMs = runCatching {
                        c.getLong(c.getColumnIndexOrThrow("heartbeat_uptime_ms"))
                    }.getOrDefault(0L)
                    val configUpdatedAt = runCatching {
                        c.getLong(c.getColumnIndexOrThrow("config_updated_at"))
                    }.getOrDefault(heartbeatAt)
                    val configGeneration = runCatching {
                        c.getLong(c.getColumnIndexOrThrow("config_generation"))
                    }.getOrDefault(0L)
                    val lifecycleToken = runCatching {
                        c.getLong(c.getColumnIndexOrThrow("runtime_lifecycle_token"))
                    }.getOrDefault(0L)
                    val bootId = runCatching {
                        c.getString(c.getColumnIndexOrThrow("boot_id"))
                    }.getOrDefault("")
                    if (activeInt != 1) {
                        return JSONObject().apply {
                            put("active", false)
                            put("fail_closed", false)
                            put("heartbeat_at", 0L)
                            put("heartbeat_uptime_ms", 0L)
                            put("config_updated_at", configUpdatedAt)
                            put("config_generation", configGeneration)
                            put("runtime_lifecycle_token", lifecycleToken)
                            put("boot_id", bootId)
                            put("wifi_json", emptyWifiPayload())
                            put("cell_json", org.json.JSONArray())
                            put("bluetooth_json", org.json.JSONArray())
                        }
                    }
                    
                    val lat = c.getDouble(c.getColumnIndexOrThrow("lat"))
                    val lng = c.getDouble(c.getColumnIndexOrThrow("lng"))
                    val wifiJson = c.getString(c.getColumnIndexOrThrow("wifi_json"))
                    val cellJson = c.getString(c.getColumnIndexOrThrow("cell_json"))
                    val simBearing = c.getFloat(c.getColumnIndexOrThrow("sim_bearing"))
                    val simSpeed = runCatching {
                        c.getFloat(c.getColumnIndexOrThrow("sim_speed"))
                    }.getOrDefault(0f)
                    val altitude = c.getDouble(c.getColumnIndexOrThrow("altitude"))
                    val satelliteCount = c.getInt(c.getColumnIndexOrThrow("satellite_count"))
                    val enableJitter = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("enable_jitter")) == 1
                    }.getOrDefault(true)
                    val jitterRadius = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("jitter_radius_meters"))
                    }.getOrDefault(DEFAULT_JITTER_RADIUS_METERS.toInt())
                    val jitterSpeed = runCatching {
                        c.getString(c.getColumnIndexOrThrow("jitter_speed"))
                    }.getOrDefault("MEDIUM")
                    val signalJitterEnabled = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("signal_jitter_enabled")) == 1
                    }.getOrDefault(true)
                    val signalJitterLevel = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("signal_jitter_level"))
                    }.getOrDefault(40)
                    val wifiConnectionMode = runCatching {
                        c.getString(c.getColumnIndexOrThrow("wifi_connection_mode"))
                    }.getOrDefault("FIXED")
                    val mockWifi = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("mock_wifi")) == 1
                    }.getOrDefault(false)
                    val mockCell = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("mock_cell")) == 1
                    }.getOrDefault(false)
                    val config = JSONObject().apply {
                        put("active", true)
                        put("lat", lat)
                        put("lng", lng)
                        put("altitude", altitude)
                        put("sim_bearing", simBearing.toDouble())
                        put("sim_speed", simSpeed.toDouble())
                        put("satellite_count", satelliteCount)
                        // These values are emitted by the host's sole runtime writer. The reader
                        // must never renew liveness merely because a ContentProvider query ran.
                        put("heartbeat_at", heartbeatAt)
                        put("heartbeat_uptime_ms", heartbeatUptimeMs)
                        put("config_updated_at", configUpdatedAt)
                        put("config_generation", configGeneration)
                        put("runtime_lifecycle_token", lifecycleToken)
                        put("boot_id", bootId)
                        put("mock_cell", mockCell)
                        put("mock_wifi", mockWifi)
                        put("mock_bluetooth", false)
                        put("enable_jitter", enableJitter)
                        put("jitter_radius_meters", jitterRadius.coerceIn(1, 80))
                        put("jitter_speed", jitterSpeed)
                        put("signal_jitter_enabled", signalJitterEnabled)
                        put("signal_jitter_level", signalJitterLevel.coerceIn(0, 100))
                        put("wifi_connection_mode", wifiConnectionMode)
                        put("cell_json", safeArrayPayload(cellJson))
                        put("wifi_json", safeWifiPayload(wifiJson))
                        put("bluetooth_json", org.json.JSONArray())
                    }
                    val wifiCount = config.optJSONObject("wifi_json")
                        ?.optJSONArray("nearbyWifi")
                        ?.length() ?: 0
                    val cellCount = config.optJSONArray("cell_json")?.length() ?: 0
                    logConfigDebugEvery(
                        "provider:$currentPackageName:$wifiCount:$cellCount",
                        "loadConfigFromContentProvider in $currentPackageName: active=true wifi=$wifiCount cell=$cellCount"
                    )
                    config
                } else {
                    null
                }
            }
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "config:provider:$currentPackageName:${e.javaClass.name}",
                "loadConfigFromContentProvider failed in $currentPackageName: ${e.javaClass.simpleName}: ${e.message}",
                60_000L
            )
            null
        }
    }

    private fun loadConfigFromProperties(
        stopGeneration: Long = readStopGeneration()
    ): JSONObject? {
        val missing = "__locationspoofer_property_missing__"
        val activeStr = getSystemProperty("gsm.locsp.active", missing).lowercase()
        logConfigDebugEvery(
            "properties:$currentPackageName:$activeStr",
            "loadConfigFromProperties in $currentPackageName: activeStr=$activeStr"
        )
        if (activeStr != "true" && activeStr != "false") {
            return null
        }
        return try {
            val heartbeatStr = getSystemProperty("gsm.locsp.heartbeat", "0")
            val heartbeat = heartbeatStr.toLongOrNull() ?: 0L
            val heartbeatUptime = getSystemProperty("gsm.locsp.heartbeat_uptime", "0")
                .toLongOrNull() ?: 0L
            val bootId = getSystemProperty("gsm.locsp.boot_id", "")
            val snapshotGeneration = getSystemProperty("gsm.locsp.snapshot_generation", "0")
                .toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            if (activeStr == "false") {
                return JSONObject().apply {
                    put("active", false)
                    put(
                        "config_generation",
                        stopGeneration.takeIf { it > 0L } ?: snapshotGeneration
                    )
                    put("fail_closed", false)
                    put("heartbeat_at", 0L)
                    put("config_updated_at", heartbeat)
                    put("boot_id", bootId)
                    put("wifi_json", emptyWifiPayload())
                    put("cell_json", org.json.JSONArray())
                    put("bluetooth_json", org.json.JSONArray())
                }
            }

            val latStr = getSystemProperty("gsm.locsp.lat", "0.0")
            val lngStr = getSystemProperty("gsm.locsp.lng", "0.0")
            val altStr = getSystemProperty("gsm.locsp.alt", "0.0")
            val bearingStr = getSystemProperty("gsm.locsp.bearing", "0.0")
            val speedStr = getSystemProperty("gsm.locsp.speed", "0.0")
            val satCountStr = getSystemProperty("gsm.locsp.sat_count", "10")
            val mockWifiStr = getSystemProperty("gsm.locsp.mock_wifi", "false")
            val mockCellStr = getSystemProperty("gsm.locsp.mock_cell", "false")
            val enableJitterStr = getSystemProperty("gsm.locsp.enable_jitter", "true")
            val jitterRadiusStr = getSystemProperty("gsm.locsp.jitter_radius", DEFAULT_JITTER_RADIUS_METERS.toInt().toString())
            val jitterSpeed = getSystemProperty("gsm.locsp.jitter_speed", "MEDIUM")
            val signalJitterStr = getSystemProperty("gsm.locsp.signal_jitter", "true")
            val signalJitterLevelStr = getSystemProperty("gsm.locsp.signal_jitter_level", "40")
            val wifiMode = getSystemProperty("gsm.locsp.wifi_mode", "FIXED")

            JSONObject().apply {
                put("active", true)
                put("config_generation", snapshotGeneration)
                put("lat", latStr.toDoubleOrNull() ?: 0.0)
                put("lng", lngStr.toDoubleOrNull() ?: 0.0)
                put("altitude", altStr.toDoubleOrNull() ?: 0.0)
                put("sim_bearing", bearingStr.toDoubleOrNull() ?: 0.0)
                put("sim_speed", speedStr.toDoubleOrNull() ?: 0.0)
                put("satellite_count", satCountStr.toIntOrNull() ?: 10)
                put("heartbeat_at", heartbeat)
                put("heartbeat_uptime_ms", heartbeatUptime)
                put("config_updated_at", heartbeat)
                put("boot_id", bootId)
                put("mock_cell", mockCellStr == "true")
                put("mock_wifi", mockWifiStr == "true")
                put("mock_bluetooth", false)
                put("enable_jitter", enableJitterStr == "true")
                put("jitter_radius_meters", (jitterRadiusStr.toIntOrNull() ?: DEFAULT_JITTER_RADIUS_METERS.toInt()).coerceIn(1, 80))
                put("jitter_speed", jitterSpeed)
                put("signal_jitter_enabled", signalJitterStr == "true")
                put("signal_jitter_level", (signalJitterLevelStr.toIntOrNull() ?: 40).coerceIn(0, 100))
                put("wifi_connection_mode", wifiMode)
                put("cell_json", org.json.JSONArray())
            }
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "config:properties:$currentPackageName:${e.javaClass.name}",
                "loadConfigFromProperties failed in $currentPackageName: ${e.javaClass.simpleName}: ${e.message}",
                60_000L
            )
            null
        }
    }

    /**
     * 从本地文件读取模拟配置(纯文件方案,无ContentProvider跨进程调用)
     *
     * 架构优化:
     *    由于此方法会被各种 Hook 在主线程极其高频地调用（例如每秒数百次），
     *    任何在主线程进行的文件 IO（哪怕是偶尔一次）都会导致严重的丢帧卡顿（Stutter）。
     *    因此重构为：在首次调用时启动一个后台守护线程（Daemon Thread），
     *    active 时在后台更新 Volatile 的 lastConfig；inactive 时由 system property
     *    change callback 唤醒，并仅保留一分钟一次的兼容性 fallback。
     *    主线程的 readConfig() 永远只返回内存中的 lastConfig，实现真正的 0 IO 延迟。
     */
    private fun readConfig(): JSONObject? {
        // Claim before the synchronous initial read. Hooks reached re-entrantly from that read
        // fail open against the current cache instead of starting a second permanent poller.
        if (configPollingClaimed.compareAndSet(false, true)) {
            try {
                loadConfigFromDisk("initial")
            } catch (e: Throwable) {
                XposedBridge.logOpenCellIdEvery(
                    "config:initial-load:${e.javaClass.name}",
                    "readConfig initial load failed: ${e.javaClass.simpleName}: ${e.message}",
                    30_000L
                )
            }
            installConfigChangeCallback()
            installConfigContentObserver()

            // Start one process-local watcher. It performs no file/provider I/O while
            // idle unless a lifecycle transition wakes it or the long fallback expires.
            Thread {
                while (true) {
                    try {
                        configPollWake.tryAcquire(
                            configPollIntervalMs,
                            java.util.concurrent.TimeUnit.MILLISECONDS
                        )
                        // Retry observer registration only on this existing low-frequency
                        // watcher. A transient early-boot/context failure therefore recovers
                        // without adding a timer or work to hot location/radio hooks.
                        installConfigContentObserver()
                        loadConfigFromDisk("poll")
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        XposedBridge.logOpenCellId("LocationSpoofer config poller interrupted")
                        break
                    } catch (e: Throwable) {
                        // A malformed/transient source must not permanently freeze the
                        // volatile cache. Log this iteration and retry on the next tick.
                        XposedBridge.logOpenCellIdEvery(
                            "config:poll-load:${e.javaClass.name}",
                            "readConfig poll failed: ${e.javaClass.simpleName}: ${e.message}",
                            30_000L
                        )
                    }
                }
            }.apply {
                isDaemon = true
                name = "LocationSpoofer_ConfigPoller"
                start()
            }
        }
        return lastConfig
    }

    private var cachedGpsSatellitesList: Iterable<Any>? = null
    private var lastGpsSatellitesUpdate = 0L

    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    private fun createSpoofedGpsSatellites(classLoader: ClassLoader): Iterable<Any> {
        val now = System.currentTimeMillis()
        if (cachedGpsSatellitesList == null || now - lastGpsSatellitesUpdate > 1000) {
            val list = ArrayList<Any>()
            try {
                updateSatelliteCacheIfNeeded()
                if (!isSpoofingActiveCache || cachedSatellites == null) return list

                val satelliteClass = classLoader.loadClass("android.location.GpsSatellite")
                val constructor = satelliteClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                constructor.isAccessible = true

                for (i in 0 until spoofingCountCache) {
                    val data = getCachedSatellite(i) ?: continue
                    val prn = if (data.type == 3) data.svid + 64 else data.svid
                    val sat = constructor.newInstance(prn)
                    try { XposedHelpers.setBooleanField(sat, "mValid", true) } catch (e: Throwable) {}
                    try { XposedHelpers.setBooleanField(sat, "mHasEphemeris", true) } catch (e: Throwable) {}
                    try { XposedHelpers.setBooleanField(sat, "mHasAlmanac", true) } catch (e: Throwable) {}
                    try { XposedHelpers.setBooleanField(sat, "mUsedInFix", data.usedInFix) } catch (e: Throwable) {}
                    try { 
                        val f = satelliteClass.getDeclaredField("mSnr")
                        f.isAccessible = true
                        f.setFloat(sat, data.cn0)
                    } catch (e: Throwable) {}
                    try { 
                        val f = satelliteClass.getDeclaredField("mElevation")
                        f.isAccessible = true
                        f.setFloat(sat, data.elevation)
                    } catch (e: Throwable) {}
                    try { 
                        val f = satelliteClass.getDeclaredField("mAzimuth")
                        f.isAccessible = true
                        f.setFloat(sat, data.azimuth)
                    } catch (e: Throwable) {}
                    list.add(sat)
                }
                cachedGpsSatellitesList = list
                lastGpsSatellitesUpdate = now
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }
    }

        return cachedGpsSatellitesList ?: ArrayList()
    }

    private fun buildSpoofedAndroidLocation(
        config: JSONObject,
        provider: String
    ): android.location.Location {
        return android.location.Location(provider).also {
            applySpoofedLocationToObject(it, config, provider)
        }
    }

    private fun hookSystemLocationProviderPipeline(classLoader: ClassLoader, pkg: String) {
        if (pkg != "android" && pkg != "system") return

        val managerClassName = "com.android.server.location.provider.LocationProviderManager"
        val managerClass = XposedHelpers.findClassIfExists(managerClassName, classLoader) ?: return
        val processMethodCount = managerClass.declaredMethods.count {
            it.name == "processReportedLocation" && it.parameterCount == 1
        }

        try {
            if (processMethodCount > 0) {
                XposedBridge.hookAllMethods(managerClass, "processReportedLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == null) return
                        val config = readConfig() ?: return
                        if (!shouldDeliverLocation(config)) return
                        val manager = param.thisObject ?: return
                        val provider = systemProviderName(manager) ?: return
                        if (!isSyntheticSystemProvider(provider)) return
                        val replacement = buildCanonicalFrameworkLocationResult(classLoader, config, provider) ?: return
                        param.result = replacement
                        XposedBridge.logEvery(
                            "systemCanonicalFix:$provider",
                            "[LocationSpoofer] canonical $provider fix created after provider validation and before framework cache/filter",
                            10_000L
                        )
                    }
                })
            } else {
                // Compatibility fallback for releases without processReportedLocation().
                XposedBridge.hookAllMethods(managerClass, "onReportLocation", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldDeliverLocation(config)) return
                        val manager = param.thisObject ?: return
                        val provider = systemProviderName(manager) ?: return
                        if (!isSyntheticSystemProvider(provider)) return
                        val replacement = buildCanonicalFrameworkLocationResult(classLoader, config, provider) ?: return
                        if (param.args.isNotEmpty()) param.args[0] = replacement
                    }
                })
            }

            XposedBridge.hookAllMethods(managerClass, "startManager", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let(::captureSystemProviderManager)
                }
            })

            val lateCaptureHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let(::captureSystemProviderManager)
                }
            }
            for (methodName in listOf("registerLocationRequest", "getCurrentLocation", "getLastLocation")) {
                XposedBridge.hookAllMethods(managerClass, methodName, lateCaptureHook)
            }

            XposedBridge.log(
                "[LocationSpoofer] system framework fix hook installed at " +
                    if (processMethodCount > 0) "processReportedLocation-after" else "onReportLocation-before"
            )
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] system framework fix hook failed: $e")
        }
    }

    private fun systemProviderName(manager: Any): String? {
        return try {
            XposedHelpers.getObjectField(manager, "mName") as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun isSyntheticSystemProvider(provider: String): Boolean {
        return provider == android.location.LocationManager.GPS_PROVIDER ||
            provider == android.location.LocationManager.NETWORK_PROVIDER ||
            provider == "fused"
    }

    private fun captureSystemProviderManager(manager: Any) {
        val provider = systemProviderName(manager) ?: return
        if (!isSyntheticSystemProvider(provider)) return
        systemProviderManagers[provider] = manager
        XposedBridge.logEvery(
            "systemProviderCaptured:$provider",
            "[LocationSpoofer] captured real $provider provider manager for framework cadence"
        )
        // system_server installs only the provider-pipeline hook group, so it may not
        // encounter any other callback that calls readConfig() before the first client
        // asks for a location. Seed the passive config watcher at the exact point where
        // a usable real provider manager becomes available. Without this, lastConfig
        // remains null and the cadence never starts on devices with no physical fix.
        // Subsequent calls are memory-only; inactive polling still sleeps for 60 seconds
        // and lifecycle commits wake it through the ContentService observer.
        readConfig()
        installConfigContentObserver()
        ensureSystemLocationInjector()
    }

    private fun ensureSystemLocationInjector() {
        synchronized(systemInjectorLock) {
            if (!shouldSpoofLocation(lastConfig)) return
            if (systemInjectorStarted) return
            systemInjectorStarted = true
            lateinit var tick: Runnable
            tick = Runnable {
                try {
                    injectSystemProviderFixes()
                } catch (e: Throwable) {
                    XposedBridge.logEvery(
                        "systemProviderTickFailed",
                        "[LocationSpoofer] system provider cadence failed: $e"
                    )
                } finally {
                    val shouldContinue = shouldDeliverLocation(lastConfig)
                    synchronized(systemInjectorLock) {
                        if (systemInjectorStarted && systemInjectorTick === tick && shouldContinue) {
                            systemLocationHandler.postDelayed(tick, 1_000L)
                        } else if (systemInjectorTick === tick) {
                            systemInjectorStarted = false
                            systemInjectorTick = null
                        }
                        Unit
                    }
                }
            }
            systemInjectorTick = tick
            systemLocationHandler.post(tick)
        }
    }

    private fun wakeSystemLocationInjector() {
        if (shouldSpoofLocation(lastConfig)) {
            ensureSystemLocationInjector()
            return
        }
        synchronized(systemInjectorLock) {
            systemInjectorTick?.let(systemLocationHandler::removeCallbacks)
            systemInjectorTick = null
            systemInjectorStarted = false
        }
    }

    private fun injectSystemProviderFixes() {
        val config = readConfig() ?: return
        if (!shouldDeliverLocation(config)) return

        var delivered = 0
        for ((provider, manager) in systemProviderManagers) {
            val rawLocation = buildCompleteRawLocation(config, provider)
            val managerClassLoader = manager.javaClass.classLoader ?: ClassLoader.getSystemClassLoader()
            val result = createFrameworkLocationResult(managerClassLoader, rawLocation) ?: continue
            if (reportThroughRealProvider(manager, result)) delivered++
        }
        if (delivered > 0) {
            systemInjectedReportCount += delivered.toLong()
            XposedBridge.logEvery(
                "systemProviderCadence",
                "[LocationSpoofer] framework cadence delivered $delivered provider reports (total=$systemInjectedReportCount)",
                10_000L
            )
        }
    }

    private fun buildCompleteRawLocation(config: JSONObject, provider: String): android.location.Location {
        val base = standardLocationCoordinates(config)
        return android.location.Location(provider).also { location ->
            location.latitude = base.first
            location.longitude = base.second
            location.accuracy = 20f
            location.time = System.currentTimeMillis()
            location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
    }

    private fun reportThroughRealProvider(manager: Any, result: Any): Boolean {
        return try {
            val mockable = XposedHelpers.getObjectField(manager, "mProvider") ?: return false
            val realProvider = try {
                XposedHelpers.getObjectField(mockable, "mRealProvider")
            } catch (_: Throwable) {
                null
            }
            val provider = realProvider ?: XposedHelpers.callMethod(mockable, "getProvider") ?: return false
            // Never route through Android's MockLocationProvider. The real provider abstraction
            // owns the listener/lock that enters LocationProviderManager normally.
            if (provider.javaClass.name.endsWith(".MockLocationProvider")) return false
            XposedHelpers.callMethod(provider, "reportLocation", result)
            true
        } catch (e: Throwable) {
            XposedBridge.logEvery(
                "systemProviderReportFailed:${systemProviderName(manager)}",
                "[LocationSpoofer] unable to report through real ${systemProviderName(manager)} provider: $e",
                10_000L
            )
            false
        }
    }

    private fun buildCanonicalFrameworkLocationResult(
        classLoader: ClassLoader,
        config: JSONObject,
        provider: String
    ): Any? {
        return createFrameworkLocationResult(classLoader, buildSpoofedAndroidLocation(config, provider))
    }

    private fun createFrameworkLocationResult(
        classLoader: ClassLoader,
        location: android.location.Location
    ): Any? {
        val resultClass = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
            ?: return null
        val locations = arrayListOf(location)
        val candidateNames = setOf("wrap", "create", "of", "from")
        for (method in resultClass.declaredMethods) {
            if (!java.lang.reflect.Modifier.isStatic(method.modifiers) || method.parameterCount != 1) continue
            if (method.name !in candidateNames) continue
            val parameter = method.parameterTypes[0]
            val argument: Any = when {
                java.util.List::class.java.isAssignableFrom(parameter) ||
                    java.util.Collection::class.java.isAssignableFrom(parameter) -> locations
                parameter.isArray &&
                    parameter.componentType?.isAssignableFrom(android.location.Location::class.java) == true ->
                    arrayOf(location)
                else -> continue
            }
            try {
                method.isAccessible = true
                return method.invoke(null, argument)
            } catch (_: Throwable) {
            }
        }
        for (constructor in resultClass.declaredConstructors) {
            if (constructor.parameterCount != 1) continue
            val parameter = constructor.parameterTypes[0]
            val argument: Any = when {
                java.util.List::class.java.isAssignableFrom(parameter) ||
                    java.util.Collection::class.java.isAssignableFrom(parameter) -> locations
                parameter.isArray &&
                    parameter.componentType?.isAssignableFrom(android.location.Location::class.java) == true ->
                    arrayOf(location)
                else -> continue
            }
            try {
                constructor.isAccessible = true
                return constructor.newInstance(argument)
            } catch (_: Throwable) {
            }
        }
        XposedBridge.logEvery(
            "frameworkLocationResultFactoryMissing",
            "[LocationSpoofer] no compatible android.location.LocationResult factory"
        )
        return null
    }

    private fun applySpoofedLocationToObject(
        loc: Any,
        config: JSONObject,
        provider: String
    ) {
        val sample = createLocationSample(
            config = config,
            provider = provider,
            timestamps = nextSpoofedTimestamps()
        )

        try { XposedHelpers.callMethod(loc, "setLatitude", sample.latitude) } catch (_: Throwable) {
            try { XposedHelpers.setDoubleField(loc, "mLatitude", sample.latitude) } catch (_: Throwable) {}
        }
        try { XposedHelpers.callMethod(loc, "setLongitude", sample.longitude) } catch (_: Throwable) {
            try { XposedHelpers.setDoubleField(loc, "mLongitude", sample.longitude) } catch (_: Throwable) {}
        }
        try { XposedHelpers.callMethod(loc, "setAccuracy", sample.accuracy) } catch (_: Throwable) {
            try { XposedHelpers.setFloatField(loc, "mHorizontalAccuracyMeters", sample.accuracy) } catch (_: Throwable) {}
        }
        try { XposedHelpers.callMethod(loc, "setTime", sample.timestamps.wallTimeMs) } catch (_: Throwable) {
            try { XposedHelpers.setLongField(loc, "mTimeMs", sample.timestamps.wallTimeMs) } catch (_: Throwable) {}
        }
        try { XposedHelpers.callMethod(loc, "setElapsedRealtimeNanos", sample.timestamps.elapsedRealtimeNanos) } catch (_: Throwable) {
            try { XposedHelpers.setLongField(loc, "mElapsedRealtimeNs", sample.timestamps.elapsedRealtimeNanos) } catch (_: Throwable) {}
        }
        try { XposedHelpers.callMethod(loc, "setProvider", sample.provider) } catch (_: Throwable) {}

        if (sample.altitude > 0.0) {
            try { XposedHelpers.callMethod(loc, "setAltitude", sample.altitude) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "setVerticalAccuracyMeters", (sample.accuracy * 0.6f).coerceAtLeast(2f)) } catch (_: Throwable) {}
        } else {
            try { XposedHelpers.callMethod(loc, "removeAltitude") } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "removeVerticalAccuracy") } catch (_: Throwable) {}
        }
        // MSL values belong to the original real fix and must not survive coordinate replacement.
        try { XposedHelpers.callMethod(loc, "removeMslAltitude") } catch (_: Throwable) {}
        try { XposedHelpers.callMethod(loc, "removeMslAltitudeAccuracy") } catch (_: Throwable) {}
        if (sample.hasMotion) {
            try { XposedHelpers.callMethod(loc, "setSpeed", sample.speed) } catch (_: Throwable) {
                try { XposedHelpers.setFloatField(loc, "mSpeed", sample.speed) } catch (_: Throwable) {}
            }
            try { XposedHelpers.callMethod(loc, "setSpeedAccuracyMetersPerSecond", 0.35f) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "setBearing", sample.bearing) } catch (_: Throwable) {
                try { XposedHelpers.setFloatField(loc, "mBearing", sample.bearing) } catch (_: Throwable) {}
            }
            try { XposedHelpers.callMethod(loc, "setBearingAccuracyDegrees", 4.5f) } catch (_: Throwable) {
                try { XposedHelpers.setFloatField(loc, "mBearingAccuracyDegrees", 4.5f) } catch (_: Throwable) {}
            }
        } else {
            try { XposedHelpers.callMethod(loc, "removeSpeed") } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "removeSpeedAccuracy") } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "removeBearing") } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "removeBearingAccuracy") } catch (_: Throwable) {}
        }

        try { XposedHelpers.callMethod(loc, "setMock", false) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(loc, "mMock", false) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false) } catch (_: Throwable) {}
        try {
            val extras = XposedHelpers.callMethod(loc, "getExtras") as? android.os.Bundle
            val bundle = extras ?: android.os.Bundle()
            bundle.remove("mockLocation")
            bundle.remove("isMock")
            bundle.putInt("satellites", config.optInt("satellite_count", 20).coerceIn(0, 64))
            if (extras == null) {
                XposedHelpers.callMethod(loc, "setExtras", bundle)
            }
        } catch (_: Throwable) {}
    }

    private fun createOnNmeaMessageListenerProxy(original: Any, classLoader: ClassLoader): Any {
        nmeaListenerProxies[original]?.let { proxy ->
            startNmeaInjector(original, "onNmeaMessage")
            return proxy
        }
        val interfaceClass = classLoader.loadClass("android.location.OnNmeaMessageListener")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            classLoader,
            arrayOf(interfaceClass),
            object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                    if (method.name == "onNmeaMessage" && args != null && args.size >= 2) {
                        val originalMsg = args[0] as? String
                        val timestamp = (args[1] as? Number)?.toLong()
                        if (originalMsg != null && timestamp != null) {
                            val config = lastConfig
                            val deliveredMsg = if (shouldDeliverLocation(config)) {
                                spoofNmeaMessage(originalMsg, config ?: return null)
                            } else {
                                originalMsg
                            }
                            if (deliveredMsg == null) return null // Allow dropping messages
                            val listener = original as? android.location.OnNmeaMessageListener
                            if (listener != null) {
                                // An active-created proxy can outlive the simulation. In the
                                // inactive state this is now a direct, allocation-free pass-through:
                                // no config read, sentence rewrite, argument copy, or reflection.
                                listener.onNmeaMessage(deliveredMsg, timestamp)
                                return null
                            }
                            return method.invoke(original, deliveredMsg, timestamp)
                        }
                    }
                    val methodArgs = if (args == null) emptyArray<Any>() else Array(args.size) { i -> args[i] }
                    return method.invoke(original, *methodArgs)
                }
            }
        )
        nmeaListenerProxies[original] = proxy
        startNmeaInjector(original, "onNmeaMessage")
        return proxy
    }

    @Suppress("DEPRECATION")
    private fun createGpsStatusNmeaListenerProxy(original: Any, classLoader: ClassLoader): Any {
        nmeaListenerProxies[original]?.let { proxy ->
            startNmeaInjector(original, "onNmeaReceived")
            return proxy
        }
        val interfaceClass = classLoader.loadClass("android.location.GpsStatus\$NmeaListener")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            classLoader,
            arrayOf(interfaceClass),
            object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                    if (method.name == "onNmeaReceived" && args != null && args.size >= 2) {
                        val originalMsg = args[1] as? String
                        val timestamp = (args[0] as? Number)?.toLong()
                        if (originalMsg != null && timestamp != null) {
                            val config = lastConfig
                            val deliveredMsg = if (shouldDeliverLocation(config)) {
                                spoofNmeaMessage(originalMsg, config ?: return null)
                            } else {
                                originalMsg
                            }
                            if (deliveredMsg == null) return null // Allow dropping messages
                            val listener = original as? android.location.GpsStatus.NmeaListener
                            if (listener != null) {
                                listener.onNmeaReceived(timestamp, deliveredMsg)
                                return null
                            }
                            return method.invoke(original, timestamp, deliveredMsg)
                        }
                    }
                    val methodArgs = if (args == null) emptyArray<Any>() else Array(args.size) { i -> args[i] }
                    return method.invoke(original, *methodArgs)
                }
            }
        )
        nmeaListenerProxies[original] = proxy
        startNmeaInjector(original, "onNmeaReceived")
        return proxy
    }

    private fun startNmeaInjector(original: Any, methodName: String) {
        if (!shouldActivelyInjectClientGnss()) return
        if (!shouldDeliverLocation(readConfig())) return
        if (nmeaTimers.containsKey(original)) return
        val timer = java.util.Timer("LocationSpoofer_NMEA_${System.identityHashCode(original)}", true)
        nmeaTimers[original] = timer
        XposedBridge.logEvery(
            "nmeaInjectorStarted:$currentPackageName:${original.javaClass.name}",
            "[GPS_Spoofer] active NMEA injector started for $currentPackageName listener=${original.javaClass.name}"
        )
        timer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                try {
                    val config = readConfig()
                    if (!shouldDeliverLocation(config)) {
                        nmeaTimers.remove(original)?.cancel()
                        return
                    }
                    val activeConfig = config ?: return
                    val now = System.currentTimeMillis()
                    for (sentence in buildSpoofedNmeaBurst(activeConfig)) {
                        if (methodName == "onNmeaReceived") {
                            XposedHelpers.tryCallMethodDeep(original, methodName, now, sentence)
                        } else {
                            XposedHelpers.tryCallMethodDeep(original, methodName, sentence, now)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }, 150L, 1000L)
    }

    private fun spoofNmeaMessage(sentence: String, config: JSONObject): String? {
        try {
            if (!shouldDeliverLocation(config)) return sentence
            
            val targetLat = config.optDouble("wgs84_lat", 0.0)
            val targetLng = config.optDouble("wgs84_lng", 0.0)
            if (targetLat == 0.0 && targetLng == 0.0) return sentence
            
            val parts = sentence.split("*")
            val mainPart = parts[0]
            val fields = mainPart.split(",").toMutableList()
            if (fields.isEmpty()) return sentence
            
            val type = fields[0]
            var modified = false
            
            if (type.endsWith("GSV")) {
                return buildSpoofedGsvSentence()
            }
            if (type.endsWith("GSA")) {
                return buildSpoofedGsaSentence()
            }
            
            if (type.endsWith("RMC") && fields.size >= 7) {
                val (latStr, latDir) = convertToNmeaLatitude(targetLat)
                val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
                if (fields.size > 2) fields[2] = "A"
                fields[3] = latStr
                fields[4] = latDir
                fields[5] = lngStr
                fields[6] = lngDir
                if (fields.size > 8) fields[8] = String.format(java.util.Locale.US, "%.1f", spoofedBearing(config))
                modified = true
            } else if (type.endsWith("GGA") && fields.size >= 6) {
                val (latStr, latDir) = convertToNmeaLatitude(targetLat)
                val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
                fields[2] = latStr
                fields[3] = latDir
                fields[4] = lngStr
                fields[5] = lngDir
                if (fields.size > 6) fields[6] = "1"
                if (fields.size > 7) fields[7] = String.format(java.util.Locale.US, "%02d", config.optInt("satellite_count", 20).coerceIn(0, 64))
                if (fields.size > 8) fields[8] = estimateNmeaHdop()
                modified = true
            } else if (type.endsWith("GLL") && fields.size >= 5) {
                val (latStr, latDir) = convertToNmeaLatitude(targetLat)
                val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
                fields[1] = latStr
                fields[2] = latDir
                fields[3] = lngStr
                fields[4] = lngDir
                if (fields.size > 6) fields[6] = "A"
                modified = true
            } else if (type.endsWith("VTG") && fields.size >= 2) {
                fields[1] = String.format(java.util.Locale.US, "%.1f", spoofedBearing(config))
                if (fields.size > 2) fields[2] = "T"
                modified = true
            }
            
            if (!modified) return sentence
            
            val newMainPart = fields.joinToString(",")
            val newChecksum = calculateNmeaChecksum(newMainPart)
            
            val tail = if (parts.size > 1) {
                val rawTail = parts[1]
                val lineEnding = rawTail.substring(Math.min(2, rawTail.length))
                "*$newChecksum$lineEnding"
            } else {
                "*$newChecksum"
            }
            return newMainPart + tail
        } catch (e: Exception) {
            XposedBridge.log(e)
            return sentence
        }
    }

    private fun buildSpoofedGsaSentence(): String? {
        updateSatelliteCacheIfNeeded()
        val sats = cachedSatellites ?: return null
        if (!isSpoofingActiveCache || sats.isEmpty()) return null
        val used = sats.filter { it.usedInFix }.take(12).map { nmeaPrn(it) }.toMutableList()
        while (used.size < 12) used.add("")
        val hdop = estimateNmeaHdop()
        val hdopValue = hdop.toDoubleOrNull() ?: 0.9
        val pdop = String.format(java.util.Locale.US, "%.1f", (hdopValue * 1.7).coerceIn(0.8, 3.5))
        val vdop = String.format(java.util.Locale.US, "%.1f", (hdopValue * 1.3).coerceIn(0.7, 2.8))
        val fields = mutableListOf("\$GNGSA", "A", "3")
        fields.addAll(used)
        fields.add(pdop)
        fields.add(hdop)
        fields.add(vdop)
        return appendNmeaChecksum(fields.joinToString(","))
    }

    private fun buildSpoofedNmeaBurst(config: JSONObject): List<String> {
        val targetLat = config.optDouble("wgs84_lat", 0.0)
        val targetLng = config.optDouble("wgs84_lng", 0.0)
        if (targetLat == 0.0 && targetLng == 0.0) return emptyList()
        updateSatelliteCacheIfNeeded()
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val time = String.format(
            java.util.Locale.US,
            "%02d%02d%02d.00",
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND)
        )
        val date = String.format(
            java.util.Locale.US,
            "%02d%02d%02d",
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.YEAR) % 100
        )
        val (latStr, latDir) = convertToNmeaLatitude(targetLat)
        val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
        val sats = cachedSatellites
        val satCount = (sats?.size ?: config.optInt("satellite_count", 20)).coerceIn(0, 64)
        val hdop = estimateNmeaHdop()
        val altitude = config.optDouble("altitude", 0.0)
        val speedKnots = spoofedSpeed(config) * 1.943844f
        val bearing = if (hasSpoofedMotion(config)) spoofedBearing(config) else 0f
        val sentences = ArrayList<String>()
        sentences.add(appendNmeaChecksum("\$GNRMC,$time,A,$latStr,$latDir,$lngStr,$lngDir,${String.format(java.util.Locale.US, "%.1f", speedKnots)},${String.format(java.util.Locale.US, "%.1f", bearing)},$date,,,A"))
        sentences.add(appendNmeaChecksum("\$GNGGA,$time,$latStr,$latDir,$lngStr,$lngDir,1,${String.format(java.util.Locale.US, "%02d", satCount)},$hdop,${String.format(java.util.Locale.US, "%.1f", altitude)},M,0.0,M,,"))
        sentences.add(appendNmeaChecksum("\$GNGLL,$latStr,$latDir,$lngStr,$lngDir,$time,A,A"))
        sentences.add(appendNmeaChecksum("\$GNVTG,${String.format(java.util.Locale.US, "%.1f", bearing)},T,,M,${String.format(java.util.Locale.US, "%.1f", speedKnots)},N,${String.format(java.util.Locale.US, "%.1f", spoofedSpeed(config) * 3.6f)},K,A"))
        buildSpoofedGsaSentence()?.let { sentences.add(it) }
        val gsvPages = ((satCount + 3) / 4).coerceAtLeast(1)
        repeat(gsvPages) {
            buildSpoofedGsvSentence()?.let { sentence -> sentences.add(sentence) }
        }
        return sentences
    }

    private fun buildSpoofedGsvSentence(): String? {
        updateSatelliteCacheIfNeeded()
        val sats = cachedSatellites ?: return null
        if (!isSpoofingActiveCache || sats.isEmpty()) return null
        val totalMessages = ((sats.size + 3) / 4).coerceAtLeast(1)
        val page = (nmeaGsvPageCounter % totalMessages) + 1
        nmeaGsvPageCounter = (nmeaGsvPageCounter + 1) % totalMessages
        val start = (page - 1) * 4
        val end = minOf(start + 4, sats.size)
        val fields = mutableListOf(
            "\$GNGSV",
            totalMessages.toString(),
            page.toString(),
            sats.size.toString()
        )
        for (i in start until end) {
            val sat = sats[i]
            fields.add(nmeaPrn(sat))
            fields.add(sat.elevation.toInt().coerceIn(0, 90).toString())
            fields.add(sat.azimuth.toInt().coerceIn(0, 359).toString())
            fields.add(sat.cn0.toInt().coerceIn(0, 99).toString())
        }
        return appendNmeaChecksum(fields.joinToString(","))
    }

    private fun nmeaPrn(sat: SatelliteData): String {
        val prn = when (sat.type) {
            3 -> sat.svid + 64
            5 -> sat.svid + 200
            else -> sat.svid
        }
        return String.format(java.util.Locale.US, "%02d", prn)
    }

    private fun estimateNmeaHdop(): String {
        updateSatelliteCacheIfNeeded()
        val sats = cachedSatellites ?: return "0.9"
        val used = sats.count { it.usedInFix }.coerceAtLeast(1)
        val avgElevation = sats.filter { it.usedInFix }.map { it.elevation }.ifEmpty { sats.map { it.elevation } }.average()
        val hdop = (2.4 - used * 0.11 - avgElevation / 120.0).coerceIn(0.6, 2.2)
        return String.format(java.util.Locale.US, "%.1f", hdop)
    }

    private fun appendNmeaChecksum(mainPart: String): String {
        return mainPart + "*" + calculateNmeaChecksum(mainPart)
    }

    private fun convertToNmeaLatitude(lat: Double): Pair<String, String> {
        val absLat = Math.abs(lat)
        val degrees = absLat.toInt()
        val minutes = (absLat - degrees) * 60.0
        val latStr = String.format(java.util.Locale.US, "%02d%08.5f", degrees, minutes)
        val dir = if (lat >= 0) "N" else "S"
        return Pair(latStr, dir)
    }

    private fun convertToNmeaLongitude(lng: Double): Pair<String, String> {
        val absLng = Math.abs(lng)
        val degrees = absLng.toInt()
        val minutes = (absLng - degrees) * 60.0
        val lngStr = String.format(java.util.Locale.US, "%03d%08.5f", degrees, minutes)
        val dir = if (lng >= 0) "E" else "W"
        return Pair(lngStr, dir)
    }

    private fun calculateNmeaChecksum(sentence: String): String {
        var checksum = 0
        val startIndex = if (sentence.startsWith("$")) 1 else 0
        val endIndex = sentence.indexOf('*')
        val limit = if (endIndex != -1) endIndex else sentence.length
        for (i in startIndex until limit) {
            checksum = checksum xor sentence[i].code
        }
        return String.format(java.util.Locale.US, "%02X", checksum)
    }

}
