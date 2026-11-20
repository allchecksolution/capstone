package com.allcheck.driver.infrastructure.repository.registration

import com.allcheck.driver.domain.entity.registration.Driver
import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DriverRepository : JpaRepository<Driver, String> {
    fun findByAccountId(accountId: String): Driver?

    fun existsByAccountId(accountId: String): Boolean

    fun existsByAccountIdAndAccountStatus(accountId: String, accountStatus: DriverAccountStatus): Boolean

    @Query("select driver.accountId from Driver driver where driver.id = :driverId")
    fun findAccountId(@Param("driverId") driverId: String): String?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select driver from Driver driver where driver.accountId = :accountId")
    fun findByAccountIdForUpdate(@Param("accountId") accountId: String): Driver?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select driver from Driver driver where driver.id = :driverId")
    fun findByIdForUpdate(@Param("driverId") driverId: String): Driver?

    @Query(
        value = """
        select driver from Driver driver join fetch driver.profile profile join fetch driver.business business
        where (:status is null or driver.accountStatus = :status)
          and (:pattern = '' or lower(profile.workerName) like :pattern escape '!' or lower(business.businessName) like :pattern escape '!')
        order by driver.submittedAt desc, driver.id
        """,
        countQuery = """
        select count(driver) from Driver driver join driver.profile profile join driver.business business
        where (:status is null or driver.accountStatus = :status)
          and (:pattern = '' or lower(profile.workerName) like :pattern escape '!' or lower(business.businessName) like :pattern escape '!')
        """,
    )
    fun search(@Param("status") status: DriverAccountStatus?, @Param("pattern") pattern: String, pageable: Pageable): Page<Driver>
}
