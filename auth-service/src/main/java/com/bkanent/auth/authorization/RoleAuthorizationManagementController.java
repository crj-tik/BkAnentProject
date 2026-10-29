package com.bkanent.auth.authorization;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/auth/authorization")
@ConditionalOnProperty(prefix = "auth.authorization", name = "enabled", havingValue = "true")
public class RoleAuthorizationManagementController {
    private final RoleAuthorizationManagementService service;

    public RoleAuthorizationManagementController(RoleAuthorizationManagementService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    public List<AuthorizationFacts.Permission> catalog(@RequestHeader(value="Authorization",required=false) String header) {
        return service.catalog(bearer(header));
    }

    @GetMapping("/roles")
    public List<RoleAuthorizationManagementService.Role> roles(@RequestHeader(value="Authorization",required=false) String header) {
        return service.roles(bearer(header));
    }

    @PostMapping("/roles")
    public RoleAuthorizationManagementService.ManagementResult createRole(@RequestHeader(value="Authorization",required=false) String header,
                                                                          @RequestBody RoleAuthorizationManagementService.RoleDefinition body) {
        return service.createRole(bearer(header),body);
    }

    @PutMapping("/roles/{roleId}")
    public RoleAuthorizationManagementService.ManagementResult updateRole(@RequestHeader(value="Authorization",required=false) String header,
                                                                          @PathVariable("roleId") long roleId,
                                                                          @RequestBody RoleAuthorizationManagementService.RoleDefinition body) {
        return service.updateRole(bearer(header),roleId,body);
    }

    @DeleteMapping("/roles/{roleId}")
    public RoleAuthorizationManagementService.ManagementResult deleteRole(@RequestHeader(value="Authorization",required=false) String header,
                                                                          @PathVariable("roleId") long roleId) {
        return service.deleteRole(bearer(header),roleId);
    }

    @GetMapping("/users/{userId}/roles")
    public List<RoleAuthorizationManagementService.UserRoleGrant> grants(@RequestHeader(value="Authorization",required=false) String header,
                                                                         @PathVariable("userId") long userId) {
        return service.userRoles(bearer(header),userId);
    }

    @PutMapping("/users/{userId}/roles")
    public RoleAuthorizationManagementService.ManagementResult replaceGrants(@RequestHeader(value="Authorization",required=false) String header,
                                                                              @PathVariable("userId") long userId,@RequestBody UserRoles body) {
        return service.replaceUserRoles(bearer(header),userId,body.grants());
    }

    @PutMapping("/users/{userId}/business-admin")
    public RoleAuthorizationManagementService.ManagementResult administrator(@RequestHeader(value="Authorization",required=false) String header,
                                                                             @PathVariable("userId") long userId,@RequestBody BusinessAdmin body) {
        return service.setBusinessAdmin(bearer(header),userId,body.enabled());
    }

    @GetMapping("/users/{userId}/business-identities")
    public List<RoleAuthorizationManagementService.BusinessIdentity> businessIdentities(
            @RequestHeader(value="Authorization",required=false) String header, @PathVariable("userId") long userId) {
        return service.businessIdentities(bearer(header), userId);
    }

    @PutMapping("/users/{userId}/broker-identity")
    public RoleAuthorizationManagementService.BusinessIdentityChange brokerIdentity(
            @RequestHeader(value="Authorization",required=false) String header,
            @PathVariable("userId") long userId, @RequestBody BrokerIdentity body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identity body required");
        return service.setBrokerIdentity(bearer(header), userId, body.brokerId());
    }

    @PutMapping("/users/{userId}/store-identity")
    public RoleAuthorizationManagementService.StoreIdentityChange storeIdentity(
            @RequestHeader(value="Authorization",required=false) String header,
            @PathVariable("userId") long userId, @RequestBody StoreIdentity body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identity body required");
        return service.setStoreIdentity(bearer(header), userId, body.storeId());
    }

    private static String bearer(String header) {
        if (header==null || !header.startsWith("Bearer ") || header.length()<=7 || header.substring(7).contains(" "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer access token required");
        return header.substring(7);
    }

    public record UserRoles(List<RoleAuthorizationManagementService.UserRoleGrant> grants) { }
    public record BusinessAdmin(boolean enabled) { }
    public record BrokerIdentity(Long brokerId) { }
    public record StoreIdentity(Long storeId) { }
}
