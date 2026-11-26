// Native app bridge (this file is part of the app, not of the KaraBala10 engine).
// It replaces bridge.js (chrome.* is not available in a WebView) and gives the app a small API:
//   __app.state()        -> { ready, detail }
//   __app.caps()         -> browser support for the encoders
//   __app.make(opts)     -> makes the sticker, keeps it in memory, returns base64 webp/ogg + sizes
//   __app.chats()        -> recent chats
//   __app.send(chatId)   -> sends the last sticker with its audio ("self" = your own chat)
(function () {
  if (window.__app) return;

  // ---- bridge.js replacement: logs stay in memory, "getLog" gets an answer.
  var LOG = [];
  window.addEventListener('message', function (ev) {
    if (ev.source !== window) return;
    var d = ev.data; if (!d || typeof d !== 'object') return;
    if (d.__waas === 'log' && d.entry) { LOG.push(d.entry); if (LOG.length > 2000) LOG.shift(); }
    else if (d.__waas === 'getLog') window.postMessage({ __waas: 'logResponse', reqId: d.reqId, log: LOG.slice() }, location.origin);
    else if (d.__waas === 'clearLog') LOG = [];
  });

  function W() { return window.WAAS && window.WAAS.wa; }
  function prog(t, f) { try { AndroidBridge.progress(String(t), f == null ? -1 : +f); } catch (e) {} }
  var last = null;

  function b64(blob) {
    return new Promise(function (res, rej) {
      var fr = new FileReader();
      fr.onload = function () { var s = String(fr.result); res(s.slice(s.indexOf(',') + 1)); };
      fr.onerror = function () { rej(fr.error); };
      fr.readAsDataURL(blob);
    });
  }
  async function getFile(src) {
    var r = await fetch(src.url, { cache: 'no-store' });
    if (!r.ok) throw new Error('Cannot read the local file (' + r.status + ')');
    var b = await r.blob();
    return new File([b], src.name || 'media', { type: src.mime || b.type });
  }

  window.__app = {
    state: function () {
      var w = W();
      if (!w || !w.hasLoader()) return { ready: false, detail: 'Loading WhatsApp Web…' };
      try {
        var CC = w.ns('WAWebChatCollection').ChatCollection;
        var Me = w.ns('WAWebUserPrefsMeUser');
        var me = (Me.getMaybeMePnUser && Me.getMaybeMePnUser()) || (Me.getMaybeMeLidUser && Me.getMaybeMeLidUser());
        if (!me) return { ready: false, detail: 'Not linked yet' };
        if (!CC || !CC.getModelsArray || CC.getModelsArray().length === 0) return { ready: false, detail: 'Loading chats…' };
        return { ready: true, detail: '' };
      } catch (e) {
        return { ready: false, detail: 'Not linked yet' };
      }
    },

    caps: async function () {
      var c = await window.WAAS.capabilities();
      c.imageDecoder = typeof ImageDecoder !== 'undefined';
      return c;
    },

    make: async function (o) {
      var A = window.WAAS;
      var vfile = await getFile(o.visual);
      var visual = o.visual.kind === 'image'
        ? { kind: 'image', file: vfile }
        : { kind: o.visual.kind, file: vfile, start: o.visual.start, dur: o.visual.dur };
      var audio = o.audio
        ? { file: await getFile(o.audio), start: o.audio.start, dur: o.audio.dur }
        : { file: vfile, start: o.visual.start, dur: o.visual.dur };
      last = null;
      var an = await A.analyze({ visual: visual, audio: audio, fit: 'repetir', mode: o.crop ? 'recortar' : 'ajustar', onProgress: prog });
      try {
        if (!an.auto) throw new Error('No size fits in 1 MB for this clip. Make the clip shorter.');
        var r = await A.create(an, { side: an.auto.side, fps: an.auto.fps, onProgress: prog });
        last = r;
        return { webp: await b64(r.webp), ogg: await b64(r.ogg), webpBytes: r.webp.size, oggBytes: r.ogg.size,
                 seconds: r.info.segundosAudio };
      } finally {
        A.release(an);
      }
    },

    chats: function () {
      var CC = W().ns('WAWebChatCollection').ChatCollection;
      return CC.getModelsArray().slice(0, 100).map(function (c) {
        var id = c.id;
        return { id: String((id && id._serialized) || id),
                 title: String(c.formattedTitle || c.name || '').trim() || '(no name)',
                 group: !!(id && id.isGroup && id.isGroup()) };
      });
    },

    send: async function (chatId) {
      if (!last) throw new Error('Make a sticker first.');
      var w = W();
      if (!window.__app.state().ready) throw new Error('WhatsApp is not ready.');
      try { w.hookWALogger(); } catch (e) {}
      w.forceSoundStickers();
      var chat = chatId === 'self' ? await w.getSelfChat() : w.ns('WAWebChatCollection').ChatCollection.get(chatId);
      if (!chat) throw new Error('Chat not found.');
      prog('Uploading audio…');
      var sa = await w.uploadAudio(last.ogg, last.info.segundosAudio, chat);
      prog('Sending sticker…');
      var msg = await w.sendSticker(chat, last.webp, last.info, sa);
      try { await w.waitForAck(msg, 20000); } catch (e) {}
      return String(chat.formattedTitle || chat.name || 'chat');
    }
  };
})();
