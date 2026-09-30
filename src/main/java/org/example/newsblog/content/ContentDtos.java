package org.example.newsblog.content;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class ContentDtos {
    private ContentDtos() {}
    public record NewsWrite(@NotBlank @Size(max=200) String title,
                            @NotBlank @Size(max=1000) String summary,
                            @NotBlank @Size(max=20000) String bodyHtml,
                            @NotBlank @Size(max=80) String category, UUID coverImageId) {}
    public record NewsUpdate(@NotNull @PositiveOrZero Long version,
                             @NotBlank @Size(max=200) String title,
                             @NotBlank @Size(max=1000) String summary,
                             @NotBlank @Size(max=20000) String bodyHtml,
                             @NotBlank @Size(max=80) String category, UUID coverImageId) {}
    public record StatusWrite(@NotNull @PositiveOrZero Long version, @NotNull News.Status status) {}
    public record CommentWrite(@NotBlank @Size(max=2000) String body, UUID parentId) {}
    public record CommentUpdate(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max=2000) String body) {}
    public record Moderation(@NotNull @PositiveOrZero Long version, @NotNull NewsComment.Status status) {}
    public record NewsView(UUID id, UUID authorId, String title, String summary, String bodyHtml,
                           String category, News.Status status, UUID coverImageId, Instant createdAt,
                           Instant updatedAt, Instant publishedAt, long version) {
        static NewsView of(News n) { return new NewsView(n.id,n.authorId,n.title,n.summary,n.bodyHtml,
                n.category,n.status,n.coverImageId,n.createdAt,n.updatedAt,n.publishedAt,n.version); }
    }
    public record NewsSummary(UUID id, UUID authorId, String title, String summary, String category,
                              News.Status status, UUID coverImageId, Instant publishedAt, Instant updatedAt, long version) {
        static NewsSummary of(News n) { return new NewsSummary(n.id,n.authorId,n.title,n.summary,n.category,
                n.status,n.coverImageId,n.publishedAt,n.updatedAt,n.version); }
    }
    public record CommentView(UUID id, UUID newsId, UUID authorId, UUID parentId, String body,
                              NewsComment.Status status, Instant createdAt, Instant updatedAt, long version) {
        static CommentView of(NewsComment c) { return new CommentView(c.id,c.newsId,c.authorId,c.parentId,
                c.body,c.status,c.createdAt,c.updatedAt,c.version); }
    }
    public record ImageView(UUID id, UUID newsId, UUID uploaderId, String contentType, String altText,
                            int width, int height, int byteSize, Instant createdAt, String contentUrl) {
        static ImageView of(NewsImage i) { return new ImageView(i.id,i.newsId,i.uploaderId,i.contentType,
                i.altText,i.width,i.height,i.byteSize,i.createdAt,"/api/images/"+i.id+"/content"); }
    }
    public record PageView<T>(List<T> items, int page, int size, long total, int totalPages) {
        static <T> PageView<T> of(Page<T> p) { return new PageView<>(p.getContent(),p.getNumber(),p.getSize(),p.getTotalElements(),p.getTotalPages()); }
    }
}
