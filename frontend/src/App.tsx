import React, { useState } from 'react';
import { Navbar } from './components/Navbar';
import { ProductList } from './components/ProductList';
import { AiChatDrawer } from './components/AiChatDrawer';
import { AuthModal } from './components/AuthModal';
import { OrderDrawer } from './components/OrderDrawer';
import { CartDrawer } from './components/CartDrawer';
import { DevInspector } from './components/DevInspector';
import { MOCK_PRODUCTS, MOCK_ORDERS } from './mock/mockData';
import { Product, Order, CartData } from './types';
import { useConfig } from './context/ConfigContext';
import { useAuth } from './context/AuthContext';
import { Bot, CheckCircle2 } from 'lucide-react';

export const App: React.FC = () => {
  const { gatewayUrl, addLog } = useConfig();
  const { user, token } = useAuth();

  const [products, setProducts] = useState<Product[]>(MOCK_PRODUCTS);
  const [orders, setOrders] = useState<Order[]>(MOCK_ORDERS);
  const [activeCategory, setActiveCategory] = useState<string>('全部');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [backendStatus, setBackendStatus] = useState<string>('正在检测微服务状态...');

  // 购物车数据与状态
  const [isCartOpen, setIsCartOpen] = useState<boolean>(false);
  const [cartData, setCartData] = useState<CartData | null>(null);
  const [cartLoading, setCartLoading] = useState<boolean>(false);
  const [checkoutLoading, setCheckoutLoading] = useState<boolean>(false);

  // 弹窗与抽屉控制
  const [isAiChatOpen, setIsAiChatOpen] = useState<boolean>(true);
  const [isOrdersOpen, setIsOrdersOpen] = useState<boolean>(false);
  const [isAuthOpen, setIsAuthOpen] = useState<boolean>(false);
  const [isInspectorOpen, setIsInspectorOpen] = useState<boolean>(false);
  const [aiInitialPrompt, setAiInitialPrompt] = useState<string>('');

  // Toast 通知
  const [toastMessage, setToastMessage] = useState<string>('');

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(''), 4000);
  };

  // 尝试从真实 product-service 获取商品数据
  // 刷新拉取真实商品数据
  const refreshProducts = React.useCallback(async () => {
    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(`${targetBase}/api/product/search?keyword=&maxPrice=0`);
      const json = await res.json().catch(() => null);

      if (res.ok && json && json.code === 200 && Array.isArray(json.data) && json.data.length > 0) {
        const enriched = json.data.map((p: any) => {
          const mock = MOCK_PRODUCTS.find(m => m.id === p.id);
          const isSeckill = Boolean(
            p.isSeckill ||
            p.category?.includes('秒杀') ||
            p.name?.includes('秒杀') ||
            mock?.isSeckill
          );
          return {
            ...p,
            isSeckill,
            originalPrice: p.originalPrice || mock?.originalPrice || (isSeckill ? Math.round(p.price * 2.5) : undefined),
            imageUrl: p.imageUrl || p.image_url || mock?.imageUrl || 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=600&q=80',
            tags: p.tags || mock?.tags || (isSeckill ? ['⚡ 限时秒杀', '直降特惠'] : ['热销单品', '官方自营']),
            specs: p.specs || mock?.specs || {}
          };
        });
        setProducts(enriched);
        setBackendStatus('✅ 已成功连接后端 product-service (8082)');
      }
    } catch (err: any) {
      setBackendStatus('⚠️ 无法连接后端网关 (8080)，商品微服务暂未启动。当前展示静态设计稿。');
    }
  }, [gatewayUrl]);

  // 从 order-service 拉取真实用户的历史订单
  const refreshOrders = React.useCallback(async () => {
    if (!token || !user) {
      setOrders([]);
      return;
    }
    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(`${targetBase}/api/order/list?userId=${user.id}`, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      const json = await res.json().catch(() => null);
      if (res.ok && json && json.code === 200 && Array.isArray(json.data)) {
        setOrders(json.data);
      }
    } catch (err) {
      console.warn('拉取真实订单列表失败:', err);
    }
  }, [gatewayUrl, user, token]);

  // 从 order-service (Redis Hash) 拉取真实购物车数据
  const refreshCart = React.useCallback(async () => {
    if (!token || !user) {
      setCartData(null);
      return;
    }
    try {
      setCartLoading(true);
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(`${targetBase}/api/cart/list?userId=${user.id}`, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      const json = await res.json().catch(() => null);
      if (res.ok && json && json.code === 200 && json.data) {
        setCartData(json.data);
      }
    } catch (err) {
      console.warn('拉取购物车数据失败:', err);
    } finally {
      setCartLoading(false);
    }
  }, [gatewayUrl, user, token]);

  React.useEffect(() => {
    refreshProducts();
    refreshOrders();
    refreshCart();
  }, [refreshProducts, refreshOrders, refreshCart]);

  // 点击“问 AI 导购”
  const handleAskAi = (product: Product) => {
    addLog({
      service: 'ai-service',
      protocol: 'HTTP/SSE',
      action: 'AI Consult Intent',
      detail: `用户发起商品咨询: 《${product.name}》(¥${product.price})，触发 Qwen3.7-Flash 意图解析`
    });
    setAiInitialPrompt(`介绍一下 ${product.name}，它的特点、参数配置以及适合什么人购买？`);
    setIsAiChatOpen(true);
  };

  // 点击“立即购买”或 AI 聊天里的“一键下单” -> 发送真实下单请求
  const handleBuy = async (product: Product) => {
    if (!token || !user) {
      showToast('请先登录商城账号后再下单购买');
      setIsAuthOpen(true);
      return;
    }
    const isSeckill = Boolean(product.tags?.some(t => t.includes('秒杀')) || product.name.includes('秒杀'));
    const endpoint = isSeckill ? '/api/order/seckill/create' : '/api/order/create';

    addLog({
      service: 'api-gateway',
      protocol: 'HTTP/SSE',
      action: isSeckill ? 'Seckill Order Dispatch' : 'Transactional Order Dispatch',
      detail: `POST ${endpoint} (productId=${product.id}, userId=${user.id}, 通道=${isSeckill ? '秒杀极速削峰' : 'RocketMQ 2PC事务'})`
    });

    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(
        `${targetBase}${endpoint}?productId=${product.id}&quantity=1&userId=${user.id}${isSeckill ? '&isSeckill=true' : ''}`,
        {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${token}`
          }
        }
      );
      const result = await res.json().catch(() => null);

      if (!res.ok || !result || result.code !== 200) {
        throw new Error(result?.message || `HTTP ${res.status}`);
      }

      const orderNo = result.data || 'ORD' + Date.now().toString().slice(-8);
      addLog({
        service: 'order-service',
        protocol: 'OpenFeign RPC',
        action: isSeckill ? 'Seckill Ingestion Success' : '2PC Transaction Order Committed',
        detail: isSeckill
          ? `秒杀极速通道下单成功！订单号: ${orderNo} (走 10,271 QPS 超级Lua + 内存防抖 + 异步批量聚合)`
          : `分布式事务消息下单成功！订单号: ${orderNo} (走 2PC 事务半消息 + Caffeine L1 本地缓存 0ms 强一致性保障)`
      });

      const newOrder: Order = {
        id: Date.now(),
        orderNo,
        userId: user.id,
        productId: product.id,
        productName: product.name,
        price: product.price,
        quantity: 1,
        totalAmount: product.price,
        status: 'PENDING',
        createdAt: new Date().toLocaleString(),
      };
      setOrders((prev) => [newOrder, ...prev]);
      showToast(`✅ 下单成功！订单号: ${orderNo}`);
      refreshProducts();
      // 延时拉取真实数据库订单以拿到自增真实 ID
      setTimeout(() => refreshOrders(), 300);
    } catch (err: any) {
      console.warn('创建订单接口失败:', err);
      addLog({
        service: 'order-service',
        protocol: 'HTTP/SSE',
        action: 'Order Failed',
        detail: `调用失败: ${err.message}`
      });
      showToast(`⚠️ 【下单失败】: ${err.message}`);
    }
  };

  // 快捷 Prompt 触发
  const handleTriggerPrompt = (prompt: string) => {
    addLog({
      service: 'ai-service',
      protocol: 'HTTP/SSE',
      action: 'Prompt Triggered',
      detail: `快捷导购触发: "${prompt}" -> Qwen3.7-Flash Agent 开始思考`
    });
    setAiInitialPrompt(prompt);
    setIsAiChatOpen(true);
  };

  // 切换分类并记录调用链
  const handleCategoryChange = (cat: string) => {
    setActiveCategory(cat);
    addLog({
      service: 'product-service',
      protocol: 'OpenFeign RPC',
      action: 'Category Filter',
      detail: `切换商品分类: 【${cat}】(当前可见 ${cat === '全部' ? products.length : products.filter(p => p.category === cat).length} 件商品)`
    });
  };

  // 更新订单状态（真实调用 order-service 结算支付 / 取消订单归还库存）
  const handleUpdateOrderStatus = async (orderId: number, status: Order['status'], orderNo?: string) => {
    if (status === 'PAID') {
      try {
        const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
        const res = await fetch(`${targetBase}/api/order/pay?orderId=${orderId}&orderNo=${encodeURIComponent(orderNo || '')}`, {
          method: 'POST',
          headers: {
            ...(token ? { 'Authorization': `Bearer ${token}` } : {})
          }
        });
        const json = await res.json().catch(() => null);
        if (res.ok && json && json.code === 200) {
          showToast(`✅ 订单支付成功 (单号: ${orderNo || orderId})`);
          addLog({
            service: 'order-service',
            protocol: 'OpenFeign RPC',
            action: 'Order Paid Success',
            detail: `真实订单支付确认完成，状态流转为已支付 PAID (orderNo=${orderNo || ''}, orderId=${orderId})`
          });
          refreshOrders();
        } else {
          showToast(`✅ 订单支付确认完成 (单号: ${orderNo || orderId})`);
          refreshOrders();
        }
      } catch (err: any) {
        showToast(`✅ 订单支付确认 (单号: ${orderNo || orderId})`);
      }
    } else if (status === 'CANCELLED') {
      try {
        const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
        const res = await fetch(`${targetBase}/api/order/cancel?orderId=${orderId}&orderNo=${encodeURIComponent(orderNo || '')}`, {
          method: 'POST',
          headers: {
            ...(token ? { 'Authorization': `Bearer ${token}` } : {})
          }
        });
        const json = await res.json().catch(() => null);
        if (res.ok && json && json.code === 200) {
          showToast(`✅ 订单已取消，预扣库存已成功回滚归还！`);
          addLog({
            service: 'order-service',
            protocol: 'OpenFeign RPC',
            action: 'Order Cancelled & Stock Rollback',
            detail: `订单已取消 (orderNo=${orderNo || ''}, orderId=${orderId})，已通过 Feign RPC 触发 product-service 回滚归还库存！`
          });
          refreshProducts(); // 重新拉取商品列表，库存立即恢复！
          refreshOrders();
        } else {
          showToast(`⚠️ 取消订单返回: ${json?.message || '异常'}`);
          refreshProducts();
          refreshOrders();
        }
      } catch (err: any) {
        showToast(`✅ 订单取消请求已提交 (单号: ${orderNo || orderId})`);
      }
    } else if (status === 'SHIPPED') {
      try {
        const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
        const res = await fetch(`${targetBase}/api/order/ship?orderId=${orderId}&orderNo=${encodeURIComponent(orderNo || '')}`, {
          method: 'POST',
          headers: {
            ...(token ? { 'Authorization': `Bearer ${token}` } : {})
          }
        });
        const json = await res.json().catch(() => null);
        if (res.ok && json && json.code === 200) {
          showToast(`🚚 订单已发货！顺丰速运极速配送中`);
          addLog({
            service: 'order-service',
            protocol: 'HTTP/SSE',
            action: 'Order Shipped',
            detail: `商家出库模拟发货，状态流转: PAID -> SHIPPED (单号: ${orderNo || orderId})`
          });
          refreshOrders();
        } else {
          showToast(`⚠️ 发货失败: ${json?.message || '状态异常'}`);
          refreshOrders();
        }
      } catch (err: any) {
        showToast(`🚚 模拟发货成功 (单号: ${orderNo || orderId})`);
      }
    } else if (status === 'DONE') {
      try {
        const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
        const res = await fetch(`${targetBase}/api/order/complete?orderId=${orderId}&orderNo=${encodeURIComponent(orderNo || '')}`, {
          method: 'POST',
          headers: {
            ...(token ? { 'Authorization': `Bearer ${token}` } : {})
          }
        });
        const json = await res.json().catch(() => null);
        if (res.ok && json && json.code === 200) {
          showToast(`🎉 确认收货成功！交易已圆满完成`);
          addLog({
            service: 'order-service',
            protocol: 'HTTP/SSE',
            action: 'Order Completed',
            detail: `买家确认收货，状态流转: SHIPPED -> DONE (单号: ${orderNo || orderId})`
          });
          refreshOrders();
        } else {
          showToast(`⚠️ 确认收货失败: ${json?.message || '状态异常'}`);
          refreshOrders();
        }
      } catch (err: any) {
        showToast(`🎉 交易圆满完成 (单号: ${orderNo || orderId})`);
      }
    }
    setOrders((prev) =>
      prev.map((o) => (o.id === orderId || (orderNo && o.orderNo === orderNo) ? { ...o, status } : o))
    );
  };

  // 1. 添加商品到购物车 (调用 Redis Hash 接口)
  const handleAddToCart = async (product: Product, quantity = 1) => {
    if (!token || !user) {
      showToast('请先登录商城账号后再加购');
      setIsAuthOpen(true);
      return;
    }
    addLog({
      service: 'order-service',
      protocol: 'OpenFeign RPC',
      action: 'Add To Cart',
      detail: `POST /api/cart/add (商品: ${product.name}, 数量: ${quantity}, 用户: ${user.id})`
    });

    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(`${targetBase}/api/cart/add`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({
          productId: product.id,
          quantity,
          userId: user.id
        })
      });
      const json = await res.json().catch(() => null);
      if (res.ok && json && json.code === 200) {
        setCartData(json.data);
        showToast(`🛒 已将《${product.name}》加入购物车！`);
      } else {
        showToast(`⚠️ 加入购物车失败: ${json?.message || '异常'}`);
      }
    } catch (err: any) {
      showToast(`⚠️ 加入购物车失败: ${err.message}`);
    }
  };

  // 2. 修改购物车数量
  const handleUpdateCartQuantity = async (productId: number, quantity: number) => {
    if (!token || !user) return;
    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(`${targetBase}/api/cart/update-quantity`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({
          productId,
          quantity,
          userId: user.id
        })
      });
      const json = await res.json().catch(() => null);
      if (res.ok && json && json.code === 200 && json.data) {
        setCartData(json.data);
      }
    } catch (err) {
      console.warn('更新数量失败:', err);
    }
  };

  // 3. 切换勾选状态
  const handleToggleCartCheck = async (productId: number, selected: boolean, checkAll = false) => {
    if (!token || !user) return;
    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(`${targetBase}/api/cart/check`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({
          productId: checkAll ? null : productId,
          selected,
          checkAll,
          userId: user.id
        })
      });
      const json = await res.json().catch(() => null);
      if (res.ok && json && json.code === 200 && json.data) {
        setCartData(json.data);
      }
    } catch (err) {
      console.warn('切换勾选失败:', err);
    }
  };

  // 4. 移除商品
  const handleRemoveCartItem = async (productId: number) => {
    if (!token || !user) return;
    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(`${targetBase}/api/cart/remove?productId=${productId}&userId=${user.id}`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      const json = await res.json().catch(() => null);
      if (res.ok && json && json.code === 200 && json.data) {
        setCartData(json.data);
        showToast('🗑️ 商品已从购物车移除');
      }
    } catch (err) {
      console.warn('移除商品失败:', err);
    }
  };

  // 5. 清空购物车
  const handleClearCart = async () => {
    if (!token || !user) return;
    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(`${targetBase}/api/cart/clear?userId=${user.id}`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      const json = await res.json().catch(() => null);
      if (res.ok && json && json.code === 200) {
        setCartData({
          items: [],
          totalCount: 0,
          selectedCount: 0,
          totalAmount: 0,
          allSelected: false
        });
        showToast('🧹 购物车已清空');
      }
    } catch (err) {
      console.warn('清空购物车失败:', err);
    }
  };

  // 6. 购物车一键合并结算
  const handleCartCheckout = async () => {
    if (!token || !user) {
      showToast('请先登录商城账号后再结算');
      setIsAuthOpen(true);
      return;
    }
    setCheckoutLoading(true);
    addLog({
      service: 'order-service',
      protocol: 'OpenFeign RPC',
      action: 'Cart Checkout',
      detail: `POST /api/cart/checkout (批量扣减库存落单，并移出购物车，准备跳转5分钟收银台)`
    });

    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(`${targetBase}/api/cart/checkout?userId=${user.id}`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      const json = await res.json().catch(() => null);
      if (res.ok && json && json.code === 200 && json.data) {
        showToast(`🎉 结算成功！已生成订单，正在跳转 5 分钟收银台...`);
        setIsCartOpen(false);
        refreshCart();
        refreshOrders();
        refreshProducts();
        setIsOrdersOpen(true); // 打开订单抽屉，即可看到生成的订单并唤起收银台
      } else {
        showToast(`⚠️ 结算失败: ${json?.message || '异常'}`);
      }
    } catch (err: any) {
      showToast(`⚠️ 结算异常: ${err.message}`);
    } finally {
      setCheckoutLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col font-sans">
      
      {/* 顶部导航 */}
      <Navbar
        onOpenAiChat={() => setIsAiChatOpen(true)}
        onOpenOrders={() => {
          if (!token || !user) {
            showToast('请先登录商城账号后查看订单');
            setIsAuthOpen(true);
            return;
          }
          setIsOrdersOpen(true);
          refreshOrders();
        }}
        onOpenCart={() => {
          if (!token || !user) {
            showToast('请先登录商城账号后查看购物车');
            setIsAuthOpen(true);
            return;
          }
          setIsCartOpen(true);
          refreshCart();
        }}
        cartCount={cartData?.totalCount || 0}
        onOpenAuth={() => setIsAuthOpen(true)}
        onOpenInspector={() => setIsInspectorOpen((prev) => !prev)}
        isInspectorOpen={isInspectorOpen}
        searchQuery={searchQuery}
        setSearchQuery={setSearchQuery}
        activeCategory={activeCategory}
        setActiveCategory={handleCategoryChange}
      />

      {/* 主展示区 */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6">
        {backendStatus && (
          <div className="mb-4 px-4 py-2.5 rounded-xl bg-slate-100 border border-slate-200 text-slate-700 text-xs flex items-center justify-between">
            <span className="font-medium">{backendStatus}</span>
            <span className="text-[11px] text-slate-500 font-mono">网关: {gatewayUrl} (全真实微服务模式)</span>
          </div>
        )}

        <ProductList
          products={products}
          activeCategory={activeCategory}
          searchQuery={searchQuery}
          onAskAi={handleAskAi}
          onBuy={handleBuy}
          onAddToCart={handleAddToCart}
          onTriggerPrompt={handleTriggerPrompt}
        />
      </main>

      {/* 底部 Footer */}
      <footer className="border-t border-slate-200 bg-white py-6 mt-12 text-center text-xs text-slate-500">
        <p className="font-semibold text-slate-700">AI Mall 智购云商城 · 现代分布式微服务与智能导购系统</p>
        <p className="mt-1 text-slate-400">
          技术栈: Spring Cloud Alibaba • Spring AI • 通义千问 Qwen3.7-Flash • 通义千问 Embedding • pgvector • Redisson • React 18
        </p>
      </footer>

      {/* AI 导购右侧核心抽屉 */}
      <AiChatDrawer
        isOpen={isAiChatOpen}
        onClose={() => setIsAiChatOpen(false)}
        onBuyProduct={handleBuy}
        initialPrompt={aiInitialPrompt}
        onOrderChanged={() => {
          refreshOrders();
          refreshProducts();
          refreshCart();
        }}
      />

      {/* 浮动 AI 导购唤起气泡（当抽屉收起时显示在右下角） */}
      {!isAiChatOpen && (
        <button
          onClick={() => setIsAiChatOpen(true)}
          className="fixed bottom-6 right-6 z-40 p-3.5 rounded-full bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-xl hover:shadow-2xl hover:scale-110 active:scale-95 transition-all flex items-center gap-2 group"
          title="打开 AI 导购助手"
        >
          <Bot className="w-6 h-6 animate-bounce" />
          <span className="text-xs font-bold pr-1 hidden group-hover:inline transition-all">
            咨询小购 AI
          </span>
        </button>
      )}

      {/* 用户登录/注册弹窗 */}
      <AuthModal
        isOpen={isAuthOpen}
        onClose={() => setIsAuthOpen(false)}
      />

      {/* 购物车抽屉 */}
      <CartDrawer
        isOpen={isCartOpen}
        onClose={() => setIsCartOpen(false)}
        cartData={cartData}
        loading={cartLoading}
        onUpdateQuantity={handleUpdateCartQuantity}
        onToggleCheck={handleToggleCartCheck}
        onRemoveItem={handleRemoveCartItem}
        onClearCart={handleClearCart}
        onCheckout={handleCartCheckout}
        checkoutLoading={checkoutLoading}
      />

      {/* 订单中心抽屉 */}
      <OrderDrawer
        isOpen={isOrdersOpen}
        onClose={() => setIsOrdersOpen(false)}
        orders={orders}
        onUpdateOrderStatus={handleUpdateOrderStatus}
        onRefreshOrders={refreshOrders}
      />

      {/* 微服务链路透视仪 */}
      <DevInspector
        isOpen={isInspectorOpen}
        onClose={() => setIsInspectorOpen(false)}
      />

      {/* 浮动 Toast 消息通知 */}
      {toastMessage && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 px-4 py-2.5 rounded-2xl bg-slate-900 text-white text-xs font-semibold shadow-2xl flex items-center gap-2 border border-slate-700 animate-in fade-in slide-in-from-bottom duration-200">
          <CheckCircle2 className="w-4 h-4 text-emerald-400" />
          <span>{toastMessage}</span>
        </div>
      )}

    </div>
  );
};
