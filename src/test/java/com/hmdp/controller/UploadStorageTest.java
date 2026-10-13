package com.hmdp.controller;

import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadStorageTest {
    @TempDir Path images;

    @Test
    void savesImageInsideConfiguredDirectory() throws Exception {
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "imageUploadDir", images.toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        Result result = controller.uploadImage(new MockMultipartFile("file", "avatar.png", "image/png", bytes.toByteArray()));

        assertTrue(result.getSuccess());
        assertTrue(Files.isRegularFile(images.resolve(result.getData().toString().substring(1))));
    }
}
