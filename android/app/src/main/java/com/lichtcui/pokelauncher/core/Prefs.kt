package com.lichtcui.pokelauncher.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用偏好库（对应鸿蒙版 `common/Prefs.ets` 的 `SETTINGS_STORE`）。
 *
 * 选 SharedPreferences 而不是 docs 里原先写的 DataStore：
 * - 服务端生成 `/__cheats__.js` 时需要**同步**读取当前参数调整状态（DataStore 只有异步 API，
 *   那会逼出一层「内存快照 + 首次加载竞态」的额外状态机）；
 * - 零新依赖，最小 API 面；
 * - 缺点是没有 DataStore 的类型安全/事务与 Flow 订阅——但本项目的偏好项很少，
 *   且真正的状态源头是 `CheatState` / 方向 / 静音这些内存单例（与鸿蒙版一致）。
 *
 * ⚠️ 库名与 key 名要与鸿蒙版语义对齐（两边各自独立存储，不共享文件）。
 */
object Prefs {

    /** 本应用统一使用的偏好库名（参数调整 / 更新 / 方向等共用），与鸿蒙版同名 */
    const val STORE_NAME = "pokelauncher_settings"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var store: SharedPreferences? = null

    /** App 启动时调用一次；允许重复调用（幂等） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 偏好库实例；未 init 时返回 null（调用方自行降级为「不持久化」） */
    fun store(): SharedPreferences? {
        store?.let { return it }
        val ctx = appContext ?: return null
        synchronized(this) {
            store?.let { return it }
            val created = ctx.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            store = created
            return created
        }
    }

    /** 读布尔（缺省 fallback） */
    fun getBoolean(key: String, fallback: Boolean): Boolean =
        store()?.getBoolean(key, fallback) ?: fallback

    /** 读字符串（缺省空串） */
    fun getString(key: String): String = store()?.getString(key, "") ?: ""

    /** 读长整型（时间戳类偏好用；缺省 fallback） */
    fun getLong(key: String, fallback: Long): Long = store()?.getLong(key, fallback) ?: fallback

    /**
     * 写布尔/字符串/长整型。用 `apply()`：单次写很小，异步落盘足够，
     * 不必在主线程等 `commit()` 的 fsync。
     */
    fun putBoolean(key: String, value: Boolean) {
        store()?.edit()?.putBoolean(key, value)?.apply()
    }

    fun putString(key: String, value: String) {
        store()?.edit()?.putString(key, value)?.apply()
    }

    fun putLong(key: String, value: Long) {
        store()?.edit()?.putLong(key, value)?.apply()
    }
}
