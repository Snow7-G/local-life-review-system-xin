package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {
    private static final long MAX_IMAGE_SIZE = 1024 * 1024;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            String suffix = originalFilename == null ? "" : StrUtil.subAfter(originalFilename, ".", true).toLowerCase(Locale.ROOT);
            if (image.isEmpty() || image.getSize() > MAX_IMAGE_SIZE || !isSupportedImage(image, suffix)) {
                return Result.fail("仅支持1MB以内的JPEG、PNG或GIF图片");
            }
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    private boolean isSupportedImage(MultipartFile image, String suffix) throws IOException {
        if (!suffix.equals("jpg") && !suffix.equals("jpeg") && !suffix.equals("png") && !suffix.equals("gif")) {
            return false;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(image.getInputStream())) {
            if (input == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                return format.equals(suffix) || format.equals("jpeg") && suffix.equals("jpg");
            } finally {
                reader.dispose();
            }
        }
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
