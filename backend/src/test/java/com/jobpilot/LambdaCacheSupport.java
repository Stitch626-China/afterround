package com.jobpilot;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/** 纯单元测试没有 Spring/MyBatis 上下文，需要手动初始化 Lambda 字段缓存。 */
public final class LambdaCacheSupport {

    private LambdaCacheSupport() {
    }

    public static void init(Class<?>... entities) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : entities) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
