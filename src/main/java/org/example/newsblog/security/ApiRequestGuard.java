package org.example.newsblog.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded single-instance throttling. Does not trust caller-supplied proxy IP headers. */
final class ApiRequestGuard extends OncePerRequestFilter {
    private final Map<String, Window> windows = new HashMap<>();
    private record Window(long expiresAt, int attempts) {}

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean register = path.equals("/api/auth/register");
        boolean login = path.equals("/api/auth/login");
        if (request.getMethod().equals("POST") && (register || login)
                && !allow(request.getRemoteAddr() + ":" + path, register)) {
            response.setHeader("Retry-After", register ? "3600" : "600");
            SecurityResponses.error(response, 429, "RATE_LIMITED");
            return;
        }
        if (path.startsWith("/api/") && (request.getMethod().equals("POST") || request.getMethod().equals("PATCH"))) {
            byte[] bytes = request.getInputStream().readNBytes(32769);
            if (bytes.length > 32768) {
                SecurityResponses.error(response, 413, "BODY_TOO_LARGE");
                return;
            }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input = new ByteArrayInputStream(bytes);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) {
                            throw new UnsupportedOperationException("Synchronous API only");
                        }
                    };
                }
                @Override public BufferedReader getReader() {
                    return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }, response);
        } else chain.doFilter(request, response);
    }

    private synchronized boolean allow(String key, boolean register) {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= 5000) return false;
            window = new Window(now + (register ? 3600000L : 600000L), 0);
        }
        int max = register ? 10 : 20;
        if (window.attempts() >= max) return false;
        windows.put(key, new Window(window.expiresAt(), window.attempts() + 1));
        return true;
    }
}
