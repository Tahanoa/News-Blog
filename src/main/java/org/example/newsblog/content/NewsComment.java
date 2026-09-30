package org.example.newsblog.content;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "news_comments")
public class NewsComment {
    public enum Status { PENDING, APPROVED, REJECTED }
    @Id UUID id;
    @Column(name = "news_id", nullable = false) UUID newsId;
    @Column(name = "author_id", nullable = false) UUID authorId;
    @Column(name = "parent_id") UUID parentId;
    @Column(nullable = false, length = 2000) String body;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) Status status;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Version @Column(nullable = false) long version;
    protected NewsComment() {}
    NewsComment(UUID newsId, UUID authorId, UUID parentId, String body) {
        id = UUID.randomUUID(); this.newsId = newsId; this.authorId = authorId;
        this.parentId = parentId; this.body = body; status = Status.PENDING;
        createdAt = updatedAt = Instant.now();
    }
}
