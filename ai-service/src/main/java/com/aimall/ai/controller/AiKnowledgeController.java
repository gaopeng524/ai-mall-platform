package com.aimall.ai.controller;

import com.aimall.ai.service.KnowledgeService;
import com.aimall.common.context.UserContext;
import com.aimall.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI RAG 知识库管理接口
 * 提供向量知识初始化、在售商品实时 Embedding 同步与状态探针
 * 严格权限保护：仅 ADMIN 角色可执行写入与同步操作
 */
@RestController
@RequestMapping("/ai/knowledge")
@RequiredArgsConstructor
public class AiKnowledgeController {

    private final KnowledgeService knowledgeService;

    /**
     * 手动触发默认导购专业知识库初始化（对标手册与规则切片，仅管理员可用）
     */
    @PostMapping("/init")
    public Result<Map<String, Object>> initDefault() {
        if (!"ADMIN".equalsIgnoreCase(UserContext.getUserRole())) {
            return Result.fail(403, "无权限操作，仅管理员可触发知识库初始化");
        }
        int count = knowledgeService.initDefaultKnowledgeBase();
        return Result.success("知识库初始化完成", Map.of(
                "insertedDocuments", count,
                "status", count > 0 ? "SUCCESS" : "EMPTY"
        ));
    }

    /**
     * 联动商品服务拉取最新在售商品，自动 Embedding 并存入 pgvector（仅管理员可用）
     */
    @PostMapping("/sync")
    public Result<Map<String, Object>> syncProducts() {
        if (!"ADMIN".equalsIgnoreCase(UserContext.getUserRole())) {
            return Result.fail(403, "无权限操作，仅管理员可触发商品知识库同步");
        }
        int count = knowledgeService.syncFromProductService();
        return Result.success("在售商品知识库同步完成", Map.of(
                "syncedProducts", count,
                "status", count > 0 ? "SUCCESS" : "EMPTY"
        ));
    }

    /**
     * 查询知识库当前运行状态
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(Map.of(
                "engine", "Spring AI + PostgreSQL pgvector",
                "indexType", "HNSW (Hierarchical Navigable Small World)",
                "distanceType", "COSINE_DISTANCE",
                "embeddingModel", "DashScope text-embedding-v3 (1024 dim)",
                "dimensions", 1024,
                "status", "ACTIVE"
        ));
    }
}
