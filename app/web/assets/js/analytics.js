(function () {
  'use strict';

  var CONFIG = {
    ga4Id: 'G-561826488',
    clarityId: 'z7790sezw1',
    enabled: true,
    debug: false
  };

  function extend(target, source) {
    var out = {};
    var key;
    if (target) {
      for (key in target) {
        if (Object.prototype.hasOwnProperty.call(target, key)) out[key] = target[key];
      }
    }
    if (source) {
      for (key in source) {
        if (Object.prototype.hasOwnProperty.call(source, key)) out[key] = source[key];
      }
    }
    return out;
  }

  var queue = [];
  window.SOTV_TRACK = function (name, params) {
    var event = { name: name, params: params || {}, ts: Date.now() };
    if (CONFIG.debug || !CONFIG.enabled || CONFIG.ga4Id.indexOf('XXXX') > -1) {
      if (CONFIG.debug && window.console && console.log) { console.log('[track]', event); }
      return;
    }
    try {
      if (window.gtag) {
        window.gtag('event', name, extend({ page: location.pathname }, event.params));
      }
      if (window.clarity) {
        window.clarity('event', name);
      }
    } catch (e) {
      if (queue.length < 50) queue.push(event);
    }
  };

  if (CONFIG.enabled && CONFIG.ga4Id.indexOf('XXXX') < 0) {
    var ga = document.createElement('script');
    ga.async = true;
    ga.src = 'https://www.googletagmanager.com/gtag/js?id=' + CONFIG.ga4Id;
    document.head.appendChild(ga);
    window.dataLayer = window.dataLayer || [];
    window.gtag = function () { window.dataLayer.push(arguments); };
    window.gtag('js', new Date());
    window.gtag('config', CONFIG.ga4Id, { send_page_view: true });
  }

  if (CONFIG.enabled && CONFIG.clarityId.indexOf('abcdef') < 0) {
    (function (c, l, a, r, i, t, y) {
      c[a] = c[a] || function () { (c[a].q = c[a].q || []).push(arguments); };
      t = l.createElement(r); t.async = 1; t.src = 'https://www.clarity.ms/tag/' + i;
      y = l.getElementsByTagName(r)[0]; y.parentNode.insertBefore(t, y);
    })(window, document, 'clarity', 'script', CONFIG.clarityId);
  }

  document.addEventListener('DOMContentLoaded', function () {
    window.SOTV_TRACK('page_view', {
      page: location.pathname,
      title: document.title,
      ua_mobile: /Mobi|Android/i.test(navigator.userAgent)
    });
  });

  window.addEventListener('error', function (e) {
    window.SOTV_TRACK('js_error', { message: String(e.message).slice(0, 120) });
  });

  window.SOTV_ANALYTICS_CONFIG = CONFIG;
})();
