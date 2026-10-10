package com.studyagent.model.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 分页查询基类。
 *
 * <p>所有列表查询都继承它，统一 {@code pageNum/pageSize} 语义与边界收敛。
 * 边界处理放在 {@link #toPage()} 里而不是散落在各个 Service，
 * 这样"pageNum=0 / pageSize=99999 会不会炸"这种问题只需要在一处保证。</p>
 */
@Data
@Schema(description = "分页查询参数")
public class PageQuery {

    /** 默认页码 */
    public static final int DEFAULT_PAGE_NUM = 1;
    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;
    /** 每页最大条数：防止前端传 999999 把库拖死 */
    public static final int MAX_PAGE_SIZE = 200;

    @Schema(description = "页码，从 1 开始；小于 1 时按 1 处理", example = "1", defaultValue = "1")
    private Integer pageNum = DEFAULT_PAGE_NUM;

    @Schema(description = "每页条数，1~200；超出范围会被收敛", example = "10", defaultValue = "10")
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    /**
     * 转成 MyBatis-Plus 的 Page 对象，并做边界收敛。
     *
     * <p>为什么必须收敛：{@code pageNum=0} 在 MySQL 里会生成
     * {@code LIMIT -10, 10} 这种非法 SQL 直接报错；
     * {@code pageSize=1000000} 则会把内存打满。这两个都是分页接口最常见的线上事故。</p>
     */
    public <T> Page<T> toPage() {
        int num = (pageNum == null || pageNum < 1) ? DEFAULT_PAGE_NUM : pageNum;
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : pageSize;
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
        return new Page<>(num, size);
    }
}
