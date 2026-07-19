-- docs/domain-policy.md: 메뉴는 고정 seed 데이터로 시작한다 (별도 등록 API 없음).
INSERT INTO menu (name, price) VALUES
    ('아메리카노', 4500),
    ('카페라떼', 5000),
    ('바닐라라떼', 5500),
    ('카푸치노', 5000),
    ('콜드브루', 5000);
