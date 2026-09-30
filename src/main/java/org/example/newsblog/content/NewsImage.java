package org.example.newsblog.content;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "news_images")
public class NewsImage {
    @Id UUID id;
    @Column(name = "news_id", nullable = false) UUID newsId;
    @Column(name = "uploader_id", nullable = false) UUID uploaderId;
    @Column(name = "content_type", nullable = false, length = 32) String contentType;
    @Column(name = "alt_text", nullable = false, length = 300) String altText;
    @Column(nullable = false) int width;
    @Column(nullable = false) int height;
    @Column(name = "byte_size", nullable = false) int byteSize;
    @JdbcTypeCode(SqlTypes.VARBINARY) @Column(nullable = false, columnDefinition = "bytea") byte[] data;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    protected NewsImage() {}
    NewsImage(UUID newsId, UUID uploaderId, String type, String alt, int width, int height, byte[] data) {
        id = UUID.randomUUID(); this.newsId = newsId; this.uploaderId = uploaderId;
        contentType = type; altText = alt; this.width = width; this.height = height;
        this.data = data; byteSize = data.length; createdAt = Instant.now();
    }
}
