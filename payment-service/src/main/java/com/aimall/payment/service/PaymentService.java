package com.aimall.payment.service;

import com.aimall.common.context.UserContext;
import com.aimall.common.exception.BusinessException;
import com.aimall.common.exception.ErrorCode;
import com.aimall.common.result.Result;
import com.aimall.payment.client.OrderRemoteClient;
import com.aimall.payment.dto.MockPaySubmitDTO;
import com.aimall.payment.dto.PayCreateDTO;
import com.aimall.payment.entity.PaymentRecord;
import com.aimall.payment.mapper.PaymentRecordMapper;
import com.aimall.payment.vo.PayResultVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRecordMapper paymentRecordMapper;
    private final OrderRemoteClient orderRemoteClient;
    private final RocketMQTemplate rocketMQTemplate;

    private static final String ORDER_PAID_TOPIC = "order-paid-topic";
    private static final AtomicInteger PAY_SEQ = new AtomicInteger(1000);
    private static final long TIMEOUT_SECONDS = 300L; // 5 分钟超时时限

    /**
     * 1. 唤起收银台：生成支付流水单 (状态: INIT)
     */
    public PayResultVO createPayment(PayCreateDTO dto) {
        // 1. RPC 跨服务查询订单信息并校验合法性
        Result<Map<String, Object>> orderResult = orderRemoteClient.getOrderDetail(dto.getOrderNo());
        if (orderResult == null || orderResult.getData() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "订单不存在: " + dto.getOrderNo());
        }

        Map<String, Object> orderData = orderResult.getData();
        String orderStatus = String.valueOf(orderData.get("status"));
        BigDecimal totalAmount = new BigDecimal(String.valueOf(orderData.get("totalAmount")));
        Long userId = Long.valueOf(String.valueOf(orderData.get("userId")));

        // 归属校验：非 ADMIN 时不允许为他人订单创建支付流水
        String role = UserContext.getUserRole();
        if (dto.getUserId() != null && !UserContext.isAdmin(role) && !dto.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权为他人订单发起支付");
        }

        // 2. 状态机前置拦截校验
        if ("PAID".equalsIgnoreCase(orderStatus)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "订单已完成支付，请勿重复发起");
        }
        if ("CANCELLED".equalsIgnoreCase(orderStatus)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "订单已超过5分钟超时自动关闭，无法发起支付");
        }

        // 3. 计算 5 分钟支付剩余倒计时
        String createdAtStr = String.valueOf(orderData.get("createdAt"));
        long remainingSeconds = calculateRemainingSeconds(createdAtStr);
        if (remainingSeconds <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "订单支付倒计时已归零，系统正在关单");
        }

        // 4. 检查是否已有处于 INIT 状态的支付流水（避免重复创建）
        PaymentRecord existingRecord = paymentRecordMapper.selectOne(
                new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getOrderNo, dto.getOrderNo())
                        .eq(PaymentRecord::getStatus, "INIT")
                        .orderByDesc(PaymentRecord::getId)
                        .last("LIMIT 1")
        );

        if (existingRecord != null) {
            log.info("[收银台复用] 订单 {} 复用已有未支付流水: {}", dto.getOrderNo(), existingRecord.getPaySn());
            return PayResultVO.builder()
                    .paySn(existingRecord.getPaySn())
                    .orderNo(dto.getOrderNo())
                    .amount(existingRecord.getAmount())
                    .payChannel(existingRecord.getPayChannel())
                    .status(existingRecord.getStatus())
                    .remainingSeconds(remainingSeconds)
                    .message("收银台就绪，请在倒计时结束前完成付款")
                    .build();
        }

        // 5. 生成高并发全局唯一支付流水号 (PAY + yyyyMMddHHmmssSSS + 4位原子序列 + 3位随机)
        int seq = PAY_SEQ.getAndUpdate(s -> s >= 9999 ? 1000 : s + 1);
        String paySn = "PAY" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + seq + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));

        PaymentRecord record = PaymentRecord.builder()
                .paySn(paySn)
                .orderNo(dto.getOrderNo())
                .userId(userId)
                .amount(totalAmount)
                .payChannel(dto.getPayChannel() != null ? dto.getPayChannel() : "MOCK")
                .status("INIT")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        paymentRecordMapper.insert(record);
        log.info("[收银台创建成功] 订单: {}, 生成支付流水: {}, 金额: ¥{}, 倒计时剩余: {}秒",
                dto.getOrderNo(), paySn, totalAmount, remainingSeconds);

        return PayResultVO.builder()
                .paySn(paySn)
                .orderNo(dto.getOrderNo())
                .amount(totalAmount)
                .payChannel(record.getPayChannel())
                .status("INIT")
                .remainingSeconds(remainingSeconds)
                .message("收银台就绪，请在 5 分钟内完成支付")
                .build();
    }

    /**
     * 2. 模拟用户确认付款：驱动支付与 CAS 订单状态机闭环 (支持防资损自动退款)
     */
    @Transactional(rollbackFor = Exception.class)
    public PayResultVO mockSubmitPay(MockPaySubmitDTO dto) {
        PaymentRecord record = paymentRecordMapper.selectOne(
                new LambdaQueryWrapper<PaymentRecord>().eq(PaymentRecord::getPaySn, dto.getPaySn())
        );
        if (record == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "未找到对应支付流水: " + dto.getPaySn());
        }

        // 归属校验：非 ADMIN 时不允许操作他人支付流水
        Long currentUserId = UserContext.getUserId();
        String role = UserContext.getUserRole();
        if (currentUserId != null && !UserContext.isAdmin(role) && !currentUserId.equals(record.getUserId())) {
            throw new BusinessException(403, "无权操作他人的支付流水");
        }

        // 幂等校验
        if ("SUCCESS".equalsIgnoreCase(record.getStatus())) {
            log.info("[支付幂等放行] 流水 {} 此前已支付成功", dto.getPaySn());
            return PayResultVO.builder()
                    .paySn(record.getPaySn())
                    .orderNo(record.getOrderNo())
                    .amount(record.getAmount())
                    .payChannel(record.getPayChannel())
                    .status("SUCCESS")
                    .remainingSeconds(0L)
                    .message("订单已完成支付")
                    .build();
        }

        if ("REFUNDED".equalsIgnoreCase(record.getStatus())) {
            return PayResultVO.builder()
                    .paySn(record.getPaySn())
                    .orderNo(record.getOrderNo())
                    .amount(record.getAmount())
                    .payChannel(record.getPayChannel())
                    .status("REFUNDED")
                    .remainingSeconds(0L)
                    .message("订单已超时关闭，款项已原路退回")
                    .build();
        }

        // 模拟第三方交易号
        String tradeNo = "MOCK_UNION_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000, 9999);

        // 3. 【核心协同】：跨服务调用 order-service 执行 CAS 状态机流转 (PENDING -> PAID)
        Result<Integer> callbackResult = orderRemoteClient.payCallback(
                record.getOrderNo(),
                record.getPaySn(),
                tradeNo,
                record.getPayChannel()
        );

        Integer resultCode = 0;
        if (callbackResult != null) {
            if (callbackResult.isSuccess() && callbackResult.getData() != null) {
                resultCode = callbackResult.getData();
            } else if (callbackResult.getCode() != null) {
                resultCode = callbackResult.getCode();
            }
        }

        if (resultCode != null && (resultCode == 1 || resultCode == 2)) {
            // 支付成功！更新支付流水为 SUCCESS
            record.setStatus("SUCCESS");
            record.setTradeNo(tradeNo);
            record.setPaidAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            paymentRecordMapper.updateById(record);

            // 发送 RocketMQ 支付成功事件广播（通知物流履约发货与积分赠送）
            try {
                rocketMQTemplate.syncSend(ORDER_PAID_TOPIC, record);
                log.info("[RocketMQ支付广播成功] 订单: {}, 支付流水: {}, 已触发下游物流履约",
                        record.getOrderNo(), record.getPaySn());
            } catch (Exception e) {
                log.error("[RocketMQ支付广播异常] 发送 order-paid-topic 失败: {}", e.getMessage(), e);
            }

            return PayResultVO.builder()
                    .paySn(record.getPaySn())
                    .orderNo(record.getOrderNo())
                    .amount(record.getAmount())
                    .payChannel(record.getPayChannel())
                    .status("SUCCESS")
                    .remainingSeconds(0L)
                    .message("恭喜！支付成功！订单已流转为已支付，仓库正在火速打包发货！")
                    .build();

        } else if (resultCode != null && resultCode == -1) {
            // 【大厂级时序竞态防资损】：5 分钟刚好到期，订单被超时关单并回滚库存！用户款项自动原路退回！
            record.setStatus("REFUNDED");
            record.setTradeNo(tradeNo);
            record.setPaidAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            paymentRecordMapper.updateById(record);

            log.warn("[时序竞态自动退款成功] 订单 {} 已超过 5 分钟被关单，支付流水 {} 触发自动原路退款！¥{}",
                    record.getOrderNo(), record.getPaySn(), record.getAmount());

            return PayResultVO.builder()
                    .paySn(record.getPaySn())
                    .orderNo(record.getOrderNo())
                    .amount(record.getAmount())
                    .payChannel(record.getPayChannel())
                    .status("REFUNDED")
                    .remainingSeconds(0L)
                    .message("⚠️ 订单已超过 5 分钟超时取消，支付金额已自动原路退还至您的账户！")
                    .build();

        } else {
            record.setStatus("FAILED");
            paymentRecordMapper.updateById(record);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "订单支付状态流转失败: " +
                    (callbackResult != null ? callbackResult.getMessage() : "未知异常"));
        }
    }

    /**
     * 3. 查询支付流水状态
     */
    public PayResultVO getPaymentStatus(String paySn, String orderNo) {
        PaymentRecord record = null;
        if (paySn != null && !paySn.isBlank()) {
            record = paymentRecordMapper.selectOne(
                    new LambdaQueryWrapper<PaymentRecord>().eq(PaymentRecord::getPaySn, paySn)
            );
        } else if (orderNo != null && !orderNo.isBlank()) {
            record = paymentRecordMapper.selectOne(
                    new LambdaQueryWrapper<PaymentRecord>()
                            .eq(PaymentRecord::getOrderNo, orderNo)
                            .orderByDesc(PaymentRecord::getId)
                            .last("LIMIT 1")
            );
        }

        if (record == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "未找到对应支付记录");
        }

        Long currentUserId = UserContext.getUserId();
        String role = UserContext.getUserRole();
        if (currentUserId != null && !UserContext.isAdmin(role) && !currentUserId.equals(record.getUserId())) {
            throw new BusinessException(403, "无权查看他人的支付流水");
        }

        return PayResultVO.builder()
                .paySn(record.getPaySn())
                .orderNo(record.getOrderNo())
                .amount(record.getAmount())
                .payChannel(record.getPayChannel())
                .status(record.getStatus())
                .remainingSeconds(0L)
                .message("SUCCESS".equalsIgnoreCase(record.getStatus()) ? "已支付" : record.getStatus())
                .build();
    }

    private long calculateRemainingSeconds(String createdAtStr) {
        try {
            LocalDateTime createdAt = LocalDateTime.parse(createdAtStr);
            long elapsed = Duration.between(createdAt, LocalDateTime.now()).getSeconds();
            return Math.max(0, TIMEOUT_SECONDS - elapsed);
        } catch (Exception e) {
            return TIMEOUT_SECONDS;
        }
    }
}
