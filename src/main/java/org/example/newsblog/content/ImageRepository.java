package org.example.newsblog.content;

import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ImageRepository extends JpaRepository<NewsImage, UUID> {
    long countByNewsId(UUID newsId);
    interface Metadata {
        UUID getId(); UUID getNewsId(); UUID getUploaderId(); String getContentType(); String getAltText();
        int getWidth(); int getHeight(); int getByteSize(); Instant getCreatedAt();
    }
    @Query("select i.id as id, i.newsId as newsId, i.uploaderId as uploaderId, i.contentType as contentType, "
            + "i.altText as altText, i.width as width, i.height as height, i.byteSize as byteSize, i.createdAt as createdAt "
            + "from NewsImage i where i.newsId = :newsId")
    Page<Metadata> metadata(@Param("newsId") UUID newsId, Pageable page);
}
