package com.example.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agent.entity.JobPositionEntity;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

public interface JobPositionMapper extends BaseMapper<JobPositionEntity> {
    @Select("SELECT * FROM job_position WHERE id = #{id} FOR UPDATE")
    JobPositionEntity lockById(@Param("id") Long id);
}
