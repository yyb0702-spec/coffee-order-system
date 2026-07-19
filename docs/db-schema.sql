-- 커피 주문 시스템 ERD 초안 (MySQL DDL)
-- ERDCloud(erdcloud.com) 좌측 하단 "Import" 버튼에 이 파일 내용을 그대로 붙여넣으면
-- 테이블과 관계(FK)가 자동으로 다이어그램에 생성됩니다.
-- 참고 문서: docs/domain-policy.md, docs/order-policy.md, docs/point-policy.md, docs/popular-menu-policy.md

CREATE TABLE menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '메뉴 ID',
    name VARCHAR(100) NOT NULL COMMENT '메뉴명',
    price INT NOT NULL COMMENT '가격(원)',
    CONSTRAINT chk_menu_price CHECK (price > 0)
) COMMENT '주문 가능한 커피 메뉴 (seed 데이터로 시작)';

CREATE TABLE user_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '포인트 계정 ID',
    user_id BIGINT NOT NULL COMMENT '사용자 식별값',
    balance INT NOT NULL DEFAULT 0 COMMENT '현재 잔액 (point_transaction 합계 캐시)',
    UNIQUE KEY uk_user_point_user_id (user_id),
    CONSTRAINT chk_user_point_balance CHECK (balance >= 0)
) COMMENT '사용자별 포인트 잔액. 충전 시 자동 생성(upsert)';

CREATE TABLE point_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '원장 ID',
    user_id BIGINT NOT NULL COMMENT '사용자 식별값',
    type VARCHAR(10) NOT NULL COMMENT 'CHARGE 또는 USE',
    amount INT NOT NULL COMMENT '변동 금액',
    balance_after INT NOT NULL COMMENT '변동 후 잔액 스냅샷',
    created_at DATETIME NOT NULL COMMENT '발생 시각',
    CONSTRAINT fk_point_transaction_user FOREIGN KEY (user_id) REFERENCES user_point (user_id),
    KEY idx_point_transaction_user_created (user_id, created_at)
) COMMENT '포인트 충전/사용 원장 (감사·잔액 검증용 원천 데이터)';

CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '주문 ID',
    user_id BIGINT NOT NULL COMMENT '주문한 사용자 식별값',
    menu_id BIGINT NOT NULL COMMENT '주문 메뉴 ID',
    paid_amount INT NOT NULL COMMENT '결제 금액 스냅샷 (주문 시점 메뉴 가격)',
    status VARCHAR(20) NOT NULL COMMENT 'PAID 또는 CANCELLED',
    ordered_at DATETIME NOT NULL COMMENT '주문 시각',
    CONSTRAINT fk_orders_menu FOREIGN KEY (menu_id) REFERENCES menu (id),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES user_point (user_id),
    KEY idx_orders_status_ordered_at (status, ordered_at),
    KEY idx_orders_ordered_at_menu_id (ordered_at, menu_id)
) COMMENT '완료된 주문 이력 (인기 메뉴 집계의 원천 데이터)';

CREATE TABLE processed_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '처리 이력 ID',
    event_id VARCHAR(64) NOT NULL COMMENT 'Kafka OrderCompletedEvent의 eventId',
    event_type VARCHAR(50) NOT NULL COMMENT '이벤트 종류',
    consumer_group VARCHAR(50) NOT NULL COMMENT '처리한 Consumer Group (예: ranking-consumer-group)',
    processed_at DATETIME NOT NULL COMMENT '처리 시각',
    UNIQUE KEY uk_processed_event_event_id (event_id)
) COMMENT 'Kafka Consumer 멱등 처리 이력';

CREATE TABLE idempotency_key (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '멱등성 키 ID',
    idempotency_key VARCHAR(64) NOT NULL COMMENT '클라이언트가 전달한 Idempotency-Key 헤더값',
    endpoint VARCHAR(50) NOT NULL COMMENT '요청 대상 API (예: POST /api/orders)',
    created_at DATETIME NOT NULL COMMENT '요청 처리 시각',
    UNIQUE KEY uk_idempotency_key (idempotency_key)
) COMMENT '주문/충전 API의 중복 요청 차단용';
