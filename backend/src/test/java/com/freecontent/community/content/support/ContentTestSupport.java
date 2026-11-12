package com.freecontent.community.content.support;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/** 旧工作区测试也使用真实审核关联，避免伪造悬空activeReviewId。 */
public final class ContentTestSupport {
    private ContentTestSupport() { }
    public static String pending(JdbcTemplate jdbc, String content, String revision) {
        String review = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO content_review(id,content_id,revision_id,purpose,state,version,submitted_at) VALUES(?,?,?,'INITIAL','PENDING',1,UTC_TIMESTAMP(6))",
                review, content, revision);
        return review;
    }
    public static void clearReviews(JdbcTemplate jdbc, String owner) {
        jdbc.update("UPDATE community_content SET active_review_id=NULL WHERE author_id=?", owner);
        for (String table : new String[]{"content_review_outbox", "content_review_submission_request", "content_review"})
            jdbc.update("DELETE FROM " + table + " WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)", owner);
    }

    /** 调用方先清理审核和审计记录，再按原有顺序清理该作者的修订与内容。 */
    public static void clearWorkspace(JdbcTemplate jdbc, String owner) {
        jdbc.update("UPDATE community_content SET working_revision_id=NULL,published_revision_id=NULL WHERE author_id=?", owner);
        jdbc.update("DELETE a FROM content_revision_asset a JOIN content_revision r ON r.id=a.revision_id JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
        jdbc.update("DELETE t FROM content_revision_tag t JOIN content_revision r ON r.id=t.revision_id JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
        jdbc.update("DELETE r FROM content_revision r JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
        jdbc.update("DELETE FROM community_content WHERE author_id=?", owner);
    }
}
