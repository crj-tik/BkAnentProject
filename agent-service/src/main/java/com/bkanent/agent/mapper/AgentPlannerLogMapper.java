package com.bkanent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bkanent.agent.entity.AgentPlannerLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Planner 会话日志 Mapper。
 */
@Mapper
public interface AgentPlannerLogMapper extends BaseMapper<AgentPlannerLogEntity> {
}
