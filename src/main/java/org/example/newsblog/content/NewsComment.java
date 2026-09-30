package org.example.newsblog.content;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "news_comments", indexes = {
    @Index(name = "comments_news_status_created_idx", columnList = "news_id,status,created_at"),
    @Index(name = "comments_parent_idx", columnList = "parent_id"),
    @Index(name = "comments_author_news_idx", columnList = "author_id,news_id")
})
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "news_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "news_comments_news_id_fkey"))
    private News article;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "news_comments_author_id_fkey"))
    private org.example.newsblog.user.AppUser author;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "parent_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "news_comments_parent_id_fkey"))
    private NewsComment parent;
    protected NewsComment() {}
    NewsComment(UUID newsId, UUID authorId, UUID parentId, String body) {
        id = UUID.randomUUID(); this.newsId = newsId; this.authorId = authorId;
        this.parentId = parentId; this.body = body; status = Status.PENDING;
        createdAt = updatedAt = Instant.now();
    }
}
