'use strict';
(() => {
    const page = document.getElementById('page');
    const message = document.getElementById('message');
    let token = null, user = null, expiryTimer = null, controller = null, revision = 0, fieldSequence = 0;
    const urls = new Set();
    const admin = () => user?.role === 'ADMIN';
    const reporter = () => admin() || user?.role === 'REPORTER';
    const owns = n => admin() || reporter() && n.authorId === user?.id;
    const editable = n => admin() || owns(n) && n.status === 'DRAFT';
    const el = (tag, text, cls) => {
        const node = document.createElement(tag);
        if (text !== undefined) node.textContent = text;
        if (cls) node.className = cls;
        return node;
    };
    function notice(text, success = false) {
        message.textContent = text; message.hidden = !text; message.className = success ? 'success' : '';
    }
    function header() {
        document.getElementById('identity').textContent = user ? `${user.username} | ${user.role}` : 'Not logged in';
        document.getElementById('admin-link').hidden = !admin();
        document.getElementById('reporter-link').hidden = !reporter();
        document.getElementById('login-link').hidden = !!user;
        document.getElementById('register-link').hidden = !!user;
        document.getElementById('logout').hidden = !user;
    }
    function clearAuth() {
        token = null; user = null; clearTimeout(expiryTimer); header();
    }
    function go(path, replace = false) {
        history[replace ? 'replaceState' : 'pushState']({}, '', path);
        window.scrollTo(0, 0); render();
    }
    function link(text, path) {
        const a = el('a', text); a.href = path; a.dataset.route = ''; return a;
    }
    function button(text, action, primary = false) {
        const b = el('button', text, primary ? 'primary' : ''); b.type = 'button';
        b.addEventListener('click', () => run(b, action)); return b;
    }
    async function run(control, action) {
        control.disabled = true; notice('');
        try { await action(); } catch (error) { report(error); }
        finally { control.disabled = false; }
    }
    function report(error) {
        if (error.name === 'AbortError') return;
        notice(error instanceof TypeError ? 'Cannot connect to the server.' : error.message);
    }
    async function api(path, { method = 'GET', body, binary = false, type } = {}) {
        const headers = { Accept: binary ? 'image/png, image/jpeg' : 'application/json' };
        if (token) headers.Authorization = `Bearer ${token}`;
        if (body !== undefined) headers['Content-Type'] = type || 'application/json; charset=utf-8';
        const response = await fetch(path, {
            method, headers, credentials: 'omit', cache: 'no-store',
            signal: method === 'GET' ? controller?.signal : undefined,
            body: body === undefined ? undefined : type ? body : JSON.stringify(body)
        });
        if (!response.ok) {
            const error = await response.json().catch(() => ({}));
            if (response.status === 401 && token) {
                clearAuth(); render();
                throw new Error('Your token expired or was revoked. Log in again.');
            }
            throw new Error(error.message || `Request failed (${response.status}).`);
        }
        if (response.status === 204) return null;
        return binary ? response.blob() : response.json();
    }
    function field(form, name, title, { value = '', type = 'text', max, min, required = true, rows } = {}) {
        const label = el('label', title);
        const input = el(rows ? 'textarea' : 'input'); input.name = name; input.value = value; input.id = `field-${name}-${++fieldSequence}`;
        if (!rows) input.type = type;
        input.required = required;
        if (max) input.maxLength = max;
        if (min) input.minLength = min;
        if (rows) input.rows = rows;
        label.append(input); form.append(label); return input;
    }
    function select(form, name, title, values, value = '') {
        const label = el('label', title), input = el('select'); input.name = name;
        for (const [key, text] of values) { const option = el('option', text); option.value = key; input.append(option); }
        input.value = value; label.append(input); form.append(label); return input;
    }
    function submit(form, title, action) {
        const b = el('button', title, 'primary'); b.type = 'submit'; form.append(b);
        form.addEventListener('submit', event => { event.preventDefault(); run(b, action); }); return b;
    }
    function pager(parent, number, total, size, change) {
        const bar = el('div', undefined, 'pager');
        const prev = button('Previous', () => change(number - 1)); prev.disabled = number === 0;
        const next = button('Next', () => change(number + 1)); next.disabled = (number + 1) * size >= total;
        bar.append(prev, el('span', `Page ${number + 1} | ${total} items`), next); parent.append(bar);
    }
    function when(value) { return value ? new Date(value).toLocaleString() : 'Not published'; }
    function auth(signup, returnTo) {
        const card = el('div', undefined, 'card auth'); card.append(el('h1', signup ? 'Sign up' : 'Log in'));
        card.append(el('p', 'A Bearer token is required to read news and use the panels.'));
        const form = el('form');
        const username = field(form, 'username', 'Username', { min: 3, max: 40 });
        username.pattern = '[a-zA-Z0-9_.-]{3,40}'; username.autocomplete = 'username';
        const email = signup ? field(form, 'email', 'Email', { type: 'email', max: 254 }) : null;
        if (email) email.autocomplete = 'email';
        const password = field(form, 'password', 'Password', { type: 'password', min: signup ? 12 : 1, max: 72 });
        password.autocomplete = signup ? 'new-password' : 'current-password';
        if (signup) form.append(el('small', 'At least 12 characters, at most 72 UTF-8 bytes. New accounts have the USER role.'));
        submit(form, signup ? 'Create account' : 'Log in', async () => {
            const body = { username: username.value.trim(), password: password.value };
            if (signup) {
                body.email = email.value.trim(); await api('/api/auth/register', { method: 'POST', body });
                password.value = ''; go('/login'); notice('Account created. Log in now.', true); return;
            }
            const result = await api('/api/auth/login', { method: 'POST', body });
            token = result.accessToken; user = result.user; password.value = ''; header();
            clearTimeout(expiryTimer);
            expiryTimer = setTimeout(() => { clearAuth(); render(); notice('Your token expired. Log in again.'); }, result.expiresIn * 1000);
            go(returnTo || '/');
        });
        card.append(form, link(signup ? 'Already registered? Log in' : 'Create an account', signup ? '/login' : '/register'));
        page.append(card);
    }
    async function image(parent, id, alt, stamp) {
        if (!id) return;
        try {
            const blob = await api(`/api/images/${id}/content`, { binary: true });
            if (stamp !== revision) return;
            const url = URL.createObjectURL(blob); urls.add(url);
            const img = el('img'); img.alt = alt || 'Article image'; img.src = url; parent.append(img);
        } catch (error) { if (error.name !== 'AbortError' && stamp === revision) parent.append(el('small', 'Image unavailable.')); }
    }
    function safeHtml(html) {
        // Only backend-sanitized article HTML enters this renderer. Reapply a client allowlist as well.
        const doc = new DOMParser().parseFromString(html, 'text/html');
        const tags = new Set('a b blockquote br caption cite code col colgroup dd div dl dt em h1 h2 h3 h4 h5 h6 i li ol p pre q small span strike strong sub sup table tbody td tfoot th thead tr u ul'.split(' '));
        for (const node of [...doc.body.querySelectorAll('*')]) {
            if (!tags.has(node.localName)) { node.remove(); continue; }
            for (const attr of [...node.attributes]) {
                if (node.localName === 'a' && attr.name === 'href') {
                    try { if (!['https:', 'http:', 'mailto:'].includes(new URL(attr.value, location.origin).protocol)) node.removeAttribute(attr.name); }
                    catch { node.removeAttribute(attr.name); }
                } else node.removeAttribute(attr.name);
            }
            if (node.localName === 'a') node.setAttribute('rel', 'nofollow noopener noreferrer');
        }
        const body = el('div', undefined, 'article-body'); body.dir = 'auto';
        body.append(...[...doc.body.childNodes].map(node => document.importNode(node, true))); return body;
    }
    async function newsList(panel, stamp) {
        const params = new URLSearchParams(location.search);
        const number = Math.max(0, Number.parseInt(params.get('page') || '0', 10) || 0);
        const title = panel ? (admin() ? 'Editorial panel' : 'Reporter panel') : 'Latest news';
        page.append(el('h1', title));
        if (panel) {
            page.append(el('p', 'Create drafts, attach images, use local AI and moderate comments. Publication is controlled by administrators.'));
            page.append(button('Create a draft', () => editor(null, stamp), true));
        }
        const filters = el('form', undefined, 'filters');
        const q = field(filters, 'q', 'Search', { value: params.get('q') || '', max: 100, required: false });
        const category = field(filters, 'category', 'Category', { value: params.get('category') || '', max: 80, required: false });
        const status = panel ? select(filters, 'status', 'Status', [['', 'All statuses'], ['DRAFT', 'Draft'], ['PUBLISHED', 'Published'], ['ARCHIVED', 'Archived']], params.get('status') || '') : null;
        submit(filters, 'Apply', () => {
            const next = new URLSearchParams();
            if (q.value.trim()) next.set('q', q.value.trim());
            if (category.value.trim()) next.set('category', category.value.trim());
            if (status?.value) next.set('status', status.value);
            go(location.pathname + '?' + next);
        }); page.append(filters);
        const request = new URLSearchParams({ page: number, size: 12 });
        if (q.value) request.set('q', q.value);
        if (category.value) request.set('category', category.value);
        if (panel) { if (!admin()) request.set('mine', 'true'); if (status?.value) request.set('status', status.value); }
        else request.set('status', 'PUBLISHED');
        const result = await api('/api/news?' + request); if (stamp !== revision) return;
        const grid = el('div', undefined, 'grid');
        for (const n of result.items) {
            const card = el('article', undefined, 'card'), h = el('h2');
            h.append(link(n.title, `/news/${n.id}`)); card.append(h, el('p', n.summary), el('p', `${n.category} | ${n.status} | ${when(n.publishedAt)}`, 'meta'));
            if (panel) {
                const actions = el('div', undefined, 'actions');
                if (editable(n)) actions.append(button('Edit / images / AI', () => editor(n.id, stamp)));
                actions.append(link('Read / comments', `/news/${n.id}`));
                if (admin()) for (const target of ['DRAFT', 'PUBLISHED', 'ARCHIVED']) {
                    if (target !== n.status) actions.append(button(target, async () => {
                        await api(`/api/news/${n.id}/status`, { method: 'PATCH', body: { version: n.version, status: target } });
                        await render(); notice('Publication status updated.', true);
                    }));
                }
                card.append(actions);
            } else if (n.coverImageId) { const media = el('figure'); card.append(media); image(media, n.coverImageId, n.title, stamp); }
            grid.append(card);
        }
        if (!result.items.length) grid.append(el('p', 'No articles found.'));
        page.append(grid);
        pager(page, number, result.total, result.size, next => { params.set('page', next); go(location.pathname + '?' + params); });
    }
    async function editor(id, stamp) {
        let n = id ? await api(`/api/news/${id}`) : null; if (stamp !== revision) return;
        if (n && !editable(n)) throw new Error('Return this article to draft before editing.');
        page.replaceChildren(el('h1', id ? 'Edit article' : 'Create draft'));
        const form = el('form', undefined, 'card');
        const title = field(form, 'title', 'Title', { value: n?.title || '', max: 200 }); title.dir = 'auto';
        const summary = field(form, 'summary', 'Summary', { value: n?.summary || '', max: 1000, rows: 3 }); summary.dir = 'auto';
        const category = field(form, 'category', 'Category', { value: n?.category || 'General', max: 80 }); category.dir = 'auto';
        const html = field(form, 'bodyHtml', 'Article HTML', { value: n?.bodyHtml || '<p></p>', max: 20000, rows: 12 }); html.className = 'code'; html.dir = 'auto';
        const cover = select(form, 'coverImageId', 'Cover image', [['', 'No cover']], '');
        form.append(el('small', 'HTML is sanitized by the server. Saving a draft does not publish it.'));
        async function saveCurrent() {
            const body = { title: title.value, summary: summary.value, category: category.value, bodyHtml: html.value, coverImageId: cover.value || null };
            if (n) body.version = n.version;
            n = await api(n ? `/api/news/${n.id}` : '/api/news', { method: n ? 'PUT' : 'POST', body });
            return n;
        }
        submit(form, 'Save draft / changes', async () => {
            const saved = await saveCurrent();
            if (stamp !== revision) return;
            await editor(saved.id, stamp); notice('Article saved. You can attach images below.', true);
        }); page.append(form);
        const back = button('Back to articles', () => go(reporter() ? '/reporter' : '/'));  page.append(back);
        if (n) {
            const media = el('section', undefined, 'card'); media.append(el('h2', 'Attached images'));
            const result = await api(`/api/news/${id}/images?size=100`); if (stamp !== revision) return;
            for (const i of result.items) {
                const option = el('option', `${i.altText || i.id} (${i.contentType})`); option.value = i.id; cover.append(option);
                const figure = el('figure'); figure.append(el('figcaption', `${i.altText || 'Image'} | ${i.width} x ${i.height} | ${i.byteSize} bytes`));
                image(figure, i.id, i.altText, stamp);
                figure.append(button('Delete image', async () => { await saveCurrent(); await api(`/api/images/${i.id}`, { method: 'DELETE' }); if (stamp === revision) await editor(id, stamp); }));
                media.append(figure);
            }
            cover.value = n.coverImageId || '';
            const upload = el('form'); const file = field(upload, 'file', 'PNG or JPEG, maximum 5 MiB', { type: 'file' }); file.accept = 'image/png,image/jpeg';
            const alt = field(upload, 'alt', 'Alt text', { required: false, max: 300 });
            submit(upload, 'Upload image', async () => {
                const selected = file.files[0]; if (!selected) throw new Error('Select an image.');
                if (selected.size > 5 * 1024 * 1024) throw new Error('Maximum image size is 5 MiB.');
                const type = selected.type || (/\.png$/i.test(selected.name) ? 'image/png' : 'image/jpeg');
                if (!form.reportValidity()) return;
                await saveCurrent();
                await api(`/api/news/${id}/images?alt=${encodeURIComponent(alt.value)}`, { method: 'POST', body: selected, type });
                if (stamp !== revision) return;
                await editor(id, stamp); notice('Image uploaded. Select it as cover and save if needed.', true);
            }); media.append(upload); page.append(media);
        } else page.append(el('p', 'Save the draft before uploading images.'));
        const ai = el('section', undefined, 'card'); ai.append(el('h2', 'Local AI assistant'));
        const aiForm = el('form'); const prompt = field(aiForm, 'prompt', 'Instructions or source text (final output will be Persian)', { max: 12000, rows: 4 }); prompt.dir = 'auto';
        const output = el('pre', undefined, 'output'); output.dir = 'auto';
        const use = el('div', undefined, 'actions');
        submit(aiForm, 'Generate Persian text', async () => {
            const result = await api('/api/admin/ai/generate', { method: 'POST', body: { prompt: prompt.value } }); output.textContent = result.text;
            use.replaceChildren(button('Use as summary', () => { summary.value = result.text.slice(0, 1000); }), button('Use as article text', () => { const p = el('p', result.text); html.value = p.outerHTML; }));
            if (result.truncated) notice('AI output reached its limit. Review the draft before saving.');
        }); ai.append(aiForm, output, use); page.append(ai);
    }
    async function detail(id, stamp) {
        const n = await api(`/api/news/${id}`); if (stamp !== revision) return;
        page.append(link('Back to news', '/'));
        const article = el('article'); const title = el('h1', n.title); title.dir = 'auto';
        const summary = el('p', n.summary); summary.dir = 'auto';
        article.append(title, el('p', `${n.category} | ${n.status} | ${when(n.publishedAt)}`, 'meta'), summary);
        if (n.coverImageId) { const cover = el('figure'); article.append(cover); image(cover, n.coverImageId, n.title, stamp); }
        article.append(safeHtml(n.bodyHtml)); page.append(article);
        const media = await api(`/api/news/${id}/images?size=100`); if (stamp !== revision) return;
        if (media.items.length > 1 || media.items.some(i => i.id !== n.coverImageId)) {
            const gallery = el('div', undefined, 'grid');
            for (const i of media.items.filter(i => i.id !== n.coverImageId)) {
                const figure = el('figure', undefined, 'card'); figure.append(el('figcaption', i.altText)); image(figure, i.id, i.altText, stamp); gallery.append(figure);
            } page.append(gallery);
        }
        if (editable(n)) page.append(button('Edit article', () => editor(id, stamp)));
        const comments = el('section'); page.append(comments); await commentList(n, comments, 0, stamp);
    }
    async function commentList(n, parent, number, stamp) {
        const result = await api(`/api/news/${n.id}/comments?page=${number}&size=20`); if (stamp !== revision) return;
        parent.replaceChildren(el('h2', 'Comments'));
        if (!result.items.length) parent.append(el('p', 'No comments yet.'));
        let replyTo = null;
        for (const c of result.items) {
            const card = el('div', undefined, 'comment' + (c.parentId ? ' reply' : ''));
            const body = el('p', c.body); body.dir = 'auto';
            card.append(body, el('p', `${c.authorId === user.id ? 'You' : c.authorId} | ${c.status} | ${when(c.createdAt)}`, 'meta'));
            if (c.parentId) card.append(el('small', 'Reply to comment ' + c.parentId));
            const actions = el('div', undefined, 'actions');
            if (n.status === 'PUBLISHED' && c.status === 'APPROVED' && !c.parentId) actions.append(button('Reply', () => { replyTo = c.id; reply.textContent = 'Reply to ' + c.id; text.focus(); }));
            if (c.authorId === user.id && n.status === 'PUBLISHED') {
                actions.append(button('Edit', () => {
                    const editForm = el('form'); const editText = field(editForm, 'body', 'Updated comment', { value: c.body, max: 2000, rows: 3 }); editText.dir = 'auto';
                    submit(editForm, 'Save for approval', async () => {
                        await api(`/api/comments/${c.id}`, { method: 'PUT', body: { version: c.version, body: editText.value } });
                        await commentList(n, parent, number, stamp); notice('Comment updated and awaiting approval.', true);
                    }); card.append(editForm);
                }));
            }
            if (owns(n)) for (const status of ['APPROVED', 'REJECTED']) {
                if (status !== c.status) actions.append(button(status === 'APPROVED' ? 'Approve' : 'Reject', async () => {
                    await api(`/api/comments/${c.id}/moderation`, { method: 'PATCH', body: { version: c.version, status } }); await commentList(n, parent, number, stamp);
                }));
            }
            if (owns(n) || c.authorId === user.id) actions.append(button('Delete', async () => {
                await api(`/api/comments/${c.id}?version=${c.version}`, { method: 'DELETE' }); await commentList(n, parent, number, stamp);
            })); card.append(actions); parent.append(card);
        }
        pager(parent, number, result.total, result.size, next => commentList(n, parent, next, stamp));
        if (n.status !== 'PUBLISHED') { parent.append(el('p', 'Comments can only be submitted on published news.')); return; }
        const form = el('form', undefined, 'card'), reply = el('p', 'New comment'); form.append(reply);
        const text = field(form, 'body', 'Your comment', { max: 2000, rows: 4 }); text.dir = 'auto';
        form.append(button('Cancel reply', () => { replyTo = null; reply.textContent = 'New comment'; }));
        submit(form, 'Submit comment', async () => {
            await api(`/api/news/${n.id}/comments`, { method: 'POST', body: { body: text.value, parentId: replyTo } });
            await commentList(n, parent, number, stamp); notice('Comment submitted. It will appear to other readers after approval.', true);
        }); parent.append(form);
    }
    async function adminPanel(stamp) {
        page.append(el('h1', 'Admin panel'), el('p', 'Manage accounts, roles and enabled status. The last enabled administrator is protected.'));
        page.append(link('Manage articles and moderation', '/reporter'));
        const box = el('section', undefined, 'card'); page.append(box);
        async function users(number) {
            const result = await api(`/api/admin/users?page=${number}&size=20`); if (stamp !== revision) return;
            box.replaceChildren(el('h2', 'Users')); const wrap = el('div', undefined, 'table-wrap'), table = el('table');
            const head = el('thead'), row = el('tr'); for (const text of ['Username', 'Email', 'Role', 'Enabled', 'Action']) row.append(el('th', text)); head.append(row); table.append(head);
            const body = el('tbody');
            for (const account of result.users) {
                const tr = el('tr'); tr.append(el('td', account.username), el('td', account.email));
                const roleCell = el('td'), role = el('select'); role.setAttribute('aria-label', 'Role for ' + account.username);
                for (const name of ['USER', 'REPORTER', 'ADMIN']) { const option = el('option', name); option.value = name; role.append(option); } role.value = account.role; roleCell.append(role);
                const enabledCell = el('td'), enabled = el('input'); enabled.type = 'checkbox'; enabled.checked = account.enabled; enabled.setAttribute('aria-label', 'Enabled for ' + account.username); enabledCell.append(enabled);
                const action = el('td'); action.append(button('Save access', async () => {
                    await api(`/api/admin/users/${account.id}/access`, { method: 'PATCH', body: { role: role.value, enabled: enabled.checked } });
                    if (account.id === user.id && (role.value !== user.role || enabled.checked !== user.enabled)) { clearAuth(); go('/login'); notice('Your access changed. Log in again.'); return; }
                    await users(number); notice('User access updated. Previous tokens for that account are revoked.', true);
                })); tr.append(roleCell, enabledCell, action); body.append(tr);
            } table.append(body); wrap.append(table); box.append(wrap); pager(box, number, result.total, result.size, users);
        }
        await users(0);
    }
    async function render() {
        controller?.abort(); controller = new AbortController(); const stamp = ++revision;
        for (const url of urls) URL.revokeObjectURL(url); urls.clear();
        page.replaceChildren(); notice(''); header();
        const path = location.pathname;
        try {
            if (!user) { auth(path === '/register', path === '/login' || path === '/register' ? '/' : path + location.search); return; }
            if (path === '/login' || path === '/register') { go('/', true); return; }
            if (path === '/admin') {
                if (!admin()) { page.append(el('h1', 'Access denied'), el('p', 'Administrator access is required.')); return; }
                await adminPanel(stamp);
            } else if (path === '/reporter') {
                if (!reporter()) { page.append(el('h1', 'Access denied'), el('p', 'Reporter or administrator access is required.')); return; }
                await newsList(true, stamp);
            } else if (/^\/news\/[0-9a-f-]{36}$/i.test(path)) await detail(path.split('/')[2], stamp);
            else await newsList(false, stamp);
        } catch (error) { if (stamp === revision) report(error); }
    }
    document.addEventListener('click', event => {
        const a = event.target.closest('a[data-route]');
        if (!a || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault(); go(a.getAttribute('href'));
    });
    window.addEventListener('popstate', render);
    document.getElementById('logout').addEventListener('click', event => run(event.target, async () => {
        await api('/api/auth/logout', { method: 'POST' }); clearAuth(); go('/login'); notice('All of your access tokens have been revoked.', true);
    }));
    render();
})();
