package com.example.coffeeordersystem.point.controller;

import com.example.coffeeordersystem.point.dto.PointChargeRequest;
import com.example.coffeeordersystem.point.dto.PointChargeResponse;
import com.example.coffeeordersystem.point.service.PointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/points/charge. docs/api-spec.md 참고.
 * TODO: Idempotency-Key 헤더 중복 검사를 공통 컴포넌트로 연결한다 (common/idempotency).
 */
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @PostMapping("/charge")
    public PointChargeResponse charge(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PointChargeRequest request
    ) {
        return pointService.charge(request);
    }
}
