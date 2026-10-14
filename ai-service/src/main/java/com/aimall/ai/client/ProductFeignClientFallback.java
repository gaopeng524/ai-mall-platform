package com.aimall.ai.client;

import com.aimall.common.result.Result;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class ProductFeignClientFallback implements ProductFeignClient {
    @Override
    public Result<List<Map<String, Object>>> search(String keyword, double maxPrice) {
        return Result.fail("商品服务暂时不可用");
    }
    @Override
    public Result<Map<String, Object>> getById(Long id) {
        return Result.fail("商品服务暂时不可用");
    }
}
