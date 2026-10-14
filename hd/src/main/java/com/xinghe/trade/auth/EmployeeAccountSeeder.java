package com.xinghe.trade.auth;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Creates a scoped employee account only when explicitly enabled for a trusted environment. */
@Component
@ConditionalOnProperty(name = "xinghe.auth.employee-seed.enabled", havingValue = "true")
public class EmployeeAccountSeeder implements ApplicationRunner {
    private final AccountUserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final Long storeId;

    public EmployeeAccountSeeder(AccountUserMapper mapper, PasswordEncoder passwordEncoder,
                                 @Value("${xinghe.auth.employee-seed.username:}") String username,
                                 @Value("${xinghe.auth.employee-seed.password:}") String password,
                                 @Value("${xinghe.auth.employee-seed.store-id:}") Long storeId) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.storeId = storeId;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.length() < 8 || storeId == null || storeId <= 0) {
            throw new IllegalStateException("Employee account seeding requires username, password (8+ chars), and positive store id");
        }
        if (mapper.findByUsername(username) != null) {
            throw new IllegalStateException("Employee seed username already exists; refusing to modify an existing account");
        }
        LocalDateTime now = LocalDateTime.now();
        AccountUser account = new AccountUser();
        account.setUserId("employee-" + IdWorker.getId());
        account.setUsername(username.trim());
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setRole(AccountUserService.EMPLOYEE_ROLE);
        account.setStatus("ACTIVE");
        account.setStoreId(storeId);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        mapper.insert(account);
    }
}