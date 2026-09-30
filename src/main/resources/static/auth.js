'use strict';
(() => {
    const byId = id => document.getElementById(id);
    let registering = location.pathname === '/register';
    let token = null; // In memory only: never stored in localStorage, URLs or cookies.
    let expiryTimer = null;
    const roles = { USER: 'User', REPORTER: 'Reporter', ADMIN: 'Administrator' };
    const errors = {
        INVALID_CREDENTIALS: 'Invalid username or password, or the account is disabled.',
        USER_EXISTS: 'Username or email is already registered.',
        INVALID_INPUT: 'Use an ASCII username, valid email and a password of at least 12 characters.',
        INVALID_PASSWORD: 'Password must contain at least 12 characters and at most 72 UTF-8 bytes.',
        RATE_LIMITED: 'Too many attempts. Try again later.',
        UNAUTHORIZED: 'Your token is invalid. Log in again.'
    };
    function mode(value) {
        registering = value;
        byId('title').textContent = value ? 'Create an account' : 'Log in';
        byId('intro').textContent = value ? 'New accounts receive the USER role.' : 'Log in to access your account.';
        byId('email-field').hidden = !value;
        byId('email').required = value;
        byId('password-help').hidden = !value;
        byId('password').minLength = value ? 12 : 1;
        byId('password').autocomplete = value ? 'new-password' : 'current-password';
        byId('submit').textContent = value ? 'Sign up' : 'Log in';
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
            throw new Error(errors[result?.code] || 'Request failed. Try again.');
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
                byId('message').textContent = 'Account created. You can now log in.';
            } else {
                const result = await api('/api/auth/login', 'POST', body);
                token = result.accessToken;
                byId('password').value = '';
                const user = await api('/api/users/me', 'GET');
                byId('account-username').textContent = user.username;
                byId('account-email').textContent = user.email;
                byId('account-role').textContent = roles[user.role] || user.role;
                byId('account-enabled').textContent = user.enabled ? 'Enabled' : 'Disabled';
                byId('auth-form').hidden = true;
                byId('tabs').hidden = true;
                byId('account').hidden = false;
                byId('title').textContent = 'Login successful';
                byId('intro').textContent = 'Your account is ready.';
                expiryTimer = setTimeout(() => {
                    clearSession();
                    byId('message').textContent = 'Your token expired. Log in again.';
                }, result.expiresIn * 1000);
            }
        } catch (error) {
            byId('message').textContent = error instanceof TypeError ? 'Cannot connect to the server.' : error.message;
        } finally { byId('submit').disabled = false; }
    });
    byId('logout').addEventListener('click', async () => {
        byId('logout').disabled = true;
        try {
            await api('/api/auth/logout', 'POST');
            clearSession();
            byId('message').textContent = 'All access tokens have been revoked.';
        } catch (error) { byId('message').textContent = error.message; }
        finally { byId('logout').disabled = false; }
    });
    mode(registering);
})();
