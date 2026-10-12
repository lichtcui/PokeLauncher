import { describe, it, expect } from '@ohos/hypium';
import { normalizeVersion, sameVersion } from '../../../main/ets/model/GameRepository';
import { mimeOf } from '../../../main/ets/common/Mime';
import { NORMALIZE_CASES, SAME_CASES, MIME_CASES, NormalizeCase, SameCase, MimeCase } from './Cases.gen';

/**
 * 纯函数单元测试（跑在设备上）。
 * 只覆盖无副作用、无需 context 的逻辑，作为回归护栏：
 * 版本号比较与资源 MIME 判定都是「错了会导致静默故障」的点。
 *
 * ⚠️ 期望值**不写在这里**：唯一真相是 `shared/cases/version.json` 与 `shared/mime.json`
 * （生成物 `Cases.gen.ets`），与安卓 `VersionTest` / `MimeTest` 跑的是同一套向量。
 */
export default function gameRepositoryTest() {
  describe('pure helpers', () => {
    it('normalizeVersion matches the shared vectors', 0, () => {
      NORMALIZE_CASES.forEach((c: NormalizeCase) => {
        expect(normalizeVersion(c.input)).assertEqual(c.expect);
      });
    });

    it('sameVersion matches the shared vectors', 0, () => {
      SAME_CASES.forEach((c: SameCase) => {
        expect(sameVersion(c.local, c.remote)).assertEqual(c.expect);
      });
    });

    it('mimeOf matches the shared table', 0, () => {
      MIME_CASES.forEach((c: MimeCase) => {
        expect(mimeOf(c.path)).assertEqual(c.expect);
      });
    });
  });
}
