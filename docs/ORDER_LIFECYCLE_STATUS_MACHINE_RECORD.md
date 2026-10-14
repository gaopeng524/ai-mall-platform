# 订单全生命周期状态机闭环与履约流转工程实施记录

## 1. 背景与目标
在本项目（AI-Mall 电商系统）中，用户在经历了秒杀下单/事务消息下单、支付中心模拟扣减（核销）之后，订单一直停留在 `已付款 (PAID)` 状态，缺少电商业务的完整履约闭环（商家发货出库、物流中转配送、买家确认签收完成）。

为了将纯 Java 业务链路完整走通，建立闭环状态机，本次实施为订单中心补齐了从 `PAID` 到 `SHIPPED`（发货）、再到 `DONE`（交易完成）的全生命周期流转机制，并在前端我的订单中心（`OrderDrawer.tsx`）提供了全状态 Tab 筛选及可视化交互动作。

---

## 2. 状态机流转设计 (State Machine)

```
        +------------------------------------------------+
        |                                                |
        |  +-----------+  支付成功  +--------+  商家发货   v
[下单] -> |  PENDING  | --------> |  PAID  | --------> [SHIPPED]
        +-----------+            +--------+               |
              |                                           |
              | 5分钟超时关单/主动取消                      | 买家确认收货
              v                                           v
        [CANCELLED]                                    [DONE]
        (回滚归还库存)                               (交易圆满完成)
```

### 状态机 CAS 原子保障
在并发环境下，防止状态乱序或重复流转，后端的变更全部采用基于乐观锁/CAS（Compare-And-Swap）的更新条件：
- **发货 (`shipOrder`)**：`UPDATE orders SET status = 'SHIPPED' WHERE id = ? AND status = 'PAID'`
- **确认收货 (`completeOrder`)**：`UPDATE orders SET status = 'DONE' WHERE id = ? AND status IN ('SHIPPED', 'PAID')`

---

## 3. 代码变更清单

### 3.1 后端 `order-service`
1. **服务层 `OrderService.java`**
   - 增加方法 `boolean shipOrder(Long orderId, String orderNo)`：
     - CAS 校验当前订单必须处于 `PAID` 状态，更新为 `SHIPPED`。
   - 增加方法 `boolean completeOrder(Long orderId, String orderNo)`：
     - CAS 校验当前订单必须处于 `SHIPPED` 或 `PAID` 状态，更新为 `DONE`。
2. **控制层 `OrderController.java`**
   - 增加接口 `POST /order/ship`：接收 `orderId` 与 `orderNo`，执行商家出库发货。
   - 增加接口 `POST /order/complete`：接收 `orderId` 与 `orderNo`，执行买家确认收货。

### 3.2 前端 `aimall-frontend`
1. **事件处理 `App.tsx`**
   - `handleUpdateOrderStatus` 增强：
     - 捕获 `status === 'SHIPPED'`，向网关 `POST /api/order/ship` 发起请求，并在调用链追踪面板打印 `Order Shipped` 日志。
     - 捕获 `status === 'DONE'`，向网关 `POST /api/order/complete` 发起请求，并在调用链追踪面板打印 `Order Completed` 日志。
2. **订单抽屉组件 `OrderDrawer.tsx`**
   - **Tab 分类增强**：由原先粗粒度分类拆分为 `全部`、`待支付`、`已付款`、`已发货`、`已完成`、`已取消` 6 个独立 Tab，并动态显示各状态订单徽标数量。
   - **交互卡片渲染**：
     - 当订单为 `PAID` 时：底部展示“已支付成功 · 仓库配货中”，并提供“模拟发货”操作按钮（一键触发商家发货）。
     - 当订单为 `SHIPPED` 时：底部展示“顺丰速运极速派送中”，并提供“确认收货”操作按钮（一键触发买家签收）。
     - 当订单为 `DONE` 时：底部展示“交易圆满完成 · 感谢惠顾”绿色成功徽章与“全链路闭环”标识。

---

## 4. 验证与上线
1. **后端验证**：
   - 执行 `.\mvnw.cmd test-compile -pl order-service`，构建通过（`BUILD SUCCESS`）。
2. **前端验证与热部署**：
   - 执行 `pnpm run build`，编译打包输出至 `dist/`。
   - 通过 `docker --context default cp frontend/dist/. aimall-frontend:/usr/share/nginx/html/` 覆盖容器静态资源。
   - 执行 `docker --context default exec aimall-frontend nginx -s reload` 平滑重载生效。
