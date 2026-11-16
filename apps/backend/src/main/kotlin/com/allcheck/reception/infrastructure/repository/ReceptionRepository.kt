package com.allcheck.reception.infrastructure.repository

import com.allcheck.reception.domain.entity.Reception
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ReceptionRepository : JpaRepository<Reception, Long> {
    fun findByReceptionIdAndOwnerAdminId(receptionId: Long, ownerAdminId: Long): Reception?

    fun findAllByOwnerAdminId(ownerAdminId: Long, pageable: Pageable): Page<Reception>
}
