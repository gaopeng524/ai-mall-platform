package com.aimall.payment.controller;

import com.aimall.common.result.Result;
import com.aimall.payment.dto.MockPaySubmitDTO;
import com.aimall.payment.dto.PayCreateDTO;
import com.aimall.payment.service.PaymentService;
import com.aimall.payment.vo.PayResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/pay")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 1. 唤起收银台：生成支付流水单 (状态: INIT)
     */
    @PostMapping("/create")
    public Result<PayResultVO> createPayment(@Valid @RequestBody PayCreateDTO dto) {
        log.info("[收银台唤起] 收到支付请求: orderNo={}, channel={}", dto.getOrderNo(), dto.getPayChannel());
        PayResultVO vo = paymentService.createPayment(dto);
        return Result.success("收银台订单创建成功", vo);
    }

    /**
     * 2. 模拟用户确认付款：驱动支付与 CAS 订单状态机闭环 (支持防资损自动退款)
     */
    @PostMapping("/mock-submit")
    public Result<PayResultVO> mockSubmitPay(@Valid @RequestBody MockPaySubmitDTO dto) {
        log.info("[收银台支付扣款] 用户确认付款: paySn={}", dto.getPaySn());
        PayResultVO vo = paymentService.mockSubmitPay(dto);
        return Result.success(vo.getMessage(), vo);
    }

    /**
     * 3. 轮询查询支付流水状态
     */
    @GetMapping("/status")
    public Result<PayResultVO> getPaymentStatus(
            @RequestParam(value = "paySn", required = false) String paySn,
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        PayResultVO vo = paymentService.getPaymentStatus(paySn, orderNo);
        return Result.success(vo);
    }
}
