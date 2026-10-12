import { describe, it, expect } from '@ohos/hypium';
import { formatBytes, formatSpeed, formatDuration, formatErrorDetail } from '../../../main/ets/common/Format';
import {
  BYTES_CASES,
  SPEED_CASES,
  DURATION_CASES,
  ERROR_DETAIL_CASES,
  NumberCase,
  ErrorDetailCase,
} from './Cases.gen';

/**
 * 展示格式化纯函数测试：这些文案错了只会「看起来怪」，不会被编译或运行时报错发现。
 *
 * ⚠️ 期望值**不写在这里**：唯一真相是 `shared/cases/format.json`（生成物 `Cases.gen.ets`），
 * 与安卓 `FormatTest` 跑的是同一套向量。
 */
export default function formatTest() {
  describe('format helpers', () => {
    it('formatBytes matches the shared vectors', 0, () => {
      BYTES_CASES.forEach((c: NumberCase) => {
        expect(formatBytes(c.input)).assertEqual(c.expect);
      });
    });

    it('formatSpeed matches the shared vectors', 0, () => {
      SPEED_CASES.forEach((c: NumberCase) => {
        expect(formatSpeed(c.input)).assertEqual(c.expect);
      });
    });

    it('formatDuration matches the shared vectors', 0, () => {
      DURATION_CASES.forEach((c: NumberCase) => {
        expect(formatDuration(c.input)).assertEqual(c.expect);
      });
    });

    it('formatDuration rejects non-finite input', 0, () => {
      // NaN / Infinity 在 JSON 里表达不了，这两条只能各端手写
      expect(formatDuration(Number.NaN)).assertEqual('');
      expect(formatDuration(Number.POSITIVE_INFINITY)).assertEqual('');
    });

    it('formatErrorDetail matches the shared vectors', 0, () => {
      ERROR_DETAIL_CASES.forEach((c: ErrorDetailCase) => {
        const message: string | undefined = c.message === null ? undefined : c.message;
        const code: number | undefined = c.code === null ? undefined : c.code;
        expect(formatErrorDetail(message, code)).assertEqual(c.expect);
      });
    });
  });
}
