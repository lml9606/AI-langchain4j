package com.example.langchain4j.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户表 Mapper。
 */
@Mapper
public interface UserMapper {

    int insert(User user);

    User selectByUsername(@Param("username") String username);
}
