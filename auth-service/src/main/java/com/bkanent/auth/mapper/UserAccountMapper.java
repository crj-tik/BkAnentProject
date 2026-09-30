package com.bkanent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bkanent.auth.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
/**
 * UserAccountMapper 数据访问接口。
 */

public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {

    @Select("SELECT * FROM user_account WHERE deleted = 0 "
            + "AND (#{keyword} IS NULL OR username LIKE CONCAT('%', #{keyword}, '%') "
            + "OR display_name LIKE CONCAT('%', #{keyword}, '%')) "
            + "AND (#{roleCode} IS NULL OR role_code = #{roleCode}) "
            + "AND (#{accountStatus} IS NULL OR account_status = #{accountStatus}) "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<UserAccountEntity> selectManagementPage(@Param("keyword") String keyword,
                                                @Param("roleCode") String roleCode,
                                                @Param("accountStatus") String accountStatus,
                                                @Param("limit") long limit,
                                                @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM user_account WHERE deleted = 0 "
            + "AND (#{keyword} IS NULL OR username LIKE CONCAT('%', #{keyword}, '%') "
            + "OR display_name LIKE CONCAT('%', #{keyword}, '%')) "
            + "AND (#{roleCode} IS NULL OR role_code = #{roleCode}) "
            + "AND (#{accountStatus} IS NULL OR account_status = #{accountStatus})")
    long countManagementUsers(@Param("keyword") String keyword,
                              @Param("roleCode") String roleCode,
                              @Param("accountStatus") String accountStatus);

    @Update("UPDATE user_account SET deleted = 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{userId} AND deleted = 0")
    int softDeleteById(@Param("userId") long userId);
}

