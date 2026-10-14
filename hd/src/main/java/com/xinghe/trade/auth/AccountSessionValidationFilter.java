package com.xinghe.trade.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Rejects cached authorization immediately after an account is changed or disabled. */
final class AccountSessionValidationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AccountSessionValidationFilter.class);
    private final AccountUserService accounts;

    AccountSessionValidationFilter(AccountUserService accounts) {
        this.accounts = accounts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // Keep login reachable from a stale session so the user can authenticate again.
        return path.equals("/api/auth/login") || path.equals("/api/auth/register")
                || path.equals("/api/auth/csrf") || path.equals("/api/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountUserPrincipal principal) {
            AccountUser current;
            try {
                current = accounts.findById(principal.getUserId());
            } catch (RuntimeException exception) {
                log.error("Unable to validate account session for userId={}", principal.getUserId(), exception);
                writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "账户校验暂不可用，请稍后重试");
                return;
            }
            if (principal.matchesCurrentAccount(current)) {
                chain.doFilter(request, response);
                return;
            }
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "账号信息已变更，请重新登录");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":-1,\"message\":\"" + message + "\",\"data\":null}");
    }
}
