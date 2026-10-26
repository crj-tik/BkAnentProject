package com.bkanent.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bkanent.interview.entity.InterviewReportEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * InterviewReport 数据访问接口。
 */
public interface InterviewReportMapper extends BaseMapper<InterviewReportEntity> {

    @Select("""
            SELECT r.*
            FROM interview_report r
            JOIN interview_asset a ON a.id = r.asset_id AND a.case_id = r.case_id
            JOIN interview_case c ON c.id = r.case_id AND c.deleted = 0
            LEFT JOIN interview_session s ON s.id = a.session_id AND s.deleted = 0
            JOIN interview_report_task t ON t.id = r.task_id AND t.report_id = r.id
            WHERE r.task_id IS NOT NULL
              AND r.source_content_hash = a.content_hash
              AND r.source_revision = a.content_revision
              AND r.template_version IS NOT NULL
              AND t.status = 'SUCCEEDED'
              AND t.source_content_hash = a.content_hash
              AND t.source_revision = a.content_revision
              AND t.source_sanitization_revision = a.sanitization_revision
              AND t.template_version = r.template_version
              AND t.input_snapshot_hash = r.input_snapshot_hash
              AND a.visibility = 'PUBLISHED'
              AND a.archive_grade IN ('L2', 'L3')
              AND a.sanitization_revision >= #{sanitizationRevision}
              AND a.deleted = 0
              AND a.content_hash IS NOT NULL
              AND SHA2(a.content, 256) = a.content_hash
              AND ((a.session_id IS NULL AND a.source = 'UPLOAD')
                   OR (s.id IS NOT NULL AND s.case_id = a.case_id AND s.status = 'ARCHIVED'))
              AND r.id = #{reportId}
            LIMIT 1
            """)
    InterviewReportEntity findPublicReportById(@Param("reportId") Long reportId,
                                                @Param("sanitizationRevision") int sanitizationRevision);

    @Select("""
            SELECT r.*
            FROM interview_report r
            JOIN interview_asset a ON a.id = r.asset_id AND a.case_id = r.case_id
            JOIN interview_case c ON c.id = r.case_id AND c.deleted = 0
            LEFT JOIN interview_session s ON s.id = a.session_id AND s.deleted = 0
            JOIN interview_report_task t ON t.id = r.task_id AND t.report_id = r.id
            WHERE r.task_id IS NOT NULL
              AND r.source_content_hash = a.content_hash
              AND r.source_revision = a.content_revision
              AND r.template_version IS NOT NULL
              AND t.status = 'SUCCEEDED'
              AND t.source_content_hash = a.content_hash
              AND t.source_revision = a.content_revision
              AND t.source_sanitization_revision = a.sanitization_revision
              AND t.template_version = r.template_version
              AND t.input_snapshot_hash = r.input_snapshot_hash
              AND a.visibility = 'PUBLISHED'
              AND a.archive_grade IN ('L2', 'L3')
              AND a.sanitization_revision >= #{sanitizationRevision}
              AND a.deleted = 0
              AND a.content_hash IS NOT NULL
              AND SHA2(a.content, 256) = a.content_hash
              AND ((a.session_id IS NULL AND a.source = 'UPLOAD')
                   OR (s.id IS NOT NULL AND s.case_id = a.case_id AND s.status = 'ARCHIVED'))
              AND a.id = #{assetId}
            ORDER BY r.id DESC
            LIMIT 1
            """)
    InterviewReportEntity findPublicReportByAssetId(@Param("assetId") Long assetId,
                                                     @Param("sanitizationRevision") int sanitizationRevision);

    @Select("""
            SELECT COUNT(*)
            FROM interview_report r
            JOIN interview_asset a ON a.id = r.asset_id AND a.case_id = r.case_id
            JOIN interview_case c ON c.id = r.case_id AND c.deleted = 0
            LEFT JOIN interview_session s ON s.id = a.session_id AND s.deleted = 0
            JOIN interview_report_task t ON t.id = r.task_id AND t.report_id = r.id
            WHERE r.task_id IS NOT NULL
              AND r.source_content_hash = a.content_hash
              AND r.source_revision = a.content_revision
              AND t.status = 'SUCCEEDED'
              AND t.source_content_hash = a.content_hash
              AND t.source_revision = a.content_revision
              AND t.source_sanitization_revision = a.sanitization_revision
              AND t.template_version = r.template_version
              AND t.input_snapshot_hash = r.input_snapshot_hash
              AND a.visibility = 'PUBLISHED'
              AND a.archive_grade IN ('L2', 'L3')
              AND a.sanitization_revision >= #{sanitizationRevision}
              AND a.deleted = 0
              AND a.content_hash IS NOT NULL
              AND SHA2(a.content, 256) = a.content_hash
              AND ((a.session_id IS NULL AND a.source = 'UPLOAD')
                   OR (s.id IS NOT NULL AND s.case_id = a.case_id AND s.status = 'ARCHIVED'))
              AND r.report_json LIKE CONCAT('%', #{keyword}, '%')
            """)
    long countPublicReports(@Param("keyword") String keyword,
                            @Param("sanitizationRevision") int sanitizationRevision);

    @Select("""
            SELECT r.*
            FROM interview_report r
            JOIN interview_asset a ON a.id = r.asset_id AND a.case_id = r.case_id
            JOIN interview_case c ON c.id = r.case_id AND c.deleted = 0
            LEFT JOIN interview_session s ON s.id = a.session_id AND s.deleted = 0
            JOIN interview_report_task t ON t.id = r.task_id AND t.report_id = r.id
            WHERE r.task_id IS NOT NULL
              AND r.source_content_hash = a.content_hash
              AND r.source_revision = a.content_revision
              AND t.status = 'SUCCEEDED'
              AND t.source_content_hash = a.content_hash
              AND t.source_revision = a.content_revision
              AND t.source_sanitization_revision = a.sanitization_revision
              AND t.template_version = r.template_version
              AND t.input_snapshot_hash = r.input_snapshot_hash
              AND a.visibility = 'PUBLISHED'
              AND a.archive_grade IN ('L2', 'L3')
              AND a.sanitization_revision >= #{sanitizationRevision}
              AND a.deleted = 0
              AND a.content_hash IS NOT NULL
              AND SHA2(a.content, 256) = a.content_hash
              AND ((a.session_id IS NULL AND a.source = 'UPLOAD')
                   OR (s.id IS NOT NULL AND s.case_id = a.case_id AND s.status = 'ARCHIVED'))
              AND r.report_json LIKE CONCAT('%', #{keyword}, '%')
            ORDER BY r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<InterviewReportEntity> selectPublicReports(@Param("keyword") String keyword,
                                                     @Param("sanitizationRevision") int sanitizationRevision,
                                                     @Param("limit") int limit,
                                                     @Param("offset") int offset);

    @Select("""
            <script>
            SELECT r.*
            FROM interview_report r
            JOIN interview_asset a ON a.id = r.asset_id AND a.case_id = r.case_id
            JOIN interview_case c ON c.id = r.case_id AND c.deleted = 0
            LEFT JOIN interview_session s ON s.id = a.session_id AND s.deleted = 0
            JOIN interview_report_task t ON t.id = r.task_id AND t.report_id = r.id
            WHERE r.task_id IS NOT NULL
              AND r.source_content_hash = a.content_hash
              AND r.source_revision = a.content_revision
              AND t.status = 'SUCCEEDED'
              AND t.source_content_hash = a.content_hash
              AND t.source_revision = a.content_revision
              AND t.source_sanitization_revision = a.sanitization_revision
              AND t.template_version = r.template_version
              AND t.input_snapshot_hash = r.input_snapshot_hash
              AND a.sanitization_revision &gt;= 1
              AND a.deleted = 0
              AND SHA2(a.content, 256) = a.content_hash
              AND ((a.session_id IS NULL AND a.source = 'UPLOAD')
                   OR (s.id IS NOT NULL AND s.case_id = a.case_id AND s.status = 'ARCHIVED'))
              AND r.report_json LIKE CONCAT('%', #{keyword}, '%')
            <if test="caseIds != null">
              <choose>
                <when test="caseIds.isEmpty()">AND 1 = 0</when>
                <otherwise>
                  AND r.case_id IN
                  <foreach collection="caseIds" item="caseId" open="(" separator="," close=")">
                    #{caseId}
                  </foreach>
                </otherwise>
              </choose>
            </if>
            ORDER BY r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<InterviewReportEntity> selectCurrentReports(@Param("keyword") String keyword,
                                                      @Param("caseIds") List<Long> caseIds,
                                                      @Param("limit") int limit,
                                                      @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM interview_report r
            JOIN interview_asset a ON a.id = r.asset_id AND a.case_id = r.case_id
            JOIN interview_case c ON c.id = r.case_id AND c.deleted = 0
            LEFT JOIN interview_session s ON s.id = a.session_id AND s.deleted = 0
            JOIN interview_report_task t ON t.id = r.task_id AND t.report_id = r.id
            WHERE r.task_id IS NOT NULL
              AND r.source_content_hash = a.content_hash
              AND r.source_revision = a.content_revision
              AND t.status = 'SUCCEEDED'
              AND t.source_content_hash = a.content_hash
              AND t.source_revision = a.content_revision
              AND t.source_sanitization_revision = a.sanitization_revision
              AND t.template_version = r.template_version
              AND t.input_snapshot_hash = r.input_snapshot_hash
              AND a.sanitization_revision &gt;= 1
              AND a.deleted = 0
              AND SHA2(a.content, 256) = a.content_hash
              AND ((a.session_id IS NULL AND a.source = 'UPLOAD')
                   OR (s.id IS NOT NULL AND s.case_id = a.case_id AND s.status = 'ARCHIVED'))
              AND r.report_json LIKE CONCAT('%', #{keyword}, '%')
            <if test="caseIds != null">
              <choose>
                <when test="caseIds.isEmpty()">AND 1 = 0</when>
                <otherwise>
                  AND r.case_id IN
                  <foreach collection="caseIds" item="caseId" open="(" separator="," close=")">
                    #{caseId}
                  </foreach>
                </otherwise>
              </choose>
            </if>
            </script>
            """)
    long countCurrentReports(@Param("keyword") String keyword, @Param("caseIds") List<Long> caseIds);
}
