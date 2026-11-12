package com.freecontent.community.content.review.mapper;

import com.freecontent.community.content.model.persistence.ReviewRow;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdminReviewQueryMapper {
    String FILTER = "WHERE state=#{state} <if test='purpose != null'> AND purpose=#{purpose} </if>";

    @Select("""
            SELECT id,content_id,revision_id,purpose,state,version,submitted_at,decided_at,reviewer_id,reason
            FROM content_review WHERE id=#{reviewId}
            """)
    ReviewRow findById(@Param("reviewId") String reviewId);

    @Select("<script> SELECT COUNT(*) FROM content_review " + FILTER + " </script>")
    long count(@Param("state") String state, @Param("purpose") String purpose);

    @Select("""
            <script> SELECT id,content_id,revision_id,purpose,state,version,submitted_at,decided_at,reviewer_id,reason
            FROM content_review
            """ + FILTER + """
            <choose><when test="state == 'PENDING'"> ORDER BY submitted_at ASC,id ASC </when>
            <otherwise> ORDER BY submitted_at DESC,id DESC </otherwise></choose>
            LIMIT #{limit} OFFSET #{offset} </script>
            """)
    List<ReviewRow> page(@Param("state") String state, @Param("purpose") String purpose, @Param("offset") long offset, @Param("limit") int limit);
}
