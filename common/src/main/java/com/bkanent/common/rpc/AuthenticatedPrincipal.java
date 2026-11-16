package com.bkanent.common.rpc;

import java.io.Serializable;

/**
 * Auth 根据凭据解析的平台用户；userId 即本系统 UCID（user_account.id）。
 * 经纪人、员工、门店等业务编号须通过明确关系关联，不能作为此处的用户编号。
 */
public record AuthenticatedPrincipal(Long userId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
