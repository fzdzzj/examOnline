/**
 * 措辞纪律护栏单测（创新点 5 二期 · 阶段 4）。
 *
 * 规范要求（spec-delta 与 proposal.md 红线）：
 * 1. 严格禁止出现「离线考试 / 离线作答 / offline exam / offline answer」表述；
 * 2. 能力表述为「断网重入已领取的考试 / 本地保存 / 离线保护」；
 * 3. 护栏覆盖全部常量文案定义及 UI 关键模板。
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import {
  OFFLINE_LIST_NOTICE,
  OFFLINE_REENTRY_NOTICE,
  OFFLINE_STATUS_BANNER_TEXT,
} from '@/constants/studentTaking';

const HERE = path.dirname(fileURLToPath(import.meta.url));

const FORBIDDEN_WORDS = ['离线考试', '离线作答', 'offline exam', 'offline answer'];

function assertNoForbiddenWords(text: string, sourceName: string): void {
  const lower = text.toLowerCase();
  for (const word of FORBIDDEN_WORDS) {
    expect(
      lower.includes(word.toLowerCase()),
      `[${sourceName}] 违反措辞纪律，包含了禁用词 "${word}"：\n${text}`
    ).toBe(false);
  }
}

function stripComments(code: string): string {
  return code.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\/|\/\/.*/g, '');
}

describe('措辞纪律护栏（禁用「离线考试 / 离线作答」等表述）', () => {
  it('① 常量文件 OFFLINE_REENTRY_NOTICE 严格遵守措辞纪律', () => {
    assertNoForbiddenWords(OFFLINE_REENTRY_NOTICE.TITLE, 'OFFLINE_REENTRY_NOTICE.TITLE');
    assertNoForbiddenWords(
      OFFLINE_REENTRY_NOTICE.RISK_WARNING,
      'OFFLINE_REENTRY_NOTICE.RISK_WARNING'
    );
    assertNoForbiddenWords(
      OFFLINE_REENTRY_NOTICE.DESCRIPTION,
      'OFFLINE_REENTRY_NOTICE.DESCRIPTION'
    );
  });

  it('② 常量文件 OFFLINE_STATUS_BANNER_TEXT 严格遵守措辞纪律', () => {
    assertNoForbiddenWords(
      OFFLINE_STATUS_BANNER_TEXT.MESSAGE,
      'OFFLINE_STATUS_BANNER_TEXT.MESSAGE'
    );
    assertNoForbiddenWords(
      OFFLINE_STATUS_BANNER_TEXT.DESCRIPTION,
      'OFFLINE_STATUS_BANNER_TEXT.DESCRIPTION'
    );
  });

  it('③ 常量文件 OFFLINE_LIST_NOTICE 严格遵守措辞纪律', () => {
    assertNoForbiddenWords(OFFLINE_LIST_NOTICE.BADGE, 'OFFLINE_LIST_NOTICE.BADGE');
    assertNoForbiddenWords(OFFLINE_LIST_NOTICE.ALERT_MESSAGE, 'OFFLINE_LIST_NOTICE.ALERT_MESSAGE');
    assertNoForbiddenWords(
      OFFLINE_LIST_NOTICE.ALERT_DESCRIPTION,
      'OFFLINE_LIST_NOTICE.ALERT_DESCRIPTION'
    );
  });

  it('④ OfflineStatusBanner.vue 组件源码中不含禁用词', () => {
    const filePath = path.resolve(HERE, '../../components/student/OfflineStatusBanner.vue');
    const content = readFileSync(filePath, 'utf-8');
    assertNoForbiddenWords(stripComments(content), 'OfflineStatusBanner.vue');
  });

  it('⑤ 作答页 [id].page.vue 源码中不含禁用词', () => {
    const filePath = path.resolve(HERE, '../../pages/(dashboard)/student/exams/[id].page.vue');
    const content = readFileSync(filePath, 'utf-8');
    assertNoForbiddenWords(stripComments(content), '[id].page.vue');
  });

  it('⑥ 考试列表 index.page.vue 源码中不含禁用词', () => {
    const filePath = path.resolve(HERE, '../../pages/(dashboard)/student/exams/index.page.vue');
    const content = readFileSync(filePath, 'utf-8');
    assertNoForbiddenWords(stripComments(content), 'index.page.vue');
  });
});
