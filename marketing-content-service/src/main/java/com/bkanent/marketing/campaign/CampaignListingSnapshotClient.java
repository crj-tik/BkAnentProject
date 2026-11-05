package com.bkanent.marketing.campaign;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.listing.ListingSourceStatus;
import com.bkanent.common.marketing.campaign.CampaignDataMode;
import com.bkanent.common.marketing.campaign.CampaignErrorCode;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagCanonicalHash;
import com.bkanent.common.rag.RagContextAuthorizedListingBusinessBatchReadRequest;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rpc.ListingBusinessSourceRpcService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Marketing-owned adapter for the signed, versioned Listing business source RPC. */
@Component
public class CampaignListingSnapshotClient {

    private final CampaignRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    @DubboReference(check = false)
    private ListingBusinessSourceRpcService listings;
    @Value("${marketing.listing-read.key-id:marketing-read-current}")
    private String keyId = "marketing-read-current";
    @Value("${marketing.listing-read.signing-secret:}")
    private String secret = "";

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
        if (listings == null) throw CampaignDomainException.unavailable(
                CampaignErrorCode.DEPENDENCY_UNAVAILABLE, "listing snapshot source is not configured");
        if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                    "listing snapshot caller identity is not configured");
        }
        try {
            var verified = VerifiedDomainAuthorization.requireVerifiedContext();
            String operation = verified.context().allowedOperations().contains("listing.read.all")
                    ? "listing.read.all" : "listing.read";
            VerifiedDomainAuthorization.requirePermission(operation);
            String requestId = UUID.randomUUID().toString();
            var read = new ListingSnapshotBatchReadRequest(ListingBusinessResponse.VERSION,
                    List.of(new com.bkanent.common.listing.ListingSourceVersionRef(
                            new ListingRef("legacy-local", Long.toString(listingId)), null)));
            var request = new RagContextAuthorizedListingBusinessBatchReadRequest(1, requestId, read,
                    verified.context(), verified.freshness());
            long now = Instant.now().getEpochSecond();
            long expires = Math.min(now + 60, Math.min(verified.context().expiresAtEpochSecond(),
                    Math.min(verified.context().runHardDeadlineEpochSecond(),
                            verified.freshness().notAfterEpochSecond())));
            if (expires <= now) throw CampaignDomainException.unauthorized("listing authorization has expired");
            var unsigned = new RagAuthEnvelope(1, keyId, "listing-master-service", "marketing-content-service",
                    RagOperation.READ, requestId, RagCanonicalHash.requestHash(request), RagActorMode.USER,
                    verified.actorId(), now, expires, null, null);
            var envelope = RagRequestSigner.sign(unsigned, secret.getBytes(StandardCharsets.UTF_8));
            ListingBusinessResponse<ListingSnapshotBatchReadItem> response = listings.batchReadContext(request, envelope);
            if (response == null || !ListingBusinessResponse.VERSION.equals(response.contractVersion())
                    || response.items().size() != 1) {
                throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                        "listing snapshot response does not match domain-parallel-v1");
            }
            ListingSnapshotBatchReadItem item = response.items().get(0);
            if (item.status() != ListingSourceStatus.CURRENT || item.snapshot() == null
                    || !item.listingRef().equals(new ListingRef("legacy-local", Long.toString(listingId)))) {
                throw CampaignDomainException.forbidden("listing snapshot is not accessible");
            }
            ListingSourceSnapshot snapshot = item.snapshot();
            // Internal listings are the only production source; their unproven rows stay UNKNOWN,
            // so real-mode campaigns accept REAL or UNKNOWN and only reject SIMULATED facts.
            if (!snapshot.listingRef().equals(item.listingRef())
                    || snapshot.dataMode() == com.bkanent.common.listing.ListingDataMode.SIMULATED) {
                throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                        "listing snapshot has an invalid identity or source mode");
            }
            return objectMapper.valueToTree(Map.of("contractVersion", ListingBusinessResponse.VERSION,
                    "listingRef", snapshot.listingRef(), "revision", snapshot.revision(),
                    "contentHash", snapshot.contentHash(), "observedAt", snapshot.observedAt(),
                    "dataMode", snapshot.dataMode(), "datasetId", snapshot.datasetId(), "facts", snapshot.facts()));
        } catch (CampaignDomainException failure) {
            throw failure;
        } catch (com.bkanent.common.rag.RagException failure) {
            if (failure.code() == com.bkanent.common.rag.RagErrorCode.FORBIDDEN) {
                throw CampaignDomainException.forbidden("listing snapshot is not accessible");
            }
            if (failure.code() == com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_EXPIRED) {
                throw CampaignDomainException.unauthorized("listing authorization has expired");
            }
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                    "listing snapshot authorization failed");
        } catch (com.bkanent.common.authz.AuthzException failure) {
            if (failure.code().httpStatus() == 401) {
                throw CampaignDomainException.unauthorized("listing authorization is required");
            }
            if (failure.code().httpStatus() == 403) {
                throw CampaignDomainException.forbidden("listing snapshot is not accessible");
            }
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                    "listing snapshot authorization is unavailable");
        } catch (Exception failure) {
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE, "listing snapshot source is unavailable");
        }
    }
}
