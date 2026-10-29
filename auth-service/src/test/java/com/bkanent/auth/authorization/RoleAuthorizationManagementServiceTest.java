package com.bkanent.auth.authorization;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.InMemoryTokenRevocationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleAuthorizationManagementServiceTest {
    private AuthorizationRepositoryTest fixture;
    private RoleAuthorizationManagementService service;
    private AuthTokenService tokens;
    private String admin;
    private String ordinary;
    private final Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

    @BeforeEach
    void setup() throws Exception {
        fixture = new AuthorizationRepositoryTest(); fixture.setup();
        fixture.jdbc.update("INSERT INTO auth_permission VALUES ('auth.roles.manage','auth','roles.manage',1,4,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        fixture.jdbc.update("INSERT INTO auth_permission VALUES ('auth.business-identity.manage','auth','business-identity.manage',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var properties = new AuthTokenProperties(); properties.setSecret("management-unit-test-secret");
        tokens = new AuthTokenService(properties,new InMemoryTokenRevocationStore()); tokens.initialize();
        service = new RoleAuthorizationManagementService(tokens,fixture.repository,Clock.fixed(now,ZoneOffset.UTC));
        admin = tokens.issueAccessToken(account(3)); ordinary = tokens.issueAccessToken(account(1));
    }

    @Test
    void signedTokenCurrentAccountAndExplicitPermissionAreRequired() {
        status(HttpStatus.UNAUTHORIZED, () -> service.roles("mock-3"));
        status(HttpStatus.UNAUTHORIZED, () -> service.roles(tokens.issueRefreshToken(account(3))));
        status(HttpStatus.FORBIDDEN, () -> service.createRole(ordinary,definition("USER",List.of("listing.read"))));
        assertEquals(4,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_role",Integer.class));
        tokens.revoke(admin);
        status(HttpStatus.UNAUTHORIZED, () -> service.roles(admin));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_audit",Integer.class));
    }

    @Test
    void inactiveDeletedOrMissingStateAdminCannotManage() {
        fixture.jdbc.update("UPDATE user_account SET account_status='DISABLED' WHERE id=3");
        status(HttpStatus.UNAUTHORIZED, () -> service.catalog(admin));
        fixture.jdbc.update("UPDATE user_account SET account_status='ACTIVE',deleted=1 WHERE id=3");
        status(HttpStatus.UNAUTHORIZED, () -> service.roles(admin));
        fixture.jdbc.update("UPDATE user_account SET deleted=0 WHERE id=3");
        fixture.jdbc.update("DELETE FROM auth_user_authorization_state WHERE user_id=3");
        status(HttpStatus.UNAUTHORIZED, () -> service.roles(admin));
    }

    @Test
    void crudRoleUsesExactCatalogAndWritesAuditOutbox() {
        var created = service.createRole(admin,definition("NEW_ROLE",List.of("listing.read")));
        assertNotNull(created.eventId()); assertFalse(created.publicationConfirmed());
        long id = created.targetId();
        fixture.jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES (1,?)",id);
        var updated = service.updateRole(admin,id,definition("NEW_ROLE",List.of("auth.users.manage")));
        assertEquals(1,updated.versions().size());
        assertTrue(fixture.repository.snapshot(1,now).orElseThrow().hasPermission("auth.users.manage",now));
        service.deleteRole(admin,id);
        assertTrue(fixture.repository.snapshot(1,now).orElseThrow().permissions().isEmpty());
        assertEquals(3,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_audit",Integer.class));
        assertEquals(3,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox",Integer.class));
        status(HttpStatus.NOT_FOUND, () -> service.updateRole(admin,id,definition("NEW_ROLE",List.of())));
    }

    @Test
    void unknownWildcardDisabledDuplicateAndImmutableRoleCodeRejectedBeforeWrites() {
        for (String code : List.of("invented.manage","agent.workflow.invoke.evil","listing.read ","disabled.read","LISTING.READ","*.read"))
            status(HttpStatus.BAD_REQUEST, () -> service.createRole(admin,definition("NEW",List.of(code))));
        status(HttpStatus.BAD_REQUEST, () -> service.createRole(admin,definition("NEW",List.of("listing.read","listing.read"))));
        status(HttpStatus.BAD_REQUEST, () -> service.updateRole(admin,10,definition("CHANGED",List.of("listing.read"))));
        status(HttpStatus.NOT_FOUND, () -> service.deleteRole(admin,999));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox",Integer.class));
    }

    @Test
    void fullUserGrantReplaceValidatesAllBeforeMutationAndIndependentAdminFlag() {
        var result = service.replaceUserRoles(admin,1,List.of(new RoleAuthorizationManagementService.UserRoleGrant(20,now,now.plusSeconds(30))));
        assertEquals(2,result.versions().get(0).permissionRevision());
        assertEquals(1,service.userRoles(admin,1).size());
        assertTrue(fixture.repository.snapshot(1,now).orElseThrow().hasPermission("auth.users.manage",now));
        assertFalse(fixture.repository.snapshot(1,now).orElseThrow().businessAdmin());
        status(HttpStatus.BAD_REQUEST, () -> service.replaceUserRoles(admin,1,List.of(
                new RoleAuthorizationManagementService.UserRoleGrant(20,null,null),new RoleAuthorizationManagementService.UserRoleGrant(30,null,null))));
        status(HttpStatus.BAD_REQUEST, () -> service.replaceUserRoles(admin,1,List.of(new RoleAuthorizationManagementService.UserRoleGrant(20,now.plusSeconds(5),now))));
        status(HttpStatus.BAD_REQUEST, () -> service.replaceUserRoles(admin,1,List.of(new RoleAuthorizationManagementService.UserRoleGrant(20,null,now))));
        status(HttpStatus.NOT_FOUND, () -> service.replaceUserRoles(admin,1,List.of(new RoleAuthorizationManagementService.UserRoleGrant(999,null,null))));
        assertEquals(1,service.userRoles(admin,1).size());
        assertEquals(2,fixture.repository.currentVersion(1).orElseThrow().permissionRevision());
        service.replaceUserRoles(admin,1,List.of());
        assertTrue(fixture.repository.snapshot(1,now).orElseThrow().permissions().isEmpty());
    }

    @Test
    void selfAdminCannotBeGrantedByOrdinaryManagerOrRevokedByAdminAndLastAdminRemains() {
        service.replaceUserRoles(admin,1,List.of(new RoleAuthorizationManagementService.UserRoleGrant(20,null,null)));
        status(HttpStatus.FORBIDDEN, () -> service.setBusinessAdmin(ordinary,1,true));
        status(HttpStatus.FORBIDDEN, () -> service.setBusinessAdmin(admin,3,false));
        service.setBusinessAdmin(admin,2,true);
        service.setBusinessAdmin(admin,2,false);
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE business_admin=1",Integer.class));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT business_admin FROM user_account WHERE id=3",Integer.class));
    }

    @Test
    void brokerIdentityIsExplicitUniqueAuditedAndVersionsAffectedUser() {
        var first = service.setBrokerIdentity(admin, 1, 8101L);
        assertEquals(2, first.versions().get(0).permissionRevision());
        assertEquals(java.util.Map.of("BROKER", 8101L), fixture.repository.snapshot(1, now).orElseThrow().businessIdentities());
        assertEquals(List.of(new RoleAuthorizationManagementService.BusinessIdentity("BROKER", 8101L)),
                service.businessIdentities(admin, 1));
        assertEquals(first.eventId(), fixture.jdbc.queryForObject(
                "SELECT event_id FROM auth_authorization_audit WHERE action='BROKER_IDENTITY_ASSIGNED'", String.class));
        status(HttpStatus.CONFLICT, () -> service.setBrokerIdentity(admin, 2, 8101L));
        assertEquals(1, fixture.repository.currentVersion(2).orElseThrow().permissionRevision());
        assertEquals(0, service.setBrokerIdentity(admin, 1, 8101L).versions().size());
        var changed = service.setBrokerIdentity(admin, 1, 8102L);
        assertEquals(3, changed.versions().get(0).permissionRevision());
        assertEquals(2, fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_authorization_audit WHERE event_id=?", Integer.class, changed.eventId()));
        status(HttpStatus.FORBIDDEN, () -> service.setBrokerIdentity(ordinary, 2, 8103L));
    }

    @Test
    void storeIdentitySupportsSharedStoreAndRevocationWithVersionedAudit() {
        var first = service.setStoreIdentity(admin, 1, 23L);
        var second = service.setStoreIdentity(admin, 2, 23L);
        assertEquals(2, first.versions().get(0).permissionRevision());
        assertEquals(2, second.versions().get(0).permissionRevision());
        assertEquals(java.util.Map.of("STORE", 23L), fixture.repository.snapshot(1, now).orElseThrow().businessIdentities());
        assertEquals(java.util.Map.of("STORE", 23L), fixture.repository.snapshot(2, now).orElseThrow().businessIdentities());
        assertEquals(List.of(new RoleAuthorizationManagementService.BusinessIdentity("STORE", 23L)), service.businessIdentities(admin, 1));
        assertTrue(service.setStoreIdentity(admin, 1, 23L).versions().isEmpty());
        var revoked = service.setStoreIdentity(admin, 1, null);
        assertEquals(3, revoked.versions().get(0).permissionRevision());
        assertTrue(fixture.repository.snapshot(1, now).orElseThrow().businessIdentities().isEmpty());
        assertEquals(java.util.Map.of("STORE", 23L), fixture.repository.snapshot(2, now).orElseThrow().businessIdentities());
        status(HttpStatus.FORBIDDEN, () -> service.setStoreIdentity(ordinary, 2, 24L));
        status(HttpStatus.BAD_REQUEST, () -> service.setStoreIdentity(admin, 2, 0L));
        assertEquals(3, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_audit WHERE target_type='STORE'", Integer.class));
    }

    @Test
    void storeIdentityMutationRollsBackIfAuditFails() {
        fixture.jdbc.execute("DROP TABLE auth_authorization_audit");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.setStoreIdentity(admin, 1, 23L));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_user_store_identity", Integer.class));
        assertEquals(1, fixture.repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox", Integer.class));
    }

    @Test
    void secondTokenParseInsideWriterRejectsRevocationRace() {
        AuthTokenService checked = spy(tokens);
        var principal = tokens.parse(admin);
        doReturn(principal).doReturn(null).when(checked).parse(admin);
        var guarded = new RoleAuthorizationManagementService(checked,fixture.repository,Clock.fixed(now,ZoneOffset.UTC));
        status(HttpStatus.UNAUTHORIZED, () -> guarded.createRole(admin,definition("RACE",List.of())));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox",Integer.class));
    }

    @Test
    void authorizationWritesRollbackIfAuditFails() {
        fixture.jdbc.execute("DROP TABLE auth_authorization_audit");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.createRole(admin,definition("ROLLBACK",List.of("listing.read"))));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_role WHERE role_code='ROLLBACK'",Integer.class));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox",Integer.class));
    }

    private RoleAuthorizationManagementService.RoleDefinition definition(String code,List<String> permissions) {
        return new RoleAuthorizationManagementService.RoleDefinition(code,"test",null,true,true,permissions);
    }
    private UserAccountEntity account(long id) { var account=new UserAccountEntity(); account.setId(id); return account; }
    private void status(HttpStatus status,Runnable call) {
        assertEquals(status,assertThrows(ResponseStatusException.class,call::run).getStatusCode());
    }
}
