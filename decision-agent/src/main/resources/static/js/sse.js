/**
 * SSE 订阅封装（Task 5.2）：
 * - EventSource onerror 后指数退避自动重连（1s 起、每次 ×2、上限 30s）
 * - 收到任意事件（含 10s 心跳）即视为链路健康，重置退避
 * - 重连后 EventPublisher 会先回放全部历史事件（SQLite 双写），断线期间错过的
 *   更新自动补齐——页面侧需按事件指纹去重（见 session.js 的渲染幂等处理）
 *
 * 用法：
 *   const sse = createReconnectingEventSource('/api/events/xxx', {
 *     'collect:question': (data, ev) => { ... },
 *     done: (data, ev) => { ... sse.close(); },
 *   }, { onState: (state, info) => { /* connecting | online | backoff *\/ } });
 */
(function () {
  'use strict';

  var RETRY_BASE_MS = 1000;
  var RETRY_MAX_MS = 30000;

  function createReconnectingEventSource(url, handlerMap, opts) {
    opts = opts || {};
    var es = null;
    var attempt = 0;   // 连续失败次数（成功收到事件后清零）
    var closed = false; // 终态后主动关闭，不再重连
    var timer = null;

    function notify(state, info) {
      if (typeof opts.onState === 'function') opts.onState(state, info);
    }

    function connect() {
      notify('connecting');
      es = new EventSource(url);
      Object.keys(handlerMap).forEach(function (type) {
        es.addEventListener(type, function (ev) {
          attempt = 0; // 有事件到达 = 连接健康，重置退避
          var data = {};
          try { data = JSON.parse(ev.data); } catch (ignored) { /* 容忍非 JSON 载荷 */ }
          handlerMap[type](data, ev);
        });
      });
      es.onerror = function () {
        if (closed) return; // 终态主动关闭触发的 error 不再重连
        try { es.close(); } catch (ignored) { }
        // 指数退避：1s → 2s → 4s → 8s → 16s → 30s（封顶）
        var delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS * Math.pow(2, attempt));
        attempt += 1;
        notify('backoff', { delay: delay, attempt: attempt });
        timer = setTimeout(connect, delay);
      };
    }

    connect();

    return {
      close: function () {
        closed = true;
        if (timer) { clearTimeout(timer); timer = null; }
        if (es) { try { es.close(); } catch (ignored) { } }
      },
    };
  }

  window.createReconnectingEventSource = createReconnectingEventSource;
})();
