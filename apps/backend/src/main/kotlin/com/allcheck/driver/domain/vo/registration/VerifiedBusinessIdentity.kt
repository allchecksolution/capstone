package com.allcheck.driver.domain.vo.registration

import java.time.Instant

/** 입력 정보의 일치 결과이며 기사 가입·승인 또는 업무 자격을 자동 부여하지 않는다. */
class VerifiedBusinessIdentity(
    val identity: BusinessIdentity,
    val operatingStatus: BusinessOperatingStatus,
    val verifiedAt: Instant,
)
