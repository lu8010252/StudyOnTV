/* AI 出题面板：右下角「🤖 AI出题」按钮 + 弹层。管理令牌与积分面板共用（sessionStorage）。 */
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
  var BOX = 'background:#fff;color:#222;border-radius:14px;padding:16px;max-width:560px;width:calc(100% - 24px);max-height:90vh;overflow:auto;box-shadow:0 8px 40px rgba(0,0,0,.25);font:14px/1.5 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif';
  var INP = 'width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid #d5d9d2;border-radius:8px;font-size:14px;margin:3px 0 9px';
  var BTN = 'padding:9px 14px;border:0;border-radius:8px;background:#5b6fb5;color:#fff;font-size:14px;margin:0 8px 6px 0;cursor:pointer';
  var BTN2 = 'padding:9px 14px;border:1px solid #c9cec6;border-radius:8px;background:#fff;color:#333;font-size:14px;margin:0 8px 6px 0;cursor:pointer';
  var BTN3 = 'padding:9px 14px;border:1px solid #e0b4b0;border-radius:8px;background:#fff;color:#c0392b;font-size:14px;margin:0 8px 6px 0;cursor:pointer';

  function fmt(ts) {
    if (!ts) return '从未';
    var d = new Date(ts), p = function (n) { return n < 10 ? '0' + n : '' + n; };
    return (d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }
  function toast(t, bad) {
    var n = el('div', { text: t, style: 'position:fixed;left:50%;bottom:90px;transform:translateX(-50%);background:' + (bad ? '#c0392b' : '#333') + ';color:#fff;padding:9px 16px;border-radius:20px;z-index:100001;font-size:14px;max-width:86vw' });
    document.body.appendChild(n); setTimeout(function () { n.remove(); }, 2600);
  }
  function stopPoll() { if (timer) { clearInterval(timer); timer = null; } }
  function close() { stopPoll(); if (panel) { panel.remove(); panel = null; } }

  function open() {
    close();
    panel = el('div', { style: 'position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:99998;display:flex;align-items:center;justify-content:center', onclick: function (e) { if (e.target === panel) close(); } });
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
    frame('AI 出题 · 验证管理密码', [pw, tip, el('button', { text: '进入', style: BTN, onclick: go })]);
    pw.focus();
  }
  function load(silent) {
    api('GET', '/api/aigen').then(function (j) {
      data = j;
      if (j.running && !timer) timer = setInterval(function () { load(true); }, 3000);
      var finished = !j.running && !!timer;
      if (finished) { stopPoll(); if (silent) toast(j.lastError ? j.lastError : (j.lastNote || '生成完成'), !!j.lastError); }
      if (!silent || finished) view();   // 出题过程中的轮询不重绘，避免打断正在编辑的输入框
    }).catch(function (e) { stopPoll(); if (e.message === 'unauthorized') login('登录已失效，请重新输入'); else frame('AI 出题', [el('p', { text: e.message })]); });
  }

  function stat(label, value) {
    return el('div', { style: 'flex:1;min-width:92px;background:#f1f3fa;border-radius:10px;padding:8px 10px;text-align:center' }, [
      el('div', { text: value, style: 'font-size:19px;font-weight:700' }), el('div', { text: label, style: 'font-size:12px;color:#6b7280' })]);
  }

  function view() {
    var d = data, msg = el('div', { style: 'min-height:20px;margin:4px 0;color:#c0392b' });
    var enabled = el('input', { type: 'checkbox', 'data-editing': '1' }); enabled.checked = !!d.enabled;
    var endpoint = el('input', { type: 'text', 'data-editing': '1', style: INP, placeholder: d.sharedWithGrading && d.effectiveEndpoint ? '留空 = 沿用 AI 批改的：' + d.effectiveEndpoint : '例如 https://你的中转站/v1/chat/completions', value: d.endpoint || '' });
    var key = el('input', { type: 'password', 'data-editing': '1', style: INP, placeholder: d.hasKey ? (d.sharedWithGrading ? '留空 = 沿用 AI 批改的 Key' : '已保存；不改请留空') : 'sk-…' });
    var model = el('input', { type: 'text', 'data-editing': '1', style: INP, placeholder: d.effectiveModel ? '留空 = ' + d.effectiveModel : '模型名称，如 gpt-4o-mini / deepseek-chat', value: d.model || '' });
    var batch = el('input', { type: 'number', min: '5', max: '30', 'data-editing': '1', style: INP, value: String(d.batchSize) });
    var low = el('input', { type: 'number', min: '10', max: '500', 'data-editing': '1', style: INP, value: String(d.lowWater) });
    var daily = el('input', { type: 'number', min: '1', max: '30', 'data-editing': '1', style: INP, value: String(d.dailyBatches) });
    var challenge = el('select', { 'data-editing': '1', style: INP }, [
      el('option', { value: 'standard', text: '标准：2–3 星为主，接近课本例题' }),
      el('option', { value: 'advanced', text: '提高：3–4 星为主，比课本略难（推荐）' }),
      el('option', { value: 'hard', text: '挑战：4–5 星为主，多步综合、易错点多' })
    ]);
    challenge.value = d.challenge || 'advanced';

    var profBox = el('div', {});
    var profiles = JSON.parse(JSON.stringify(d.profiles || []));
    function drawProfiles() {
      profBox.innerHTML = '';
      profiles.forEach(function (p, i) {
        var subj = el('input', { type: 'text', 'data-editing': '1', style: INP + ';width:30%;display:inline-block', value: p.subject, placeholder: '科目' });
        var grade = el('input', { type: 'text', 'data-editing': '1', style: INP + ';width:30%;display:inline-block;margin-left:2%', value: p.grade || '', placeholder: '年级' });
        var book = el('input', { type: 'text', 'data-editing': '1', style: INP + ';width:36%;display:inline-block;margin-left:2%', value: p.textbook || '', placeholder: '教材版本' });
        var focus = el('textarea', { 'data-editing': '1', rows: '5', style: INP + ';font-family:inherit', placeholder: '本学期的知识点，每行一个' });
        focus.value = p.focus || '';
        subj.addEventListener('input', function () { p.subject = subj.value; });
        grade.addEventListener('input', function () { p.grade = grade.value; });
        book.addEventListener('input', function () { p.textbook = book.value; });
        focus.addEventListener('input', function () { p.focus = focus.value; });
        profBox.appendChild(el('div', { style: 'border:1px solid #e3e6ef;border-radius:10px;padding:8px 10px;margin-bottom:8px' }, [
          subj, grade, book, el('div', { text: '知识点范围（每行一个，换新学期或换版本时改这里）', style: 'font-size:12px;color:#6b7280' }), focus,
          el('button', { text: '删除这门科目', style: BTN3 + ';padding:5px 10px;font-size:12px', onclick: function () { profiles.splice(i, 1); drawProfiles(); } })]));
      });
    }
    drawProfiles();

    var save = function (after) {
      var body = { enabled: enabled.checked, endpoint: endpoint.value.trim(), model: model.value.trim(), batchSize: +batch.value, lowWater: +low.value, dailyBatches: +daily.value, challenge: challenge.value,
        profiles: profiles.map(function (p) { return { subject: p.subject, level: p.level, grade: p.grade, textbook: p.textbook, focus: p.focus }; }) };
      if (key.value) body.apiKey = key.value;
      msg.style.color = '#6b7280'; msg.textContent = '保存中…';
      api('PUT', '/api/aigen/settings', body).then(function (j) { data = j; toast('已保存'); if (after) after(); else view(); })
        .catch(function (e) { msg.style.color = '#c0392b'; msg.textContent = e.message; });
    };
    var runNow = function () {
      save(function () {
        api('POST', '/api/aigen/run').then(function (j) { data = j; toast('已开始出题，约需 1–2 分钟'); load(); })
          .catch(function (e) { msg.textContent = e.message; });
      });
    };
    var wipe = function () {
      if (!confirm('删除全部 AI 生成的题目？（你自己导入的题不受影响）')) return;
      api('DELETE', '/api/aigen/questions').then(function (j) { data = j; toast('已删除'); view(); });
    };

    var subjRows = (d.subjects || []).map(function (s) {
      return el('div', { text: s.subject + '：共 ' + s.total + ' 题，还没做过 ' + s.unseen + ' 题', style: 'font-size:13px;color:#374151' });
    });

    frame('AI 出题', [
      el('div', { style: 'display:flex;gap:8px;flex-wrap:wrap;margin-bottom:8px' }, [
        stat('AI 已出题目', String(d.aiTotal)), stat('今天已出批次', d.batchesToday + ' / ' + d.dailyBatches), stat('状态', d.running ? '出题中…' : '空闲')]),
      el('div', { style: 'margin-bottom:8px' }, subjRows),
      el('div', { text: '上次：' + fmt(d.lastRun) + (d.lastError ? '　失败：' + d.lastError : (d.lastNote ? '　' + d.lastNote : '')), style: 'font-size:12px;margin-bottom:10px;color:' + (d.lastError ? '#c0392b' : '#6b7280') }),
      el('label', { style: 'display:block;margin-bottom:8px' }, [enabled, ' 开启自动出题（某科没做过的题少于下面的数量时，自动补一批）']),
      el('div', { text: 'API 地址（OpenAI 兼容的 chat/completions，可用中转站）' }), endpoint,
      el('div', { text: 'API Key' }), key,
      el('div', { text: '模型' }), model,
      el('div', { style: 'display:flex;gap:8px' }, [
        el('div', { style: 'flex:1' }, [el('div', { text: '每批题数（5–30）', style: 'font-size:12px' }), batch]),
        el('div', { style: 'flex:1' }, [el('div', { text: '没做过的题少于此数就补', style: 'font-size:12px' }), low]),
        el('div', { style: 'flex:1' }, [el('div', { text: '每天最多出几批', style: 'font-size:12px' }), daily])]),
      el('div', { text: '出题难度（只影响新生成的题）' }), challenge,
      el('div', { text: '科目与教材', style: 'font-weight:600;margin:6px 0' }), profBox,
      el('button', { text: '＋ 添加科目', style: BTN2, onclick: function () { profiles.push({ subject: '', level: 'AI 出题', grade: '', textbook: '', focus: '' }); drawProfiles(); } }),
      msg,
      el('div', {}, [el('button', { text: '保存', style: BTN, onclick: function () { save(); } }),
        el('button', { text: d.running ? '出题中…' : '保存并立即出一批', style: BTN2, onclick: d.running ? function () {} : runNow }),
        el('button', { text: '删除全部 AI 题', style: BTN3, onclick: wipe })]),
      el('div', { text: '每批题都会让 AI 不看答案重做一遍，两次答案不一致的题会被丢弃；已有题目会去重；同一题型模板（只是数字不同）最多留 2 道，字面太像的也会丢掉。版本 v' + (d.version || ''), style: 'margin-top:8px;font-size:12px;color:#9aa0a6' })
    ]);
  }

  function mount() {
    if (document.getElementById('stv-aigen-fab')) return;
    document.body.appendChild(el('button', { id: 'stv-aigen-fab', text: '🤖 AI出题', title: 'AI 出题',
      style: 'position:fixed;right:110px;bottom:16px;z-index:9999;padding:10px 16px;border:0;border-radius:24px;background:#5b6fb5;color:#fff;font-size:14px;box-shadow:0 3px 12px rgba(0,0,0,.25);cursor:pointer', onclick: open }));
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
})();
