package com.bkanent.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bkanent.interview.entity.InterviewAssetEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * InterviewAsset 数据访问接口。
 */
public interface InterviewAssetMapper extends BaseMapper<InterviewAssetEntity> {

    @Select("""
            SELECT a.*
            FROM interview_asset a
            JOIN interview_case c ON c.id = a.case_id AND c.deleted = 0
            LEFT JOIN interview_session s ON s.id = a.session_id AND s.deleted = 0
            WHERE a.visibility = 'PUBLISHED'
              AND a.archive_grade IN ('L2', 'L3')
              AND a.sanitization_revision >= #{sanitizationRevision}
              AND a.deleted = 0
              AND a.content_hash IS NOT NULL
              AND SHA2(a.content, 256) = a.content_hash
              AND ((a.session_id IS NULL AND a.source = 'UPLOAD')
                   OR (s.id IS NOT NULL AND s.case_id = a.case_id AND s.status = 'ARCHIVED'))
              AND (#{keyword} IS NULL OR #{keyword} = '' OR a.title LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY a.id DESC
            LIMIT #{limit}
            """)
    List<InterviewAssetEntity> selectPublicAssets(@Param("keyword") String keyword,
                                                   @Param("sanitizationRevision") int sanitizationRevision,
                                                   @Param("limit") int limit);
}
