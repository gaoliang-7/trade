package com.tradeexample.common.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册仓储Mapper与乐观锁插件；业务事务和更新行数检查仍由Service负责。 */
@Configuration
@MapperScan("com.tradeexample.wms.mapper")
public class MybatisPlusConfig {

    /**
     * 为支持的实体更新方法添加版本检查，需要实体的@Version字段携带非空旧版本。
     * 自定义SQL必须自行保证版本条件；更新返回0不会自动抛出业务异常。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        interceptor.addInnerInterceptor(
                new OptimisticLockerInnerInterceptor()
        );

        return interceptor;
    }

}
