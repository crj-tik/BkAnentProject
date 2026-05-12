package com.bkanent.compare.mcp;

import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.compare.service.CompareAnalysisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
public class CompareMcpConfiguration {

    public static final String SERVER_NAME = "compare-mcp-server";
    public static final String TOOL_NAME = "compareListings";

    @Bean
    public McpJsonMapper compareMcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper.copy());
    }

    @Bean
    public HttpServletStreamableServerTransportProvider compareMcpTransportProvider(McpJsonMapper mcpJsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .jsonMapper(mcpJsonMapper)
                .build();
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer compareMcpServer(HttpServletStreamableServerTransportProvider transportProvider,
                                          McpJsonMapper mcpJsonMapper,
                                          CompareAnalysisService compareAnalysisService) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .title("Compare Listings")
                .description("对多个房源做结构化对比并返回总结。")
                .inputSchema(mcpJsonMapper, "{\"type\":\"object\",\"properties\":{\"listingIds\":{\"type\":\"string\"}},\"required\":[\"listingIds\"]}")
                .outputSchema(mcpJsonMapper, "{\"type\":\"object\",\"properties\":{\"resultText\":{\"type\":\"string\"},\"listingIds\":{\"type\":\"string\"},\"comparisonTableMarkdown\":{\"type\":\"string\"},\"aiConclusion\":{\"type\":\"string\"}},\"required\":[\"resultText\",\"listingIds\",\"comparisonTableMarkdown\",\"aiConclusion\"]}")
                .build();
        return McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, "1.0.0")
                .tool(tool, (exchange, arguments) -> {
                    String listingIdsText = String.valueOf(arguments.get("listingIds"));
                    CompareReportDTO report = compareAnalysisService.generateRpcCompareReport(parseListingIds(listingIdsText));
                    String text = """
                            房源对比表：
                            %s

                            AI 对比结论：
                            %s
                            """.formatted(report.comparisonTableMarkdown(), report.aiConclusion()).trim();
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(text)
                            .structuredContent(Map.of(
                                    "resultText", text,
                                    "listingIds", listingIdsText,
                                    "comparisonTableMarkdown", report.comparisonTableMarkdown(),
                                    "aiConclusion", report.aiConclusion()
                            ))
                            .build();
                })
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> compareMcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, "/mcp", "/mcp/*");
    }

    private static List<Long> parseListingIds(String listingIdsText) {
        if (listingIdsText == null || listingIdsText.isBlank()) {
            return List.of();
        }
        return Arrays.stream(listingIdsText.split("[,，、\\s]+"))
                .filter(value -> value != null && !value.isBlank())
                .map(Long::valueOf)
                .toList();
    }
}
