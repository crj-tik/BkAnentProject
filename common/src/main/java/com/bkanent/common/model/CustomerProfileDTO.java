package com.bkanent.common.model;

import java.math.BigDecimal;

public record CustomerProfileDTO(
        Long id,
        String name,
        String mobile,
        String intention,
        BigDecimal budgetMin,
        BigDecimal budgetMax
) {
}
