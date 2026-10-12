import { describe, it, expect } from '@ohos/hypium';
import {
  downloadCandidates,
  splitCandidates,
  rankSourcesByProbe,
  hostOf,
  kindOf,
  mirrorOf,
  ProbeResult,
  SourceSplit,
} from '../../../main/ets/common/DownloadUrls';
import {
  DOWNLOAD_CANDIDATE_CASES,
  DOWNLOAD_SPLIT_CASES,
  DOWNLOAD_RANK_CASES,
  DOWNLOAD_HOST_CASES,
  DOWNLOAD_KIND_CASES,
  DOWNLOAD_MIRROR_CASES,
  DownloadCandidateCase,
  DownloadSplitCase,
  DownloadRankCase,
  DownloadHostCase,
  DownloadKindCase,
  DownloadMirrorCase,
} from './Cases.gen';

/**
 * 下载候选地址 / 选源排序测试（对应安卓 `install/DownloadUrlsTest.kt`）。
 *
 * ⚠️ 期望值**不写在这里**：唯一真相是 `shared/cases/download.json`（生成物 `Cases.gen.ets`），
 * 与安卓 `DownloadUrlsTest` 跑的是同一套向量。
 * 这里只测纯逻辑：真实网络测速（probe）必须真机验证。
 *
 * 向量里的 `null` 兜底在鸿蒙侧用空串表示（与 paths 向量同口径）。
 */
export default function downloadUrlsTest() {
  describe('download urls', () => {
    it('candidates match the shared vectors', 0, () => {
      DOWNLOAD_CANDIDATE_CASES.forEach((c: DownloadCandidateCase) => {
        expect(downloadCandidates(c.assetUrl, c.mirrors, c.debugAssetUrl)).assertDeepEquals(c.expect);
      });
    });

    it('split matches the shared vectors', 0, () => {
      DOWNLOAD_SPLIT_CASES.forEach((c: DownloadSplitCase) => {
        const split: SourceSplit = splitCandidates(c.candidates);
        expect(split.probed).assertDeepEquals(c.expectProbed);
        expect(split.fallback).assertEqual(c.expectFallback === null ? '' : c.expectFallback);
      });
    });

    it('rank matches the shared vectors', 0, () => {
      DOWNLOAD_RANK_CASES.forEach((c: DownloadRankCase) => {
        const probed: ProbeResult[] = [];
        for (let i = 0; i < c.probed.length; i++) {
          probed.push({ url: c.probed[i].url, speed: c.probed[i].speed });
        }
        expect(rankSourcesByProbe(probed, c.fallback === null ? '' : c.fallback)).assertDeepEquals(c.expect);
      });
    });

    it('hosts match the shared vectors', 0, () => {
      DOWNLOAD_HOST_CASES.forEach((c: DownloadHostCase) => {
        expect(hostOf(c.url)).assertEqual(c.expect);
      });
    });

    it('source kinds match the shared vectors', 0, () => {
      DOWNLOAD_KIND_CASES.forEach((c: DownloadKindCase) => {
        expect(kindOf(c.index, c.count)).assertEqual(c.expect);
      });
    });

    it('mirror urls match the shared vectors', 0, () => {
      DOWNLOAD_MIRROR_CASES.forEach((c: DownloadMirrorCase) => {
        expect(mirrorOf(c.url, c.mirrors)).assertEqual(c.expect);
      });
    });
  });
}
