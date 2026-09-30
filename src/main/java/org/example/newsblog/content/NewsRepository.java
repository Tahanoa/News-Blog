package org.example.newsblog.content;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface NewsRepository extends JpaRepository<News, UUID>, JpaSpecificationExecutor<News> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from News n where n.id = :id")
    Optional<News> findLocked(@Param("id") UUID id);
}
