package com.lichtcui.pokelauncher.core

/**
 * 版本号处理（纯函数，可 JVM 单测）。
 * 对齐鸿蒙版 `GameRepository.normalizeVersion`：zip 内的 `currentVersion.txt` 只存 commit，
 * 而 GitHub Release 的 tag 是 `release-<commit>`，两侧比较前必须先把前缀去掉。
 */
object Version {

    const val RELEASE_PREFIX = "release-"

    /** `release-abc1234` → `abc1234`；其他原样返回（不做 trim，与鸿蒙版一致） */
    fun normalize(v: String): String =
        if (v.startsWith(RELEASE_PREFIX)) v.substring(RELEASE_PREFIX.length) else v

    /** 是否与远程 tag 表示同一个版本（空串一律视为不相等） */
    fun same(local: String, remote: String): Boolean {
        if (local.isEmpty() || remote.isEmpty()) return false
        return normalize(local) == normalize(remote)
    }
}
