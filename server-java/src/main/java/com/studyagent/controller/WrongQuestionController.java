package com.studyagent.controller;

import com.alibaba.excel.EasyExcel;
import com.studyagent.common.Result;
import com.studyagent.model.dto.BatchRequest;
import com.studyagent.model.dto.WrongQuestionQuery;
import com.studyagent.model.dto.WrongQuestionRequest;
import com.studyagent.model.vo.PageResult;
import com.studyagent.model.vo.WrongQuestionExportVO;
import com.studyagent.model.vo.WrongQuestionVO;
import com.studyagent.security.UserContext;
import com.studyagent.service.WrongQuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 错题本接口。
 */
@Slf4j
@RestController
@RequestMapping("/wrong-question")
@RequiredArgsConstructor
@Tag(name = "04-错题本", description = "错题 CRUD、标签筛选、批量操作、Excel 导出")
@SecurityRequirement(name = "bearerAuth")
public class WrongQuestionController {

    private final WrongQuestionService wrongQuestionService;

    @GetMapping("/page")
    @Operation(summary = "分页查询错题",
            description = "支持科目 / 掌握度 / 题型 / 难度 / 标签 / 关键字 / 时间范围筛选，返回结果带标签")
    public Result<PageResult<WrongQuestionVO>> page(WrongQuestionQuery query) {
        return Result.ok(wrongQuestionService.pageQuestions(UserContext.userId(), query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "错题详情")
    public Result<WrongQuestionVO> detail(@PathVariable Long id) {
        return Result.ok(wrongQuestionService.getDetail(UserContext.userId(), id));
    }

    @PostMapping
    @Operation(summary = "新增错题", description = "标签不存在会自动创建")
    public Result<WrongQuestionVO> create(@Valid @RequestBody WrongQuestionRequest request) {
        return Result.ok("添加成功", wrongQuestionService.create(UserContext.userId(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改错题",
            description = "请求里 tags 为 null 表示不改标签；传空数组表示清空标签")
    public Result<WrongQuestionVO> update(@PathVariable Long id,
                                         @Valid @RequestBody WrongQuestionRequest request) {
        return Result.ok("修改成功", wrongQuestionService.update(UserContext.userId(), id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除错题", description = "逻辑删除，同时清理标签关联")
    public Result<Void> delete(@PathVariable Long id) {
        wrongQuestionService.delete(UserContext.userId(), id);
        return Result.ok("删除成功", null);
    }

    @DeleteMapping("/batch")
    @Operation(summary = "批量删除", description = "只删属于当前用户的错题，返回实际删除条数")
    public Result<Integer> batchDelete(@Valid @RequestBody BatchRequest request) {
        int count = wrongQuestionService.batchDelete(UserContext.userId(), request.getIds());
        return Result.ok("已删除 " + count + " 条", count);
    }

    @PutMapping("/batch/mastery")
    @Operation(summary = "批量标记掌握度", description = "未掌握/复习中/已掌握")
    public Result<Integer> batchMastery(@Valid @RequestBody BatchRequest request) {
        int count = wrongQuestionService.batchUpdateMastery(
                UserContext.userId(), request.getIds(), request.getMastery());
        return Result.ok("已更新 " + count + " 条", count);
    }

    @GetMapping("/tags")
    @Operation(summary = "标签列表", description = "当前用户的标签，按被引用次数降序，筛选栏用")
    public Result<List<String>> tags() {
        return Result.ok(wrongQuestionService.listTags(UserContext.userId()));
    }

    /**
     * Excel 导出。
     *
     * <p>两个实现要点：</p>
     * <ol>
     *   <li>这是<b>唯一不走统一返回体</b>的接口：它直接写 {@code application/vnd.ms-excel} 文件流。
     *       所以异常必须在写流之前抛出（否则响应头已提交，改不了状态码，前端会拿到一个损坏的 xlsx）。</li>
     *   <li>文件名用 RFC 5987 的 {@code filename*=UTF-8''...} 形式，中文名在浏览器里才不会乱码。</li>
     * </ol>
     */
    @GetMapping("/export")
    @Operation(summary = "导出错题 Excel",
            description = "按当前筛选条件导出，单次上限 10000 条。直接返回文件流，不走统一返回体")
    public void export(WrongQuestionQuery query, HttpServletResponse response) throws IOException {
        Long userId = UserContext.userId();
        // 先取数据再动响应头：数据量超限时 BizException 会被全局异常处理成 JSON，响应头还是干净的
        List<WrongQuestionExportVO> rows = wrongQuestionService.listForExport(userId, query);

        String fileName = "错题本_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        // 让前端能读到文件名（跨域下默认只暴露简单响应头）
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

        EasyExcel.write(response.getOutputStream(), WrongQuestionExportVO.class)
                .sheet("错题本")
                .doWrite(rows);

        log.info("错题导出完成 userId={} 条数={}", userId, rows.size());
    }

    @GetMapping("/mastery-options")
    @Operation(summary = "掌握度选项", description = "前端下拉框用，避免把枚举硬编码在前端")
    public Result<List<String>> masteryOptions() {
        return Result.ok(List.of("未掌握", "复习中", "已掌握"));
    }
}
