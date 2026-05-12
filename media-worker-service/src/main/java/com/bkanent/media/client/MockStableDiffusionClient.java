package com.bkanent.media.client;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟 Stable Diffusion 图片生成客户端。
 */
@Component
public class MockStableDiffusionClient implements MediaImageGenerationClient {

    @Override
    public List<GeneratedMediaFile> generateListingImages(Long listingId, String prompt, List<String> angles) {
        List<GeneratedMediaFile> files = new ArrayList<>();
        int index = 1;
        for (String angle : angles) {
            files.add(new GeneratedMediaFile(buildFileName(index, angle), renderImage(listingId, prompt, angle)));
            index++;
        }
        return files;
    }

    private String buildFileName(int index, String angle) {
        String normalizedAngle = angle == null ? "default" : angle.replaceAll("[^\\p{IsHan}a-zA-Z0-9]+", "-");
        return index + "-" + normalizedAngle + ".png";
    }

    private byte[] renderImage(Long listingId, String prompt, String angle) {
        try {
            BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(new Color(236, 240, 241));
                graphics.fillRect(0, 0, 1280, 720);
                graphics.setColor(new Color(39, 55, 77));
                graphics.fillRoundRect(60, 60, 1160, 600, 36, 36);

                graphics.setColor(Color.WHITE);
                graphics.setFont(new Font("Microsoft YaHei", Font.BOLD, 42));
                graphics.drawString("房源 AI 展示图", 100, 150);

                graphics.setFont(new Font("Microsoft YaHei", Font.PLAIN, 28));
                graphics.drawString("房源ID：" + listingId, 100, 240);
                graphics.drawString("视角：" + safeText(angle), 100, 300);
                graphics.drawString("提示词：" + truncate(prompt, 32), 100, 360);
                graphics.drawString("生成方式：模拟 Stable Diffusion", 100, 420);
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("生成模拟图片失败", exception);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "未提供";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "默认视角" : value;
    }
}
