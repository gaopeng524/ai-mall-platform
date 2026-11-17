package com.aimall.ai.service;

import com.aimall.ai.context.AiToolExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 商品知识 RAG：从 pgvector 召回静态商品知识（规格、卖点），作为系统提示词的参考资料。
 * 不含实时价格与库存，这两项必须走工具调用查微服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ProductKnowledgeRetriever {

    private final VectorStore vectorStore;

    @Value("${ai.rag.similarity-threshold:0.50}")
    private double ragSimilarityThreshold;

    /**
     * RAG 语义检索：从 pgvector 向量库召回商品知识
     */
    String retrieveContext(String query) {
        try {
            var docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(3)
                            .similarityThreshold(ragSimilarityThreshold)
                            .build());
            if (docs == null || docs.isEmpty()) {
                return "";
            }
            AiToolExecutionContext.emitToolStep(
                    "vector_search",
                    "已从 pgvector 向量库召回 " + docs.size() + " 条商品知识片段",
                    String.format("余弦相似度阈值 ≥ %.2f (静态语义参考，不含实时售价与库存)", ragSimilarityThreshold),
                    "success"
            );
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < docs.size(); i++) {
                sb.append(i + 1).append(". ").append(docs.get(i).getContent()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("向量库检索跳过: {}", e.getMessage());
            return "";
        }
    }
}
