package com.example.agent.rag;

import com.example.agent.entity.LangGraphCheckpointEntity;
import com.example.agent.mapper.LangGraphCheckpointMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;

import java.util.*;

/**
 * 基于 MySQL 的 LangGraph4j checkpoint 保存器（M9）。
 *
 * <p>实现 {@link BaseCheckpointSaver} 接口，将 Checkpoint 的 state Map 序列化为 JSON
 * 存入 {@code langgraph_checkpoint} 表。进程重启后可从 MySQL 恢复 checkpoint 链，
 * 支持多 Agent 任务的断点恢复。
 *
 * <p>注意：state Map 的 value 可能是 String/Integer/Boolean/List 等，
 * 用 Jackson 序列化为 JSON；反序列化时 List 会变成 {@code List<LinkedHashMap>}，
 * 调用方（MultiAgentServiceImpl）需自行处理 VectorMatch 的类型转换。
 */
@Slf4j
public class MysqlCheckpointSaver implements BaseCheckpointSaver {

    private final LangGraphCheckpointMapper mapper;
    private final ObjectMapper objectMapper;

    public MysqlCheckpointSaver(LangGraphCheckpointMapper mapper) {
        this.mapper = mapper;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        String threadId = threadId(config);
        try {
            List<LangGraphCheckpointEntity> entities = mapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LangGraphCheckpointEntity>()
                            .eq(LangGraphCheckpointEntity::getThreadId, threadId)
                            .orderByAsc(LangGraphCheckpointEntity::getCreatedAt));
            List<Checkpoint> checkpoints = new ArrayList<>();
            for (LangGraphCheckpointEntity e : entities) {
                checkpoints.add(toCheckpoint(e));
            }
            return checkpoints;
        } catch (Exception e) {
            log.error("list checkpoint 失败 threadId={}", threadId, e);
            return List.of();
        }
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String threadId = threadId(config);
        try {
            // 取最新一条（按 created_at 降序，取第一个）
            LangGraphCheckpointEntity entity = mapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LangGraphCheckpointEntity>()
                            .eq(LangGraphCheckpointEntity::getThreadId, threadId)
                            .orderByDesc(LangGraphCheckpointEntity::getCreatedAt)
                            .last("LIMIT 1"));
            return Optional.ofNullable(entity).map(this::toCheckpoint);
        } catch (Exception e) {
            log.error("get checkpoint 失败 threadId={}", threadId, e);
            return Optional.empty();
        }
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String threadId = threadId(config);
        LangGraphCheckpointEntity entity = new LangGraphCheckpointEntity();
        entity.setThreadId(threadId);
        entity.setCheckpointId(checkpoint.getId());
        entity.setNodeId(checkpoint.getNodeId());
        entity.setNextNodeId(checkpoint.getNextNodeId());
        entity.setStateJson(objectMapper.writeValueAsString(checkpoint.getState()));
        entity.setCreatedAt(java.time.LocalDateTime.now());
        mapper.insert(entity);
        log.debug("put checkpoint threadId={} checkpointId={}", threadId, checkpoint.getId());
        return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
    }

    @Override
    public Tag release(RunnableConfig config) throws Exception {
        String threadId = threadId(config);
        mapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LangGraphCheckpointEntity>()
                .eq(LangGraphCheckpointEntity::getThreadId, threadId));
        log.debug("release checkpoint threadId={}", threadId);
        return new Tag(threadId, List.of());
    }

    private Checkpoint toCheckpoint(LangGraphCheckpointEntity entity) {
        try {
            Map<String, Object> state = objectMapper.readValue(
                    entity.getStateJson(), new TypeReference<Map<String, Object>>() {});
            return Checkpoint.builder()
                    .id(entity.getCheckpointId())
                    .state(state)
                    .nodeId(entity.getNodeId() != null ? entity.getNodeId() : "")
                    .nextNodeId(entity.getNextNodeId() != null ? entity.getNextNodeId() : "")
                    .build();
        } catch (Exception e) {
            log.error("反序列化 checkpoint 失败 id={}", entity.getCheckpointId(), e);
            throw new RuntimeException("反序列化 checkpoint 失败", e);
        }
    }
}
