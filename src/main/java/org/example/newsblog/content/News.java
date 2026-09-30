package org.example.newsblog.content;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "news", indexes = {
    @Index(name = "news_status_published_idx", columnList = "status,published_at"),
    @Index(name = "news_author_created_idx", columnList = "author_id,created_at"),
    @Index(name = "news_category_idx", columnList = "category")
})
public class News {
    public enum Status { DRAFT, PUBLISHED, ARCHIVED }
    @Id UUID id;
    @Column(name = "author_id", nullable = false) UUID authorId;
    @Column(nullable = false, length = 200) String title;
    @Column(nullable = false, length = 1000) String summary;
    @Column(name = "body_html", nullable = false, columnDefinition = "text") String bodyHtml;
    @Column(nullable = false, length = 80) String category;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) Status status;
    @Column(name = "cover_image_id") UUID coverImageId;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "published_at") Instant publishedAt;
    @Version @Column(nullable = false) long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "news_author_id_fkey"))
    private org.example.newsblog.user.AppUser author;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "cover_image_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "news_cover_fk"))
    private NewsImage coverImage;
    protected News() {}
    News(UUID authorId) {
        id = UUID.randomUUID(); this.authorId = authorId; status = Status.DRAFT;
        createdAt = updatedAt = Instant.now();
    }
}
