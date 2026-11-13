/* 积分同步面板：悬浮按钮 + 弹层，独立于家长后台主界面。
 * 登录用同一个管理密码（POST /api/auth），令牌只存在 sessionStorage。 */
(function () {
  'use strict';
  var TK = 'stv_points_token';
  var panel = null, data = null, children = [];

  function tok() { try { return sessionStorage.getItem(TK) || ''; } catch (e) { return ''; } }
  function setTok(v) { try { if (v) sessionStorage.setItem(TK, v); else sessionStorage.removeItem(TK); } catch (e) {} }

  function api(method, path, body) {
    var headers = { 'Content-Type': 'application/json' };
    if (tok()) headers.Authorization = 'Bearer ' + tok();
    return fetch(path, { method: method, headers: headers, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store' })
      .then(function (r) {
        if (r.status === 401) { setTok(''); throw new Error('unauthorized'); }
        return r.json();
      })
      .then(function (j) { if (j && j.error && j.message) throw new Error(j.message); return j; });
  }

  function el(tag, attrs, kids) {
    var n = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') n.textContent = attrs[k];
      else if (k === 'style') n.style.cssText = attrs[k];
      else if (k.slice(0, 2) === 'on') n.addEventListener(k.slice(2), attrs[k]);
      else n.setAttribute(k, attrs[k]);
    });
    (kids || []).forEach(function (c) { if (c) n.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return n;
  }

  var BOX = 'background:#fff;color:#222;border-radius:14px;padding:16px;max-width:520px;width:calc(100% - 24px);max-height:88vh;overflow:auto;box-shadow:0 8px 40px rgba(0,0,0,.25);font:14px/1.5 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif';
  var INP = 'width:100%;box-sizing:border-box;padding:9px 10px;border:1px solid #d5d9d2;border-radius:8px;font-size:14px;margin:4px 0 10px';
  var BTN = 'padding:9px 14px;border:0;border-radius:8px;background:#5b8c5a;color:#fff;font-size:14px;margin-right:8px;cursor:pointer';
  var BTN2 = 'padding:9px 14px;border:1px solid #c9cec6;border-radius:8px;background:#fff;color:#333;font-size:14px;margin-right:8px;cursor:pointer';

  function fmt(ts) {
    if (!ts) return '从未';
    var d = new Date(ts), p = function (n) { return n < 10 ? '0' + n : '' + n; };
    return (d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  function close() { if (panel) { panel.remove(); panel = null; } }

  function open() {
    close();
    panel = el('div', { style: 'position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:99999;display:flex;align-items:center;justify-content:center', onclick: function (e) { if (e.target === panel) close(); } });
    document.body.appendChild(panel);
    if (!tok()) return loginView();
    load();
  }

  function frame(title, body) {
    panel.innerHTML = '';
    var box = el('div', { style: BOX }, [
      el('div', { style: 'display:flex;align-items:center;justify-content:space-between;margin-bottom:10px' }, [
        el('b', { text: title, style: 'font-size:16px' }),
        el('button', { text: '✕', style: 'border:0;background:none;font-size:18px;cursor:pointer', onclick: close })
      ])
    ].concat(body));
    panel.appendChild(box);
    return box;
  }

  function loginView(msg) {
    var pw = el('input', { type: 'password', placeholder: '管理密码', style: INP });
    var tip = el('div', { text: msg || '', style: 'color:#c0392b;min-height:18px' });
    var go = function () {
      api('POST', '/api/auth', { passphrase: pw.value }).then(function (j) {
        if (!j.token) throw new Error(j.error || '登录失败');
        setTok(j.token); load();
      }).catch(function (e) { tip.textContent = e.message === 'unauthorized' ? '管理密码错误' : e.message; });
    };
    pw.addEventListener('keydown', function (e) { if (e.key === 'Enter') go(); });
    frame('积分同步 · 验证管理密码', [pw, tip, el('button', { text: '进入', style: BTN, onclick: go })]);
    pw.focus();
  }

  function load() {
    api('GET', '/api/points').then(function (j) { data = j; view(); })
      .catch(function (e) { if (e.message === 'unauthorized') loginView('登录已失效，请重新输入'); else frame('积分同步', [el('p', { text: e.message })]); });
  }

  function stat(label, value) {
    return el('div', { style: 'flex:1;min-width:90px;background:#f4f7f1;border-radius:10px;padding:8px 10px;text-align:center' }, [
      el('div', { text: value, style: 'font-size:20px;font-weight:700' }), el('div', { text: label, style: 'font-size:12px;color:#6d776f' })
    ]);
  }

  function view() {
    var d = data, msg = el('div', { style: 'min-height:20px;margin:6px 0;color:#c0392b' });
    var enabled = el('input', { type: 'checkbox' }); enabled.checked = !!d.enabled;
    var base = el('input', { type: 'text', placeholder: '例如 https://star.8010252.xyz', style: INP, value: d.baseUrl || '' });
    var pw = el('input', { type: 'password', placeholder: d.loggedIn ? '已登录；仅在星星屋换了密码时才需要重填' : '星星屋家庭密码', style: INP });
    var kid = el('select', { style: INP });
    var fillKids = function (list) {
      kid.innerHTML = '';
      if (!list.length && d.childId) list = [{ id: d.childId, name: d.childName || ('孩子 ' + d.childId), points: d.balance }];
      list.forEach(function (c) {
        var o = el('option', { value: String(c.id), text: c.name + (c.points != null && c.points >= 0 ? '（' + c.points + ' 分）' : '') });
        if (c.id === d.childId) o.selected = true;
        kid.appendChild(o);
      });
      if (!list.length) kid.appendChild(el('option', { value: '0', text: '先点「获取孩子列表」' }));
    };
    fillKids(children);
    var perStar = el('input', { type: 'number', min: '0', style: INP, value: String(d.perStar) });
    var perQ = el('input', { type: 'number', min: '0', style: INP, value: String(d.perQuestion != null ? d.perQuestion : 1) });
    var mode = el('select', { style: INP }, [
      el('option', { value: 'flat', text: '每题固定加分（答对一题就加，不看星级）' }),
      el('option', { value: 'star', text: '按星级加分（星级 × 每星积分）' })
    ]);
    mode.value = d.scoreMode === 'star' ? 'star' : 'flat';
    var perQBox = el('div', {}, [el('div', { text: '每答对一题加几分' }), perQ]);
    var perStarBox = el('div', {}, [el('div', { text: '每颗星对应积分（题目 1–5 星，答对一题得 星级 × 此值）' }), perStar]);
    var syncMode = function () {
      perQBox.style.display = mode.value === 'flat' ? '' : 'none';
      perStarBox.style.display = mode.value === 'star' ? '' : 'none';
    };
    mode.addEventListener('change', syncMode);
    syncMode();
    var bonus = el('input', { type: 'number', min: '0', style: INP, value: String(d.passBonus) });
    var cap = el('input', { type: 'number', min: '0', style: INP, value: String(d.dailyCap) });

    var getKids = function () {
      msg.style.color = '#6d776f'; msg.textContent = '正在连接星星屋…';
      api('POST', '/api/points/children', { baseUrl: base.value, password: pw.value }).then(function (j) {
        children = j.items || []; msg.textContent = '找到 ' + children.length + ' 个孩子，请选择要绑定的那一个'; fillKids(children);
      }).catch(function (e) { msg.style.color = '#c0392b'; msg.textContent = e.message; });
    };
    var saveBtn = function () {
      var body = { enabled: enabled.checked, baseUrl: base.value, scoreMode: mode.value, perQuestion: +perQ.value, perStar: +perStar.value, passBonus: +bonus.value, dailyCap: +cap.value };
      if (pw.value) body.password = pw.value;
      var sel = +kid.value;
      if (sel > 0) { body.childId = sel; var c = children.filter(function (x) { return x.id === sel; })[0]; if (c) body.childName = c.name; }
      msg.style.color = '#6d776f'; msg.textContent = '保存中…';
      api('PUT', '/api/points/settings', body).then(function (j) { data = j; pw.value = ''; view(); toast('已保存'); })
        .catch(function (e) { msg.style.color = '#c0392b'; msg.textContent = e.message; });
    };
    var syncBtn = function () {
      msg.style.color = '#6d776f'; msg.textContent = '同步中…';
      api('POST', '/api/points/sync').then(function (j) { data = j; view(); if (j.lastError) toast(j.lastError, true); else toast('同步完成'); })
        .catch(function (e) { msg.style.color = '#c0392b'; msg.textContent = e.message; });
    };

    var rows = (d.recent || []).map(function (r) {
      return el('tr', {}, [
        el('td', { text: fmt(r.ts), style: 'padding:3px 6px;color:#6d776f;white-space:nowrap' }),
        el('td', { text: r.reason, style: 'padding:3px 6px' }),
        el('td', { text: '+' + r.delta, style: 'padding:3px 6px;color:#2e7d32;font-weight:600' }),
        el('td', { text: r.synced ? '已同步' : '待同步', style: 'padding:3px 6px;color:' + (r.synced ? '#6d776f' : '#c77700') })
      ]);
    });

    frame('星星屋积分同步', [
      el('div', { style: 'display:flex;gap:8px;flex-wrap:wrap;margin-bottom:8px' }, [
        stat((d.childName || '星星屋') + ' 总积分', d.balance >= 0 ? '⭐ ' + d.balance : '未获取'),
        stat('今天答题得分', String(d.today)),
        stat('待同步', String(d.pending))
      ]),
      el('div', { text: '上次同步：' + fmt(d.lastSync) + (d.lastError ? '　失败：' + d.lastError : ''), style: 'font-size:12px;color:' + (d.lastError ? '#c0392b' : '#6d776f') + ';margin-bottom:10px' }),
      el('label', { style: 'display:block;margin-bottom:8px' }, [enabled, ' 开启积分同步（答对题自动给星星屋加分）']),
      el('div', { text: '星星屋地址' }), base,
      el('div', { text: '星星屋家庭密码（只用来换取令牌，不会保存密码）' }), pw,
      el('div', { text: '绑定的孩子' }), kid,
      el('button', { text: '获取孩子列表', style: BTN2, onclick: getKids }),
      el('div', { style: 'height:10px' }),
      el('div', { text: '计分方式' }), mode,
      perQBox, perStarBox,
      el('div', { text: '通过一组任务的额外奖励积分' }), bonus,
      el('div', { text: '每天最多通过答题得多少分（0 表示不限）' }), cap,
      msg,
      el('div', {}, [el('button', { text: '保存', style: BTN, onclick: saveBtn }), el('button', { text: '立即同步', style: BTN2, onclick: syncBtn })]),
      el('div', { text: '最近得分', style: 'margin:14px 0 4px;font-weight:600' }),
      rows.length ? el('table', { style: 'width:100%;border-collapse:collapse;font-size:13px' }, rows) : el('div', { text: '还没有记录', style: 'color:#6d776f' }),
      el('div', { text: '版本 v' + (d.version || ''), style: 'margin-top:12px;font-size:12px;color:#9aa39c' })
    ]);
  }

  function toast(text, bad) {
    var t = el('div', { text: text, style: 'position:fixed;left:50%;bottom:90px;transform:translateX(-50%);background:' + (bad ? '#c0392b' : '#333') + ';color:#fff;padding:9px 16px;border-radius:20px;z-index:100000;font-size:14px' });
    document.body.appendChild(t); setTimeout(function () { t.remove(); }, 2200);
  }

  function mount() {
    if (document.getElementById('stv-points-fab')) return;
    var fab = el('button', { id: 'stv-points-fab', text: '⭐ 积分', title: '星星屋积分同步',
      style: 'position:fixed;right:14px;bottom:64px;z-index:9999;padding:10px 16px;border:0;border-radius:24px;background:#5b8c5a;color:#fff;font-size:14px;box-shadow:0 3px 12px rgba(0,0,0,.25);cursor:pointer', onclick: open });
    document.body.appendChild(fab);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
})();
