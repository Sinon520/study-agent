package com.studyagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.entity.WrongQuestion;
import com.studyagent.entity.WrongQuestionTag;
import com.studyagent.entity.WrongQuestionTagRel;
import com.studyagent.mapper.WrongQuestionMapper;
import com.studyagent.mapper.WrongQuestionTagMapper;
import com.studyagent.model.dto.WrongQuestionQuery;
import com.studyagent.model.dto.WrongQuestionRequest;
import com.studyagent.model.vo.PageResult;
import com.studyagent.model.vo.WrongQuestionExportVO;
import com.studyagent.model.vo.WrongQuestionVO;
import com.studyagent.mq.MessagePublisher;
import com.studyagent.mq.MqTopics;
import com.studyagent.mq.message.WrongClassifyMessage;
import com.studyagent.service.WrongQuestionService;
import com.studyagent.service.WrongQuestionTagRelService;
import com.studyagent.service.WrongQuestionTagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 错题 业务实现。
 *
 * <h3>三个值得说的实现细节</h3>
 * <ol>
 *   <li><b>避免 N+1</b>：列表页的标签不是逐条查，而是拿到本页所有错题 ID 后
 *       一次性 {@code IN} 查询再在内存里分派。</li>
 *   <li><b>标签筛选走 Mapper 的子查询</b>：先查出符合条件的错题 ID 集合，
 *       再加 {@code IN} 条件到主查询。相比 join 后再分页（会在内存里对笛卡尔积分页），
 *       这种"先筛 ID 再查主体"的方式分页总数才准确。</li>
 *   <li><b>导出有上限</b>：Excel 导出不分页，但必须设上限（{@link #EXPORT_LIMIT}），
 *       否则用户筛出全库错题会让 JVM 直接 OOM。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WrongQuestionServiceImpl extends ServiceImpl<WrongQuestionMapper, WrongQuestion>
        implements WrongQuestionService {

    /** 单次导出上限：EasyExcel 虽然用 SXSSF 流式写，但查出来仍占内存 */
    public static final int EXPORT_LIMIT = 10000;

    private static final List<String> VALID_MASTERY = List.of("未掌握", "复习中", "已掌握");

    private final WrongQuestionTagService tagService;
    private final WrongQuestionTagRelService tagRelService;
    private final WrongQuestionTagMapper tagMapper;
    private final MessagePublisher messagePublisher;

    // ------------------------------------------------------------------ 查询

    @Override
    public PageResult<WrongQuestionVO> pageQuestions(Long userId, WrongQuestionQuery query) {
        Page<WrongQuestion> page = query.toPage();

        // 标签筛选：先取满足全部标签的错题 ID。空集合会导致 IN () 语法错误，直接返回空页。
        List<Long> tagFilteredIds = null;
        if (query.getTags() != null && !query.getTags().isEmpty()) {
            List<String> names = query.getTags().stream().filter(StringUtils::hasText).distinct().toList();
            if (names.isEmpty()) {
                return PageResult.of(List.of(), 0L, page.getCurrent(), page.getSize());
            }
            tagFilteredIds = tagMapper.selectQuestionIdsByTags(userId, names, names.size());
            if (tagFilteredIds.isEmpty()) {
                return PageResult.of(List.of(), 0L, page.getCurrent(), page.getSize());
            }
        }

        var wrapper = Wrappers.<WrongQuestion>lambdaQuery()
                .eq(WrongQuestion::getUserId, userId)
                .eq(StringUtils.hasText(query.getSubject()), WrongQuestion::getSubject, query.getSubject())
                .eq(StringUtils.hasText(query.getMastery()), WrongQuestion::getMastery, query.getMastery())
                .eq(StringUtils.hasText(query.getQuestionType()), WrongQuestion::getQuestionType, query.getQuestionType())
                .eq(query.getDifficulty() != null, WrongQuestion::getDifficulty, query.getDifficulty())
                .in(tagFilteredIds != null, WrongQuestion::getId, tagFilteredIds)
                .ge(query.getStartDate() != null, WrongQuestion::getCreateTime,
                        query.getStartDate() == null ? null : query.getStartDate().atStartOfDay())
                .le(query.getEndDate() != null, WrongQuestion::getCreateTime,
                        query.getEndDate() == null ? null : query.getEndDate().atTime(23, 59, 59))
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(WrongQuestion::getContent, query.getKeyword())
                        .or().like(WrongQuestion::getAnswer, query.getKeyword())
                        .or().like(WrongQuestion::getAnalysis, query.getKeyword()));

        applyOrder(wrapper, query);

        Page<WrongQuestion> result = page(page, wrapper);

        List<WrongQuestionVO> records = result.getRecords().stream().map(WrongQuestionVO::from).toList();
        fillTags(records);

        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize(), result.getPages());
    }

    /**
     * 排序字段白名单。
     * <p>绝不能把前端传来的字符串直接拼进 order by（SQL 注入），
     * 所以这里用显式分支把 {@code sortBy} 映射成确定的列。</p>
     */
    private void applyOrder(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WrongQuestion> wrapper,
                            WrongQuestionQuery query) {
        boolean asc = Boolean.TRUE.equals(query.getAsc());
        String sortBy = query.getSortBy() == null ? "createTime" : query.getSortBy();
        switch (sortBy) {
            case "difficulty" -> wrapper.orderBy(true, asc, WrongQuestion::getDifficulty);
            case "mastery" -> wrapper.orderBy(true, asc, WrongQuestion::getMastery);
            case "id" -> wrapper.orderBy(true, asc, WrongQuestion::getId);
            default -> wrapper.orderBy(true, asc, WrongQuestion::getCreateTime);
        }
        // 加一个确定性兜底排序，避免同一时间戳下分页结果抖动（翻页出现重复/漏项）
        wrapper.orderByDesc(WrongQuestion::getId);
    }

    @Override
    public WrongQuestionVO getDetail(Long userId, Long id) {
        WrongQuestion question = requireOwned(userId, id);
        WrongQuestionVO vo = WrongQuestionVO.from(question);
        fillTags(List.of(vo));
        return vo;
    }

    @Override
    public List<String> listTags(Long userId) {
        List<Map<String, Object>> rows = tagMapper.listTagsWithCount(userId);
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null) {
                names.add(String.valueOf(name));
            }
        }
        return names;
    }

    @Override
    public List<WrongQuestionExportVO> listForExport(Long userId, WrongQuestionQuery query) {
        // 导出不分页，但强制用上限覆盖 pageSize，并捕获超限情况给出明确提示
        query.setPageNum(1);
        query.setPageSize(EXPORT_LIMIT);

        PageResult<WrongQuestionVO> pageResult = pageQuestions(userId, query);
        if (pageResult.getTotal() != null && pageResult.getTotal() > EXPORT_LIMIT) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "导出数据量 " + pageResult.getTotal() + " 条，超过单次上限 " + EXPORT_LIMIT
                            + " 条。请用科目/掌握度/时间范围缩小范围后分批导出。");
        }

        List<WrongQuestionExportVO> rows = new ArrayList<>(pageResult.getRecords().size());
        int index = 1;
        for (WrongQuestionVO vo : pageResult.getRecords()) {
            rows.add(WrongQuestionExportVO.from(vo, index++));
        }
        log.info("错题导出 userId={} 条数={}", userId, rows.size());
        return rows;
    }

    // ------------------------------------------------------------------ 写入

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WrongQuestionVO create(Long userId, WrongQuestionRequest request) {
        WrongQuestion question = new WrongQuestion();
        question.setUserId(userId);
        applyRequest(question, request);
        save(question);

        replaceTags(userId, question.getId(), request.getTags());
        log.info("新增错题 id={} userId={} subject={}", question.getId(), userId, question.getSubject());

        // 异步归类（AI 打标签）：事务提交后再发消息，详见 publishClassifyAfterCommit 的注释
        publishClassifyAfterCommit(question);

        return getDetail(userId, question.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WrongQuestionVO update(Long userId, Long id, WrongQuestionRequest request) {
        WrongQuestion question = requireOwned(userId, id);
        applyRequest(question, request);
        updateById(question);

        // tags 为 null 表示"不改标签"；空列表表示"清空标签"
        if (request.getTags() != null) {
            replaceTags(userId, id, request.getTags());
        }
        log.info("修改错题 id={} userId={}", id, userId);
        return getDetail(userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long id) {
        requireOwned(userId, id);
        // 关联表是物理删除：它是纯粹的映射关系，没有保留价值，
        // 而且错题已经逻辑删了，留着关联只会让标签统计虚高
        tagRelService.remove(Wrappers.<WrongQuestionTagRel>lambdaQuery()
                .eq(WrongQuestionTagRel::getWrongQuestionId, id));
        removeById(id);
        log.info("删除错题 id={} userId={}", id, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(Long userId, List<Long> ids) {
        List<Long> owned = filterOwnedIds(userId, ids);
        if (owned.isEmpty()) {
            return 0;
        }
        tagRelService.remove(Wrappers.<WrongQuestionTagRel>lambdaQuery()
                .in(WrongQuestionTagRel::getWrongQuestionId, owned));
        removeByIds(owned);
        log.info("批量删除错题 userId={} 请求={} 实际={}", userId, ids.size(), owned.size());
        return owned.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateMastery(Long userId, List<Long> ids, String mastery) {
        if (!VALID_MASTERY.contains(mastery)) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "掌握度不合法：" + mastery + "，可选 " + VALID_MASTERY);
        }
        List<Long> owned = filterOwnedIds(userId, ids);
        if (owned.isEmpty()) {
            return 0;
        }
        WrongQuestion patch = new WrongQuestion();
        patch.setMastery(mastery);
        update(patch, Wrappers.<WrongQuestion>lambdaUpdate()
                .eq(WrongQuestion::getUserId, userId)
                .in(WrongQuestion::getId, owned));
        log.info("批量标记掌握度 userId={} 条数={} mastery={}", userId, owned.size(), mastery);
        return owned.size();
    }

    @Override
    public WrongQuestion requireOwned(Long userId, Long id) {
        WrongQuestion question = getById(id);
        if (question == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "错题不存在或已被删除：" + id);
        }
        if (!question.getUserId().equals(userId)) {
            log.warn("水平越权拦截：userId={} 试图访问 wrongQuestionId={}（属于 userId={}）",
                    userId, id, question.getUserId());
            throw new BizException(Result.CODE_FORBIDDEN, "无权访问该错题");
        }
        return question;
    }

    // ------------------------------------------------------------------ 内部

    private void applyRequest(WrongQuestion question, WrongQuestionRequest request) {
        question.setSubject(request.getSubject().trim());
        question.setQuestionType(request.getQuestionType());
        question.setContent(request.getContent());
        question.setAnswer(request.getAnswer());
        question.setAnalysis(request.getAnalysis());
        question.setImageUrl(request.getImageUrl());
        question.setDifficulty(request.getDifficulty() == null ? 3 : request.getDifficulty());
        question.setMastery(StringUtils.hasText(request.getMastery()) ? request.getMastery() : "未掌握");
        question.setSource(request.getSource());
    }

    /**
     * 全量替换某道错题的标签。
     *
     * <p>不存在的标签自动创建（错题本场景下让用户先去建标签太重），
     * 用唯一键 {@code uk_user_name} 保证并发下不会建出重复标签。</p>
     */
    private void replaceTags(Long userId, Long questionId, List<String> tagNames) {
        tagRelService.remove(Wrappers.<WrongQuestionTagRel>lambdaQuery()
                .eq(WrongQuestionTagRel::getWrongQuestionId, questionId));

        if (tagNames == null || tagNames.isEmpty()) {
            return;
        }
        List<String> names = normalizeTagNames(tagNames);
        if (names.isEmpty()) {
            return;
        }

        Map<String, Long> tagIds = ensureTagIds(userId, names);
        List<WrongQuestionTagRel> rels = new ArrayList<>(names.size());
        for (String name : names) {
            Long tagId = tagIds.get(name);
            if (tagId == null) {
                continue;
            }
            WrongQuestionTagRel rel = new WrongQuestionTagRel();
            rel.setWrongQuestionId(questionId);
            rel.setTagId(tagId);
            rels.add(rel);
        }
        if (!rels.isEmpty()) {
            // 唯一键 uk_question_tag 让重复插入无害（幂等）
            tagRelService.saveBatch(rels, 100);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int appendTags(Long userId, Long questionId, List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return 0;
        }
        List<String> names = normalizeTagNames(tagNames);
        if (names.isEmpty()) {
            return 0;
        }

        Map<String, Long> tagIds = ensureTagIds(userId, names);

        // 幂等：MQ 至少一次投递，重复消费/重试都可能再跑一遍 ——
        // 先查已有关系、只插缺的；唯一键 uk_question_tag 作最终兜底
        Set<Long> linked = tagRelService.list(Wrappers.<WrongQuestionTagRel>lambdaQuery()
                        .eq(WrongQuestionTagRel::getWrongQuestionId, questionId))
                .stream()
                .map(WrongQuestionTagRel::getTagId)
                .collect(Collectors.toSet());

        List<WrongQuestionTagRel> rels = tagIds.values().stream()
                .filter(tagId -> !linked.contains(tagId))
                .map(tagId -> {
                    WrongQuestionTagRel rel = new WrongQuestionTagRel();
                    rel.setWrongQuestionId(questionId);
                    rel.setTagId(tagId);
                    return rel;
                })
                .toList();
        if (rels.isEmpty()) {
            return 0;
        }
        try {
            tagRelService.saveBatch(rels, 100);
        } catch (DuplicateKeyException e) {
            // 并发（如重复消费的两条消息同时进来）：对方先插成功，本次忽略即可
            log.warn("标签关系并发重复，已忽略 questionId={}", questionId);
            return 0;
        }
        return rels.size();
    }

    /** 标签名清洗：去空、去重、单题上限 20 个（防止前端刷出几百个标签） */
    private List<String> normalizeTagNames(List<String> tagNames) {
        return tagNames.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .limit(20)
                .toList();
    }

    /**
     * 确保一组标签存在（不存在则创建），返回 {@code name -> tagId}。
     *
     * <p>并发下用唯一键 {@code uk_user_name} 兜底：插入撞键时把已有记录查回来，
     * 而不是让整个事务失败（和用户手输标签走的是同一条路）。</p>
     */
    private Map<String, Long> ensureTagIds(Long userId, List<String> names) {
        Map<String, Long> existing = new HashMap<>();
        for (WrongQuestionTag tag : tagMapper.selectByNames(userId, names)) {
            existing.put(tag.getName(), tag.getId());
        }
        for (String name : names) {
            if (existing.containsKey(name)) {
                continue;
            }
            WrongQuestionTag tag = new WrongQuestionTag();
            tag.setUserId(userId);
            tag.setName(name);
            try {
                tagService.save(tag);
            } catch (DuplicateKeyException e) {
                // 并发下另一个请求刚建了同名标签，查回来即可
                List<WrongQuestionTag> again = tagMapper.selectByNames(userId, List.of(name));
                if (again.isEmpty()) {
                    continue;
                }
                tag = again.get(0);
            }
            existing.put(name, tag.getId());
        }
        return existing;
    }

    /**
     * 事务提交后再发"错题归类"消息（Day 23）。
     *
     * <h3>为什么必须等提交后</h3>
     * 消息一发出去，消费者就可能立刻拿 wrongQuestionId 回查数据库 ——
     * 如果此时本事务还没提交，消费者查不到这条错题，归类直接丢失。
     * 用 {@link TransactionSynchronization#afterCommit()} 把发送挂到提交之后；
     * 非事务环境（如单测直调）则即时发送。
     *
     * <h3>失败语义：宁丢标注，不丢错题</h3>
     * 发送失败（Broker 挂了等）只告警不抛异常 —— 错题本的核心价值是"把题记下来"，
     * 自动打标是锦上添花，不能因为 MQ 不可用导致用户存不了错题。
     * 代价是这条链路从"至少一次"退化为"至多一次"；要严格保证应上
     * RocketMQ 事务消息（半消息）或本地消息表 + 定时补投 ——
     * 个人项目里"漏标可重存/可手动补"更划算，取舍写在这里供面试展开。
     */
    private void publishClassifyAfterCommit(WrongQuestion question) {
        Runnable publish = () -> {
            try {
                messagePublisher.send(MqTopics.WRONG_CLASSIFY, new WrongClassifyMessage(
                        question.getId(), question.getContent(), question.getSubject(),
                        MessagePublisher.currentTraceId()));
            } catch (Exception e) {
                log.warn("发送错题归类消息失败 wrongQuestionId={}（不影响错题保存）: {}",
                        question.getId(), e.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    /** 一次性给一批 VO 补标签，避免逐条查询的 N+1 */
    private void fillTags(List<WrongQuestionVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream().map(WrongQuestionVO::getId).toList();
        Map<Long, List<String>> tagMap = new HashMap<>();
        for (Map<String, Object> row : tagMapper.listTagsByQuestionIds(ids)) {
            Object qid = row.get("wrongQuestionId");
            Object name = row.get("name");
            if (qid == null || name == null) {
                continue;
            }
            tagMap.computeIfAbsent(Long.valueOf(String.valueOf(qid)), k -> new ArrayList<>())
                    .add(String.valueOf(name));
        }
        for (WrongQuestionVO vo : records) {
            vo.setTags(tagMap.getOrDefault(vo.getId(), new ArrayList<>()));
        }
    }

    /** 过滤出确实属于该用户的 ID（批量操作必须防越权） */
    private List<Long> filterOwnedIds(Long userId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        List<WrongQuestion> owned = list(Wrappers.<WrongQuestion>lambdaQuery()
                .eq(WrongQuestion::getUserId, userId)
                .in(WrongQuestion::getId, distinct)
                .select(WrongQuestion::getId));
        return owned.stream().map(WrongQuestion::getId).toList();
    }

    /** 供子类/测试观察的当前时间（避免直接 new Date 造成难测） */
    protected LocalDateTime now() {
        return LocalDateTime.now();
    }
}
