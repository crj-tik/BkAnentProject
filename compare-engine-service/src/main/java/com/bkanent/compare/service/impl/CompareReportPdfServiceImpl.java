package com.bkanent.compare.service.impl;

import com.bkanent.compare.model.CompareMetricResponse;
import com.bkanent.compare.model.CompareReportResponse;
import com.bkanent.compare.model.CompareRowResponse;
import com.bkanent.compare.service.CompareReportPdfService;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 房源对比 PDF 生成服务实现。
 */
@Service
public class CompareReportPdfServiceImpl implements CompareReportPdfService {

    private static final int IMAGE_WIDTH = 1240;
    private static final int PAGE_PADDING = 60;
    private static final int TITLE_FONT_SIZE = 28;
    private static final int CONTENT_FONT_SIZE = 22;
    private static final int LINE_HEIGHT = 34;

    @Override
    public byte[] generatePdf(CompareReportResponse report) {
        try {
            BufferedImage image = buildReportImage(report);
            byte[] imageBytes = renderImageBytes(image);
            return buildPdfBytes(image, imageBytes);
        } catch (IOException exception) {
            throw new IllegalStateException("生成房源对比 PDF 失败", exception);
        }
    }

    private BufferedImage buildReportImage(CompareReportResponse report) {
        Font titleFont = resolveFont(Font.BOLD, TITLE_FONT_SIZE);
        Font contentFont = resolveFont(Font.PLAIN, CONTENT_FONT_SIZE);
        List<String> lines = buildWrappedLines(report, titleFont, contentFont);
        int imageHeight = Math.max(1754, PAGE_PADDING * 2 + lines.size() * LINE_HEIGHT + 40);

        BufferedImage image = new BufferedImage(IMAGE_WIDTH, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, IMAGE_WIDTH, imageHeight);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int y = PAGE_PADDING;
            graphics.setColor(new Color(33, 37, 41));
            graphics.setFont(titleFont);
            graphics.drawString("房源对比分析报告", PAGE_PADDING, y);
            y += LINE_HEIGHT + 16;

            graphics.setFont(contentFont);
            for (String line : lines) {
                graphics.drawString(line, PAGE_PADDING, y);
                y += LINE_HEIGHT;
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private List<String> buildWrappedLines(CompareReportResponse report, Font titleFont, Font contentFont) {
        BufferedImage measureImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = measureImage.createGraphics();
        try {
            FontMetrics titleMetrics = graphics.getFontMetrics(titleFont);
            FontMetrics contentMetrics = graphics.getFontMetrics(contentFont);
            int availableWidth = IMAGE_WIDTH - PAGE_PADDING * 2;

            List<String> lines = new ArrayList<>();
            lines.addAll(wrapLine("分享编号：" + safeText(report.shareCode()), contentMetrics, availableWidth));
            lines.addAll(wrapLine("房源数量：" + report.listings().size(), contentMetrics, availableWidth));
            lines.addAll(wrapLine("对比房源：" + String.join("、",
                    report.listings().stream().map(item -> safeText(item.title())).toList()), contentMetrics, availableWidth));
            lines.add("");
            lines.addAll(wrapLine("核心指标：", titleMetrics, availableWidth));
            for (CompareMetricResponse metric : report.metrics()) {
                lines.addAll(wrapLine(metric.metricName() + "：" + safeText(metric.summaryValue()), contentMetrics, availableWidth));
            }
            lines.add("");
            lines.addAll(wrapLine("结构化对比：", titleMetrics, availableWidth));
            for (CompareRowResponse row : report.rows()) {
                lines.addAll(wrapLine("[" + safeText(row.listingTitle()) + "]", contentMetrics, availableWidth));
                for (var entry : row.values().entrySet()) {
                    lines.addAll(wrapLine(" - " + entry.getKey() + "：" + safeText(entry.getValue()), contentMetrics, availableWidth));
                }
            }
            lines.add("");
            lines.addAll(wrapLine("AI 分析结论：", titleMetrics, availableWidth));
            lines.addAll(wrapLine(safeText(report.aiConclusion()), contentMetrics, availableWidth));
            lines.add("");
            lines.addAll(wrapLine("Markdown 对比表：", titleMetrics, availableWidth));
            for (String markdownLine : report.comparisonTableMarkdown().split("\\R")) {
                lines.addAll(wrapLine(markdownLine, contentMetrics, availableWidth));
            }
            return lines;
        } finally {
            graphics.dispose();
        }
    }

    private List<String> wrapLine(String line, FontMetrics metrics, int availableWidth) {
        List<String> result = new ArrayList<>();
        if (line == null || line.isBlank()) {
            result.add(" ");
            return result;
        }

        StringBuilder builder = new StringBuilder();
        for (char current : line.toCharArray()) {
            builder.append(current);
            if (metrics.stringWidth(builder.toString()) > availableWidth) {
                builder.deleteCharAt(builder.length() - 1);
                result.add(builder.toString());
                builder.setLength(0);
                builder.append(current);
            }
        }
        if (!builder.isEmpty()) {
            result.add(builder.toString());
        }
        return result;
    }

    private Font resolveFont(int style, int size) {
        List<String> candidates = List.of("Microsoft YaHei", "SimSun", "PingFang SC", Font.SANS_SERIF);
        for (String candidate : candidates) {
            Font font = new Font(candidate, style, size);
            if (!"Dialog".equals(font.getFamily(Locale.ROOT)) || Font.SANS_SERIF.equals(candidate)) {
                return font;
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private byte[] renderImageBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", outputStream);
        return outputStream.toByteArray();
    }

    private byte[] buildPdfBytes(BufferedImage image, byte[] imageBytes) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        writeAscii(outputStream, "%PDF-1.4\n");

        float pageWidth = image.getWidth() * 0.5F;
        float pageHeight = image.getHeight() * 0.5F;

        writeObject(outputStream, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        writeObject(outputStream, offsets, 2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        writeObject(outputStream, offsets, 3,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + formatFloat(pageWidth) + " " + formatFloat(pageHeight)
                        + "] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>");
        writeBinaryObject(outputStream, offsets, 4,
                "<< /Type /XObject /Subtype /Image /Width " + image.getWidth()
                        + " /Height " + image.getHeight()
                        + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + imageBytes.length + " >>",
                imageBytes);

        byte[] contentBytes = ("q " + formatFloat(pageWidth) + " 0 0 " + formatFloat(pageHeight) + " 0 0 cm /Im0 Do Q")
                .getBytes(StandardCharsets.ISO_8859_1);
        writeBinaryObject(outputStream, offsets, 5, "<< /Length " + contentBytes.length + " >>", contentBytes);

        int xrefOffset = outputStream.size();
        writeAscii(outputStream, "xref\n");
        writeAscii(outputStream, "0 6\n");
        writeAscii(outputStream, "0000000000 65535 f \n");
        for (Integer offset : offsets) {
            writeAscii(outputStream, String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }
        writeAscii(outputStream, "trailer << /Size 6 /Root 1 0 R >>\n");
        writeAscii(outputStream, "startxref\n");
        writeAscii(outputStream, String.valueOf(xrefOffset));
        writeAscii(outputStream, "\n%%EOF");
        return outputStream.toByteArray();
    }

    private void writeObject(ByteArrayOutputStream outputStream, List<Integer> offsets, int objectId, String body) throws IOException {
        offsets.add(outputStream.size());
        writeAscii(outputStream, objectId + " 0 obj\n");
        writeAscii(outputStream, body);
        writeAscii(outputStream, "\nendobj\n");
    }

    private void writeBinaryObject(ByteArrayOutputStream outputStream, List<Integer> offsets, int objectId,
                                   String header, byte[] data) throws IOException {
        offsets.add(outputStream.size());
        writeAscii(outputStream, objectId + " 0 obj\n");
        writeAscii(outputStream, header);
        writeAscii(outputStream, "\nstream\n");
        outputStream.write(data);
        writeAscii(outputStream, "\nendstream\nendobj\n");
    }

    private void writeAscii(ByteArrayOutputStream outputStream, String text) throws IOException {
        outputStream.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private String formatFloat(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }
}
