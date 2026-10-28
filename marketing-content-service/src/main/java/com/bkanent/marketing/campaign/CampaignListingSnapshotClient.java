package com.bkanent.marketing.campaign;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.marketing.campaign.CampaignDataMode;
import com.bkanent.common.marketing.campaign.CampaignErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Marketing-owned wire adapter for the frozen domain-parallel-v1 listing snapshot endpoint. */
@Component
public class CampaignListingSnapshotClient {

    private final CampaignRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public CampaignListingSnapshotClient(CampaignRuntimeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public JsonNode read(long listingId, CampaignDataMode mode) {
        if (mode == CampaignDataMode.SIMULATED) {
            return objectMapper.valueToTree(Map.of(
                    "contractVersion", "domain-parallel-v1",
                    "listingRef", Map.of("sourceSystem", "marketing-local-fixture", "sourceId", Long.toString(listingId)),
                    "revision", "fixture-v1",
                    "contentHash", CampaignHash.sha256("marketing-listing-fixture:" + listingId),
                    "observedAt", java.time.Instant.EPOCH.toString(),
                    "dataMode", "SIMULATED",
                    "datasetId", "marketing-fixture-v1",
                    "facts", Map.of("title", "Fixture listing " + listingId, "areaSquareMetres", "88.0",
                            "totalPrice", Map.of("amount", "2500000", "currency", "CNY", "unit", "YUAN"))));
        }
        String baseUrl = properties.getListingSnapshotBaseUrl();
        String credential = requestCredential();
        if (!StringUtils.hasText(baseUrl)) {
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                    "listing snapshot source is not configured");
        }
        if (!StringUtils.hasText(credential)) throw CampaignDomainException.unauthorized("listing snapshot requires current user credentials");
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "contractVersion", "domain-parallel-v1",
                    "refs", java.util.List.of(Map.of("sourceSystem", "listing-master-service", "sourceId", Long.toString(listingId)))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/listings/business/v1/snapshots/batch-read"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Authorization", credential)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                        "listing snapshot source returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("success").asBoolean(false)) {
                throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                        "listing snapshot source rejected the request");
            }
            JsonNode data = root.path("data");
            if (!"domain-parallel-v1".equals(data.path("contractVersion").asText())
                    || !data.path("items").isArray() || data.path("items").size() != 1) {
                throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                        "listing snapshot response does not match domain-parallel-v1");
            }
            JsonNode item = data.path("items").get(0);
            if (!Long.toString(listingId).equals(item.path("listingRef").path("sourceId").asText())
                    || !"listing-master-service".equals(item.path("listingRef").path("sourceSystem").asText())
                    || !item.path("contentHash").asText().matches("[0-9a-f]{64}")
                    || !StringUtils.hasText(item.path("revision").asText())
                    || !StringUtils.hasText(item.path("datasetId").asText())
                    || !item.path("facts").isObject()
                    || !validObservedAt(item.path("observedAt").asText())
                    || !"REAL".equals(item.path("dataMode").asText())) {
                throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                        "listing snapshot response has invalid identity, version, facts, or source mode");
            }
            return item;
        } catch (CampaignDomainException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE, "listing snapshot request was interrupted");
        } catch (Exception failure) {
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE, "listing snapshot source is unavailable");
        }
    }

    private String requestCredential() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            String authorization = servlet.getRequest().getHeader("Authorization");
            if (StringUtils.hasText(authorization)) return authorization;
        }
        try {
            var credentials = A2aCredentialContext.currentCredentials();
            if (credentials != null && StringUtils.hasText(credentials.accessToken())) return "Bearer " + credentials.accessToken();
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }

    private boolean validObservedAt(String value) {
        if (!StringUtils.hasText(value)) return false;
        try {
            java.time.Instant.parse(value);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
