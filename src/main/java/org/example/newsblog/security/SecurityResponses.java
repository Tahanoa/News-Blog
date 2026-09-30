package org.example.newsblog.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

final class SecurityResponses {
    private SecurityResponses() {}
    static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"Request rejected\"}");
    }
}
