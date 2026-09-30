package org.example.newsblog.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.example.newsblog.security.ApiException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.example.newsblog.content.ContentDtos.*;

@Service
public class ContentService {
    private final NewsRepository news;
    private final CommentRepository comments;
    private final ImageRepository images;
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    // Embedded images must be attached through the image API, never remote URLs or data URIs.
    private static final Safelist HTML = Safelist.relaxed().removeTags("img")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer");
    ContentService(NewsRepository news, CommentRepository comments, ImageRepository images) {
        this.news=news; this.comments=comments; this.images=images;
    }
    private UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    private boolean admin(Authentication a) { return a.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_ADMIN")); }
    private boolean reporter(Authentication a) { return a.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_REPORTER")); }
    private boolean editor(News n, Authentication a) { return admin(a) || reporter(a) && n.authorId.equals(actor(a)); }
    private void edit(News n, Authentication a) {
        if (!editor(n,a)) throw error(HttpStatus.FORBIDDEN,"FORBIDDEN","You cannot edit this article.");
        if (!admin(a) && n.status != News.Status.DRAFT)
            throw error(HttpStatus.CONFLICT,"NEWS_NOT_DRAFT","Ask an administrator to return this article to draft before editing.");
    }
    private void visible(News n, Authentication a) {
        if (n.status != News.Status.PUBLISHED && !editor(n,a)) throw missing("NEWS_NOT_FOUND");
    }
    private News get(UUID id) { return news.findById(id).orElseThrow(() -> missing("NEWS_NOT_FOUND")); }
    private News locked(UUID id) { return news.findLocked(id).orElseThrow(() -> missing("NEWS_NOT_FOUND")); }
    private void version(long actual, long expected) {
        if (actual != expected) throw error(HttpStatus.CONFLICT,"VERSION_CONFLICT","Reload the resource before updating it.");
    }
    private PageRequest page(int page,int size,String field) {
        if(page<0 || page>100000 || size<1 || size>100) throw error(HttpStatus.BAD_REQUEST,"INVALID_PAGE","Page must be nonnegative and size must be between 1 and 100.");
        return PageRequest.of(page,size,Sort.by(Sort.Order.desc(field),Sort.Order.desc("id")));
    }
    private static ApiException error(HttpStatus status,String code,String message) { return new ApiException(status,code,message); }
    private static ApiException missing(String code) { return error(HttpStatus.NOT_FOUND,code,"Resource not found."); }

    @Transactional(readOnly=true)
    public PageView<NewsSummary> list(Authentication a,int page,int size,String q,String category,News.Status status,boolean mine) {
        if(q!=null && q.length()>100 || category!=null && category.length()>80)
            throw error(HttpStatus.BAD_REQUEST,"INVALID_INPUT","Search and category are too long.");
        Specification<News> spec=(r,query,cb) -> {
            var predicates=new ArrayList<jakarta.persistence.criteria.Predicate>();
            if(!admin(a)) predicates.add(cb.or(cb.equal(r.get("status"),News.Status.PUBLISHED),
                    reporter(a) ? cb.equal(r.get("authorId"),actor(a)) : cb.disjunction()));
            if(mine) predicates.add(cb.equal(r.get("authorId"),actor(a)));
            if(status!=null) predicates.add(cb.equal(r.get("status"),status));
            if(category!=null && !category.isBlank()) predicates.add(cb.equal(r.get("category"),category.strip()));
            if(q!=null && !q.isBlank()) {
                String pattern="%"+q.strip().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
                predicates.add(cb.or(cb.like(cb.lower(r.get("title")),pattern,'\\'),cb.like(cb.lower(r.get("summary")),pattern,'\\')));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageView.of(news.findAll(spec,page(page,size,"createdAt")).map(NewsSummary::of));
    }
    @Transactional(readOnly=true)
    public NewsView read(UUID id,Authentication a) { News n=get(id); visible(n,a); return NewsView.of(n); }
    @Transactional
    public NewsView create(NewsWrite req,Authentication a) {
        if(!admin(a) && !reporter(a)) throw error(HttpStatus.FORBIDDEN,"FORBIDDEN","Reporter access required.");
        if(req.coverImageId()!=null) throw error(HttpStatus.BAD_REQUEST,"INVALID_COVER","Upload images after creating the draft.");
        News n=new News(actor(a)); apply(n,req.title(),req.summary(),req.bodyHtml(),req.category(),null);
        return NewsView.of(news.saveAndFlush(n));
    }
    private void apply(News n,String title,String summary,String html,String category,UUID cover) {
        String clean=Jsoup.clean(html,"",HTML,new Document.OutputSettings().prettyPrint(false));
        if(Jsoup.parseBodyFragment(clean).text().isBlank()) throw error(HttpStatus.BAD_REQUEST,"EMPTY_HTML","Article HTML must contain readable text.");
        if(clean.length()>20000) throw error(HttpStatus.BAD_REQUEST,"HTML_TOO_LONG","Sanitized HTML exceeds 20000 characters.");
        if(cover!=null) {
            NewsImage image=images.findById(cover).orElseThrow(() -> missing("IMAGE_NOT_FOUND"));
            if(!image.newsId.equals(n.id)) throw error(HttpStatus.BAD_REQUEST,"INVALID_COVER","Cover image must belong to this article.");
        }
        n.title=title.strip(); n.summary=summary.strip(); n.bodyHtml=clean; n.category=category.strip();
        n.coverImageId=cover; n.updatedAt=Instant.now();
    }
    @Transactional
    public NewsView update(UUID id,NewsUpdate req,Authentication a) {
        News n=locked(id); edit(n,a); version(n.version,req.version());
        apply(n,req.title(),req.summary(),req.bodyHtml(),req.category(),req.coverImageId());
        news.flush(); return NewsView.of(n);
    }
    @Transactional
    public NewsView status(UUID id,StatusWrite req,Authentication a) {
        if(!admin(a)) throw error(HttpStatus.FORBIDDEN,"FORBIDDEN","Only administrators can change publication status.");
        News n=locked(id); version(n.version,req.version()); n.status=req.status();
        n.publishedAt=n.status==News.Status.PUBLISHED ? Instant.now() : null;
        n.updatedAt=Instant.now(); news.flush(); return NewsView.of(n);
    }
    @Transactional
    public void archive(UUID id,long expected,Authentication a) {
        status(id,new StatusWrite(expected,News.Status.ARCHIVED),a);
    }

    @Transactional(readOnly=true)
    public PageView<CommentView> comments(UUID newsId,Authentication a,int page,int size) {
        News n=get(newsId); visible(n,a);
        Specification<NewsComment> spec=(r,q,cb) -> cb.and(cb.equal(r.get("newsId"),newsId),
                editor(n,a) ? cb.conjunction() : cb.or(cb.equal(r.get("status"),NewsComment.Status.APPROVED),cb.equal(r.get("authorId"),actor(a))));
        return PageView.of(comments.findAll(spec,page(page,size,"createdAt")).map(CommentView::of));
    }
    @Transactional
    public CommentView comment(UUID newsId,CommentWrite req,Authentication a) {
        News n=locked(newsId); visible(n,a);
        if(n.status!=News.Status.PUBLISHED) throw error(HttpStatus.CONFLICT,"NEWS_NOT_PUBLISHED","Comments require a published article.");
        if(comments.countByAuthorIdAndNewsId(actor(a),newsId)>=100) throw error(HttpStatus.TOO_MANY_REQUESTS,"COMMENT_LIMIT","Comment limit reached for this article.");
        if(req.parentId()!=null) {
            NewsComment parent=comments.findById(req.parentId()).orElseThrow(() -> missing("COMMENT_NOT_FOUND"));
            if(!parent.newsId.equals(newsId) || parent.status!=NewsComment.Status.APPROVED || parent.parentId!=null)
                throw error(HttpStatus.BAD_REQUEST,"INVALID_PARENT","Reply to an approved top-level comment on the same article.");
        }
        return CommentView.of(comments.saveAndFlush(new NewsComment(newsId,actor(a),req.parentId(),req.body().strip())));
    }
    private NewsComment commentLocked(UUID id) {
        NewsComment c=comments.findById(id).orElseThrow(() -> missing("COMMENT_NOT_FOUND"));
        locked(c.newsId); return c;
    }
    @Transactional
    public CommentView updateComment(UUID id,CommentUpdate req,Authentication a) {
        NewsComment c=commentLocked(id); News n=get(c.newsId); visible(n,a);
        if(!c.authorId.equals(actor(a))) throw error(HttpStatus.FORBIDDEN,"FORBIDDEN","Only the comment author can edit its text.");
        if(n.status!=News.Status.PUBLISHED) throw error(HttpStatus.CONFLICT,"NEWS_NOT_PUBLISHED","Comments require a published article.");
        version(c.version,req.version()); c.body=req.body().strip(); c.status=NewsComment.Status.PENDING;
        c.updatedAt=Instant.now(); comments.flush(); return CommentView.of(c);
    }
    @Transactional
    public CommentView moderate(UUID id,Moderation req,Authentication a) {
        NewsComment c=commentLocked(id); News n=get(c.newsId);
        if(!editor(n,a)) throw error(HttpStatus.FORBIDDEN,"FORBIDDEN","Only this article's reporter or an administrator can moderate comments.");
        version(c.version,req.version()); c.status=req.status(); c.updatedAt=Instant.now(); comments.flush(); return CommentView.of(c);
    }
    @Transactional
    public void deleteComment(UUID id,long expected,Authentication a) {
        NewsComment c=commentLocked(id); News n=get(c.newsId); visible(n,a);
        if(!editor(n,a) && !c.authorId.equals(actor(a))) throw error(HttpStatus.FORBIDDEN,"FORBIDDEN","You cannot delete this comment.");
        version(c.version,expected);
        if(comments.existsByParentId(id)) throw error(HttpStatus.CONFLICT,"COMMENT_HAS_REPLIES","Moderate this comment instead of deleting its replies.");
        comments.delete(c); comments.flush();
    }

    @Transactional(readOnly=true)
    public PageView<ImageView> imageList(UUID newsId,Authentication a,int page,int size) {
        visible(get(newsId),a);
        return PageView.of(images.metadata(newsId,page(page,size,"createdAt")).map(i -> new ImageView(
                i.getId(),i.getNewsId(),i.getUploaderId(),i.getContentType(),i.getAltText(),i.getWidth(),
                i.getHeight(),i.getByteSize(),i.getCreatedAt(),"/api/images/"+i.getId()+"/content")));
    }
    @Transactional
    public ImageView upload(UUID newsId,byte[] data,String contentType,String alt,Authentication a) {
        News n=locked(newsId); edit(n,a);
        if(alt==null || alt.length()>300) throw error(HttpStatus.BAD_REQUEST,"INVALID_INPUT","Alt text must be at most 300 characters.");
        if(data.length==0 || data.length>MAX_IMAGE_BYTES) throw error(HttpStatus.PAYLOAD_TOO_LARGE,"IMAGE_TOO_LARGE","Images must be between 1 byte and 5 MiB.");
        if(images.countByNewsId(newsId)>=20) throw error(HttpStatus.CONFLICT,"IMAGE_LIMIT","At most 20 images may be attached to one article.");
        String type=contentType==null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if(!Set.of("image/png","image/jpeg").contains(type)) throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"INVALID_IMAGE","Only PNG and JPEG are accepted.");
        // Verify format and dimensions before decoding; re-encode to remove metadata and trailing payloads.
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw error(HttpStatus.BAD_REQUEST,"INVALID_IMAGE","Image data is invalid.");
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!(type.equals("image/png") && format.equals("png") || type.equals("image/jpeg") && (format.equals("jpeg") || format.equals("jpg"))))
                    throw error(HttpStatus.BAD_REQUEST,"INVALID_IMAGE","Declared media type does not match the image.");
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || width>8000 || height>8000 || (long)width*height>16000000)
                    throw error(HttpStatus.BAD_REQUEST,"IMAGE_DIMENSIONS","Images must be at most 8000 pixels per dimension and 16 megapixels.");
                var decoded=reader.read(0); var output=new ByteArrayOutputStream();
                if(!ImageIO.write(decoded,format.equals("png") ? "png" : "jpeg",output))
                    throw error(HttpStatus.BAD_REQUEST,"INVALID_IMAGE","Cannot encode this image.");
                byte[] safe=output.toByteArray();
                if(safe.length>MAX_IMAGE_BYTES) throw error(HttpStatus.PAYLOAD_TOO_LARGE,"IMAGE_TOO_LARGE","Re-encoded image exceeds 5 MiB.");
                return ImageView.of(images.saveAndFlush(new NewsImage(newsId,actor(a),type,alt.strip(),width,height,safe)));
            } finally { reader.dispose(); }
        } catch(java.io.IOException | IllegalArgumentException ex) {
            throw error(HttpStatus.BAD_REQUEST,"INVALID_IMAGE","Image data is invalid.");
        }
    }
    private NewsImage image(UUID id,Authentication a) {
        NewsImage i=images.findById(id).orElseThrow(() -> missing("IMAGE_NOT_FOUND")); visible(get(i.newsId),a); return i;
    }
    @Transactional(readOnly=true)
    public ImageView imageInfo(UUID id,Authentication a) { return ImageView.of(image(id,a)); }
    public record ImageBytes(String contentType,byte[] data) {}
    @Transactional(readOnly=true)
    public ImageBytes imageBytes(UUID id,Authentication a) { NewsImage i=image(id,a); return new ImageBytes(i.contentType,i.data); }
    @Transactional
    public void deleteImage(UUID id,Authentication a) {
        NewsImage i=images.findById(id).orElseThrow(() -> missing("IMAGE_NOT_FOUND")); News n=locked(i.newsId); edit(n,a);
        if(id.equals(n.coverImageId)) { n.coverImageId=null; n.updatedAt=Instant.now(); news.flush(); }
        images.delete(i); images.flush();
    }
}
