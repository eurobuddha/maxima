// The Parlons app's own vector icons (app/src/main/res/drawable/ic_*.xml), ported path for path.
// Stroke icons use currentColor; never a font glyph (Manrope has none - they render as tofu).
(function () {
  'use strict';
  const S = 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
  const Q = 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="square" stroke-linejoin="miter"';
  const F = 'fill="currentColor"';
  const P = {
    back: '<path ' + S + ' d="M15,5 L8,12 L15,19"/>',
    search: '<path ' + S + ' d="M4.5,11 a6.5,6.5 0 1,0 13,0 a6.5,6.5 0 1,0 -13,0 z M20,20 l-4.2,-4.2"/>',
    more: '<path ' + F + ' d="M10.3,5 a1.7,1.7 0 1,0 3.4,0 a1.7,1.7 0 1,0 -3.4,0 z M10.3,12 a1.7,1.7 0 1,0 3.4,0 a1.7,1.7 0 1,0 -3.4,0 z M10.3,19 a1.7,1.7 0 1,0 3.4,0 a1.7,1.7 0 1,0 -3.4,0 z"/>',
    newchat: '<g fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2,3 L11,3 L11,9 L6,9 L4.6,11.1 L5.3,9 L2,9 Z"/><path d="M12.5,4.5 L21.5,4.5 L21.5,10.5 L16.5,10.5 L15.1,12.6 L15.8,10.5 L12.5,10.5 Z"/><path d="M6.5,10.5 L15.5,10.5 L15.5,16.5 L10.5,16.5 L9.1,18.6 L9.8,16.5 L6.5,16.5 Z"/></g>',
    send: '<path ' + F + ' d="M4,11.6 l15.6,-6.9 c0.7,-0.3 1.4,0.4 1.1,1.1 L13.8,21 c-0.3,0.7 -1.3,0.6 -1.5,-0.1 l-1.7,-6 l-6,-1.7 c-0.8,-0.2 -0.8,-1.2 -0.1,-1.5 z"/>',
    emoji: '<path ' + S + ' d="M3,12 a9,9 0 1,0 18,0 a9,9 0 1,0 -18,0 z"/><path ' + F + ' d="M7.9,10 a1.1,1.1 0 1,0 2.2,0 a1.1,1.1 0 1,0 -2.2,0 z M13.9,10 a1.1,1.1 0 1,0 2.2,0 a1.1,1.1 0 1,0 -2.2,0 z"/><path ' + S + ' d="M8.5,14.5 c0.9,1.2 2.1,1.8 3.5,1.8 c1.4,0 2.6,-0.6 3.5,-1.8"/>',
    attach: '<path ' + S + ' d="M20,11.5 l-7.6,7.6 a5,5 0 0 1 -7,-7 L12.9,4 a3.3,3.3 0 0 1 4.7,4.7 l-8.5,8.5 a1.6,1.6 0 0 1 -2.3,-2.3 l7.6,-7.6"/>',
    camera: '<path ' + S + ' d="M4,8.5 a2,2 0 0 1 2,-2 h1.4 l1,-1.6 a1,1 0 0 1 0.85,-0.5 h5.5 a1,1 0 0 1 0.85,0.5 l1,1.6 H18 a2,2 0 0 1 2,2 v8 a2,2 0 0 1 -2,2 H6 a2,2 0 0 1 -2,-2 z"/><path ' + S + ' d="M8.7,12.5 a3.3,3.3 0 1,0 6.6,0 a3.3,3.3 0 1,0 -6.6,0 z"/>',
    theme: '<path ' + S + ' d="M3,12 a9,9 0 1,0 18,0 a9,9 0 1,0 -18,0 z"/><path ' + F + ' d="M12,3 a9,9 0 0,0 0,18 z"/>',
    call: '<path ' + S + ' d="M6.6,10.8 c1.2,2.4 3.2,4.4 5.6,5.6 l1.9,-1.9 c0.3,-0.3 0.7,-0.4 1,-0.2 c1,0.4 2.1,0.6 3.2,0.6 c0.6,0 1,0.5 1,1 V19 c0,0.6 -0.5,1 -1,1 C10.9,20 4,13.1 4,5 c0,-0.6 0.5,-1 1,-1 h3.1 c0.6,0 1,0.5 1,1 c0,1.1 0.2,2.2 0.6,3.2 c0.1,0.4 0,0.8 -0.3,1 z"/>',
    videocall: '<path ' + S + ' d="M6,6.5 H12.5 A3,3 0 0 1 15.5,9.5 V14.5 A3,3 0 0 1 12.5,17.5 H6 A3,3 0 0 1 3,14.5 V9.5 A3,3 0 0 1 6,6.5 z"/><path ' + S + ' d="M15.5,10 l4.2,-2.3 c0.5,-0.3 1.1,0.1 1.1,0.7 v7.2 c0,0.6 -0.6,1 -1.1,0.7 L15.5,14"/>',
    arrowdown: '<path ' + S + ' d="M6,9.5 L12,15.5 L18,9.5"/>',
    check: '<path ' + Q + ' d="M5,12 L10,17 L20,6"/>',
    copy: '<path ' + Q + ' d="M8,8 H20 V20 H8 Z M16,8 V4 H4 V16 H8"/>',
    qr: '<path ' + Q + ' d="M4,4 H9 V9 H4 Z M15,4 H20 V9 H15 Z M4,15 H9 V20 H4 Z"/><path ' + F + ' d="M6,6 H7.5 V7.5 H6 Z M16.5,6 H18 V7.5 H16.5 Z M6,16.5 H7.5 V18 H6 Z M15,15 H17 V17 H15 Z M18,15 H20 V17 H18 Z M15,18 H17 V20 H15 Z M18,18 H20 V20 H18 Z"/>',
    personadd: '<path ' + Q + ' d="M6,9 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0 M3,20 V18.5 a5.5,5.5 0 0,1 11,0 V20 M17,5 V11 M14,8 H20"/>',
    group: '<path ' + Q + ' d="M7.4,8 a2.6,2.6 0 1,0 5.2,0 a2.6,2.6 0 1,0 -5.2,0 M3.5,20 V18.5 a5,5 0 0,1 10,0 V20 M15.5,6.6 a2.4,2.4 0 1,1 0,4.8 M17,20 V18.5 a5,5 0 0,0 -2,-4"/>',
    trash: '<path ' + Q + ' d="M4,7 H20 M9,7 V4 H15 V7 M6,7 L7,20 H17 L18,7 M10,11 V16 M14,11 V16"/>',
    edit: '<path ' + Q + ' d="M4,16 L14,6 L18,10 L8,20 H4 Z M13,7 L17,11"/>',
    close: '<path ' + Q + ' d="M6,6 L18,18 M18,6 L6,18"/>',
    moon: '<path ' + S + ' d="M20,14.5 A8.5,8.5 0 0 1 9.5,4 A7,7 0 1 0 20,14.5 z"/>',
    sun: '<path ' + S + ' d="M8.5,12 a3.5,3.5 0 1,0 7,0 a3.5,3.5 0 1,0 -7,0 z"/><path ' + S + ' d="M12,3 v2.5 M12,18.5 v2.5 M3,12 h2.5 M18.5,12 h2.5 M5.6,5.6 l1.8,1.8 M16.6,16.6 l1.8,1.8 M18.4,5.6 l-1.8,1.8 M7.4,16.6 l-1.8,1.8"/>',
    photo: '<path ' + S + ' d="M6.5,5 H17.5 A3,3 0 0 1 20.5,8 V16 A3,3 0 0 1 17.5,19 H6.5 A3,3 0 0 1 3.5,16 V8 A3,3 0 0 1 6.5,5 z M5,17 l4.5,-4.5 l3,3 L16,11 l3.5,3.5"/><path ' + F + ' d="M6.9,10 a1.6,1.6 0 1,0 3.2,0 a1.6,1.6 0 1,0 -3.2,0 z"/>',
    play: '<path ' + Q + ' d="M7,5 L19,12 L7,19 Z"/>',
    pause: '<path fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="square" d="M9,5 L9,19 M15,5 L15,19"/>',
    plus: '<path ' + Q + ' d="M12,5 V19 M5,12 H19"/>',
    scan: '<path ' + Q + ' d="M4,8 V4 H8 M16,4 H20 V8 M20,16 V20 H16 M8,20 H4 V16 M3,12 H21"/>',
    mic: '<path ' + S + ' d="M9,6 a3,3 0 0 1 6,0 v5 a3,3 0 0 1 -6,0 z"/><path ' + S + ' d="M6,11 a6,6 0 0 0 12,0 M12,17 v4 M9,21 h6"/>'
  };
  window.icon = function (name, cls) {
    return '<svg class="ic' + (cls ? ' ' + cls : '') + '" viewBox="0 0 24 24" aria-hidden="true">' + (P[name] || '') + '</svg>';
  };
})();
