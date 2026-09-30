import { describe, it, expect } from '@ohos/hypium';
import { resolveRequestPath } from '../../../main/ets/model/LocalHttpServer';

/**
 * 本地 HTTP 服务的路径穿越判拒：这是安全边界（把 gameDir 之外的文件挡在门外）。
 * 覆盖字面量与 URL 编码两种绕过方式。
 */
export default function localHttpServerTest() {
  describe('resolveRequestPath', () => {
    it('passes through ordinary asset paths', 0, () => {
      expect(resolveRequestPath('/index.html')).assertEqual('/index.html');
      expect(resolveRequestPath('/js/main.js')).assertEqual('/js/main.js');
      expect(resolveRequestPath('/images/logo.png')).assertEqual('/images/logo.png');
    });

    it('strips query and fragment', 0, () => {
      expect(resolveRequestPath('/index.html?v=1')).assertEqual('/index.html');
      expect(resolveRequestPath('/main.js#x')).assertEqual('/main.js');
    });

    it('maps root and empty to index', 0, () => {
      expect(resolveRequestPath('/')).assertEqual('/index.html');
      expect(resolveRequestPath('')).assertEqual('/index.html');
    });

    it('rejects literal path traversal', 0, () => {
      expect(resolveRequestPath('/../etc/passwd')).assertEqual('');
      expect(resolveRequestPath('/js/../main.js')).assertEqual('');
    });

    it('rejects URL-encoded traversal', 0, () => {
      expect(resolveRequestPath('/%2e%2e/%2e%2e/etc/passwd')).assertEqual('');
      expect(resolveRequestPath('/js/%2e%2e/main.js')).assertEqual('');
      expect(resolveRequestPath('/%2E%2E/index.html')).assertEqual('');
    });

    it('keeps harmless dotted segments', 0, () => {
      expect(resolveRequestPath('/data.folder/file.js')).assertEqual('/data.folder/file.js');
    });
  });
}