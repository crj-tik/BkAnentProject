package com.bkanent.customer.rpc;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedCustomerProfileVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rag.RagSigningKey;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.customer.config.CustomerAuthorizationProperties;
import com.bkanent.customer.config.CustomerProfileVerifyProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Customer 侧受权 RPC 身份验证器，等价于 listing 侧
 * {@code ListingRagIdentity.verifyAuthorizedOperationContext}：
 * 验证 Listing→Customer 卖方档案验证请求的 RagAuthEnvelope 服务签名与 Auth 签名执行上下文。
 *
 * <p>上下文受众接受可信调用方服务名（listing-master-service 的网关入口绑定上下文）
 * 或 agent-service（A2A 运行上下文），与 listing 侧
 * {@code verifyAuthorizedBusinessSourceContext} 的跨服务转发验签模式保持一致；
 * 请求类型自身已强制 runId/sessionId 非空，未绑定上下文无法构造请求。
 */
@Service
public class CustomerRpcIdentity {

    /**
     * 本服务名称，作为入站 envelope 的受众。
     */
    public static final String SERVICE = "customer-service";

    /**
     * A2A 父服务受众。
     */
    public static final String A2A_PARENT_SERVICE = "agent-service";

    /**
     * 跨域档案读取操作。
     */
    public static final String OWN_OPERATION = "customer.read";

    /**
     * 业务全量读取操作。
     */
    public static final String BUSINESS_WIDE_OPERATION = "customer.read.all";

    private static final Set<RagOperation> CALLER_OPERATIONS =
            Set.of(RagOperation.SEARCH, RagOperation.READ, RagOperation.SUBMIT);

    private final CustomerProfileVerifyProperties properties;
    private final CustomerAuthorizationProperties authorizationProperties;

    public CustomerRpcIdentity(CustomerProfileVerifyProperties properties,
                               CustomerAuthorizationProperties authorizationProperties) {
        this.properties = properties;
        this.authorizationProperties = authorizationProperties;
    }

    /**
     * 验证 Listing 转发的卖方档案验证上下文，返回可信操作人事实；
     * 验签或上下文失败时抛出 RagException，由调用方失败关闭。
     */
    public VerifiedProfileCaller verifyOwnerProfileCaller(RagContextAuthorizedCustomerProfileVerifyRequest request,
                                                          RagAuthEnvelope envelope) {
        try {
            if (request == null || envelope == null) {
                throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
            }
            Map<String, PublicKey> authorityKeys = decodedAuthorityPublicKeys();
            if (authorityKeys.isEmpty()) {
                throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
            }
            CustomerProfileVerifyProperties.TrustedCaller caller = trustedCaller(envelope);
            verifyCaller(request, envelope, caller);
            AuthorizationContext context = request.authorizationContext();
            AuthzFreshness freshness = request.freshness();
            long now = Instant.now().getEpochSecond();
            if (envelope.actorMode() != RagActorMode.USER
                    || !(caller.getCallerService().equals(context.audience())
                        || A2A_PARENT_SERVICE.equals(context.audience()))
                    || !context.actorId().equals(envelope.actorId())
                    || !context.actorId().equals(freshness.userId())
                    || context.permissionRevision() != freshness.permissionRevision()
                    || context.credentialEpoch() != freshness.credentialEpoch()
                    || !context.permissionHash().equals(freshness.permissionHash())
                    || context.runId() == null || context.sessionId() == null
                    || context.issuedAtEpochSecond() < freshness.checkedAtEpochSecond()
                    || envelope.expiresAtEpochSecond() > context.expiresAtEpochSecond()
                    || envelope.expiresAtEpochSecond() > context.runHardDeadlineEpochSecond()
                    || envelope.expiresAtEpochSecond() > freshness.notAfterEpochSecond()) {
                throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            }
            AuthzSignature.verifyContext(context, context.audience(), authorityKeys, Instant.ofEpochSecond(now));
            AuthzSignature.verifyFreshness(freshness, authorityKeys, Instant.ofEpochSecond(now));
            boolean businessWide = context.allowedOperations().contains(BUSINESS_WIDE_OPERATION);
            String operation = businessWide ? BUSINESS_WIDE_OPERATION : OWN_OPERATION;
            if (!context.allowedOperations().contains(operation)) {
                throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
            }
            // BROKER 映射原样返回：非业务管理员缺失映射时由档案归属比对得出 NOT_ACCESSIBLE，
            // 与决策 7 的"映射缺失拒绝登记"端到端语义一致（登记侧收到不可访问即拒绝）。
            Long brokerId = context.businessIdentities().get("BROKER");
            return new VerifiedProfileCaller(Long.parseLong(context.actorId()), businessWide, brokerId);
        } catch (AuthzException failure) {
            throw toRagException(failure);
        }
    }

    /**
     * 已验证的跨服务操作人事实：actorId、是否业务全量、可信 BROKER 映射。
     */
    public record VerifiedProfileCaller(long actorUserId, boolean businessWide, Long brokerId) {
    }

    /**
     * RagAuthEnvelope HMAC 验签：可信 caller 配置（keyId → 调用方服务与密钥），
     * 密钥缺失或不足 32 字节直接拒绝。
     */
    private void verifyCaller(RagContextAuthorizedCustomerProfileVerifyRequest request,
                              RagAuthEnvelope envelope,
                              CustomerProfileVerifyProperties.TrustedCaller caller) {
        RagRequestSigner.verify(envelope,
                CustomerVerifyContractHash.customerProfileVerifyRequestHash(request),
                SERVICE, RagOperation.READ, request.request().requestId(), null,
                new RagSigningKey(envelope.keyId(), caller.getCallerService(), null, CALLER_OPERATIONS,
                        caller.getSecret().getBytes(StandardCharsets.UTF_8)), Instant.now());
    }

    private CustomerProfileVerifyProperties.TrustedCaller trustedCaller(RagAuthEnvelope envelope) {
        CustomerProfileVerifyProperties.TrustedCaller caller =
                properties.getTrustedCallers().get(envelope.keyId());
        if (caller == null || caller.getCallerService() == null || caller.getCallerService().isBlank()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        if (caller.getSecret() == null || caller.getSecret().isBlank()
                || caller.getSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        return caller;
    }

    /**
     * Auth 验签公钥（复用入口授权配置），未配置时按空处理并失败关闭。
     */
    private Map<String, PublicKey> decodedAuthorityPublicKeys() {
        if (authorizationProperties.getAuthorityPublicKeys() == null
                || authorizationProperties.getAuthorityPublicKeys().isEmpty()) {
            return Map.of();
        }
        return authorizationProperties.getAuthorityPublicKeys().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> ServiceIdentityCrypto.decodePublicKey(entry.getValue())));
    }

    private static RagException toRagException(AuthzException failure) {
        RagErrorCode code = switch (failure.code()) {
            case PERMISSION_DENIED -> RagErrorCode.FORBIDDEN;
            case EXPIRED -> RagErrorCode.AUTHENTICATION_EXPIRED;
            case INVALID_ARGUMENT -> RagErrorCode.INVALID_ARGUMENT;
            case AUTHORIZATION_UNAVAILABLE -> RagErrorCode.DEPENDENCY_UNAVAILABLE;
            case CONTEXT_CONFLICT, REPLAY_DETECTED, AUTHENTICATION_REQUIRED, AUTHENTICATION_FAILED ->
                    RagErrorCode.AUTHENTICATION_FAILED;
        };
        return new RagException(code);
    }
}
