import React from 'react';
import { 
  Bot, 
  ShoppingBag, 
  Search, 
  User, 
  Activity, 
  Sparkles,
  Layers,
  LogOut,
  ShoppingCart
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';

interface NavbarProps {
  onOpenAiChat: () => void;
  onOpenOrders: () => void;
  onOpenCart: () => void;
  cartCount?: number;
  onOpenAuth: () => void;
  onOpenInspector: () => void;
  isInspectorOpen?: boolean;
  searchQuery: string;
  setSearchQuery: (q: string) => void;
  activeCategory: string;
  setActiveCategory: (cat: string) => void;
}

export const Navbar: React.FC<NavbarProps> = ({
  onOpenAiChat,
  onOpenOrders,
  onOpenCart,
  cartCount,
  onOpenAuth,
  onOpenInspector,
  isInspectorOpen,
  searchQuery,
  setSearchQuery,
  activeCategory,
  setActiveCategory,
}) => {
  const { user, isLoggedIn, logout } = useAuth();

  const categories = ['全部', '🔥 限时秒杀', '运动鞋', '手机', '智能数码'];

  return (
    <header className="sticky top-0 z-40 bg-white/90 backdrop-blur-md border-b border-slate-200 shadow-xs">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-16 gap-4">
          
          {/* Logo 与系统标题 */}
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-indigo-600 via-indigo-500 to-violet-500 flex items-center justify-center text-white shadow-md shadow-indigo-200">
              <Bot className="w-6 h-6 animate-pulse-slow" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-bold text-lg text-slate-900 tracking-tight">AI Mall 智购云</span>
                <span className="text-[10px] uppercase font-semibold px-1.5 py-0.5 rounded-sm bg-indigo-50 text-indigo-600 border border-indigo-200">
                  Microservices
                </span>
              </div>
              <p className="text-xs text-slate-500 hidden sm:block">分布式微服务 + 通义千问 Qwen3.7-Flash 智能导购平台</p>
            </div>
          </div>

          {/* 搜索框 */}
          <div className="flex-1 max-w-md hidden md:block">
            <div className="relative">
              <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                placeholder="搜索商品，或直接询问右下角 AI 导购..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full pl-10 pr-4 py-2 text-sm bg-slate-100 hover:bg-slate-50 focus:bg-white border border-slate-200 focus:border-indigo-500 rounded-full outline-hidden transition-all placeholder:text-slate-400"
              />
            </div>
          </div>

          {/* 快捷操作区 */}
          <div className="flex items-center gap-2 sm:gap-3">
            
            {/* 真实后端网关标识 */}
            <div
              title="当前直连微服务网关 http://localhost:8080"
              className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-full text-xs font-medium bg-emerald-50 text-emerald-700 border border-emerald-200"
            >
              <Activity className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">真实后端 (8080)</span>
            </div>

            {/* 架构透视仪按钮 */}
            <button
              onClick={onOpenInspector}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                isInspectorOpen
                  ? 'bg-slate-900 text-white shadow-sm ring-2 ring-indigo-500/50'
                  : 'text-slate-700 hover:bg-slate-100 border border-slate-200'
              }`}
              title="查看分布式调用链路与微服务拓扑"
            >
              <Layers className={`w-3.5 h-3.5 ${isInspectorOpen ? 'text-indigo-400' : 'text-indigo-600'}`} />
              <span className="hidden lg:inline">微服务链路</span>
              {isInspectorOpen && (
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse ml-0.5"></span>
              )}
            </button>

            {/* 购物车 */}
            <button
              onClick={onOpenCart}
              className="relative flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-slate-700 hover:bg-slate-100 border border-slate-200 transition-colors"
              title="查看购物车"
            >
              <ShoppingCart className="w-3.5 h-3.5 text-indigo-600" />
              <span className="hidden sm:inline">购物车</span>
              {cartCount !== undefined && cartCount > 0 && (
                <span className="inline-flex items-center justify-center min-w-[18px] h-[18px] px-1 text-[10px] font-black leading-none text-white bg-rose-500 rounded-full animate-in zoom-in shadow-xs">
                  {cartCount > 99 ? '99+' : cartCount}
                </span>
              )}
            </button>

            {/* 我的订单 */}
            <button
              onClick={onOpenOrders}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-slate-700 hover:bg-slate-100 border border-slate-200 transition-colors"
            >
              <ShoppingBag className="w-3.5 h-3.5 text-slate-600" />
              <span className="hidden sm:inline">我的订单</span>
            </button>

            {/* AI 导购核心按钮（醒目渐变） */}
            <button
              onClick={onOpenAiChat}
              className="relative flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-xs font-semibold bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-sm shadow-indigo-300 hover:shadow-md hover:shadow-indigo-400 transition-all hover:scale-105 active:scale-95"
            >
              <Sparkles className="w-3.5 h-3.5 animate-spin" style={{ animationDuration: '4s' }} />
              <span>AI 导购</span>
              <span className="flex h-2 w-2 relative">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-400"></span>
              </span>
            </button>

            {/* 用户登录 / 状态 */}
            {isLoggedIn ? (
              <div className="flex items-center gap-2 pl-1 border-l border-slate-200">
                <div className="flex items-center gap-1.5 text-xs text-slate-700 bg-slate-100 px-2.5 py-1.5 rounded-lg">
                  <User className="w-3.5 h-3.5 text-indigo-600" />
                  <span className="font-medium max-w-[80px] truncate">{user?.username}</span>
                </div>
                <button
                  onClick={logout}
                  title="退出登录"
                  className="p-1.5 text-slate-400 hover:text-rose-600 transition-colors"
                >
                  <LogOut className="w-3.5 h-3.5" />
                </button>
              </div>
            ) : (
              <button
                onClick={onOpenAuth}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-indigo-600 bg-indigo-50 hover:bg-indigo-100 transition-colors"
              >
                <User className="w-3.5 h-3.5" />
                <span>登录/注册</span>
              </button>
            )}

          </div>

        </div>

        {/* 分类快捷导航栏 */}
        <div className="flex items-center gap-1.5 overflow-x-auto py-2 border-t border-slate-100 scrollbar-none">
          {categories.map((cat) => {
            const isSeckillCat = cat.includes('秒杀');
            const isActive = activeCategory === cat;
            return (
              <button
                key={cat}
                onClick={() => setActiveCategory(cat)}
                className={`px-3 py-1 rounded-full text-xs font-medium whitespace-nowrap transition-all ${
                  isActive
                    ? isSeckillCat
                      ? 'bg-gradient-to-r from-rose-600 via-rose-500 to-amber-500 text-white shadow-md shadow-rose-200 font-bold scale-105'
                      : 'bg-slate-900 text-white shadow-xs'
                    : isSeckillCat
                    ? 'bg-rose-50 text-rose-600 hover:bg-rose-100 border border-rose-200 font-bold'
                    : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
                }`}
              >
                {cat}
              </button>
            );
          })}
          <div className="ml-auto text-[11px] text-slate-400 hidden md:flex items-center gap-2">
            <span>⚡ 支持 Redisson 分布式锁库存扣减</span>
            <span>•</span>
            <span>pgvector 语义检索增强</span>
          </div>
        </div>

      </div>
    </header>
  );
};
