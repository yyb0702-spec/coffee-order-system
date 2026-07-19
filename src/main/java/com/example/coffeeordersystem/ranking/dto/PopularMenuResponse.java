package com.example.coffeeordersystem.ranking.dto;

/**
 * GET /api/menus/popular 응답 원소. docs/api-spec.md 참고.
 */
public record PopularMenuResponse(int rank, Long menuId, String menuName, long orderCount) {
}
