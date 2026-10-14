package com.xinghe.trade.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(min = 8, max = 128) String password) {}
    public record RegisterRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(min = 8, max = 128) String password) {}
    public record AccountView(String userId, String username, String role, Long storeId) {}
}