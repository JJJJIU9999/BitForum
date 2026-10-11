package com.bitforum.controller;

import com.bitforum.exception.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bitforum.common.Result;
import com.bitforum.service.ImageResourceService;
import com.bitforum.service.ImageResourceService.ImageView;

@RestController
@RequestMapping("/api/admin/images")
public class AdminImageResourceController {
    @Autowired private ImageResourceService images;

    public record Decision(String reason) {}

    @GetMapping("/pending")
    public Result<IPage<ImageView>> pending(@RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok("查询成功", images.pending(pageNum, pageSize));
    }

    @PutMapping("/{id}/approve")
    public Result<String> approve(@PathVariable Long id, @RequestAttribute("userId") Long reviewerId) {
        try {
            images.decide(id, reviewerId, true, null);
            return Result.ok("图片审核通过", null);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @PutMapping("/{id}/reject")
    public Result<String> reject(@PathVariable Long id, @RequestAttribute("userId") Long reviewerId,
            @RequestBody Decision decision) {
        try {
            images.decide(id, reviewerId, false, decision.reason());
            return Result.ok("图片已驳回", null);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }
}
