package com.hmdp.mapper;

import com.hmdp.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Mapper

public interface UserMapper extends BaseMapper<User> {

    @Insert("insert into tb_user (phone) values (#{phone})")
    void createUserWithPhone(String phone);
    @Select("select * from tb_user where phone = #{phone}")
    User findByPhone(String phone);
}
