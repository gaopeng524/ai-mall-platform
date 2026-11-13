package com.aimall.product.service;

import com.aimall.product.entity.Product;
import com.aimall.product.mapper.ProductMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductSearchTest {
    private final ProductMapper mapper = mock(ProductMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> stock = mock(ValueOperations.class);
    private final ProductService service = new ProductService(mapper, null, redis, null, null);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Product.class);
        when(redis.opsForValue()).thenReturn(stock);
    }

    @Test
    void knownAliasesMatchTheSameExactModelAcrossStorefrontQueries() {
        var base = product(1, "苹果 ｉＰｈｏｎｅ １５ 128GB", "手机", 4999);
        var pro = product(2, "苹果 iPhone 15 Pro 128GB", "手机", 7999);
        var proMax = product(3, "苹果 iPhone 15 Pro Max", "手机", 8999);
        when(mapper.selectList(any())).thenReturn(List.of(base, pro, proMax));

        assertEquals(List.of(base), service.search("果15", 0));
        assertEquals(List.of(base), service.search("苹果１５", 0));
        assertEquals(List.of(base), service.search("苹果", 0, "果15在商城卖多少钱？"));
        assertEquals(List.of(base), service.search("iPhone 15 有货吗？", 0));
        assertEquals(List.of(pro), service.search("iPhone15Pro", 0));
        assertEquals(List.of(proMax), service.search("果15 Pro Max", 0));
        assertNull(ProductSearchTerms.model("水果15"));
        assertEquals("咖啡机", ProductSearchTerms.modelKeyword("咖啡机", "果15和咖啡机"));
    }

    @Test
    void alternativesUseOneBudgetLimitedCatalogueQueryAndExcludeReservedStock() {
        var pro = product(2, "苹果 iPhone 15 Pro", "手机", 7999);
        var soldOut = product(3, "小米 14", "手机", 3999);
        var redmi = product(4, "红米 Redmi K70", "手机", 2399);
        List<LambdaQueryWrapper<Product>> queries = new ArrayList<>();
        when(mapper.selectList(any())).thenAnswer(call -> {
            queries.add(call.getArgument(0));
            return queries.size() == 1 ? List.of(pro) : List.of(redmi, soldOut);
        });
        when(stock.multiGet(List.of("stock:count:4", "stock:count:3"))).thenReturn(List.of("6", "0"));

        var discovery = service.discover("苹果", 4000, "果15多少钱，有没有国产平替手机？");

        assertTrue(discovery.matches().isEmpty(), "Pro must not be presented as the base model");
        assertEquals(List.of(redmi), discovery.alternatives());
        assertEquals(6, redmi.getStock());
        assertEquals(2, queries.size());
        String fallbackSql = queries.get(1).getSqlSegment();
        assertTrue(fallbackSql.contains("price <="));
        assertTrue(fallbackSql.contains("category IN"));
        assertTrue(fallbackSql.contains("stock >"));
        assertTrue(queries.get(1).getParamNameValuePairs().containsValue(BigDecimal.valueOf(4000.0)));
    }

    @Test
    void ordinaryLookupKeepsOutOfStockExactMatchesAndDoesNotSearchForAlternatives() {
        var pro = product(2, "苹果 iPhone 15 Pro", "手机", 7999);
        when(mapper.selectList(any())).thenReturn(List.of(pro));
        when(stock.multiGet(List.of("stock:count:2"))).thenReturn(List.of("0"));

        assertEquals(List.of(pro), service.search("苹果１５ Pro", 0));
        assertEquals(0, pro.getStock());
        verify(mapper).selectList(any());
    }

    @Test
    void namedModelCanBeQuotedSeparatelyFromItsBudgetLimitedAlternatives() {
        var pro = product(2, "苹果 iPhone 15 Pro", "手机", 7999);
        var redmi = product(4, "红米 Redmi K70", "手机", 2399);
        List<LambdaQueryWrapper<Product>> queries = new ArrayList<>();
        when(mapper.selectList(any())).thenAnswer(call -> {
            queries.add(call.getArgument(0));
            return queries.size() == 1 ? List.of(pro) : List.of(redmi);
        });

        var discovery = service.discover("苹果", 3000, "果15 Pro卖多少，有3000以内的平替吗？");

        assertEquals(List.of(pro), discovery.matches());
        assertEquals(List.of(redmi), discovery.alternatives());
        assertFalse(queries.get(0).getSqlSegment().contains("price <="));
        assertTrue(queries.get(1).getSqlSegment().contains("price <="));
    }

    private static Product product(long id, String name, String category, long price) {
        var item = new Product();
        item.setId(id);
        item.setName(name);
        item.setCategory(category);
        item.setPrice(BigDecimal.valueOf(price));
        item.setStock(10);
        return item;
    }
}
