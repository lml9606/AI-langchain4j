package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.User;
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
