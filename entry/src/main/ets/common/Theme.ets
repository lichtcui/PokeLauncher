/**
 * 跨端共享的视觉令牌（颜色）。
 *
 * ⚠️ **本文件由 `scripts/gen-shared.mjs` 自动生成，不要手改**：唯一真相在仓库根 `shared/theme.json`。
 * 改色值跑 `node scripts/gen-shared.mjs`，两端（鸿蒙 / 安卓）一起更新。
 *
 * 为什么要有它：ArkUI 与 Compose 没有共同渲染器，布局代码无法共享，能共享的只有数值与角色。
 * 统一到**鸿蒙现状**（亮色）之前，两端的颜色字面量一共 41 种、真正同值的只有 5 个，
 * 而且主题相反（鸿蒙首页白 / 安卓首页深）。
 *
 * 用法：`.fontColor(THEME.textPrimary)`、`.backgroundColor(THEME.surface)`。
 * 生成器那边的 `checkThemeUsage` 会反向要求 UI 代码真的引用这里：
 * 两个 UI 源码目录里再出现 `'#RRGGBB'` 字面量、或令牌没人引用，都会直接构建失败。
 *
 * 同一个 hex 出现在多个语义名下是**有意的别名**（background / surface / onBrand / onScrim 都是白色），
 * 别为了「去重」合并它们。
 */

export interface ThemePalette {
  /** 品牌红：主按钮底色、强调文字、错误文字（与鸿蒙现状一致：错误态也用品牌红） */
  brand: string;
  /** 品牌红底上的文字（主按钮文案、进度条百分比、选中分段） */
  onBrand: string;
  /** 页面大标题（法律页 / 参数调整页） */
  textTitle: string;
  /** 正文与列表项标题（默认文字色） */
  textPrimary: string;
  /** 法律页正文（比默认略淡，长段落更耐读） */
  textBody: string;
  /** 次要按钮文案、未选中分段文字 */
  textMuted: string;
  /** 字段名与说明文字 */
  textSecondary: string;
  /** 提示文字、设置项的当前值（最常用的一种淡字） */
  textTertiary: string;
  /** 页脚、极淡说明 */
  textFaint: string;
  /** 列表箭头「›」这类装饰性图形 */
  iconFaint: string;
  /** 启动器窗口底色（鸿蒙侧为 HOME_BG_COLOR，显示在安全区 / 系统栏留白处） */
  background: string;
  /** 卡片 / 弹窗底色 */
  surface: string;
  /** 次级容器（未选中分段底色、分组卡片） */
  surfaceVariant: string;
  /** 浅灰信息块 */
  surfaceMuted: string;
  /** 进度条轨道 */
  track: string;
  /** 分隔线 */
  divider: string;
  /** 警示文字（琥珀色） */
  warning: string;
  /** 警示块底色 */
  warningBg: string;
  /** 危险提示块底色（账号风险 / 删除数据） */
  dangerBg: string;
  /** 可点击链接（资源直链 / 镜像地址） */
  link: string;
  /** 成功 / 运行中（仅安卓打靶页的诊断指标用；鸿蒙没有对应界面） */
  success: string;
  /** 遮罩 60% 黑（游戏页错误横幅 / 弹窗遮罩） */
  scrim: string;
  /** 遮罩 40% 黑（游戏页返回按钮） */
  scrimLight: string;
  /** 遮罩 80% 黑（游戏页加载浮层 / 诊断面板） */
  scrimHeavy: string;
  /** 深色遮罩上的文字（游戏页「返回」「正在载入游戏…」） */
  onScrim: string;
  /** 文字投影色（红条上的百分比数字靠它压出对比） */
  shadow: string;
  /** 游戏页诊断面板 / 错误卡片底色（仅安卓） */
  gamePanel: string;
  /** 透明（纯文字按钮的底色） */
  transparent: string;
}

/** 令牌唯一实例：业务代码只通过它取色（`THEME.textPrimary`） */
export const THEME: ThemePalette = {
  /** 品牌红：主按钮底色、强调文字、错误文字（与鸿蒙现状一致：错误态也用品牌红） */
  brand: '#DA3838',
  /** 品牌红底上的文字（主按钮文案、进度条百分比、选中分段） */
  onBrand: '#FFFFFF',
  /** 页面大标题（法律页 / 参数调整页） */
  textTitle: '#1A1A1A',
  /** 正文与列表项标题（默认文字色） */
  textPrimary: '#333333',
  /** 法律页正文（比默认略淡，长段落更耐读） */
  textBody: '#555555',
  /** 次要按钮文案、未选中分段文字 */
  textMuted: '#666666',
  /** 字段名与说明文字 */
  textSecondary: '#888888',
  /** 提示文字、设置项的当前值（最常用的一种淡字） */
  textTertiary: '#999999',
  /** 页脚、极淡说明 */
  textFaint: '#BBBBBB',
  /** 列表箭头「›」这类装饰性图形 */
  iconFaint: '#CCCCCC',
  /** 启动器窗口底色（鸿蒙侧为 HOME_BG_COLOR，显示在安全区 / 系统栏留白处） */
  background: '#FFFFFF',
  /** 卡片 / 弹窗底色 */
  surface: '#FFFFFF',
  /** 次级容器（未选中分段底色、分组卡片） */
  surfaceVariant: '#F2F2F2',
  /** 浅灰信息块 */
  surfaceMuted: '#F7F7F7',
  /** 进度条轨道 */
  track: '#EEEEEE',
  /** 分隔线 */
  divider: '#EDEDED',
  /** 警示文字（琥珀色） */
  warning: '#C77700',
  /** 警示块底色 */
  warningBg: '#FFF8E8',
  /** 危险提示块底色（账号风险 / 删除数据） */
  dangerBg: '#FFF3F3',
  /** 可点击链接（资源直链 / 镜像地址） */
  link: '#3A6FD8',
  /** 成功 / 运行中（仅安卓打靶页的诊断指标用；鸿蒙没有对应界面） */
  success: '#4CAF50',
  /** 遮罩 60% 黑（游戏页错误横幅 / 弹窗遮罩） */
  scrim: '#99000000',
  /** 遮罩 40% 黑（游戏页返回按钮） */
  scrimLight: '#66000000',
  /** 遮罩 80% 黑（游戏页加载浮层 / 诊断面板） */
  scrimHeavy: '#CC000000',
  /** 深色遮罩上的文字（游戏页「返回」「正在载入游戏…」） */
  onScrim: '#FFFFFF',
  /** 文字投影色（红条上的百分比数字靠它压出对比） */
  shadow: '#99000000',
  /** 游戏页诊断面板 / 错误卡片底色（仅安卓） */
  gamePanel: '#F2202020',
  /** 透明（纯文字按钮的底色） */
  transparent: '#00000000'
};
