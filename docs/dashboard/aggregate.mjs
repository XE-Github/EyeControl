#!/usr/bin/env node
/**
 * EyeControl 匿名活跃统计 —— 聚合脚本(Gitee 直连方案)。
 *
 * 数据来源:Gitee 私有仓库 <OWNER>/<REPO> 下的
 *   data/YYYY-MM-DD/<did>.ndjson   —— 每台设备当天首次打开时创建的独立文件。
 *   文件【存在本身】即代表"该设备当天活跃";文件内容一行 JSON {ts, ver, model}。
 *
 * 聚合口径:
 *   - DAU(某天) = data/<day>/ 目录下的文件数(每设备每天一个文件,天然去重)。
 *   - WAU        = 近 7 天所有目录里 did 的并集大小(同一 did 多天只算一个)。
 *   - 30 天趋势  = 每天的文件数序列。
 *   - 版本/机型分布 = 读【今天】各文件内容统计(只读今天,省 API 调用)。
 *   - 反馈趋势   = 近 30 天 feedback/<day>/ 下 .json 文件数序列(【只数条数,绝不读正文】)。
 *
 * 产出:把聚合结果写成 stats.json,推到【公开看板仓库】的 docs/dashboard/stats.json。
 *   看板(GitHub Pages)只读这一个静态文件,不直接碰私有统计库,更不碰令牌。
 *
 * 运行环境:GitHub Actions(定时)。所需密钥从环境变量读,【绝不写进源码/仓库】:
 *   GITEE_TOKEN   —— 采集小号令牌(读私有统计库)。仅本脚本在 CI 内使用。
 *   注:本脚本【只读】统计库;写 stats.json 到公开看板仓库由 workflow 的 git push 完成。
 *
 * ── 加固(本次修复的两个真问题之一)──────────────────────────
 *  旧实现对 Gitee API 无节流、无重试:撞到限流(429/5xx)就 console.warn 后返回 [],
 *  【把那天静默当成 0】——聚合"成功"了但数据是错的("聚合失败却看不出")。现在:
 *   1) 每次请求带指数退避重试(429/5xx/网络异常);请求间小延时,削峰避限流。
 *   2) 区分「404 = 该天真的没数据」与「多次重试仍失败 = 拉取失败」。
 *   3) 任何一次拉取彻底失败 → 全脚本 process.exit(1) 让 Actions run 变红,
 *      【绝不把失败静默当 0 写进看板】。宁可这次不更新,也不展示错误数据。
 *
 * 计日时区:与 App 上报一致(App 用设备本地时区写目录名)。看板脚注标注口径。
 */

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";

const OWNER = process.env.STAT_OWNER || "jaxinleon_1";       // 采集小号(与主账号 jaxinleon 隔离)
const REPO = process.env.STAT_REPO || "eyecontrol-analytics";
const TOKEN = process.env.GITEE_TOKEN || "";
const OUT = process.env.OUT_PATH || "stats.json";
const API = "https://gitee.com/api/v5";

// 数据源目录的网页地址(供看板卡片跳转;私库,仅作者登录可见,访客看不到内容 → 不泄露)。
// 【不含 token】。token 只在环境变量里,绝不进产物。
const SRC_BASE = `https://gitee.com/${OWNER}/${REPO}/tree/master`;

// 请求节流 / 重试参数。
const RETRY_MAX = 4;           // 每个请求最多重试次数(不含首次)
const RETRY_BASE_MS = 600;     // 退避基数:600ms、1.2s、2.4s、4.8s…
const THROTTLE_MS = 120;       // 每次请求前的小延时,削峰避限流

if (!TOKEN) {
  console.error("缺少 GITEE_TOKEN 环境变量,无法读取私有统计库。");
  process.exit(1);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** YYYY-MM-DD(本地时区,与 App 上报口径一致)。 */
function dayStr(d) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/**
 * 带重试的 GET。返回 { status, json } 或抛错。
 *  - 404 视为正常结果(交由调用方判断是"目录不存在=当天无数据"还是"文件不存在")。
 *  - 429 / 5xx / 网络异常 → 指数退避重试;重试用尽仍失败则【抛错】(绝不静默降级)。
 */
async function fetchJson(url) {
  let lastErr = null;
  for (let attempt = 0; attempt <= RETRY_MAX; attempt++) {
    if (attempt > 0) await sleep(RETRY_BASE_MS * 2 ** (attempt - 1));
    await sleep(THROTTLE_MS);
    try {
      const r = await fetch(url, { headers: { "User-Agent": "EyeControl-Aggregate" } });
      if (r.status === 404) return { status: 404, json: null };
      if (r.status === 429 || r.status >= 500) {   // 限流 / 服务端错误 → 可重试
        lastErr = new Error(`HTTP ${r.status}`);
        continue;
      }
      if (!r.ok) throw new Error(`HTTP ${r.status}`);   // 4xx(非404)= 鉴权/路径错,重试无益,直接失败
      return { status: r.status, json: await r.json() };
    } catch (e) {
      lastErr = e;                                   // 网络异常 → 重试
    }
  }
  throw new Error(`请求失败(已重试 ${RETRY_MAX} 次): ${lastErr && lastErr.message}`);
}

/**
 * 列出某目录下的条目(Gitee Contents API 对目录返回数组)。
 * 404 → 该天没有任何活跃/反馈,返回 []。其它失败(重试用尽)→ 抛错,让整个 run 失败。
 */
async function listDir(path) {
  const url = `${API}/repos/${OWNER}/${REPO}/contents/${encodeURI(path)}?access_token=${TOKEN}`;
  const { status, json } = await fetchJson(url);
  if (status === 404) return [];
  return Array.isArray(json) ? json : [];
}

/**
 * 取文件原始内容。走 Contents API 读 base64 `content` 字段。
 * 【为何不用 download_url】私有库的 download_url 是 /raw/ 端点,带不带 token 都 403 读不到;
 * 只有 Contents API 带 access_token 才能读私有库文件。404 返回 null(文件恰好被删),其它失败抛错。
 */
async function getFileContent(path) {
  const url = `${API}/repos/${OWNER}/${REPO}/contents/${encodeURI(path)}?access_token=${TOKEN}`;
  const { status, json } = await fetchJson(url);
  if (status === 404 || !json || !json.content) return null;
  return Buffer.from(json.content, "base64").toString("utf-8");
}

/** 从目录条目列表里提取 did 集合(文件名去掉 .ndjson 后缀)。 */
function didsOf(entries) {
  const s = new Set();
  for (const e of entries) {
    if (e.type === "file" && e.name.endsWith(".ndjson")) {
      s.add(e.name.slice(0, -".ndjson".length));
    }
  }
  return s;
}

async function main() {
  const now = new Date();
  const today = dayStr(now);

  // 30 天趋势 + WAU 素材:逐天列目录。
  const dau30 = [];
  const feedback30 = [];        // 近 30 天每天反馈条数序列(只数条数,绝不读正文)
  const wauSet = new Set();
  let todayEntries = [];
  let feedbackTotal = 0;

  for (let i = 29; i >= 0; i--) {
    const d = new Date(now.getTime() - i * 86400000);
    const day = dayStr(d);

    const entries = await listDir(`data/${day}`);
    const dids = didsOf(entries);
    dau30.push({ day, count: dids.size });
    if (i <= 6) for (const x of dids) wauSet.add(x);   // 近 7 天并集 = WAU
    if (day === today) todayEntries = entries;

    // 反馈:【红线】只 listDir 数 .json 条数,绝不 getFileContent 读任何反馈正文。
    const fbEntries = await listDir(`feedback/${day}`);
    const fbCount = fbEntries.filter(
      (e) => e.type === "file" && e.name.endsWith(".json")
    ).length;
    feedback30.push({ day, count: fbCount });
    feedbackTotal += fbCount;
  }
  const dau = dau30[dau30.length - 1].count;
  const wau = wauSet.size;

  // 今日版本 / 机型分布:读今天每个文件内容。设备数通常不多,直读可接受。
  const versions = {};
  const models = {};
  for (const e of todayEntries) {
    if (e.type !== "file" || !e.name.endsWith(".ndjson")) continue;
    const raw = await getFileContent(`data/${today}/${e.name}`);
    if (!raw) continue;
    const line = raw.split("\n").find((s) => s.trim().length > 0);
    if (!line) continue;
    try {
      const o = JSON.parse(line);
      const v = String(o.ver || "unknown");
      const m = String(o.model || "unknown");
      versions[v] = (versions[v] || 0) + 1;
      models[m] = (models[m] || 0) + 1;
    } catch { /* 坏行忽略 */ }
  }

  const out = {
    generatedAt: now.toISOString(),
    ok: true,                     // 本次聚合全程无拉取失败(失败会 exit1,根本写不到这里)
    tz: "device-local",           // App 按设备本地时区写目录名;看板脚注说明
    today,
    dau,
    wau,
    dau30,
    feedback30,                   // 反馈按日期序列(条数,无正文)
    versions,
    models,
    feedbackTotal,                // 近 30 天收到的反馈条数(只计数,绝不含任何反馈正文)
    src: {                        // 看板卡片跳转用(私库地址,不含 token;访客无权限看不到内容)
      data: `${SRC_BASE}/data`,
      feedback: `${SRC_BASE}/feedback`,
    },
    note: "did 随机匿名,卸载重装会变→活跃偏高;仅统计能连通 Gitee 的设备。",
  };

  mkdirSync(dirname(OUT) === "" ? "." : dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(out, null, 2), "utf-8");
  console.log(`已写 ${OUT}: DAU=${dau} WAU=${wau} 反馈=${feedbackTotal} (today=${today})`);
}

// 任何一次拉取彻底失败(重试用尽)都会走到这里 → 非 0 退出让 Actions run 变红。
// 【绝不把失败静默当 0 写进看板】:宁可本次不更新 stats.json,也不展示错误数据。
main().catch((e) => {
  console.error("聚合失败(不更新看板,避免展示错误数据):", e && e.message);
  process.exit(1);
});
