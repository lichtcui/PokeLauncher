import { describe, it, expect } from '@ohos/hypium';
import { normalizeVersion } from '../../../main/ets/model/GameRepository';
import { mimeOf } from '../../../main/ets/common/Mime';

/**
 * 纯函数单元测试（跑在设备上）。
 * 只覆盖无副作用、无需 context 的逻辑，作为回归护栏：
 * 版本号比较与资源 MIME 判定都是「错了会导致静默故障」的点。
 */
export default function gameRepositoryTest() {
  describe('pure helpers', () => {
    it('normalizeVersion strips the release- prefix only', 0, () => {
      expect(normalizeVersion('release-abc123')).assertEqual('abc123');
      expect(normalizeVersion('abc123')).assertEqual('abc123');
      expect(normalizeVersion('')).assertEqual('');
    });

    it('mimeOf maps common asset extensions', 0, () => {
      expect(mimeOf('/game/index.html')).assertEqual('text/html');
      expect(mimeOf('/game/main.js')).assertEqual('text/javascript');
      expect(mimeOf('/game/style.css')).assertEqual('text/css');
      expect(mimeOf('/game/app.webmanifest')).assertEqual('application/manifest+json');
      expect(mimeOf('/game/logo.png')).assertEqual('image/png');
      expect(mimeOf('/game/bgm.mp3')).assertEqual('audio/mpeg');
      expect(mimeOf('/game/font.woff2')).assertEqual('font/woff2');
      expect(mimeOf('/game/upper.PNG')).assertEqual('image/png');
    });

    it('mimeOf falls back to octet-stream', 0, () => {
      expect(mimeOf('/game/no-extension')).assertEqual('application/octet-stream');
      expect(mimeOf('/game/data.unknownext')).assertEqual('application/octet-stream');
    });
  });
}
