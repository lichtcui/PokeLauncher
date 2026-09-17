/**
 * 纯展示格式化工具（无副作用，可单元测试）。
 * 从 `pages/Index.ets` 抽出，避免首页继续膨胀，并纳入 ohosTest 覆盖。
 */

/** 字节数 → 人类可读：0 B / 356 KB / 8.2 MB / 1.1 GB */
export function formatBytes(n: number): string {
  if (n <= 0) {
    return '0 B';
  }
  const gb: number = n / 1024 / 1024 / 1024;
  if (gb >= 1) {
    return gb.toFixed(1) + ' GB';
  }
  const mb: number = n / 1024 / 1024;
  if (mb >= 1) {
    return mb.toFixed(1) + ' MB';
  }
  return (n / 1024).toFixed(0) + ' KB';
}

/** 速度：8.2 MB/s / 356 KB/s；非正数返回空串 */
export function formatSpeed(bytesPerSec: number): string {
  if (bytesPerSec <= 0) {
    return '';
  }
  return formatBytes(bytesPerSec) + '/s';
}

/**
 * 错误详情文案：优先用 message；BusinessError 的 message 可能为空，
 * 此时回退到错误码，避免界面上出现「下载失败：」这样的空尾。
 */
export function formatErrorDetail(message: string | undefined, code: number | undefined): string {
  if (message !== undefined && message.length > 0) {
    return message;
  }
  if (code !== undefined) {
    return '错误码 ' + code;
  }
  return '未知错误';
}

/** 剩余时间（中文概数）：约 25 秒 / 约 3 分钟 / 约 1 小时 5 分钟；无效返回空串 */
export function formatDuration(seconds: number): string {
  if (seconds <= 0 || !isFinite(seconds)) {
    return '';
  }
  if (seconds < 60) {
    return '约 ' + Math.max(1, Math.round(seconds)) + ' 秒';
  }
  const minutes: number = Math.round(seconds / 60);
  if (minutes < 60) {
    return '约 ' + minutes + ' 分钟';
  }
  const hours: number = Math.floor(minutes / 60);
  const rest: number = minutes % 60;
  return '约 ' + hours + ' 小时' + (rest > 0 ? ' ' + rest + ' 分钟' : '');
}
