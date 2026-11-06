/* 管控与题库面板：右下角「🎛 管控」按钮 + 弹层。管理令牌与积分、AI 出题面板共用（sessionStorage）。
 * ① 暂停管控（大人要看电视）② 抽题最低星级 ③ 答对多次自动淘汰。 */
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
      .then(function (r) { if (r.status === 401) { setTok(''); throw new Error('unauthorized'); } return r.json(); })
      .then(function (j) { if (j && j.error && j.message) throw new Error(j.message); return j; });
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
  var INP = 'width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid #d5d9d2;border-radius:8px;font-size:14px;margin:3px 0 9px';
  var BTN = 'padding:9px 14px;border:0;border-radius:8px;background:#b5703a;color:#fff;font-size:14px;margin:0 8px 8px 0;cursor:pointer';
  var BTN2 = 'padding:9px 14px;border:1px solid #c9cec6;border-radius:8px;background:#fff;color:#333;font-size:14px;margin:0 8px 8px 0;cursor:pointer';
  var SEC = 'border:1px solid #eadfd3;border-radius:10px;padding:10px 12px;margin-bottom:12px';

  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function fmt(ts) { var d = new Date(ts); return (d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()); }
  function toast(t, bad) {
    var n = el('div', { text: t, style: 'position:fixed;left:50%;bottom:90px;transform:translateX(-50%);background:' + (bad ? '#c0392b' : '#333') + ';color:#fff;padding:9px 16px;border-radius:20px;z-index:100001;font-size:14px;max-width:86vw' });
    document.body.appendChild(n); setTimeout(function () { n.remove(); }, 2400);
  }
  function stopTick() { if (timer) { clearInterval(timer); timer = null; } }
  function close() { stopTick(); if (panel) { panel.remove(); panel = null; } }

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
    frame('管控与题库 · 验证管理密码', [pw, tip, el('button', { text: '进入', style: BTN, onclick: go })]);
    pw.focus();
  }
  function load() {
    api('GET', '/api/tune').then(function (j) { data = j; view(); })
      .catch(function (e) { if (e.message === 'unauthorized') login('登录已失效，请重新输入'); else frame('管控与题库', [el('p', { text: e.message })]); });
  }

  function pauseText(p) {
    if (!p || !p.active) return '当前：管控中（正常拦截、计时）';
    if (p.forever) return '当前：已暂停，直到你手动恢复';
    var left = Math.max(1, Math.ceil((p.until - Date.now()) / 60000));
    return '当前：已暂停到 ' + fmt(p.until) + '（还剩约 ' + left + ' 分钟），到点自动恢复';
  }

  function view() {
    var d = data, msg = el('div', { style: 'min-height:18px;margin:4px 0;color:#c0392b' });
    var p = d.pause || {};

    var setPause = function (body, okText) {
      api('PUT', '/api/pause', body).then(function (j) { data.pause = j; toast(okText); view(); })
        .catch(function (e) { msg.textContent = e.message; });
    };
    var pauseState = el('div', { text: pauseText(p), style: 'font-weight:600;margin-bottom:8px;color:' + (p.active ? '#c77700' : '#2e7d32') });
    var pauseBtns = el('div', {}, [
      el('button', { text: '暂停 1 小时', style: BTN, onclick: function () { setPause({ minutes: 60 }, '已暂停 1 小时'); } }),
      el('button', { text: '暂停 2 小时', style: BTN, onclick: function () { setPause({ minutes: 120 }, '已暂停 2 小时'); } }),
      el('button', { text: '暂停到今天 24 点', style: BTN, onclick: function () { setPause({ today: true }, '已暂停到今天 24 点'); } }),
      el('button', { text: '一直暂停', style: BTN2, onclick: function () { setPause({ forever: true }, '已暂停，记得之后恢复'); } }),
      p.active ? el('button', { text: '恢复管控', style: BTN, onclick: function () { setPause({ off: true }, '已恢复管控'); } }) : null
    ]);

    var star = el('select', { style: INP }, [
      el('option', { value: '1', text: '不限（1 星起）' }),
      el('option', { value: '2', text: '2 星及以上（默认）' }),
      el('option', { value: '3', text: '3 星及以上' }),
      el('option', { value: '4', text: '4 星及以上' })
    ]);
    star.value = String(d.minDifficulty || 2);
    var retire = el('input', { type: 'checkbox' }); retire.checked = !!d.autoRetire;
    var times = el('input', { type: 'number', min: '2', max: '20', style: INP, value: String(d.retireTimes || 3) });

    var save = function () {
      msg.style.color = '#6d776f'; msg.textContent = '保存中…';
      api('PUT', '/api/tune/settings', { minDifficulty: +star.value, autoRetire: retire.checked, retireTimes: +times.value })
        .then(function (j) { data = j; toast('已保存'); view(); })
        .catch(function (e) { msg.style.color = '#c0392b'; msg.textContent = e.message; });
    };
    var restore = function () {
      if (!confirm('把已淘汰的 ' + d.retired + ' 道题全部放回题库，并当作新题重新做？')) return;
      api('POST', '/api/tune/restore').then(function (j) { data = j; toast('已放回题库'); view(); })
        .catch(function (e) { msg.textContent = e.message; });
    };

    stopTick();
    if (p.active && !p.forever) timer = setInterval(function () { pauseState.textContent = pauseText(data.pause); if (Date.now() > data.pause.until) { stopTick(); load(); } }, 15000);

    frame('管控与题库', [
      el('div', { style: SEC }, [
        el('b', { text: '⏸ 暂停管控（大人要看电视时）' }),
        el('div', { text: '暂停期间不弹拦截页、不计使用时间；到点自动恢复。电视上也可以：遥控器连按 ↑ 5 次 → 输入管理密码 → 暂停管控。', style: 'font-size:12px;color:#6d776f;margin:4px 0 8px' }),
        pauseState, pauseBtns
      ]),
      el('div', { style: SEC }, [
        el('b', { text: '🎯 抽题偏好' }),
        el('div', { text: '最低星级：先从这个星级以上的题里抽（题库里没有时会自动放宽，不会没题做）。没做过的题优先，做过的优先出最久没做的，本轮不会出重复题。', style: 'font-size:12px;color:#6d776f;margin:4px 0 8px' }),
        star
      ]),
      el('div', { style: SEC }, [
        el('b', { text: '🧹 答对多次自动淘汰' }),
        el('label', { style: 'display:block;margin:6px 0' }, [retire, ' 同一道题连续答对达到下面的次数后，自动从题库撤下']),
        el('div', { text: '连续答对几次（2–20，默认 3；答错会重新计数）', style: 'font-size:12px' }), times,
        el('div', { text: '已淘汰 ' + d.retired + ' 题（保留记录，AI 出题仍会避开它们；每个科目至少保留 10 题，题库做空时会自动放回）。', style: 'font-size:12px;color:#6d776f;margin-bottom:8px' }),
        el('button', { text: '把已淘汰的题放回题库', style: BTN2 + (d.retired > 0 ? '' : ';opacity:.5'), onclick: d.retired > 0 ? restore : function () {} })
      ]),
      msg,
      el('div', {}, [el('button', { text: '保存抽题与淘汰设置', style: BTN, onclick: save })]),
      el('div', { text: '版本 v' + (d.version || ''), style: 'margin-top:6px;font-size:12px;color:#9aa0a6' })
    ]);
  }

  function mount() {
    if (document.getElementById('stv-tune-fab')) return;
    document.body.appendChild(el('button', { id: 'stv-tune-fab', text: '🎛 管控', title: '暂停管控 / 抽题与淘汰',
      style: 'position:fixed;right:14px;bottom:64px;z-index:9999;padding:10px 16px;border:0;border-radius:24px;background:#b5703a;color:#fff;font-size:14px;box-shadow:0 3px 12px rgba(0,0,0,.25);cursor:pointer', onclick: open }));
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
})();
