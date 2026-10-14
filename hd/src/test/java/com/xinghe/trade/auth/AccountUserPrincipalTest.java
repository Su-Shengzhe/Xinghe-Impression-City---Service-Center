package com.xinghe.trade.auth;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AccountUserPrincipalTest {
    @Test
    void credentialChangeAndAccountRemovalRetireSessionSnapshot() {
        AccountUser account = new AccountUser();
        account.setUserId("user-1001"); account.setUsername("customer");
        account.setPasswordHash("old-hash"); account.setRole("CUSTOMER"); account.setStatus("ACTIVE");
        AccountUserPrincipal principal = new AccountUserPrincipal(account);
        assertTrue(principal.matchesCurrentAccount(account));
        account.setPasswordHash("new-hash");
        assertFalse(principal.matchesCurrentAccount(account));
        assertFalse(principal.matchesCurrentAccount(null));
    }
    @Test
    void principalCanBeSerializedForRedisSessionStorage() throws Exception {
        AccountUser account = new AccountUser();
        account.setUserId("user-1001");
        account.setUsername("customer");
        account.setPasswordHash("password-hash");
        account.setRole(AccountUserService.CUSTOMER_ROLE);
        account.setStatus("ACTIVE");

        AccountUserPrincipal principal = new AccountUserPrincipal(account);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(principal);
        }

        AccountUserPrincipal restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (AccountUserPrincipal) input.readObject();
        }

        assertEquals("customer", restored.getUsername());
        assertEquals("password-hash", restored.getPassword());
        assertTrue(restored.isEnabled());
        assertEquals("user-1001", restored.currentUser().userId());
        assertEquals("CUSTOMER", restored.currentUser().role());
    }
}
