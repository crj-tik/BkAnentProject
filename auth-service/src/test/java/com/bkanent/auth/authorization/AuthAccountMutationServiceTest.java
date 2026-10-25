package com.bkanent.auth.authorization;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.controller.AuthController;
import com.bkanent.auth.controller.AuthExceptionHandler;
import com.bkanent.auth.dto.AuthUserManagementModels.CreateUserRequest;
import com.bkanent.auth.dto.AuthUserManagementModels.UpdateUserRequest;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.mapper.AuthRoleMapper;
import com.bkanent.auth.mapper.UserAccountMapper;
import com.bkanent.auth.rpc.AuthPermissionRpcServiceImpl;
import com.bkanent.auth.service.*;
import com.bkanent.common.model.AuthLoginRequest;
import com.bkanent.common.model.AuthRefreshRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real local H2 account/schema/outbox writes and real signed tokens; MVC exercises actual controller boundaries. */
class AuthAccountMutationServiceTest {
    JdbcTemplate jdbc;
    AuthorizationRepository repository;
    AuthAccountMutationService mutations;
    AuthTokenService tokens;
    FailingStore store;
    UserManagementService management;
    UserAccountService accounts;
    AuthController controller;
    AuthPermissionRpcServiceImpl rpc;
    MockMvc mvc;
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema-h2.sql"),
                new ClassPathResource("db/data-h2.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        repository = new AuthorizationRepository(jdbc, new DataSourceTransactionManager(source));
        var properties = new AuthTokenProperties(); properties.setSecret("account-mutation-test-only-secret");
        store = new FailingStore(); tokens = new AuthTokenService(properties, store); tokens.initialize();
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("repository", repository);
        tokens.configureAuthorization(beans.getBeanProvider(AuthorizationRepository.class), true);
        mutations = new AuthAccountMutationService(repository, encoder, tokens); mutations.validateSchema();
        management = new UserManagementService(mock(UserAccountMapper.class), mock(AuthRoleMapper.class), encoder);
        beans.registerSingleton("mutations", mutations);
        management.configureAuthorization(beans.getBeanProvider(AuthAccountMutationService.class), true);
        accounts = mock(UserAccountService.class);
        when(accounts.getById(anyLong())).thenAnswer(call -> account(((Number) call.getArgument(0)).longValue()));
        when(accounts.findByUsername(anyString())).thenAnswer(call -> {
            var ids = jdbc.queryForList("SELECT id FROM user_account WHERE username=? AND deleted=0", Long.class, call.<String>getArgument(0));
            return ids.isEmpty() ? null : account(ids.get(0));
        });
        controller = new AuthController(accounts, encoder, tokens, management);
        rpc = new AuthPermissionRpcServiceImpl(tokens, accounts, management);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new AuthExceptionHandler()).build();
        jdbc.update("UPDATE user_account SET password_hash=?", encoder.encode("correct-password"));
    }

    @Test
    void noRoleAdminAndOrdinaryEmptyRolesAndMultiRoleAllAuthenticateWithoutLegacyNames() throws Exception {
        jdbc.update("DELETE FROM auth_user_role WHERE user_id=2");
        jdbc.update("UPDATE user_account SET role_code='UNKNOWN' WHERE id=2");
        var admin = controller.login(new AuthLoginRequest("admin01", "correct-password", null));
        assertTrue(admin.success());
        assertNotNull(rpc.resolvePrincipal(admin.data().accessToken()));
        assertTrue(controller.refresh(new AuthRefreshRequest(admin.data().refreshToken())).success());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + admin.data().accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.businessAdmin").value(true))
                .andExpect(jsonPath("$.data.roles").isEmpty()).andExpect(jsonPath("$.data.roleCode").value("UNKNOWN"));
        mvc.perform(get("/auth/roles").header("Authorization", "Bearer " + admin.data().accessToken()))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM auth_user_role WHERE user_id=1");
        jdbc.update("UPDATE user_account SET role_code='UNKNOWN' WHERE id=1");
        var ordinary = controller.login(new AuthLoginRequest("broker01", "correct-password", null));
        assertTrue(ordinary.success());
        assertNotNull(rpc.resolvePrincipal(ordinary.data().accessToken()));
        assertFalse(rpc.hasPermission(1L, "agent.chat.use"));
        mvc.perform(get("/auth/roles").header("Authorization", "Bearer " + ordinary.data().accessToken()))
                .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO auth_role_permission SELECT id,'memory.read' FROM auth_role WHERE role_code='MANAGER'");
        jdbc.update("INSERT INTO auth_user_role SELECT 1,id,NULL,NULL,1 FROM auth_role WHERE role_code IN ('BROKER','MANAGER')");
        assertEquals(java.util.List.of("BROKER", "MANAGER"), mutations.roles(1));
        assertTrue(rpc.hasPermission(1L, "memory.read"));
        assertFalse(rpc.hasPermission(2L, "invented.manage"));
        assertFalse(rpc.hasPermission(2L, "agent.workflow.invoke.invented"));
        assertFalse(rpc.hasPermission(2L, "agent.chat.use.extra"));
    }

    @Test
    void managementWritesReplaceRealRelationsAndNeverGrantAdminFromRoleCode() throws Exception {
        var created = management.createUser(tokens.issueAccessToken(account(2)), 2, new CreateUserRequest("new-admin", "correct-password", "new", "ADMIN", "test"));
        long id = created.userId();
        assertFalse(repository.snapshot(id, Instant.now()).orElseThrow().businessAdmin());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_user_role WHERE user_id=?", Integer.class, id));
        assertEquals(2, repository.currentVersion(id).orElseThrow().permissionRevision());
        management.updateUser(tokens.issueAccessToken(account(2)), 2, id, new UpdateUserRequest("updated", "MANAGER", "test", "ACTIVE"));
        assertEquals(java.util.List.of("MANAGER"), mutations.roles(id));
        assertFalse(repository.snapshot(id, Instant.now()).orElseThrow().businessAdmin());
        long epoch = repository.currentVersion(id).orElseThrow().credentialEpoch();
        management.resetPassword(tokens.issueAccessToken(account(2)), 2, id, "replacement-password");
        assertEquals(epoch + 1, repository.currentVersion(id).orElseThrow().credentialEpoch());
        assertTrue(encoder.matches("replacement-password", account(id).getPasswordHash()));
        management.updateUser(tokens.issueAccessToken(account(2)), 2, id, new UpdateUserRequest("updated", "MANAGER", "test", "DISABLED"));
        assertEquals(epoch + 2, repository.currentVersion(id).orElseThrow().credentialEpoch());
        assertEquals("DISABLED", repository.currentVersion(id).orElseThrow().status());
        management.deleteUser(tokens.issueAccessToken(account(2)), 2, id);
        assertEquals(epoch + 3, repository.currentVersion(id).orElseThrow().credentialEpoch());
        assertEquals("DELETED", repository.currentVersion(id).orElseThrow().status());
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_audit WHERE actor_id=2", Integer.class));
        var token = tokens.issueAccessToken(account(2));
        mvc.perform(post("/auth/users").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"mvc-created\",\"password\":\"correct-password\",\"displayName\":\"MVC\",\"roleCode\":\"MANAGER\",\"tenantCode\":\"test\",\"businessAdmin\":true}"))
                .andExpect(status().isCreated());
        long mvcId = jdbc.queryForObject("SELECT id FROM user_account WHERE username='mvc-created'", Long.class);
        assertEquals(0, jdbc.queryForObject("SELECT business_admin FROM user_account WHERE id=?", Integer.class, mvcId));
    }

    @Test
    void selfDisableDeleteAndLastBusinessAdminAreRejectedAtomically() {
        long revision = repository.currentVersion(2).orElseThrow().permissionRevision();
        assertThrows(UserManagementException.class, () -> management.updateUser(tokens.issueAccessToken(account(2)), 2, 2,
                new UpdateUserRequest("self", "BROKER", "test", "DISABLED")));
        assertThrows(UserManagementException.class, () -> management.deleteUser(tokens.issueAccessToken(account(2)), 2, 2));
        // Leaving legacy ADMIN does not revoke the independent flag.
        management.updateUser(tokens.issueAccessToken(account(2)), 2, 2, new UpdateUserRequest("admin", "MANAGER", "test", "ACTIVE"));
        assertTrue(repository.snapshot(2, Instant.now()).orElseThrow().businessAdmin());
        assertEquals(revision + 1, repository.currentVersion(2).orElseThrow().permissionRevision());
        // Trusted internal writer still rejects deleting the sole admin from another actor.
        jdbc.update("UPDATE user_account SET business_admin=1 WHERE id=1");
        jdbc.update("UPDATE user_account SET business_admin=0 WHERE id=1");
        assertThrows(UserManagementException.class, () -> management.deleteUser(1, 2));
        assertEquals(0, jdbc.queryForObject("SELECT deleted FROM user_account WHERE id=2", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
    }

    @Test
    void logoutSqlFirstDeniesPendingTokenAndRetriesWithoutEpochStormAndOtherTokensReenter() throws Exception {
        String submitted = tokens.issueAccessToken(account(1));
        String other = tokens.issueRefreshToken(account(1)); // Existing format has no device id; distinct valid credential.
        long epoch = repository.currentVersion(1).orElseThrow().credentialEpoch();
        store.fail = true;
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + submitted))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_LOGOUT_PENDING"));
        assertEquals(epoch + 1, repository.currentVersion(1).orElseThrow().credentialEpoch());
        assertNull(tokens.parse(submitted)); assertNull(rpc.resolvePrincipal(submitted));
        assertNotNull(tokens.parse(other));
        assertEquals("PENDING", repository.logoutEvent(AuthTokenService.logoutDigest(submitted)).orElseThrow().state());
        store.fail = false;
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + submitted)).andExpect(status().isOk());
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + submitted)).andExpect(status().isOk());
        assertEquals(epoch + 1, repository.currentVersion(1).orElseThrow().credentialEpoch());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox WHERE reason='LOGOUT'", Integer.class));
        assertEquals("REVOKED", repository.logoutEvent(AuthTokenService.logoutDigest(submitted)).orElseThrow().state());
        assertTrue(controller.refresh(new AuthRefreshRequest(other)).success());
        assertEquals(epoch + 1, repository.currentVersion(1).orElseThrow().credentialEpoch(), "refresh's internal revoke is not logout");
    }

    @Test
    void missingStateAndMissingAccountEventSchemaFailClosedAndMutationRollsBack() {
        jdbc.update("DELETE FROM auth_user_authorization_state WHERE user_id=1");
        assertFalse(controller.login(new AuthLoginRequest("broker01", "correct-password", null)).success());
        assertThrows(IllegalStateException.class, () -> management.resetPassword(tokens.issueAccessToken(account(2)), 2, 1, "new-password"));
        assertTrue(encoder.matches("correct-password", account(1).getPasswordHash()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
        jdbc.execute("DROP TABLE auth_account_logout_event");
        assertThrows(org.springframework.dao.DataAccessException.class, mutations::validateSchema);
    }

    @Test
    void canonicalTokenAliasesNeverCreateSecondLogoutEpochEvent() {
        String token = tokens.issueAccessToken(account(1));
        long epoch = repository.currentVersion(1).orElseThrow().credentialEpoch();
        mutations.logout(token);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int index = alphabet.indexOf(token.charAt(token.length()-1));
        String padBits = token.substring(0,token.length()-1) + alphabet.charAt(index+1);
        for (String alias : java.util.List.of(token+"=", padBits)) {
            assertNull(tokens.parse(alias)); assertNull(tokens.verifyForLogout(alias));
            tokens.revoke(alias); mutations.logout(alias);
        }
        mutations.logout(token);
        assertEquals(epoch+1, repository.currentVersion(1).orElseThrow().credentialEpoch());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_account_logout_event", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox WHERE reason='LOGOUT'", Integer.class));
    }

    @Test
    void logoutDuringRealPasswordHashingPreventsCreateAndResetWrites() throws Exception {
        String originalHash = account(1).getPasswordHash();
        String admin = tokens.issueAccessToken(account(2));
        var hashing = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        when(hashing.encode(anyString())).thenAnswer(call -> {
            String encoded = encoder.encode(call.<String>getArgument(0));
            mutations.logout(admin); // Happens after real BCrypt and before mutex admission.
            return encoded;
        });
        MockMvc protectedMvc = mvcWithPasswordEncoder(hashing);
        protectedMvc.perform(post("/auth/users").header("Authorization", "Bearer "+admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"must-not-create\",\"password\":\"correct-password\",\"displayName\":\"new\",\"roleCode\":\"BROKER\",\"tenantCode\":\"test\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username='must-not-create'", Integer.class));
        // Use a separate valid issued credential, as another device could; hook revokes that exact reset token.
        var properties = new AuthTokenProperties(); properties.setSecret("account-mutation-test-only-secret");
        properties.setAccessTtlSeconds(4000);
        var otherIssuer = new AuthTokenService(properties, store); otherIssuer.initialize();
        String resetToken = otherIssuer.issueAccessToken(account(2));
        doAnswer(call -> {
            String encoded = encoder.encode(call.<String>getArgument(0)); mutations.logout(resetToken); return encoded;
        }).when(hashing).encode(anyString());
        protectedMvc.perform(put("/auth/users/1/password").header("Authorization", "Bearer "+resetToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"new-password\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(originalHash, account(1).getPasswordHash());
        assertEquals(1, repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_audit", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox WHERE reason<>'LOGOUT'", Integer.class));
    }

    @Test
    void expiryDuringRealPasswordHashingPreventsCreateAndResetWrites() throws Exception {
        Instant base = Instant.now();
        var time = new java.util.concurrent.atomic.AtomicReference<>(base);
        String admin = tokens.issueAccessToken(account(2));
        String originalHash = account(1).getPasswordHash();
        long expiresAt = tokens.parse(admin).expiresAt();
        var hashing = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        when(hashing.encode(anyString())).thenAnswer(call -> {
            String encoded = encoder.encode(call.<String>getArgument(0));
            time.set(Instant.ofEpochSecond(expiresAt)); return encoded;
        });
        MockMvc protectedMvc = mvcWithPasswordEncoder(hashing);
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenAnswer(ignored -> time.get());
            protectedMvc.perform(post("/auth/users").header("Authorization", "Bearer "+admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"expired-create\",\"password\":\"correct-password\",\"displayName\":\"new\",\"roleCode\":\"BROKER\",\"tenantCode\":\"test\"}"))
                    .andExpect(status().isUnauthorized());
            time.set(base);
            protectedMvc.perform(put("/auth/users/1/password").header("Authorization", "Bearer "+admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"new-password\"}"))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username='expired-create'", Integer.class));
        assertEquals(originalHash, account(1).getPasswordHash());
        assertEquals(1, repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_audit", Integer.class));
    }

    @Test
    void allAccountWritesRejectActorOnlyMismatchedRefreshRevokedAndMissingCurrentPermission() {
        var create = new CreateUserRequest("rejected", "correct-password", "test", "BROKER", "test");
        var update = new UpdateUserRequest("must-not-update", "MANAGER", "test", "ACTIVE");
        assertThrows(UserManagementException.class, () -> mutations.create(2, create));
        assertThrows(UserManagementException.class, () -> mutations.update(2, 1, update));
        assertThrows(UserManagementException.class, () -> mutations.resetPassword(2, 1, "new-password"));
        assertThrows(UserManagementException.class, () -> mutations.delete(2, 1));
        assertThrows(UserManagementException.class, () -> management.createUser(2, create));
        assertThrows(UserManagementException.class, () -> management.updateUser(2, 1, update));
        assertThrows(UserManagementException.class, () -> management.resetPassword(2, 1, "new-password"));
        assertThrows(UserManagementException.class, () -> management.deleteUser(2, 1));
        String access = tokens.issueAccessToken(account(2));
        String refresh = tokens.issueRefreshToken(account(2));
        for (String invalid : java.util.List.of(tokens.issueAccessToken(account(1)), refresh)) {
            assertThrows(UserManagementException.class, () -> management.createUser(invalid, 2, create));
            assertThrows(UserManagementException.class, () -> management.updateUser(invalid, 2, 1, update));
            assertThrows(UserManagementException.class, () -> management.resetPassword(invalid, 2, 1, "new-password"));
            assertThrows(UserManagementException.class, () -> management.deleteUser(invalid, 2, 1));
        }
        jdbc.update("UPDATE auth_permission SET active=0 WHERE permission_code='auth.users.manage'");
        assertThrows(UserManagementException.class, () -> management.updateUser(access, 2, 1, update));
        jdbc.update("UPDATE auth_permission SET active=1 WHERE permission_code='auth.users.manage'");
        tokens.revoke(access);
        assertThrows(UserManagementException.class, () -> management.createUser(access, 2, create));
        assertThrows(UserManagementException.class, () -> management.updateUser(access, 2, 1, update));
        assertThrows(UserManagementException.class, () -> management.resetPassword(access, 2, 1, "new-password"));
        assertThrows(UserManagementException.class, () -> management.deleteUser(access, 2, 1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
        assertEquals(1, repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals("Broker Demo", account(1).getDisplayName());
    }

    private MockMvc mvcWithPasswordEncoder(org.springframework.security.crypto.password.PasswordEncoder passwords) {
        var guarded = new AuthAccountMutationService(repository, passwords, tokens);
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("guarded", guarded);
        var service = new UserManagementService(mock(UserAccountMapper.class), mock(AuthRoleMapper.class), passwords);
        service.configureAuthorization(beans.getBeanProvider(AuthAccountMutationService.class), true);
        return MockMvcBuilders.standaloneSetup(new AuthController(accounts, encoder, tokens, service))
                .setControllerAdvice(new AuthExceptionHandler()).build();
    }

    @Test
    void legacyModeStaysOnBrokerAdminWhitelistWithoutRequiringNewSchema() {
        var roleMapper = mock(AuthRoleMapper.class);
        when(roleMapper.selectCount(any())).thenReturn(1L);
        var legacy = new UserManagementService(mock(UserAccountMapper.class), roleMapper, encoder);
        var properties = new AuthTokenProperties(); properties.setSecret("legacy-smoke-test-secret");
        var legacyTokens = new AuthTokenService(properties, new InMemoryTokenRevocationStore()); legacyTokens.initialize();
        var legacyController = new AuthController(accounts, encoder, legacyTokens, legacy);
        assertTrue(legacyController.login(new AuthLoginRequest("broker01", "correct-password", null)).success());
        jdbc.update("UPDATE user_account SET role_code='MANAGER' WHERE id=1");
        assertFalse(legacyController.login(new AuthLoginRequest("broker01", "correct-password", null)).success());
        jdbc.update("UPDATE user_account SET role_code='BROKER' WHERE id=1");
        String token = legacyTokens.issueAccessToken(account(1));
        assertTrue(legacyController.refresh(new AuthRefreshRequest(legacyTokens.issueRefreshToken(account(1)))).success());
        legacyController.logout("Bearer " + token);
        assertNull(legacyTokens.parse(token));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
        assertFalse(legacy.isVersionedAuthorization());
        assertTrue(UserManagementService.isSupportedRole("BROKER"));
        assertFalse(UserManagementService.isSupportedRole("MANAGER"));
        var beans = new DefaultListableBeanFactory();
        legacy.configureAuthorization(beans.getBeanProvider(AuthAccountMutationService.class), false);
        assertThrows(IllegalStateException.class, () -> legacy.configureAuthorization(beans.getBeanProvider(AuthAccountMutationService.class), true));
    }

    private UserAccountEntity account(long id) {
        return jdbc.query("SELECT * FROM user_account WHERE id=? AND deleted=0", (rs, row) -> {
            var u = new UserAccountEntity(); u.setId(rs.getLong("id")); u.setUsername(rs.getString("username"));
            u.setPasswordHash(rs.getString("password_hash")); u.setDisplayName(rs.getString("display_name"));
            u.setRoleCode(rs.getString("role_code")); u.setTenantCode(rs.getString("tenant_code"));
            u.setAccountStatus(rs.getString("account_status")); u.setDeleted(rs.getInt("deleted")); return u;
        }, id).stream().findFirst().orElse(null);
    }

    static class FailingStore implements TokenRevocationStore {
        final InMemoryTokenRevocationStore delegate = new InMemoryTokenRevocationStore();
        boolean fail;
        public void revoke(String digest, Duration ttl) { if (fail) throw new IllegalStateException("test revocation unavailable"); delegate.revoke(digest, ttl); }
        public boolean isRevoked(String digest) { return delegate.isRevoked(digest); }
    }
}
