package com.bkanent.listing.source;

import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.listing.ListingSourceStatus;
import com.bkanent.common.model.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Explicit managed HTTP source. Network and contract failures never select another provider. */
@Component
@ConditionalOnProperty(prefix = "listing.source", name = "provider", havingValue = "http")
public class HttpListingSourceAdapter implements ListingSourceAdapter {
    private static final ParameterizedTypeReference<ApiResponse<ListingBusinessResponse<UpstreamSnapshot>>> SEARCH_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<ListingBusinessResponse<UpstreamBatchReadItem>>> READ_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<ListingBusinessResponse<ListingSnapshotCheckItem>>> CHECK_TYPE =
            new ParameterizedTypeReference<>() { };

    private final ListingSourceProperties.Http properties;
    private final RestClient client;

    public HttpListingSourceAdapter(ListingSourceProperties properties) {
        this.properties = properties.getHttp();
        validateConfiguration(this.properties);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(this.properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(this.properties.getReadTimeoutMs()));
        this.client = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public ListingSourcePage search(ListingBusinessSearchRequest request, ListingAccessScope scope) {
        ListingBusinessResponse<UpstreamSnapshot> response = post(
                properties.getSearchPath(), request, SEARCH_TYPE, scope);
        if (response.items().stream().anyMatch(item -> item == null || item.listingRef() == null)) {
            throw new ListingSourceUnavailableException();
        }
        List<ListingSourceSnapshot> normalized = response.items().stream().map(this::normalize).toList();
        return new ListingSourcePage(normalized, response.nextCursor());
    }

    @Override
    public List<ListingSnapshotBatchReadItem> batchRead(ListingSnapshotBatchReadRequest request,
                                                        ListingAccessScope scope) {
        ListingBusinessResponse<UpstreamBatchReadItem> response = post(
                properties.getBatchReadPath(), request, READ_TYPE, scope);
        if (response.items().stream().anyMatch(item -> item == null || item.listingRef() == null)) {
            throw new ListingSourceUnavailableException();
        }
        return response.items().stream().map(item -> {
            if (item.status() == null || item.status() == ListingSourceStatus.CURRENT && item.snapshot() == null
                    || item.status() != ListingSourceStatus.CURRENT && item.snapshot() != null) {
                throw new ListingSourceUnavailableException();
            }
            if (item.status() == ListingSourceStatus.CURRENT) {
                return new ListingSnapshotBatchReadItem(normalizeRef(item.listingRef()), item.status(), normalize(item.snapshot()));
            }
            return new ListingSnapshotBatchReadItem(normalizeRef(item.listingRef()), item.status(), null);
        }).toList();
    }

    @Override
    public List<ListingSnapshotCheckItem> check(ListingSnapshotCheckRequest request, ListingAccessScope scope) {
        ListingBusinessResponse<ListingSnapshotCheckItem> response = post(
                properties.getCheckPath(), request, CHECK_TYPE, scope);
        if (response.items().stream().anyMatch(item -> item == null || item.listingRef() == null)) {
            throw new ListingSourceUnavailableException();
        }
        return response.items().stream().map(item -> new ListingSnapshotCheckItem(
                normalizeRef(item.listingRef()), item.expectedRevision(), checkedStatus(item.status()))).toList();
    }

    private <T> ListingBusinessResponse<T> post(String path, Object request,
                                               ParameterizedTypeReference<ApiResponse<ListingBusinessResponse<T>>> type,
                                               ListingAccessScope scope) {
        try {
            if (scope == null) throw new ListingSourceUnavailableException();
            RestClient.RequestBodySpec body = client.post().uri(join(properties.getBaseUrl(), path))
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(properties.getBearerToken())) {
                body.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getBearerToken());
            }
            body.header("X-Listing-Actor-Id", Long.toString(scope.actorId()))
                    .header("X-Listing-Read-Scope", scope.businessWideRead() ? "business" : "broker");
            if (!scope.businessWideRead()) body.header("X-Listing-Broker-Id", Long.toString(scope.brokerId()));
            ApiResponse<ListingBusinessResponse<T>> envelope = body.body(request).retrieve().body(type);
            if (envelope == null || !envelope.success() || envelope.data() == null
                    || !ListingBusinessResponse.VERSION.equals(envelope.data().contractVersion())
                    || envelope.data().items() == null) {
                throw new ListingSourceUnavailableException();
            }
            return envelope.data();
        } catch (ListingSourceUnavailableException failure) {
            throw failure;
        } catch (RestClientException | IllegalArgumentException failure) {
            throw new ListingSourceUnavailableException();
        }
    }

    private ListingSourceSnapshot normalize(UpstreamSnapshot snapshot) {
        try {
            if (snapshot == null || snapshot.observedAt() == null || snapshot.facts() == null
                    || !StringUtils.hasText(snapshot.datasetId())) throw new ListingSourceUnavailableException();
            ListingRef ref = normalizeRef(snapshot.listingRef());
            ListingDataMode mode = properties.getConfirmedDataMode();
            if (mode == null) mode = ListingDataMode.UNKNOWN;
            // Hash and data mode are confirmed locally; remote values are never trusted as proof.
            String contentHash = ListingSourceSnapshotFactory.contentHash(ref, mode, snapshot.datasetId(), snapshot.facts());
            String revision = StringUtils.hasText(snapshot.revision()) ? snapshot.revision() : contentHash;
            return new ListingSourceSnapshot(ref, revision, contentHash, snapshot.observedAt(), mode,
                    snapshot.datasetId(), snapshot.facts());
        } catch (ListingSourceUnavailableException failure) {
            throw failure;
        } catch (IllegalArgumentException malformed) {
            throw new ListingSourceUnavailableException();
        }
    }

    private ListingSourceStatus checkedStatus(ListingSourceStatus status) {
        if (status == null) throw new ListingSourceUnavailableException();
        return status;
    }

    private ListingRef normalizeRef(ListingRef ref) {
        if (ref == null || !properties.getSourceSystem().equals(ref.sourceSystem())) {
            throw new ListingSourceUnavailableException();
        }
        return new ListingRef(properties.getSourceSystem(), ref.sourceId());
    }

    private static URI join(String baseUrl, String path) {
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    private static void validateConfiguration(ListingSourceProperties.Http properties) {
        if (properties.getConnectTimeoutMs() < 1 || properties.getConnectTimeoutMs() > 30_000
                || properties.getReadTimeoutMs() < 1 || properties.getReadTimeoutMs() > 60_000
                || !StringUtils.hasText(properties.getSourceSystem())
                || !properties.getSourceSystem().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
                || properties.getConfirmedDataMode() == null
                || !validPath(properties.getSearchPath()) || !validPath(properties.getBatchReadPath())
                || !validPath(properties.getCheckPath())) {
            throw new IllegalStateException("Invalid listing HTTP source configuration");
        }
        if (!StringUtils.hasText(properties.getBaseUrl())) return;
        try {
            URI uri = URI.create(properties.getBaseUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || !StringUtils.hasText(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException("Invalid listing HTTP source base URL");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid listing HTTP source base URL", invalid);
        }
    }

    private static boolean validPath(String path) {
        return StringUtils.hasText(path) && path.startsWith("/") && !path.startsWith("//")
                && !path.contains("..") && !path.contains("?") && !path.contains("#");
    }

    /** Upstream revision/hash/mode are treated as claims; revision may be absent and is derived locally. */
    private record UpstreamSnapshot(ListingRef listingRef, String revision, String contentHash,
                                    java.time.Instant observedAt, ListingDataMode dataMode,
                                    String datasetId, com.bkanent.common.listing.ListingSnapshotFacts facts) { }

    private record UpstreamBatchReadItem(ListingRef listingRef, ListingSourceStatus status,
                                         UpstreamSnapshot snapshot) { }
}
