package com.bitforum.ai.trace;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bitforum.ai.dto.AiTraceResponse;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.mapper.AiExecutionTraceMapper;

/**
 * 执行轨迹的查询服务（M18 管理端）。
 *
 * <p>只读：写入全部由 {@link TraceRecorder} 负责。查询侧做两件事 ——
 * 按场景/状态/用户过滤地翻页，以及把一步骤 JSON 解析成前端能渲染的数组。
 *
 * <p>列表页刻意**不解析 steps**：轨迹表会随使用量增长，列表页每行都带 JSON
 * 会让响应迅速膨胀，而列表只需要"N 步 + 状态 + 耗时 + token"。
 */
@Service
public class AiTraceQueryService {

    private static final Logger log = LoggerFactory.getLogger(AiTraceQueryService.class);

    private final AiExecutionTraceMapper traceMapper;
    private final ObjectMapper objectMapper;

    public AiTraceQueryService(AiExecutionTraceMapper traceMapper, ObjectMapper objectMapper) {
        this.traceMapper = traceMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页查询轨迹（不含步骤明细）。
     *
     * @param scene  场景过滤，null 表示全部
     * @param status 状态过滤，null 表示全部
     * @param userId 用户过滤，null 表示全部
     */
    public Page<AiTraceResponse> pageTraces(long pageNum, long pageSize, String scene,
                                            String status, Long userId) {
        LambdaQueryWrapper<AiExecutionTrace> wrapper = new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(scene != null && !scene.isBlank(), AiExecutionTrace::getScene, scene)
                .eq(status != null && !status.isBlank(), AiExecutionTrace::getStatus, status)
                .eq(userId != null, AiExecutionTrace::getUserId, userId)
                .orderByDesc(AiExecutionTrace::getId);

        Page<AiExecutionTrace> page = traceMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Page<AiTraceResponse> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toResponse).toList());
        return result;
    }

    /** 按 traceId 查详情（含步骤明细）；不存在返回 null。 */
    public AiTraceResponse findByTraceId(String traceId) {
        AiExecutionTrace entity = traceMapper.selectOne(new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(AiExecutionTrace::getTraceId, traceId)
                .last("LIMIT 1"));
        if (entity == null) {
            return null;
        }
        AiTraceResponse response = toResponse(entity);
        response.setSteps(parseSteps(entity.getSteps()));
        return response;
    }

    private AiTraceResponse toResponse(AiExecutionTrace entity) {
        AiTraceResponse response = new AiTraceResponse();
        response.setId(entity.getId());
        response.setTraceId(entity.getTraceId());
        response.setScene(entity.getScene());
        response.setAgentType(entity.getAgentType());
        response.setUserId(entity.getUserId());
        response.setConversationId(entity.getConversationId());
        response.setRefType(entity.getRefType());
        response.setRefId(entity.getRefId());
        response.setStatus(entity.getStatus());
        response.setRoute(entity.getRoute());
        response.setModel(entity.getModel());
        response.setPromptTokens(entity.getPromptTokens());
        response.setCompletionTokens(entity.getCompletionTokens());
        response.setTotalTokens(entity.getTotalTokens());
        response.setLatencyMs(entity.getLatencyMs());
        response.setStepCount(entity.getStepCount());
        response.setDegradeReason(entity.getDegradeReason());
        response.setMessage(entity.getMessage());
        response.setCreateTime(entity.getCreateTime());
        response.setUpdateTime(entity.getUpdateTime());
        return response;
    }

    private List<TraceStep> parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<TraceStep>>() {
            });
        } catch (Exception exception) {
            // 解析失败不能让整个详情接口 500：轨迹本身仍然有价值（状态、耗时、token 都在）
            log.warn("执行轨迹步骤解析失败：{}", exception.getMessage());
            return List.of();
        }
    }
}
