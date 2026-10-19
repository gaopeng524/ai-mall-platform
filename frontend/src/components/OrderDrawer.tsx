import React, { useState, useEffect, useMemo } from 'react';
import { 
  X, 
  ShoppingBag, 
  CheckCircle2, 
  Clock, 
  Ban, 
  CreditCard, 
  ShieldCheck, 
  RefreshCw, 
  Search, 
  Copy, 
  Check, 
  AlertTriangle, 
  Package, 
  ArrowRight,
  Truck
} from 'lucide-react';
import { Order } from '../types';
import { useConfig } from '../context/ConfigContext';
import { useAuth } from '../context/AuthContext';

interface OrderDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  orders: Order[];
  onUpdateOrderStatus: (orderId: number, status: Order['status'], orderNo?: string) => void;
  onRefreshOrders?: () => void;
}

interface PayResultVO {
  paySn: string;
  orderNo: string;
  amount: number;
  payChannel: string;
  status: string;
  remainingSeconds: number;
  message?: string;
}

type OrderTab = 'ALL' | 'PENDING' | 'PAID' | 'SHIPPED' | 'DONE' | 'CANCELLED';

export const OrderDrawer: React.FC<OrderDrawerProps> = ({
  isOpen,
  onClose,
  orders,
  onUpdateOrderStatus,
  onRefreshOrders,
}) => {
  const { gatewayUrl, addLog } = useConfig();
  const { token } = useAuth();
  
  // 状态管理
  const [activeTab, setActiveTab] = useState<OrderTab>('ALL');
  const [searchKeyword, setSearchKeyword] = useState<string>('');
  const [copiedOrderNo, setCopiedOrderNo] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [now, setNow] = useState<number>(Date.now());

  // 收银台 Modal 状态
  const [cashierOrder, setCashierOrder] = useState<Order | null>(null);
  const [payInfo, setPayInfo] = useState<PayResultVO | null>(null);
  const [loadingPay, setLoadingPay] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [cancellingId, setCancellingId] = useState<number | null>(null);
  const [payChannel, setPayChannel] = useState<'MOCK' | 'ALIPAY' | 'WECHAT'>('MOCK');
  const [cashierCountdown, setCashierCountdown] = useState<number>(300);
  const [payMessage, setPayMessage] = useState<string | null>(null);

  // 1. 每秒心跳计时器，驱动卡片 5 分钟倒计时与收银台倒计时
  useEffect(() => {
    if (!isOpen) return;
    const timer = setInterval(() => {
      setNow(Date.now());
    }, 1000);
    return () => clearInterval(timer);
  }, [isOpen]);

  // 2. 打开抽屉时触发拉取最新订单
  useEffect(() => {
    if (isOpen && onRefreshOrders) {
      setRefreshing(true);
      Promise.resolve(onRefreshOrders()).finally(() => {
        setTimeout(() => setRefreshing(false), 400);
      });
    }
  }, [isOpen]);

  // 3. 收银台独立倒计时
  useEffect(() => {
    if (!cashierOrder || cashierCountdown <= 0) return;
    const timer = setInterval(() => {
      setCashierCountdown((prev) => (prev <= 1 ? 0 : prev - 1));
    }, 1000);
    return () => clearInterval(timer);
  }, [cashierOrder, cashierCountdown]);

  // 4. 所有 useMemo 钩子置于顶部无条件执行（严格遵循 Rules of Hooks）
  const safeOrders = useMemo(() => (Array.isArray(orders) ? orders : []), [orders]);

  const counts = useMemo(() => {
    const pending = safeOrders.filter((o) => o?.status === 'PENDING').length;
    const paid = safeOrders.filter((o) => o?.status === 'PAID').length;
    const shipped = safeOrders.filter((o) => o?.status === 'SHIPPED').length;
    const done = safeOrders.filter((o) => o?.status === 'DONE').length;
    const cancelled = safeOrders.filter((o) => o?.status === 'CANCELLED').length;
    return {
      all: safeOrders.length,
      pending,
      paid,
      shipped,
      done,
      cancelled,
    };
  }, [safeOrders]);

  const filteredOrders = useMemo(() => {
    return safeOrders.filter((order) => {
      if (!order) return false;
      // Tab 过滤
      if (activeTab === 'PENDING' && order.status !== 'PENDING') return false;
      if (activeTab === 'PAID' && order.status !== 'PAID') return false;
      if (activeTab === 'SHIPPED' && order.status !== 'SHIPPED') return false;
      if (activeTab === 'DONE' && order.status !== 'DONE') return false;
      if (activeTab === 'CANCELLED' && order.status !== 'CANCELLED') return false;

      // 搜索关键字过滤
      if (searchKeyword.trim()) {
        const kw = searchKeyword.trim().toLowerCase();
        const matchNo = order.orderNo?.toLowerCase().includes(kw);
        const matchName = order.productName?.toLowerCase().includes(kw);
        return matchNo || matchName;
      }
      return true;
    });
  }, [safeOrders, activeTab, searchKeyword]);

  // 复制订单号
  const handleCopyOrderNo = (orderNo: string) => {
    try {
      if (navigator?.clipboard?.writeText) {
        navigator.clipboard.writeText(orderNo);
      }
      setCopiedOrderNo(orderNo);
      setTimeout(() => setCopiedOrderNo(null), 1800);
    } catch {
      setCopiedOrderNo(orderNo);
      setTimeout(() => setCopiedOrderNo(null), 1800);
    }
  };

  // 手动刷新订单列表
  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      if (onRefreshOrders) {
        await onRefreshOrders();
      }
    } finally {
      setTimeout(() => setRefreshing(false), 500);
    }
  };

  // 解析并计算待支付订单剩余有效时间 (5 分钟 = 300 秒)
  const getOrderRemainingSeconds = (createdAtStr?: string): number => {
    try {
      if (!createdAtStr) return 300;
      const normalized = createdAtStr.includes('T') ? createdAtStr : createdAtStr.replace(' ', 'T');
      const createTime = new Date(normalized).getTime();
      if (isNaN(createTime)) return 300;
      const elapsed = Math.floor((now - createTime) / 1000);
      return Math.max(0, 300 - elapsed);
    } catch {
      return 300;
    }
  };

  const formatCountdown = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  // 唤起收银台 (调用 payment-service 创建流水)
  const handleOpenCashier = async (order: Order) => {
    setLoadingPay(true);
    setPayMessage(null);
    setCashierOrder(order);

    const remainingSecs = getOrderRemainingSeconds(order.createdAt);
    setCashierCountdown(remainingSecs > 0 ? remainingSecs : 0);

    addLog({
      service: 'payment-service',
      protocol: 'OpenFeign RPC',
      action: 'Create Cashier',
      detail: `订单 ${order.orderNo} 唤起统一收银台 (应付 ¥${order.totalAmount})`
    });

    try {
      const res = await fetch(`${gatewayUrl}/api/pay/create`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { 'Authorization': `Bearer ${token}` } : {})
        },
        body: JSON.stringify({
          orderNo: order.orderNo,
          userId: order.userId,
          payChannel: payChannel
        })
      });
      const data = await res.json();
      if (data.code === 200 && data.data) {
        setPayInfo(data.data);
        if (data.data.remainingSeconds !== undefined) {
          setCashierCountdown(data.data.remainingSeconds);
        }
      } else {
        setPayMessage(data.message || '唤起收银台失败');
      }
    } catch (err: any) {
      setPayInfo({
        paySn: `PAY${Date.now()}`,
        orderNo: order.orderNo,
        amount: order.totalAmount,
        payChannel: 'MOCK',
        status: 'INIT',
        remainingSeconds: remainingSecs
      });
    } finally {
      setLoadingPay(false);
    }
  };

  // 确认付款
  const handleConfirmPay = async () => {
    if (!cashierOrder || !payInfo) return;
    setSubmitting(true);

    addLog({
      service: 'payment-service',
      protocol: 'OpenFeign RPC',
      action: 'Confirm Payment',
      detail: `流水号 ${payInfo.paySn} 确认扣款，触发 CAS 状态流转 PENDING -> PAID`
    });

    try {
      const res = await fetch(`${gatewayUrl}/api/pay/mock-submit`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { 'Authorization': `Bearer ${token}` } : {})
        },
        body: JSON.stringify({
          paySn: payInfo.paySn,
          payPassword: '111111'
        })
      });
      const data = await res.json();
      if (data.code === 200) {
        setPayMessage('🎉 ' + (data.message || '支付成功！'));
        onUpdateOrderStatus(cashierOrder.id, 'PAID', cashierOrder.orderNo);
        setTimeout(() => {
          setCashierOrder(null);
          setPayInfo(null);
        }, 1200);
      } else if (data.data?.status === 'REFUNDED') {
        setPayMessage('⚠️ 订单已超过 5 分钟超时取消，款项已原路自动退回！');
        onUpdateOrderStatus(cashierOrder.id, 'CANCELLED', cashierOrder.orderNo);
        setTimeout(() => {
          setCashierOrder(null);
          setPayInfo(null);
        }, 2000);
      } else {
        setPayMessage('❌ ' + (data.message || '支付失败'));
      }
    } catch (err: any) {
      onUpdateOrderStatus(cashierOrder.id, 'PAID', cashierOrder.orderNo);
      setPayMessage('🎉 模拟支付成功 (单机容错完成)');
      setTimeout(() => {
        setCashierOrder(null);
        setPayInfo(null);
      }, 1000);
    } finally {
      setSubmitting(false);
    }
  };

  // 取消订单（释放预扣库存并归还）
  const handleCancelOrder = async (order: Order) => {
    setCancellingId(order.id);
    try {
      await onUpdateOrderStatus(order.id, 'CANCELLED', order.orderNo);
    } finally {
      setCancellingId(null);
    }
  };

  // 渲染状态徽章
  const renderStatusBadge = (status: Order['status'], remainingSecs: number) => {
    switch (status) {
      case 'PENDING':
        if (remainingSecs <= 0) {
          return (
            <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-rose-600 bg-rose-50 border border-rose-200 px-2 py-0.5 rounded-full">
              <AlertTriangle className="w-3 h-3 text-rose-500" />
              已超时 (待关单)
            </span>
          );
        }
        return (
          <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-amber-600 bg-amber-50 border border-amber-200 px-2 py-0.5 rounded-full">
            <Clock className="w-3 h-3 text-amber-500 animate-pulse" />
            待支付
          </span>
        );
      case 'PAID':
        return (
          <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-600 bg-emerald-50 border border-emerald-200 px-2 py-0.5 rounded-full">
            <CheckCircle2 className="w-3 h-3 text-emerald-500" />
            已付款
          </span>
        );
      case 'SHIPPED':
        return (
          <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-indigo-600 bg-indigo-50 border border-indigo-200 px-2 py-0.5 rounded-full">
            <Package className="w-3 h-3 text-indigo-500" />
            已发货
          </span>
        );
      case 'DONE':
        return (
          <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-teal-600 bg-teal-50 border border-teal-200 px-2 py-0.5 rounded-full">
            <CheckCircle2 className="w-3 h-3 text-teal-500" />
            交易完成
          </span>
        );
      case 'CANCELLED':
        return (
          <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-slate-400 bg-slate-100 border border-slate-200 px-2 py-0.5 rounded-full">
            <Ban className="w-3 h-3" />
            已取消 · 库存已归还
          </span>
        );
      default:
        return null;
    }
  };

  // 核心：所有 Hooks 均已无条件执行完毕后，再做 isOpen 拦截判断
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-slate-900/40 backdrop-blur-sm animate-in fade-in duration-200">
      <div className="w-full max-w-xl bg-white h-full shadow-2xl flex flex-col border-l border-slate-200 animate-in slide-in-from-right duration-300">
        
        {/* 1. 顶栏头部 */}
        <div className="p-5 border-b border-slate-200 bg-slate-50/70">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <div className="w-9 h-9 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center border border-indigo-100 shadow-sm">
                <ShoppingBag className="w-5 h-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h3 className="font-bold text-slate-900 text-base">我的订单中心</h3>
                  <span className="text-[11px] font-semibold bg-indigo-100 text-indigo-700 px-2 py-0.5 rounded-full">
                    {counts.all} 笔订单
                  </span>
                </div>
                <p className="text-[11px] text-slate-500 mt-0.5">
                  5分钟限时支付 · RocketMQ 延迟关单 · 统一收银台
                </p>
              </div>
            </div>

            <div className="flex items-center gap-1.5">
              {/* 刷新按钮 */}
              <button
                onClick={handleRefresh}
                title="刷新订单列表"
                disabled={refreshing}
                className="p-2 text-slate-500 hover:text-indigo-600 rounded-lg hover:bg-slate-100 transition-colors disabled:opacity-50"
              >
                <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin text-indigo-600' : ''}`} />
              </button>
              {/* 关闭按钮 */}
              <button
                onClick={onClose}
                className="p-2 text-slate-400 hover:text-slate-600 rounded-lg hover:bg-slate-100 transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
          </div>

          {/* 搜索框 */}
          <div className="mt-3.5 relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
            <input
              type="text"
              placeholder="搜索订单编号或商品名称..."
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              className="w-full pl-9 pr-8 py-1.5 text-xs bg-white border border-slate-200 rounded-xl outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/20 transition-all placeholder:text-slate-400"
            />
            {searchKeyword && (
              <button
                onClick={() => setSearchKeyword('')}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 text-xs"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            )}
          </div>

          {/* 2. 状态 Filter Tabs */}
          <div className="flex items-center gap-1.5 mt-3 pt-2 border-t border-slate-200/60 overflow-x-auto scrollbar-none">
            <button
              onClick={() => setActiveTab('ALL')}
              className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold whitespace-nowrap transition-all ${
                activeTab === 'ALL'
                  ? 'bg-slate-900 text-white shadow-sm'
                  : 'text-slate-600 hover:bg-slate-200/70'
              }`}
            >
              <span>全部</span>
              <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${activeTab === 'ALL' ? 'bg-slate-700 text-slate-200' : 'bg-slate-200 text-slate-600'}`}>
                {counts.all}
              </span>
            </button>

            <button
              onClick={() => setActiveTab('PENDING')}
              className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold whitespace-nowrap transition-all ${
                activeTab === 'PENDING'
                  ? 'bg-amber-500 text-white shadow-sm'
                  : 'text-slate-600 hover:bg-slate-200/70'
              }`}
            >
              <span>待支付</span>
              <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${activeTab === 'PENDING' ? 'bg-amber-600 text-amber-100' : 'bg-amber-100 text-amber-700'}`}>
                {counts.pending}
              </span>
            </button>

            <button
              onClick={() => setActiveTab('PAID')}
              className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold whitespace-nowrap transition-all ${
                activeTab === 'PAID'
                  ? 'bg-emerald-600 text-white shadow-sm'
                  : 'text-slate-600 hover:bg-slate-200/70'
              }`}
            >
              <span>已付款</span>
              <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${activeTab === 'PAID' ? 'bg-emerald-700 text-emerald-100' : 'bg-emerald-100 text-emerald-700'}`}>
                {counts.paid}
              </span>
            </button>

            <button
              onClick={() => setActiveTab('SHIPPED')}
              className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold whitespace-nowrap transition-all ${
                activeTab === 'SHIPPED'
                  ? 'bg-indigo-600 text-white shadow-sm'
                  : 'text-slate-600 hover:bg-slate-200/70'
              }`}
            >
              <span>已发货</span>
              <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${activeTab === 'SHIPPED' ? 'bg-indigo-700 text-indigo-100' : 'bg-indigo-100 text-indigo-700'}`}>
                {counts.shipped}
              </span>
            </button>

            <button
              onClick={() => setActiveTab('DONE')}
              className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold whitespace-nowrap transition-all ${
                activeTab === 'DONE'
                  ? 'bg-teal-600 text-white shadow-sm'
                  : 'text-slate-600 hover:bg-slate-200/70'
              }`}
            >
              <span>已完成</span>
              <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${activeTab === 'DONE' ? 'bg-teal-700 text-teal-100' : 'bg-teal-100 text-teal-700'}`}>
                {counts.done}
              </span>
            </button>

            <button
              onClick={() => setActiveTab('CANCELLED')}
              className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold whitespace-nowrap transition-all ${
                activeTab === 'CANCELLED'
                  ? 'bg-slate-600 text-white shadow-sm'
                  : 'text-slate-600 hover:bg-slate-200/70'
              }`}
            >
              <span>已取消</span>
              <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${activeTab === 'CANCELLED' ? 'bg-slate-700 text-slate-200' : 'bg-slate-200 text-slate-600'}`}>
                {counts.cancelled}
              </span>
            </button>
          </div>
        </div>

        {/* 3. 订单列表主体 */}
        <div className="flex-1 overflow-y-auto p-4 space-y-3.5 bg-slate-50/40">
          {filteredOrders.length > 0 ? (
            filteredOrders.map((order) => {
              const remainingSecs = order.status === 'PENDING' ? getOrderRemainingSeconds(order.createdAt) : 0;
              const isExpired = order.status === 'PENDING' && remainingSecs <= 0;
              const percent = Math.min(100, Math.max(0, (remainingSecs / 300) * 100));
              const price = Number(order.price) || 0;
              const totalAmount = Number(order.totalAmount) || 0;

              return (
                <div
                  key={order.id || order.orderNo}
                  className={`rounded-2xl bg-white border transition-all duration-200 shadow-sm hover:shadow-md ${
                    order.status === 'PENDING'
                      ? isExpired
                        ? 'border-rose-200 hover:border-rose-300'
                        : 'border-amber-200 hover:border-amber-300'
                      : 'border-slate-200 hover:border-indigo-200'
                  }`}
                >
                  {/* 卡片头部：订单号与状态 */}
                  <div className="p-3.5 pb-2.5 flex items-center justify-between border-b border-slate-100">
                    <div className="flex items-center gap-2">
                      <span className="text-[11px] font-mono font-bold text-slate-700">
                        {order.orderNo}
                      </span>
                      <button
                        onClick={() => handleCopyOrderNo(order.orderNo)}
                        title="复制单号"
                        className="p-1 text-slate-400 hover:text-indigo-600 rounded-md hover:bg-slate-100 transition-colors"
                      >
                        {copiedOrderNo === order.orderNo ? (
                          <Check className="w-3.5 h-3.5 text-emerald-600" />
                        ) : (
                          <Copy className="w-3.5 h-3.5" />
                        )}
                      </button>
                    </div>
                    {renderStatusBadge(order.status, remainingSecs)}
                  </div>

                  {/* 卡片内容：商品信息 */}
                  <div className="p-4 space-y-3">
                    <div className="flex items-start justify-between gap-3">
                      <div className="space-y-1">
                        <h4 className="font-bold text-slate-900 text-sm leading-snug line-clamp-2">
                          {order.productName}
                        </h4>
                        <div className="flex items-center gap-2 text-xs text-slate-400">
                          <span>数量: x{order.quantity || 1}</span>
                          <span>•</span>
                          <span>单价: ¥{price.toFixed(2)}</span>
                        </div>
                      </div>
                      <div className="text-right whitespace-nowrap">
                        <p className="text-[11px] text-slate-400">实付总额</p>
                        <span className="text-lg font-black text-rose-600">
                          ¥{totalAmount.toFixed(2)}
                        </span>
                      </div>
                    </div>

                    {/* 5 分钟支付倒计时横条 (针对 PENDING 待支付订单) */}
                    {order.status === 'PENDING' && (
                      <div className={`p-2.5 rounded-xl border space-y-1.5 ${
                        isExpired
                          ? 'bg-rose-50 border-rose-200 text-rose-700'
                          : 'bg-gradient-to-r from-amber-50 to-orange-50 border-amber-200 text-amber-800'
                      }`}>
                        <div className="flex items-center justify-between text-xs font-semibold">
                          <div className="flex items-center gap-1.5">
                            <Clock className={`w-3.5 h-3.5 ${isExpired ? 'text-rose-500' : 'text-amber-600 animate-pulse'}`} />
                            <span>{isExpired ? '已超时' : '剩余支付时间'}</span>
                          </div>
                          <span className="font-mono font-bold text-sm">
                            {isExpired ? '00:00 (待系统关单)' : formatCountdown(remainingSecs)}
                          </span>
                        </div>
                        {/* 倒计时微型进度条 */}
                        {!isExpired && (
                          <div className="w-full bg-amber-200/60 rounded-full h-1.5 overflow-hidden">
                            <div 
                              className="bg-gradient-to-r from-amber-500 to-orange-500 h-full rounded-full transition-all duration-1000 ease-linear"
                              style={{ width: `${percent}%` }}
                            />
                          </div>
                        )}
                        <p className="text-[10px] text-slate-500 leading-tight">
                          {isExpired
                            ? '订单已超过 5 分钟有效支付期，可点击取消立即归还预扣库存。'
                            : '⚡ 订单将在 5 分钟后由 RocketMQ 延迟关单，超时将自动回滚库存。'}
                        </p>
                      </div>
                    )}

                    {/* 下单时间与流转状态提示 */}
                    <div className="text-[11px] text-slate-400 flex items-center justify-between pt-1">
                      <span>下单时间: {order.createdAt}</span>
                      {order.status === 'PAID' && (
                        <span className="text-emerald-600 font-medium flex items-center gap-1">
                          <CheckCircle2 className="w-3 h-3" /> 支付核销已完成
                        </span>
                      )}
                      {order.status === 'SHIPPED' && (
                        <span className="text-indigo-600 font-medium flex items-center gap-1">
                          <Truck className="w-3 h-3" /> 顺丰速运派送中
                        </span>
                      )}
                      {order.status === 'DONE' && (
                        <span className="text-teal-600 font-medium flex items-center gap-1">
                          <CheckCircle2 className="w-3 h-3" /> 订单已签收完成
                        </span>
                      )}
                    </div>
                  </div>

                  {/* 卡片底部操作栏 */}
                  {order.status === 'PENDING' && (
                    <div className="px-4 py-3 bg-slate-50/70 rounded-b-2xl border-t border-slate-100 flex items-center justify-end gap-2.5">
                      <button
                        onClick={() => handleCancelOrder(order)}
                        disabled={cancellingId === order.id}
                        className="py-1.5 px-3 rounded-xl border border-slate-200 hover:bg-slate-100 text-slate-600 text-xs font-medium transition-colors disabled:opacity-50"
                      >
                        {cancellingId === order.id ? '取消中...' : isExpired ? '关闭订单归还库存' : '取消订单'}
                      </button>

                      <button
                        onClick={() => handleOpenCashier(order)}
                        disabled={loadingPay && cashierOrder?.id === order.id}
                        className="py-1.5 px-4 rounded-xl bg-gradient-to-r from-indigo-600 to-violet-600 hover:from-indigo-700 hover:to-violet-700 active:scale-98 text-white text-xs font-bold flex items-center gap-1.5 transition-all shadow-sm shadow-indigo-200 disabled:opacity-50"
                      >
                        {loadingPay && cashierOrder?.id === order.id ? (
                          <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                        ) : (
                          <CreditCard className="w-3.5 h-3.5" />
                        )}
                        <span>{loadingPay && cashierOrder?.id === order.id ? '唤起中...' : '立即付款'}</span>
                        <ArrowRight className="w-3 h-3" />
                      </button>
                    </div>
                  )}

                  {order.status === 'PAID' && (
                    <div className="px-4 py-2.5 bg-emerald-50/50 rounded-b-2xl border-t border-emerald-100/60 flex items-center justify-between text-xs text-emerald-800">
                      <div className="flex items-center gap-1.5 font-medium">
                        <ShieldCheck className="w-3.5 h-3.5 text-emerald-600" />
                        <span>已支付成功 · 仓库配货中</span>
                      </div>
                      <button
                        onClick={() => onUpdateOrderStatus(order.id, 'SHIPPED', order.orderNo)}
                        className="py-1.5 px-3.5 rounded-xl bg-indigo-600 hover:bg-indigo-700 active:scale-95 text-white text-xs font-bold flex items-center gap-1.5 transition-all shadow-sm shadow-indigo-200"
                        title="商家履约出库"
                      >
                        <Truck className="w-3.5 h-3.5" />
                        <span>模拟发货</span>
                      </button>
                    </div>
                  )}

                  {order.status === 'SHIPPED' && (
                    <div className="px-4 py-2.5 bg-indigo-50/50 rounded-b-2xl border-t border-indigo-100/60 flex items-center justify-between text-xs text-indigo-800">
                      <div className="flex items-center gap-1.5 font-medium">
                        <Truck className="w-3.5 h-3.5 text-indigo-600 animate-pulse" />
                        <span>顺丰速运极速派送中</span>
                      </div>
                      <button
                        onClick={() => onUpdateOrderStatus(order.id, 'DONE', order.orderNo)}
                        className="py-1.5 px-3.5 rounded-xl bg-gradient-to-r from-teal-600 to-emerald-600 hover:from-teal-700 hover:to-emerald-700 active:scale-95 text-white text-xs font-bold flex items-center gap-1.5 transition-all shadow-sm shadow-teal-200"
                        title="买家确认签收"
                      >
                        <CheckCircle2 className="w-3.5 h-3.5" />
                        <span>确认收货</span>
                      </button>
                    </div>
                  )}

                  {order.status === 'DONE' && (
                    <div className="px-4 py-2.5 bg-teal-50/50 rounded-b-2xl border-t border-teal-100/60 flex items-center justify-between text-xs text-teal-800">
                      <div className="flex items-center gap-1.5 font-medium">
                        <CheckCircle2 className="w-3.5 h-3.5 text-teal-600" />
                        <span>交易圆满完成 · 感谢惠顾</span>
                      </div>
                      <span className="text-[11px] text-teal-600/70 font-mono font-medium">全链路闭环</span>
                    </div>
                  )}
                </div>
              );
            })
          ) : (
            <div className="text-center py-20 text-slate-400 space-y-3">
              <div className="w-14 h-14 mx-auto rounded-2xl bg-slate-100 flex items-center justify-center text-slate-400">
                <ShoppingBag className="w-7 h-7 stroke-1" />
              </div>
              <div className="space-y-1">
                <p className="text-sm font-bold text-slate-600">
                  {searchKeyword
                    ? '没有找到符合搜索条件的订单'
                    : activeTab === 'PENDING'
                    ? '暂无待支付订单'
                    : activeTab === 'PAID'
                    ? '暂无待发货订单'
                    : activeTab === 'SHIPPED'
                    ? '暂无待收货订单'
                    : activeTab === 'DONE'
                    ? '暂无已完成订单'
                    : activeTab === 'CANCELLED'
                    ? '暂无已取消订单'
                    : '暂无任何订单记录'}
                </p>
                <p className="text-xs text-slate-400 max-w-xs mx-auto">
                  {searchKeyword
                    ? '请尝试更换搜索关键字，或清除搜索框'
                    : '可以选购心仪商品或对右下角 AI 导购说“帮我买一双李宁赤兔鞋”立即体验全链路！'}
                </p>
              </div>
              {searchKeyword && (
                <button
                  onClick={() => setSearchKeyword('')}
                  className="px-3 py-1 text-xs text-indigo-600 bg-indigo-50 hover:bg-indigo-100 rounded-lg transition-colors font-medium"
                >
                  清除搜索关键字
                </button>
              )}
            </div>
          )}
        </div>

      </div>

      {/* 4. 统一支付收银台 Modal */}
      {cashierOrder && (
        <div className="fixed inset-0 z-60 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-in fade-in duration-150">
          <div className="w-full max-w-sm bg-white rounded-3xl shadow-2xl border border-slate-100 overflow-hidden animate-in zoom-in-95 duration-200">
            {/* 收银台顶栏 */}
            <div className="bg-gradient-to-r from-indigo-600 via-indigo-500 to-violet-600 p-5 text-white flex justify-between items-center">
              <div className="flex items-center gap-2">
                <ShieldCheck className="w-5 h-5 text-indigo-200" />
                <h4 className="font-bold text-sm tracking-wide">AI-Mall 安全收银台</h4>
              </div>
              <button
                onClick={() => setCashierOrder(null)}
                className="text-white/80 hover:text-white p-1 rounded-full hover:bg-white/10 transition-colors"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* 收银台主体内容 */}
            <div className="p-6 space-y-4 text-center">
              {/* 金额 */}
              <div>
                <p className="text-xs text-slate-400 font-medium">应付总额</p>
                <div className="text-3xl font-black text-slate-900 mt-1 tracking-tight">
                  ¥{(Number(cashierOrder.totalAmount) || 0).toFixed(2)}
                </div>
              </div>

              {/* 5 分钟倒计时卡片 */}
              <div className="p-3 bg-amber-50 rounded-2xl border border-amber-200 flex items-center justify-center gap-2 text-amber-700 text-xs font-semibold">
                <Clock className="w-4 h-4 animate-pulse text-amber-600" />
                <span>支付剩余时间：</span>
                <span className="font-mono text-sm font-black text-amber-800">
                  {formatCountdown(cashierCountdown)}
                </span>
              </div>

              {/* 流水与订单信息 */}
              <div className="text-[11px] text-slate-500 space-y-1.5 text-left bg-slate-50 p-3 rounded-xl border border-slate-100">
                <div className="flex justify-between">
                  <span>订单编号:</span>
                  <span className="font-mono text-slate-700 font-bold">{cashierOrder.orderNo}</span>
                </div>
                {payInfo && (
                  <div className="flex justify-between">
                    <span>支付流水:</span>
                    <span className="font-mono text-indigo-600 font-bold">{payInfo.paySn}</span>
                  </div>
                )}
                <div className="flex justify-between">
                  <span>商品名称:</span>
                  <span className="text-slate-700 font-medium truncate max-w-[180px]">{cashierOrder.productName}</span>
                </div>
              </div>

              {/* 支付通道选择 */}
              <div className="space-y-2 text-left">
                <label className="text-xs font-bold text-slate-700">选择支付方式</label>
                <div className="grid grid-cols-3 gap-2">
                  <button
                    type="button"
                    onClick={() => setPayChannel('MOCK')}
                    className={`py-2 px-1 text-center text-xs font-medium rounded-xl border transition-all ${
                      payChannel === 'MOCK'
                        ? 'border-indigo-600 bg-indigo-50 text-indigo-700 font-bold ring-2 ring-indigo-600/20'
                        : 'border-slate-200 text-slate-600 hover:border-slate-300'
                    }`}
                  >
                    ⚡ 极速模拟
                  </button>
                  <button
                    type="button"
                    onClick={() => setPayChannel('ALIPAY')}
                    className={`py-2 px-1 text-center text-xs font-medium rounded-xl border transition-all ${
                      payChannel === 'ALIPAY'
                        ? 'border-sky-500 bg-sky-50 text-sky-700 font-bold ring-2 ring-sky-500/20'
                        : 'border-slate-200 text-slate-600 hover:border-slate-300'
                    }`}
                  >
                    🔵 支付宝
                  </button>
                  <button
                    type="button"
                    onClick={() => setPayChannel('WECHAT')}
                    className={`py-2 px-1 text-center text-xs font-medium rounded-xl border transition-all ${
                      payChannel === 'WECHAT'
                        ? 'border-emerald-500 bg-emerald-50 text-emerald-700 font-bold ring-2 ring-emerald-500/20'
                        : 'border-slate-200 text-slate-600 hover:border-slate-300'
                    }`}
                  >
                    🟢 微信支付
                  </button>
                </div>
              </div>

              {/* 消息提示 */}
              {payMessage && (
                <div className="p-2.5 rounded-xl text-xs font-medium bg-slate-100 text-slate-700">
                  {payMessage}
                </div>
              )}

              {/* 支付按钮 */}
              <button
                onClick={handleConfirmPay}
                disabled={submitting || cashierCountdown <= 0}
                className="w-full py-3 rounded-2xl bg-gradient-to-r from-indigo-600 to-violet-600 hover:from-indigo-700 hover:to-violet-700 active:scale-98 text-white font-bold text-sm shadow-lg shadow-indigo-600/25 transition-all flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {submitting ? (
                  <>
                    <RefreshCw className="w-4 h-4 animate-spin" />
                    <span>安全结算中...</span>
                  </>
                ) : (
                  <>
                    <CreditCard className="w-4 h-4" />
                    <span>立即支付 ¥{(Number(cashierOrder.totalAmount) || 0).toFixed(2)}</span>
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
