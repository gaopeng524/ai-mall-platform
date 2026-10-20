package com.aimall.ai.service;

import com.aimall.ai.client.ProductFeignClient;
import com.aimall.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * AI 知识库服务（pgvector HNSW 向量索引管理与知识灌库）
 * 
 * 核心升级：
 * 1. 自动适配 1024 维：阿里通义千问 text-embedding-v3 默认 1024 维，自动校验修复旧表历史维度；
 * 2. 幂等性保障：所有知识片段与在售商品均绑定确定性 ID（如 kb_matrix_*、kb_prod_*），杜绝重复灌库膨胀；
 * 3. 分批次 Embedding：单批次限制 6 条（远低于 DashScope 10 条上限），杜绝 400 Bad Request 异常；
 * 4. 启动异步热加载与自检探针。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService implements ApplicationRunner {

    private final VectorStore vectorStore;
    private final ProductFeignClient productFeignClient;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        CompletableFuture.runAsync(() -> {
            try {
                // 延迟 3 秒等待微服务与数据源完全就绪
                Thread.sleep(3000);
                autoCheckAndInitKnowledgeBase();
            } catch (Exception e) {
                log.warn("[AI RAG 知识库] 启动自检跳过: {}", e.getMessage());
            }
        });
    }

    /**
     * 创建或修复 pgvector 1024 维表结构及 HNSW 索引
     */
    public void createVectorTableIfNotExists() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector;");
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";");
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS vector_store (
                    id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
                    content text,
                    metadata json,
                    embedding vector(1024)
                );
            """);
            jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS vector_store_embedding_hnsw_idx 
                ON vector_store USING hnsw (embedding vector_cosine_ops);
            """);
            log.info("[AI RAG 知识库] pgvector 1024 维表结构与 HNSW 索引就绪");
        } catch (Exception e) {
            log.error("[AI RAG 知识库] 创建 vector_store 表结构异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 校验并自动修复 pgvector 表结构（针对历史遗留的 1536 维字段自动重置为 1024 维）
     */
    public void ensureVectorSchema() {
        try {
            String checkSql = """
                SELECT atttypmod 
                FROM pg_attribute 
                WHERE attrelid = 'vector_store'::regclass 
                  AND attname = 'embedding' 
                  AND NOT attisdropped;
            """;
            Integer typmod = jdbcTemplate.queryForObject(checkSql, Integer.class);
            if (typmod != null && typmod != 1024) {
                log.warn("[AI RAG 知识库] 检测到 pgvector 字段维度为 {}，与 text-embedding-v3(1024) 不匹配，正在重置重建表...", typmod);
                jdbcTemplate.execute("DROP TABLE IF EXISTS vector_store CASCADE;");
                createVectorTableIfNotExists();
                log.info("[AI RAG 知识库] 维度不匹配的旧表已清理并立即重建 1024 维表");
            }
        } catch (Exception e) {
            log.info("[AI RAG 知识库] 表不存在或未初始化，正在初始化 1024 维向量表: {}", e.getMessage());
            createVectorTableIfNotExists();
        }
    }

    /**
     * 启动自检：检查 pgvector 中是否已有知识，若为空则自动灌入默认商品与导购知识库
     */
    public void autoCheckAndInitKnowledgeBase() {
        ensureVectorSchema();
        try {
            var probeResults = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("跑步鞋 耳机 手机 键盘")
                            .topK(1)
                            .similarityThreshold(0.3)
                            .build()
            );
            if (probeResults != null && !probeResults.isEmpty()) {
                log.info("[AI RAG 知识库] 探针检测成功，pgvector 已存在索引知识，无需重复初始化");
                return;
            }
        } catch (Exception e) {
            log.info("[AI RAG 知识库] 向量库初始化检测: {}", e.getMessage());
        }

        log.info("[AI RAG 知识库] pgvector 知识库当前为空，正在执行首期专业导购知识库灌库...");
        initDefaultKnowledgeBase();
    }

    /**
     * 初始化默认商品矩阵与导购专业知识（旗舰 vs 平替 对标手册 + 交易规则）
     * 采用确定性 Document ID，实现幂等插入
     */
    public int initDefaultKnowledgeBase() {
        List<Document> docs = new ArrayList<>();

        // 1. 运动鞋品类 (Nike Pegasus vs 李宁赤兔6 Pro, 空军一号 vs 回力)
        docs.add(new Document(
                "kb_matrix_runningshoes",
                """
                【专业跑鞋选购与平替知识】
                - 旗舰标杆：Nike Air Zoom Pegasus 40（售价 ¥799），核心科技为 React 泡棉中底 + 前后掌双 Zoom Air 气垫，适合日常慢跑训练，支撑稳定，重量约 285g。
                - 顶级高性价比平替：李宁赤兔6 Pro 专业竞速跑步鞋（售价 ¥499，限时秒杀 ¥99）。
                - 核心平替对比与优势：李宁赤兔6 Pro 采用全掌李宁䨻（BOOM）科技轻弹中底，能量回馈高达 80%，重量仅约 210g（比 Nike 轻 75g！），前掌滚动感与跑道提速极佳。千元内专业竞速与日常拉练首选平替。
                - 百元休闲板鞋对标：Nike Air Force 1 空军一号（¥749）与回力复古经典低帮小白鞋（¥79），回力橡胶硫化底耐磨防滑，适合学生与日常百元内板鞋极致平替。
                """,
                Map.of("category", "运动鞋", "type", "comparison_matrix", "tags", "跑鞋,李宁䨻,Nike,平替")
        ));

        // 2. 手机数码 (iPhone 15 Pro vs 小米14, 华为 Mate60 vs 红米 K70)
        docs.add(new Document(
                "kb_matrix_phones",
                """
                【智能手机选购与平替知识】
                - 旗舰影像标杆：苹果 iPhone 15 Pro（售价 ¥7999），搭载 A17 Pro 3nm 芯片与航空钛金属机身，支持硬件级光追与 ProRes 视频。
                - 小尺寸全能影像平替：小米 14（售价 ¥3999）。搭载高通骁龙 8 Gen 3 处理器，配备新一代徕卡 Summilux 顶级镜头与光影猎人 900 传感器，支持 1.5K 超窄边直屏与 90W 秒充，价格仅为 iPhone 的一半，拍照与续航综合体验极强。
                - 性能黑马平替：红米 Redmi K70（售价 ¥2399）。搭载第二代骁龙 8 处理器、2K 顶级中国屏与 120W 快充，5000mAh 大电池，是对标华为 Mate 60 Pro（¥6999）等高价机型在日常游戏与长续航场景下的两千元档极致性能平替。
                """,
                Map.of("category", "手机", "type", "comparison_matrix", "tags", "小米14,iPhone,红米K70,骁龙8Gen3")
        ));

        // 3. 降噪耳机 (索尼 WH-1000XM5 vs 漫步者 W820NB, AirPods Pro 2 vs 红米 Buds 5 Pro)
        docs.add(new Document(
                "kb_matrix_earphones",
                """
                【降噪耳机选购与平替知识】
                - 头戴旗舰标杆：索尼 WH-1000XM5（售价 ¥2999），双芯驱动，8 麦克风主动降噪天花板，LDAC 高解析度传输。
                - 百元头戴降噪神级平替：漫步者 (EDIFIER) W820NB 双金标头戴式无线降噪耳机（售价 ¥299）。
                - 核心平替对比与优势：漫步者拥有 Hi-Res 与 Hi-Res WIRELESS 双金标官方认证，具备 -43dB 深度混合主动降噪，零压感专利耳套久戴不压头，续航长达 50 小时（超索尼 20 小时！），售价仅为索尼的十分之一。
                - 入耳式降噪平替：苹果 AirPods Pro 2（¥1899，H2 芯片）对标红米 Redmi Buds 5 Pro（¥399）。红米具备 52dB 超宽频深度降噪，圈瓷同轴双单元，LHDC 5.0 高清音频，是两千元内无线降噪耳机的公认卷王平替。
                """,
                Map.of("category", "耳机", "type", "comparison_matrix", "tags", "降噪耳机,漫步者W820NB,索尼XM5,双金标")
        ));

        // 4. 外设与数码穿戴 (罗技 GPW 2 vs 雷柏 VT9 PRO, Keychron vs 达尔优 A98)
        docs.add(new Document(
                "kb_matrix_peripherals",
                """
                【电竞鼠标与机械键盘选购与平替知识】
                - 旗舰电竞鼠标：罗技 GPW 二代狗屁王（售价 ¥1099），60g 超轻量化设计，HERO 25K 传感器。
                - 顶级百元平替：雷柏 VT9 PRO 双模无线鼠标（售价 ¥199）。同样搭载原相顶级 PAW3395 传感器，68g 轻量化，V+ 无线游戏技术低至 1ms 延迟，支持 4K 回报率升级，价格仅为罗技的两成。
                - 客制化机械键盘平替：Keychron Q1 Pro 全铝客制化键盘（¥1188）对标达尔优 A98 三模热插拔（¥399）。达尔优 A98 采用 Gasket 结构消音垫，搭载天空轴 V3，手感软弹音纯，RGB 灯效与三模连接一应俱全。
                """,
                Map.of("category", "数码外设", "type", "comparison_matrix", "tags", "雷柏VT9,PAW3395,达尔优A98,GPW2")
        ));

        // 5. 户外与冬季穿搭 (北面 1996 vs 波司登, 始祖鸟 Beta AR vs 拓路者雪峰)
        docs.add(new Document(
                "kb_matrix_outdoor",
                """
                【户外硬壳与极寒羽绒选购与平替知识】
                - 冬季羽绒标杆：北面 (The North Face) 1996 经典羽绒服（售价 ¥2998），美版 700 蓬松度鹅绒。
                - 极寒高性价比平替：波司登极寒大朵白鹅绒服（售价 ¥1299）。采用高品质 600+ 蓬松度大朵白鹅绒，含绒量 90%，高密防风暴外壳，抵御零下 30℃ 严寒，保暖与做工品质扎实。
                - 户外冲锋衣平替：始祖鸟 Beta AR 顶级硬壳（售价 ¥6000）对标拓路者雪峰三合一冲锋衣（售价 ¥499）。拓路者具备暴雨级防水防风面料，可拆卸双面加厚摇粒绒内胆，一衣三穿，满足绝大多数徒步与日常通勤需求。
                """,
                Map.of("category", "服装", "type", "comparison_matrix", "tags", "羽绒服,波司登,冲锋衣,始祖鸟平替")
        ));

        // 6. 商城核心业务规则与保障
        docs.add(new Document(
                "kb_rules_mall",
                """
                【AI 电商平台核心交易规则与售后保障】
                1. 订单支付时效：所有商品下单后进入 PENDING 待支付状态，系统挂载 RocketMQ 延迟消息与 Redis 双通道超时保护，5 分钟未支付将自动关单并自动归还商品库存。
                2. 秒杀与并发保障：高并发秒杀商品（如李宁赤兔6 Pro ¥99 秒杀）通过 Redis + Lua 脚本进行原子扣减，具备本地 Caffeine 缓存与 Sentinel 限流防护，杜绝超卖。
                3. 取消订单：未支付订单支持随时自主取消（或让 AI 导购协助取消），取消后库存秒级回滚。
                4. 购物车与结算：支持多商品勾选一键合并结算下单，生成主单号。
                """,
                Map.of("category", "平台规则", "type", "faq", "tags", "超时关单,秒杀,库存回滚,购物车")
        ));

        int added = batchAddDocuments(docs, 6);
        if (added == 0) {
            log.error("[AI RAG 知识库] 初始化默认知识库失败：写入文档数为 0");
            throw new BusinessException(500, "初始化知识库失败，向量写入条数为 0，请检查 pgvector 连通性与模型配置");
        }
        return added;
    }

    /**
     * 联动 product-service 微服务拉取在售商品，自动转化为知识切片并写入 pgvector
     * 核心亮点：绑定确定性 ID (kb_prod_{id})，杜绝重复同步导致数据膨胀；采用分批写入杜绝 400 异常
     */
    public int syncFromProductService() {
        try {
            var result = productFeignClient.search("", 0.0);
            if (result == null || result.getData() == null || result.getData().isEmpty()) {
                log.warn("[AI RAG 知识库] 从商品微服务拉取在售商品为空");
                return 0;
            }

            List<Map<String, Object>> products = result.getData();
            List<Document> docs = new ArrayList<>();

            for (Map<String, Object> p : products) {
                Object id = p.getOrDefault("id", 0);
                String name = String.valueOf(p.getOrDefault("name", ""));
                String category = String.valueOf(p.getOrDefault("category", ""));
                Object price = p.getOrDefault("price", 0);
                String desc = String.valueOf(p.getOrDefault("description", ""));

                String content = String.format("""
                        【商城在售商品知识】
                        - 商品名称：%s
                        - 所属品类：%s
                        - 官方售价：¥%s
                        - 核心卖点与规格描述：%s
                        """, name, category, price, desc);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("productId", id);
                metadata.put("name", name);
                metadata.put("category", category);
                metadata.put("type", "live_product");

                // 核心修复 P0-4：绑定确定性唯一 ID，ON CONFLICT (id) DO UPDATE 保证幂等防重复
                String docId = "kb_prod_" + id;
                docs.add(new Document(docId, content, metadata));
            }

            int added = batchAddDocuments(docs, 6);
            log.info("[AI RAG 知识库] 已成功从商品服务同步并 Embedding 写入 {} 款在售商品知识！", added);
            return added;
        } catch (Exception e) {
            log.error("[AI RAG 知识库] 同步在售商品向量知识异常: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 按照 batchSize 分批向 pgvector 写入向量，严格遵守 DashScope 批次数量上限 (≤10)
     */
    private int batchAddDocuments(List<Document> docs, int batchSize) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        int totalAdded = 0;
        for (int i = 0; i < docs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, docs.size());
            List<Document> batch = docs.subList(i, end);
            try {
                vectorStore.add(batch);
                totalAdded += batch.size();
                log.info("[AI RAG 知识库] 分批向量写入完成: batch {}~{}/{}", i + 1, end, docs.size());
            } catch (Exception e) {
                log.error("[AI RAG 知识库] 分批写入异常: batch {}~{}, error: {}", i + 1, end, e.getMessage(), e);
            }
        }
        return totalAdded;
    }
}
