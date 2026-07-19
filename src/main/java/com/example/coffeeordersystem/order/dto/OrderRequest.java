package com.example.coffeeordersystem.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;


public record OrderRequest(

        @NotNull
        @Positive
        Long userId,

        @NotNull
        @Positive
        Long menuId
) {
}
