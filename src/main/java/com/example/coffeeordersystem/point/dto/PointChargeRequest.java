package com.example.coffeeordersystem.point.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * POST /api/points/charge 요청. docs/api-spec.md, docs/point-policy.md(1P~1,000,000P) 참고.
 */
public record PointChargeRequest(

        @NotNull
        @Positive
        Long userId,

        @NotNull
        @Min(1)
        Integer amount
) {
}
