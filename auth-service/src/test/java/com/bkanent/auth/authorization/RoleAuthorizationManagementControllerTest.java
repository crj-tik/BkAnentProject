package com.bkanent.auth.authorization;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.InMemoryTokenRevocationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RoleAuthorizationManagementControllerTest {
    private MockMvc mvc;
    private String admin;
    private String ordinary;

    @BeforeEach
    void setup() throws Exception {
        var fixture=new AuthorizationRepositoryTest(); fixture.setup();
        fixture.jdbc.update("INSERT INTO auth_permission VALUES ('auth.roles.manage','auth','roles.manage',1,4,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var properties=new AuthTokenProperties(); properties.setSecret("controller-test-secret");
        var tokens=new AuthTokenService(properties,new InMemoryTokenRevocationStore()); tokens.initialize();
        var account=new UserAccountEntity(); account.setId(3L); admin=tokens.issueAccessToken(account);
        account.setId(1L); ordinary=tokens.issueAccessToken(account);
        mvc=MockMvcBuilders.standaloneSetup(new RoleAuthorizationManagementController(
                new RoleAuthorizationManagementService(tokens,fixture.repository))).build();
    }

    @Test
    void missingMalformedAndForgedBearerAreUnauthorized() throws Exception {
        mvc.perform(get("/auth/authorization/roles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/authorization/roles").header("Authorization","Basic "+admin)).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/authorization/roles").header("Authorization","Bearer mock-3")).andExpect(status().isUnauthorized());
    }

    @Test
    void ordinaryWriteIsForbiddenAndAdminRoleCreationWorksWithPublicationPending() throws Exception {
        String body="{\"code\":\"NEW\",\"name\":\"test\",\"active\":true,\"assignable\":true,\"permissionCodes\":[\"listing.read\"]}";
        mvc.perform(post("/auth/authorization/roles").header("Authorization","Bearer "+ordinary)
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/auth/authorization/roles").header("Authorization","Bearer "+admin)
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").isString()).andExpect(jsonPath("$.publicationConfirmed").value(false));
    }

    @Test
    void userRoleReplaceAndIndependentAdminGrantHttpRoutesWork() throws Exception {
        mvc.perform(put("/auth/authorization/users/1/roles").header("Authorization","Bearer "+admin)
                .contentType("application/json").content("{\"grants\":[{\"roleId\":20}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versions[0].userId").value(1));
        mvc.perform(put("/auth/authorization/users/1/business-admin").header("Authorization","Bearer "+ordinary)
                .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/auth/authorization/users/3/business-admin").header("Authorization","Bearer "+admin)
                .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
    }
}
