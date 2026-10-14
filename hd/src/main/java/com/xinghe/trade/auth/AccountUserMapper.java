package com.xinghe.trade.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountUserMapper extends BaseMapper<AccountUser> {
    @Select("SELECT * FROM account_user WHERE username = #{username} LIMIT 1")
    AccountUser findByUsername(String username);
}