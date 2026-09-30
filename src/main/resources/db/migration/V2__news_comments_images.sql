CREATE TABLE news (
    id UUID PRIMARY KEY,
    author_id UUID NOT NULL REFERENCES app_users(id),
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    body_html TEXT NOT NULL,
    category VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    cover_image_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX news_status_published_idx ON news(status, published_at DESC);
CREATE INDEX news_author_created_idx ON news(author_id, created_at DESC);
CREATE INDEX news_category_idx ON news(category);
CREATE TABLE news_comments (
    id UUID PRIMARY KEY,
    news_id UUID NOT NULL REFERENCES news(id),
    author_id UUID NOT NULL REFERENCES app_users(id),
    parent_id UUID REFERENCES news_comments(id),
    body VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX comments_news_status_created_idx ON news_comments(news_id, status, created_at);
CREATE INDEX comments_parent_idx ON news_comments(parent_id);
CREATE INDEX comments_author_news_idx ON news_comments(author_id, news_id);
CREATE TABLE news_images (
    id UUID PRIMARY KEY,
    news_id UUID NOT NULL REFERENCES news(id),
    uploader_id UUID NOT NULL REFERENCES app_users(id),
    content_type VARCHAR(32) NOT NULL CHECK (content_type IN ('image/jpeg','image/png')),
    alt_text VARCHAR(300) NOT NULL,
    width INTEGER NOT NULL CHECK (width > 0),
    height INTEGER NOT NULL CHECK (height > 0),
    byte_size INTEGER NOT NULL CHECK (byte_size > 0 AND byte_size <= 5242880),
    data BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX images_news_idx ON news_images(news_id);
ALTER TABLE news ADD CONSTRAINT news_cover_fk FOREIGN KEY (cover_image_id) REFERENCES news_images(id);
