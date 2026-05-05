package com.bkanent.common.model;

import java.math.BigDecimal;

public record ListingDTO(
        Long id,
        String title,
        String address,
        String layout,
        BigDecimal area,
        BigDecimal totalPrice,
        String status
) {
}
