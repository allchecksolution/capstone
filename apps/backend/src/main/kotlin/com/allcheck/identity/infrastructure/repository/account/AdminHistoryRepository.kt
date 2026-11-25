package com.allcheck.identity.infrastructure.repository.account

import com.allcheck.identity.domain.entity.account.AdminHistory
import org.springframework.data.jpa.repository.JpaRepository

interface AdminHistoryRepository : JpaRepository<AdminHistory, Long>
