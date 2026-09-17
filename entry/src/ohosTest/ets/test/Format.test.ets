import { describe, it, expect } from '@ohos/hypium';
import { formatBytes, formatSpeed, formatDuration, formatErrorDetail } from '../../../main/ets/common/Format';

/** 展示格式化纯函数测试：这些文案错了只会「看起来怪」，不会被编译或运行时报错发现 */
export default function formatTest() {
  describe('format helpers', () => {
    it('formatBytes switches unit at the right thresholds', 0, () => {
      expect(formatBytes(0)).assertEqual('0 B');
      expect(formatBytes(-1)).assertEqual('0 B');
      expect(formatBytes(1024)).assertEqual('1 KB');
      expect(formatBytes(1024 * 1024)).assertEqual('1.0 MB');
      expect(formatBytes(1024 * 1024 * 1024)).assertEqual('1.0 GB');
    });

    it('formatSpeed appends /s and hides non-positive values', 0, () => {
      expect(formatSpeed(0)).assertEqual('');
      expect(formatSpeed(-100)).assertEqual('');
      expect(formatSpeed(1024)).assertEqual('1 KB/s');
      expect(formatSpeed(1024 * 1024)).assertEqual('1.0 MB/s');
    });

    it('formatDuration renders Chinese approximations', 0, () => {
      expect(formatDuration(0)).assertEqual('');
      expect(formatDuration(-5)).assertEqual('');
      expect(formatDuration(30)).assertEqual('约 30 秒');
      expect(formatDuration(90)).assertEqual('约 2 分钟');
      expect(formatDuration(3600)).assertEqual('约 1 小时');
      expect(formatDuration(3660)).assertEqual('约 1 小时 1 分钟');
    });

    it('formatErrorDetail prefers message then code', 0, () => {
      expect(formatErrorDetail('boom', 401)).assertEqual('boom');
      expect(formatErrorDetail('', 401)).assertEqual('错误码 401');
      expect(formatErrorDetail(undefined, 401)).assertEqual('错误码 401');
      expect(formatErrorDetail(undefined, undefined)).assertEqual('未知错误');
    });
  });
}
