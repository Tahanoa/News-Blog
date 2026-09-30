package org.example.newsblog.content;

import java.util.UUID;
import org.springframework.data.jpa.repository.*;

interface CommentRepository extends JpaRepository<NewsComment, UUID>, JpaSpecificationExecutor<NewsComment> {
    long countByAuthorIdAndNewsId(UUID authorId, UUID newsId);
    boolean existsByParentId(UUID parentId);
}
