package com.xinghe.trade.auth;

import com.xinghe.trade.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountUserService accountService;
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(AccountUserService accountService, AuthenticationManager authenticationManager,
                          SessionAuthenticationStrategy sessionAuthenticationStrategy,
                          SecurityContextRepository securityContextRepository) {
        this.accountService = accountService;
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/register")
    public ApiResponse<AuthDtos.AccountView> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ApiResponse.ok(accountService.view(accountService.registerCustomer(request.username(), request.password())));
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.AccountView> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                    HttpServletRequest servletRequest,
                                                    HttpServletResponse servletResponse) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        sessionAuthenticationStrategy.onAuthentication(authentication, servletRequest, servletResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        servletRequest.getSession(true);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        return ApiResponse.ok(view((AccountUserPrincipal) authentication.getPrincipal()));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> loginFailure(org.springframework.security.core.AuthenticationException exception) {
        return ApiResponse.fail("用户名或密码错误");
    }

    @GetMapping("/csrf")
    public ApiResponse<String> csrf(CsrfToken csrfToken) {
        return ApiResponse.ok(csrfToken.getToken());
    }

    @GetMapping("/me")
    public ApiResponse<AuthDtos.AccountView> me(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            throw new org.springframework.security.authentication.InsufficientAuthenticationException("未登录");
        }
        if (authentication.getPrincipal() instanceof AccountUserPrincipal principal) {
            // The request filter has already checked this snapshot against the current account row.
            return ApiResponse.ok(view(principal));
        }
        UserDetails details = (UserDetails) authentication.getPrincipal();
        return ApiResponse.ok(accountService.view(accountService.loadActive(details.getUsername())));
    }

    private static AuthDtos.AccountView view(AccountUserPrincipal principal) {
        AuthenticatedUser user = principal.currentUser();
        return new AuthDtos.AccountView(user.userId(), user.username(), user.role(), user.storeId());
    }

    /** Logs out the current session after the CSRF filter has validated the request. */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest servletRequest) {
        SecurityContextHolder.clearContext();
        HttpSession session = servletRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.ok(null);
    }
}
