import { describe, it, expect } from '@ohos/hypium';
import { LEGAL_BANNER, LEGAL_SECTIONS, LEGAL_UPDATED_AT, LegalSection } from '../../../main/ets/model/LegalText';

/**
 * 法律页正文（纯数据）的守门测试，与安卓侧 `LegalTextTest` 同一套判据。
 *
 * 两个目的：
 * 1. 结构完整性——章节 id 不重复、标题/段落都不为空（漏字段在界面上就是一个空白块）；
 * 2. **审核红线**——所有用户可见文案不得出现「作弊」字样（应用商店审核风险），
 *    该功能统一叫「参数调整」。
 *
 * 正文由 `shared/legal.json` 生成（改文案改 shared/，别改这里）；生成器管结构，
 * 这个测试管「生成出来的东西能不能上线」。
 */
export default function legalTextTest() {
  /** 与安卓版 `ui/LegalText.kt` 的章节集合与顺序一致 */
  const expectedIds: string[] = [
    'positioning',
    'copyright',
    'network',
    'responsibility',
    'disclaimer',
    'compliance',
    'study',
    'prohibited',
    'license',
    'feedback'
  ];

  const allUserVisibleText = (): string[] => {
    const out: string[] = [LEGAL_BANNER, LEGAL_UPDATED_AT];
    LEGAL_SECTIONS.forEach((s: LegalSection) => {
      out.push(s.id);
      out.push(s.title);
      s.body.forEach((p: string) => out.push(p));
    });
    return out;
  };

  describe('legal text', () => {
    it('sections match the android version (id + order)', 0, () => {
      expect(LEGAL_SECTIONS.length).assertEqual(expectedIds.length);
      LEGAL_SECTIONS.forEach((s: LegalSection, i: number) => {
        expect(s.id).assertEqual(expectedIds[i]);
      });
    });

    it('ids are unique', 0, () => {
      const ids: string[] = LEGAL_SECTIONS.map((s: LegalSection) => s.id);
      expect(new Set<string>(ids).size).assertEqual(ids.length);
    });

    it('titles and bodies are not blank', 0, () => {
      LEGAL_SECTIONS.forEach((s: LegalSection) => {
        expect(s.title.trim().length > 0).assertTrue();
        expect(s.body.length > 0).assertTrue();
        s.body.forEach((p: string) => {
          expect(p.trim().length > 0).assertTrue();
        });
      });
    });

    it('banner and timestamp are present', 0, () => {
      expect(LEGAL_BANNER.length > 10).assertTrue();
      expect(LEGAL_UPDATED_AT.startsWith('最近更新：')).assertTrue();
    });

    it('never uses the banned word', 0, () => {
      allUserVisibleText().forEach((text: string) => {
        expect(text.includes('作弊')).assertFalse();
      });
    });

    it('compliance section calls the feature by its approved name', 0, () => {
      const compliance = LEGAL_SECTIONS.find((s: LegalSection) => s.id === 'compliance');
      expect(compliance !== undefined).assertTrue();
      expect(compliance !== undefined && compliance.body.join('\n').includes('参数调整')).assertTrue();
    });
  });
}
