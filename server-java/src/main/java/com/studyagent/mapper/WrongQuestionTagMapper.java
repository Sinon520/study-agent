package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.WrongQuestionTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 错题标签 Mapper。
 */
@Mapper
public interface WrongQuestionTagMapper extends BaseMapper<WrongQuestionTag> {

    /**
     * 按使用次数统计用户的标签（筛选栏排序用）。
     *
     * <p>用 {@code LEFT JOIN} 而不是 {@code INNER JOIN}：新加但还没被引用过的标签
     * 也应该出现在筛选栏里，否则用户建了标签却找不到。</p>
     *
     * @return 每行 {@code {name, questionCount}}
     */
    @Select("""
            SELECT t.name AS name,
                   COUNT(r.wrong_question_id) AS questionCount
              FROM wrong_question_tag t
              LEFT JOIN wrong_question_tag_rel r ON r.tag_id = t.id
             WHERE t.user_id = #{userId}
             GROUP BY t.id, t.name
             ORDER BY questionCount DESC, t.id ASC
            """)
    List<Map<String, Object>> listTagsWithCount(@Param("userId") Long userId);

    /**
     * 按标签名批量查标签 ID（新增错题写关联时用，避免 N 次单查）。
     */
    @Select("""
            <script>
            SELECT id, name FROM wrong_question_tag
             WHERE user_id = #{userId}
               AND name IN
               <foreach collection="names" item="name" open="(" separator="," close=")">#{name}</foreach>
            </script>
            """)
    List<WrongQuestionTag> selectByNames(@Param("userId") Long userId, @Param("names") List<String> names);

    /**
     * 查某批错题各自的标签名（列表页一次性补标签，避免 N+1 查询）。
     *
     * @return 每行 {@code {wrongQuestionId, name}}
     */
    @Select("""
            <script>
            SELECT r.wrong_question_id AS wrongQuestionId, t.name AS name
              FROM wrong_question_tag_rel r
              JOIN wrong_question_tag t ON t.id = r.tag_id
             WHERE r.wrong_question_id IN
               <foreach collection="questionIds" item="qid" open="(" separator="," close=")">#{qid}</foreach>
             ORDER BY t.id ASC
            </script>
            """)
    List<Map<String, Object>> listTagsByQuestionIds(@Param("questionIds") List<Long> questionIds);

    /**
     * 按标签筛选错题 ID（多个标签取交集）。
     *
     * <p>交集用 {@code GROUP BY ... HAVING COUNT(DISTINCT name) = n} 实现，
     * 比"每个标签查一次再在内存里取交集"少 n-1 次数据库往返。</p>
     */
    @Select("""
            <script>
            SELECT r.wrong_question_id
              FROM wrong_question_tag_rel r
              JOIN wrong_question_tag t ON t.id = r.tag_id
             WHERE t.user_id = #{userId}
               AND t.name IN
               <foreach collection="names" item="name" open="(" separator="," close=")">#{name}</foreach>
             GROUP BY r.wrong_question_id
            HAVING COUNT(DISTINCT t.name) = #{expected}
            </script>
            """)
    List<Long> selectQuestionIdsByTags(@Param("userId") Long userId,
                                       @Param("names") List<String> names,
                                       @Param("expected") int expected);
}
