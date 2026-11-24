// Agent Threads relay (Cloudflare Worker + one Durable Object per Mac).
//
//   Mac bridge ──(outbound WebSocket, GET /mac)──▶ MacRelay ◀──(HTTPS)── watch, from any network
//
// The watch's requests are forwarded to the Mac over that WebSocket and the answers sent back.
// Both sides prove they hold the bridge token; the relay keeps only its SHA-256 (secret
// TOKEN_SHA256) and stores nothing. Only the endpoints the wrist app uses are forwarded.
import { DurableObject } from 'cloudflare:workers';

/** What the watch may reach through the relay. Everything else (typing into the front app, test
 *  threads, hooks) stays local to the Mac. */
const ALLOWED = [
  ['GET', '/conversations'],
  ['GET', '/conversation'],
  ['POST', '/conversation/reply'],
  ['POST', '/action'],
  ['GET', '/image'],
  ['POST', '/voice-transcribe'],
];

const MAX_BODY = 8 * 1024 * 1024;       // a long voice reply is well under this
const CHUNK = 512 * 1024;               // WebSocket messages stay under the 1 MiB limit
const REQUEST_TIMEOUT_MS = 45_000;      // speech-to-text on the Mac can take a while

async function sha256Hex(text) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
}

/** Constant-time check of a presented token against the stored hash. */
async function tokenOk(token, env) {
  if (!token || !env.TOKEN_SHA256) return false;
  const a = await sha256Hex(token);
  const b = String(env.TOKEN_SHA256).trim().toLowerCase();
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function json(status, obj) {
  return new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' } });
}

function toB64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

function fromB64(b64) {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // One relay object per Mac, named by the token hash (so a second Mac with its own token gets its own).
    const stub = env.RELAY.getByName(String(env.TOKEN_SHA256 || 'unset').slice(0, 16));

    if (url.pathname === '/mac') {
      const bearer = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '');
      if (!(await tokenOk(bearer, env))) return json(401, { error: 'Unauthorized' });
      if (request.headers.get('Upgrade') !== 'websocket') return json(426, { error: 'WebSocket required' });
      return stub.fetch(request);
    }

    if (url.pathname === '/healthz') {
      const token = request.headers.get('X-HUD-Token') || '';
      if (!(await tokenOk(token, env))) return json(401, { error: 'Unauthorized' });
      return json(200, { ok: true, macConnected: await stub.macConnected() });
    }

    if (!ALLOWED.some(([m, p]) => m === request.method && p === url.pathname)) return json(404, { error: 'Not found' });
    if (request.headers.get('Origin')) return json(401, { error: 'Unauthorized' }); // never callable from a web page
    const token = request.headers.get('X-HUD-Token') || '';
    if (!(await tokenOk(token, env))) return json(401, { error: 'Unauthorized' });

    let body = '';
    if (request.method === 'POST') {
      const bytes = new Uint8Array(await request.arrayBuffer());
      if (bytes.length > MAX_BODY) return json(413, { error: 'Too large' });
      body = toB64(bytes);
    }
    const res = await stub.relay({
      method: request.method,
      path: url.pathname,
      query: url.search,
      contentType: request.headers.get('Content-Type') || '',
      contentEncoding: request.headers.get('Content-Encoding') || '',
      ifNoneMatch: request.headers.get('If-None-Match') || '',
      token,
      body,
    });
    const headers = { 'Cache-Control': 'no-store' };
    if (res.contentType) headers['Content-Type'] = res.contentType;
    if (res.etag) headers['ETag'] = res.etag;
    return new Response(res.status === 304 || !res.body ? null : fromB64(res.body), { status: res.status, headers });
  },
};

export class MacRelay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.pending = new Map(); // request id -> { resolve, timer, parts, meta }
    this.incoming = new Map(); // chunked response assembly
    this.nextId = 1;
    // Keep-alive pings are answered without waking the object (hibernation).
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
  }

  /** The Mac connects (or reconnects): the newest connection replaces any older one. */
  async fetch(request) {
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    for (const old of this.ctx.getWebSockets('mac')) {
      try { old.close(4000, 'Replaced by a newer connection'); } catch {}
    }
    this.ctx.acceptWebSocket(server, ['mac']);
    return new Response(null, { status: 101, webSocket: client });
  }

  async macConnected() {
    return this.ctx.getWebSockets('mac').length > 0;
  }

  /** Forwards one watch request to the Mac and waits for its answer. */
  async relay(req) {
    const ws = this.ctx.getWebSockets('mac')[0];
    if (!ws) return { status: 503, contentType: 'application/json', body: toB64(new TextEncoder().encode(JSON.stringify({ error: 'The Mac is offline' }))) };
    const id = `r${this.nextId++}`;
    const chunks = [];
    for (let i = 0; i < req.body.length; i += CHUNK) chunks.push(req.body.slice(i, i + CHUNK));
    const meta = {
      t: 'req', id, method: req.method, path: req.path, query: req.query,
      contentType: req.contentType, contentEncoding: req.contentEncoding || '',
      ifNoneMatch: req.ifNoneMatch || '', token: req.token, parts: chunks.length,
    };
    const answer = new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        this.incoming.delete(id);
        resolve({ status: 504, contentType: 'application/json', body: toB64(new TextEncoder().encode(JSON.stringify({ error: 'The Mac took too long' }))) });
      }, REQUEST_TIMEOUT_MS);
      this.pending.set(id, { resolve, timer });
    });
    try {
      ws.send(JSON.stringify(meta));
      chunks.forEach((data, i) => ws.send(JSON.stringify({ t: 'part', id, i, data })));
    } catch {
      const p = this.pending.get(id);
      if (p) { clearTimeout(p.timer); this.pending.delete(id); }
      return { status: 503, contentType: 'application/json', body: toB64(new TextEncoder().encode(JSON.stringify({ error: 'The Mac is offline' }))) };
    }
    return answer;
  }

  /** Answers from the Mac: { t:'res', id, status, contentType, parts } then parts { t:'part', id, i, data }. */
  async webSocketMessage(ws, message) {
    if (typeof message !== 'string') return;
    let msg;
    try { msg = JSON.parse(message); } catch { return; }
    if (msg.t === 'res') {
      if (!this.pending.has(msg.id)) return;
      const entry = { status: msg.status, contentType: msg.contentType || '', etag: msg.etag || '', parts: new Array(msg.parts || 0), got: 0 };
      if (!msg.parts) return this.finish(msg.id, entry);
      this.incoming.set(msg.id, entry);
    } else if (msg.t === 'part') {
      const entry = this.incoming.get(msg.id);
      if (!entry || entry.parts[msg.i] !== undefined) return;
      entry.parts[msg.i] = msg.data;
      if (++entry.got === entry.parts.length) this.finish(msg.id, entry);
    }
  }

  finish(id, entry) {
    const p = this.pending.get(id);
    this.incoming.delete(id);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(id);
    p.resolve({ status: entry.status, contentType: entry.contentType, etag: entry.etag, body: entry.parts.join('') });
  }

  async webSocketClose(ws, code, reason) {
    try { ws.close(code, reason); } catch {}
  }
}
