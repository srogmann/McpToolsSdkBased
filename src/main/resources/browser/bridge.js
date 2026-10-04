/*
 * __bridge.js — the bridge between the browser and the browser proxy (M1: fill, click, undo;
 * submits are gated in the tool, not here. M2 adds the field identity and the value pass - both only
 * when a command asks for them, so an M1 session runs exactly as before).
 *
 * It hangs in every proxied document and is where the server has no chance: the browser knows how to
 * render, what a field really contains and when the page is finished. The bridge does exactly four
 * things:
 *
 *   1. Patches  — fetch/XHR/WebSocket/EventSource/sendBeacon/history: needed in path mode because the
 *                 application builds root-absolute paths ("/api/x") that would run past the session
 *                 prefix without us. Which is why the bridge MUST run before the application code — the
 *                 rewriter puts it in as the first script in <head>.
 *   2. Snapshot — an accessibility-like view of the DOM, including frames (same-origin after the
 *                 rewrite, therefore traversable), with refs, labels, values and a locator bundle.
 *   3. Quiescence — "the page is finished": no requests in flight AND no mutation any more
 *                 AND two framed animation steps. Without this term every snapshot would be a ride on a
 *                 half-built grid.
 *   4. Events — what user and page do, as a stream to the server (and thereby to the LLM).
 *   5. Value pass — the writable fields read as a keyed map, once before and once after a write.
 *                 What changed although nobody wrote into it, the application computed (M2).
 *
 * No inline script in the document, no global name but __mcpb, no visible DOM node but our own badge in
 * the shadow DOM.
 */
(function () {
  'use strict';

  var script = document.currentScript;
  var params = new URLSearchParams(script ? script.src.slice(script.src.indexOf('?') + 1) : '');
  var session = params.get('s') || '';
  var expectVersion = parseInt(params.get('v') || '1', 10);

  // Secure this before every patch: the bridge itself talks over the original APIs.
  var nativeFetch = window.fetch ? window.fetch.bind(window) : null;

  var CONFIG = null;          // channel base from the script URL ("a"), complete via hello
  var REFS = new WeakMap();   // element -> ref of the last snapshot (full address, frame included)
  var KEYS = new WeakMap();   // element -> field key of the last snapshot (M2, stable across revisions)
  var REF_ELEMENTS = new Map(); // ref -> element (for scroll and, from M1 on, for set/click)
  var inflight = 0;
  var lastMutation = Date.now();
  var stopped = false;
  var paused = false;
  // Set the moment the document starts to leave: a click that navigates must not answer with a value
  // diff of the page that is on its way out (it is true but worthless, and it costs the 1.5 s of
  // quiescence the user feels as a stall).
  var leaving = false;
  var framePath = 'f0';
  var isTop = (function () { try { return window.top === window; } catch (e) { return false; } })();

  // ---------------------------------------------------------------- utilities

  function trimText(value, max) {
    if (value == null) { return ''; }
    var text = String(value).replace(/\s+/g, ' ').trim();
    var limit = max || 90;
    return text.length > limit ? text.slice(0, limit) + '…' : text;
  }

  function normalizeKey(name) {
    return String(name || '').toLowerCase().replace(/[^a-z0-9]/g, '');
  }

  function isSecretKey(name) {
    var key = normalizeKey(name);
    if (!key || !CONFIG) { return false; }
    for (var i = 0; i < CONFIG.secrets.length; i++) {
      var secret = normalizeKey(CONFIG.secrets[i]);
      if (secret && key.indexOf(secret) >= 0) { return true; }
    }
    return false;
  }

  function post(payload) {
    if (!nativeFetch || !CONFIG) { return Promise.resolve(); }
    return nativeFetch(channelUrl(CONFIG.post), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      credentials: 'same-origin',
      keepalive: false
    }).catch(function (error) { reportError('post: ' + error); });
  }

  /**
   * A channel address of this document, naming the session it was launched for.
   *
   * <p>The id is the one from the script URL - never the one a server might hand out later. That is the
   * whole point: after a restart the sessions live in memory and are new, the open tab is not, and in
   * port mode its poll carries no session in the path at all (the port IS the session). Without this
   * parameter a stale tab writes into the fresh session's log and receives its commands - which breaks
   * the one claim the capture exists for: that a line belongs to the document it says it does.</p>
   */
  function channelUrl(endpoint, extra) {
    if (!endpoint) {
      return endpoint;
    }
    var parts = session ? ['s=' + encodeURIComponent(session)] : [];
    if (extra) {
      parts.push(extra);
    }
    if (!parts.length) {
      return endpoint;
    }
    return endpoint + (endpoint.indexOf('?') < 0 ? '?' : '&') + parts.join('&');
  }

  function reportError(message) {
    if (!nativeFetch || !CONFIG) { return; }
    nativeFetch(channelUrl(CONFIG.post), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ t: 'error', frame: framePath, message: trimText(message, 400) })
    }).catch(function () { /* without a channel there is nothing to do */ });
  }

  // ------------------------------------------------------------- 1. patches
  //
  // rewriteUrl(): bring a URL into our session. Three cases:
  //   /api/x            → {base}/api/x           (root-absolute on our origin)
  //   https://host/x    → {prefix}/x             (registered host, written absolutely)
  //   anything else     → unchanged (real foreign systems deliberately run past us)

  function rewriteUrl(value) {
    if (!CONFIG || value == null) { return value; }
    var raw = typeof value === 'string' ? value : (value && value.toString ? value.toString() : value);
    if (typeof raw !== 'string' || raw === '') { return value; }
    if (/^(data:|blob:|about:|mailto:|tel:|javascript:|#)/i.test(raw)) { return value; }
    if (CONFIG.base && (raw.indexOf(CONFIG.base + '/') === 0 || raw === CONFIG.base)) { return value; }
    // Our own channel (root comes from the script URL, so it is right per mode): never rewrite.
    if (CONFIG.root && raw.indexOf(CONFIG.root + '/') === 0) { return value; }
    var absolute = /^https?:\/\//i.test(raw);
    if (absolute) {
      var map = hostMatch(raw);
      return map ? raw.replace(map.upstream, map.prefix) : value;
    }
    if (raw.charAt(0) === '/') { return CONFIG.base + raw; }
    return value;   // relative: the <base> element takes care of that
  }

  function hostMatch(url) {
    var hosts = CONFIG.hosts || [];
    for (var i = 0; i < hosts.length; i++) {
      var forms = hosts[i].upstreams || [hosts[i].upstream];
      for (var k = 0; k < forms.length; k++) {
        if (forms[k] && url.indexOf(forms[k]) === 0) {
          return { upstream: forms[k], prefix: hosts[i].prefix };
        }
      }
    }
    return null;
  }

  function installPatches() {
    if (window.__mcpb && window.__mcpb.patched) { return; }

    if (nativeFetch) {
      window.fetch = function (input, init) {
        try {
          if (typeof input === 'string' || input instanceof URL) {
            input = rewriteUrl(String(input));
          } else if (input && typeof input.url === 'string' && input.constructor === Request) {
            input = new Request(rewriteUrl(input.url), input);
          }
        } catch (e) { /* exotic input: pass it through as it is */ }
        inflight++;
        lastMutation = Date.now();
        return nativeFetch(input, init).then(function (response) {
          inflight--; return response;
        }, function (error) { inflight--; throw error; });
      };
    }

    var open = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      var patched = rewriteUrl(url);
      this.__mcpbUrl = patched;
      inflight++;
      lastMutation = Date.now();
      var args = Array.prototype.slice.call(arguments);
      args[1] = patched;
      var self = this;
      this.addEventListener('loadend', function () { inflight--; });
      return open.apply(self, args);
    };

    if (window.WebSocket) {
      var WebSocketOriginal = window.WebSocket;
      window.WebSocket = function (url, protocols) {
        var patched = rewriteUrl(url);
        return protocols === undefined ? new WebSocketOriginal(patched)
                                       : new WebSocketOriginal(patched, protocols);
      };
      window.WebSocket.prototype = WebSocketOriginal.prototype;
    }

    if (window.EventSource) {
      var EventSourceOriginal = window.EventSource;
      window.EventSource = function (url, config) {
        return new EventSourceOriginal(rewriteUrl(url), config);
      };
      window.EventSource.prototype = EventSourceOriginal.prototype;
    }

    if (navigator.sendBeacon) {
      var beacon = navigator.sendBeacon.bind(navigator);
      navigator.sendBeacon = function (url, data) { return beacon(rewriteUrl(url), data); };
    }

    // pushState/replaceState: the application writes '/auftrag/5', the browser has to show
    // '/browser/app/sess/auftrag/5', and the other way round when going back.
    ['pushState', 'replaceState'].forEach(function (name) {
      var original = history[name];
      if (!original) { return; }
      history[name] = function (state, title, url) {
        var args = Array.prototype.slice.call(arguments);
        if (url !== undefined) { args[2] = rewriteUrl(url); }
        var result = original.apply(history, args);
        report({ t: 'nav', frame: framePath, url: location.pathname + location.search,
                 title: document.title, frames: frameCount() });
        return result;
      };
    });
    window.addEventListener('pagehide', function () { leaving = true; }, true);
    // From the back-forward cache the document comes back alive, and so does its right to a diff.
    window.addEventListener('pageshow', function () { leaving = false; }, true);
    window.addEventListener('popstate', function () {
      report({ t: 'nav', frame: framePath, url: location.pathname + location.search,
               title: document.title, frames: frameCount() });
    });

    window.__mcpb = { patched: true, version: expectVersion, rewrite: rewriteUrl,
                     quiescent: quiescent, snapshot: snapshot, valueMap: valueMap,
                     fieldKey: fieldKeyOf, pause: function (on) { paused = !!on; paintBadge(); } };
  }

  // --------------------------------------------------------- 2. quiescence

  function observeMutations() {
    var observer = new MutationObserver(function () { lastMutation = Date.now(); });
    observer.observe(document.documentElement, {
      childList: true, subtree: true, attributes: true, characterData: true
    });
  }

  function nextFrames(count) {
    return new Promise(function (resolve) {
      var remaining = count || 2;
      function tick() { remaining--; if (remaining <= 0) { resolve(); } else { requestAnimationFrame(tick); } }
      requestAnimationFrame(tick);
    });
  }

  /**
   * "The page is finished": no requests in flight, no mutation in the last interval, two frames gone
   * by. The term carries everything — snapshot, readback, field classification.
   */
  function quiescent(timeoutMs) {
    var quiet = (CONFIG && CONFIG.quiesceMs) || 300;
    var deadline = Date.now() + (timeoutMs || 1500);
    function settled() {
      return inflight === 0 && (Date.now() - lastMutation) >= quiet;
    }
    return new Promise(function (resolve) {
      function step() {
        if (document.readyState !== 'complete') {
          if (Date.now() < deadline) { setTimeout(step, 50); } else { resolve(false); }
          return;
        }
        if (!settled()) {
          if (Date.now() < deadline) { setTimeout(step, 40); } else { resolve(false); }
          return;
        }
        nextFrames(2).then(function () { resolve(settled()); });
      }
      step();
    });
  }

  // ------------------------------------------------------------ 3. snapshot

  function roleOf(el) {
    var explicit = el.getAttribute && el.getAttribute('role');
    if (explicit) { return explicit; }
    var tag = el.tagName ? el.tagName.toLowerCase() : '';
    var type = (el.type || '').toLowerCase();
    if (tag === 'a' && el.hasAttribute('href')) { return 'link'; }
    if (tag === 'button' || type === 'submit' || type === 'button' || type === 'reset') { return 'button'; }
    if (tag === 'select') { return el.multiple ? 'listbox' : 'combobox'; }
    if (tag === 'textarea') { return 'textbox'; }
    if (tag === 'input') {
      if (type === 'password') { return 'passwordbox'; }
      if (type === 'checkbox') { return 'checkbox'; }
      if (type === 'radio') { return 'radio'; }
      if (type === 'date' || type === 'datetime-local' || type === 'month' || type === 'week') { return 'date'; }
      if (type === 'number' || type === 'range') { return 'number'; }
      if (type === 'file') { return 'file'; }
      if (type === 'search') { return 'searchbox'; }
      return 'textbox';
    }
    if (tag === 'iframe' || tag === 'frame') { return 'iframe'; }
    if (tag === 'td') { return 'cell'; }
    if (tag === 'th') { return 'columnheader'; }
    if (tag === 'option') { return 'option'; }
    if (/^h[1-6]$/.test(tag)) { return 'heading'; }
    if (tag === 'summary' || tag === 'details') { return tag; }
    if (el.isContentEditable) { return 'richtext'; }
    if (tag === 'label') { return 'label'; }
    return '';
  }

  var KEEP_ROLES = {
    link: 1, button: 1, combobox: 1, listbox: 1, textbox: 1, searchbox: 1, passwordbox: 1,
    checkbox: 1, radio: 1, date: 1, number: 1, file: 1, iframe: 1, cell: 1, columnheader: 1,
    heading: 1, richtext: 1, option: 1, tab: 1, menuitem: 1, switch: 1, slider: 1, spinbutton: 1,
    gridcell: 1, row: 1, summary: 1
  };

  /**
   * The text of a <label>, minus whatever the label merely carries. A label that wraps its control also
   * contains that control's own text, and for a <select> that is the whole option list: read on a live
   * page, one field came back labelled "AUTO-DETECTUTF-8ASCIIISO-8859-1…" - forty entries glued together
   * and cut at 90 characters. That text is what the LLM reads as the name of the field, and it is part of
   * the M2 field key, so the key would move every time the application adds a choice. Both label paths
   * below go through here: the associated one (`el.labels`) fires first and would otherwise keep
   * producing the concatenated list.
   *
   * @param labelEl the label element (associated or wrapping)
   * @return its own text, controls taken out
   */
  function labelElementText(labelEl) {
    try {
      var shell = labelEl.cloneNode(true);
      var nested = shell.querySelectorAll('select,textarea,input,button');
      for (var i = nested.length - 1; i >= 0; i--) {
        shell.removeChild(nested[i]);      // the option text belongs to the element, not to the label
      }
      return trimText(shell.textContent, 90);
    } catch (e) {
      return trimText(labelEl.textContent, 90);
    }
  }

  function labelOf(el) {
    try {
      if (el.labels && el.labels.length) {
        var fromLabel = labelElementText(el.labels[0]);
        if (fromLabel) { return fromLabel.replace(/\*\s*$/, '').trim(); }
      }
      var aria = el.getAttribute('aria-label');
      if (aria) { return trimText(aria, 90); }
      var labelledBy = el.getAttribute('aria-labelledby');
      if (labelledBy) {
        var parts = labelledBy.split(/\s+/).map(function (id) {
          var other = document.getElementById(id);
          return other ? trimText(other.textContent, 60) : '';
        }).filter(Boolean);
        if (parts.length) { return parts.join(' '); }
      }
      var wrapped = el.closest && el.closest('label');
      if (wrapped) {
        var wrappedText = labelElementText(wrapped);
        if (wrappedText) { return wrappedText; }
      }
      if (el.placeholder) { return trimText(el.placeholder, 90); }
      var title = el.getAttribute('title');
      if (title) { return trimText(title, 90); }
      // A button says what it does in its text; `name="action"` is a form technicality (the reason it
      // exists here at all is that buttons submit a named value). Reading the name as the label turned
      // every review title into "Submit 'action'" - the human on the other side of the gate has to see
      // "Submit 'Save comment'". Inputs keep the name fallback: they have no text of their own.
      var elType = (el.type || '').toLowerCase();
      if (el.tagName === 'BUTTON' || elType === 'submit' || elType === 'button'
          || elType === 'reset') {
        var buttonText = trimText(el.textContent, 60);
        if (buttonText) { return buttonText; }
      }
      var name = el.getAttribute('name') || el.id;
      if (name) { return String(name).replace(/[_\-.]/g, ' '); }
    } catch (e) { /* label resolution is never critical */ }
    return '';
  }

  function nearbyHeading(el) {
    var scope = el.closest && (el.closest('fieldset') || el.closest('table') || el.closest('section')
              || el.closest('form') || el.closest('div'));
    if (!scope) { return ''; }
    var legend = scope.querySelector ? scope.querySelector('legend,caption,h1,h2,h3,h4') : null;
    return legend ? trimText(legend.textContent, 70) : '';
  }

  function tableContext(el) {
    var cell = el.matches && el.matches('td,th') ? el : (el.closest ? el.closest('td,th') : null);
    if (!cell) { return null; }
    var row = cell.parentNode;
    var table = cell.closest ? cell.closest('table') : null;
    var columnIndex = Array.prototype.indexOf.call(row.children, cell);
    var out = { row: -1, col: columnIndex, colHead: '', rowHead: '' };
    if (!table) { return out; }
    var headRow = table.querySelector('thead tr');
    if (headRow && headRow.children[columnIndex]) {
      out.colHead = trimText(headRow.children[columnIndex].textContent, 60);
    }
    var bodyRows = table.querySelectorAll('tbody tr');
    for (var i = 0; i < bodyRows.length; i++) {
      if (bodyRows[i] === row) { out.row = i; break; }
    }
    if (row.children.length && row.children[0] !== cell) {
      out.rowHead = trimText(row.children[0].textContent, 60);
    }
    return out;
  }

  function cssPath(el) {
    var parts = [];
    var node = el;
    while (node && node.nodeType === 1 && parts.length < 6) {
      var part = node.tagName.toLowerCase();
      if (node.id) { parts.unshift(part + '[id=' + node.id + ']'); break; }
      if (node.className && typeof node.className === 'string') {
        var cls = node.className.trim().split(/\s+/)[0];
        if (cls && /^[A-Za-z0-9_-]{1,30}$/.test(cls)) { part += '.' + cls; }
      }
      var parent = node.parentNode;
      if (parent) {
        var sameTagSiblings = Array.prototype.filter.call(parent.children, function (sibling) {
          return sibling.tagName === node.tagName;
        });
        if (sameTagSiblings.length > 1) {
          part += ':nth-of-type(' + (sameTagSiblings.indexOf(node) + 1) + ')';
        }
      }
      parts.unshift(part);
      node = node.parentNode;
    }
    return parts.join(' > ');
  }

  function valueClass(value) {
    if (value === '' || value == null) { return 'empty'; }
    if (/^-?[\d.,\s%€$]+$/.test(value)) { return 'number'; }
    if (/^\d{4}-\d{2}-\d{2}/.test(value)) { return 'date'; }
    return 'text';
  }

  /** The one test for "this field holds a secret": snapshot, events, readback and value pass share it. */
  function isSecretField(el) {
    var type = (el && el.type ? el.type : '').toLowerCase();
    if (type === 'password') { return true; }
    var name = el && el.getAttribute ? el.getAttribute('name') : null;
    return isSecretKey(name) || isSecretKey(el && el.id);
  }

  // ------------------------------------------------- 3b. field identity and value pass (M2)
  //
  // A ref is valid for one revision, a css path survives a re-render by luck. Neither is a name the
  // application uses for the field, and M2 has to talk about the same field tomorrow, in another
  // session, in a log read back offline. The field key is that name: the form the control belongs to
  // plus what the control itself is called (name, id, label, role, type), prefixed by the frame.
  // Nothing in it is generated by us, so nothing in it moves when the page re-renders.

  var VALUE_ROLES = { textbox: 1, searchbox: 1, passwordbox: 1, combobox: 1, listbox: 1,
                      checkbox: 1, radio: 1, date: 1, number: 1, slider: 1, spinbutton: 1,
                      switch: 1, richtext: 1 };

  function formKeyOf(el) {
    var form = el.form;
    // Not form.id! - a named control shadows the form's own property: the report editor holds two
    // elements that answer to "id", so form.id is a RadioNodeList and the whole field key used to read
    // "#[object RadioNodeList]". Two different forms on one page then shared one identity, which is
    // exactly the wrong thing for a key that is supposed to survive sessions. Attribute, never property.
    if (!form || form.nodeType !== 1) { return '(noform)'; }   // outside a <form>: one group, not one per control
    var id = form.getAttribute('id');
    if (id) { return '#' + id; }
    var action = form.getAttribute('action');
    if (action) { return '@' + action; }
    var forms = (form.ownerDocument || document).getElementsByTagName('form');
    for (var i = 0; i < forms.length; i++) {
      if (forms[i] === form) { return 'form[' + i + ']'; }
    }
    return '(form)';
  }

  function fieldKeyOf(el) {
    var role = roleOf(el) || 'field';
    var name = el.getAttribute ? (el.getAttribute('name') || '') : '';
    var label = (labelOf(el) || '').trim().toLowerCase();
    var type = (el.type || '').toLowerCase();
    return formKeyOf(el) + '|' + name + '|' + (el.id || '') + '|' + label + '|' + role + '|' + type;
  }

  /** The key as it stands in the snapshot and in the value map: the frame is part of the address. */
  function frameFieldKey(frameId, el) {
    return frameId + '|' + fieldKeyOf(el);
  }

  /** The value of a control as text - a secret never leaves the page, not even into a diff. */
  function fieldValue(el) {
    if (isSecretField(el)) { return '‹redacted›'; }
    if (el.isContentEditable) { return trimText(el.textContent, 200); }
    var type = (el.type || '').toLowerCase();
    if (type === 'checkbox' || type === 'radio') { return el.checked ? 'true' : 'false'; }
    if (el.tagName === 'SELECT') {
      var option = el.selectedOptions && el.selectedOptions[0];
      return trimText(option ? option.textContent : el.value, 200);
    }
    return trimText(el.value, 200);
  }

  /**
   * What the page says about a field without anyone typing: the raw material of the `policy` slot
   * (required / maxlength / pattern / min / max) and of `lookup` (a datalist, an option list). Cheap
   * because it runs only on the fields the value pass walks anyway.
   */
  function fieldHints(el) {
    var hints = {};
    if (el.required) { hints.required = true; }
    if (el.readOnly) { hints.readonly = true; }
    if (el.disabled) { hints.disabled = true; }
    var attr = el.getAttribute ? function (name) { return el.getAttribute(name); } : function () { return null; };
    if (attr('maxlength')) { hints.maxlength = attr('maxlength'); }
    if (attr('pattern')) { hints.pattern = trimText(attr('pattern'), 60); }
    if (attr('list')) { hints.datalist = attr('list'); }
    if (el.min) { hints.min = trimText(String(el.min), 30); }
    if (el.max) { hints.max = trimText(String(el.max), 30); }
    if (el.tagName === 'SELECT') { hints.optionCount = el.options ? el.options.length : 0; }
    return hints;
  }

  var CONTROL_SELECTOR = 'input,select,textarea,[contenteditable],[role]';

  function walkValues(doc, frameId, out, limit, state) {
    var nodes = doc.querySelectorAll(CONTROL_SELECTOR);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.closest && el.closest('[data-mcpb-ui]')) { continue; }
      if (el.type === 'hidden') { continue; }
      if (!VALUE_ROLES[roleOf(el)]) { continue; }
      if (out.length >= limit) { state.truncated = true; return; }
      var entry = { key: frameFieldKey(frameId, el), frame: frameId, role: roleOf(el),
                    label: trimText(labelOf(el), 90), value: fieldValue(el) };
      if (isSecretField(el)) { entry.secret = true; }
      var hints = fieldHints(el);
      if (Object.keys(hints).length) { entry.hints = hints; }
      out.push(entry);
      state.count++;
    }
    var frames = doc.querySelectorAll('iframe,frame');
    for (var k = 0; k < frames.length; k++) {
      var child = null;
      try { child = frames[k].contentDocument; } catch (e) { child = null; }
      if (!child || child === doc || out.length >= limit) { continue; }
      walkValues(child, frameId + '/f' + (k + 1), out, limit, state);
    }
  }

  /**
   * The writable fields of the whole page (frames included) as a keyed map - the same frame numbering
   * as {@link walkFrames}, so a key of this pass is the key of the snapshot's element.
   */
  function valueMap() {
    var fields = [];
    var state = { count: 0, truncated: false };
    try {
      walkValues(document, 'f0', fields, (CONFIG && CONFIG.budget) || 1200, state);
    } catch (e) {
      reportError('valueMap: ' + e);
      return { fields: [], truncated: true, failed: String(e) };
    }
    return { fields: fields, truncated: state.truncated };
  }

  /**
   * Before against after. `written` holds the keys the operation itself touched - everything else that
   * differs the application has recalculated while the page rested. An inconclusive answer says so
   * instead of letting the server read "nothing changed" out of a half-finished page.
   */
  function diffValues(before, after, written, settled) {
    var previous = {};
    before.fields.forEach(function (item) { previous[item.key] = item; });
    var changed = [];
    var unchanged = 0;
    after.fields.forEach(function (item) {
      var old = previous[item.key];
      if (!old) { return; }                       // appeared meanwhile: a re-render, not a recalculation
      if (old.value === item.value) { unchanged++; return; }
      changed.push({ key: item.key, frame: item.frame, role: item.role, label: item.label,
                     from: old.value, to: item.value, written: !!written[item.key] });
    });
    var recalculated = changed.filter(function (item) { return !item.written; });
    var inconclusive = '';
    if (!settled) { inconclusive = 'page not settled - values may still change'; }
    else if (before.failed || after.failed) { inconclusive = 'the value pass failed: '
            + (before.failed || after.failed); }
    else if (after.truncated || before.truncated) {
      inconclusive = 'budget reached - the value pass did not cover every field';
    }
    return { scanned: after.fields.length, unchanged: unchanged, changed: changed,
             recalculated: recalculated, inconclusive: inconclusive };
  }

  function elementEntry(el, doc, ref) {
    var role = roleOf(el);
    var type = (el.type || '').toLowerCase();
    var secret = isSecretField(el);
    var entry = { ref: ref, role: role };

    if (type) { entry.type = type; }
    var label = labelOf(el);
    if (role === 'button' || role === 'link' || role === 'heading' || role === 'cell'
        || role === 'columnheader' || role === 'option' || role === 'summary') {
      var ownText = trimText(el.textContent, 90);
      if (role === 'heading' || role === 'cell' || role === 'columnheader' || role === 'option') {
        entry.value = ownText;
      }
      if (ownText && role !== 'cell') { entry.label = entry.label || ownText; }
      if (!entry.label) { entry.label = ownText; }
    }
    if (label) { entry.label = label; }

    if (el.tagName === 'IFRAME') {
      entry.src = el.getAttribute('src') || '';
      try {
        entry.opaque = !el.contentDocument;
      } catch (e) {
        entry.opaque = true;
      }
    }
    if (role === 'richtext') {
      if (!secret) { entry.value = trimText(el.textContent, 200); }
    }
    if (role === 'link' && el.getAttribute('href')) {
      entry.href = trimText(el.getAttribute('href'), 120);
    }
    if (el.tagName === 'IMG' && el.getAttribute('alt')) {
      entry.label = trimText(el.getAttribute('alt'), 90);
    }

    if (el.tagName === 'SELECT') {
      var values = [];
      var options = el.options;
      for (var i = 0; i < options.length && values.length < 40; i++) {
        if (options[i].selected) { entry.value = trimText(options[i].textContent, 90); }
        values.push(trimText(options[i].textContent, 40));
      }
      if (entry.value === undefined) { entry.value = ''; }
      if (values.length && values.length <= 40) { entry.options = values; }
    } else if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
      if (role === 'checkbox' || role === 'radio') {
        entry.checked = !!el.checked;
      } else if (secret) {
        entry.value = '‹redacted›';
        entry.secret = true;
      } else {
        entry.value = trimText(el.value, 200);
      }
      if (el.getAttribute('list')) {
        var list = doc.getElementById(el.getAttribute('list'));
        if (list) {
          var suggestions = [];
          list.querySelectorAll('option').forEach(function (option) {
            if (suggestions.length < 20 && option.value) { suggestions.push(trimText(option.value, 60)); }
          });
          if (suggestions.length) { entry.options = suggestions; }
        }
      }
      var asDate = el.valueAsDate;
      if (role === 'date' && asDate) { entry.iso = el.value; }
    }

    if (el.required) { entry.required = true; }
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') { entry.disabled = true; }
    if (el.readOnly) { entry.readonly = true; }
    var invalid = el.getAttribute('aria-invalid');
    if (invalid === 'true') { entry.invalid = true; }

    var near = nearbyHeading(el);
    if (near) { entry.near = near; }
    var context = tableContext(el);
    if (context) {
      entry.grid = context.row + ',' + context.col;
      if (context.colHead) { entry.colHead = context.colHead; }
      if (context.rowHead) { entry.rowHead = context.rowHead; }
    }

    entry.loc = {
      tag: el.tagName.toLowerCase(),
      id: el.id || null,
      name: el.getAttribute('name') || null,
      css: cssPath(el),
      label: label || null
    };
    entry.fp = { role: role, label: label || '', type: type, values: secret ? 'secret' : valueClass(entry.value) };
    entry.__el = el;
    return entry;
  }

  /**
   * What the write path and the value pass have to agree on: the element behind a ref and the key of
   * that same field in the value map. Both are addressed with the frame in front of them - storing the
   * local number (as this did once) made every event report a bare "e12" that no lookup could resolve,
   * and "f0/e5" against "f0/f1/e5" would be indistinguishable.
   */
  function rememberAddress(el, frameId, entry) {
    REFS.set(el, entry.ref);
    if (VALUE_ROLES[entry.role]) {
      entry.key = frameFieldKey(frameId, el);
      KEYS.set(el, entry.key);
    }
  }

  function collect(doc, frameId, out, budget) {
    var selector = 'a[href],button,input,select,textarea,[contenteditable],[role],'
                 + 'iframe,th,td,h1,h2,h3,h4,summary,[onclick],[tabindex]';
    var nodes = doc.querySelectorAll(selector);
    var counter = 0;
    var truncated = false;
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.closest && el.closest('[data-mcpb-ui]')) { continue; }     // our own badge
      if (el.type === 'hidden') { continue; }
      var role = roleOf(el);
      if (!role || !KEEP_ROLES[role]) { continue; }
      if (role === 'option' && el.closest && el.closest('select') && !el.selected) { continue; }
      if (out.length >= budget) { truncated = true; break; }
      counter++;
      var entry = elementEntry(el, doc, 'e' + counter);
      entry.ref = frameId + '/' + entry.ref;
      rememberAddress(el, frameId, entry);
      REF_ELEMENTS.set(entry.ref, el);
      out.push(entry);
    }
    return truncated;
  }

  function walkFrames(doc, frameId, frames, budget) {
    var elements = [];
    var truncated = collect(doc, frameId, elements, budget);
    var record = { frame: frameId, url: doc.location ? doc.location.pathname + doc.location.search : '',
                   title: trimText(doc.title, 90), ax: elements.map(stripPrivate) };
    if (truncated) { record.truncated = true; }
    frames.push(record);
    var remaining = budget - elements.length;
    var index = 0;
    var subframes = doc.querySelectorAll('iframe,frame');
    for (var i = 0; i < subframes.length; i++) {
      var child = null;
      try { child = subframes[i].contentDocument; } catch (e) { child = null; }
      index++;
      if (!child || child === doc) { continue; }
      if (remaining <= 0) { break; }
      walkFrames(child, frameId + '/f' + index, frames, remaining);
      remaining = budget - frames.reduce(function (sum, item) { return sum + item.ax.length; }, 0);
    }
    return frames;
  }

  function stripPrivate(entry) {
    var out = {};
    Object.keys(entry).forEach(function (key) {
      if (key !== '__el') { out[key] = entry[key]; }
    });
    return out;
  }

  function frameCount() {
    return document.querySelectorAll('iframe,frame').length + 1;
  }

  function snapshot(scope, full) {
    var budget = (CONFIG && CONFIG.budget) || 1200;
    var frames = [];
    REF_ELEMENTS.clear();
    try {
      walkFrames(document, 'f0', frames, budget);
    } catch (e) {
      reportError('snapshot: ' + e);
      return Promise.resolve({ t: 'snapshot', frame: framePath, error: String(e), frames: [] });
    }
    if (!full) {
      frames.forEach(function (record) {
        record.ax = record.ax.filter(function (item) { return item.role !== 'heading'; });
      });
    }
    if (scope) {
      frames = frames.filter(function (record) { return record.frame === scope; });
    }
    return quiescent(1200).then(function (settled) {
      return { t: 'snapshot', frame: framePath, at: Date.now(), quiescent: settled,
               ready: document.readyState, frames: frames, leaks: leaks() };
    });
  }

  function leaks() {
    if (!performance || !performance.getEntriesByType) { return []; }
    var upstreams = ((CONFIG && CONFIG.hosts) || []).map(function (item) { return item.upstream; });
    var found = [];
    performance.getEntriesByType('resource').forEach(function (item) {
      for (var i = 0; i < upstreams.length; i++) {
        if (item.name.indexOf(upstreams[i]) === 0 && found.length < 10) {
          found.push(item.name);
        }
      }
    });
    return found;
  }

  // ------------------------------------------------------------ 4. events

  function report(payload) {
    if (stopped || paused || !CONFIG) { return; }
    var known = REFS.get(payload.el);
    delete payload.el;
    if (known) { payload.ref = known; }
    payload.frame = framePath;
    pending.push(payload);
    flushSoon();
  }

  var pending = [];
  var flushTimer = null;

  function flushSoon() {
    if (flushTimer) { return; }
    flushTimer = setTimeout(function () {
      flushTimer = null;
      if (!pending.length) { return; }
      var batch = pending;
      pending = [];
      post(batch.length === 1 ? batch[0] : batch);
    }, 120);
  }

  function interesting(el) {
    if (!el || !el.closest) { return false; }
    if (el.closest('[data-mcpb-ui]')) { return false; }
    var role = roleOf(el);
    return !!role && !!KEEP_ROLES[role];
  }

  function describe(el, kind, value) {
    var payload = { t: 'event', k: kind, role: roleOf(el) };
    var label = labelOf(el);
    if (label) { payload.label = label; }
    // The key of M2: an input event has to be joinable with the field of the same name in the
    // snapshot and in the value pass, or the whole "who wrote this?" question cannot be answered.
    var key = KEYS.get(el) || (framePath + '|' + fieldKeyOf(el));
    if (key) { payload.key = key; }
    if (isSecretField(el)) {
      payload.value = '‹redacted›';
      payload.secret = true;
    } else if (value !== undefined) {
      payload.value = trimText(value, 120);
    }
    payload.el = el;
    return payload;
  }

  function installEventReporting() {
    var inputDebounce = new WeakMap();
    document.addEventListener('focusin', function (event) {
      if (interesting(event.target)) { report(describe(event.target, 'focus')); }
    }, true);
    document.addEventListener('input', function (event) {
      if (!interesting(event.target)) { return; }
      var existing = inputDebounce.get(event.target);
      if (existing) { clearTimeout(existing); }
      inputDebounce.set(event.target, setTimeout(function () {
        report(describe(event.target, 'input', event.target.value));
      }, 350));
    }, true);
    document.addEventListener('change', function (event) {
      if (interesting(event.target)) { report(describe(event.target, 'change', event.target.value)); }
    }, true);
    document.addEventListener('click', function (event) {
      if (interesting(event.target)) { report(describe(event.target, 'click')); }
    }, true);
    document.addEventListener('submit', function (event) {
      if (event.target && event.target.tagName === 'FORM') {
        report({ t: 'event', k: 'submit', role: 'form',
                 label: trimText(event.target.getAttribute('action'), 120), el: event.target });
      }
    }, true);
  }

  // ------------------------------------------------------------- 5. badge
  //
  // The user has to see that they are not at the application directly, and has to be able to switch it
  // off. Both are the same element here: the emergency exit is one line of code.

  var badgeHost = null;

  function paintBadge() {
    if (!isTop) { return; }
    if (!badgeHost) {
      badgeHost = document.createElement('div');
      badgeHost.setAttribute('data-mcpb-ui', '1');
      badgeHost.style.cssText = 'position:fixed;right:12px;bottom:12px;z-index:2147483647';
      var shadow = badgeHost.attachShadow({ mode: 'open' });
      shadow.innerHTML = '<style>'
        + '.b{font:12px/1.4 system-ui,sans-serif;background:#202124;color:#e8eaed;border-radius:8px;'
        + 'padding:.3rem .5rem;display:flex;gap:.5rem;align-items:center;box-shadow:0 1px 6px rgba(0,0,0,.35)}'
        + 'b{font-weight:600}button{font:inherit;background:#3c4043;color:#e8eaed;border:0;'
        + 'border-radius:5px;padding:.1rem .4rem;cursor:pointer}'
        + '.off{background:#5f6368;color:#fff}'
        + '</style><div class="b" part="badge"><b>Proxy</b><span id="t">…</span>'
        + '<button id="p" part="pause">Pause</button></div>';
      document.documentElement.appendChild(badgeHost);
      shadow.getElementById('p').addEventListener('click', function () {
        paused = !paused;
        paintBadge();
      });
    }
    var label = badgeHost.shadowRoot.getElementById('t');
    var button = badgeHost.shadowRoot.getElementById('p');
    if (stopped) {
      label.textContent = 'disconnected';
      button.textContent = 'off';
      button.classList.add('off');
      return;
    }
    label.textContent = paused ? 'LLM paused' : ((CONFIG ? CONFIG.app : '?') + ' · ' + session
        + ' · LLM is reading along');
    button.textContent = paused ? 'Resume' : 'Pause';
    button.classList.toggle('off', paused);
  }

  // ---------------------------------------------------- 5b. write operations (M1)
  //
  // The recipes of §7.2: every control type gets the write path its framework survives — editor stack
  // first (execCommand), native setter as the fallback (bypasses React's value tracker), real
  // input/change events in both cases. The undo journal captures the first old value per element;
  // `undo` restores it. Secrets are writable but never readable: the readback redacts them.

  var UNDO = [];   // {ref, kind, value} — captured before the first write to the element

  function locateForWrite(ref, expectRole, expectLabel) {
    var el = REF_ELEMENTS.get(ref);
    if (!el) {
      return { error: 'ref-unknown',
               hint: 'ref does not belong to the last snapshot - take a snapshot again first (action=refresh)' };
    }
    if (!el.isConnected) {
      // The ref still points at the element object, but the page replaced that part of the DOM since
      // the snapshot. Writing now would edit a detached clone: the recipe reports ok, the user sees
      // nothing. Honest instead of silent - and it clears the way for the fingerprint check.
      return { error: 'element-detached',
               hint: 'the page rebuilt this part since the snapshot - take a snapshot again (action=refresh)' };
    }
    if (expectRole) {
      var role = roleOf(el);
      var label = (labelOf(el) || '').trim().toLowerCase();
      var wanted = (expectLabel || '').trim().toLowerCase();
      if (role !== expectRole || (wanted && label !== wanted)) {
        return { error: 'fingerprint-mismatch',
                 hint: 'the element has changed since the snapshot - look again before writing',
                 actualRole: role, actualLabel: label };
      }
    }
    return { el: el };
  }

  function rememberUndo(ref, el) {
    for (var i = 0; i < UNDO.length; i++) {
      if (UNDO[i].ref === ref) { return; }
    }
    var kind = 'value';
    var old = '';
    if (el.tagName === 'SELECT') { kind = 'select'; old = el.value; }
    else if (el.type === 'checkbox' || el.type === 'radio') { kind = 'checked'; old = !!el.checked; }
    else if (el.isContentEditable) { kind = 'richtext'; old = el.textContent; }
    else { old = el.value; }
    UNDO.push({ ref: ref, kind: kind, value: old });
  }

  function nativeSetter(el) {
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    return Object.getOwnPropertyDescriptor(proto, 'value').set;
  }

  function dispatch(el, type) {
    el.dispatchEvent(new Event(type, { bubbles: true }));
  }

  /**
   * One write recipe. Returns {ok, value, exact?} or {error, hint?}.
   */
  function applyValue(el, value) {
    var type = (el.type || '').toLowerCase();
    if (type === 'file') {
      return { error: 'file-not-settable',
               hint: '<input type=file> cannot be set from JS - upload endpoint or the user.' };
    }
    if (el.tagName === 'SELECT') {
      var target = String(value == null ? '' : value);
      var found = false;
      for (var i = 0; i < el.options.length; i++) {
        var option = el.options[i];
        var match = option.value === target || option.textContent.trim() === target;
        if (el.multiple) { option.selected = match; if (match) { found = true; } }
        else if (match) { el.selectedIndex = i; found = true; break; }
      }
      if (!found && !el.multiple) {
        var selectSetter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set;
        selectSetter.call(el, target);
        found = el.value === target;
      }
      dispatch(el, 'input');
      dispatch(el, 'change');
      var selected = el.selectedOptions && el.selectedOptions[0] ? el.selectedOptions[0].textContent : el.value;
      return { ok: true, value: trimText(selected, 200) };
    }
    if (type === 'checkbox' || type === 'radio') {
      var want = !(value === false || value === 'false' || value === '' || value === '0'
                   || value === null || value === undefined);
      if (!!el.checked !== want) { el.click(); }
      return { ok: true, value: el.checked };
    }
    var text = String(value == null ? '' : value);
    if (el.isContentEditable) {
      el.focus();
      var selection = window.getSelection();
      var range = document.createRange();
      range.selectNodeContents(el);
      selection.removeAllRanges();
      selection.addRange(range);
      var inserted = false;
      try { inserted = document.execCommand('insertText', false, text); } catch (e) { inserted = false; }
      if (!inserted) { el.textContent = text; dispatch(el, 'input'); }
      dispatch(el, 'change');
      return { ok: true, value: trimText(el.textContent, 200) };
    }
    el.focus();
    if (type === 'date' || type === 'datetime-local' || type === 'month' || type === 'week'
        || type === 'number' || type === 'range') {
      // These types ignore execCommand and selection - the native setter is the recipe.
      nativeSetter(el).call(el, text);
      dispatch(el, 'input');
      dispatch(el, 'change');
      return { ok: true, value: el.value, exact: el.value === text };
    }
    var viaEditor = false;
    try {
      viaEditor = document.execCommand('selectAll', false, null)
                  && document.execCommand('insertText', false, text);
    } catch (e) { viaEditor = false; }
    if (!viaEditor || el.value !== text) {
      // Fallback: the native setter - it bypasses React's value tracker, which is why the events
      // are dispatched by hand afterwards.
      nativeSetter(el).call(el, text);
      dispatch(el, 'input');
      dispatch(el, 'change');
    }
    return { ok: true, value: el.value };
  }

  function readback(el) {
    var type = (el.type || '').toLowerCase();
    var secret = isSecretField(el);
    var value;
    if (type === 'checkbox' || type === 'radio') { value = el.checked; }
    else if (el.tagName === 'SELECT') {
      var selected = el.selectedOptions && el.selectedOptions[0] ? el.selectedOptions[0].textContent : el.value;
      value = trimText(selected, 200);
    }
    else if (el.isContentEditable) { value = trimText(el.textContent, 200); }
    else { value = secret ? '‹redacted›' : trimText(el.value, 200); }
    var invalid = !!el.checkValidity && !el.checkValidity();
    return { value: value, secret: secret,
             validationMessage: invalid ? (el.validationMessage || 'invalid') : '' };
  }

  /**
   * One fill run. With `wantDiff` the writable fields are read as a keyed map before the first write
   * and again after the page rests; the difference is the evidence of M2 (§7a docs/browser.md): what
   * changed without being written, the application computes.
   */
  function doFill(commandId, items, wantDiff) {
    var results = [];
    var written = {};
    var valuesBefore = wantDiff ? valueMap() : null;
    var chain = Promise.resolve();
    items.forEach(function (item) {
      chain = chain.then(function () {
        var found = locateForWrite(item.ref, item.role, item.label);
        if (found.error) {
          results.push({ ref: item.ref, ok: false, error: found.error, hint: found.hint,
                         actualRole: found.actualRole, actualLabel: found.actualLabel });
          return;
        }
        var el = found.el;
        var label = labelOf(el);
        var key = KEYS.get(el) || null;
        if (el.disabled || el.readOnly) {
          results.push({ ref: item.ref, ok: false, error: 'element-locked', label: label, key: key });
          return;
        }
        rememberUndo(item.ref, el);
        try {
          el.scrollIntoView({ block: 'center' });
          var before = readback(el);
          var out = applyValue(el, item.value);
          if (out.error) {
            results.push({ ref: item.ref, ok: false, error: out.error, hint: out.hint, label: label,
                           key: key });
            return;
          }
          if (key) { written[key] = true; }
          var after = readback(el);
          results.push({ ref: item.ref, ok: true, label: label, key: key, oldValue: before.value,
                         value: after.value, secret: after.secret,
                         validationMessage: after.validationMessage, exact: out.exact });
        } catch (e) {
          results.push({ ref: item.ref, ok: false, error: 'write-failed: ' + e, label: label,
                         key: key });
        }
      });
    });
    return chain.then(function () {
      return quiescent(1500).then(function (settled) {
        var ok = results.every(function (item) { return item.ok; });
        var answer = { t: 'result', id: commandId, ok: ok, op: 'fill', results: results,
                       quiescent: settled };
        if (valuesBefore) {
          answer.diff = diffValues(valuesBefore, valueMap(), written, settled);
        }
        return answer;
      });
    });
  }

  function doClick(command) {
    var found = locateForWrite(command.ref, command.role, command.label);
    if (found.error) {
      return Promise.resolve({ t: 'result', id: command.id, ok: false, error: found.error,
                               hint: found.hint, actualRole: found.actualRole,
                               actualLabel: found.actualLabel });
    }
    var el = found.el;
    if (el.disabled) {
      return Promise.resolve({ t: 'result', id: command.id, ok: false, error: 'element-locked',
                               label: labelOf(el) });
    }
    var valuesBefore = command.diff ? valueMap() : null;
    var where = location.pathname + location.search;
    try {
      el.scrollIntoView({ block: 'center' });
      el.click();
    } catch (e) {
      return Promise.resolve({ t: 'result', id: command.id, ok: false, error: 'click-failed: ' + e });
    }
    // A click can navigate the page away; the answer does not always survive the unload. The server
    // treats a following nav event as the answer (docs/browser.md §7.4).
    var answer = { t: 'result', id: command.id, ok: true, op: 'click',
                   label: labelOf(el), role: roleOf(el) };
    // A link with a real href leaves for certain, so there is no reason to read a page that is about to
    // go away - and `pagehide` cannot help here, because it fires when the navigation commits, which is
    // after this synchronous part.
    var leaves = el.tagName === 'A' && (el.getAttribute('href') || '').charAt(0) !== '#';
    if (!valuesBefore || leaves || leaving || location.pathname + location.search !== where) {
      return Promise.resolve(answer);      // navigated: nothing left on this page to compare against
    }
    return quiescent(1500).then(function (settled) {
      // The page can still leave during the wait (a submit handler, a timer, a framework router): the
      // second read must not describe a document that is already gone.
      if (leaving || location.pathname + location.search !== where) {
        return answer;
      }
      // Nothing was written by hand here, so every change is the application's own - a button that
      // triggers a fetch is the cheapest way to learn which fields are computed.
      answer.diff = diffValues(valuesBefore, valueMap(), {}, settled);
      return answer;
    });
  }

  function doUndo(command) {
    var restored = 0;
    var failed = [];
    var written = {};
    var valuesBefore = command.diff ? valueMap() : null;
    for (var i = UNDO.length - 1; i >= 0; i--) {
      var entry = UNDO[i];
      var el = REF_ELEMENTS.get(entry.ref);
      if (!el) { failed.push({ ref: entry.ref, error: 'ref-unknown' }); continue; }
      var key = KEYS.get(el);
      if (key) { written[key] = true; }
      try {
        if (entry.kind === 'checked') {
          if (!!el.checked !== entry.value) { el.click(); }
        } else if (entry.kind === 'select') {
          var selectSetter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set;
          selectSetter.call(el, entry.value);
          dispatch(el, 'change');
        } else if (entry.kind === 'richtext') {
          el.textContent = entry.value;
          dispatch(el, 'input');
        } else {
          nativeSetter(el).call(el, entry.value);
          dispatch(el, 'input');
          dispatch(el, 'change');
        }
        restored++;
      } catch (e) {
        failed.push({ ref: entry.ref, error: String(e) });
      }
    }
    UNDO = [];
    var answer = { t: 'result', id: command.id, ok: failed.length === 0, op: 'undo',
                   restored: restored, failed: failed };
    if (!valuesBefore) {
      return Promise.resolve(answer);
    }
    return quiescent(1500).then(function (settled) {
      answer.diff = diffValues(valuesBefore, valueMap(), written, settled);
      return answer;
    });
  }

  // --------------------------------------------------------------- 6. loop

  function execute(command) {
    var type = command.t;
    if (type === 'ping') {
      return Promise.resolve({ t: 'pong', frame: framePath, ready: document.readyState,
                               quiescent: inflight === 0 });
    }
    if (type === 'snapshot') {
      return snapshot(command.scope || null, !!command.full).then(function (result) {
        result.id = command.id;
        return result;
      });
    }
    if (type === 'scroll') {
      var target = REF_ELEMENTS.get(command.ref);
      if (!target) {
        return Promise.resolve({ t: 'result', id: command.id, ok: false, error: 'ref-unknown',
                                 hint: 'ref does not belong to the last revision - take a snapshot again first' });
      }
      target.scrollIntoView({ block: 'center', behavior: 'smooth' });
      return Promise.resolve({ t: 'result', id: command.id, ok: true, ref: command.ref });
    }
    if (type === 'stop') {
      stopped = true;
      paintBadge();
      return Promise.resolve({ t: 'result', id: command.id, ok: true, stopped: true });
    }
    // The value pass on its own (M2): reads the fields the way the write path reads them, changes
    // nothing - therefore deliberately behind no write switch, like the snapshot.
    if (type === 'observe') {
      return quiescent(command.waitMs || 1200).then(function (settled) {
        var map = valueMap();
        return { t: 'result', id: command.id, ok: !map.failed, op: 'observe', quiescent: settled,
                 page: location.pathname + location.search, fields: map.fields,
                 truncated: !!map.truncated, failed: map.failed || '' };
      });
    }
    // From here on: write operations. The boundary stands in the code, not only in the docs, so that
    // a server accidentally switched on cannot have any effect (CONFIG.write arrives via hello).
    if (type === 'fill' || type === 'set' || type === 'select' || type === 'paste') {
      if (!CONFIG || !CONFIG.write) {
        return Promise.resolve({ t: 'result', id: command.id, ok: false,
                                 error: 'write-ops-disabled' });
      }
      var items = type === 'fill' ? (command.items || [])
                                  : [{ ref: command.ref, value: command.value }];
      return doFill(command.id, items, !!command.diff);
    }
    if (type === 'click' || type === 'submit') {
      if (!CONFIG || !CONFIG.write) {
        return Promise.resolve({ t: 'result', id: command.id, ok: false,
                                 error: 'write-ops-disabled' });
      }
      return doClick(command);
    }
    if (type === 'undo') {
      if (!CONFIG || !CONFIG.write) {
        return Promise.resolve({ t: 'result', id: command.id, ok: false,
                                 error: 'write-ops-disabled' });
      }
      return doUndo(command);
    }
    return Promise.resolve({ t: 'result', id: command.id, ok: false, error: 'unknown-command' });
  }

  function pollLoop() {
    if (stopped) { return; }
    if (paused || !nativeFetch) {
      setTimeout(pollLoop, 1000);
      return;
    }
    var wait = Math.min(8000, ((CONFIG.pollSeconds || 8) * 1000));
    nativeFetch(channelUrl(CONFIG.poll, 'wait=' + wait), { credentials: 'same-origin' })
      .then(function (response) {
        if (response.status === 410) { stopped = true; paintBadge(); return []; }
        return response.json();
      })
      .then(function (commands) {
        if (!commands || !commands.length) { return null; }
        return Promise.all(commands.map(function (command) {
          if (command.t === 'gone') { stopped = true; paintBadge(); return null; }
          // One promise per command that ALWAYS resolves: a recipe that throws inside its async chain
          // must still send a result back, or the server waits the whole ceiling for an answer that
          // exists nowhere (it reads exactly like a hang). The per-command catch is the guarantee;
          // reportError stays the diagnostic twin.
          return Promise.resolve().then(function () { return execute(command); })
              .then(function (answer) {
                if (answer && command.id && !answer.id) { answer.id = command.id; }
                return answer;
              })
              .catch(function (error) {
                reportError('command ' + command.t + ': ' + error);
                return { t: 'result', id: command.id, ok: false, op: command.t,
                         error: 'bridge-exception', hint: String(error) };
              });
        })).then(function (answers) {
          var sendable = (answers || []).filter(Boolean);
          if (!sendable.length) { return null; }
          return post(sendable.length === 1 ? sendable[0] : sendable);
        });
      })
      .catch(function (error) { reportError('poll: ' + error); })
      .finally(function () { if (!stopped) { setTimeout(pollLoop, 40); } });
  }

  // ---------------------------------------------------------------- boot

  function boot() {
    if (!session || !nativeFetch) {
      return;   // no session, no fetch: nothing to do (and leave the page alone)
    }
    if (!isTop) {
      // Child documents are traversed by the top frame; a channel of its own would be double work.
      window.__mcpb = { passive: true, version: expectVersion };
      return;
    }
    // "b" is the application prefix - "" in port mode, where the application IS the origin root (§2b).
    // "a" is the channel base path of our endpoints; without that one this document belongs to no
    // session and there is nothing to do. Only "a" can be missing, "b" can be empty by design.
    var base = params.get('b') || '';
    var channel = params.get('a') || '';
    if (!channel) { return; }
    // Provisional configuration from the script URL: the patches have to go in NOW, before the
    // application code runs - the full configuration (secrets, hosts, write) arrives right after via hello.
    CONFIG = { base: base, session: session, channel: channel, root: channel.replace(/\/session\/[^/]+$/, ''),
               write: false, secrets: [], hosts: [], budget: 1200, quiesceMs: 300, pollSeconds: 8,
               poll: channel + '/poll', post: channel + '/post' };
    installPatches();
    observeMutations();
    installEventReporting();
    nativeFetch(channelUrl(channel + '/hello'), { credentials: 'same-origin' })
      .then(function (response) {
        if (!response.ok) { throw new Error('hello HTTP ' + response.status); }
        return response.json();
      })
      .then(function (config) {
        Object.keys(config || {}).forEach(function (key) { CONFIG[key] = config[key]; });
        // What the channel needs is the pair of endpoints and the session they belong to - NOT a
        // non-empty "base": in port mode the application occupies the root of its own origin, so the
        // prefix is legitimately "" (docs/browser.md §2b). A guard on it put the bridge out of service
        // on every port-mode application, quietly, because the page itself proxies fine.
        if (CONFIG.session !== session || !CONFIG.poll || !CONFIG.post) {
          throw new Error('hello answered without the channel of this session');
        }
        paintBadge();
        post({ t: 'hello', frame: framePath, url: location.pathname + location.search,
           title: trimText(document.title, 120),
               frames: frameCount(), bridgeVersion: expectVersion, write: !!CONFIG.write });
        // The first snapshot comes by itself as soon as the page rests: otherwise the LLM would always
        // have to pay one channel round trip first before seeing anything at all.
        quiescent(2500).then(function () { return snapshot(null, true); })
          .then(function (result) { post(result); pollLoop(); })
          .catch(function (error) { reportError('first-snapshot: ' + error); pollLoop(); });
      })
      .catch(function (error) { reportError('boot: ' + error); paintBadge(); });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
})();
