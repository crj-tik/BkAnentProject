package com.bkanent.auth.authorization;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.InMemoryTokenRevocationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrganizationManagementControllerTest {
    private AuthorizationRepositoryTest fixture;
    private MockMvc mvc;
    private String admin;
    private String ordinary;

    @BeforeEach
    void setup() throws Exception {
        fixture = new AuthorizationRepositoryTest();
        fixture.setup();
        var properties = new AuthTokenProperties();
        properties.setSecret("organization-controller-test-secret");
        var tokens = new AuthTokenService(properties, new InMemoryTokenRevocationStore());
        tokens.initialize();
        var account = new UserAccountEntity();
        account.setId(3L);
        admin = tokens.issueAccessToken(account);
        account.setId(1L);
        ordinary = tokens.issueAccessToken(account);
        mvc = MockMvcBuilders.standaloneSetup(new OrganizationManagementController(
                new OrganizationManagementService(tokens, fixture.repository))).build();
    }

    @Test
    void authenticatedAdministratorCreatesCompanyAndTwoChildrenWithAudit() throws Exception {
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"code\":\"COMPANY\",\"name\":\"公司\",\"type\":\"COMPANY\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"code\":\"STORE\",\"name\":\"门店\",\"type\":\"STORE\",\"parentId\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentId").value(1));
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"code\":\"OPS\",\"name\":\"运营\",\"type\":\"DEPARTMENT\",\"parentId\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentId").value(1));
        mvc.perform(get("/auth/authorization/organizations").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        assertEquals(3, fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_authorization_audit WHERE action='ORG_CREATED'", Integer.class));
    }

    @Test
    void rejectsUnauthorizedDuplicateAndInvalidHierarchy() throws Exception {
        String company = "{\"code\":\"COMPANY\",\"name\":\"公司\",\"type\":\"COMPANY\"}";
        mvc.perform(post("/auth/authorization/organizations").contentType("application/json").content(company))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + ordinary)
                .contentType("application/json").content(company)).andExpect(status().isForbidden());
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content(company)).andExpect(status().isOk());
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content(company)).andExpect(status().isConflict());
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"code\":\"STORE\",\"name\":\"门店\",\"type\":\"STORE\",\"parentId\":99}"))
                .andExpect(status().isBadRequest());
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_org_unit", Integer.class));
    }

    @Test
    void managementPermissionWithoutAdministratorIdentityCannotChangeOrganization() throws Exception {
        fixture.jdbc.update("INSERT INTO auth_user_role(user_id,role_id,active) VALUES (1,20,1)");
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + ordinary)
                        .contentType("application/json")
                        .content("{\"code\":\"NO_ADMIN\",\"name\":\"拒绝\",\"type\":\"COMPANY\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_org_unit", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_audit", Integer.class));
    }

    @Test
    void memberAssignmentVersionsAndAuditsWithoutInventingStoreScope() throws Exception {
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"code\":\"COMPANY\",\"name\":\"公司\",\"type\":\"COMPANY\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"code\":\"STORE\",\"name\":\"门店\",\"type\":\"STORE\",\"parentId\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/authorization/organizations/2/members").header("Authorization", "Bearer " + ordinary)
                .contentType("application/json").content("{\"userId\":1}")).andExpect(status().isForbidden());
        mvc.perform(post("/auth/authorization/organizations/2/members").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"userId\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissionRevision").value(2));
        mvc.perform(get("/auth/authorization/organizations/2/members").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].userId").value(1));
        mvc.perform(post("/auth/authorization/organizations/2/members").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"userId\":1}")).andExpect(status().isConflict());
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_user_store_identity WHERE user_id=1", Integer.class));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox WHERE reason='ORG_MEMBERSHIP_ADDED'", Integer.class));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_audit WHERE action='ORG_MEMBER_ADDED'", Integer.class));
        mvc.perform(delete("/auth/authorization/organizations/2/members/1").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissionRevision").value(3));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_org_membership WHERE status='ENDED'", Integer.class));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox WHERE reason='ORG_MEMBERSHIP_ENDED'", Integer.class));
    }

    @Test
    void legacyStoreScopeRequiresEvidenceBeforeAddingMembership() throws Exception {
        fixture.jdbc.update("INSERT INTO auth_org_unit(org_code,org_name,org_type,parent_id) VALUES ('C','公司','COMPANY',NULL)");
        fixture.jdbc.update("INSERT INTO auth_org_unit(org_code,org_name,org_type,parent_id) VALUES ('D','运营','DEPARTMENT',1)");
        fixture.jdbc.update("INSERT INTO auth_user_store_identity(user_id,store_id) VALUES (1,123)");
        mvc.perform(post("/auth/authorization/organizations/2/members").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"userId\":1}")).andExpect(status().isConflict());
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_org_membership", Integer.class));
    }

    @Test
    void cannotRemoveStoreMappingWhileLegacyIdentityStillUsesIt() throws Exception {
        fixture.jdbc.update("INSERT INTO auth_org_unit(org_code,org_name,org_type,parent_id) VALUES ('C','公司','COMPANY',NULL)");
        fixture.jdbc.update("INSERT INTO auth_org_unit(org_code,org_name,org_type,parent_id,store_business_id) "
                + "VALUES ('S','门店','STORE',1,456)");
        fixture.jdbc.update("INSERT INTO auth_user_store_identity(user_id,store_id) VALUES (1,456)");

        mvc.perform(put("/auth/authorization/organizations/2/store-business-id")
                        .header("Authorization", "Bearer " + admin).contentType("application/json")
                        .content("{\"storeBusinessId\":null}"))
                .andExpect(status().isConflict());

        assertEquals(456L, fixture.jdbc.queryForObject(
                "SELECT store_business_id FROM auth_org_unit WHERE id=2", Long.class));
        assertEquals(1, fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_user_store_identity WHERE user_id=1 AND store_id=456", Integer.class));
    }

    @Test
    void scheduledStoreMembershipAddsTrustedScopeOnlyAtItsBoundary() throws Exception {
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"code\":\"COMPANY\",\"name\":\"公司\",\"type\":\"COMPANY\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/authorization/organizations").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"code\":\"STORE\",\"name\":\"门店\",\"type\":\"STORE\",\"parentId\":1}"))
                .andExpect(status().isOk());
        mvc.perform(put("/auth/authorization/organizations/2/store-business-id")
                .header("Authorization", "Bearer " + admin).contentType("application/json")
                .content("{\"storeBusinessId\":456}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.storeBusinessId").value(456));
        fixture.jdbc.update("INSERT INTO auth_user_store_identity(user_id,store_id) VALUES (1,456)");

        java.time.Instant start = java.time.Instant.now().plusSeconds(300);
        java.time.Instant end = start.plusSeconds(3600);
        mvc.perform(post("/auth/authorization/organizations/2/members").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"userId\":1,\"primary\":true,\"validFrom\":\""
                        + start + "\",\"validUntil\":\"" + end + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissionRevision").value(2));
        assertEquals(java.util.Map.of("STORE", 456L),
                fixture.repository.snapshot(1, java.time.Instant.now()).orElseThrow().businessIdentities());
        mvc.perform(get("/auth/authorization/organizations/2/members").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].effective").value(false));

        fixture.jdbc.update("UPDATE auth_org_membership SET valid_from=? WHERE user_id=1 AND org_id=2",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
        var effective = fixture.repository.snapshot(1, java.time.Instant.now()).orElseThrow();
        assertEquals(java.util.Map.of("STORE", 456L), effective.businessIdentities());
        assertEquals(0, fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_user_store_identity WHERE user_id=1 AND store_id=456", Integer.class));
        assertEquals(3, effective.permissionRevision());
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox "
                + "WHERE reason='ORG_MEMBERSHIP_BOUNDARY'", Integer.class));

        mvc.perform(delete("/auth/authorization/organizations/2/members/1").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        assertTrue(fixture.repository.snapshot(1, java.time.Instant.now()).orElseThrow().businessIdentities().isEmpty());
    }

    @Test
    void endingFutureMembershipPreservesLegacyStoreScopeUntilItWasEffective() throws Exception {
        fixture.jdbc.update("INSERT INTO auth_org_unit(org_code,org_name,org_type,parent_id) VALUES ('C','公司','COMPANY',NULL)");
        fixture.jdbc.update("INSERT INTO auth_org_unit(org_code,org_name,org_type,parent_id) VALUES ('S','门店','STORE',1)");
        fixture.jdbc.update("UPDATE auth_org_unit SET store_business_id=456 WHERE id=2");
        fixture.jdbc.update("INSERT INTO auth_user_store_identity(user_id,store_id) VALUES (1,456)");
        java.time.Instant start = java.time.Instant.now().plusSeconds(300);
        mvc.perform(post("/auth/authorization/organizations/2/members").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"userId\":1,\"primary\":true,\"validFrom\":\""
                        + start + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/auth/authorization/organizations/2/members/1").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        assertEquals(1, fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_user_store_identity WHERE user_id=1 AND store_id=456", Integer.class));
        assertEquals(java.util.Map.of("STORE", 456L),
                fixture.repository.snapshot(1, java.time.Instant.now()).orElseThrow().businessIdentities());
    }
}
