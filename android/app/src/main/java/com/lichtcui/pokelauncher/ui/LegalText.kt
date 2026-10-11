package com.lichtcui.pokelauncher.ui

/**
 * 法律与免责声明页的正文（对应鸿蒙版 `pages/Legal.ets` 的 `LEGAL_SECTIONS`）。
 *
 * 单独放一个不依赖 Compose 的文件，好处有二：能被 JVM 单测覆盖（比如扫「审核红线文案」），
 * 也让「改文案」这件事不必碰界面代码。
 *
 * 与鸿蒙版的差异只在两处措辞：本项目现在同时有鸿蒙与安卓两个客户端；
 * 自更新的载体不同（鸿蒙是 HAP，安卓是 APK）。免责边界本身逐条对齐。
 */

/** 一条免责章节 */
data class LegalSection(val id: String, val title: String, val body: List<String>)

/** 页面顶部常驻提示条 */
const val LEGAL_BANNER: String =
    "本项目为第三方非官方启动器，仅供个人学习交流与离线游玩使用，与 PokeRogue 官方无关。"

/** 页脚日期 */
const val LEGAL_UPDATED_AT: String = "最近更新：2026-09-18"

val LEGAL_SECTIONS: List<LegalSection> = listOf(
    LegalSection(
        id = "positioning",
        title = "项目定位",
        body = listOf(
            "本项目是第三方、非官方的启动器（Launcher），仅用于在 HarmonyOS 与 Android 设备上离线加载并运行 PokeRogue 游戏资源，改善其运行与操作体验。",
            "本项目与 PokeRogue 的官方开发团队、发行方及任何关联方无隶属、合作、赞助或授权关系，不代表官方立场。",
            "本仓库仅包含启动器代码，不包含、不打包、不再分发任何游戏本体资源（美术、音频、数据、代码等）；游戏资源在运行时由用户设备从公开来源下载。"
        )
    ),
    LegalSection(
        id = "copyright",
        title = "版权声明",
        body = listOf(
            "PokeRogue 及其游戏资源（含美术、音乐、音效、数据、源代码等）的著作权归其原作者及贡献者所有，并以其官方仓库声明的协议（如 AGPL-3.0）为准。本项目不对其主张任何权利。",
            "本项目启动器源代码以 MIT 协议开源（详见仓库根目录 LICENSE 文件），版权归本项目作者所有。",
            "本页及仓库中出现的第三方名称、商标、图标与资源，权利均归各自权利人所有，此处仅为说明性引用。"
        )
    ),
    LegalSection(
        id = "network",
        title = "网络与数据访问说明",
        body = listOf(
            "本启动器除下载游戏资源与检查自身更新外，不访问其他网络服务，也不收集、上传任何个人信息。",
            "游戏资源：在你主动点击「开始下载」后，应用会访问 PokeRogue 公开仓库的发布接口（api.github.com / github.com）获取资源包信息并下载资源包；为此可能使用第三方加速镜像（gh-proxy.com、ghfast.top、ghproxy.net），并在镜像不可用时回退到 GitHub 直连。",
            "应用更新：应用会定期访问 Gitee（gitee.com）上的版本清单，以检查启动器自身是否有新版本，不会上传任何设备或使用数据。",
            "下载的资源与你的存档均保存在设备本地应用沙箱内，卸载应用即随之删除；本应用不设服务器，不向任何第三方共享你的数据。"
        )
    ),
    LegalSection(
        id = "responsibility",
        title = "用户责任",
        body = listOf(
            "用户应自行确认其所在国家或地区对下载、运行、修改游戏资源以及使用参数调整功能的合法性，并自行承担由此产生的一切后果。",
            "用户应妥善保管游戏存档与账号。因使用本启动器（含参数调整功能）导致的账号被标记、封禁，或存档损坏、丢失，均由用户自行承担。",
            "用户应同时遵守游戏官方及资源来源方的服务条款与社区规范。"
        )
    ),
    LegalSection(
        id = "disclaimer",
        title = "免责条款",
        body = listOf(
            "本项目按「现状（AS IS）」提供，不对可用性、稳定性、兼容性、无错误或特定用途适用性作任何明示或默示担保。",
            "对于因使用或无法使用本项目而造成的任何直接或间接损失（包括但不限于账号封禁、存档丢失、设备或数据损坏、法律纠纷），作者及贡献者不承担任何责任。",
            "本项目可能随上游变化而失效，作者无义务提供维护、更新或技术支持。"
        )
    ),
    LegalSection(
        id = "compliance",
        title = "合规使用建议",
        body = listOf(
            "请仅在你拥有合法获取途径的前提下使用，并优先选择官方版本或官方认可的方式游玩。",
            "使用参数调整功能前，请确认仅在纯离线、单人、不涉及在线排行榜或联机的环境中使用，且不要将修改后的存档导入或上传至任何在线服务。",
            "若你喜爱本游戏，请通过官方渠道支持原作者，以帮助项目持续发展。"
        )
    ),
    LegalSection(
        id = "study",
        title = "学习交流的合法使用",
        body = listOf(
            "本项目仅供个人学习、技术研究与交流使用，可用于了解 HarmonyOS / Android 原生开发、WebView（Web 组件）、本地 HTTP 服务与离线资源加载等技术。",
            "在符合当地法律及游戏官方条款的前提下，用于个人非商业目的的学习交流属于允许范围。"
        )
    ),
    LegalSection(
        id = "prohibited",
        title = "禁止用途",
        body = listOf(
            "禁止将本项目用于任何违反当地法律法规的用途。",
            "禁止将本项目用于商业售卖、付费分发，或将其包装为官方产品进行推广、诱导消费。",
            "禁止利用本项目传播、下载或分发任何侵权内容，或用于规避、破坏游戏官方的正当技术与账号机制以牟利。"
        )
    ),
    LegalSection(
        id = "license",
        title = "开源许可",
        body = listOf(
            "本项目启动器代码以 MIT License 发布，可自由使用、修改与分发，但须保留原始版权与许可声明。",
            "PokeRogue 游戏资源不适用本许可，其版权与许可归原作者及贡献者所有。"
        )
    ),
    LegalSection(
        id = "feedback",
        title = "权利反馈",
        body = listOf(
            "本项目尊重各方知识产权。若你是相关权利人或授权代表，认为本项目的任何内容（包括但不限于名称、图标、截图、说明文字）侵犯了你的合法权益，请通过项目仓库的 Issue 与我们联系。",
            "项目仓库：github.com/lichtcui/PokeLauncher",
            "我们将在核实后及时删除相关内容或采取其他必要措施。"
        )
    )
)
