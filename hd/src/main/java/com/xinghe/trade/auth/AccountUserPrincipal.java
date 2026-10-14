package com.xinghe.trade.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Spring Security principal retaining the account ID and employee store binding. */
public final class AccountUserPrincipal implements UserDetails, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String userId;
    private final String username;
    private final String passwordHash;
    private final String role;
    private final String status;
    private final Long storeId;

    public AccountUserPrincipal(AccountUser account) {
        this.userId = account.getUserId();
        this.username = account.getUsername();
        this.passwordHash = account.getPasswordHash();
        this.role = account.getRole();
        this.status = account.getStatus();
        this.storeId = account.getStoreId();
    }

    public AuthenticatedUser currentUser() {
        return new AuthenticatedUser(userId, username, role, storeId);
    }

    public String getUserId() {
        return userId;
    }

    /** Any change to the account's authorization or credentials retires this session. */
    public boolean matchesCurrentAccount(AccountUser account) {
        return account != null
                && "ACTIVE".equals(account.getStatus())
                && Objects.equals(userId, account.getUserId())
                && Objects.equals(username, account.getUsername())
                && Objects.equals(passwordHash, account.getPasswordHash())
                && Objects.equals(role, account.getRole())
                && Objects.equals(storeId, account.getStoreId());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return "ACTIVE".equals(status);
    }
}
