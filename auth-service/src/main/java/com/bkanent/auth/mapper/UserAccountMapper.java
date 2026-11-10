package com.bkanent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bkanent.auth.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;

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

    @Select("SELECT u.* FROM user_account u WHERE u.deleted = 0 "
            + "AND (#{keyword} IS NULL OR u.username LIKE CONCAT('%', #{keyword}, '%') "
            + "OR u.display_name LIKE CONCAT('%', #{keyword}, '%')) "
            + "AND (#{accountStatus} IS NULL OR u.account_status = #{accountStatus}) "
            + "AND (#{roleCode} IS NULL OR EXISTS (SELECT 1 FROM auth_user_role ur "
            + "JOIN auth_role r ON r.id = ur.role_id WHERE ur.user_id = u.id AND r.role_code = #{roleCode} "
            + "AND ur.active = 1 AND r.active = 1 AND r.deleted = 0 "
            + "AND (ur.valid_from IS NULL OR ur.valid_from <= #{now}) "
            + "AND (ur.valid_until IS NULL OR ur.valid_until > #{now}))) "
            + "ORDER BY u.created_at DESC, u.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<UserAccountEntity> selectAuthorizationManagementPage(@Param("keyword") String keyword,
                                                              @Param("roleCode") String roleCode,
                                                              @Param("accountStatus") String accountStatus,
                                                              @Param("now") java.time.LocalDateTime now,
                                                              @Param("limit") long limit,
                                                              @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM user_account u WHERE u.deleted = 0 "
            + "AND (#{keyword} IS NULL OR u.username LIKE CONCAT('%', #{keyword}, '%') "
            + "OR u.display_name LIKE CONCAT('%', #{keyword}, '%')) "
            + "AND (#{accountStatus} IS NULL OR u.account_status = #{accountStatus}) "
            + "AND (#{roleCode} IS NULL OR EXISTS (SELECT 1 FROM auth_user_role ur "
            + "JOIN auth_role r ON r.id = ur.role_id WHERE ur.user_id = u.id AND r.role_code = #{roleCode} "
            + "AND ur.active = 1 AND r.active = 1 AND r.deleted = 0 "
            + "AND (ur.valid_from IS NULL OR ur.valid_from <= #{now}) "
            + "AND (ur.valid_until IS NULL OR ur.valid_until > #{now})))")
    long countAuthorizationManagementUsers(@Param("keyword") String keyword,
                                           @Param("roleCode") String roleCode,
                                           @Param("accountStatus") String accountStatus,
                                           @Param("now") java.time.LocalDateTime now);

    @Update("UPDATE user_account SET deleted = 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{userId} AND deleted = 0")
    int softDeleteById(@Param("userId") long userId);

    @Update("UPDATE user_account SET display_name = #{displayName}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{userId} AND deleted = 0 AND account_status = 'ACTIVE'")
    int updateOwnDisplayName(@Param("userId") long userId, @Param("displayName") String displayName);

    @Select("SELECT o.id,o.org_code,o.org_name,o.org_type,o.parent_id,m.is_primary "
            + "FROM auth_org_membership m JOIN auth_org_unit o ON o.id=m.org_id "
            + "WHERE m.user_id=#{userId} AND m.status='ACTIVE' AND o.status='ACTIVE' "
            + "AND (m.valid_from IS NULL OR m.valid_from<=#{now}) "
            + "AND (m.valid_until IS NULL OR m.valid_until>#{now}) ORDER BY m.is_primary DESC,o.id")
    @ConstructorArgs({
            @Arg(column="id", javaType=Long.class), @Arg(column="org_code", javaType=String.class),
            @Arg(column="org_name", javaType=String.class), @Arg(column="org_type", javaType=String.class),
            @Arg(column="parent_id", javaType=Long.class), @Arg(column="is_primary", javaType=boolean.class)
    })
    List<com.bkanent.auth.dto.AuthUserManagementModels.OrganizationSummary> selectCurrentOrganizations(
            @Param("userId") long userId, @Param("now") java.sql.Timestamp now);
}

