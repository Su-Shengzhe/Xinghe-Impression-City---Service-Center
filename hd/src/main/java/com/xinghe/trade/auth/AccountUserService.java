package com.xinghe.trade.auth;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AccountUserService {
    public static final String CUSTOMER_ROLE = "CUSTOMER";
    public static final String EMPLOYEE_ROLE = "STORE_EMPLOYEE";

    private final AccountUserMapper mapper;
    private final PasswordEncoder passwordEncoder;

    public AccountUserService(AccountUserMapper mapper, PasswordEncoder passwordEncoder) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
    }

    public AccountUser loadActive(String username) {
        AccountUser account = mapper.findByUsername(username);
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return account;
    }

    public AccountUser findById(String userId) {
        return mapper.selectById(userId);
    }

    @Transactional
    public AccountUser registerCustomer(String username, String password) {
        if (mapper.findByUsername(username) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        AccountUser account = new AccountUser();
        account.setUserId("user-" + IdWorker.getId());
        account.setUsername(username.trim());
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setRole(CUSTOMER_ROLE);
        account.setStatus("ACTIVE");
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        try {
            mapper.insert(account);
            return account;
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("用户名已存在");
        }
    }

    public AuthDtos.AccountView view(AccountUser account) {
        return new AuthDtos.AccountView(account.getUserId(), account.getUsername(), account.getRole(), account.getStoreId());
    }
}
