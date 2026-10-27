package com.bkanent.listing.comparison;

import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
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

/** Builds a report PDF from already revalidated immutable source snapshots. */
@Service
public class ListingComparisonPdfService {
    private static final int WIDTH = 1240;
    private static final int PADDING = 60;
    private static final int LINE_HEIGHT = 34;

    public byte[] generate(ListingComparisonReportResponse report) {
        try {
            BufferedImage image = render(report);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", jpeg);
            return pdf(image, jpeg.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("生成房源比较 PDF 失败", failure);
        }
    }

    private BufferedImage render(ListingComparisonReportResponse report) {
        Font titleFont = font(Font.BOLD, 28);
        Font textFont = font(Font.PLAIN, 22);
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D measuring = measure.createGraphics();
        List<String> lines;
        try {
            lines = buildLines(report, measuring.getFontMetrics(textFont));
        } finally {
            measuring.dispose();
        }
        int height = Math.max(1754, PADDING * 2 + LINE_HEIGHT * (lines.size() + 1));
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, height);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(33, 37, 41));
            graphics.setFont(titleFont);
            int y = PADDING;
            graphics.drawString("房源比较报告", PADDING, y);
            graphics.setFont(textFont);
            y += LINE_HEIGHT + 16;
            for (String line : lines) {
                graphics.drawString(line, PADDING, y);
                y += LINE_HEIGHT;
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private List<String> buildLines(ListingComparisonReportResponse report, FontMetrics metrics) {
        int availableWidth = WIDTH - PADDING * 2;
        List<String> raw = new ArrayList<>();
        raw.add("报告编号：" + report.reportId());
        raw.add("来源数据模式：" + report.dataMode());
        raw.add("计算规则：" + report.rulesVersion());
        raw.add("房源来源：");
        report.sources().forEach(source -> raw.add(source.listingRef().sourceSystem() + "/"
                + source.listingRef().sourceId() + " 版本=" + source.revision() + " 数据=" + source.dataMode()));
        raw.add("指标：");
        report.metrics().forEach(metric -> raw.add(metric.metric() + "：" + metric.value()));
        raw.add("逐套对比：");
        report.rows().forEach(row -> {
            raw.add(row.title() + " [" + row.listingRef().sourceSystem() + "/" + row.listingRef().sourceId() + "]");
            row.values().forEach((name, value) -> raw.add("  " + name + "：" + value));
        });
        raw.add("结论：" + report.conclusion());
        List<String> wrapped = new ArrayList<>();
        for (String line : raw) wrapped.addAll(wrap(line, metrics, availableWidth));
        return wrapped;
    }

    private List<String> wrap(String value, FontMetrics metrics, int width) {
        if (value == null || value.isBlank()) return List.of(" ");
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (char character : value.toCharArray()) {
            line.append(character);
            if (metrics.stringWidth(line.toString()) > width) {
                line.deleteCharAt(line.length() - 1);
                result.add(line.toString());
                line.setLength(0);
                line.append(character);
            }
        }
        if (!line.isEmpty()) result.add(line.toString());
        return result;
    }

    private Font font(int style, int size) {
        for (String family : List.of("Microsoft YaHei", "Noto Sans CJK SC", "SimSun", Font.SANS_SERIF)) {
            Font candidate = new Font(family, style, size);
            if (!"Dialog".equals(candidate.getFamily(Locale.ROOT)) || Font.SANS_SERIF.equals(family)) return candidate;
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private byte[] pdf(BufferedImage image, byte[] jpeg) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(output, "%PDF-1.4\n");
        float pageWidth = image.getWidth() * 0.5F;
        float pageHeight = image.getHeight() * 0.5F;
        object(output, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        object(output, offsets, 2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        object(output, offsets, 3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + format(pageWidth) + " "
                + format(pageHeight) + "] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>");
        binaryObject(output, offsets, 4, "<< /Type /XObject /Subtype /Image /Width " + image.getWidth()
                + " /Height " + image.getHeight() + " /ColorSpace /DeviceRGB /BitsPerComponent 8"
                + " /Filter /DCTDecode /Length " + jpeg.length + " >>", jpeg);
        byte[] content = ("q " + format(pageWidth) + " 0 0 " + format(pageHeight) + " 0 0 cm /Im0 Do Q")
                .getBytes(StandardCharsets.ISO_8859_1);
        binaryObject(output, offsets, 5, "<< /Length " + content.length + " >>", content);
        int xref = output.size();
        write(output, "xref\n0 6\n0000000000 65535 f \n");
        offsets.forEach(offset -> writeUnchecked(output, String.format(Locale.ROOT, "%010d 00000 n \n", offset)));
        write(output, "trailer << /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF");
        return output.toByteArray();
    }

    private void object(ByteArrayOutputStream output, List<Integer> offsets, int id, String body) throws IOException {
        offsets.add(output.size());
        write(output, id + " 0 obj\n" + body + "\nendobj\n");
    }

    private void binaryObject(ByteArrayOutputStream output, List<Integer> offsets, int id,
                              String header, byte[] bytes) throws IOException {
        offsets.add(output.size());
        write(output, id + " 0 obj\n" + header + "\nstream\n");
        output.write(bytes);
        write(output, "\nendstream\nendobj\n");
    }

    private void write(ByteArrayOutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void writeUnchecked(ByteArrayOutputStream output, String text) {
        try { write(output, text); } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private String format(float value) { return String.format(Locale.ROOT, "%.2f", value); }
}
