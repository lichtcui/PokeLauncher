import { describe, it, expect } from '@ohos/hypium';
import { numberLiteral, resolveNumber, displayValue } from '../../../main/ets/common/CheatFormat';
import { CHEAT_VALUE_CASES, CheatValueCase } from './Cases.gen';

/**
 * 参数调整的数值取值（夹紧 / 浮点吸附）与展示文案（labels 档位名 / 单位）。
 *
 * ⚠️ 期望值**不写在这里**：唯一真相是 `shared/cases/cheat.json`（生成物 `Cases.gen.ets`），
 * 与安卓 `CheatFormatTest` 跑的是同一套向量 —— 这两端的实现曾经真分叉过
 * （单位拼接方向相反：`x10` vs `10x`；labels 档位判定一宽一严）。
 */
export default function cheatFormatTest() {
  describe('cheat value formatting', () => {
    it('resolveNumber / numberLiteral / displayValue match the shared vectors', 0, () => {
      CHEAT_VALUE_CASES.forEach((c: CheatValueCase) => {
        const v: number = resolveNumber(c.param, c.raw === null ? undefined : c.raw);
        expect(v).assertEqual(c.expectValue);
        expect(numberLiteral(v)).assertEqual(c.expectLiteral);
        expect(displayValue(c.param, v)).assertEqual(c.expectDisplay);
      });
    });

    // NaN / Infinity 在 JSON 里表达不了，这两条各端自己补（安卓 CheatFormatTest 里也有）
    it('non-finite values fall back to 0', 0, () => {
      expect(numberLiteral(Number.NaN)).assertEqual('0');
      expect(numberLiteral(Number.POSITIVE_INFINITY)).assertEqual('0');
      expect(numberLiteral(Number.NEGATIVE_INFINITY)).assertEqual('0');
    });
  });
}
