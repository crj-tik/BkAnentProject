package com.bkanent.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * UserAccountEntity 实体类。
 */
@TableName("user_account")
public class UserAccountEntity extends BaseEntity {

    /**
     * 业务属性：username。
     */
    private String username;
    /**
     * 业务属性：passwordHash。
     */
    private String passwordHash;
    /**
     * 业务属性：displayName。
     */
    private String displayName;
    /**
     * 业务属性：roleCode。
     */
    private String roleCode;
    /**
     * 业务属性：tenantCode。
     */
    private String tenantCode;
    /**
     * 业务属性：accountStatus。
     */
    private String accountStatus;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }
}

