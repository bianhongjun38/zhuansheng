
// 数学能力审计：证明开源库确实解决了手写版做不到的事
const fs = require('fs');
const vm = require('vm');
const D = 'E:\\小黑盒运营\\cq-zhuanshengbens\\mobile\\assets\\math\\';

let pass = 0, fail = 0;
function ok(name, cond, detail) {
  if (cond) { pass++; console.log('  PASS  ' + name + (detail ? '  [' + detail + ']' : '')); }
  else { fail++; console.log('  FAIL  ' + name + (detail ? '  [' + detail + ']' : '')); }
}

const ctx = {
  console, window: {}, self: {},
  document: { createElement: () => ({ getContext: () => null, style: {} }), addEventListener(){}, documentElement:{style:{}}, querySelectorAll: () => [] },
  navigator: { userAgent: 'node' },
  XMLHttpRequest: function(){ this.open=()=>{}; this.send=()=>{}; },
  setTimeout, clearTimeout, setInterval, clearInterval,
};
ctx.window = ctx; ctx.global = ctx; ctx.self = ctx;
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(D + 'nerdamer.js', 'utf8'), ctx, { filename: 'nerdamer.js' });
const n = ctx.nerdamer;

console.log('=== ① 符号求导（手写版只能查表）===');
const derivs = [
  ['x^3+2*x',        '2+3*x^2'],
  ['sin(x)*x',       'cos(x)*x+sin(x)'],
  ['ln(x)',          'x^(-1)'],
  ['exp(x)*cos(x)',  'exp(x)*cos(x)-exp(x)*sin(x)'],
  ['(x^2+1)^3',      '6*x*(x^2+1)^2'],
  ['tan(x)',         'sec(x)^2'],
  ['1/x',            '-x^(-2)'],
  ['sqrt(x)',        '0.5*x^(-0.5)'],
];
for (const [f, expect] of derivs) {
  try {
    const got = n('diff(' + f + ',x)').toString();
    // 用数值比对而不是字符串比对（化简形式可能不同）
    let same = true;
    for (let i = 1; i <= 5; i++) {
      const x = i * 0.41 + 0.17;
      const a = parseFloat(n(got, {x: String(x)}).evaluate().text('decimals'));
      const b = parseFloat(n(expect, {x: String(x)}).evaluate().text('decimals'));
      if (isFinite(a) && isFinite(b) && Math.abs(a-b) > 1e-6 * Math.max(1, Math.abs(b))) { same = false; break; }
    }
    ok('d/dx ' + f.padEnd(16), same, got);
  } catch (e) { ok('d/dx ' + f, false, e.message.slice(0,40)); }
}

console.log();
console.log('=== ② 手写版做不到的：积分 ===');
try {
  ok('∫x^2 dx', n('integrate(x^2,x)').toString().indexOf('x^3') >= 0, n('integrate(x^2,x)').toString());
  ok('∫sin(x) dx', n('integrate(sin(x),x)').toString().indexOf('cos') >= 0, n('integrate(sin(x),x)').toString());
  ok('∫1/x dx', n('integrate(1/x,x)').toString().indexOf('log') >= 0, n('integrate(1/x,x)').toString());
} catch (e) { ok('积分', false, e.message.slice(0,40)); }

console.log();
console.log('=== ③ 手写版做不到的：解方程 ===');
try {
  const s1 = n.solve('x^2-4','x').toString();
  ok('x²-4=0 得 ±2', s1.indexOf('2') >= 0 && s1.indexOf('-2') >= 0, s1);
  const s2 = n.solve('x^2-5*x+6','x').toString();
  ok('x²-5x+6=0 得 2,3', s2.indexOf('2') >= 0 && s2.indexOf('3') >= 0, s2);
} catch (e) { ok('解方程', false, e.message.slice(0,40)); }

console.log();
console.log('=== ④ 判等价：这是求导练习的关键 ===');
// 手写版比字符串，这些写法会被误判为错
const cases = [
  ['3*x^2', 'x*3*x', true, '换顺序'],
  ['3*x^2', '3x^2', true, '省乘号'],
  ['2*x*sin(x)+x^2*cos(x)', 'x*(2*sin(x)+x*cos(x))', true, '提取公因式'],
  ['cos(x)', 'cos x', true, '带空格'],
  ['3*x^2', '2*x^2', false, '错一个系数'],
  ['3*x^2', '3*x', false, '漏一次方'],
];
for (const [a, b, expect, why] of cases) {
  let eq;
  try {
    const d = n('simplify((' + a + ')-(' + b + '))').toString();
    eq = (d === '0');
    if (!eq) {
      let same = true, tested = 0;
      for (let i = 1; i <= 6; i++) {
        const x = i * 0.37 + 0.11;
        try {
          const va = parseFloat(n(a, {x:String(x)}).evaluate().text('decimals'));
          const vb = parseFloat(n(b, {x:String(x)}).evaluate().text('decimals'));
          if (isFinite(va) && isFinite(vb)) { tested++; if (Math.abs(va-vb) > 1e-6*Math.max(1,Math.abs(vb))) { same = false; break; } }
        } catch(e) {}
      }
      eq = same && tested >= 3;
    }
  } catch (e) { eq = null; }
  ok(('"'+a+'" vs "'+b+'" → '+(expect?'等价':'不等价')).padEnd(46), eq === expect, why);
}

console.log();
console.log('=== ⑤ 值域采样（绘图不炸）===');
try {
  const ys = [];
  for (let i = 0; i <= 50; i++) {
    const x = -6 + 12 * i / 50;
    const v = parseFloat(n('tan(x)', {x:String(x)}).evaluate().text('decimals'));
    if (isFinite(v)) ys.push(v);
  }
  ys.sort((a,b)=>a-b);
  const lo = ys[Math.floor(ys.length*0.02)], hi = ys[Math.floor(ys.length*0.98)];
  ok('tan(x) 分位数剔除极端值后范围有限', isFinite(lo) && isFinite(hi) && (hi-lo) < 1e6, lo.toFixed(1)+' ~ '+hi.toFixed(1));
} catch (e) { ok('值域采样', false, e.message.slice(0,40)); }

console.log();
console.log('=== ⑥ 资源体积 ===');
const files = ['nerdamer.js','function-plot.js','index.html'];
let total = 0;
for (const f of files) {
  try { const s = fs.statSync(D + f).size; total += s; ok(f.padEnd(18) + (s/1024).toFixed(0) + ' KB', s > 0); }
  catch (e) { ok(f, false, '缺失'); }
}
ok('合计 ' + (total/1024/1024).toFixed(2) + ' MB（相对 447MB 的包可忽略）', total < 5*1024*1024);

console.log();
console.log('STAT ' + pass + ' ' + fail);
if (fail > 0) process.exit(1);