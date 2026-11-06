(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var all = function (selector) { return Array.prototype.slice.call(document.querySelectorAll(selector)); };
  var state = { screen: '', round: null, config: {}, learning: {}, busy: false, selected: [], version: 0, awaiting: false, reviewed: false, review: null, draft: null };
  var toastTimer, nextTimer, taps = 0, lastTap = 0, upTaps = 0, lastUpTap = 0;
  function nativeCall(name, args) {
    var bridge = window.SOTVNative;
    try { if (bridge && typeof bridge[name] === 'function') return bridge[name].apply(bridge, args || []); } catch (e) {}
    return null;
  }
  function request(path, body, callback) {
    var xhr = new XMLHttpRequest(), finished = false;
    function done(error, value) { if (finished) return; finished = true; callback(error, value); }
    xhr.open(body ? 'POST' : 'GET', path, true);
    xhr.timeout = 8000;
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.setRequestHeader('X-Device-Token', nativeCall('apiToken') || '');
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      var data;
      try { data = JSON.parse(xhr.responseText); } catch (e) { return done('连接设备失败'); }
      if (xhr.status < 200 || xhr.status >= 300 || data.error) return done(data.message || '请求失败，请重试');
      done(null, data);
    };
    xhr.onerror = function () { done('连接设备失败'); };
    xhr.ontimeout = function () { done('连接超时，请重试'); };
    xhr.send(body ? JSON.stringify(body) : null);
  }
  function toast(value) {
    var box = $('toast');
    if (!box) { box = document.createElement('div'); box.id = 'toast'; document.body.appendChild(box); }
    box.textContent = value; box.className = 'toast show';
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () { box.className = 'toast'; }, 3000);
  }
  function show(screen) {
    all('.screen').forEach(function (el) { el.className = 'screen'; });
    $('screen-' + screen).className = 'screen active';
    state.screen = screen;
    document.body.className = 'on-' + screen;
  }
  function pill() {
    var c = state.config, st = state.learning;
    var left = Math.max(0, (c.roundsPerDay || 3) - (st.roundsDoneToday || 0));
    $('topPill').textContent = (st.remainingSeconds > 0 ? '可用 ' + Math.ceil(st.remainingSeconds / 60) + ' 分钟 · ' : '') + '还剩 ' + left + ' 组' + (st.extraPool > 0 ? ' · 加练池 ' + st.extraPool + ' 题' : '') +
      (st.points && st.points.enabled && st.points.balance >= 0 ? ' · ⭐ ' + st.points.balance : '');
    if ($('ver') && st.version) $('ver').textContent = 'v' + st.version + (st.monitor ? ' · 计时：' + st.monitor : '') + (st.diag ? ' · ' + st.diag : '');
  }
  function loadState(callback) {
    request('/api/state', null, function (error, result) {
      if (!error) { state.config = result.config; state.learning = result.learning; pill(); }
      if (callback) callback(error, result);
    });
  }
  function start() {
    if (state.busy) return;
    state.busy = true;
    request('/api/quiz/start', {}, function (error, result) {
      state.busy = false;
      if (error) {
        show('quiz'); $('qText').textContent = error;
        $('qOptions').innerHTML = '<button class="btn btn-primary focusable" data-action="retry">重试</button>';
        return;
      }
      state.learning = result.status || state.learning;
      pill();
      if (result.pending) return pending(result.reviewId);
      if (result.reviewed) return reviewed(result);
      state.awaiting = false; state.reviewed = false;
      if (result.blocked) return lock(result.blocked);
      if (result.watching) {
        state.lockReason = '';
        show('lock'); $('lockTitle').textContent = '已解锁使用时间';
        $('lockDesc').textContent = '还可以使用 ' + Math.ceil(state.learning.remainingSeconds / 60) + ' 分钟';
        $('lockIcon').textContent = '✓'; $('lockNote').textContent = '';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="home">返回桌面</button>';
        focusDefault(); return;
      }
      state.round = result;
      show('quiz'); renderQuestion();
    });
  }
  function renderQuestion() {
    var round = state.round, item = round.question;
    if (!state.draft || state.draft.nonce !== round.nonce) state.draft = { nonce: round.nonce, text: '', drawing: '' };
    state.selected = [];
    $('qText').textContent = item.q;
    $('qIndex').textContent = round.mode === 'streak' ? round.streak : round.mode === 'practice' ? round.answered + 1 : Math.min(round.answered + 1, round.target);
    $('qTotal').textContent = round.mode === 'practice' ? '题' : '/ ' + round.target;
    $('qMode').textContent = (round.extra ? '加练任务' : round.mode === 'streak' ? '连续答对' : round.mode === 'practice' ? '自由练习' : '本组任务') + ' · ' + new Array((item.difficulty || 2) + 1).join('★');
    $('qPool').textContent = round.extraPool > 0 ? '加练池还剩 ' + round.extraPool + ' 题' : '';
    $('qDots').textContent = round.mode === 'streak' ? '连续答对 ' + round.streak + ' 题' : '';
    var options = $('qOptions'); options.innerHTML = '';
    if (item.type === 'text' || item.type === 'subjective') {
      var input = document.createElement('textarea'); input.id = 'writtenAnswer'; input.className = 'written-answer';
      input.placeholder = item.type === 'subjective' ? '写下您的思路和答案…' : '请输入答案'; input.maxLength = 10000; input.autocomplete = 'off'; input.rows = 4;
      input.value = state.draft.text;
      input.oninput = function () { state.draft.text = input.value; };
      options.appendChild(input);
      var tools = document.createElement('div'); tools.className = 'input-tools';
      if (document.documentElement.classList.contains('tv-device')) {
        // 电视/投影上遥控器没法直接在输入框里打字，走原生输入页（系统电视输入法）
        tools.innerHTML = '<button class="btn btn-ghost focusable" data-action="answer">' + (state.draft.text ? '修改答案' : '填写答案') + '</button>';
        options.appendChild(tools);
      } else {
        tools.innerHTML = '<button class="btn btn-ghost focusable" data-action="voice">语音输入</button><button class="btn btn-ghost focusable" data-action="handwrite">手写作答</button>';
        options.appendChild(tools);
      }
      if (state.draft.drawing) {
        var image = document.createElement('img'); image.className = 'drawing-preview'; image.src = state.draft.drawing; options.appendChild(image);
        var clear = document.createElement('button'); clear.className = 'btn btn-ghost focusable'; clear.setAttribute('data-action', 'clear-drawing'); clear.textContent = '清除手写'; options.appendChild(clear);
      }
      addSubmit(options, '提交答案');
    } else {
      item.a.forEach(function (value, index) {
        var button = document.createElement('button'); button.className = 'option focusable';
        button.setAttribute('data-opt', index);
        var letter = document.createElement('span'); letter.className = 'letter'; letter.textContent = String.fromCharCode(65 + index);
        var label = document.createElement('span'); label.className = 'opt-text'; label.textContent = value;
        button.appendChild(letter); button.appendChild(label); options.appendChild(button);
      });
      if (item.type === 'multiple') addSubmit(options, '确认所选答案');
    }
    window.SOTV_MATH($('qText')); window.SOTV_MATH(options);
    focusDefault();
  }
  function addSubmit(parent, value) {
    var button = document.createElement('button'); button.className = 'btn btn-primary focusable answer-submit';
    button.setAttribute('data-action', 'submit'); button.textContent = value; parent.appendChild(button);
  }
  function choose(index) {
    if (state.busy || !state.round) return;
    if (state.round.question.type === 'multiple') {
      var pos = state.selected.indexOf(index);
      if (pos < 0) state.selected.push(index); else state.selected.splice(pos, 1);
      all('#qOptions [data-opt]').forEach(function (el) {
        el.classList.toggle('selected', state.selected.indexOf(+el.getAttribute('data-opt')) >= 0);
      });
    } else { state.selected = [index]; submit(); }
  }
  function submit() {
    if (state.busy || !state.round) return;
    var round = state.round, written = $('writtenAnswer');
    if (written ? (!written.value.trim() && !state.draft.drawing) : !state.selected.length) { toast('请先填写或选择答案'); return; }
    state.busy = true;
    request('/api/quiz/answer', { roundId: round.id, nonce: round.nonce, selections: state.selected, text: written ? written.value : '', drawing: state.draft ? state.draft.drawing : '' }, function (error, result) {
      if (error) { state.busy = false; toast(error); return; }
      if (result.pending) { state.busy = false; pending(result.reviewId); return; }
      if (result.blocked) { state.busy = false; lock(result.blocked); return; }
      state.learning = result.status; pill();
      all('#qOptions [data-opt]').forEach(function (el) {
        var i = +el.getAttribute('data-opt');
        if (result.correctIndices.indexOf(i) >= 0) el.className += ' right';
        else if (state.selected.indexOf(i) >= 0) el.className += ' wrong';
      });
      var gain = result.pointsEarned > 0 ? '  +' + result.pointsEarned + ' ⭐' : '';
      if (written && !result.correct) toast('答案：' + result.answerText + gain);
      else if (result.tip) toast(result.tip + gain);
      else if (gain) toast('答对了' + gain);
      nextTimer = window.setTimeout(function () {
        state.busy = false;
        if (state.screen !== 'quiz') return;
        if (result.finished) {
          state.round = null;
          if (result.passed) {
            if (result.status.reason) { lock(result.status.reason); toast('奖励已保存，达到可用时段后使用'); return; }
            show('lock'); $('lockTitle').textContent = '任务完成'; $('lockIcon').textContent = '✓';
            $('lockDesc').textContent = '已解锁 ' + result.rewardMinutes + ' 分钟';
            var pb = result.status.points;
            $('lockNote').textContent = pb && pb.enabled && pb.balance >= 0 ? '星星屋积分 ' + pb.balance + '（今天已得 ' + pb.today + '）' : '';
            $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="home">返回桌面</button>';
            focusDefault();
            window.setTimeout(function () { nativeCall('exitToLauncher'); }, 1300);
          } else { toast('本组未通过，再试一组'); start(); }
        } else { state.round = result.next; renderQuestion(); }
      }, result.correct ? 600 : 1500);
    });
  }
  function reviewed(result) {
    state.awaiting = false; state.reviewed = true; state.review = result;
    show('lock'); $('lockIcon').textContent = result.result.correct ? '✓' : '✎';
    $('lockTitle').textContent = '批改完成 · ' + result.score + ' 分';
    $('lockDesc').textContent = result.feedback || '已保存本次批改结果';
    $('lockNote').textContent = result.result.correct ? '本题通过' : '查看反馈后继续练习';
    $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="review-continue">' + (result.result.finished && result.result.passed ? '继续使用' : '继续答题') + '</button>';
    if (window.SOTV_MATH) window.SOTV_MATH($('lockDesc'));
    focusDefault();
  }
  function pending(id) {
    state.awaiting = true; state.reviewed = false;
    show('lock'); $('lockIcon').textContent = '✓'; $('lockTitle').textContent = '答案已保存';
    $('lockDesc').textContent = '等待家长或 AI 批改，通过后继续任务';
    $('lockNote').textContent = '';
    $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="retry">查看批改结果</button>';
    focusDefault();
  }
  function lock(reason) {
    state.lockReason = reason;
    show('lock'); state.round = null;
    $('lockIcon').textContent = reason === 'schedule' ? '◷' : '⌛';
    $('lockTitle').textContent = reason === 'schedule' ? '还没到可用时间' : reason === 'rounds' ? '今天的任务已全部完成' : reason === 'quota' ? '今天的使用时间已用完' : '使用时间到了';
    var when = '';
    if (state.learning.nextAvailableAt) {
      var date = new Date(state.learning.nextAvailableAt);
      when = (date.getMonth() + 1) + ' 月 ' + date.getDate() + ' 日 ' + ('0' + date.getHours()).slice(-2) + ':' + ('0' + date.getMinutes()).slice(-2);
    }
    $('lockDesc').textContent = when ? '下次可用：' + when : reason === 'timeup' ? '完成学习任务后才能使用设备' : '现在暂不可用';
    $('lockNote').textContent = state.config.allDay ? '' : '可用时段 ' + state.config.windowStart + ' — ' + state.config.windowEnd;
    $('lockActions').innerHTML = reason === 'timeup' ? '<button class="btn btn-primary focusable" data-action="retry">开始答题</button>' : '';
    focusDefault();
  }
  function pair() {
    var raw = nativeCall('lanInfo'), info;
    try { info = JSON.parse(raw); } catch (e) {}
    if (!info) { nativeCall('openParent'); return; }
    show('pair');
    if (!info.ip) { $('pairQr').textContent = '请先连接 WiFi'; $('pairUrl').textContent = '暂无局域网地址，学习功能可离线使用'; focusDefault(); return; }
    var url = 'http://' + info.ip + ':' + info.port + '/admin/';
    $('pairUrl').textContent = url;
    var qr = window.qrcode(0, 'M'); qr.addData(url); qr.make(); $('pairQr').innerHTML = qr.createImgTag(8, 8);
    focusDefault();
  }
  function focusables() {
    var screen = document.querySelector('.screen.active');
    return screen ? Array.prototype.slice.call(screen.querySelectorAll('.focusable')) : [];
  }
  function setFocus(index) {
    var list = focusables();
    list.forEach(function (el) { el.classList.remove('focused'); });
    if (list[index]) { list[index].classList.add('focused'); list[index].scrollIntoView({ block: 'nearest' }); }
  }
  function focusDefault() { if (document.documentElement.classList.contains('tv-device')) setFocus(0); }
  function move(dir) {
    var list = focusables(), index = -1, best = -1, score = 1e12;
    list.forEach(function (el, i) { if (el.classList.contains('focused')) index = i; });
    if (index < 0) { setFocus(0); return; }
    var rect = list[index].getBoundingClientRect();
    list.forEach(function (el, i) {
      if (i === index) return;
      var r = el.getBoundingClientRect(), dx = r.left + r.width / 2 - rect.left - rect.width / 2, dy = r.top + r.height / 2 - rect.top - rect.height / 2;
      var primary = dir === 'left' ? -dx : dir === 'right' ? dx : dir === 'up' ? -dy : dy;
      var secondary = dir === 'left' || dir === 'right' ? Math.abs(dy) : Math.abs(dx);
      if (primary > 8 && primary + secondary * 1.8 < score) { score = primary + secondary * 1.8; best = i; }
    });
    if (best >= 0) setFocus(best);
  }
  function activate(el) {
    if (!el) el = document.querySelector('.screen.active .focused');
    if (!el) return;
    if (el.hasAttribute('data-opt')) return choose(+el.getAttribute('data-opt'));
    var action = el.getAttribute('data-action');
    if (action === 'submit') submit();
    if (action === 'review-continue' && !state.busy) {
      state.busy = true;
      request('/api/quiz/review', { reviewId: state.review.reviewId }, function (error, result) {
        state.busy = false;
        if (error) { toast(error); return; }
        var finished = state.review.result.finished && state.review.result.passed;
        state.reviewed = false;
        if (finished && !result.status.reason && result.status.remainingSeconds > 0) nativeCall('exitToLauncher');
        else start();
      });
    }
    if (action === 'voice' || action === 'handwrite') nativeCall('answerInput', [action === 'voice']);
    if (action === 'answer') nativeCall('answerInput', [false]);
    if (action === 'clear-drawing') { state.draft.drawing = ''; renderQuestion(); }
    if (action === 'retry') start();
    if (action === 'home') nativeCall('exitToLauncher');
    if (action === 'quiz') { if (state.screen === 'pair') nativeCall('backToQuiz'); else start(); }
  }
  function route() {
    window.clearTimeout(nextTimer); state.busy = false;
    var hash = location.hash || '#/quiz';
    if (hash.indexOf('#/pair') === 0) return pair();
    loadState(function (error) {
      if (error) { $('qText').textContent = error; return; }
      if (hash.indexOf('#/lock') === 0) lock(state.learning.reason || 'timeup'); else start();
    });
  }
  function boot() {
    show('quiz');
    document.querySelector('.brand').addEventListener('click', function () {
      var now = Date.now(); taps = now - lastTap < 900 ? taps + 1 : 1; lastTap = now;
      if (taps >= 5) { taps = 0; nativeCall('openParent'); }
    });
    document.addEventListener('click', function (event) {
      var el = event.target;
      while (el && el !== document.body) {
        if (el.classList && el.classList.contains('focusable')) { activate(el); return; }
        el = el.parentNode;
      }
    });
    document.addEventListener('keydown', function (event) {
      if (event.keyCode === 38 && !event.repeat) {
        var now = Date.now(); upTaps = now - lastUpTap < 900 ? upTaps + 1 : 1; lastUpTap = now;
        if (upTaps >= 5) { upTaps = 0; nativeCall('openParent'); event.preventDefault(); return; }
      } else if (!event.repeat) upTaps = 0;
      if (event.target.tagName === 'INPUT' || event.target.tagName === 'TEXTAREA') return;
      var dir = {37:'left',38:'up',39:'right',40:'down'}[event.keyCode];
      if (dir) move(dir); else if (event.keyCode === 13) activate(); else return;
      event.preventDefault();
    });
    $('updateGo').onclick = function () { var u = window.currentUpdate; if (u) nativeCall('installUpdate', [u.url, u.latest, u.notes || '']); };
    $('updateClose').onclick = function () { $('updateTip').className = 'update-tip'; };
    var info = nativeCall('deviceInfo');
    if (info && JSON.parse(info).platform === 'android-tv') document.documentElement.classList.add('tv-device');
    else if (info) { var analytics = document.createElement('script'); analytics.src = 'assets/js/analytics.js'; analytics.async = true; document.head.appendChild(analytics); }
    route();
    window.setInterval(function () {
      $('clock').textContent = new Date().toTimeString().slice(0, 5);
      if (state.busy || state.reviewed || state.screen === 'pair') return;
      var version = state.config.version;
      loadState(function (error) {
        if (error) return;
        if (state.screen === 'lock' && !state.lockReason && !state.learning.reason && state.learning.remainingSeconds > 0)
          $('lockDesc').textContent = '还可以使用 ' + Math.ceil(state.learning.remainingSeconds / 60) + ' 分钟（停在本页不计时，离开去看电视后才开始计时）';
        if (state.awaiting) { start(); return; }
        if (state.screen === 'lock' && state.lockReason !== state.learning.reason) {
          if (!state.learning.reason) start(); else lock(state.learning.reason);
          return;
        }
        if (state.screen === 'quiz' && state.learning.reason && ['quota','rounds','schedule'].indexOf(state.learning.reason) >= 0 && state.config.playMode !== 'practice') lock(state.learning.reason);
        else if (version !== state.config.version && state.screen === 'quiz') start();
      });
    }, 2000);
  }
  window.SOTV_INPUT = function (result) {
    if (!state.round) return;
    state.draft = { nonce: state.round.nonce, text: result.text || '', drawing: result.drawing || '' };
    renderQuestion();
  };
  window.SOTV_UPDATE = function (info) { if (!info || !info.latest) return; window.currentUpdate = info; $('updateText').textContent = '发现新版本 v' + info.latest; $('updateTip').className = 'update-tip show'; };
  window.TV = { move: move, select: function () { activate(); }, back: function () { if (!state.learning.reason && state.learning.remainingSeconds > 0) nativeCall('exitToLauncher'); else toast('现在暂不可用'); }, state: state };
  window.SOTV_RESUME = function () { if (document.readyState === 'complete' && !state.busy && state.screen !== 'pair') route(); };
  window.addEventListener('hashchange', route);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
