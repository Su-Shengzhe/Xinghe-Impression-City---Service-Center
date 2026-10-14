package com.xinghe.trade.auth;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("account_user")
public class AccountUser {
    @TableId
    private String userId;
    private String username;
    private String passwordHash;
    private String role;
    private String status;
    private Long storeId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}