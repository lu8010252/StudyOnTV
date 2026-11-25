(function () {
  'use strict';
  var queue = [], loading = false, failed = false;
  function render(element, requireConnected) {
    if (!element || typeof window.renderMathInElement !== 'function') return;
    if (requireConnected && document.documentElement && !document.documentElement.contains(element)) return;
    try {
      window.renderMathInElement(element, {
        delimiters: [
          { left: '$$', right: '$$', display: true },
          { left: '\\[', right: '\\]', display: true },
          { left: '\\begin{equation}', right: '\\end{equation}', display: true },
          { left: '\\begin{align}', right: '\\end{align}', display: true },
          { left: '\\begin{aligned}', right: '\\end{aligned}', display: true },
          { left: '\\begin{cases}', right: '\\end{cases}', display: true },
          { left: '\\(', right: '\\)', display: false },
          { left: '$', right: '$', display: false }
        ],
        throwOnError: false, trust: false, strict: 'ignore', maxExpand: 200,
        ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code', 'option', 'input']
      });
    } catch (e) {}
  }
  function failLoad() {
    loading = false;
    failed = true;
    queue = [];
  }
  function script(src, done) {
    var element = document.createElement('script');
    element.src = src;
    element.onload = done;
    element.onerror = failLoad;
    document.head.appendChild(element);
  }
  function escapeHtml(text) {
    return String(text == null ? '' : text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }
  function richToHtml(raw) {
    var text = String(raw == null ? '' : raw);
    if (!text) return '';
    var tokens = [];
    // Protect math spans and inline code before formatting so * / _ inside LaTeX or code stay intact.
    var protectedText = text.replace(/\$\$[\s\S]*?\$\$|\\\[[\s\S]*?\\\]|\\\([\s\S]*?\\\)|\\begin\{[a-zA-Z*]+\}[\s\S]*?\\end\{[a-zA-Z*]+\}|\$(?:\\.|[^$\n])+\$|`[^`\n]+`/g, function (match) {
      var idx = tokens.length;
      if (match.charAt(0) === '`') {
        tokens.push('<code class="sotv-code">' + escapeHtml(match.slice(1, -1)) + '</code>');
      } else {
        tokens.push(escapeHtml(match));
      }
      return '\u0000SOTV' + idx + '\u0000';
    });
    var html = escapeHtml(protectedText);
    // Bullet lines: "- item" or "* item" at start of a line
    html = html.replace(/(^|\n)[-*]\s+([^\n]+)/g, '$1<span class="sotv-li">• $2</span>');
    // Rich text inline styles (supports single-character spans and multiple spans per line)
    html = html.replace(/\*\*((?:(?!\*\*)[\s\S])+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/__((?:(?!__)[\s\S])+?)__/g, '<u>$1</u>');
    html = html.replace(/==((?:(?!==)[\s\S])+?)==/g, '<mark class="sotv-mark">$1</mark>');
    html = html.replace(/(^|[^*])\*([^\s\n*]|[^\s\n*][^\n*]*[^\s\n*])\*(?!\*)/g, '$1<em>$2</em>');
    html = html.replace(/\u0000SOTV(\d+)\u0000/g, function (_, idx) {
      return tokens[Number(idx)] || '';
    });
    return html;
  }
  function latexToSpeech(expr) {
    var s = String(expr == null ? '' : expr);
    if (!/\\|\^/.test(s)) return s;
    // 1. 常见运算符、希腊字母与几何符号转为自然中文口语，便于电视语音朗读
    s = s.replace(/\\times\b/g, '乘');
    s = s.replace(/\\div\b/g, '除以');
    s = s.replace(/\\cdot\b/g, '乘');
    s = s.replace(/\\pm\b/g, '正负');
    s = s.replace(/\\mp\b/g, '负正');
    s = s.replace(/\\leq?\b/g, '小于等于');
    s = s.replace(/\\geq?\b/g, '大于等于');
    s = s.replace(/\\neq?\b/g, '不等于');
    s = s.replace(/\\approx\b/g, '约等于');
    s = s.replace(/\\infty\b/g, '无穷大');
    s = s.replace(/\\pi\b/g, '派');
    s = s.replace(/\\alpha\b/g, '阿尔法');
    s = s.replace(/\\beta\b/g, '贝塔');
    s = s.replace(/\\gamma\b/g, '伽马');
    s = s.replace(/\\delta\b/g, '德尔塔');
    s = s.replace(/\\Delta\b/g, '德尔塔');
    s = s.replace(/\\theta\b/g, '西塔');
    s = s.replace(/\\lambda\b/g, '兰姆达');
    s = s.replace(/\\mu\b/g, '缪');
    s = s.replace(/\\omega\b/g, '欧米伽');
    s = s.replace(/\\angle\b/g, '角');
    s = s.replace(/\\triangle\b/g, '三角形');
    s = s.replace(/\\perp\b/g, '垂直于');
    s = s.replace(/\\parallel\b/g, '平行于');
    s = s.replace(/\\(?:left|right|quad|qquad|,|;|!)/g, ' ');
    s = s.replace(/\\begin\{[a-zA-Z*]+\}|\\end\{[a-zA-Z*]+\}/g, ' ');
    s = s.replace(/\\\\/g, '，');
    s = s.replace(/\^\s*\{\\circ\}|\^\\circ|\\circ\b/g, '度');
    // 2. 花括号结构自底向上有界循环展开，支持嵌套分式/根号/幂次
    for (var pass = 0; pass < 6; pass++) {
      var prev = s;
      s = s.replace(/\\(?:text|mathrm|mathbf|mathit|operatorname)\s*\{([^{}]+)\}/g, '$1');
      s = s.replace(/\\sqrt\s*\[([^\]]+)\]\s*\{([^{}]+)\}/g, '$1次根号下$2');
      s = s.replace(/\\sqrt\s*\{([^{}]+)\}/g, '根号$1');
      s = s.replace(/\\(?:d|t)?frac\s*\{([^{}]+)\}\s*\{([^{}]+)\}/g, '$2分之$1');
      s = s.replace(/\^\s*\{2\}/g, '的平方');
      s = s.replace(/\^\s*\{3\}/g, '的立方');
      s = s.replace(/\^\s*\{([^{}]+)\}/g, '的$1次方');
      if (s === prev) break;
    }
    // 3. 无花括号的单字符幂次
    s = s.replace(/\^2(?![0-9])/g, '的平方');
    s = s.replace(/\^3(?![0-9])/g, '的立方');
    s = s.replace(/\^([0-9a-zA-Z])/g, '的$1次方');
    s = s.replace(/[{}]/g, '');
    return s.replace(/\s+/g, ' ').trim();
  }
  function toPlain(raw) {
    var text = String(raw == null ? '' : raw);
    if (!text) return '';
    var tokens = [];
    var protectedText = text.replace(/\$\$([\s\S]*?)\$\$|\\\[([\s\S]*?)\\\]|\\\(([\s\S]*?)\\\)|(\\begin\{[a-zA-Z*]+\}[\s\S]*?\\end\{[a-zA-Z*]+\})|\$((?:\\.|[^$\n])+)\$|`([^`\n]+)`/g, function (_, d1, d2, d3, env, d4, code) {
      var idx = tokens.length;
      if (code !== undefined) {
        tokens.push(code || '');
      } else {
        var inner = d1 !== undefined ? d1 : d2 !== undefined ? d2 : d3 !== undefined ? d3 : env !== undefined ? env : d4;
        tokens.push(latexToSpeech(inner || ''));
      }
      return '\u0000SOTVP' + idx + '\u0000';
    });
    return protectedText
      .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
      .replace(/(^|\n)[-*]\s+/g, '$1')
      .replace(/\*\*((?:(?!\*\*)[\s\S])+?)\*\*/g, '$1')
      .replace(/__((?:(?!__)[\s\S])+?)__/g, '$1')
      .replace(/==((?:(?!==)[\s\S])+?)==/g, '$1')
      .replace(/(^|[^*])\*([^\s\n*]|[^\s\n*][^\n*]*[^\s\n*])\*(?!\*)/g, '$1$2')
      .replace(/\u0000SOTVP(\d+)\u0000/g, function (_, idx) {
        return tokens[Number(idx)] || '';
      })
      .replace(/\$\$?/g, '')
      .replace(/\\\[|\\\]|\\\(|\\\)/g, '')
      .trim();
  }
  window.SOTV_MATH = function (element) {
    if (!element) return;
    if (typeof window.renderMathInElement === 'function') { render(element, false); return; }
    if (failed) return;
    if (!/\$|\\[\[(]|\\begin\{/.test(element.textContent || '')) return;
    if (queue.indexOf(element) < 0) queue.push(element);
    if (loading) return;
    loading = true;
    var katexBase = 'assets/vendor/katex/';
    var style = document.createElement('link');
    style.rel = 'stylesheet';
    style.href = katexBase + 'katex.min.css';
    document.head.appendChild(style);
    script(katexBase + 'katex.min.js', function () {
      if (!window.katex) { failLoad(); return; }
      script(katexBase + 'contrib/auto-render.min.js', function () {
        loading = false;
        if (typeof window.renderMathInElement !== 'function') { failLoad(); return; }
        var pending = queue.slice();
        queue = [];
        pending.forEach(function (el) { render(el, true); });
      });
    });
  };
  window.SOTV_RICH = function (element, rawText) {
    if (!element) return;
    element.innerHTML = richToHtml(rawText);
    window.SOTV_MATH(element);
  };
  window.SOTV_PLAIN = toPlain;
})();
