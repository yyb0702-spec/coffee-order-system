package com.example.coffeeordersystem.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 메뉴입니다."),
    USER_POINT_NOT_FOUND(HttpStatus.NOT_FOUND, "충전 이력이 없는 사용자입니다."),
    INVALID_CHARGE_AMOUNT(HttpStatus.BAD_REQUEST, "충전 금액이 허용 범위를 벗어났습니다."),
    INSUFFICIENT_POINT(HttpStatus.CONFLICT, "포인트 잔액이 부족합니다."),
    ORDER_LOCK_NOT_ACQUIRED(HttpStatus.CONFLICT, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "이미 처리된 요청입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
