import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const elements: any[] = [];
const mockWindow: any = {
  renderMathInElement: (el: any) => {
    elements.push(el);
  },
};
const mockDoc: any = {
  documentElement: { contains: () => true },
  head: { appendChild: () => {} },
  createElement: () => ({}),
};

const code = readFileSync(new URL('../../shared/math.js', import.meta.url), 'utf8');
vm.runInNewContext(code, { window: mockWindow, document: mockDoc });

// 1. XSS escaping + rich text formatting
const el = { innerHTML: '', textContent: '' };
mockWindow.SOTV_RICH(el, '<script>alert(1)</script> **加粗** *斜体* __下划线__ ==重点== `a<b`');
assert.ok(!el.innerHTML.includes('<script>'), 'script tag must be escaped');
assert.ok(el.innerHTML.includes('&lt;script&gt;alert(1)&lt;/script&gt;'));
assert.ok(el.innerHTML.includes('<strong>加粗</strong>'));
assert.ok(el.innerHTML.includes('<em>斜体</em>'));
assert.ok(el.innerHTML.includes('<u>下划线</u>'));
assert.ok(el.innerHTML.includes('<mark class="sotv-mark">重点</mark>'));
assert.ok(el.innerHTML.includes('<code class="sotv-code">a&lt;b</code>'));

// 2. Math spans with * or _ must NOT be mangled by bold/italic/underline
const mathEl = { innerHTML: '', textContent: '' };
mockWindow.SOTV_RICH(mathEl, '计算 $a * b + x_1 + y_2$ 的值，注意 **关键**');
assert.ok(mathEl.innerHTML.includes('$a * b + x_1 + y_2$'), 'math span must stay intact');
assert.ok(mathEl.innerHTML.includes('<strong>关键</strong>'));

// 3. Bullet list lines
const listEl = { innerHTML: '', textContent: '' };
mockWindow.SOTV_RICH(listEl, '步骤：\n- 第一步\n- 第二步');
assert.ok(listEl.innerHTML.includes('<span class="sotv-li">• 第一步</span>'));
assert.ok(listEl.innerHTML.includes('<span class="sotv-li">• 第二步</span>'));

// 4. Plain text stripping & LaTeX-to-speech conversion for TTS
assert.equal(
  mockWindow.SOTV_PLAIN('**重点**：求 $x^2$ 的==最小值==\n- 提示 `ok`'),
  '重点：求 x的平方 的最小值\n提示 ok',
);
assert.equal(
  mockWindow.SOTV_PLAIN('计算 $\\frac{1}{2} \\times \\sqrt{4} + 90^\\circ$'),
  '计算 2分之1 乘 根号4 + 90度',
);
const envEl = { innerHTML: '', textContent: '' };
mockWindow.SOTV_RICH(envEl, '解方程组：\\begin{cases}x+y=5\\\\x-y=1\\end{cases}');
assert.ok(envEl.innerHTML.includes('\\begin{cases}x+y=5\\\\x-y=1\\end{cases}'), 'LaTeX environment must stay intact');

// 5. Single-character spans & multiple spans on one line without swallowing text between them
const singleCharEl = { innerHTML: '', textContent: '' };
mockWindow.SOTV_RICH(singleCharEl, '**甲** 和 **乙**，__A__ or __B__，==1== + ==2==，*a* and *b*');
assert.equal(
  singleCharEl.innerHTML,
  '<strong>甲</strong> 和 <strong>乙</strong>，<u>A</u> or <u>B</u>，<mark class="sotv-mark">1</mark> + <mark class="sotv-mark">2</mark>，<em>a</em> and <em>b</em>',
);
assert.equal(
  mockWindow.SOTV_PLAIN('**甲** 和 **乙**，__A__ or __B__，==1== + ==2==，*a* and *b*'),
  '甲 和 乙，A or B，1 + 2，a and b',
);

// 6. Plain arithmetic multiplication 3 * 4 * 5 must NOT be turned into <em>
const multEl = { innerHTML: '', textContent: '' };
mockWindow.SOTV_RICH(multEl, '计算 3 * 4 * 5 = 60');
assert.equal(multEl.innerHTML, '计算 3 * 4 * 5 = 60');
assert.ok(!multEl.innerHTML.includes('<em>'), 'plain multiplication must not become italic');
assert.equal(mockWindow.SOTV_PLAIN('计算 3 * 4 * 5 = 60'), '计算 3 * 4 * 5 = 60');

// 7. KaTeX renderMathInElement exception resilience (must not throw)
const throwingWindow: any = {
  renderMathInElement: () => {
    throw new Error('Legacy WebView KaTeX failure');
  },
};
vm.runInNewContext(code, { window: throwingWindow, document: mockDoc });
const safeEl = { innerHTML: '', textContent: '$x^2$' };
assert.doesNotThrow(() => {
  throwingWindow.SOTV_RICH(safeEl, '求 **解** $x^2$');
});
assert.ok(safeEl.innerHTML.includes('<strong>解</strong>'));

// 8. KaTeX dynamic loader failure sets failed flag and does not infinitely append tags
const appendedTags: any[] = [];
const lazyWindow: any = {};
const lazyDoc: any = {
  documentElement: { contains: () => true },
  head: {
    appendChild: (node: any) => {
      appendedTags.push(node);
      if (node.tagName === 'script' && typeof node.onload === 'function') {
        // Simulate 200 HTML fallback where window.katex is still undefined
        node.onload();
      }
    },
  },
  createElement: (tag: string) => ({ tagName: tag }),
};
vm.runInNewContext(code, { window: lazyWindow, document: lazyDoc });
lazyWindow.SOTV_MATH({ textContent: '$a+b$' });
const countAfterFirstFail = appendedTags.length;
assert.equal(countAfterFirstFail, 2, 'should append 1 link + 1 katex.min.js script and stop before auto-render');
lazyWindow.SOTV_MATH({ textContent: '$c+d$' });
assert.equal(appendedTags.length, countAfterFirstFail, 'must not keep appending tags after load failure');

console.log('rich-text.test.ts passed');
