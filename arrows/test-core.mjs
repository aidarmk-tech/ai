/**
 * Автотест ядра игры «Стрелки».
 * Вытаскивает блок между /*--CORE-START--* / и /*--CORE-END--* / из index.html
 * и проверяет главное обещание генератора: каждый сгенерированный уровень
 * действительно решается своим порядком уборки, с учётом замков, бомб и связок.
 *
 *   node arrows/test-core.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const html = readFileSync(join(here, 'index.html'), 'utf8');
const A = html.indexOf('/*--CORE-START--*/');
const B = html.indexOf('/*--CORE-END--*/');
if (A < 0 || B < 0) { console.error('Не найден блок ядра в index.html'); process.exit(1); }
const core = html.slice(A, B);

const CORE = new Function(core + `
  return { generateLevel, campaignCfg, dailyCfg, endlessCfg, levelMetrics,
           sweepArrow, buildOcc, T_LOCK, T_CHAIN, T_BOMB, T_ICE, T_DOUBLE };
`)();

const { generateLevel, campaignCfg, dailyCfg, endlessCfg, sweepArrow, buildOcc,
  T_LOCK, T_CHAIN, T_BOMB } = CORE;

/** Проигрываем уровень его собственным решением и ловим любое нарушение правил. */
function solve(level) {
  const alive = new Set(level.arrows.map(a => a.id));
  const occ = buildOcc(level, alive);
  const fuse = new Map();
  for (const a of level.arrows) if (a.type === T_BOMB) fuse.set(a.id, a.fuse);
  let moves = 0;
  const total = level.arrows.length;

  const drop = a => {
    for (const c of a.cells) occ[c[1] * level.cols + c[0]] = -1;
    alive.delete(a.id);
  };

  for (const id of level.solution) {
    if (!alive.has(id)) continue;                 // уже ушла как партнёр по связке
    const a = level.arrows[id];
    const removed = total - alive.size;
    if (a.type === T_LOCK && removed < a.need) {
      return `замок #${id}: нужно ${a.need}, убрано ${removed}`;
    }
    const r = sweepArrow(level, occ, a, a.dir);
    if (!r.ok) return `стрелка #${id} упирается на ходу ${moves} (hit=${r.hit})`;

    const group = [a];
    if (a.type === T_CHAIN && alive.has(a.link)) {
      const p = level.arrows[a.link];
      const r2 = sweepArrow(level, occ, p, p.dir);
      if (!r2.ok) return `связка #${id}+#${p.id}: партнёру перекрыт путь на ходу ${moves}`;
      group.push(p);
    }
    for (const g of group) drop(g);
    moves++;

    for (const [bid, f] of fuse) {
      if (!alive.has(bid)) { fuse.delete(bid); continue; }
      const left = f - 1;
      if (left <= 0) return `бомба #${bid} рванула на ходу ${moves}`;
      fuse.set(bid, left);
    }
  }
  if (alive.size) return `осталось ${alive.size} стрелок`;
  return null;
}

let fails = 0, checked = 0;
const t0 = Date.now();
const stat = { arrows: [], safe: [], ms: [] };

function check(name, seed, cfg) {
  const s = Date.now();
  const lv = generateLevel(seed, cfg);
  const gen = Date.now() - s;
  checked++;
  stat.arrows.push(lv.arrows.length);
  stat.safe.push(lv.metrics ? lv.metrics.avgSafe : 0);
  stat.ms.push(gen);
  if (lv.arrows.length < 3) { console.error(`✗ ${name}: всего ${lv.arrows.length} стрелок`); fails++; return; }
  const err = solve(lv);
  if (err) { console.error(`✗ ${name} (seed=${seed}): ${err}`); fails++; }
}

console.log('Кампания, 100 уровней…');
for (let i = 1; i <= 100; i++) check('уровень ' + i, 'lv:' + i, campaignCfg(i));

console.log('Вызов дня, 60 дат…');
for (let i = 0; i < 60; i++) {
  const d = new Date(2026, 0, 1 + i * 6);
  const key = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  check('день ' + key, 'daily:' + key, dailyCfg());
}

console.log('Бесконечный, 5 забегов × 30 уровней…');
for (let run = 0; run < 5; run++) {
  for (let d = 1; d <= 30; d++) check(`забег ${run} глубина ${d}`, `endless:${run}:${d}`, endlessCfg(d));
}

const avg = a => a.reduce((x, y) => x + y, 0) / a.length;
console.log('---');
console.log(`уровней проверено: ${checked}`);
console.log(`стрелок в среднем: ${avg(stat.arrows).toFixed(1)} (макс ${Math.max(...stat.arrows)})`);
console.log(`средняя доля безопасных ходов: ${avg(stat.safe).toFixed(3)}`);
console.log(`генерация: в среднем ${avg(stat.ms).toFixed(0)} мс, худшая ${Math.max(...stat.ms)} мс`);
console.log(`всего за ${((Date.now() - t0) / 1000).toFixed(1)} с`);
console.log(fails ? `✗ ПРОВАЛЕНО: ${fails}` : '✓ Все уровни решаются своим порядком уборки');
process.exit(fails ? 1 : 0);
