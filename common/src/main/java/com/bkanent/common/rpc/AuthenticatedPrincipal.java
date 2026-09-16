package com.bkanent.common.rpc;

import java.io.Serializable;

/**
 * The account context resolved by the authentication service for an access token.
 */
public record AuthenticatedPrincipal(Long userId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
