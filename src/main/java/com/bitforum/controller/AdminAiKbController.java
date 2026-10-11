package com.bitforum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bitforum.ai.rag.KbIndexService;
import com.bitforum.common.Result;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 管理员知识库接口（M15）。
 *
 * <p>路径位于 {@code /api/admin/**} 下，由既有的 {@code AdminInterceptor} 统一做管理员校验，
 * 不需要额外的权限代码。
 *
 * <p>M15 第二步先提供手动重建与统计；文章发布/更新/下架后的自动索引在第三步接入消息队列。
 */
@RestController
@RequestMapping("/api/admin/ai/kb")
@Tag(name = "管理员知识库", description = "RAG 知识库的全量重建与统计")
@SecurityRequirement(name = "bearerAuth")
public class AdminAiKbController {

    @Autowired
    private KbIndexService kbIndexService;

    /**
     * 全量重建知识库。
     *
     * <p>同步执行：当前站内文章量级很小（几十篇），重建在秒级完成；
     * 内容未变化的文章会按指纹跳过，因此重复点击不会重复消耗嵌入算力。
     */
    @PostMapping("/rebuild")
    @Operation(summary = "全量重建知识库")
    public Result<KbIndexService.RebuildResult> rebuild() {
        KbIndexService.RebuildResult result = kbIndexService.rebuildAll();
        return Result.ok("知识库全量重建完成", result);
    }

    @GetMapping("/stats")
    @Operation(summary = "查询知识库统计")
    public Result<KbIndexService.KbStats> stats() {
        return Result.ok("知识库统计查询成功", kbIndexService.stats());
    }
}
