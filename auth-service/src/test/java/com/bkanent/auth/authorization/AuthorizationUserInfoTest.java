package com.bkanent.auth.authorization;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.bkanent.auth.dto.AuthUserManagementModels;
import com.bkanent.auth.mapper.AuthRoleMapper;
import com.bkanent.auth.mapper.UserAccountMapper;
import com.bkanent.auth.service.UserManagementService;
import com.bkanent.common.model.AuthLoginRequest;
import com.bkanent.common.model.AuthRefreshRequest;
import com.bkanent.common.model.AuthTokenDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AuthorizationUserInfoTest {
    private AuthAccountMutationServiceTest fixture;
    private UserManagementService management;
    private UserAccountMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        fixture = new AuthAccountMutationServiceTest();
        fixture.setup();
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(fixture.jdbc.getDataSource());
        bean.setConfiguration(new MybatisConfiguration());
        var factory = bean.getObject();
        factory.getConfiguration().addMapper(UserAccountMapper.class);
        mapper = new org.mybatis.spring.SqlSessionTemplate(factory).getMapper(UserAccountMapper.class);
        management = new UserManagementService(mapper, mock(AuthRoleMapper.class), fixture.encoder);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("mutations", fixture.mutations);
        management.configureAuthorization(beans.getBeanProvider(AuthAccountMutationService.class), true);
    }

    @Test
    void noRoleAdministratorLoginRefreshAndManagementRowsExposeTrustedFacts() {
        fixture.jdbc.update("DELETE FROM auth_user_role WHERE user_id=2");
        fixture.jdbc.update("UPDATE user_account SET role_code='UNRELATED' WHERE id=2");
        var login = fixture.controller.login(new AuthLoginRequest("admin01", "correct-password", null));
        assertThat(login.success()).isTrue();
        assertThat(login.data().businessAdmin()).isTrue();
        assertThat(login.data().roles()).isEmpty();
        assertThat(login.data().permissionRevision()).isPositive();
        assertThat(login.data().credentialEpoch()).isPositive();
        assertThat(login.data().roleCode()).isEqualTo("UNRELATED");
        var refreshed = fixture.controller.refresh(new AuthRefreshRequest(login.data().refreshToken()));
        assertThat(refreshed.data().businessAdmin()).isTrue();
        assertThat(refreshed.data().roles()).isEmpty();
        var row = management.listUsers(1, 20, "admin01", null, "ACTIVE").records().get(0);
        assertThat(row.businessAdmin()).isTrue();
        assertThat(row.roles()).isEmpty();
        assertThat(row.permissionRevision()).isEqualTo(login.data().permissionRevision());
    }

    @Test
    void secondaryRoleFiltersBeforePaginationAndUsesTheSameCountPredicate() {
        fixture.jdbc.update("INSERT INTO auth_user_role(user_id,role_id,active) SELECT 1,id,1 FROM auth_role WHERE role_code='MANAGER'");
        fixture.jdbc.update("INSERT INTO auth_user_role(user_id,role_id,active) SELECT 2,id,1 FROM auth_role WHERE role_code='MANAGER'");
        var first = management.listUsers(1, 1, null, "MANAGER", "ACTIVE");
        var second = management.listUsers(2, 1, null, "MANAGER", "ACTIVE");
        assertThat(first.total()).isEqualTo(2);
        assertThat(second.total()).isEqualTo(2);
        assertThat(first.records()).hasSize(1);
        assertThat(second.records()).hasSize(1);
        assertThat(first.records().get(0).userId()).isNotEqualTo(second.records().get(0).userId());
        assertThat(management.listUsers(1, 20, "broker", "MANAGER", "ACTIVE").records())
                .singleElement().satisfies(row -> {
                    assertThat(row.businessAdmin()).isFalse();
                    assertThat(row.roles()).containsExactly("BROKER", "MANAGER");
                });
        assertThat(fixture.controller.login(new AuthLoginRequest("broker01", "correct-password", null)).data().roles())
                .containsExactly("BROKER", "MANAGER");
    }

    @Test
    void expiredFutureInactiveAndDeletedRolesDoNotMatchOrAppearInUserInfo() {
        fixture.jdbc.update("INSERT INTO auth_user_role(user_id,role_id,valid_until,active) SELECT 1,id,?,1 FROM auth_role WHERE role_code='MANAGER'",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
        assertThat(management.listUsers(1, 20, null, "MANAGER", null).total()).isZero();
        fixture.jdbc.update("UPDATE auth_user_role SET valid_until=NULL,valid_from=? WHERE user_id=1 AND role_id=(SELECT id FROM auth_role WHERE role_code='MANAGER')",
                java.sql.Timestamp.from(Instant.now().plusSeconds(60)));
        assertThat(management.listUsers(1, 20, null, "MANAGER", null).records()).isEmpty();
        fixture.jdbc.update("UPDATE auth_user_role SET valid_from=NULL WHERE user_id=1");
        fixture.jdbc.update("UPDATE auth_role SET active=0 WHERE role_code='MANAGER'");
        assertThat(management.listUsers(1, 20, null, "MANAGER", null).records()).isEmpty();
        fixture.jdbc.update("UPDATE auth_role SET active=1,deleted=1 WHERE role_code='MANAGER'");
        assertThat(management.listUsers(1, 20, null, "MANAGER", null).total()).isZero();
        assertThat(fixture.controller.login(new AuthLoginRequest("broker01", "correct-password", null)).data().roles())
                .containsExactly("BROKER");
        fixture.jdbc.update("UPDATE user_account SET deleted=1 WHERE id=1");
        assertThat(mapper.countAuthorizationManagementUsers(null, null, null, LocalDateTime.now())).isEqualTo(1);
    }

    @Test
    void disabledUsersRemainManageableWithoutBeingReportedAsAuthorizedAdministrators() {
        fixture.jdbc.update("UPDATE user_account SET account_status='DISABLED' WHERE id=1");
        var page = management.listUsers(1, 20, null, null, "DISABLED");
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records()).singleElement().satisfies(row -> {
            assertThat(row.accountStatus()).isEqualTo("DISABLED");
            assertThat(row.businessAdmin()).isFalse();
            assertThat(row.permissionRevision()).isPositive();
        });
        assertThat(fixture.controller.login(new AuthLoginRequest("broker01", "correct-password", null)).success()).isFalse();
    }

    @Test
    void legacyConstructorsAndJsonKeepExistingFieldsWithoutInferringAuthority() throws Exception {
        var token = new AuthTokenDTO("synthetic-access", "synthetic-refresh", 7L, "ADMIN", "name", "admin", "tenant");
        var user = new AuthUserManagementModels.UserItem(7L, "user", "name", "ADMIN", "admin", "tenant", "ACTIVE", null);
        var current = new AuthUserManagementModels.CurrentUser(7L, "user", "name", "ADMIN", "admin", "tenant");
        assertThat(token.businessAdmin()).isFalse();
        assertThat(user.businessAdmin()).isFalse();
        assertThat(current.businessAdmin()).isFalse();
        assertThat(token.roles()).isEmpty();
        assertThat(token.permissionRevision()).isZero();
        var json = new ObjectMapper();
        assertThat(json.readValue(json.writeValueAsString(token), AuthTokenDTO.class)).isEqualTo(token);
        assertThat(json.valueToTree(token).path("roleCode").asText()).isEqualTo("ADMIN");
        assertThat(token.toString()).doesNotContain("synthetic-access", "synthetic-refresh");
        var roles = new java.util.ArrayList<>(List.of("MANAGER", "BROKER", "BROKER"));
        var immutable = new AuthTokenDTO("access", "refresh", 7L, "old", "name", "old", "tenant", false, roles, 1, 1);
        roles.clear();
        assertThat(immutable.roles()).containsExactly("BROKER", "MANAGER");
        assertThrows(UnsupportedOperationException.class, () -> immutable.roles().clear());
        String payload = new String(java.util.Base64.getUrlDecoder().decode(
                fixture.tokens.issueAccessToken(fixture.accounts.getById(1L)).split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload.split("\\|", -1)).hasSize(3);
    }
}
