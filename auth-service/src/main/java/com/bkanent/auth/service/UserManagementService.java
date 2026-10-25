package com.bkanent.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.auth.dto.AuthUserManagementModels.CreateUserRequest;
import com.bkanent.auth.dto.AuthUserManagementModels.PageResult;
import com.bkanent.auth.dto.AuthUserManagementModels.RoleOption;
import com.bkanent.auth.dto.AuthUserManagementModels.UpdateUserRequest;
import com.bkanent.auth.dto.AuthUserManagementModels.UserItem;
import com.bkanent.auth.entity.AuthRoleEntity;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.mapper.AuthRoleMapper;
import com.bkanent.auth.mapper.UserAccountMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserManagementService {

    private final UserAccountMapper userAccountMapper;
    private final AuthRoleMapper authRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(UserAccountMapper userAccountMapper,
                                 AuthRoleMapper authRoleMapper,
                                 PasswordEncoder passwordEncoder) {
        this.userAccountMapper = userAccountMapper;
        this.authRoleMapper = authRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    private com.bkanent.auth.authorization.AuthAccountMutationService accountMutations;

    @org.springframework.beans.factory.annotation.Autowired
    public void configureAuthorization(org.springframework.beans.factory.ObjectProvider<com.bkanent.auth.authorization.AuthAccountMutationService> mutations,
                                       @org.springframework.beans.factory.annotation.Value("${auth.authorization.enabled:false}") boolean enabled) {
        if (enabled) {
            accountMutations = mutations.getIfAvailable();
            if (accountMutations == null) throw new IllegalStateException("Account authorization writer required");
        }
    }

    public boolean isVersionedAuthorization() { return accountMutations != null; }

    public boolean isAuthorizedAccount(UserAccountEntity account) {
        return account != null && Integer.valueOf(0).equals(account.getDeleted())
                && "ACTIVE".equalsIgnoreCase(account.getAccountStatus())
                && (isVersionedAuthorization() ? accountMutations.active(account.getId())
                : isSupportedRole(account.getRoleCode()) && isActiveRole(account.getRoleCode()));
    }

    public com.bkanent.auth.authorization.EffectivePermissionService.Snapshot authorization(long userId) {
        return accountMutations.snapshot(userId);
    }

    public com.bkanent.auth.dto.AuthUserManagementModels.CurrentUser currentUser(UserAccountEntity account) {
        if (!isVersionedAuthorization()) return new com.bkanent.auth.dto.AuthUserManagementModels.CurrentUser(
                account.getId(), account.getUsername(), account.getDisplayName(), account.getRoleCode(),
                getRoleNameForCode(account.getRoleCode()), account.getTenantCode());
        var snapshot = authorization(account.getId());
        return new com.bkanent.auth.dto.AuthUserManagementModels.CurrentUser(account.getId(), account.getUsername(),
                account.getDisplayName(), account.getRoleCode(), getRoleNameForCode(account.getRoleCode()), account.getTenantCode(),
                snapshot.businessAdmin(), accountMutations.roles(account.getId()), snapshot.permissionRevision(), snapshot.credentialEpoch());
    }

    public void logout(String token) { accountMutations.logout(token); }

    public static boolean isSupportedRole(String roleCode) {
        return "ADMIN".equals(roleCode) || "BROKER".equals(roleCode);
    }

    public boolean isActiveRole(String roleCode) {
        if (!isSupportedRole(roleCode)) return false;
        return authRoleMapper.selectCount(new LambdaQueryWrapper<AuthRoleEntity>()
                .eq(AuthRoleEntity::getRoleCode, roleCode)
                .eq(AuthRoleEntity::getActive, 1)) > 0;
    }

    public String getRoleNameForCode(String roleCode) {
        AuthRoleEntity role = authRoleMapper.selectOne(new LambdaQueryWrapper<AuthRoleEntity>()
                .eq(AuthRoleEntity::getRoleCode, roleCode)
                .last("limit 1"));
        return role == null ? roleCode : role.getRoleName();
    }

    public List<RoleOption> listRoles() {
        return authRoleMapper.selectList(new LambdaQueryWrapper<AuthRoleEntity>()
                        .eq(AuthRoleEntity::getActive, 1)
                        .eq(AuthRoleEntity::getAssignable, 1)
                        .orderByAsc(AuthRoleEntity::getId))
                .stream()
                .filter(role -> isVersionedAuthorization() || isSupportedRole(role.getRoleCode()))
                .map(role -> new RoleOption(role.getRoleCode(), role.getRoleName(), role.getDescription()))
                .toList();
    }

    public PageResult<UserItem> listUsers(long page, long pageSize, String keyword,
                                          String roleCode, String accountStatus) {
        long safePage = Math.max(1, page);
        long safePageSize = Math.min(100, Math.max(1, pageSize));
        String safeKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        String safeRoleCode = StringUtils.hasText(roleCode) ? roleCode.trim() : null;
        String safeAccountStatus = StringUtils.hasText(accountStatus) ? accountStatus.trim() : null;
        long total = userAccountMapper.countManagementUsers(safeKeyword, safeRoleCode, safeAccountStatus);
        List<UserAccountEntity> accounts = userAccountMapper.selectManagementPage(
                safeKeyword, safeRoleCode, safeAccountStatus, safePageSize, (safePage - 1) * safePageSize);
        Map<String, String> roleNames = authRoleMapper.selectList(new LambdaQueryWrapper<AuthRoleEntity>()
                        .eq(AuthRoleEntity::getActive, 1))
                .stream()
                .collect(Collectors.toMap(AuthRoleEntity::getRoleCode, AuthRoleEntity::getRoleName));
        List<UserItem> records = accounts.stream()
                .map(account -> toUserItem(account, roleNames))
                .toList();
        return new PageResult<>(records, total, safePage, safePageSize);
    }

    @Transactional
    public UserItem createUser(CreateUserRequest request) {
        return createUser(0, request);
    }

    public UserItem createUser(long actorId, CreateUserRequest request) {
        return createUser(null, actorId, request);
    }

    public UserItem createUser(String accessToken, long actorId, CreateUserRequest request) {
        if (isVersionedAuthorization()) {
            try {
                var account = accountMutations.create(accessToken, actorId, request);
                return toUserItem(account, Map.of(account.getRoleCode(), roleName(account.getRoleCode())));
            } catch (DuplicateKeyException duplicate) { throw conflict("账号已存在"); }
        }
        String roleCode = request.roleCode().trim().toUpperCase(Locale.ROOT);
        if (userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getUsername, request.username())) > 0) {
            throw conflict("账号已存在");
        }
        findActiveRole(roleCode, true);
        UserAccountEntity account = new UserAccountEntity();
        account.setUsername(request.username().trim());
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setDisplayName(request.displayName().trim());
        account.setRoleCode(roleCode);
        account.setTenantCode(request.tenantCode().trim());
        account.setAccountStatus("ACTIVE");
        LocalDateTime now = LocalDateTime.now();
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        account.setDeleted(0);
        try {
            userAccountMapper.insert(account);
        } catch (DuplicateKeyException exception) {
            throw conflict("账号已存在");
        }
        return toUserItem(account, Map.of(account.getRoleCode(), roleName(account.getRoleCode())));
    }

    @Transactional
    public UserItem updateUser(long actorId, long userId, UpdateUserRequest request) {
        return updateUser(null, actorId, userId, request);
    }

    public UserItem updateUser(String accessToken, long actorId, long userId, UpdateUserRequest request) {
        if (isVersionedAuthorization()) {
            var updated = accountMutations.update(accessToken, actorId, userId, request);
            return toUserItem(updated, Map.of(updated.getRoleCode(), roleName(updated.getRoleCode())));
        }
        UserAccountEntity account = requireUser(userId);
        String roleCode = request.roleCode().trim().toUpperCase(Locale.ROOT);
        findActiveRole(roleCode, true);
        boolean isSelf = account.getId().equals(actorId);
        boolean isLeavingAdmin = "ADMIN".equals(account.getRoleCode())
                && (!"ADMIN".equals(roleCode) || !"ACTIVE".equals(request.accountStatus()));
        if (isSelf && isLeavingAdmin) {
            throw forbidden("不能停用或移除当前登录管理员角色");
        }
        if (isLeavingAdmin) {
            ensureAnotherActiveAdmin(account.getId());
        }
        account.setDisplayName(request.displayName().trim());
        account.setRoleCode(roleCode);
        account.setTenantCode(request.tenantCode().trim());
        account.setAccountStatus(request.accountStatus());
        account.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(account);
        return toUserItem(account, Map.of(account.getRoleCode(), roleName(account.getRoleCode())));
    }

    @Transactional
    public void resetPassword(long userId, String password) { resetPassword(0, userId, password); }

    public void resetPassword(long actorId, long userId, String password) { resetPassword(null, actorId, userId, password); }

    public void resetPassword(String accessToken, long actorId, long userId, String password) {
        if (isVersionedAuthorization()) { accountMutations.resetPassword(accessToken, actorId, userId, password); return; }
        UserAccountEntity account = requireUser(userId);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(account);
    }

    @Transactional
    public void deleteUser(long actorId, long userId) { deleteUser(null, actorId, userId); }

    public void deleteUser(String accessToken, long actorId, long userId) {
        if (isVersionedAuthorization()) { accountMutations.delete(accessToken, actorId, userId); return; }
        UserAccountEntity account = requireUser(userId);
        if (account.getId().equals(actorId)) {
            throw forbidden("不能删除当前登录账号");
        }
        if ("ADMIN".equals(account.getRoleCode())
                && "ACTIVE".equals(account.getAccountStatus())) {
            ensureAnotherActiveAdmin(account.getId());
        }
        userAccountMapper.softDeleteById(userId);
    }

    private UserAccountEntity requireUser(long userId) {
        UserAccountEntity account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new UserManagementException(HttpStatus.NOT_FOUND, "AUTH_USER_NOT_FOUND", "用户不存在");
        }
        return account;
    }

    private AuthRoleEntity findActiveRole(String roleCode, boolean requireAssignable) {
        if (!isSupportedRole(roleCode)) {
            throw new UserManagementException(HttpStatus.BAD_REQUEST, "AUTH_ROLE_INVALID", "角色不存在或不可分配");
        }
        AuthRoleEntity role = authRoleMapper.selectOne(new LambdaQueryWrapper<AuthRoleEntity>()
                .eq(AuthRoleEntity::getRoleCode, roleCode)
                .eq(AuthRoleEntity::getActive, 1)
                .eq(requireAssignable, AuthRoleEntity::getAssignable, 1)
                .last("limit 1"));
        if (role == null) {
            throw new UserManagementException(HttpStatus.BAD_REQUEST, "AUTH_ROLE_INVALID", "角色不存在或不可分配");
        }
        return role;
    }

    private String roleName(String roleCode) {
        return getRoleNameForCode(roleCode);
    }

    private void ensureAnotherActiveAdmin(Long excludedUserId) {
        long activeAdmins = userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getRoleCode, "ADMIN")
                .eq(UserAccountEntity::getAccountStatus, "ACTIVE")
                .ne(excludedUserId != null, UserAccountEntity::getId, excludedUserId));
        if (activeAdmins == 0) {
            throw conflict("系统至少需要保留一个启用的管理员");
        }
    }

    private UserItem toUserItem(UserAccountEntity account, Map<String, String> roleNames) {
        return new UserItem(account.getId(), account.getUsername(), account.getDisplayName(),
                account.getRoleCode(), roleNames.getOrDefault(account.getRoleCode(), account.getRoleCode()),
                account.getTenantCode(), account.getAccountStatus(), account.getCreatedAt());
    }

    private UserManagementException conflict(String message) {
        return new UserManagementException(HttpStatus.CONFLICT, "AUTH_USER_CONFLICT", message);
    }

    private UserManagementException forbidden(String message) {
        return new UserManagementException(HttpStatus.FORBIDDEN, "AUTH_FORBIDDEN", message);
    }
}
