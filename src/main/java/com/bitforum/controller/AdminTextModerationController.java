package com.bitforum.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.common.Result;
import com.bitforum.entity.TextReviewRecord;
import com.bitforum.entity.TextRule;
import com.bitforum.entity.TextRuleOperation;
import com.bitforum.mapper.TextRuleOperationMapper;
import com.bitforum.service.TextRescanService;
import com.bitforum.service.TextReviewService;
import com.bitforum.service.TextRuleService;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/admin/text-moderation")
@SecurityRequirement(name = "bearerAuth")
public class AdminTextModerationController {
    @Autowired private TextRuleService ruleService;
    @Autowired private TextRuleOperationMapper operationMapper;
    @Autowired private TextReviewService reviewService;
    @Autowired private TextRescanService rescanService;

    public record RuleRequest(String phrase, Boolean enabled) {}
    public record DecisionRequest(boolean approved, String reason) {}
    public record ExecuteRequest(long ruleVersion, String cursor) {}

    @GetMapping("/rules")
    public Result<List<TextRule>> rules() {
        return Result.ok("查询成功", ruleService.listRules());
    }

    @PostMapping("/rules")
    public Result<TextRule> create(@RequestBody RuleRequest request, @RequestAttribute("userId") Long actorId) {
        return Result.ok("规则已创建", ruleService.create(request.phrase(), actorId));
    }

    @PutMapping("/rules/{id}")
    public Result<TextRule> update(@PathVariable Long id, @RequestBody RuleRequest request,
            @RequestAttribute("userId") Long actorId) {
        return Result.ok("规则已更新", ruleService.update(id, request.phrase(), request.enabled(), actorId));
    }

    @DeleteMapping("/rules/{id}")
    public Result<Void> delete(@PathVariable Long id, @RequestAttribute("userId") Long actorId) {
        ruleService.delete(id, actorId);
        return Result.ok("规则已删除", null);
    }

    @GetMapping("/operations")
    public Result<Page<TextRuleOperation>> operations(@RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok("查询成功", operationMapper.selectPage(new Page<>(Math.max(pageNum, 1), Math.max(1, Math.min(pageSize, 100))),
                new QueryWrapper<TextRuleOperation>().orderByDesc("id")));
    }

    @GetMapping("/pending")
    public Result<Page<TextReviewRecord>> pending(@RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok("查询成功", reviewService.pagePending(Math.max(pageNum, 1), Math.max(1, Math.min(pageSize, 100))));
    }

    @PutMapping("/pending/{id}")
    public Result<Void> decide(@PathVariable Long id, @RequestBody DecisionRequest request,
            @RequestAttribute("userId") Long actorId) {
        reviewService.decide(id, actorId, request.approved(), request.reason());
        return Result.ok("审核已处理", null);
    }

    @GetMapping("/rescan/preview")
    public Result<TextRescanService.Preview> preview(@RequestAttribute("userId") Long actorId) {
        return Result.ok("预览完成", rescanService.preview(actorId));
    }

    @PostMapping("/rescan/execute")
    public Result<TextRescanService.Batch> execute(@RequestBody ExecuteRequest request,
            @RequestAttribute("userId") Long actorId) {
        return Result.ok("批次已执行", rescanService.execute(request.ruleVersion(), request.cursor(), actorId));
    }


}
