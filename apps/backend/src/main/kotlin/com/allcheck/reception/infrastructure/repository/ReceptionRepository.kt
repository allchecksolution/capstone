package com.allcheck.reception.infrastructure.repository

import com.allcheck.reception.domain.entity.Reception
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ReceptionRepository : JpaRepository<Reception, String> {
    fun findByReceptionIdAndOwnerAdminId(receptionId: String, ownerAdminId: String): Reception?

    fun findAllByOwnerAdminId(ownerAdminId: String, pageable: Pageable): Page<Reception>
}
