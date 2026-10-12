import { appTasks } from '@ohos/hvigor-ohos-plugin';
import { HvigorPlugin } from '@ohos/hvigor';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

/**
 * 跨端共享代码校验插件。
 *
 * 背景：鸿蒙（ArkTS）与安卓（Kotlin）之间没有共同运行时，能共享的只有 `shared/**` 里的
 * JS 与数据，它们由 `scripts/gen-shared.mjs` **生成**成两端源码（见 `shared/README.md`）。
 * 生成器同时会校验：生成文件是否过期、两端常量（端口 / 镜像 / 版本清单地址）是否分叉、
 * 有没有人把共享 JS 重新内联回某一端的代码里。
 *
 * 这里在 hvigor 配置阶段就跑它，**编译前先失败**——否则「改了 shared/ 忘了跑生成器」的后果是
 * 两端行为静默不一致，比编译错误难查得多。
 */
const CHECK_SCRIPT: string = 'scripts/gen-shared.mjs';

let alreadyChecked: boolean = false;

function runSharedCodeCheck(): void {
  if (alreadyChecked) {
    return;
  }
  alreadyChecked = true;
  // hvigor 的 cwd 就是工程根；`__dirname` 在 ESM 下不存在，所以做个兜底
  const root: string = typeof __dirname === 'string' ? __dirname : process.cwd();
  if (!existsSync(join(root, CHECK_SCRIPT))) {
    throw new Error(`找不到 ${CHECK_SCRIPT}（工程根判定为 ${root}）`);
  }
  try {
    // 用跑 hvigor 的那个 node，避免 PATH 里没有 node 导致误报
    execFileSync(process.execPath, [CHECK_SCRIPT, '--check'], { cwd: root, stdio: 'inherit' });
  } catch (e) {
    throw new Error(`shared/ 与两端生成代码不一致，请执行：node ${CHECK_SCRIPT}（原因见上方输出）`);
  }
}

function checkSharedCode(): HvigorPlugin {
  return {
    pluginId: 'checkSharedCode',
    apply(): void {
      // 直接在配置阶段跑（不给某个 task 挂钩子）：挂 `beforeRun` 的话，任务命中增量缓存时
      // 钩子不会执行，校验就会「时灵时不灵」——这种护栏必须每次构建都跑。
      runSharedCodeCheck();
    }
  };
}

export default {
  system: appTasks,
  plugins: [checkSharedCode()]
}
