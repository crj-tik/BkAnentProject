package com.bkanent.agent.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记基础工具。
 *
 * <p>被该注解标记的工具会在系统启动时自动注册到 ChatClient 默认工具列表中。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentBaseTool {
}
