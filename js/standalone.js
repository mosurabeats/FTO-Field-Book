// Standalone (home-screen) runtime. Provides the same `claude.use(...)` surface the app
// uses inside Claude, backed by the phone itself: files go to the iOS share sheet,
// dictation uses the browser's speech recognition, and Claude drafting uses an
// optional Anthropic API key saved on the device. No account sync: data stays local.
window.FTO_STANDALONE = true;
(function () {
  const KEY = 'fto_api_key';
  const TYPES = { docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', pdf: 'application/pdf', json: 'application/json', txt: 'text/plain', html: 'text/html' };

  const downloads = {
    async save({ filename, data }) {
      const ext = filename.split('.').pop().toLowerCase();
      const blob = data instanceof Blob ? data : new Blob([data]);
      const file = new File([blob], filename, { type: TYPES[ext] || 'application/octet-stream' });
      if (navigator.canShare && navigator.canShare({ files: [file] })) {
        try { await navigator.share({ files: [file], title: filename }); return { status: 'saved' }; }
        catch (e) { if (e && e.name === 'AbortError') throw { code: 'declined' }; /* else fall through to download */ }
      }
      const url = URL.createObjectURL(file);
      const a = document.createElement('a'); a.href = url; a.download = filename; document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 5000);
      return { status: 'saved' };
    }
  };

  function dictation() {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return null;
    let rec = null; const fin = [], par = [], st = [];
    const emit = (list, x) => list.forEach((f) => { try { f(x); } catch (e) {} });
    return {
      onFinal: (f) => fin.push(f), onPartial: (f) => par.push(f), onState: (f) => st.push(f),
      start() {
        return new Promise((res, rej) => {
          rec = new SR(); rec.continuous = true; rec.interimResults = true; rec.lang = 'en-US';
          rec.onstart = () => { emit(st, { state: 'listening' }); res(); };
          rec.onresult = (e) => { let interim = ''; for (let i = e.resultIndex; i < e.results.length; i++) { const r = e.results[i]; if (r.isFinal) emit(fin, { text: r[0].transcript }); else interim += r[0].transcript; } emit(par, { text: interim }); };
          rec.onerror = (e) => { if (e.error === 'not-allowed' || e.error === 'service-not-allowed') rej({ code: 'not_granted' }); };
          rec.onend = () => emit(st, { state: 'idle', reason: 'stopped' });
          try { rec.start(); } catch (e) { rej({ code: 'dismissed' }); }
        });
      },
      stop() { return new Promise((r) => { if (!rec) return r(); const done = rec.onend; rec.onend = () => { done && done(); r(); }; rec.stop(); }); }
    };
  }

  function sampler() {
    const key = localStorage.getItem(KEY);
    if (!key) return null;
    const call = async (input, opts = {}) => {
      const messages = typeof input === 'string' ? [{ role: 'user', content: input }] : input;
      let r;
      try {
        r = await fetch('https://api.anthropic.com/v1/messages', {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-api-key': key, 'anthropic-version': '2023-06-01', 'anthropic-dangerous-direct-browser-access': 'true' },
          body: JSON.stringify({ model: localStorage.getItem('fto_model') || 'claude-sonnet-5-5', max_tokens: 4096, messages })
        });
      } catch (e) { throw { code: 'unavailable', message: 'No connection' }; }
      if (r.status === 429 || r.status === 529) throw { code: 'rate_limited' };
      if (r.status === 401 || r.status === 403) throw { code: 'bad_key', message: 'API key was rejected' };
      if (!r.ok) throw { code: 'error', message: await r.text() };
      const j = await r.json();
      const text = (j.content || []).filter((c) => c.type === 'text').map((c) => c.text).join('');
      if (opts.onText) opts.onText({ text, delta: text });
      return { text, truncated: j.stop_reason === 'max_tokens' };
    };
    call.json = async (input, opts) => { const { text } = await call(input, opts); const m = text.match(/\{[\s\S]*\}/); return JSON.parse(m ? m[0] : text); };
    return call;
  }
  window.__ftoSampler = sampler;

  window.claude = {
    use: async (name) => name === 'downloads' ? downloads : name === 'dictation' ? dictation() : name === 'sample' ? sampler() : null
  };

  try { navigator.storage && navigator.storage.persist && navigator.storage.persist(); } catch (e) {}
  if ('serviceWorker' in navigator && location.protocol === 'https:') {
    window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
  }
})();
