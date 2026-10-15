/**
 * 作答端防作弊策略（`antiCheatConfig`）的**唯一读取口径**（add-anti-cheat-restrictions 阶段 3）。
 *
 * ## 为什么集中在这里
 *
 * 后端 `EnterExamResponse.antiCheatConfig` 是 `JsonNode`（生成的 TS 类型是 `unknown`），
 * 历史快照、脏数据、未来新增键都会出现在同一列里。消费方若各自 `as` 一把，
 * 迟早出现"某处把字符串 `'false'` 当真值"这类事故——所以只在这里窄化一次：
 * 页面/组件/hook 一律只拿 {@link AntiCheatPolicy}。
 *
 * ## 三条纪律
 *
 * 1. **只认严格 `true`**：白名单外的形状（字符串 `'true'`、数字 `1`、数组、null）
 *    一律按"未开启"处理——与后端 `AntiCheatConfigs`「脏配置不阻断、不误伤」同款取舍：
 *    拿不准时**不拦学生**，绝不让配置解析歧义变成学生的作答障碍；
 * 2. **守卫只警告 + 记录**：本模块不含任何交卷/作废概念（硬约定：防作弊绝不强制交卷），
 *    提示文案也据此写死，避免各处自造"已作废/自动交卷"之类的越界措辞；
 * 3. **IP 白名单预检只做「明显不合法」**：白名单写错等于把整场考生挡在门外（后端不命中即 403），
 *    所以建考表单在保存前提示；但**权威匹配器仍是后端 `IpMatcher`**，
 *    本模块的判定是超集容忍的（拿不准一律判合法），只把确定解析不出来的条目列为问题。
 */

/** 作答端两个守卫开关（其余键前端不读，也绝不臆造语义）。 */
export interface AntiCheatPolicy {
  /** 禁止复制/剪切/粘贴/右键菜单（true 时拦截并旁路上报 COPY_PASTE）。 */
  forbidCopy: boolean;
  /** 要求全屏作答（true 时弹层引导并监听退出上报 FULLSCREEN_EXIT）。 */
  fullScreen: boolean;
}

/** 被拦截的剪贴板 DOM 事件（事件名即上报 eventData.action）。 */
export type ClipboardAction = 'copy' | 'cut' | 'paste' | 'contextmenu';

/** 剪贴板事件清单（监听注册与单测共用，避免两处各写一份）。 */
export const CLIPBOARD_EVENTS: readonly ClipboardAction[] = ['copy', 'cut', 'paste', 'contextmenu'];

/**
 * 友好提示文案（常量唯一真源）：三条都显式写明"只记录、不交卷"，
 * 避免作答端出现"你已被判定作弊"这类前端无权给出的结论。
 */
export const ANTI_CHEAT_HINT = {
  COPY_BLOCKED:
    '本场考试已禁用复制、剪切、粘贴与右键菜单，该操作已被记录（只警告，不交卷；是否处置由教师依据行为日志判定）',
  FULLSCREEN_REQUIRED: '本场考试要求全屏作答，请点击下方按钮进入全屏后继续',
  FULLSCREEN_EXIT: '已退出全屏作答模式，该操作已被记录（只警告，不交卷；请重新进入全屏继续作答）',
} as const;

/** 未配置/脏配置的缺省策略：两个守卫都关（绝不误伤）。 */
const POLICY_OFF: AntiCheatPolicy = { forbidCopy: false, fullScreen: false };

/**
 * 从 `antiCheatConfig`（未知形状）读取守卫策略。
 * 只看两个布尔键且只认严格 `true`；非对象（含 null/数组/字符串）一律返回全关。
 */
export function readAntiCheatPolicy(config: unknown): AntiCheatPolicy {
  if (typeof config !== 'object' || config === null || Array.isArray(config)) {
    return { ...POLICY_OFF };
  }
  const record = config as Record<string, unknown>;
  return {
    forbidCopy: record.forbidCopy === true,
    fullScreen: record.fullScreen === true,
  };
}

/** DOM 事件名 → 剪贴板动作；非剪贴板事件返回 null（不误拦）。 */
export function asClipboardAction(type: string): ClipboardAction | null {
  const normalized = type.trim().toLowerCase();
  return (CLIPBOARD_EVENTS as readonly string[]).includes(normalized)
    ? (normalized as ClipboardAction)
    : null;
}

/**
 * 建考表单的白名单文本 → 规范化条目数组：按换行与逗号拆分、去首尾空白、
 * 丢弃空项、去重并保持声明顺序（与后端 `AntiCheatConfigs.ipWhitelist` 同口径）。
 * 非字符串输入（脏回填）返回空数组——空数组即"不做 IP 限制"。
 */
export function parseIpWhitelistText(raw: unknown): string[] {
  if (typeof raw !== 'string') return [];
  const seen = new Set<string>();
  for (const piece of raw.split(/[\n,]/)) {
    const entry = piece.trim();
    if (entry !== '') seen.add(entry);
  }
  return [...seen];
}

/** 条目数组 → 多行文本（编辑回填用，与 parseIpWhitelistText 互逆）。 */
export function formatIpWhitelistText(entries: readonly string[]): string {
  return entries.join('\n');
}

/**
 * 列出**按服务端匹配规则确定解析不出来**的白名单条目（建考表单保存前提示）。
 *
 * 判定是超集容忍的：只要可能被 `IpMatcher` 接受就不列（零误报优先于零漏报），
 * 因此拿不准的 IPv6 形态交给后端裁决，不在这里拦教师。
 */
export function invalidIpWhitelistEntries(entries: readonly string[]): string[] {
  return entries.filter((entry) => entry.trim() !== '' && !isSupportedEntry(entry.trim()));
}

function isSupportedEntry(entry: string): boolean {
  if (entry === '*') return true; // 全通配（仅 IPv4 生效，服务端如此）
  const slash = entry.indexOf('/');
  if (slash >= 0) {
    const prefixText = entry.slice(slash + 1).trim();
    if (!/^\d{1,3}$/.test(prefixText)) return false;
    const family = addressFamily(entry.slice(0, slash));
    return family !== null && Number(prefixText) <= family * 8;
  }
  if (entry.includes('*')) return isWildcardIpv4(entry);
  return addressFamily(entry) !== null;
}

/** 通配符只认整段（`192.168.1.*` / `10.*.*.*`），半段通配（`192.168.1.1*`）判不合法。 */
function isWildcardIpv4(entry: string): boolean {
  const segments = entry.split('.');
  if (segments.length !== 4) return false;
  return segments.every((segment) => segment === '*' || isIpv4Octet(segment));
}

/** IP 字面量的族位数：4 = IPv4（含 IPv4-mapped 归一化后的形态），16 = IPv6；非法返回 null。 */
function addressFamily(entry: string): 4 | 16 | null {
  const candidate = entry.trim();
  if (candidate === '') return null;
  if (!candidate.includes(':')) return isIpv4Literal(candidate) ? 4 : null;
  const groups = countIpv6Groups(candidate);
  if (groups === null) return null;
  // ::ffff:a.b.c.d 会被服务端归一化成 IPv4 再比较，但仍属 IPv6 字面量族
  return 16;
}

function isIpv4Literal(candidate: string): boolean {
  const segments = candidate.split('.');
  return segments.length === 4 && segments.every((segment) => isIpv4Octet(segment));
}

/** 单个 IPv4 段：纯数字、最多 3 位、0..255（与 `IpMatcher.parseOctet` 同口径）。 */
function isIpv4Octet(segment: string): boolean {
  return /^\d{1,3}$/.test(segment) && Number(segment) <= 255;
}

/**
 * IPv6 组数计数（内嵌 IPv4 计 2 组）：非法返回 null。
 * 与 `IpMatcher.parseIpv6` 同判据——至多一个 `::`，非压缩形态必须恰好 8 组，
 * 压缩形态至多 7 组；段长 ≤4 且为十六进制；作用域后缀 `%eth0` 先剥离。
 */
function countIpv6Groups(raw: string): number | null {
  let candidate = raw.trim();
  const zone = candidate.indexOf('%');
  if (zone > 0) candidate = candidate.slice(0, zone);

  const doubleColon = candidate.indexOf('::');
  const compressed = doubleColon >= 0;
  const headPart = compressed ? candidate.slice(0, doubleColon) : candidate;
  const tailPart = compressed ? candidate.slice(doubleColon + 2) : null;
  if (tailPart !== null && tailPart.includes('::')) return null;

  const head = readIpv6Groups(headPart, !compressed);
  if (head === null) return null;
  const tail = tailPart === null ? 0 : readIpv6Groups(tailPart, true);
  if (tail === null) return null;

  const total = head + tail;
  if (compressed ? total > 7 : total !== 8) return null;
  return total;
}

function readIpv6Groups(part: string, allowEmbeddedIpv4: boolean): number | null {
  if (part === '') return 0;
  const segments = part.split(':');
  let count = 0;
  for (let i = 0; i < segments.length; i++) {
    const segment = segments[i];
    if (segment === '') return null; // 空段只可能来自 "::"，已在上层剥离
    if (allowEmbeddedIpv4 && i === segments.length - 1 && segment.includes('.')) {
      if (!isIpv4Literal(segment) || count > 6) return null;
      count += 2;
      continue;
    }
    if (!/^[0-9a-fA-F]{1,4}$/.test(segment)) return null;
    if (count >= 8) return null;
    count += 1;
  }
  return count;
}
