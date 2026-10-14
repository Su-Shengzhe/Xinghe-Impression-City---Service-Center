package com.xinghe.trade.auth;

/** The verified account attributes that are safe for business controllers to consume. */
public record AuthenticatedUser(String userId, String username, String role, Long storeId) {
    /** Returns the employee's server-bound store, rejecting malformed or non-employee identities. */
    public Long requireStoreId() {
        if (!AccountUserService.EMPLOYEE_ROLE.equals(role) || storeId == null || storeId <= 0) {
            throw new SecurityException("无权操作该店铺");
        }
        return storeId;
    }
}