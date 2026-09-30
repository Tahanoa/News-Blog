'use strict';
(() => {
    const page = document.getElementById('page');
    const message = document.getElementById('message');
    let token = null, user = null, expiryTimer = null, controller = null, revision = 0, fieldSequence = 0;
    const urls = new Set();
    const storageKey = 'news-blog-access';
    let dirty = false, saving = false;
    document.getElementById('today').textContent = new Date().toLocaleDateString('en', { month: 'long', day: 'numeric', year: 'numeric' });
    document.getElementById('year').textContent = new Date().getFullYear();
    function storeAuth(result) {
        const expiresAt = Date.now() + result.expiresIn * 1000;
        try { sessionStorage.setItem(storageKey, JSON.stringify({ token: result.accessToken, expiresAt })); }
        catch { /* Restricted storage falls back to the active tab's memory. */ }
        expireAt(expiresAt);
    }
    function expireAt(expiresAt) {
        clearTimeout(expiryTimer);
        expiryTimer = setTimeout(() => {
            clearAuth(); if (!dirty && !saving) render(); notice('Your access token expired. Save a copy of unsaved text, then log in to continue.');
        }, Math.max(0, expiresAt - Date.now()));
    }
    async function restoreAuth() {
        try {
            const stored = JSON.parse(sessionStorage.getItem(storageKey) || 'null');
            if (stored && typeof stored.token === 'string' && stored.token.length < 10000
                    && Number.isFinite(stored.expiresAt) && stored.expiresAt > Date.now()) {
                token = stored.token;
                user = await api('/api/users/me', { restore: true });
                expireAt(stored.expiresAt); header(); return;
            }
        } catch { /* Missing, expired, revoked or invalid tokens are removed. */ }
        clearAuth();
    }
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
        document.getElementById('identity').textContent = user ? `${user.username} · ${user.role.toLowerCase()}` : '';
        document.getElementById('admin-link').hidden = !admin();
        document.getElementById('reporter-link').hidden = !reporter();
        document.getElementById('login-link').hidden = !!user;
        document.getElementById('register-link').hidden = !!user;
        document.getElementById('logout').hidden = !user;
        for (const a of document.querySelectorAll('.main-nav a')) a.classList.toggle('active', a.getAttribute('href') === location.pathname);
    }
    function clearAuth() {
        token = null; user = null; clearTimeout(expiryTimer);
        try { sessionStorage.removeItem(storageKey); } catch { /* Storage may be disabled. */ }
        header();
    }
    function go(path, replace = false) {
        if (saving) { notice('Please wait until the article finishes saving.'); return; }
        if (dirty && !window.confirm('Leave the editor and discard unsaved changes?')) return;
        dirty = false;
        history[replace ? 'replaceState' : 'pushState']({}, '', path);
        lastPath = location.pathname + location.search; window.scrollTo(0, 0); render();
    }
    function link(text, path) {
        const a = el('a', text); a.href = path; a.dataset.route = ''; return a;
    }
    function button(text, action, primary = false) {
        const b = el('button', text, primary ? 'primary' : ''); b.type = 'button';
        b.addEventListener('click', () => run(b, action)); return b;
    }
    async function run(control, action) {
        if (saving) { notice('Please wait until saving finishes.'); return; }
        control.disabled = true; notice('');
        try { await action(); } catch (error) { report(error); }
        finally { control.disabled = false; }
    }
    function report(error) {
        if (error.name === 'AbortError') return;
        notice(error instanceof TypeError ? 'Cannot connect to the server.' : error.message);
    }
    async function api(path, { method = 'GET', body, binary = false, type, restore = false } = {}) {
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
                clearAuth();
                if (!restore && method === 'GET' && /^\/api\/(news|images)(?:\/|\?|$)/.test(path)) {
                    render(); return api(path, { method, body, binary, type });
                }
                if (!restore && !dirty && !saving) render();
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
        input.dir = 'auto'; label.append(input); form.append(label); return input;
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
        const layout = el('div', undefined, 'auth-layout'), story = el('section', undefined, 'auth-story');
        const intro = el('div'); intro.append(el('p', 'A SPACE FOR YOUR PERSPECTIVE', 'eyebrow'), el('h2', 'Good stories bring us together.'), el('p', 'Read something new. Share your perspective. Be part of the conversation.'));
        story.append(intro, el('small', 'Independent voices. A curious community.'));
        const card = el('div', undefined, 'auth'); card.append(el('p', signup ? 'JOIN THE CONVERSATION' : 'YOUR NEXT CHAPTER', 'eyebrow'), el('h1', signup ? 'Make yourself at home.' : 'Welcome back.'));
        layout.append(story, card);
        card.append(el('p', signup ? 'A few details, and you are ready to join in.' : 'Log in to your account. Your stories are waiting.'));
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
            storeAuth(result);
            go(returnTo || '/');
        });
        card.append(form, link(signup ? 'Already registered? Log in' : 'Create an account', signup ? '/login' : '/register'));
        page.append(layout);
    }
    function image(parent, id, alt, stamp) {
        if (!id || stamp !== revision) return;
        const img = el('img'); img.alt = alt || 'Article image';
        img.src = `/api/images/${id}/content`; img.loading = 'lazy'; img.decoding = 'async';
        img.addEventListener('error', () => { img.replaceWith(el('small', 'Image unavailable.')); }, { once: true });
        parent.append(img);
    }
    function heading(title, description, eyebrow) {
        const section = el('div', undefined, 'page-heading'), copy = el('div');
        copy.append(el('p', eyebrow || 'THE NEWSROOM', 'eyebrow'), el('h1', title));
        if (description) copy.append(el('p', description));
        section.append(copy); return section;
    }
    function workspace(kind) {
        const layout = el('div', undefined, 'workspace'), aside = el('aside', undefined, 'workspace-side');
        aside.setAttribute('aria-label', 'Workspace navigation');
        aside.append(el('p', 'YOUR WORKSPACE', 'eyebrow'));
        const editorial = link('✎  Editorial desk', '/reporter'); editorial.className = kind === 'editorial' ? 'active' : '';
        aside.append(editorial);
        if (admin()) { const accounts = link('◎  People & access', '/admin'); accounts.className = kind === 'accounts' ? 'active' : ''; aside.append(accounts); }
        aside.append(link('↗  View the publication', '/'), el('p', 'A space for better stories. Make every word count.', 'side-note'));
        const content = el('div', undefined, 'workspace-content'); layout.append(aside, content); page.append(layout); return content;
    }
    function stat(label, value, note) {
        const card = el('div', undefined, 'stat'); card.append(el('span', label), el('strong', value), el('small', note)); return card;
    }
    function safeHtml(html) {
        // Only backend-sanitized article HTML enters this renderer. Reapply a client allowlist as well.
        const doc = new DOMParser().parseFromString(html, 'text/html');
        const tags = new Set('img a b blockquote br caption cite code col colgroup dd div dl dt em h1 h2 h3 h4 h5 h6 i li ol p pre q small span strike strong sub sup table tbody td tfoot th thead tr u ul'.split(' '));
        for (const node of [...doc.body.querySelectorAll('*')]) {
            if (!tags.has(node.localName)) { node.remove(); continue; }
            if (node.localName === 'img') {
                const id = node.getAttribute('data-image-id');
                if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id || '')) { node.remove(); continue; }
                const placeholder = el('span', undefined, 'inline-image');
                placeholder.dataset.imageId = id; placeholder.dataset.alt = node.getAttribute('alt') || '';
                node.replaceWith(placeholder); continue;
            }
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
        const target = panel ? workspace('editorial') : page;
        const params = new URLSearchParams(location.search);
        const number = Math.max(0, Number.parseInt(params.get('page') || '0', 10) || 0);
        const top = heading(panel ? 'Your editorial desk.' : 'The stories that matter.', panel ? 'From the first idea to the final headline. Create, refine and manage your stories here.' : 'A fresh perspective on the world around you. Read something that stays with you.', panel ? 'CREATE · CURATE · PUBLISH' : 'THE DAILY EDIT');
        if (panel) top.append(button('+  New story', () => editor(null, stamp), true));
        else { const edition = el('div', undefined, 'edition'); edition.append(el('strong', 'A fresh perspective'), el('span', 'Independent voices. Informed readers.')); top.append(edition); }
        target.append(top);
        if (!panel) {
            const topics = el('nav', undefined, 'topic-nav'); topics.setAttribute('aria-label', 'News categories');
            for (const text of ['All stories', 'World', 'Technology', 'Business', 'Culture', 'Science']) {
                const a = link(text, text === 'All stories' ? '/' : '/?category=' + encodeURIComponent(text));
                a.className = (params.get('category') || 'All stories') === text ? 'active' : ''; topics.append(a);
            } target.append(topics);
        }
        const filters = el('form', undefined, 'filters');
        const q = field(filters, 'q', 'Search stories', { value: params.get('q') || '', max: 100, required: false }); q.placeholder = 'A headline, a topic, an idea…';
        const category = field(filters, 'category', 'Category', { value: params.get('category') || '', max: 80, required: false }); category.placeholder = 'Any category';
        const status = panel ? select(filters, 'status', 'Publication status', [['', 'All statuses'], ['DRAFT', 'Draft'], ['PUBLISHED', 'Published'], ['ARCHIVED', 'Archived']], params.get('status') || '') : null;
        submit(filters, 'Search →', () => {
            const next = new URLSearchParams();
            if (q.value.trim()) next.set('q', q.value.trim());
            if (category.value.trim()) next.set('category', category.value.trim());
            if (status?.value) next.set('status', status.value);
            go(location.pathname + '?' + next);
        }); target.append(filters);
        const request = new URLSearchParams({ page: number, size: 12 });
        if (q.value) request.set('q', q.value);
        if (category.value) request.set('category', category.value);
        if (panel) { if (!admin()) request.set('mine', 'true'); if (status?.value) request.set('status', status.value); }
        else request.set('status', 'PUBLISHED');
        const loading = el('div', 'Gathering your stories…', 'loading'); target.append(loading);
        const result = await api('/api/news?' + request); if (stamp !== revision) return; loading.remove();
        if (panel) {
            const stats = el('div', undefined, 'stats');
            stats.append(stat('Matching stories', result.total, 'Across the current filters'), stat('Your access', admin() ? 'Admin' : 'Reporter', admin() ? 'Manage publishing and people' : 'Create drafts and moderate replies'), stat('Writing assistant', 'Local AI', 'Persian output · your final review'));
            filters.before(stats);
        }
        function story(n, featured = false) {
            const card = el('article', undefined, featured ? 'feature' : 'news-card');
            const media = link('', `/news/${n.id}`); media.className = 'card-media'; media.setAttribute('aria-label', n.title);
            if (n.coverImageId) image(media, n.coverImageId, n.title, stamp);
            else { const placeholder = el('div', undefined, 'media-placeholder'); placeholder.append(el('span', 'n.'), el('small', 'A FRESH PERSPECTIVE')); media.append(placeholder); }
            const copy = el('div', undefined, 'card-copy');
            const category = el('span', n.category, featured ? 'eyebrow' : 'badge'); category.dir = 'auto'; copy.append(category);
            const h = el('h2'); h.dir = 'auto'; h.append(link(n.title, `/news/${n.id}`)); const summary = el('p', n.summary, 'summary'); summary.dir = 'auto'; copy.append(h, summary);
            const bottom = el('div', undefined, 'card-bottom');
            const badge = el('span', panel ? n.status : 'Read the story ↗', panel ? 'badge ' + n.status.toLowerCase() : '');
            bottom.append(el('span', n.publishedAt ? new Date(n.publishedAt).toLocaleDateString() : 'In progress'), badge); copy.append(bottom);
            if (panel) {
                const actions = el('div', undefined, 'actions');
                if (editable(n)) actions.append(button('✎  Edit story', () => editor(n.id, stamp)));
                actions.append(link('Read & comments', `/news/${n.id}`));
                if (admin()) for (const status of ['DRAFT', 'PUBLISHED', 'ARCHIVED']) {
                    if (status !== n.status) actions.append(button(status === 'PUBLISHED' ? 'Publish ↗' : status === 'DRAFT' ? 'Return to draft' : 'Archive', async () => {
                        if (!window.confirm(`Change this story to ${status.toLowerCase()}?`)) return;
                        await api(`/api/news/${n.id}/status`, { method: 'PATCH', body: { version: n.version, status } });
                        await render(); notice('Publication status updated.', true);
                    }));
                } copy.append(actions);
            }
            card.append(media, copy); return card;
        }
        let items = result.items;
        if (!panel && number === 0 && !q.value && !category.value && items.length) { target.append(story(items[0], true)); items = items.slice(1); }
        const sectionTitle = el('div', undefined, 'section-heading'); sectionTitle.append(el('h2', panel ? 'Story library' : 'Latest perspectives'), el('span', `${result.total} stories`)); target.append(sectionTitle);
        const grid = el('div', undefined, 'grid');
        for (const n of items) grid.append(story(n));
        if (!result.items.length) { const empty = el('div', undefined, 'empty'); empty.append(el('h2', panel ? 'Your next story starts here.' : 'A new story is on its way.'), el('p', panel ? 'Create a draft, or try another search.' : 'Check back soon or explore another topic.')); grid.append(empty); }
        target.append(grid);
        if (result.total > result.size) pager(target, number, result.total, result.size, next => { params.set('page', next); go(location.pathname + '?' + params); });
    }
    async function editor(id, stamp) {
        if (saving) return;
        if (dirty && !window.confirm('Discard unsaved changes and open this story?')) return;
        let n = id ? await api(`/api/news/${id}`) : null;
        if (stamp !== revision) return;
        if (n && !editable(n)) throw new Error('Return this article to draft before editing.');
        dirty = false;
        page.replaceChildren();
        const top = el('div', undefined, 'editor-header'), copy = el('div');
        copy.append(el('p', 'THE EDITORIAL STUDIO', 'eyebrow'), el('h1', id ? 'Make your story shine.' : 'Every story starts here.'));
        const back = button('←  Back to newsroom', () => go('/reporter')); top.append(copy, back); page.append(top);
        const layout = el('div', undefined, 'editor-layout'), form = el('form', undefined, 'editor-form'), side = el('aside', undefined, 'editor-side');
        layout.append(form, side); page.append(layout);
        const intro = el('section', undefined, 'card');
        const title = field(intro, 'title', 'HEADLINE', { value: n?.title || '', max: 200 }); title.className = 'title-input'; title.placeholder = 'A headline worth reading…';
        const summary = field(intro, 'summary', 'THE SHORT VERSION', { value: n?.summary || '', max: 1000, rows: 3 }); summary.placeholder = 'Give readers a reason to keep reading.';
        const category = field(intro, 'category', 'CATEGORY', { value: n?.category || 'General', max: 80 }); form.append(intro);
        const writing = el('section', undefined, 'card'); writing.append(el('h2', 'Tell the full story'));
        const shell = el('div', undefined, 'editor-shell'), toolbar = el('div', undefined, 'editor-toolbar'); toolbar.setAttribute('role', 'toolbar'); toolbar.setAttribute('aria-label', 'Text formatting');
        const rich = el('div', undefined, 'rich-editor article-body'); rich.contentEditable = 'true'; rich.dir = 'auto'; rich.setAttribute('role', 'textbox'); rich.setAttribute('aria-multiline', 'true'); rich.setAttribute('aria-label', 'Full article'); rich.dataset.placeholder = 'Start writing, or drop an image right into your story…';
        const status = el('div', undefined, 'editor-status'), wordCount = el('span'), saveState = el('span', n ? 'All changes saved' : 'New draft · not saved yet');
        status.append(wordCount, el('span', 'Drop PNG or JPEG images anywhere in the story'), saveState);
        shell.append(toolbar, rich, status); writing.append(shell); form.append(writing);
        let bookmark = null;
        const pending = new Map(), library = new Map();
        const coverBox = el('section', undefined, 'card'); coverBox.append(el('h2', 'Story settings'));
        const cover = select(coverBox, 'coverImageId', 'Cover image', [['', 'No cover image']], '');
        const coverPreview = el('figure', undefined, 'cover-preview'); coverPreview.hidden = true; coverBox.append(coverPreview);
        coverBox.append(el('p', 'Choose a cover separately from the images in your story. Saving keeps the current publication status.'));
        side.append(coverBox);
        const media = el('section', undefined, 'card'); media.append(el('h2', 'Images & media'));
        const fileInput = el('input'); fileInput.type = 'file'; fileInput.accept = 'image/png,image/jpeg'; fileInput.multiple = true; fileInput.hidden = true; fileInput.setAttribute('aria-label', 'Select article images');
        const dropzone = button('↑  Add images', () => fileInput.click()); dropzone.className = 'dropzone'; dropzone.append(el('span', 'or drop them here · PNG/JPEG · up to 5 MiB'));
        const tiles = el('div', undefined, 'media-library'); media.append(dropzone, fileInput, tiles); side.append(media);
        function mark() { dirty = true; saveState.textContent = 'Unsaved changes'; count(); }
        function count() { wordCount.textContent = `${rich.innerText.trim().split(/\s+/).filter(Boolean).length} words`; }
        function remember() {
            const selection = window.getSelection();
            if (selection.rangeCount && rich.contains(selection.anchorNode) && rich.contains(selection.focusNode)) bookmark = selection.getRangeAt(0).cloneRange();
        }
        function restore() {
            rich.focus(); const selection = window.getSelection(); selection.removeAllRanges();
            if (bookmark && rich.contains(bookmark.commonAncestorContainer)) selection.addRange(bookmark);
            else { const range = document.createRange(); range.selectNodeContents(rich); range.collapse(false); selection.addRange(range); }
        }
        function insertNode(node) {
            restore(); const selection = window.getSelection(), range = selection.getRangeAt(0);
            if (node.contains(range.commonAncestorContainer)) { bookmark = null; return; }
            range.deleteContents(); range.insertNode(node); range.setStartAfter(node); range.collapse(true); selection.removeAllRanges(); selection.addRange(range); remember(); mark();
        }
        function positionAt(event) {
            let range = document.caretRangeFromPoint?.(event.clientX, event.clientY);
            if (!range && document.caretPositionFromPoint) { const caret = document.caretPositionFromPoint(event.clientX, event.clientY); if (caret) { range = document.createRange(); range.setStart(caret.offsetNode, caret.offset); range.collapse(true); } }
            if (range && rich.contains(range.startContainer)) bookmark = range;
        }
        function setBody(html) {
            const safe = safeHtml(html);
            for (const holder of safe.querySelectorAll('[data-image-id]')) {
                const img = el('img'); img.dataset.imageId = holder.dataset.imageId; img.alt = holder.dataset.alt || ''; img.src = `/api/images/${img.dataset.imageId}/content`; holder.replaceWith(img);
            }
            rich.replaceChildren(...safe.childNodes); bookmark = null; count();
        }
        setBody(n?.bodyHtml || '');
        function formatting(label, command, value) {
            const b = button(label, () => { restore(); document.execCommand(command, false, value); remember(); mark(); });
            b.setAttribute('aria-label', label); b.title = label; b.addEventListener('mousedown', e => e.preventDefault()); toolbar.append(b); return b;
        }
        formatting('Text', 'formatBlock', 'p'); formatting('H2', 'formatBlock', 'h2'); formatting('H3', 'formatBlock', 'h3');
        toolbar.append(el('span', undefined, 'separator'));
        formatting('Bold', 'bold'); formatting('Italic', 'italic'); formatting('Underline', 'underline');
        toolbar.append(el('span', undefined, 'separator'));
        formatting('• List', 'insertUnorderedList'); formatting('1. List', 'insertOrderedList'); formatting('Quote', 'formatBlock', 'blockquote');
        const addLink = button('Link ↗', () => {
            const value = window.prompt('Enter a link starting with https://, http:// or mailto:'); if (!value) return;
            const url = new URL(value); if (!['https:', 'http:', 'mailto:'].includes(url.protocol)) throw new Error('Use an HTTP, HTTPS or email link.');
            restore(); document.execCommand('createLink', false, url.href); remember(); mark();
        }); addLink.addEventListener('mousedown', e => e.preventDefault()); toolbar.append(addLink);
        formatting('Unlink', 'unlink'); formatting('Undo', 'undo'); formatting('Redo', 'redo');
        const addImage = button('Image ↑', () => fileInput.click()); addImage.addEventListener('mousedown', e => e.preventDefault()); toolbar.append(addImage);
        const preview = button('Preview', () => {
            const showing = rich.contentEditable === 'false'; rich.contentEditable = showing ? 'true' : 'false'; preview.textContent = showing ? 'Preview' : 'Keep editing';
            for (const b of toolbar.querySelectorAll('button')) if (b !== preview) b.disabled = !showing;
            notice(showing ? '' : 'Reading preview. Choose Keep editing to return to the editor.', true);
        }); toolbar.append(preview);
        for (const event of ['keyup', 'mouseup', 'focus']) rich.addEventListener(event, remember);
        rich.addEventListener('input', () => { remember(); mark(); });
        for (const input of [title, summary, category, cover]) input.addEventListener('input', mark);
        function insertMedia(item) {
            const img = el('img'); img.alt = item.altText || '';
            if (item.pendingId) { img.dataset.pendingId = item.pendingId; img.src = item.url; }
            else { img.dataset.imageId = item.id; img.src = `/api/images/${item.id}/content`; }
            insertNode(img);
        }
        function refreshMedia() {
            const chosen = cover.value; cover.replaceChildren(); const none = el('option', 'No cover image'); none.value = ''; cover.append(none); tiles.replaceChildren();
            for (const item of [...library.values(), ...pending.values()]) {
                const key = item.pendingId ? 'pending:' + item.pendingId : item.id;
                const option = el('option', item.altText || item.name || 'Article image'); option.value = key; cover.append(option);
                const tile = el('figure', undefined, 'media-tile' + (chosen === key ? ' is-cover' : ''));
                const img = el('img'); img.alt = item.altText || ''; img.src = item.url || `/api/images/${item.id}/content`; img.draggable = true; img.loading = 'lazy';
                img.addEventListener('dragstart', e => e.dataTransfer.setData('application/x-news-image', key));
                tile.append(img, el('figcaption', item.pendingId ? 'Ready to upload' : `${item.width} × ${item.height}`));
                if (item.pendingId) {
                    const alt = el('input'); alt.placeholder = 'Describe this image'; alt.value = item.altText; alt.maxLength = 300; alt.setAttribute('aria-label', 'Image description'); alt.dir = 'auto';
                    alt.addEventListener('input', () => { item.altText = alt.value; for (const node of rich.querySelectorAll('img')) if (node.dataset.pendingId === item.pendingId) node.alt = alt.value; mark(); }); tile.append(alt);
                }
                tile.append(button('Insert into story', () => insertMedia(item)), button(chosen === key ? '✓ Cover image' : 'Use as cover', () => { cover.value = key; mark(); refreshMedia(); }));
                tile.append(button(item.pendingId ? 'Remove' : 'Delete image', async () => {
                    if (!window.confirm('Remove this image? Existing article references to it will also be removed.')) return;
                    if (item.pendingId) {
                        for (const node of rich.querySelectorAll('img')) if (node.dataset.pendingId === item.pendingId) node.remove();
                        pending.delete(item.pendingId); URL.revokeObjectURL(item.url); urls.delete(item.url); if (cover.value === key) cover.value = ''; mark();
                    } else {
                        if (!form.reportValidity()) return;
                        await saveCurrent();
                        saving = true; form.inert = true; side.inert = true;
                        try {
                            await api(`/api/images/${item.id}`, { method: 'DELETE' });
                            n = await api(`/api/news/${n.id}`);
                            if (stamp !== revision) return;
                            for (const node of rich.querySelectorAll('img')) if (node.dataset.imageId === item.id) node.remove();
                            library.delete(item.id); if (cover.value === key) cover.value = ''; dirty = false; saveState.textContent = 'All changes saved';
                        } finally { saving = false; form.inert = false; side.inert = false; }
                    }
                    refreshMedia(); count();
                })); tiles.append(tile);
            }
            cover.value = [...cover.options].some(o => o.value === chosen) ? chosen : '';
            coverPreview.replaceChildren(); coverPreview.hidden = !cover.value;
            if (cover.value) { const current = cover.value.startsWith('pending:') ? pending.get(cover.value.slice(8)) : library.get(cover.value); if (current) { const img = el('img'); img.alt = current.altText || 'Cover preview'; img.src = current.url || `/api/images/${current.id}/content`; coverPreview.append(img); } }
        }
        cover.addEventListener('change', refreshMedia);
        function queueFiles(files, inline = true) {
            const selected = [...files];
            if (selected.length + pending.size + library.size > 20) throw new Error('A story can have at most 20 images.');
            for (const file of selected) {
                if (!['image/png', 'image/jpeg'].includes(file.type)) throw new Error('Only PNG and JPEG images are supported.');
                if (!file.size || file.size > 5 * 1024 * 1024) throw new Error('Each image must be smaller than 5 MiB.');
            }
            for (const file of selected) {
                const pendingId = crypto.randomUUID(), url = URL.createObjectURL(file); urls.add(url);
                const item = { pendingId, file, url, name: file.name, altText: file.name.replace(/\.[^.]+$/, '').slice(0, 300) };
                pending.set(pendingId, item); if (inline) insertMedia(item);
            }
            refreshMedia(); mark(); notice('Images added to your draft. Save the story to upload them.', true);
        }
        fileInput.addEventListener('change', () => { try { queueFiles(fileInput.files); } catch (error) { report(error); } fileInput.value = ''; });
        for (const zone of [rich, dropzone]) {
            zone.addEventListener('dragover', e => { e.preventDefault(); if (!saving) zone.classList.add('drag-over'); });
            zone.addEventListener('dragleave', e => { if (!zone.contains(e.relatedTarget)) zone.classList.remove('drag-over'); });
            zone.addEventListener('drop', e => {
                e.preventDefault(); zone.classList.remove('drag-over'); if (saving) return;
                try {
                    if (zone === rich) positionAt(e);
                    if (e.dataTransfer.files.length) queueFiles(e.dataTransfer.files, zone === rich);
                    else if (zone === rich) {
                        const key = e.dataTransfer.getData('application/x-news-image');
                        const item = key.startsWith('pending:') ? pending.get(key.slice(8)) : library.get(key);
                        if (item) {
                            const moved = rich.querySelector('img[data-dragging]'); if (moved) { moved.removeAttribute('data-dragging'); insertNode(moved); } else insertMedia(item);
                        }
                    }
                } catch (error) { report(error); }
            });
        }
        rich.addEventListener('dragstart', e => {
            if (e.target.localName !== 'img') return;
            e.target.dataset.dragging = 'true';
            e.dataTransfer.setData('application/x-news-image', e.target.dataset.pendingId ? 'pending:' + e.target.dataset.pendingId : e.target.dataset.imageId);
        });
        rich.addEventListener('dragend', () => { for (const node of rich.querySelectorAll('[data-dragging]')) node.removeAttribute('data-dragging'); });
        rich.addEventListener('paste', e => {
            e.preventDefault(); if (saving) return;
            try {
                const files = [...e.clipboardData.files];
                if (files.length) { remember(); queueFiles(files); }
                else { restore(); document.execCommand('insertText', false, e.clipboardData.getData('text/plain')); remember(); mark(); }
            } catch (error) { report(error); }
        });
        function bodyHtml() {
            const clone = rich.cloneNode(true);
            for (const img of clone.querySelectorAll('img')) {
                if (!img.dataset.imageId) { img.remove(); continue; }
                const id = img.dataset.imageId, alt = img.alt;
                for (const attr of [...img.attributes]) img.removeAttribute(attr.name);
                img.dataset.imageId = id; img.alt = alt.slice(0, 300);
            }
            for (const node of clone.querySelectorAll('*')) for (const attr of [...node.attributes]) {
                if (node.localName === 'img' && ['data-image-id', 'alt'].includes(attr.name)) continue;
                if (node.localName === 'a' && attr.name === 'href') continue;
                node.removeAttribute(attr.name);
            }
            return clone.innerHTML;
        }
        function payload() { return { title: title.value.trim(), summary: summary.value.trim(), category: category.value.trim(), bodyHtml: bodyHtml(), coverImageId: cover.value && !cover.value.startsWith('pending:') ? cover.value : null }; }
        async function saveCurrent() {
            if (saving) throw new Error('A save is already in progress.');
            if (!form.reportValidity()) throw new Error('Complete the headline, summary and category.');
            if (!rich.innerText.trim()) { rich.focus(); throw new Error('Write some article text before saving.'); }
            if (bodyHtml().length > 20000) throw new Error('The article is too long. Shorten it before saving.');
            saving = true; form.inert = true; side.inert = true; saveState.textContent = 'Saving your story…';
                        try {
                if (!n) n = await api('/api/news', { method: 'POST', body: { ...payload(), coverImageId: null } });
                for (const item of [...pending.values()]) {
                    const nodes = [...rich.querySelectorAll('img')].filter(node => node.dataset.pendingId === item.pendingId);
                    // Upload all queued media, including images reserved for a future cover.
                    const uploaded = await api(`/api/news/${n.id}/images?alt=${encodeURIComponent(item.altText)}`, { method: 'POST', body: item.file, type: item.file.type });
                    for (const node of nodes) { delete node.dataset.pendingId; node.dataset.imageId = uploaded.id; node.src = `/api/images/${uploaded.id}/content`; node.alt = item.altText; }
                    library.set(uploaded.id, uploaded); pending.delete(item.pendingId);
                    if (cover.value === 'pending:' + item.pendingId) { const option = el('option', item.altText || 'Cover'); option.value = uploaded.id; cover.append(option); cover.value = uploaded.id; }
                    URL.revokeObjectURL(item.url); urls.delete(item.url);
                }
                n = await api(`/api/news/${n.id}`, { method: 'PUT', body: { ...payload(), version: n.version } });
                setBody(n.bodyHtml); dirty = false; saveState.textContent = 'All changes saved'; refreshMedia(); notice('Story saved. Your images and formatting are ready.', true); return n;
            } catch (error) { dirty = true; saveState.textContent = 'Save incomplete · your changes are still here'; throw error; }
            finally { saving = false; form.inert = false; side.inert = false; }
        }
        const saveBar = el('div', undefined, 'save-bar'); saveBar.append(el('small', 'Your story stays private until an administrator publishes it.')); form.append(saveBar);
        submit(form, 'Save story →', saveCurrent);
        const ai = el('section', undefined, 'card ai-card'); ai.append(el('p', 'YOUR WRITING PARTNER', 'eyebrow'), el('h2', 'A little inspiration.'));
        const aiForm = el('form'); const prompt = field(aiForm, 'prompt', 'Source text or instructions', { max: 12000, rows: 5 }); prompt.placeholder = 'Describe the story you want to tell…';
        aiForm.append(el('small', 'Generated title, summary, category and article will be in Persian. Review before saving.'));
        const output = el('pre', undefined, 'output'); output.dir = 'auto'; const use = el('div', undefined, 'actions');
        submit(aiForm, '✦  Generate complete story', async () => {
            if (dirty && !window.confirm('Replace the current text fields with an AI draft? Images and cover stay in the media library.')) return;
            const result = await api('/api/admin/ai/news-draft', { method: 'POST', body: { prompt: prompt.value } }); if (stamp !== revision) return;
            title.value = result.title; summary.value = result.summary; category.value = result.category; setBody(result.bodyHtml); mark();
            output.textContent = 'Your Persian draft is ready. Make it your own, then save.'; use.replaceChildren(); notice('AI filled every text field. Review your new story before saving.', true);
        });
        aiForm.append(button('Generate text only', async () => {
            if (!prompt.reportValidity()) return;
            const result = await api('/api/admin/ai/generate', { method: 'POST', body: { prompt: prompt.value } }); if (stamp !== revision) return;
            output.textContent = result.text;
            use.replaceChildren(button('Use as summary', () => { summary.value = result.text.slice(0, 1000); mark(); }), button('Append to story', () => { const paragraph = el('p', result.text); rich.append(paragraph); mark(); }));
            if (result.truncated) notice('AI output reached its limit. Review it before saving.');
        })); ai.append(aiForm, output, use); side.append(ai);
        if (n) {
            const result = await api(`/api/news/${n.id}/images?size=100`); if (stamp !== revision) return;
            for (const item of result.items) library.set(item.id, item);
            refreshMedia(); cover.value = n.coverImageId || ''; refreshMedia();
        }
        count();
    }
    async function detail(id, stamp) {
        const loading = el('div', 'Opening the story…', 'loading'); page.append(loading);
        const n = await api(`/api/news/${id}`); if (stamp !== revision) return; loading.remove();
        const article = el('article', undefined, 'article-detail');
        const back = link('←  Back to all stories', '/'); back.className = 'eyebrow'; article.append(back);
        const category = el('p', n.category, 'eyebrow'); category.dir = 'auto';
        const title = el('h1', n.title); title.dir = 'auto';
        const summary = el('p', n.summary, 'article-deck'); summary.dir = 'auto';
        const text = new DOMParser().parseFromString(n.bodyHtml, 'text/html').body.textContent;
        const readTime = Math.max(1, Math.ceil(text.trim().split(/\s+/).length / 200));
        const meta = el('div', undefined, 'meta'); meta.append(el('span', `News Blog Editorial · ${when(n.publishedAt)} · ${readTime} min read`));
        if (n.status !== 'PUBLISHED') meta.append(el('span', n.status, 'badge ' + n.status.toLowerCase()));
        article.append(category, title, meta, summary);
        if (n.coverImageId) { const cover = el('figure', undefined, 'article-cover'); article.append(cover); image(cover, n.coverImageId, n.title, stamp); }
        const body = safeHtml(n.bodyHtml); article.append(body); page.append(article);
        for (const placeholder of body.querySelectorAll('[data-image-id]')) image(placeholder, placeholder.dataset.imageId, placeholder.dataset.alt, stamp);
        const media = await api(`/api/news/${id}/images?size=100`); if (stamp !== revision) return;
        const displayedImages = new Set([...body.querySelectorAll('[data-image-id]')].map(node => node.dataset.imageId));
        if (n.coverImageId) displayedImages.add(n.coverImageId);
        const additionalImages = media.items.filter(i => !displayedImages.has(i.id));
        if (additionalImages.length) {
            const gallery = el('div', undefined, 'grid');
            for (const i of additionalImages) {
                const figure = el('figure', undefined, 'card'); image(figure, i.id, i.altText, stamp); const caption = el('figcaption', i.altText); caption.dir = 'auto'; figure.append(caption); gallery.append(figure);
            } article.append(gallery);
        }
        if (editable(n)) article.append(button('✎  Open in editorial studio', () => editor(id, stamp)));
        const comments = el('section', undefined, 'comments-area'); page.append(comments); await commentList(n, comments, 0, stamp);
    }
    async function commentList(n, parent, number, stamp) {
        const result = await api(`/api/news/${n.id}/comments?page=${number}&size=20`); if (stamp !== revision) return;
        parent.replaceChildren(el('p', 'JOIN THE CONVERSATION', 'eyebrow'), el('h2', 'Your perspective matters.'));
        if (!result.items.length) parent.append(el('p', 'Be the first to share a thoughtful perspective.'));
        let replyTo = null;
        for (const c of result.items) {
            const card = el('div', undefined, 'comment' + (c.parentId ? ' reply' : ''));
            const body = el('p', c.body); body.dir = 'auto';
            card.append(body, el('p', `${c.authorId === user?.id ? 'You' : 'Reader ' + c.authorId.slice(0, 6)} | ${c.status} | ${when(c.createdAt)}`, 'meta'));
            if (c.parentId) card.append(el('small', 'In reply to another reader'));
            const actions = el('div', undefined, 'actions');
            if (user && n.status === 'PUBLISHED' && c.status === 'APPROVED' && !c.parentId) actions.append(button('Reply', () => { replyTo = c.id; reply.textContent = 'Reply to this reader'; text.focus(); }));
            if (c.authorId === user?.id && n.status === 'PUBLISHED') {
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
            if (owns(n) || c.authorId === user?.id) actions.append(button('Delete', async () => {
                await api(`/api/comments/${c.id}?version=${c.version}`, { method: 'DELETE' }); await commentList(n, parent, number, stamp);
            })); card.append(actions); parent.append(card);
        }
        pager(parent, number, result.total, result.size, next => commentList(n, parent, next, stamp));
        if (n.status !== 'PUBLISHED') { parent.append(el('p', 'Comments can only be submitted on published news.')); return; }
        if (!user) {
            parent.append(link('Log in to leave a comment', '/login?returnTo=' + encodeURIComponent('/news/' + n.id)));
            return;
        }
        const form = el('form', undefined, 'card'), reply = el('p', 'Share your perspective'); form.append(reply);
        const text = field(form, 'body', 'Your comment', { max: 2000, rows: 4 }); text.dir = 'auto';
        form.append(button('Cancel reply', () => { replyTo = null; reply.textContent = 'Share your perspective'; }));
        submit(form, 'Submit comment', async () => {
            await api(`/api/news/${n.id}/comments`, { method: 'POST', body: { body: text.value, parentId: replyTo } });
            await commentList(n, parent, number, stamp); notice('Comment submitted. It will appear to other readers after approval.', true);
        }); parent.append(form);
    }
    async function adminPanel(stamp) {
        const target = workspace('accounts');
        target.append(heading('The people behind the stories.', 'Manage your community, assign editorial roles and keep access in the right hands.', 'PEOPLE & PERMISSIONS'));
        const box = el('section', undefined, 'card'); target.append(box);
        async function users(number) {
            const result = await api(`/api/admin/users?page=${number}&size=20`); if (stamp !== revision) return;
            box.replaceChildren();
            const stats = el('div', undefined, 'stats'); stats.append(stat('Community members', result.total, 'Registered accounts'), stat('Available roles', '3', 'Reader · reporter · administrator'), stat('Access updates', 'Immediate', 'Changed accounts must log in again')); box.append(stats);
            const intro = el('div', undefined, 'section-heading'); intro.append(el('h2', 'Community directory'), el('span', 'The last active administrator is protected.')); box.append(intro); const wrap = el('div', undefined, 'table-wrap'), table = el('table');
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
        document.title = location.pathname === '/admin' ? 'People & access — News Blog' : location.pathname === '/reporter' ? 'Editorial desk — News Blog' : 'News Blog — A fresh perspective';
        const path = location.pathname;
        try {
            if (path === '/login' || path === '/register') {
                if (user) { go('/', true); return; }
                const requested = new URLSearchParams(location.search).get('returnTo');
                const returnTo = requested && /^\/(?!\/)/.test(requested) ? requested : '/';
                auth(path === '/register', returnTo); return;
            }
            if (!user && (path === '/admin' || path === '/reporter')) { auth(false, path + location.search); return; }
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
    let lastPath = location.pathname + location.search;
    window.addEventListener('popstate', () => {
        if (saving || dirty && !window.confirm('Leave the editor and discard unsaved changes?')) { history.pushState({}, '', lastPath); return; }
        dirty = false; lastPath = location.pathname + location.search; render();
    });
    window.addEventListener('beforeunload', event => { if (dirty || saving) { event.preventDefault(); event.returnValue = ''; } });
    document.getElementById('logout').addEventListener('click', event => run(event.target, async () => {
        if (dirty && !window.confirm('Log out and discard unsaved changes?')) return;
        dirty = false; await api('/api/auth/logout', { method: 'POST' }); clearAuth(); go('/login'); notice('All of your access tokens have been revoked.', true);
    }));
    restoreAuth().then(render);
})();
