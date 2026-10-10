package com.studyagent.model.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 分页结果包装。
 *
 * <p>不直接把 MyBatis-Plus 的 {@code Page} 返回给前端：它的 {@code records/total/size/current}
 * 之外还带着 {@code orders/optimizeCountSql/searchCount} 等内部字段，
 * 既污染 Swagger 文档，也把分页实现的细节泄露给了调用方。</p>
 *
 * @param <T> 列表元素类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分页结果")
public class PageResult<T> {

    @Schema(description = "当前页数据")
    private List<T> records = Collections.emptyList();

    @Schema(description = "总记录数", example = "137")
    private Long total = 0L;

    @Schema(description = "当前页码", example = "1")
    private Long pageNum = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long pageSize = 10L;

    @Schema(description = "总页数", example = "14")
    private Long pages = 0L;

    /** 由 MyBatis-Plus 的 IPage 转换而来（元素类型相同） */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /** 由 MyBatis-Plus 的 IPage 转换而来，并对每个元素做类型转换（Entity → VO） */
    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        List<T> records = page.getRecords() == null
                ? Collections.emptyList()
                : page.getRecords().stream().map(mapper).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /** 供"先查 ID 列表再补数据"这类场景手工构造 */
    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        long pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(records, total, pageNum, pageSize, pages);
    }
}
