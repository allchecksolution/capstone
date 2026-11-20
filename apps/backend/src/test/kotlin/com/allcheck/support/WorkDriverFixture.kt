package com.allcheck.support

import org.springframework.jdbc.core.JdbcTemplate

internal fun JdbcTemplate.insertDriverFixture(id: Long) {
    update(
        """
        with account as (
            insert into identity_accounts(account_id, user_type, social_provider, social_user_id, created_at)
            values (?, 'DRIVER', 'KAKAO', ?, current_timestamp) on conflict (account_id) do nothing
            returning account_id
        ), business as (
            insert into driver_businesses(business_id, business_name, registration_number, opened_on, business_type,
                business_item, representative_name, company_phone, company_address, operating_status, verified_at)
            select account_id, '테스트', '1234567890', current_date, '수리', '수리', '테스트', '01012345678',
                '서울', 'CONTINUING', current_timestamp from account
            returning business_id
        )
        insert into drivers(driver_id, account_id, business_id, account_status, worker_name, phone, email, residence,
            vehicle_number, bank_name, account_number, holder_name, tax_invoice_email, submitted_at, revision)
        select business_id, business_id, business_id, 'ACTIVATED', '테스트', '01012345678', 'test@example.test',
            '서울', '테스트', '테스트', '123456', '테스트', 'test@example.test', current_timestamp, 1
        from business
        """.trimIndent(),
        id,
        id.toString(),
    )
}
