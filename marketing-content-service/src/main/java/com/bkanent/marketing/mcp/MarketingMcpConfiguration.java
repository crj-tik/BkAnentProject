package com.bkanent.marketing.mcp;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.MediaWorkerRpcService;
import com.bkanent.common.rpc.PromotionRpcService;
import com.bkanent.marketing.service.MarketingAssetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
public class MarketingMcpConfiguration {

    public static final String SERVER_NAME = "marketing-mcp-server";
    public static final String TOOL_NAME = "publishMarketingContent";
    private final MediaWorkerRpcService mediaWorkerRpcService;
    private final PromotionRpcService promotionRpcService;

    public MarketingMcpConfiguration(ObjectProvider<MediaWorkerRpcService> mediaWorkerRpcServiceProvider,
                                     ObjectProvider<PromotionRpcService> promotionRpcServiceProvider) {
        this.mediaWorkerRpcService = mediaWorkerRpcServiceProvider.getIfAvailable();
        this.promotionRpcService = promotionRpcServiceProvider.getIfAvailable();
    }

    @Bean
    public McpJsonMapper marketingMcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper.copy());
    }

    @Bean
    public HttpServletStreamableServerTransportProvider marketingMcpTransportProvider(McpJsonMapper mcpJsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .jsonMapper(mcpJsonMapper)
                .build();
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer marketingMcpServer(HttpServletStreamableServerTransportProvider transportProvider,
                                            McpJsonMapper mcpJsonMapper,
                                            MarketingAssetService marketingAssetService) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .title("Publish Marketing Content")
                .description("生成营销内容并分发到指定平台。")
                .inputSchema(mcpJsonMapper, "{\"type\":\"object\",\"properties\":{\"listingId\":{\"type\":\"integer\"},\"platforms\":{\"type\":\"string\"},\"copywriting\":{\"type\":\"string\"}},\"required\":[\"listingId\",\"platforms\",\"copywriting\"]}")
                .outputSchema(mcpJsonMapper, "{\"type\":\"object\",\"properties\":{\"resultText\":{\"type\":\"string\"},\"listingId\":{\"type\":\"integer\"},\"platforms\":{\"type\":\"string\"},\"contents\":{\"type\":\"array\"}},\"required\":[\"resultText\",\"listingId\",\"platforms\",\"contents\"]}")
                .build();
        return McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, "1.0.0")
                .tool(tool, (exchange, arguments) -> {
                    Long listingId = Long.valueOf(String.valueOf(arguments.get("listingId")));
                    String copywriting = String.valueOf(arguments.get("copywriting"));
                    String platforms = String.valueOf(arguments.get("platforms"));
                    List<MarketingContentDTO> savedContents = executePublish(marketingAssetService, listingId, copywriting, platforms);
                    String text = "已生成 " + savedContents.size() + " 条内容并提交发布。";
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(text)
                            .structuredContent(Map.of(
                                    "resultText", text,
                                    "listingId", listingId,
                                    "platforms", platforms,
                                    "contents", savedContents
                            ))
                            .build();
                })
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> marketingMcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, "/mcp", "/mcp/*");
    }

    private List<MarketingContentDTO> executePublish(MarketingAssetService marketingAssetService,
                                                     Long listingId,
                                                     String copywriting,
                                                     String platforms) {
        List<String> platformList = parsePlatforms(platforms);
        List<String> assets = mediaWorkerRpcService == null ? List.of() : mediaWorkerRpcService.generateAssets(listingId, copywriting);
        List<MarketingContentDTO> contents = platformList.stream()
                .map(platform -> new MarketingContentDTO(listingId, platform, copywriting, assets, "DRAFT"))
                .toList();
        List<MarketingContentDTO> savedContents = marketingAssetService.saveContents(contents);
        if (promotionRpcService != null) {
            savedContents.forEach(promotionRpcService::publish);
        }
        return savedContents;
    }

    private List<String> parsePlatforms(String platforms) {
        if (platforms == null || platforms.isBlank()) {
            return List.of("douyin");
        }
        return Arrays.stream(platforms.split("[,，、\\s]+"))
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }
}
