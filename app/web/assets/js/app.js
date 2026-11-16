(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var all = function (selector) { return Array.prototype.slice.call(document.querySelectorAll(selector)); };
  var state = { screen: '', round: null, config: {}, learning: {}, busy: false, selected: [], version: 0, awaiting: false, reviewed: false, review: null, draft: null, unlockPending: false };
  var toastTimer, nextTimer, taps = 0, lastTap = 0, upTaps = 0, lastUpTap = 0;
  var QUESTION_INPUT_GUARD_MS = 300;
  var renderedQuestionNonce = null, questionInputAllowedAt = 0;
  function questionInputTime() {
    return window.performance && typeof window.performance.now === 'function' ? window.performance.now() : Date.now();
  }
  function questionInputBlocked() {
    return state.screen === 'quiz' && state.round && state.round.nonce === renderedQuestionNonce &&
      questionInputTime() < questionInputAllowedAt;
  }
  // 断电 / 被系统清后台后恢复当前这组题。用 localStorage 而不是 sessionStorage：
  // 进程被杀后 WebView 是新的会话，sessionStorage 活不下来。
  var ROUND_KEY = 'sotv_round';
  function saveRound() {
    if (!state.round) return clearRound();
    try {
      window.localStorage.setItem(ROUND_KEY, JSON.stringify({
        round: state.round, quizGeneration: state.learning.quizGeneration || 0
      }));
    } catch (e) {}
  }
  function clearRound() { try { window.localStorage.removeItem(ROUND_KEY); } catch (e) {} }
  function savedRound() {
    try {
      var saved = JSON.parse(window.localStorage.getItem(ROUND_KEY) || 'null');
      // nonce 和 quizGeneration 都对得上，说明服务端还停在这一题，恢复才不会答到已经换掉的题
      if (saved && saved.round && saved.round.nonce && saved.round.question &&
          saved.quizGeneration === (state.learning.quizGeneration || 0)) return saved.round;
    } catch (e) {}
    return null;
  }
  function supportsGrid() {
    var style = document.documentElement.style;
    return !!(style && (typeof style.grid !== 'undefined' || typeof style.gridTemplateColumns !== 'undefined'));
  }
  function canVoice() { return !!(window.SOTVNative && typeof window.SOTVNative.answerVoice === 'function'); }
  function canRead() { return !!(window.SOTVNative && typeof window.SOTVNative.readInLanguage === 'function'); }
  // 可用时段文案由设备按周计划算好（state.learning.scheduleText），页面不再自己拼旧版的 windowStart/windowEnd
  function scheduleText() { return (state.learning && state.learning.scheduleText) || ''; }
  // 朗读内容由家长在「设置 → 朗读」里定：只读题干、题干加选项、再加答案（答案在作答后才读）
  function readScope() { return (state.speech && state.speech.readScope) || 'question'; }
  // 公式里的 $ 符号 TTS 会念出来，朗读前去掉
  function speakable(text) { return String(text == null ? '' : text).replace(/\$\$?/g, '').trim(); }
  function readText(item) {
    if (!item) return '';
    var parts = [speakable(item.q)];
    if (readScope() !== 'question') {
      (item.a || []).forEach(function (value, index) { parts.push(String.fromCharCode(65 + index) + '、' + speakable(value)); });
    }
    return parts.join('。');
  }
  // 答题 / 锁定页的家长解锁入口（默认关闭，家长在「时间计划」里打开）
  function canUnlock() { return !!(window.SOTVNative && typeof window.SOTVNative.askParentPassword === 'function' && typeof window.SOTVNative.quizUnlock === 'function'); }
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
    var who = (state.children && state.children.length > 1) ? (childName() + ' · ') : '';
    var left = Math.max(0, (c.roundsPerDay || 3) - (st.roundsDoneToday || 0));
    if ($('ver') && st.version) $('ver').textContent = 'v' + st.version + (st.monitor ? ' · 计时：' + st.monitor : '') + (st.diag ? ' · ' + st.diag : '');
    if (st.override && st.override.mode === 'unlock') { $('topPill').textContent = who + '家长已解锁 · ' + left2(st.override.remainingSeconds); return; }
    if (st.override && st.override.mode === 'indefinite') { $('topPill').textContent = who + '管控已关闭'; return; }
    var point = st.activePoint;
    var pointName = point && point.name ? point.name : '';
    if (st.free) {
      if (!pointName) { $('topPill').textContent = who + '自由时段'; return; }
      $('topPill').textContent = who + (point.mode === 'exempt' ? '大人豁免 · ' : '网课时间 · ') + pointName + (point.until ? ' 至 ' + point.until : '');
      return;
    }
    var remSec = (st.remainingSeconds || 0) + (st.freeQuotaSeconds || 0);
    var remStr = remSec > 0 ? (remSec < 3600 ? '剩 ' + Math.ceil(remSec / 60) + ' 分钟' : '剩 ' + Math.floor(remSec / 3600) + ' 小时 ' + Math.ceil((remSec % 3600) / 60) + ' 分') + ' · ' : '';
    $('topPill').textContent = who + (pointName ? '网课时间 · ' + pointName + ' 至 ' + (point.until || '') + ' · ' : '') + remStr + '还剩 ' + left + ' 组' + (st.extraPool > 0 ? ' · 加练池 ' + st.extraPool + ' 题' : '') +
      (st.points && st.points.enabled && st.points.balance >= 0 ? ' · ⭐ ' + st.points.balance : '');
  }
  function left2(seconds) {
    var m = Math.ceil(seconds / 60);
    return m >= 60 ? '还剩 ' + Math.floor(m / 60) + ' 小时' + (m % 60 ? ' ' + (m % 60) + ' 分' : '') : '还剩 ' + m + ' 分钟';
  }
  function loadState(callback) {
    request('/api/state', null, function (error, result) {
      if (!error) {
        state.config = result.config; state.learning = result.learning; state.speech = result.speech || {};
        // 多孩子（#132）：孩子列表和当前是谁由设备端算好，页面只负责显示 / 切换
        state.children = result.children || []; state.activeChild = result.activeChild || '';
        pill(); applyTheme(result.config.theme); applyFont();
      }
      if (callback) callback(error, result);
    });
  }
  function childName(id) {
    var list = state.children || [];
    for (var i = 0; i < list.length; i++) if (list[i].id === (id === undefined ? state.activeChild : id)) return list[i].name;
    return '';
  }
  function applyFont() {
    var scale = Math.max(75, Math.min(200, +state.config.fontScale || 100));
    if (state.fontScale === scale) return;
    document.documentElement.style.fontSize = '';
    var base = parseFloat(window.getComputedStyle(document.documentElement).fontSize);
    document.documentElement.style.setProperty('--learn-scale', String(scale / 100));
    document.documentElement.style.fontSize = (base * scale / 100) + 'px'; state.fontScale = scale;
  }
  window.addEventListener('resize', function () { state.fontScale = 0; applyFont(); window.setTimeout(fitQuiz, 0); });
  function start(level) {
    if (state.busy) return;
    state.busy = true;
    request('/api/quiz/start', level ? { level: level } : {}, function (error, result) {
      state.busy = false;
      if (error) {
        show('quiz'); $('qText').textContent = error;
        $('qOptions').className = 'options solo';
        $('qOptions').innerHTML = '<button class="btn btn-primary focusable" data-action="retry">重试</button>';
        return;
      }
      clearRound();   // 新的一轮 / 锁屏：上一组题的恢复点作废（下面真的出题时会重新保存）
      state.learning = result.status || state.learning;
      pill();
      if (result.videoLesson) {
        state.lockReason = state.learning.reason; state.wasFree = !!state.learning.free;
        show('lock'); icon($('lockIcon'), 'pencil'); $('lockTitle').textContent = '公益视频学习';
        $('lockDesc').textContent = '完成 ' + Math.ceil(result.videoLesson.remainingSeconds / 60) + ' 分钟观看后领取使用时间';
        $('lockNote').textContent = '播放暂停时不累计，拖动进度不能跳过学习时长';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="watch-lesson">开始观看</button>';
        focusDefault(); return;
      }
      if (result.chooseLevel) {
        state.lockReason = state.learning.reason; state.wasFree = !!state.learning.free;
        show('lock'); icon($('lockIcon'), 'pencil'); $('lockTitle').textContent = '选择你的年级';
        $('lockDesc').textContent = '完成这一轮后可以重新选择'; $('lockNote').textContent = ''; $('lockActions').innerHTML = '';
        result.chooseLevel.forEach(function (value) { var button = document.createElement('button'); button.className = 'btn btn-primary focusable'; button.textContent = value; button.setAttribute('data-action', 'choose-level'); button.setAttribute('data-level', value); $('lockActions').appendChild(button); });
        focusDefault(); return;
      }
      if (result.heldFeedback) {
        // 兜底：解析内容丢先时不要白屏（主屏会重新取题）
        if (!result.question) { window.setTimeout(start, 800); return; }
        state.round = {question:result.question,mode:'round',answered:0,target:1};
        show('quiz'); renderQuestion(); state.busy = true; state.feedback = result.result; state.feedback.status = result.status;
        showFeedback(state.feedback, result.question, result.question.type === 'text' || result.question.type === 'subjective'); return;
      }
      if (result.pending) return pending(result.reviewId);
      if (result.reviewed) return reviewed(result);
      state.awaiting = false; state.reviewed = false;
      if (result.blocked) return lock(result.blocked);
      if (result.free) {
        // 「只在这段时间内需要先答题」模式下的时段外：不管控
        state.lockReason = '';
        var ov = state.learning.override;
        show('lock'); icon($('lockIcon'), 'check');
        $('lockTitle').textContent = ov && ov.mode === 'unlock' ? '家长已临时解锁' : '现在是自由时段';
        $('lockDesc').textContent = ov && ov.mode === 'unlock' ? left2(ov.remainingSeconds) + '，到时自动恢复学习规则'
          : (scheduleText() ? scheduleText() + ' 需要先完成学习任务，其余时间可以直接使用' : '其余时间可以直接使用');
        $('lockNote').textContent = '';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="home">返回桌面</button>';
        focusDefault(); return;
      }
      if (result.watching) {
        state.lockReason = '';
        show('lock'); $('lockTitle').textContent = '已解锁使用时间';
        $('lockDesc').textContent = '还可以使用 ' + Math.ceil(state.learning.remainingSeconds / 60) + ' 分钟';
        icon($('lockIcon'), 'check'); $('lockNote').textContent = '';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="home">返回桌面</button>';
        focusDefault(); return;
      }
      state.round = result;
      show('quiz'); renderQuestion();
    });
  }
  // 题库被家长删空 / 答题范围没有题目：给出明确提示并允许重试，绝不能停在旧题上
  function quizEmpty(message) {
    state.round = null; state.busy = false; clearRound();
    show('quiz');
    $('qText').textContent = message || '题库为空，请由家长添加题目';
    $('qOptions').className = 'options solo';
    $('qOptions').innerHTML = '<button class="btn btn-primary focusable" data-action="retry">重试</button>';
    focusDefault();
  }
  function renderQuestion() {
    var round = state.round, item = round.question;
    saveRound();   // 记住当前这组题，App 被系统清掉后还能接着答
    if (window._pendingInput) { state.draft = { nonce: round.nonce, text: window._pendingInput.text || '', drawing: window._pendingInput.drawing || '' }; window._pendingInput = null; }
    else if (!state.draft || state.draft.nonce !== round.nonce) state.draft = { nonce: round.nonce, text: '', drawing: '' };
    state.selected = [];
    $('qText').textContent = item.q;
    $('qIndex').textContent = round.mode === 'count' ? round.correct || 0 : round.mode === 'streak' ? round.streak : round.mode === 'practice' ? round.answered + 1 : Math.min(round.answered + 1, round.target);
    $('qTotal').textContent = round.mode === 'practice' ? '题' : '/ ' + round.target;
    $('qMode').textContent = (round.extra ? '加练任务' : round.mode === 'streak' ? '连续答对' : round.mode === 'count' ? '答对指定题数' : round.mode === 'practice' ? '自由练习' : '本组任务') + ' · ' + new Array((item.difficulty || 2) + 1).join('★');
    $('qPool').textContent = round.extraPool > 0 ? '加练池还剩 ' + round.extraPool + ' 题' : '';
    $('qDots').textContent = round.mode === 'streak' ? '连续答对 ' + round.streak + ' 题' : '';
    var qLength = (item.q || '').length;
    $('qText').className = 'question' + (qLength > 120 ? ' longer' : qLength > 56 ? ' long' : '');
    nativeCall('stopReading');
    if (state.speech && state.speech.autoRead) nativeCall('readInLanguage', [readText(item), item.language || '']);
    renderMedia(item);
    var options = $('qOptions'); options.innerHTML = '';
    var longest = 0, optionCount = (item.a || []).length;
    (item.a || []).forEach(function (value) { longest = Math.max(longest, String(value).length); });
    // 5 个以上选项用紧凑排版；只有 1 个选项时铺满一行；
    // 极老 WebView 不支持 CSS Grid，加 no-grid 退回原来的两列 flex
    options.className = 'options' + (optionCount > 4 ? ' many' : '') + (optionCount === 1 ? ' solo' : '') +
      (longest > 26 ? ' wordy' : '') + (supportsGrid() ? '' : ' no-grid');
    if (item.type === 'text' || item.type === 'subjective') {
      var input = document.createElement('textarea'); input.id = 'writtenAnswer'; input.className = 'written-answer';
      input.placeholder = item.type === 'subjective' ? '写下您的思路和答案…' : '请输入答案'; input.maxLength = 10000; input.autocomplete = 'off'; input.rows = 4;
      input.value = state.draft.text;
      input.oninput = function () { state.draft.text = input.value; };
      if (isTv()) {
        // 遥控器无法直接在网页输入框里打字：按 OK 打开原生输入框（可调出系统输入法 / 语音）
        input.readOnly = true; input.className += ' focusable'; input.setAttribute('data-action', 'type-answer');
        input.placeholder = '按 OK 键输入答案';
      }
      options.appendChild(input);
      if (isTv()) {
        // 已有草稿时 placeholder 不显示，操作方法必须常驻可见
        var hint = document.createElement('div'); hint.className = 'tv-input-hint';
        hint.textContent = '▶  遥控器 OK 键打开键盘输入';
        options.appendChild(hint);
      }
      var tools = document.createElement('div'); tools.className = 'input-tools';
      if (isTv()) {
        // 电视端只有原生层真的支持语音才给按钮，文案直接说明按 OK 会做什么
        if (canVoice()) {
          var voiceBtn = document.createElement('button'); voiceBtn.className = 'btn btn-ghost focusable';
          voiceBtn.setAttribute('data-action', 'voice'); voiceBtn.textContent = '按 OK 语音作答';
          tools.appendChild(voiceBtn);
        }
      } else {
        tools.innerHTML = '<button class="btn btn-ghost focusable" data-action="voice">语音输入 / 重录</button>' +
          '<button class="btn btn-ghost focusable" data-action="handwrite">手写作答</button>';
      }
      if (tools.childNodes.length) options.appendChild(tools);
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
    // 「朗读」固定放在页脚，不再混进选项区，避免遥控器在选项之间误选
    var readAloudBtn = $('readAloudBtn');
    if (readAloudBtn) {
      readAloudBtn.className = 'btn btn-ghost focusable read-aloud-btn';
      readAloudBtn.style.display = canRead() ? '' : 'none';
    }
    window.SOTV_MATH($('qText')); window.SOTV_MATH(options);
    focusDefault();
    // #141：排版要等公式 / 图片渲染完再量；这一步不会改变正常字号下的显示
    window.setTimeout(fitQuiz, 0);
    // 新题刚显示时丢弃残留连点；同一题的输入回填 / 重绘不重新计时。
    if (renderedQuestionNonce !== round.nonce) {
      renderedQuestionNonce = round.nonce;
      questionInputAllowedAt = questionInputTime() + QUESTION_INPUT_GUARD_MS;
    }
  }
  // #141：电视上系统字体被调大、或家长把「学习页字号」调大后，题干会被顶到页头后面、选项超出屏幕。
  // 先按原样量一次；放不下就换两档更紧凑的排版；还放不下就改成整页可滚动（舞台会跟着滚动）。
  function fitQuiz() {
    var wrap = document.querySelector('#screen-quiz .quiz-wrap');
    if (!wrap) return;
    if (!isTv() || !state.round || state.screen !== 'quiz') { wrap.className = 'quiz-wrap'; return; }
    var head = wrap.querySelector('.quiz-head'), foot = wrap.querySelector('.quiz-foot');
    var question = $('qText'), options = $('qOptions');
    // 前两档要求整屏放下；最后一档允许超出，交给 #stage 滚动
    var steps = ['', 'compact', 'compact-2'];
    for (var i = 0; i < steps.length; i++) {
      wrap.className = 'quiz-wrap' + (steps[i] ? ' ' + steps[i] : '');
      var headRect = head.getBoundingClientRect(), footRect = foot.getBoundingClientRect();
      var qRect = question.getBoundingClientRect(), optionsRect = options.getBoundingClientRect();
      if (qRect.top >= headRect.bottom + 6 && optionsRect.bottom <= footRect.top - 6) return;
    }
    wrap.className = 'quiz-wrap compact-2 scroll';
  }
  function renderMedia(item) {
    var box = $('qMedia'); box.innerHTML = '';
    if (item.image && /^(https?:\/\/|data:image\/)/.test(item.image)) { var image = document.createElement('img'); image.src = item.image; image.alt = '题目配图'; image.className = 'question-media-image'; box.appendChild(image); }
    if (item.audio && /^https?:\/\//.test(item.audio)) {
      if (window.SOTVNative) { var play = document.createElement('button'); play.className = 'btn btn-ghost focusable'; play.setAttribute('data-action', 'play-audio'); play.textContent = '播放题目音频'; box.appendChild(play); }
      else { var audio = document.createElement('audio'); audio.src = item.audio; audio.controls = true; audio.className = 'focusable'; audio.preload = 'none'; box.appendChild(audio); }
    }
  }
  function addSubmit(parent, value) {
    var button = document.createElement('button'); button.className = 'btn btn-primary focusable answer-submit';
    button.setAttribute('data-action', 'submit'); button.textContent = value; parent.appendChild(button);
  }
  function choose(index) {
    // 保护期只丢弃选项选择和提交，朗读、输入等其他操作仍可使用。
    if (state.busy || !state.round || questionInputBlocked()) return;
    if (state.round.question.type === 'multiple') {
      var pos = state.selected.indexOf(index);
      if (pos < 0) state.selected.push(index); else state.selected.splice(pos, 1);
      all('#qOptions [data-opt]').forEach(function (el) {
        el.classList.toggle('selected', state.selected.indexOf(+el.getAttribute('data-opt')) >= 0);
      });
      // 多选只切换选中状态，保留当前焦点，选完后由用户移到提交按钮。
    } else { state.selected = [index]; submit(); }
  }
  function submit() {
    if (state.busy || !state.round || questionInputBlocked()) return;
    var round = state.round, item = round.question, written = $('writtenAnswer');
    if (written ? (!written.value.trim() && !state.draft.drawing) : !state.selected.length) { toast('请先填写或选择答案'); return; }
    state.busy = true;
    request('/api/quiz/answer', { roundId: round.id, nonce: round.nonce, selections: state.selected, text: written ? written.value : '', drawing: state.draft ? state.draft.drawing : '' }, function (error, result) {
      if (error) { state.busy = false; toast(error); return; }
      if (result.pending) { state.busy = false; pending(result.reviewId); return; }
      if (result.blocked) { state.busy = false; lock(result.blocked); return; }
      // 家长刚删掉这道题时后端返回可恢复结果：重新取题，不要停在旧题上
      if (result.recovered) {
        hideFeedback(); state.draft = null;
        if (result.quizEmpty) return quizEmpty(result.quizEmptyMessage);
        state.busy = false; start(); return;
      }
      if (!result.correctIndices) { state.busy = false; hideFeedback(); start(); return; }
      state.learning = result.status; pill();
      all('#qOptions [data-opt]').forEach(function (el) {
        var i = +el.getAttribute('data-opt');
        if (result.correctIndices.indexOf(i) >= 0) el.className += ' right';
        else if (state.selected.indexOf(i) >= 0) el.className += ' wrong';
      });
      state.feedback = result;
      if (result.pointsEarned > 0) toast('+' + result.pointsEarned + ' ⭐');   // 星星屋积分
      showFeedback(result, item, written);
      var auto = +state.config.autoNextSeconds || 0;
      if (auto > 0) nextTimer = window.setTimeout(proceed, Math.max(auto, result.status.feedbackSeconds || 0) * 1000 + 200);
    });
  }
  var PRAISE = ['太棒了！', '答对啦！', '完全正确！', '真厉害！', '好样的，继续保持！', '漂亮！你越来越熟练了'];
  var ENCOURAGE = ['没关系，记住正确答案就好', '差一点点，下一题加油！', '错误也是学习的一部分', '别灰心，再接再厉！'];
  function phrase(list, fallback) {
    var pool = (list && list.length ? list : fallback).filter(function (v) { return v && String(v).trim(); });
    if (!pool.length) pool = fallback;
    return String(pool[Math.floor(Math.random() * pool.length)]);
  }
  // 答题结果显示在题目下方（参考多邻国），孩子看完后自己按“继续”进入下一题（Issue #3）
  function showFeedback(result, item, written) {
    var box = $('qFeedback'), c = state.config, right = !!result.correct;
    var title = right ? phrase(c.praisePhrases, PRAISE) : phrase(c.encouragePhrases, ENCOURAGE);
    if (right && state.round && state.round.mode !== 'practice' && result.next && result.next.streak >= 3) title = '连续答对 ' + result.next.streak + ' 题！' + title;
    var answer = '';
    if (!right) {
      if (written) answer = result.answerText;
      else answer = (result.correctIndices || []).map(function (i) { return String.fromCharCode(65 + i) + '. ' + (item.a || [])[i]; }).join('　');
    }
    box.className = 'feedback show ' + (right ? 'is-right' : 'is-wrong');
    $('fbIcon').textContent = right ? '✓' : '!';
    $('fbTitle').textContent = title;
    $('fbAnswer').textContent = answer ? '正确答案：' + answer : '';
    $('fbTip').textContent = result.tip ? '解析：' + result.tip : '';
    // 「题目、选项和答案」：作答之后再读正确答案，作答前不剧透
    if (state.speech && state.speech.autoRead && readScope() === 'questionOptionsAnswer') {
      var spokenAnswer = written ? speakable(result.answerText || '')
        : (result.correctIndices || []).map(function (i) { return String.fromCharCode(65 + i) + '、' + speakable((item.a || [])[i]); }).join('，');
      if (spokenAnswer) nativeCall('readInLanguage', ['正确答案：' + spokenAnswer, item.language || '']);
    }
    var go = $('fbNext');
    state.feedbackUntil = Date.now() + ((result.status && result.status.feedbackSeconds) || 0) * 1000;
    go.textContent = result.finished ? '查看结果' : '继续';
    updateFeedbackClock();
    go.className = 'btn ' + (right ? 'btn-primary' : 'btn-danger') + ' focusable';
    if (window.SOTV_MATH) { window.SOTV_MATH($('fbAnswer')); window.SOTV_MATH($('fbTip')); }
    // 选项已经作答，焦点只留在“继续”上（页脚的朗读也一起停用，否则方向键会落到反馈条后面）
    all('#qOptions .focusable').forEach(function (el) { el.classList.remove('focusable'); el.classList.add('answered'); });
    var readAloudBtn = $('readAloudBtn');
    if (readAloudBtn) readAloudBtn.classList.remove('focusable');
    var list = focusables(); for (var i = 0; i < list.length; i++) if (list[i] === go) { setFocus(i); break; }
    if (!isTv()) go.focus();
  }
  function hideFeedback() { $('qFeedback').className = 'feedback'; state.feedback = null; }
  function updateFeedbackClock() {
    if (!state.feedback) return;
    var seconds = Math.max(0, Math.ceil(((state.feedbackUntil || 0) - Date.now()) / 1000)), go = $('fbNext');
    go.disabled = seconds > 0;
    go.textContent = seconds > 0 ? '查看解析 · ' + seconds + ' 秒' : state.feedback.finished ? '查看结果' : '继续';
  }
  window.setInterval(updateFeedbackClock, 500);
  function proceed() {
    var result = state.feedback;
    if (!result || state.continuing || (state.feedbackUntil || 0) > Date.now()) return;
    state.continuing = true;
    request('/api/quiz/continue', {}, function (error, response) {
      state.continuing = false;
      if (error) { toast(error); return; }
      result.status = response.status; state.learning = response.status;
      proceedReady(result);
    });
  }
  function proceedReady(result) {
    if (!result) return;
    window.clearTimeout(nextTimer);
    hideFeedback();
    state.busy = false;
    if (state.screen !== 'quiz') return;
    if (result.finished) {
      state.round = null; clearRound();
      if (result.passed) {
        if (result.status.reason) { lock(result.status.reason); toast('奖励已保存，达到可用时段后使用'); return; }
        var reward = Math.round((result.rewardMinutes || 0) * 10) / 10;
        show('lock'); $('lockTitle').textContent = '任务完成'; icon($('lockIcon'), 'check');
        $('lockDesc').textContent = reward > 0 ? '已解锁 ' + reward + ' 分钟' : '今天的时间额度已经领满，先使用已有的时间吧'; var pb = result.status.points;
        $('lockNote').textContent = pb && pb.enabled && pb.balance >= 0 ? '星星屋积分 ' + pb.balance + '（今天已得 ' + pb.today + '）' : '';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="resume">开始使用</button>';
        focusDefault();
        // 解锁后回到解题前正在看的应用，不要跳回桌面（#68 / #74）
        window.setTimeout(function () { if (state.screen === 'lock') nativeCall('resumePrevious'); }, 2500);
      } else { toast('本组未通过，再试一组'); start(); }
    } else if (result.next) { state.round = result.next; renderQuestion(); }
    else quizEmpty(result.quizEmptyMessage);
  }
  function reviewed(result) {
    state.awaiting = false; state.reviewed = true; state.review = result;
    show('lock'); icon($('lockIcon'), result.result.correct ? 'check' : 'pencil');
    $('lockTitle').textContent = '批改完成 · ' + result.score + ' 分';
    $('lockDesc').textContent = result.feedback || '已保存本次批改结果';
    $('lockNote').textContent = result.result.correct ? '本题通过' : '查看反馈后继续练习';
    $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="review-continue">' + (result.result.finished && result.result.passed ? '继续使用' : '继续答题') + '</button>';
    if (window.SOTV_MATH) window.SOTV_MATH($('lockDesc'));
    focusDefault();
  }
  function pending(id) {
    state.awaiting = true; state.reviewed = false;
    show('lock'); icon($('lockIcon'), 'check'); $('lockTitle').textContent = '答案已保存';
    $('lockDesc').textContent = '等待家长或 AI 批改，通过后继续任务';
    $('lockNote').textContent = '';
    $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="retry">查看批改结果</button><button class="btn btn-ghost focusable" data-action="skip-review">先跳过这题</button>';
    focusDefault();
  }
  function lock(reason) {
    state.lockReason = reason;
    show('lock'); state.round = null; clearRound();
    if (reason === 'feedback') { start(); return; }
    if (reason === 'rest') {
      icon($('lockIcon'), 'clock'); $('lockTitle').textContent = '休息一下';
      $('lockDesc').textContent = '还需休息 ' + Math.ceil((state.learning.restSeconds || 0) / 60) + ' 分钟';
      $('lockNote').textContent = '休息结束后继续原有学习计划，余额和每日额度不会重置'; $('lockActions').innerHTML = ''; focusDefault(); return;
    }
    if (reason === 'settings') {
      icon($('lockIcon'), 'hourglass');
      $('lockTitle').textContent = '管控期间暂不能打开系统设置';
      $('lockDesc').textContent = '家长验证后可临时进入，或返回学习页继续。';
      $('lockNote').textContent = '';
      $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="return-learning">返回学习页</button>';
      appendUnlockButton();
      focusDefault(); return;
    }
    if (reason === 'locked') {
      var ov = state.learning.override || {};
      icon($('lockIcon'), 'lock');
      $('lockTitle').textContent = '家长已暂停使用';
      $('lockDesc').textContent = ov.remainingSeconds > 0 ? left2(ov.remainingSeconds) + '后自动恢复' : '需要使用时，请找家长解锁';
      $('lockNote').textContent = '';
      $('lockActions').innerHTML = '';
      appendUnlockButton();
      focusDefault(); return;
    }
    icon($('lockIcon'), reason === 'schedule' ? 'clock' : 'hourglass');
    $('lockTitle').textContent = reason === 'schedule' ? '还没到可用时间' : reason === 'rounds' ? '今天的任务已全部完成' : reason === 'quota' ? '今天的使用时间已用完' : '使用时间到了';
    var when = '';
    if (state.learning.nextAvailableAt) {
      // 服务端按家长设置的时区算好文案，设备时区不对时也能和规则对得上
      when = state.learning.nextAtText || '';
      if (!when) {
        var date = new Date(state.learning.nextAvailableAt);
        when = (date.getMonth() + 1) + ' 月 ' + date.getDate() + ' 日 ' + ('0' + date.getHours()).slice(-2) + ':' + ('0' + date.getMinutes()).slice(-2);
      }
    }
    $('lockDesc').textContent = when ? '下次可用：' + when : reason === 'timeup' ? '完成学习任务后才能使用设备' : '现在暂不可用';
    $('lockNote').textContent = state.config.allDay ? '' : (scheduleText() ? '可用时段 ' + scheduleText() : '');
    if (reason === 'timeup' && (state.children || []).length > 1) {
      // 多个孩子时用「开始学习 · 名字」代替「开始答题」：谁开始就记在谁头上
      $('lockActions').innerHTML = '';
      appendChildChooser(true);
      $('lockNote').textContent = '选择自己的名字开始，进度和使用时间按人分开记录';
    } else {
      $('lockActions').innerHTML = reason === 'timeup' ? '<button class="btn btn-primary focusable" data-action="start-quiz">开始答题</button>' : '';
    }
    appendUnlockButton();
    focusDefault();
  }
  /**
   * 锁定页的「家长验证解锁」（Issue #201）：家长直接用遥控器输密码临时解锁 30 分钟，
   * 不必再切到手机面板。只有家长在设置里打开 allowQuizUnlock 才显示，默认关闭。
   */
  function appendUnlockButton() {
    if (!state.config.allowQuizUnlock || !canUnlock()) return;
    var button = document.createElement('button');
    button.className = 'btn btn-ghost focusable';
    button.setAttribute('data-action', 'parent-unlock');
    button.textContent = '家长验证解锁';
    $('lockActions').appendChild(button);
  }
  /**
   * 有多个孩子时，先问一句「是谁在学」再开始：每个人的进度、额度、历史都是分开的。
   * 只在每次打开学习页时问一次，同一轮答题不会反复打断。
   */
  function appendChildChooser(start) {
    (state.children || []).forEach(function (kid) {
      var button = document.createElement('button');
      button.className = 'btn ' + (kid.active ? 'btn-primary' : 'btn-ghost') + ' focusable';
      button.setAttribute('data-action', 'start-as-child');
      button.setAttribute('data-child', kid.id);
      button.textContent = start ? '开始学习 · ' + kid.name : kid.name;
      $('lockActions').appendChild(button);
    });
  }
  function chooseChild() {
    state.lockReason = state.learning.reason || 'timeup';   // 让 2 秒轮询不要把它冲掉
    state.wasFree = !!state.learning.free;
    show('lock'); icon($('lockIcon'), 'pencil');
    $('lockTitle').textContent = '是谁在学？';
    $('lockDesc').textContent = '每个孩子单独记录学习进度和使用时间';
    $('lockActions').innerHTML = '';
    appendChildChooser(false);
    $('lockNote').textContent = '选自己的名字开始；家长可以在「设置 → 孩子」里添加或改名';
    focusDefault();
  }
  // 家长暂停了管控（比如大人要看电视）：拦截页换成提示，可以直接回桌面
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
  // 深色 / 浅色 / 跟随系统（电视系统的深色模式设置）
  function applyTheme(name) {
    if (name === 'light' || name === 'daylight') name = 'light';
    else if (name === 'system') { var info = {}; try { info = JSON.parse(nativeCall('deviceInfo') || '{}'); } catch (e) {} name = info.night === false ? 'light' : 'dark'; }
    else name = 'dark';
    if (document.documentElement.getAttribute('data-mode') === name) return;
    document.documentElement.setAttribute('data-mode', name);
    try { window.localStorage.setItem('sotv_mode', name); } catch (e) {}
  }
  // 用内联 SVG 代替 ⌛ 等字符：很多电视的字体里没有这些符号，会显示成空白
  var ICONS = {
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    hourglass: '<path d="M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    pencil: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>'
  };
  function icon(el, name) {
    el.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + ICONS[name] + '</svg>';
  }
  function isTv() { return document.documentElement.classList.contains('tv-device'); }
  function focusables() {
    var screen = document.querySelector('.screen.active');
    var list = screen ? Array.prototype.slice.call(screen.querySelectorAll('.focusable')) : [];
    // 右上角更新提示也要能用遥控器选中
    if ($('updateTip').className.indexOf('show') >= 0) list = list.concat(all('#updateTip .focusable'));
    return list.filter(function (el) { return el.offsetWidth > 0 || el.offsetHeight > 0; });
  }
  function reveal(el) {
    // 旧版电视 WebView 不支持 scrollIntoView({block}) 参数，手动滚动到可见
    var r = el.getBoundingClientRect(), h = window.innerHeight || document.documentElement.clientHeight;
    var stage = $('stage');
    // #141：电视上 body 不滚动，放不下时滚动的是 #stage；只为 #stage 滚动会失效
    if (stage && stage.scrollHeight > stage.clientHeight + 1) {
      var s = stage.getBoundingClientRect();
      if (r.top < s.top + 12) stage.scrollTop += r.top - s.top - 12;
      else if (r.bottom > s.bottom - 12) stage.scrollTop += r.bottom - s.bottom + 12;
      return;
    }
    if (r.top < 0) window.scrollBy(0, r.top - 24); else if (r.bottom > h) window.scrollBy(0, r.bottom - h + 24);
  }
  function setFocus(index) {
    var list = focusables();
    all('.focused').forEach(function (el) { el.classList.remove('focused'); });
    if (list[index]) { list[index].classList.add('focused'); reveal(list[index]); state.focusKey = focusKey(list[index]); }
  }
  function focusKey(el) { return el.getAttribute('data-opt') !== null ? 'opt' + el.getAttribute('data-opt') : el.getAttribute('data-action') || el.id || ''; }
  function focusDefault() { if (isTv()) setFocus(0); }
  // 返回 true 表示焦点移动了；false 表示已到边缘（原生层据此判断“在最上方连按 ↑”打开家长入口）
  function move(dir) {
    var list = focusables(), index = -1, best = -1, score = 1e12, bestAligned = false;
    list.forEach(function (el, i) { if (el.classList.contains('focused')) index = i; });
    if (index < 0) { if (!list.length) return false; setFocus(0); return true; }
    var current = list[index];
    // 多选最后一行按下固定到提交；offsetTop 不受焦点放大和页面滚动影响。
    if (dir === 'down' && state.screen === 'quiz' && state.round && state.round.question.type === 'multiple' && current.hasAttribute('data-opt')) {
      var lastRow = true, submitIndex = -1;
      list.forEach(function (el, i) {
        if (el.hasAttribute('data-opt') && el.offsetTop > current.offsetTop) lastRow = false;
        if (el.getAttribute('data-action') === 'submit') submitIndex = i;
      });
      if (lastRow && submitIndex >= 0) { setFocus(submitIndex); return true; }
    }
    var rect = current.getBoundingClientRect();
    list.forEach(function (el, i) {
      if (i === index) return;
      var r = el.getBoundingClientRect(), dx = r.left + r.width / 2 - rect.left - rect.width / 2, dy = r.top + r.height / 2 - rect.top - rect.height / 2;
      var primary = dir === 'left' ? -dx : dir === 'right' ? dx : dir === 'up' ? -dy : dy;
      var secondary = dir === 'left' || dir === 'right' ? Math.abs(dy) : Math.abs(dx);
      // 优先同一行 / 列的控件，避免下方横跨两列的提交按钮抢走左右导航。
      var aligned = dir === 'left' || dir === 'right' ? r.top < rect.bottom && r.bottom > rect.top : r.left < rect.right && r.right > rect.left;
      var distance = primary + secondary * 1.8;
      if (primary > 8 && ((aligned && !bestAligned) || (aligned === bestAligned && distance < score))) {
        score = distance; best = i; bestAligned = aligned;
      }
    });
    if (best >= 0) { setFocus(best); return true; }
    return false;
  }
  function activate(el) {
    if (!el) el = document.querySelector('.screen.active .focused') || document.querySelector('.focused');
    if (!el) return;
    if (el.hasAttribute('data-opt')) return choose(+el.getAttribute('data-opt'));
    var action = el.getAttribute('data-action');
    if (action === 'submit') submit();
    if (action === 'next') proceed();
    if (action === 'review-continue' && !state.busy) {
      state.busy = true;
      request('/api/quiz/review', { reviewId: state.review.reviewId }, function (error, result) {
        state.busy = false;
        if (error) { toast(error); return; }
        var finished = state.review.result.finished && state.review.result.passed;
        state.reviewed = false;
        if (finished && !result.status.reason && result.status.remainingSeconds > 0) nativeCall('resumePrevious');
        else start();
      });
    }
    if (action === 'voice') nativeCall('answerVoice', [state.draft ? state.draft.text : '', state.round ? state.round.question.q : '', state.round ? state.round.question.language || '' : '']);
    if (action === 'handwrite') nativeCall('answerInput', [false]);
    if (action === 'choose-level') start(el.getAttribute('data-level'));
    if (action === 'type-answer' && !state.busy) nativeCall('answerText', [state.draft ? state.draft.text : '', state.round ? state.round.question.q : '']);
    if (action === 'update-go') { var u = window.currentUpdate; if (u) nativeCall('installUpdate', [u.url, u.latest, u.notes || '']); }
    if (action === 'update-close') { $('updateTip').className = 'update-tip'; try { if (window.currentUpdate) window.localStorage.setItem('sotv_update_dismissed', window.currentUpdate.latest); } catch (e) {} if (el.classList.contains('focused')) focusDefault(); }
    if (action === 'parent-unlock') {
      if (!canUnlock()) { toast('当前设备不支持密码解锁'); return; }
      state.unlockPending = true;
      // 原生页没打开 / 用户一直没提交时不能让标记一直挂着，否则下一次答题输入会被当成密码
      window.setTimeout(function () { state.unlockPending = false; }, 180000);
      nativeCall('askParentPassword');
    }
    if (action === 'permissions' || action === 'password') nativeCall(action === 'permissions' ? 'openPermissions' : 'changePassword');
    if (action === 'clear-drawing') { state.draft.drawing = ''; renderQuestion(); }
    if (action === 'watch-lesson') nativeCall('watchLesson');
    if (action === 'play-audio' && state.round) nativeCall('playAudio', [state.round.question.audio || '']);
    if (action === 'read-aloud') nativeCall('readInLanguage', [state.round ? readText(state.round.question) : '', state.round ? state.round.question.language || '' : '']);
    // retry 用于错误 / 空题库的重试，start-quiz 用于「时间到了」页的「开始答题」；两者都重新取题
    if (action === 'retry' || action === 'start-quiz') start();
    if (action === 'start-as-child' && !state.busy) {
      var kidId = el.getAttribute('data-child');
      var wantStart = !state.chosen || state.lockReason === 'timeup';
      state.chosen = true;
      if (kidId === state.activeChild) { if (wantStart) start(); return; }
      state.busy = true;
      request('/api/children/switch', { id: kidId }, function (error, result) {
        state.busy = false;
        if (error) { toast(error); return; }
        state.children = result.items || []; state.activeChild = result.active || ''; pill();
        // 点名字就是为了开始：切换成功后直接开这一轮，不必再点一次
        if (wantStart) start(); else route();
      });
    }
    if (action === 'skip-review') { request('/api/quiz/skip', {}, function (error, result) { if (error) { toast(error); return; } state.awaiting = false; state.reviewed = false; state.busy = false; start(); }); }
    if (action === 'return-learning') nativeCall('backToQuiz');
    if (action === 'home') nativeCall('exitToLauncher');
    if (action === 'resume') nativeCall('resumePrevious');
    if (action === 'quiz') { if (state.screen === 'pair') nativeCall('backToQuiz'); else start(); }
  }
  function route() {
    window.clearTimeout(nextTimer); state.busy = false; hideFeedback();
    var hash = location.hash || '#/quiz';
    state.settingsGate = hash.indexOf('reason=settings') >= 0;
    if (hash.indexOf('#/pair') === 0) return pair();
    loadState(function (error) {
      if (error) { $('qText').textContent = error; return; }
      // 答题途中被系统清掉再打开：服务端还停在同一题、当前也确实该答题时，恢复原来那组
      if (!state.settingsGate && hash.indexOf('#/lock') !== 0 && !state.learning.reason && !state.learning.free &&
          !state.learning.awaitingReview && !((state.children || []).length > 1 && !state.chosen)) {
        var restored = savedRound();
        if (restored) {
          // 同一会话里切走再回来（SOTV_RESUME）不提示；只有真的重启后恢复才提示
          var freshStart = !state.round || state.round.nonce !== restored.nonce;
          state.round = restored;
          show('quiz'); renderQuestion();
          if (freshStart) toast('已恢复到刚才的题目');
          return;
        }
      }
      if (state.settingsGate) {
        if (state.config.blockSettingsDuringControl !== false && !state.learning.free) lock('settings');
        else { state.settingsGate = false; start(); }
      } else if (hash.indexOf('#/lock') === 0 && !state.learning.free) lock(state.learning.reason || 'timeup');
      else if ((state.children || []).length > 1 && !state.chosen) chooseChild();
      else start();
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
    // 只有家长页 / 实体键盘会走到这里（学习页的遥控器按键由原生层转发给 window.TV）
    document.addEventListener('keydown', function (event) {
      if (event.target.tagName === 'INPUT' || (event.target.tagName === 'TEXTAREA' && !event.target.readOnly)) return;
      var dir = {37:'left',38:'up',39:'right',40:'down'}[event.keyCode];
      if (dir) {
        var moved = move(dir);
        if (dir === 'up' && !moved && !event.repeat && state.screen !== 'pair') {
          var now = Date.now(); upTaps = now - lastUpTap < 900 ? upTaps + 1 : 1; lastUpTap = now;
          if (upTaps >= 5) { upTaps = 0; nativeCall('openParent'); }
        } else if (!event.repeat) upTaps = 0;
      } else if (event.keyCode === 13) { if (!event.repeat) activate(); } else return;
      event.preventDefault();
    });
    var info = nativeCall('deviceInfo');
    if (info && JSON.parse(info).platform === 'android-tv') document.documentElement.classList.add('tv-device');
    else if (info) { var analytics = document.createElement('script'); analytics.src = 'assets/js/analytics.js'; analytics.async = true; document.head.appendChild(analytics); }
    // 先用上次的主题，避免加载配置前闪一下默认配色
    try { applyTheme(window.localStorage.getItem('sotv_mode')); } catch (e) { applyTheme('dark'); }
    // 启动时显示已缓存的更新信息（原生层在页面加载前拿到结果时，回调会丢失）
    try { var cached = nativeCall('updateInfo'); if (cached) window.SOTV_UPDATE(JSON.parse(cached)); } catch (e) {}
    route();
    window.setInterval(function () {
      $('clock').textContent = new Date().toTimeString().slice(0, 5);
      if (state.screen === 'pair') return;
      // 题目提交中：等作答响应回来再轮询，否则 start() 可能把还在途中的提交状态重置掉
      if (state.busy && !state.feedback) return;
      var version = state.config.version, quizGeneration = state.learning.quizGeneration || 0, wasFree = !!state.learning.free;
      loadState(function (error) {
        if (error) return;
        if (state.screen === 'lock' && !state.lockReason && !state.learning.reason && state.learning.remainingSeconds > 0)
          $('lockDesc').textContent = '还可以使用 ' + Math.ceil(state.learning.remainingSeconds / 60) + ' 分钟（停在本页不计时，离开去看电视后才开始计时）';
        if (quizGeneration !== (state.learning.quizGeneration || 0)) {
          window.clearTimeout(nextTimer); state.busy = false; state.awaiting = false; state.reviewed = false;
          state.round = null; hideFeedback(); start(); return;
        }
        if (state.settingsGate) {
          if (!state.learning.free && state.config.blockSettingsDuringControl !== false) return;
          state.settingsGate = false; start(); return;
        }
        if (wasFree !== !!state.learning.free && state.config.playMode !== 'practice') {
          window.clearTimeout(nextTimer); state.busy = false; state.awaiting = false; state.reviewed = false;
          hideFeedback(); start(); return;
        }
        var ovKey = state.learning.override ? state.learning.override.mode + state.learning.override.at : '';
        if (ovKey !== (state.ovKey || '')) {
          state.ovKey = ovKey;
          window.clearTimeout(nextTimer); state.busy = false; state.awaiting = false; state.reviewed = false;
          hideFeedback(); start(); return;
        }
        if (state.feedback) { state.feedbackUntil = Date.now() + (state.learning.feedbackSeconds || 0) * 1000; updateFeedbackClock(); }
        if (state.screen === 'lock' && state.learning.reason === 'rest') { lock('rest'); return; }
        if (state.reviewed || state.feedback) return;
        if (state.awaiting) { if (!state.learning.awaitingReview) start(); return; }
        if (state.screen === 'lock' && (state.lockReason !== state.learning.reason || state.wasFree !== !!state.learning.free)) {
          state.wasFree = !!state.learning.free;
          if (!state.learning.reason) start(); else lock(state.learning.reason);
          return;
        }
        if (state.screen === 'quiz' && state.learning.reason && ['quota','rounds','schedule','rest','feedback'].indexOf(state.learning.reason) >= 0 && state.config.playMode !== 'practice') lock(state.learning.reason);
        else if (version !== state.config.version && state.screen === 'quiz') start();
      });
    }, 2000);
  }
  window.SOTV_INPUT = function (result, token) {
    // 原生侧靠这个标记确认注入成功，失败会重试（部分电视 WebView 切回来时 JS 尚未就绪）
    if (token) window.__sotvAck = token;
    // 家长解锁：回传的是密码，不能写进答题草稿
    if (state.unlockPending) {
      state.unlockPending = false;
      var pass = (result && result.text) || '';
      if (!pass) { toast('已取消解锁'); return; }
      var reply = null;
      try { reply = JSON.parse(nativeCall('quizUnlock', [pass]) || 'null'); } catch (e) {}
      if (reply && reply.ok) {
        if (reply.status) state.learning = reply.status;
        state.lockReason = '';
        toast('已临时解锁 30 分钟');
        nativeCall('resumePrevious');
        route();
        return;
      }
      toast(reply && reply.error === 'wrong' ? '密码错误，连续错误 5 次后需等待一分钟' : '解锁失败，请确认家长已在设置里开启');
      focusDefault();
      return;
    }
    if (!state.round) { window._pendingInput = result; return; }
    state.draft = { nonce: state.round.nonce, text: result.text || '', drawing: result.drawing || '' };
    window._pendingInput = null;
    renderQuestion();
    // 遥控器输入完成后直接把焦点放到“提交答案”
    if (isTv() && state.draft.text) { var list = focusables(); for (var i = 0; i < list.length; i++) if (list[i].getAttribute('data-action') === 'submit') { setFocus(i); break; } }
  };
  window.SOTV_UPDATE = function (info) { if (!info || !info.latest) return; window.currentUpdate = info;
    try { if (!info.force && window.localStorage.getItem('sotv_update_dismissed') === info.latest) return; } catch (e) {} $('updateText').textContent = '发现新版本 v' + info.latest; $('updateTip').className = 'update-tip show'; };
  // 朗读失败（没有系统语音、云端 Key 不对、语言不支持…）直接显示在电视上，不能再「点了没声音」
  window.SOTV_SPEECH_NOTICE = function (info) { if (!info || info.ok !== false) return; toast(String(info.message || '朗读失败，请重试')); };
  window.SOTV_NAVIGATION_BLOCKED = function (message) { toast(message || '现在暂不可用'); return true; };
  window.TV = { move: move, select: function () { activate(); }, back: function () { if (state.learning.canExit === true) nativeCall('exitToLauncher'); else if (nativeCall('navigationBlocked') !== true) window.SOTV_NAVIGATION_BLOCKED(); }, state: state };
  window.SOTV_RESUME = function () { if (document.readyState === 'complete' && !state.busy && state.screen !== 'pair') route(); };
  window.addEventListener('hashchange', route);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
