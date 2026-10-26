package com.bkanent.listing.config;

import com.bkanent.common.authz.AuthzIngressProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties(prefix = "listing.authorization")
public class ListingAuthorizationProperties extends AuthzIngressProperties {
    public ListingAuthorizationProperties() { setAudience("listing-master-service"); }
    @PostConstruct public void validateProperties() { validate("listing-master-service"); }

    public Map<String, PublicKey> decodedAuthorityPublicKeys() {
        if (getAuthorityPublicKeys() == null || getAuthorityPublicKeys().isEmpty()) return Map.of();
        return getAuthorityPublicKeys().entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> com.bkanent.common.security.ServiceIdentityCrypto
                        .decodePublicKey(entry.getValue())));
    }
}
