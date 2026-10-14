package com.xinghe.trade.order;

/** Committed checkout result bound to a user-scoped idempotency token. */
public class CheckoutIdempotency {
    private String userId;
    private String token;
    private String parentOrderNo;
    private String status;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getParentOrderNo() { return parentOrderNo; }
    public void setParentOrderNo(String parentOrderNo) { this.parentOrderNo = parentOrderNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
