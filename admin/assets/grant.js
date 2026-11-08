/* 一键授权面板：右下角「🔐 授权」按钮。应用通过本机 ADB 给自己开 使用情况/无障碍/后台运行 权限。 */
(function () {
  'use strict';
  var TK = 'stv_points_token';
  var panel = null, data = null, timer = null;
  function tok() { try { return sessionStorage.getItem(TK) || ''; } catch (e) { return ''; } }
  function setTok(v) { try { if (v) sessionStorage.setItem(TK, v); else sessionStorage.removeItem(TK); } catch (e) {} }
  function api(method, path, body) {
    var h = { 'Content-Type': 'application/json' };
    if (tok()) h.Authorization = 'Bearer ' + tok();
    return fetch(path, { method: method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store' })
      .then(function (r) { if (r.status === 401) { setTok(''); throw new Error('unauthorized'); } return r.json(); });
  }
  function el(tag, a, kids) {
    var n = document.createElement(tag);
    Object.keys(a || {}).forEach(function (k) {
      if (k === 'text') n.textContent = a[k]; else if (k === 'style') n.style.cssText = a[k];
      else if (k.slice(0, 2) === 'on') n.addEventListener(k.slice(2), a[k]); else n.setAttribute(k, a[k]);
    });
    (kids || []).forEach(function (c) { if (c) n.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return n;
  }
  var BOX = 'background:#fff;color:#222;border-radius:14px;padding:16px;max-width:520px;width:calc(100% - 24px);max-height:90vh;overflow:auto;box-shadow:0 8px 40px rgba(0,0,0,.25);font:14px/1.5 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif';
  var INP = 'width:100%;box-sizing:border-box;padding:9px 10px;border:1px solid #d5d9d2;border-radius:8px;font-size:14px;margin:4px 0 10px';
  var BTN = 'padding:10px 16px;border:0;border-radius:8px;background:#c0713a;color:#fff;font-size:15px;margin:0 8px 6px 0;cursor:pointer';
  function stopPoll() { if (timer) { clearInterval(timer); timer = null; } }
  function close() { stopPoll(); if (panel) { panel.remove(); panel = null; } }
  function open() {
    close();
    panel = el('div', { style: 'position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:99997;display:flex;align-items:center;justify-content:center', onclick: function (e) { if (e.target === panel) close(); } });
    document.body.appendChild(panel);
    if (!tok()) return login();
    load();
  }
  function frame(title, kids) {
    panel.innerHTML = '';
    panel.appendChild(el('div', { style: BOX }, [
      el('div', { style: 'display:flex;align-items:center;justify-content:space-between;margin-bottom:10px' }, [
        el('b', { text: title, style: 'font-size:16px' }),
        el('button', { text: '✕', style: 'border:0;background:none;font-size:18px;cursor:pointer', onclick: close })])
    ].concat(kids)));
  }
  function login(msg) {
    var pw = el('input', { type: 'password', placeholder: '管理密码', style: INP });
    var tip = el('div', { text: msg || '', style: 'color:#c0392b;min-height:18px' });
    var go = function () {
      api('POST', '/api/auth', { passphrase: pw.value }).then(function (j) { if (!j.token) throw new Error('登录失败'); setTok(j.token); load(); })
        .catch(function (e) { tip.textContent = e.message === 'unauthorized' ? '管理密码错误' : e.message; });
    };
    pw.addEventListener('keydown', function (e) { if (e.key === 'Enter') go(); });
    frame('一键授权 · 验证管理密码', [pw, tip, el('button', { text: '进入', style: BTN, onclick: go })]);
    pw.focus();
  }
  function load() {
    api('GET', '/api/grant').then(function (j) {
      data = j;
      if (!timer) timer = setInterval(load, 3000);   // 面板开着就每 3 秒刷新，方便在手机上实时看电视的计时状态
      view();
    }).catch(function (e) { stopPoll(); if (e.message === 'unauthorized') login('登录已失效，请重新输入'); else frame('一键授权', [el('p', { text: e.message })]); });
  }
  function row(label, ok, hint) {
    return el('div', { style: 'display:flex;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid #f0f0f0' }, [
      el('span', { text: ok ? '✅' : '⬜', style: 'font-size:18px' }),
      el('div', {}, [el('div', { text: label, style: 'font-weight:600' }), el('div', { text: hint, style: 'font-size:12px;color:#6b7280' })])]);
  }
  function view() {
    var d = data, last = d.last;
    var run = function () {
      api('POST', '/api/grant/run').then(function (j) { data = j; load(); });
    };
    var kids = [
      el('div', { text: '当前计时方式：' + (d.monitor || '未知'), style: 'margin-bottom:6px;color:#374151' }),
      row('悬浮窗', d.overlay, '显示学习锁屏页'),
      row('使用情况访问', d.usage, '识别现在开的是哪个 App，计时才准'),
      row('无障碍服务', d.accessibility, '及时发现 App 切换；开机自动启动；锁屏更难绕开'),
      row('后台运行', d.background, '加入省电白名单，避免被系统杀掉')
    ];
    if (d.running) kids.push(el('div', { text: '⏳ 正在授权…第一次使用时，请看电视屏幕：弹出“允许 USB 调试吗？”时，勾选“始终允许”，再点确定。', style: 'margin:12px 0;padding:10px;background:#fff6e5;border-radius:8px' }));
    if (last) {
      kids.push(el('div', { text: last.message || '', style: 'margin:12px 0 4px;font-weight:600;color:' + (last.ok ? '#2e7d32' : '#c0392b') }));
      (last.steps || []).forEach(function (s) {
        kids.push(el('div', { text: (s.ok ? '✓ ' : '✗ ') + s.step + (s.ok || !s.out ? '' : '：' + s.out), style: 'font-size:12px;color:' + (s.ok ? '#6b7280' : '#c0392b') }));
      });
    }
    var rem = d.remainingSeconds || 0;
    kids.push(el('div', { text: '计时诊断（电视在别的 App 里时，这里能实时看到）', style: 'margin:14px 0 6px;font-weight:600' }));
    kids.push(el('div', { text: '剩余 ' + Math.floor(rem / 60) + ' 分 ' + (rem % 60) + ' 秒　·　已用 ' + (d.usedMinutes || 0).toFixed(1) + ' 分钟' + (d.reason ? '　·　锁定原因：' + d.reason : ''), style: 'font-size:13px' }));
    kids.push(el('div', { text: '现在：' + (d.monitor || '') + ' · ' + (d.diag || '暂无'), style: 'font-size:13px;color:#0b6bcb;margin:2px 0 6px;word-break:break-all' }));
    var hist = el('div', { style: 'max-height:180px;overflow:auto;font-size:12px;color:#4b5563;border:1px solid #eee;border-radius:8px;padding:6px 8px' });
    (d.history || []).forEach(function (h) {
      var t = new Date(h.ts), p2 = function (n) { return n < 10 ? '0' + n : '' + n; };
      hist.appendChild(el('div', { text: p2(t.getHours()) + ':' + p2(t.getMinutes()) + ':' + p2(t.getSeconds()) + '  ' + h.text, style: 'word-break:break-all' }));
    });
    if (!(d.history || []).length) hist.appendChild(el('div', { text: '还没有记录' }));
    kids.push(hist);
    kids.push(el('div', { style: 'margin-top:14px' }, [
      el('button', { text: d.running ? '授权中…' : '一键授权', style: BTN + (d.running ? ';opacity:.6' : ''), onclick: d.running ? function () {} : run }),
      el('button', { text: '刷新状态', style: BTN + ';background:#fff;color:#333;border:1px solid #c9cec6', onclick: load })]));
    kids.push(el('div', { text: '原理：应用通过本机的 ADB（电视里的 5555 端口）给自己授权，等于替你在电脑上敲命令，所以电视的“ADB 调试”需要保持开启。电视重启后如果 ADB 被关掉，要重新开启后再点一次。', style: 'margin-top:10px;font-size:12px;color:#9aa0a6' }));
    frame('一键授权', kids);
  }
  function mount() {
    if (document.getElementById('stv-grant-fab')) return;
    document.body.appendChild(el('button', { id: 'stv-grant-fab', text: '🔐 授权', title: '一键授权',
      style: 'position:fixed;right:14px;bottom:112px;z-index:9999;padding:10px 16px;border:0;border-radius:24px;background:#c0713a;color:#fff;font-size:14px;box-shadow:0 3px 12px rgba(0,0,0,.25);cursor:pointer', onclick: open }));
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
})();
