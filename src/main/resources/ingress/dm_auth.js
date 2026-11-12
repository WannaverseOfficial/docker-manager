// Sign-in for password-protected ingress routes. Managed by Docker Manager.
//
// A route's users live in /etc/nginx/auth/<route>.htpasswd (bcrypt). Visitors sign in on a page
// served here and get a signed cookie; scripts can send an Authorization: Basic header instead,
// which nginx checks itself. Passwords from the form are checked by asking nginx's own basic-auth
// module on a loopback-only port, so there is a single password store.

import fs from 'fs';
import qs from 'querystring';
import crypto from 'crypto';

const AUTH_DIR = '/etc/nginx/auth';
const CHECK_URL = 'http://127.0.0.1:8088/check/';
const MAX_AGE = 7 * 24 * 3600;
const ID = /^[A-Za-z0-9-]{1,64}$/;

function routeId(r) {
    const id = r.variables.dm_route || '';
    if (!ID.test(id)) throw new Error('invalid route id');
    return id;
}

function routeKey(id) {
    return fs.readFileSync(AUTH_DIR + '/' + id + '.key', 'utf8').trim();
}

function cookieName(id) {
    return 'dm_auth_' + id.replace(/[^A-Za-z0-9]/g, '').slice(0, 16);
}

function signature(id, user, exp) {
    return crypto.createHmac('sha256', routeKey(id)).update(id + '|' + user + '|' + exp).digest('base64url');
}

function readCookie(r, name) {
    const header = r.headersIn.Cookie || '';
    const parts = header.split(';');
    for (let n = 0; n < parts.length; n++) {
        const part = parts[n];
        const i = part.indexOf('=');
        if (i > 0 && part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
    }
    return null;
}

function sameText(a, b) {
    // Compare digests so the time taken does not depend on where the strings differ.
    const ha = crypto.createHash('sha256').update(a).digest('hex');
    const hb = crypto.createHash('sha256').update(b).digest('hex');
    return ha === hb;
}

function sessionUser(r, id) {
    const value = readCookie(r, cookieName(id));
    if (!value) return null;
    const parts = value.split('.');
    if (parts.length !== 3) return null;
    const exp = Number(parts[1]);
    if (!(exp > Date.now() / 1000)) return null;
    let user;
    try {
        user = Buffer.from(parts[0], 'base64url').toString();
    } catch (e) {
        return null;
    }
    return sameText(parts[2], signature(id, user, parts[1])) ? user : null;
}

/** auth_request handler: 204 with a valid session cookie, otherwise 401. */
function verify(r) {
    try {
        r.return(sessionUser(r, routeId(r)) ? 204 : 401);
    } catch (e) {
        r.error('dm_auth verify: ' + e.message);
        r.return(401);
    }
}

/** Content handler for the loopback check location; reached only after basic auth passed. */
function ok(r) {
    r.return(204);
}

function loginUrl(id, next) {
    return '/.dm-auth/' + id + '/login?next=' + encodeURIComponent(next || '/');
}

/** Not signed in: browsers go to the sign-in page, scripts get a 401. */
function challenge(r) {
    const id = routeId(r);
    const accept = r.headersIn.Accept || '';
    const browser = (r.method === 'GET' || r.method === 'HEAD') && accept.indexOf('text/html') >= 0;
    if (!browser || r.headersIn.Authorization) {
        r.headersOut['WWW-Authenticate'] = 'Basic realm="' + (r.variables.host || 'Restricted') + '", charset="UTF-8"';
        r.headersOut['Content-Type'] = 'text/plain; charset=utf-8';
        r.return(401, 'Sign-in required\n');
        return;
    }
    r.return(302, loginUrl(id, r.variables.request_uri));
}

function safeNext(next) {
    if (typeof next !== 'string' || next.length > 2000 || next[0] !== '/') return '/';
    // Browsers drop tabs and newlines and treat \ as /, so "/\t/evil.com" or "/\evil.com" would
    // become "//evil.com", another site. Only plain same-site paths are allowed.
    if (/[\x00-\x1f\x7f\\]/.test(next) || next[1] === '/') return '/';
    return next;
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function page(r, id, next, user, error) {
    const host = escapeHtml(r.variables.host || '');
    const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Sign in · ${host}</title>
<style>
:root{--fog:#0b0b0c;--paper:#161618;--well:#1f1f22;--line:#2a2a2e;--ink:#f2f2f3;--slate:#a1a1aa;--accent:#f2f2f3;--accent-ink:#0b0b0c;--danger:#f87171;color-scheme:dark}
@media (prefers-color-scheme: light){:root{--fog:#fafafa;--paper:#fff;--well:#f4f4f5;--line:#e4e4e7;--ink:#18181b;--slate:#71717a;--accent:#18181b;--accent-ink:#fff;--danger:#dc2626;color-scheme:light}}
*{box-sizing:border-box}
body{margin:0;min-height:100dvh;display:grid;place-items:center;padding:24px 16px;background:var(--fog);color:var(--ink);font:15px/1.5 Inter,ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}
main{width:100%;max-width:360px}
.card{background:var(--paper);border:1px solid var(--line);border-radius:20px;padding:28px}
.lock{width:40px;height:40px;border-radius:12px;background:var(--well);display:grid;place-items:center;margin-bottom:18px}
h1{font-size:1.35rem;font-weight:600;margin:0;letter-spacing:-.01em;overflow-wrap:anywhere}
p{margin:4px 0 22px;color:var(--slate);font-size:14px}
label{display:block;font-size:13px;color:var(--slate);margin:0 0 6px}
input{width:100%;height:42px;border-radius:10px;border:1px solid var(--line);background:var(--well);color:var(--ink);padding:0 12px;font:inherit;margin-bottom:14px}
input:focus{outline:2px solid var(--ink);outline-offset:1px}
button{width:100%;height:42px;border:0;border-radius:999px;background:var(--accent);color:var(--accent-ink);font:inherit;font-weight:500;cursor:pointer;margin-top:6px}
button:hover{opacity:.88}
.error{color:var(--danger);font-size:14px;margin:-4px 0 14px}
footer{text-align:center;color:var(--slate);font-size:12px;margin-top:16px}
</style>
</head>
<body>
<main>
<form class="card" method="post" action="/.dm-auth/${id}/login">
<div class="lock" aria-hidden="true"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg></div>
<h1>${host}</h1>
<p>Sign in to continue.</p>
${error ? `<div class="error" role="alert">${escapeHtml(error)}</div>` : ''}
<input type="hidden" name="next" value="${escapeHtml(next)}">
<label for="u">Username</label>
<input id="u" name="username" autocomplete="username" autocapitalize="off" spellcheck="false" required value="${escapeHtml(user || '')}" ${user ? '' : 'autofocus'}>
<label for="p">Password</label>
<input id="p" name="password" type="password" autocomplete="current-password" required ${user ? 'autofocus' : ''}>
<button type="submit">Sign in</button>
</form>
<footer>Protected by Docker Manager</footer>
</main>
</body>
</html>`;
    r.headersOut['Content-Type'] = 'text/html; charset=utf-8';
    r.headersOut['Cache-Control'] = 'no-store';
    r.headersOut['X-Frame-Options'] = 'DENY';
    r.headersOut['Content-Security-Policy'] = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'";
    r.return(error ? 401 : 200, html);
}

async function login(r) {
    let id;
    try {
        id = routeId(r);
    } catch (e) {
        r.return(404);
        return;
    }
    if (r.method === 'GET' || r.method === 'HEAD') {
        if (sessionUser(r, id)) {
            r.return(302, safeNext(r.args.next));
            return;
        }
        page(r, id, safeNext(r.args.next), '', null);
        return;
    }
    if (r.method !== 'POST') {
        r.return(405);
        return;
    }
    const form = qs.parse(r.requestText || '');
    const user = String(form.username || '').trim();
    const password = String(form.password || '');
    const next = safeNext(form.next);
    if (!user || !password || user.indexOf(':') >= 0) {
        page(r, id, next, user, 'Enter your username and password.');
        return;
    }
    let reply;
    try {
        reply = await ngx.fetch(CHECK_URL + id, {
            headers: { Authorization: 'Basic ' + Buffer.from(user + ':' + password).toString('base64') },
        });
    } catch (e) {
        r.error('dm_auth check failed: ' + e.message);
        page(r, id, next, user, 'Signing in is not available right now. Try again in a moment.');
        return;
    }
    if (reply.status !== 204) {
        page(r, id, next, user, 'That username and password don’t match.');
        return;
    }
    const exp = String(Math.floor(Date.now() / 1000) + MAX_AGE);
    const value = Buffer.from(user).toString('base64url') + '.' + exp + '.' + signature(id, user, exp);
    const secure = r.variables.scheme === 'https' ? '; Secure' : '';
    r.headersOut['Set-Cookie'] = cookieName(id) + '=' + value + '; Path=/; Max-Age=' + MAX_AGE + '; HttpOnly; SameSite=Lax' + secure;
    r.headersOut['Cache-Control'] = 'no-store';
    r.return(302, next);
}

function logout(r) {
    let id;
    try {
        id = routeId(r);
    } catch (e) {
        r.return(404);
        return;
    }
    r.headersOut['Set-Cookie'] = cookieName(id) + '=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax';
    r.return(302, loginUrl(id, '/'));
}

// --- Docker Manager sign-in for Grafana ------------------------------------------------------
//
// Docker Manager signs a short-lived token (RS256 JWT) for an administrator and posts it to
// /.dm-sso/<route>/callback, which keeps it in an HttpOnly cookie. Every request to Grafana then
// carries it in the X-DM-Grafana-Token header, and Grafana checks the signature with the public
// key Docker Manager gave it, so nginx only needs to look at the expiry here. Visitors without a
// valid token who open Grafana's sign-in page are sent to Docker Manager for a new one.

const JWT = /^[A-Za-z0-9_-]{10,2000}\.[A-Za-z0-9_-]{10,4000}\.[A-Za-z0-9_-]{10,1000}$/;

function ssoCookieName(id) {
    return 'dm_grafana_' + id.replace(/[^A-Za-z0-9]/g, '').slice(0, 16);
}

/** Seconds until the token expires, or 0 when it is malformed or expired. */
function tokenLife(token) {
    if (!token || !JWT.test(token)) return 0;
    try {
        const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
        const left = Math.floor(Number(claims.exp) - Date.now() / 1000);
        return left > 30 ? left : 0;
    } catch (e) {
        return 0;
    }
}

/** js_set: the visitor's token while it is valid, otherwise empty (no header is sent). */
function grafanaToken(r) {
    let id;
    try {
        id = routeId(r);
    } catch (e) {
        return '';
    }
    const token = readCookie(r, ssoCookieName(id));
    return tokenLife(token) > 0 ? token : '';
}

function hasArg(uri, name) {
    const q = String(uri || '').split('?')[1] || '';
    return q.split('&').some((p) => p.split('=')[0] === name);
}

/** auth_request for Grafana's sign-in page: let it through, or send the visitor to Docker Manager. */
function ssoCheck(r) {
    try {
        const method = r.variables.request_method;
        if (method !== 'GET' && method !== 'HEAD') {
            // Grafana's own password form posts here; Grafana checks the password.
            r.return(204);
            return;
        }
        if (grafanaToken(r) || hasArg(r.variables.request_uri, 'local')) {
            r.return(204);
            return;
        }
        r.return(401);
    } catch (e) {
        r.error('dm_auth ssoCheck: ' + e.message);
        r.return(401);
    }
}

function ssoChallenge(r) {
    const accept = r.headersIn.Accept || '';
    const target = String(r.variables.dm_sso_url || '');
    if (accept.indexOf('text/html') < 0 || !/^https?:\/\//.test(target)) {
        r.headersOut['Content-Type'] = 'text/plain; charset=utf-8';
        r.return(401, 'Sign in with Docker Manager\n');
        return;
    }
    // Afterwards, back to the page the visitor first asked for: Grafana passes it to its sign-in
    // page as ?redirectTo=. Without it, the sign-in page itself, which sends signed-in visitors on.
    let next = safeNext(r.variables.request_uri);
    const asked = /[?&]redirectTo=([^&#]*)/.exec(r.variables.request_uri || '');
    if (asked && next.split('?')[0].endsWith('/login')) {
        try {
            next = safeNext(decodeURIComponent(asked[1]));
        } catch (e) {
            // Keep the sign-in page.
        }
    }
    r.headersOut['Cache-Control'] = 'no-store';
    r.return(302, target + (target.indexOf('?') >= 0 ? '&' : '?') + 'next=' + encodeURIComponent(next));
}

// Docker Manager opens the callback with the token in the fragment (#token=...&next=...), which
// browsers never send to a server, so it stays out of logs and Referer headers. This page reads
// it, removes it from the address bar and history, and posts it back here.
const HANDOFF_SCRIPT =
    "(function(){var p=new URLSearchParams(location.hash.slice(1));" +
    "history.replaceState(null,'',location.pathname);" +
    "var f=document.getElementById('f');f.elements.token.value=p.get('token')||'';" +
    "f.elements.next.value=p.get('next')||'/';f.submit();})();";

function handoffPage(r) {
    const hash = crypto.createHash('sha256').update(HANDOFF_SCRIPT).digest('base64');
    r.headersOut['Content-Type'] = 'text/html; charset=utf-8';
    r.headersOut['Cache-Control'] = 'no-store';
    r.headersOut['Referrer-Policy'] = 'no-referrer';
    r.headersOut['X-Frame-Options'] = 'DENY';
    r.headersOut['Content-Security-Policy'] =
        "default-src 'none'; script-src 'sha256-" + hash + "'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
    r.return(
        200,
        '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="robots" content="noindex">' +
            '<title>Signing in</title></head><body>' +
            '<form id="f" method="post"><input type="hidden" name="token"><input type="hidden" name="next"></form>' +
            '<noscript>Signing in to Grafana needs JavaScript.</noscript>' +
            '<script>' + HANDOFF_SCRIPT + '</script></body></html>'
    );
}

/** The signed token arrives here; keep it in a cookie and continue to Grafana. */
function ssoCallback(r) {
    let id;
    try {
        id = routeId(r);
    } catch (e) {
        r.return(404);
        return;
    }
    if (r.method === 'GET' || r.method === 'HEAD') {
        handoffPage(r);
        return;
    }
    if (r.method !== 'POST') {
        r.return(405);
        return;
    }
    const form = qs.parse(r.requestText || '');
    const token = String(form.token || '');
    const life = tokenLife(token);
    if (!life) {
        r.headersOut['Content-Type'] = 'text/plain; charset=utf-8';
        r.return(400, 'This sign-in link has expired. Open Grafana from Docker Manager again.\n');
        return;
    }
    const secure = r.variables.scheme === 'https' ? '; Secure' : '';
    r.headersOut['Set-Cookie'] = ssoCookieName(id) + '=' + token + '; Path=/; Max-Age=' + life + '; HttpOnly; SameSite=Lax' + secure;
    r.headersOut['Cache-Control'] = 'no-store';
    r.headersOut['Referrer-Policy'] = 'no-referrer';
    r.return(302, safeNext(form.next));
}

/** Grafana's sign-out ends here: forget the token and offer to sign in again. */
function ssoSignedOut(r) {
    let id;
    try {
        id = routeId(r);
    } catch (e) {
        r.return(404);
        return;
    }
    const host = escapeHtml(r.variables.host || '');
    r.headersOut['Set-Cookie'] = ssoCookieName(id) + '=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax';
    r.headersOut['Content-Type'] = 'text/html; charset=utf-8';
    r.headersOut['Cache-Control'] = 'no-store';
    r.headersOut['X-Frame-Options'] = 'DENY';
    r.headersOut['Content-Security-Policy'] = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'";
    r.return(
        200,
        `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Signed out · ${host}</title>
<style>
:root{--fog:#0b0b0c;--paper:#161618;--line:#2a2a2e;--ink:#f2f2f3;--slate:#a1a1aa;--accent:#f2f2f3;--accent-ink:#0b0b0c;color-scheme:dark}
@media (prefers-color-scheme: light){:root{--fog:#fafafa;--paper:#fff;--line:#e4e4e7;--ink:#18181b;--slate:#71717a;--accent:#18181b;--accent-ink:#fff;color-scheme:light}}
body{margin:0;min-height:100dvh;display:grid;place-items:center;padding:24px 16px;background:var(--fog);color:var(--ink);font:15px/1.5 Inter,ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}
main{width:100%;max-width:360px;background:var(--paper);border:1px solid var(--line);border-radius:20px;padding:28px}
h1{font-size:1.35rem;font-weight:600;margin:0}
p{margin:4px 0 22px;color:var(--slate);font-size:14px}
a.button{display:block;text-align:center;height:42px;line-height:42px;border-radius:999px;background:var(--accent);color:var(--accent-ink);text-decoration:none;font-weight:500}
a.quiet{display:block;text-align:center;margin-top:14px;color:var(--slate);font-size:13px}
</style>
</head>
<body>
<main>
<h1>You signed out of Grafana</h1>
<p>${host}</p>
<a class="button" href="/">Sign in with Docker Manager</a>
<a class="quiet" href="/login?local">Use a Grafana password instead</a>
</main>
</body>
</html>`
    );
}

export default { verify, ok, challenge, login, logout, grafanaToken, ssoCheck, ssoChallenge, ssoCallback, ssoSignedOut };
