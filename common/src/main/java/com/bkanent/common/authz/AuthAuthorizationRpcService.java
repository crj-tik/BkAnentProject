package com.bkanent.common.authz;

/**
 * Versioned native RPC contract only. Implementations must authenticate current user authority,
 * enforce caller-operation ACLs, consume nonces durably and correlate the signed response.
 * Tokens and proofs belong solely in the dedicated transport, never business/model metadata.
 */
public interface AuthAuthorizationRpcService {
    AuthzResponse resolveRequestAuthorization(String token, RequestBinding requestBinding, CallerProof callerProof)
            throws AuthzException;

    /**
     * Origin signs for the target audience; the receiver independently signs its request to Auth.
     * Both cover delegationRequestHash(context, binding), with separately consumed proof nonces.
     */
    AuthzResponse validateDelegation(AuthorizationContext context, DelegationBinding binding,
                                     CallerProof originProof, CallerProof receiverProof) throws AuthzException;
}
