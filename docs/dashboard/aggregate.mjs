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
 *   - totalDevices = 全部历史目录里 did 并集(近似"累计设备数",偏高见脚注)。
 *
 * 产出:把聚合结果写成 stats.json,推到【公开看板仓库】的 docs/dashboard/stats.json。
 *   看板(GitHub/Gitee Pages)只读这一个静态文件,不直接碰私有统计库,更不碰令牌。
 *
 * 运行环境:GitHub Actions(定时)。所需密钥从环境变量读,【绝不写进源码/仓库】:
 *   GITEE_TOKEN   —— 采集小号令牌(读私有统计库)。仅本脚本在 CI 内使用。
 *   注:本脚本【只读】统计库;写 stats.json 到公开看板仓库由 workflow 的 git push 完成。
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

if (!TOKEN) {
  console.error("缺少 GITEE_TOKEN 环境变量,无法读取私有统计库。");
  process.exit(1);
}

/** YYYY-MM-DD(本地时区,与 App 上报口径一致)。 */
function dayStr(d) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** 列出某目录下的条目(Gitee Contents API 对目录返回数组)。404/空目录返回 []。 */
async function listDir(path) {
  const url = `${API}/repos/${OWNER}/${REPO}/contents/${encodeURI(path)}?access_token=${TOKEN}`;
  try {
    const r = await fetch(url, { headers: { "User-Agent": "EyeControl-Aggregate" } });
    if (r.status === 404) return [];               // 该天没有任何设备活跃
    if (!r.ok) { console.warn(`列目录 ${path} HTTP ${r.status}`); return []; }
    const j = await r.json();
    return Array.isArray(j) ? j : [];
  } catch (e) {
    console.warn(`列目录 ${path} 失败: ${e.message}`);
    return [];
  }
}

/**
 * 取文件原始内容。走 Contents API 读 base64 `content` 字段。
 * 【为何不用 download_url】私有库的 download_url 是 /raw/ 端点,带不带 token 都 403 读不到;
 * 只有 Contents API 带 access_token 才能读私有库文件。失败返回 null。
 */
async function getFileContent(path) {
  const url = `${API}/repos/${OWNER}/${REPO}/contents/${encodeURI(path)}?access_token=${TOKEN}`;
  try {
    const r = await fetch(url, { headers: { "User-Agent": "EyeControl-Aggregate" } });
    if (!r.ok) return null;
    const j = await r.json();
    if (!j || !j.content) return null;
    return Buffer.from(j.content, "base64").toString("utf-8");
  } catch {
    return null;
  }
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
  const wauSet = new Set();
  let todayEntries = [];
  // 反馈计数:近 30 天 feedback/<day>/ 下的 .json 文件数之和。
  // 【红线】只 listDir 数条数,绝不 getFileContent 读反馈正文——聚合脚本永不触碰任何反馈内容。
  let feedbackTotal = 0;

  for (let i = 29; i >= 0; i--) {
    const d = new Date(now.getTime() - i * 86400000);
    const day = dayStr(d);
    const entries = await listDir(`data/${day}`);
    const dids = didsOf(entries);
    dau30.push({ day, count: dids.size });
    if (i <= 6) for (const x of dids) wauSet.add(x);   // 近 7 天并集 = WAU
    if (day === today) todayEntries = entries;

    // 只数反馈条数(文件名以 .json 结尾),不读内容。
    const fbEntries = await listDir(`feedback/${day}`);
    feedbackTotal += fbEntries.filter(
      (e) => e.type === "file" && e.name.endsWith(".json")
    ).length;
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
    tz: "device-local",           // App 按设备本地时区写目录名;看板脚注说明
    today,
    dau,
    wau,
    dau30,
    versions,
    models,
    feedbackTotal,                // 近 30 天收到的反馈条数(只计数,绝不含任何反馈正文)
    note: "did 随机匿名,卸载重装会变→活跃偏高;仅统计能连通 Gitee 的设备。",
  };

  mkdirSync(dirname(OUT) === "" ? "." : dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(out, null, 2), "utf-8");
  console.log(`已写 ${OUT}: DAU=${dau} WAU=${wau} 反馈=${feedbackTotal} (today=${today})`);
}

main().catch((e) => { console.error(e); process.exit(1); });
