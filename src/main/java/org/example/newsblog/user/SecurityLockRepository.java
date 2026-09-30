package org.example.newsblog.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SecurityLockRepository extends JpaRepository<SecurityLock, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SecurityLock l where l.id = 1")
    SecurityLock acquire();
}
