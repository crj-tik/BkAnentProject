package com.bkanent.auth.authorization;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EffectivePermissionServiceTest {
    private final Instant now = Instant.parse("2026-10-23T10:00:00Z");
    private final EffectivePermissionService service = new EffectivePermissionService();
    private final List<AuthorizationFacts.Permission> catalog = List.of(
            new AuthorizationFacts.Permission("listing.read", "listing", "read", true, 1),
            new AuthorizationFacts.Permission("auth.users.manage", "auth", "users.manage", true, 2),
            new AuthorizationFacts.Permission("disabled.read", "disabled", "read", false, 3));

    @Test
    void activeMultiRoleUnionIsExactSortedAndDeduplicated() {
        var result = calculate(user(false), List.of(grant(1, true, false, true, null, now.plusSeconds(90),
                Set.of("listing.read", "invented.read")), grant(2, true, false, true, null, null,
                Set.of("listing.read", "auth.users.manage", "disabled.read"))));
        assertEquals(List.of("auth.users.manage", "listing.read"), result.permissions());
        assertEquals(now.plusSeconds(90), result.grantDeadline());
        assertThrows(UnsupportedOperationException.class, () -> result.permissions().add("new.read"));
    }

    @Test
    void inactiveDeletedExpiredAndInvalidWindowGrantsDoNotGrant() {
        assertTrue(calculate(user(false), List.of(
                grant(1, false, false, true, null, null, Set.of("listing.read")),
                grant(2, true, true, true, null, null, Set.of("listing.read")),
                grant(3, true, false, false, null, null, Set.of("listing.read")),
                grant(4, true, false, true, null, now, Set.of("listing.read")),
                grant(5, true, false, true, now.plusSeconds(10), now.plusSeconds(5), Set.of("listing.read"))
        )).permissions().isEmpty());
    }

    @Test
    void administratorNeedsNoRolesAndStillDeniesUnknownOrDisabledCodes() {
        var result = calculate(user(true), List.of());
        assertTrue(result.hasPermission("auth.users.manage", now));
        for (String code : List.of("disabled.read", "invented.manage", "agent.workflow.invoke.evil",
                "agent.mcp.server.evil.read", "listing.read ", "LISTING.READ", "*.read", "auth.users.manage.admin")) {
            assertFalse(result.hasPermission(code, now), code);
        }
        assertFalse(result.hasPermission(null, now));
        assertNull(result.grantDeadline());
    }

    @Test
    void roleNamedAdminDoesNotSetBusinessAdministratorOrSelfElevate() {
        var result = calculate(user(false), List.of(new AuthorizationFacts.RoleGrant(1, "ADMIN", true,
                false, true, null, null, Set.of("listing.read", "invented.manage"))));
        assertFalse(result.businessAdmin());
        assertFalse(result.hasPermission("auth.users.manage", now));
        assertEquals(List.of("listing.read"), result.permissions());
    }

    @Test
    void inactiveDeletedOrUnverifiedStateUsersHaveNoPermissionEvenAsAdmin() {
        for (var user : List.of(new AuthorizationFacts.User(7, false, false, true, 1, 1, "DISABLED"),
                new AuthorizationFacts.User(7, true, true, true, 1, 1, "ACTIVE"),
                new AuthorizationFacts.User(7, true, false, true, 1, 1, "UNKNOWN"))) {
            var result = calculate(user, List.of());
            assertFalse(result.businessAdmin());
            assertTrue(result.permissions().isEmpty());
        }
    }

    @Test
    void deadlineFailsClosedAtBoundaryAndFutureStartIsAChangePoint() {
        var result = calculate(user(false), List.of(grant(1, true, false, true, now, now.plusSeconds(30),
                Set.of("listing.read")), grant(2, true, false, true, now.plusSeconds(10), null,
                Set.of("auth.users.manage"))));
        assertEquals(now.plusSeconds(10), result.grantDeadline());
        assertTrue(result.hasPermission("listing.read", now.plusSeconds(9)));
        assertFalse(result.hasPermission("listing.read", now.plusSeconds(10)));
        assertFalse(result.hasPermission("auth.users.manage", now));
    }

    @Test
    void rebuildSameVersionBeforeChangePointHasStableContent() {
        var facts = new AuthorizationFacts(user(false), catalog,
                List.of(grant(1, true, false, true, null, now.plusSeconds(90), Set.of("listing.read"))));
        assertEquals(service.calculate(facts, now), service.calculate(facts, now.plusSeconds(10)));
        assertEquals(3, service.calculate(facts, now).catalogVersion());
    }

    private EffectivePermissionService.Snapshot calculate(AuthorizationFacts.User user,
                                                         List<AuthorizationFacts.RoleGrant> grants) {
        return service.calculate(new AuthorizationFacts(user, catalog, grants), now);
    }

    private AuthorizationFacts.User user(boolean admin) {
        return new AuthorizationFacts.User(7, true, false, admin, 1, 1, "ACTIVE");
    }

    private AuthorizationFacts.RoleGrant grant(long role, boolean active, boolean deleted, boolean grantActive,
                                               Instant from, Instant until, Set<String> codes) {
        return new AuthorizationFacts.RoleGrant(role, "ROLE" + role, active, deleted, grantActive, from, until, codes);
    }
}
