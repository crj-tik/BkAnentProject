package com.bkanent.auth.authorization;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth/authorization/organizations")
@ConditionalOnProperty(prefix = "auth.authorization", name = "enabled", havingValue = "true")
public class OrganizationManagementController {
    private final OrganizationManagementService service;

    public OrganizationManagementController(OrganizationManagementService service) {
        this.service = service;
    }

    @GetMapping
    public List<OrganizationManagementService.OrgUnit> list(
            @RequestHeader(value = "Authorization", required = false) String header) {
        return service.list(bearer(header));
    }

    @PostMapping
    public OrganizationManagementService.OrgUnit create(
            @RequestHeader(value = "Authorization", required = false) String header,
            @RequestBody OrganizationManagementService.CreateOrgUnit body) {
        return service.create(bearer(header), body);
    }

    @PutMapping("/{orgId}/status")
    public OrganizationManagementService.OrgUnit setStatus(
            @RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable(name = "orgId") long orgId, @RequestBody OrganizationStatus body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status body required");
        return service.setStatus(bearer(header), orgId, body.status());
    }

    @PutMapping("/{orgId}/store-business-id")
    public OrganizationManagementService.StoreMappingChange mapStoreBusinessId(
            @RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable(name = "orgId") long orgId, @RequestBody StoreBusinessId body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store mapping body required");
        return service.setStoreBusinessId(bearer(header), orgId, body.storeBusinessId());
    }

    @GetMapping("/{orgId}/members")
    public List<OrganizationManagementService.Member> members(
            @RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable(name = "orgId") long orgId) {
        return service.members(bearer(header), orgId);
    }

    @PostMapping("/{orgId}/members")
    public OrganizationManagementService.MemberChange addMember(
            @RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable(name = "orgId") long orgId, @RequestBody MemberRequest body) {
        if (body == null || body.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User ID required");
        }
        return service.addMembership(bearer(header), orgId, new OrganizationManagementService.MembershipRequest(
                body.userId(), body.primary(), body.validFrom(), body.validUntil()));
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    public OrganizationManagementService.MemberChange endMember(
            @RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable(name = "orgId") long orgId, @PathVariable(name = "userId") long userId) {
        return service.endPrimaryMember(bearer(header), orgId, userId);
    }

    @PutMapping("/{orgId}/members/{userId}/organization")
    public OrganizationManagementService.MemberChange moveMember(
            @RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable(name = "orgId") long orgId, @PathVariable(name = "userId") long userId,
            @RequestBody MoveMemberRequest body) {
        if (body == null || body.targetOrgId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target organization required");
        }
        return service.movePrimaryMember(bearer(header), orgId, userId, body.targetOrgId());
    }

    private static String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7
                || header.substring(7).contains(" ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer access token required");
        }
        return header.substring(7);
    }

    public record MemberRequest(Long userId, Boolean primary, java.time.Instant validFrom,
                                java.time.Instant validUntil) {
        public MemberRequest(Long userId) { this(userId, true, null, null); }
    }
    public record OrganizationStatus(String status) { }
    public record StoreBusinessId(Long storeBusinessId) { }
    public record MoveMemberRequest(Long targetOrgId) { }
}
