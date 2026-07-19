package com.example.coffeeordersystem.point.controller;

import com.example.coffeeordersystem.point.dto.PointChargeRequest;
import com.example.coffeeordersystem.point.dto.PointChargeResponse;
import com.example.coffeeordersystem.point.service.PointChargeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/points/charge. docs/api-spec.md 참고.
 * Idempotency-Key 중복 검사는 PointService.charge()가 비즈니스 트랜잭션 안에서 수행한다(ADR-006).
 * userId 기준 락(최초 충전 동시 요청 레이스 방지)은 PointChargeService가 담당한다.
 */
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

    private final PointChargeService pointChargeService;

    @PostMapping("/charge")
    public PointChargeResponse charge(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PointChargeRequest request
    ) {
        return pointChargeService.charge(idempotencyKey, request);
    }
}
