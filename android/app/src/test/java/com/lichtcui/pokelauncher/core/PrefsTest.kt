package com.lichtcui.pokelauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Prefs] 的「未初始化」契约。
 *
 * 单元测试跑在 mockable android.jar 上，拿不到真 Context，所以这里只能锁住降级行为：
 * 读走 fallback、写静默 no-op。这也正是 `CheatState` / 首页自动检查在 JVM 上能跑的原因
 * （真实读写由真机验证）。
 */
class PrefsTest {

    @Test
    fun uninitializedReadsFallBackToDefaults() {
        assertNull(Prefs.store())
        assertEquals(false, Prefs.getBoolean("missing", false))
        assertEquals(true, Prefs.getBoolean("missing", true))
        assertEquals("", Prefs.getString("missing"))
        assertEquals(7L, Prefs.getLong("missing", 7L))
        assertEquals(0L, Prefs.getLong("missing", 0L))
    }

    @Test
    fun uninitializedWritesAreSilentNoOps() {
        Prefs.putBoolean("b", true)
        Prefs.putString("s", "x")
        Prefs.putLong("l", 1234L)
        // 没有崩溃，也没有「凭空造出一个 store」
        assertNull(Prefs.store())
    }
}
