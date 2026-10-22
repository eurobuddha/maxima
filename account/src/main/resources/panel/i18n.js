/* Explicit device-local language selection. Lookup/interpolation adapted from
 * mininotes/office-engine/packages/shared/src/i18n.ts. No language detection. */
(function (root) {
  'use strict';
  const messages = root.ParlonsMessages;
  const languages = Object.freeze([
    { code: 'en', name: 'English' }, { code: 'zh-Hans', name: '简体中文' }, { code: 'ru', name: 'Русский' }, { code: 'uk', name: 'Українська' }
  ]);
  const storageKey = 'parlons.language';
  const valid = code => languages.some(language => language.code === code);
  let language = 'en', chosen = false;
  try { const saved = root.localStorage.getItem(storageKey); if (valid(saved)) { language = saved; chosen = true; } } catch (_) {}
  const listeners = new Set(), bindings = new WeakMap(), renderers = new WeakMap();
  // Compatibility for fixed system messages from the existing call/file protocols.
  // Callers opt in explicitly; never run this on chat text, names or file names.
  const systemKeys = new Map(Object.entries(messages.en).filter(([key, text]) => typeof text === 'string').map(([key, text]) => [text, key]));
  function t(key, vars = {}) {
    let text = messages[language][key] || messages.en[key];
    if (text == null) throw new Error('Unknown translation key: ' + key);
    if (typeof text !== 'string') text = text[new Intl.PluralRules(language).select(Number(vars.count))] || text.other;
    return text.replace(/\{(\w+)\}/g, (match, name) => Object.hasOwn(vars, name) ? String(vars[name]) : match);
  }
  const diagnosticPrefixes = [
    [
        "resync refused: ",
        "serverError.resyncRefused",
        "error"
    ],
    [
        "node error: ",
        "serverError.nodeFailure",
        "error"
    ],
    [
        "hosting failed: ",
        "serverError.hostFailure",
        "error"
    ],
    [
        "could not create the collection folder: ",
        "serverError.collectionFolder",
        "error"
    ],
    [
        "could not list: ",
        "serverError.listFailure",
        "error"
    ],
    [
        "could not delete: ",
        "serverError.deleteFailure",
        "error"
    ],
    [
        "unknown contact ",
        "iosError.unknownContact",
        "id"
    ],
    [
        "chunk unavailable: ",
        "iosError.chunkUnavailable",
        "id"
    ]
];
  function system(value, depth = 0, diagnosticOnly = false) {
    if (typeof value !== 'string' || depth >= 8) return value;
    const key = systemKeys.get(value);
    if (key && (!diagnosticOnly || key.startsWith('serverError.') || key.startsWith('pairing.'))) return t(key);
    for (const [prefix, key, argument] of diagnosticPrefixes) if (value.startsWith(prefix)) {
      const tail = value.slice(prefix.length);
      return t(key, {[argument]: argument === 'error' ? system(tail, depth + 1, true) : tail});
    }
    for (const [prefix, key, argument] of [
      ['can only RAISE above the current ', 'serverError.counterAboveCurrent', 'current'],
      ["above the key's maximum ", 'serverError.counterAboveMaximum', 'maximum']
    ]) if (value.startsWith(prefix)) {
      const number = value.slice(prefix.length);
      if (number.length && !/[^0-9]/.test(number)) return t(key, {[argument]: number});
    }
    const group = /^a group holds at most ([0-9]+) members [(]([0-9]+) asked[)]$/.exec(value);
    if (group && group[0].length === value.length) return t('serverError.groupLimit', {maximum: group[1], requested: group[2]});
    return value;
  }
  function apply(node = document) {
    const elements = [...node.querySelectorAll('[data-i18n], [data-i18n-render], [data-i18n-title], [data-i18n-placeholder], [data-i18n-aria-label], [data-i18n-alt]')];
    if (node.nodeType === 1) elements.unshift(node);
    for (const el of elements) {
      if (renderers.has(el)) el.textContent = renderers.get(el)();
      const vars = bindings.get(el) || JSON.parse(el.getAttribute('data-i18n-vars') || '{}');
      for (const attribute of ['text', 'title', 'placeholder', 'aria-label', 'alt']) {
        const key = el.getAttribute(attribute === 'text' ? 'data-i18n' : 'data-i18n-' + attribute);
        if (!key) continue;
        if (attribute === 'text') el.textContent = t(key, vars);
        else el.setAttribute(attribute, t(key, vars));
      }
    }
  }
  function bind(el, key, vars = {}) {
    el.removeAttribute('data-i18n-render'); renderers.delete(el);
    el.setAttribute('data-i18n', key); bindings.set(el, vars); el.textContent = t(key, vars);
  }
  function render(el, formatter) {
    el.removeAttribute('data-i18n'); bindings.delete(el);
    el.setAttribute('data-i18n-render', ''); renderers.set(el, formatter); el.textContent = formatter();
  }
  function text(el, value) {
    el.removeAttribute('data-i18n'); el.removeAttribute('data-i18n-render');
    bindings.delete(el); renderers.delete(el); el.textContent = value;
  }
  function attribute(el, name, key, vars = {}) {
    if (!['title', 'placeholder', 'aria-label', 'alt'].includes(name)) throw new Error('Unsupported translated attribute');
    el.setAttribute('data-i18n-' + name, key); bindings.set(el, vars); el.setAttribute(name, t(key, vars));
  }
  function setLanguage(code) {
    if (!valid(code)) throw new Error('Unsupported language');
    language = code; chosen = true;
    try { root.localStorage.setItem(storageKey, code); } catch (_) { /* remains usable when storage is unavailable */ }
    document.documentElement.lang = code;
    apply();
    for (const listener of listeners) listener(code);
  }
  function chooser(required = false) {
    const existing = document.getElementById('languageDialog');
    if (existing) { existing.focus(); return; }
    const dialog = document.createElement('dialog'); dialog.id = 'languageDialog'; dialog.className = 'language-dialog';
    dialog.setAttribute('aria-labelledby', 'languageHeading');
    const title = document.createElement('h2'); title.id = 'languageHeading'; title.textContent = 'Language · 语言 · Язык · Мова'; dialog.appendChild(title);
    for (const option of languages) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'language-option';
      button.lang = option.code; button.textContent = option.name;
      button.setAttribute('aria-pressed', String(chosen && option.code === language));
      button.addEventListener('click', () => { setLanguage(option.code); dialog.close(); }); dialog.appendChild(button);
    }
    if (!required) {
      const close = document.createElement('button'); close.type = 'button'; close.className = 'btn ghost full'; bind(close, 'language.close');
      close.addEventListener('click', () => dialog.close()); dialog.appendChild(close);
    } else dialog.addEventListener('cancel', event => event.preventDefault());
    dialog.addEventListener('close', () => dialog.remove(), { once: true });
    document.body.appendChild(dialog); dialog.showModal();
  }
  document.documentElement.lang = language;
  // Some existing screens append HTML directly. Only marked app labels are translated;
  // text-node changes are ignored so applying labels cannot trigger an observer loop.
  if (root.MutationObserver) {
    new root.MutationObserver(records => {
      for (const record of records) for (const node of record.addedNodes) {
        if (node.nodeType === 1) apply(node);
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
  }
  root.ParlonsI18n = Object.freeze({ languages, t, apply, bind, render, text, attribute, chooser, setLanguage,
    system,
    getLanguage: () => language, hasChoice: () => chosen,
    onChange(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    date(value, options) { return new Intl.DateTimeFormat(language, options).format(new Date(Number(value))); },
    relative(value, unit) { return new Intl.RelativeTimeFormat(language, { numeric: 'always' }).format(value, unit); }
  });
})(window);
