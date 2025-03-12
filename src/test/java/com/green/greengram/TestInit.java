package com.green.greengram;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@ActiveProfiles("test-init")
@Rollback(false)
@SpringBootTest // 없으면 테이블자체를 안만들어 줘서 테이블 수정이 필요하면 넣어야 됨
@Sql(scripts = {"classpath:test-import.sql"})
// 테스트 원상태 복귀용 or 테스트 기초 데이터를 늘릴 떄(더미데이터)
public class TestInit {
    @Test
    void init(){
    }
}
