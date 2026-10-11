package com.bitforum.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bitforum.common.Result;
import com.bitforum.service.ImageResourceService;
import com.bitforum.service.ImageResourceService.ImageData;
import com.bitforum.service.ImageResourceService.ImageView;

@RestController
@RequestMapping("/api/images")
public class ImageResourceController {
    @Autowired private ImageResourceService images;

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> publicContent(@PathVariable Long id) {
        return response(images.publicImage(id));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long id, @RequestAttribute("userId") Long viewerId) {
        return response(images.preview(id, viewerId));
    }

    @GetMapping("/mine")
    public Result<List<ImageView>> mine(@RequestAttribute("userId") Long ownerId) {
        return Result.ok("查询成功", images.mine(ownerId));
    }

    private ResponseEntity<byte[]> response(ImageData data) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(data.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(data.bytes());
    }
}
