package com.example.agent.recruitment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agent.recruitment.entity.JobPositionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface JobPositionMapper extends BaseMapper<JobPositionEntity> {
    @Select("SELECT * FROM job_position WHERE id = #{id} FOR UPDATE")
    JobPositionEntity lockById(@Param("id") Long id);
}
