package org.example.newsblog.content;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "news")
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
    protected News() {}
    News(UUID authorId) {
        id = UUID.randomUUID(); this.authorId = authorId; status = Status.DRAFT;
        createdAt = updatedAt = Instant.now();
    }
}
