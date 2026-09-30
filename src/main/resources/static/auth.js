'use strict';
(() => {
    const byId = id => document.getElementById(id);
    let registering = location.pathname === '/register';
    let token = null; // In memory only: never stored in localStorage, URLs or cookies.
    let expiryTimer = null;
    const roles = { USER: 'کاربر', REPORTER: 'خبرنگار', ADMIN: 'مدیر' };
    const errors = {
        INVALID_CREDENTIALS: 'نام کاربری یا رمز عبور معتبر نیست یا حساب غیرفعال است.',
        USER_EXISTS: 'نام کاربری یا ایمیل قبلاً ثبت شده است.',
        INVALID_INPUT: 'اطلاعات را بررسی کنید. نام کاربری انگلیسی، ایمیل معتبر و رمز حداقل ۱۲ نویسه لازم است.',
        INVALID_PASSWORD: 'رمز باید حداقل ۱۲ نویسه و حداکثر ۷۲ بایت UTF-8 داشته باشد.',
        RATE_LIMITED: 'تعداد تلاش‌ها زیاد است؛ بعداً دوباره امتحان کنید.',
        UNAUTHORIZED: 'نشست شما معتبر نیست. دوباره وارد شوید.'
    };
    function mode(value) {
        registering = value;
        byId('title').textContent = value ? 'ساخت حساب جدید' : 'ورود به حساب';
        byId('intro').textContent = value ? 'حساب جدید با نقش کاربر ساخته می‌شود.' : 'برای دسترسی به حساب خود وارد شوید.';
        byId('email-field').hidden = !value;
        byId('email').required = value;
        byId('password-help').hidden = !value;
        byId('password').minLength = value ? 12 : 1;
        byId('password').autocomplete = value ? 'new-password' : 'current-password';
        byId('submit').textContent = value ? 'ثبت‌نام' : 'ورود';
        byId('login-tab').setAttribute('aria-pressed', String(!value));
        byId('register-tab').setAttribute('aria-pressed', String(value));
        byId('message').textContent = '';
    }
    function clearSession() {
        token = null;
        clearTimeout(expiryTimer);
        byId('account').hidden = true;
        byId('auth-form').hidden = false;
        byId('tabs').hidden = false;
        byId('password').value = '';
        mode(false);
    }
    async function api(path, method, body) {
        const headers = { 'Content-Type': 'application/json' };
        if (token) headers.Authorization = `Bearer ${token}`;
        const response = await fetch(path, {
            method, headers, credentials: 'omit', cache: 'no-store',
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        const result = response.status === 204 ? null : await response.json();
        if (!response.ok) {
            if (response.status === 401 && token) clearSession();
            throw new Error(errors[result?.code] || 'درخواست انجام نشد. دوباره تلاش کنید.');
        }
        return result;
    }
    byId('login-tab').addEventListener('click', () => mode(false));
    byId('register-tab').addEventListener('click', () => mode(true));
    byId('auth-form').addEventListener('submit', async event => {
        event.preventDefault();
        byId('submit').disabled = true;
        byId('message').textContent = '';
        try {
            const body = { username: byId('username').value.trim(), password: byId('password').value };
            if (registering) {
                body.email = byId('email').value.trim();
                await api('/api/auth/register', 'POST', body);
                byId('password').value = '';
                mode(false);
                byId('message').textContent = 'حساب ساخته شد. اکنون وارد شوید.';
            } else {
                const result = await api('/api/auth/login', 'POST', body);
                token = result.accessToken;
                byId('password').value = '';
                const user = await api('/api/users/me', 'GET');
                byId('account-username').textContent = user.username;
                byId('account-email').textContent = user.email;
                byId('account-role').textContent = roles[user.role] || user.role;
                byId('account-enabled').textContent = user.enabled ? 'فعال' : 'غیرفعال';
                byId('auth-form').hidden = true;
                byId('tabs').hidden = true;
                byId('account').hidden = false;
                byId('title').textContent = 'ورود موفق';
                byId('intro').textContent = 'حساب شما آماده است.';
                expiryTimer = setTimeout(() => {
                    clearSession();
                    byId('message').textContent = 'نشست پایان یافت. دوباره وارد شوید.';
                }, result.expiresIn * 1000);
            }
        } catch (error) {
            byId('message').textContent = error instanceof TypeError ? 'ارتباط با سرور برقرار نشد.' : error.message;
        } finally { byId('submit').disabled = false; }
    });
    byId('logout').addEventListener('click', async () => {
        byId('logout').disabled = true;
        try {
            await api('/api/auth/logout', 'POST');
            clearSession();
            byId('message').textContent = 'از همه نشست‌ها خارج شدید.';
        } catch (error) { byId('message').textContent = error.message; }
        finally { byId('logout').disabled = false; }
    });
    mode(registering);
})();
