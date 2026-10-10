package com.studyagent.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 * <p>注意：{@code @MapperScan} 放在启动类 {@code StudyAgentApplication} 上，这里不再重复声明。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页最大条数，防止前端传超大 size 拖垮数据库 */
    private static final long MAX_LIMIT = 500L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 分页插件（必须在其他内部拦截器之前添加）
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限 500，超出按 500 处理
        pagination.setMaxLimit(MAX_LIMIT);
        // 溢出总页数后是否进行处理（false 表示返回空数据而不是回到第一页）
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // 防止全表更新与删除（面试点：误操作保护）
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
