import { describe, it, expect } from '@ohos/hypium';
import { resolveRequestPath } from '../../../main/ets/model/LocalHttpServer';
import { cacheControl } from '../../../main/ets/common/HttpCache';
import { PATH_CASES, PathCase, CACHE_CASES, CacheCase } from './Cases.gen';

/**
 * 本地 HTTP 服务的路径穿越判拒：这是安全边界（把 gameDir 之外的文件挡在门外）。
 *
 * ⚠️ 期望值**不写在这里**：唯一真相是 `shared/cases/paths.json`（生成物 `Cases.gen.ets`），
 * 与安卓 `PathsTest` 跑的是同一套向量。手写一份 → 两端分叉时没人发现。
 */
export default function localHttpServerTest() {
  describe('resolveRequestPath', () => {
    it('matches the shared vectors', 0, () => {
      PATH_CASES.forEach((c: PathCase) => {
        const actual: string = resolveRequestPath(c.input);
        // null = 应当拒绝；鸿蒙侧「拒绝」的约定是返回空串
        expect(actual).assertEqual(c.expect === null ? '' : c.expect);
      });
    });

    it('still contains traversal rejections', 0, () => {
      // 防止有人把向量里的拒绝项删光，变成「全部放行」还全绿
      const rejected: number = PATH_CASES.filter((c: PathCase) => c.expect === null).length;
      expect(rejected >= 8).assertTrue();
    });
  });

  /**
   * 响应头 `Cache-Control`：HTML / service-worker 被缓存住的症状是
   * 「改了注入内容却看不到变化」，只有真机才看得见——所以由向量守着。
   *
   * ⚠️ 期望值同样**不写在这里**（`shared/cases/http.json` → `CACHE_CASES`），
   * 与安卓 `HttpCacheTest` 跑的是同一套向量。
   */
  describe('cacheControl', () => {
    it('matches the shared vectors', 0, () => {
      CACHE_CASES.forEach((c: CacheCase) => {
        expect(cacheControl(c.path)).assertEqual(c.expect);
      });
    });
  });
}
