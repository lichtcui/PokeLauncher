/**
 * 作弊注册表：条目内容集中在此维护，可无限扩展。
 *
 * script 为「加载时注入」的 JS 片段，会在游戏脚本运行前由
 * LocalHttpServer 的 /__cheats__.js 动态下发，用于在启动前改变游戏逻辑。
 *
 * ⚠️ 风险：PokéRogue 官方对作弊有检测机制，Hook 改数据可能被标记/封禁。
 * 开启作弊后的存档不纯净，不建议再导入在线版。
 */
export interface Cheat {
  id: string;
  name: string;
  desc: string;
  script: string;
}

export const CHEATS: Cheat[] = [
  {
    id: 'demo',
    name: '示例作弊',
    desc: '仅用于验证注入链路（控制台打点，不影响游戏）',
    script: 'console.log("CHEAT demo injected at " + Math.round(performance.now()) + "ms");'
  }
];
