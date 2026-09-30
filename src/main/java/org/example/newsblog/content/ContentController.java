package org.example.newsblog.content;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static org.example.newsblog.content.ContentDtos.*;

@RestController
@RequestMapping("/api")
@Validated
public class ContentController {
    private final ContentService service;
    ContentController(ContentService service) { this.service=service; }
    @GetMapping("/news")
    PageView<NewsSummary> list(Authentication a,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,
            @RequestParam(required=false) String category,@RequestParam(required=false) News.Status status,
            @RequestParam(defaultValue="false") boolean mine) { return service.list(a,page,size,q,category,status,mine); }
    @GetMapping("/news/{id}") NewsView read(@PathVariable UUID id,Authentication a) { return service.read(id,a); }
    @PostMapping("/news") @ResponseStatus(HttpStatus.CREATED)
    NewsView create(@Valid @RequestBody NewsWrite req,Authentication a) { return service.create(req,a); }
    @PutMapping("/news/{id}")
    NewsView update(@PathVariable UUID id,@Valid @RequestBody NewsUpdate req,Authentication a) { return service.update(id,req,a); }
    @PatchMapping("/news/{id}/status")
    NewsView status(@PathVariable UUID id,@Valid @RequestBody StatusWrite req,Authentication a) { return service.status(id,req,a); }
    // DELETE archives news; comments and images are retained, and ordinary users lose access.
    @DeleteMapping("/news/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable UUID id,@RequestParam @PositiveOrZero long version,Authentication a) { service.archive(id,version,a); }
    @GetMapping("/news/{id}/comments")
    PageView<CommentView> comments(@PathVariable UUID id,Authentication a,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return service.comments(id,a,page,size); }
    @PostMapping("/news/{id}/comments") @ResponseStatus(HttpStatus.CREATED)
    CommentView comment(@PathVariable UUID id,@Valid @RequestBody CommentWrite req,Authentication a) { return service.comment(id,req,a); }
    @PutMapping("/comments/{id}")
    CommentView updateComment(@PathVariable UUID id,@Valid @RequestBody CommentUpdate req,Authentication a) { return service.updateComment(id,req,a); }
    @PatchMapping("/comments/{id}/moderation")
    CommentView moderate(@PathVariable UUID id,@Valid @RequestBody Moderation req,Authentication a) { return service.moderate(id,req,a); }
    @DeleteMapping("/comments/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(@PathVariable UUID id,@RequestParam @PositiveOrZero long version,Authentication a) { service.deleteComment(id,version,a); }
    @GetMapping("/news/{id}/images")
    PageView<ImageView> imageList(@PathVariable UUID id,Authentication a,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return service.imageList(id,a,page,size); }
    // Raw binary request, not Base64 JSON or multipart form data.
    @PostMapping(value="/news/{id}/images",consumes={"image/png","image/jpeg"}) @ResponseStatus(HttpStatus.CREATED)
    ImageView upload(@PathVariable UUID id,@RequestBody byte[] bytes,@RequestHeader("Content-Type") String type,
            @RequestParam(defaultValue="") String alt,Authentication a) { return service.upload(id,bytes,type,alt,a); }
    @GetMapping("/images/{id}") ImageView image(@PathVariable UUID id,Authentication a) { return service.imageInfo(id,a); }
    @GetMapping("/images/{id}/content")
    ResponseEntity<byte[]> imageBytes(@PathVariable UUID id,Authentication a) {
        var image=service.imageBytes(id,a);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore()).header("Content-Disposition", "inline; filename=" + id)
                .contentLength(image.data().length).body(image.data());
    }
    @DeleteMapping("/images/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteImage(@PathVariable UUID id,Authentication a) { service.deleteImage(id,a); }
}
