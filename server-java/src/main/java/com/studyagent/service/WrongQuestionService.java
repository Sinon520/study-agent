package com.studyagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.studyagent.entity.WrongQuestion;
import com.studyagent.model.dto.WrongQuestionQuery;
import com.studyagent.model.dto.WrongQuestionRequest;
import com.studyagent.model.vo.PageResult;
import com.studyagent.model.vo.WrongQuestionExportVO;
import com.studyagent.model.vo.WrongQuestionVO;

import java.util.List;

/**
 * 错题 业务接口。
 */
public interface WrongQuestionService extends IService<WrongQuestion> {

    /** 分页查询（科目 / 掌握度 / 标签 / 关键字 / 时间范围），返回结果带标签 */
    PageResult<WrongQuestionVO> pageQuestions(Long userId, WrongQuestionQuery query);

    /** 详情（带标签） */
    WrongQuestionVO getDetail(Long userId, Long id);

    /** 新增错题（含标签写入与标签关联） */
    WrongQuestionVO create(Long userId, WrongQuestionRequest request);

    /** 修改错题（含标签的全量替换） */
    WrongQuestionVO update(Long userId, Long id, WrongQuestionRequest request);

    /** 删除单条（逻辑删 + 清理标签关联） */
    void delete(Long userId, Long id);

    /** 批量删除，返回实际删除条数 */
    int batchDelete(Long userId, List<Long> ids);

    /** 批量标记掌握度，返回实际更新条数 */
    int batchUpdateMastery(Long userId, List<Long> ids, String mastery);

    /** 导出用的数据（不分页，但有上限保护） */
    List<WrongQuestionExportVO> listForExport(Long userId, WrongQuestionQuery query);

    /** 当前用户的标签列表（去重，按使用次数降序） */
    List<String> listTags(Long userId);

    /** 取当前用户拥有的错题，不存在或不属于该用户时抛异常 */
    WrongQuestion requireOwned(Long userId, Long id);
}
