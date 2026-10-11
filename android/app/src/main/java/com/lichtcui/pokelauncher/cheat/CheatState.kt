package com.lichtcui.pokelauncher.cheat

import com.lichtcui.pokelauncher.core.Prefs

/**
 * 参数调整状态（对应鸿蒙版 `model/CheatState.ets`）。
 *
 * 内存单例 + SharedPreferences 持久化；服务端生成 `/__cheats__.js` 时**同步读内存快照**，
 * 所以状态必须在任何请求之前载入（[load] 由 `App.onCreate` 调用）。
 *
 * 线程模型：写操作来自 UI 线程，读操作来自 HTTP 线程（每个资源请求都可能读 [hasAnyEnabled]），
 * 因此状态字段都是不可变快照 + `@Volatile`，写时整体替换。
 */
object CheatState {

    // ⚠️ key 名与鸿蒙版一致（`cheatMaster` / `cheatEnabledIds` / `cheatParams`）
    const val KEY_MASTER = "cheatMaster"
    const val KEY_IDS = "cheatEnabledIds"
    const val KEY_PARAMS = "cheatParams"

    @Volatile
    private var master: Boolean = false

    @Volatile
    private var ids: List<String> = emptyList()

    @Volatile
    private var params: Map<String, String> = emptyMap()

    /** App 启动时调用：把偏好载入内存快照（未 init Prefs 时保持默认值，不抛异常） */
    @Synchronized
    fun load() {
        master = Prefs.getBoolean(KEY_MASTER, false)
        ids = CheatFormat.parseIds(Prefs.getString(KEY_IDS))
        params = CheatFormat.parseParams(Prefs.getString(KEY_PARAMS))
    }

    fun isMasterEnabled(): Boolean = master

    fun isEnabled(id: String): Boolean = ids.contains(id)

    /** 是否有启用中的条目（决定 index.html 是否注入 `/__cheats__.js` 标签） */
    fun hasAnyEnabled(): Boolean = master && ids.isNotEmpty()

    fun enabledIds(): List<String> = ids

    /** 总开关（内存 + 持久化），返回是否有变化 */
    @Synchronized
    fun setMaster(v: Boolean): Boolean {
        if (master == v) return false
        master = v
        Prefs.putBoolean(KEY_MASTER, master)
        return true
    }

    /** 切换单条（内存 + 持久化） */
    @Synchronized
    fun toggle(id: String) {
        val next = if (ids.contains(id)) ids.filterNot { it == id } else ids + id
        ids = next
        Prefs.putString(KEY_IDS, CheatFormat.encodeIds(next))
    }

    /** 清空所有启用项（总开关保持不动） */
    @Synchronized
    fun clearEnabled() {
        if (ids.isEmpty()) return
        ids = emptyList()
        Prefs.putString(KEY_IDS, CheatFormat.encodeIds(ids))
    }

    /** 数值参数（未设置 / 无法解析时回退到注册表里的 default，并按 min/max 夹紧） */
    fun getParam(cheatId: String): Double {
        val p = CHEATS.firstOrNull { it.id == cheatId }?.param ?: return 0.0
        return CheatFormat.resolveNumber(p, params[p.id])
    }

    /** 仅更新内存（拖动中高频调用，不落盘） */
    @Synchronized
    fun setParamLive(paramId: String, value: Double) {
        val next = value.toString()
        if (params[paramId] == next) return
        params = LinkedHashMap(params).apply { put(paramId, next) }
    }

    /** 设置数值参数（内存 + 立即持久化） */
    @Synchronized
    fun setParam(paramId: String, value: Double) {
        setParamLive(paramId, value)
        Prefs.putString(KEY_PARAMS, CheatFormat.encodeParams(params))
    }

    /** 当前参数快照（供脚本生成 / UI 展示） */
    fun paramValues(): Map<String, String> = params

    /** `/__cheats__.js` 的内容（总开关关闭或无条目时为空串） */
    fun buildBootScript(): String = CheatScripts.buildBootScript(master, ids, params)
}
