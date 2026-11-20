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

interface DriverRepository : JpaRepository<Driver, Long> {
    fun findByAccountId(accountId: Long): Driver?

    fun existsByAccountId(accountId: Long): Boolean

    fun existsByAccountIdAndAccountStatus(accountId: Long, accountStatus: DriverAccountStatus): Boolean

    @Query("select driver.accountId from Driver driver where driver.id = :driverId")
    fun findAccountId(@Param("driverId") driverId: Long): Long?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select driver from Driver driver where driver.accountId = :accountId")
    fun findByAccountIdForUpdate(@Param("accountId") accountId: Long): Driver?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select driver from Driver driver where driver.id = :driverId")
    fun findByIdForUpdate(@Param("driverId") driverId: Long): Driver?

    @Query(
        value = """
        select driver from Driver driver join fetch driver.business business
        where (:status is null or driver.accountStatus = :status)
          and (:pattern = '' or lower(driver.profile.workerName) like :pattern escape '!' or lower(business.businessName) like :pattern escape '!')
        order by driver.submittedAt desc, driver.id
        """,
        countQuery = """
        select count(driver) from Driver driver join driver.business business
        where (:status is null or driver.accountStatus = :status)
          and (:pattern = '' or lower(driver.profile.workerName) like :pattern escape '!' or lower(business.businessName) like :pattern escape '!')
        """,
    )
    fun search(@Param("status") status: DriverAccountStatus?, @Param("pattern") pattern: String, pageable: Pageable): Page<Driver>
}
