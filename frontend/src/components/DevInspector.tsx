import React, { useState, useRef, useEffect } from 'react';
import { 
  X, 
  Layers, 
  ArrowRight, 
  Trash2, 
  PanelLeft, 
  PanelBottom, 
  Minimize2, 
  Maximize2,
  Sparkles, 
  ShieldCheck,
  Sun,
  Moon,
  Search,
  Activity
} from 'lucide-react';
import { useConfig } from '../context/ConfigContext';

interface DevInspectorProps {
  isOpen: boolean;
  onClose: () => void;
}

export const DevInspector: React.FC<DevInspectorProps> = ({ isOpen, onClose }) => {
  const { gatewayUrl, setGatewayUrl, logs, clearLogs } = useConfig();
  const [dockMode, setDockMode] = useState<'left' | 'bottom'>('left');
  const [isMinimized, setIsMinimized] = useState<boolean>(false);
  const [theme, setTheme] = useState<'light' | 'dark'>('light'); // 默认采用清晰明亮的高对比度浅色模式
  const [selectedService, setSelectedService] = useState<string>('all');
  const [filterKeyword, setFilterKeyword] = useState<string>('');
  const logsEndRef = useRef<HTMLDivElement>(null);

  // 当产生新日志时自动平滑滚动到底部
  useEffect(() => {
    if (isOpen && !isMinimized) {
      logsEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [logs.length, isOpen, isMinimized]);

  if (!isOpen) return null;

  // 过滤日志
  const filteredLogs = logs.filter(log => {
    const matchService = selectedService === 'all' || log.service === selectedService;
    const matchKeyword = !filterKeyword || 
      log.action.toLowerCase().includes(filterKeyword.toLowerCase()) || 
      log.detail.toLowerCase().includes(filterKeyword.toLowerCase()) ||
      log.protocol.toLowerCase().includes(filterKeyword.toLowerCase());
    return matchService && matchKeyword;
  });

  // 最小化悬浮胶囊状态
  if (isMinimized) {
    return (
      <div className="fixed bottom-6 left-6 z-50 animate-in fade-in slide-in-from-bottom duration-200">
        <button
          onClick={() => setIsMinimized(false)}
          className={`px-4 py-3 rounded-full shadow-2xl flex items-center gap-3 text-xs font-bold transition-all hover:scale-105 active:scale-95 group border ${
            theme === 'light'
              ? 'bg-white text-slate-800 border-indigo-200 shadow-indigo-100 hover:border-indigo-400'
              : 'bg-slate-900 text-white border-indigo-500/50 shadow-slate-900 hover:border-indigo-400'
          }`}
          title="点击展开微服务调用链路透视仪"
        >
          <span className="flex h-2.5 w-2.5 relative">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-emerald-500"></span>
          </span>
          <Layers className="w-4 h-4 text-indigo-500 group-hover:rotate-12 transition-transform" />
          <span>链路透视仪 ({logs.length})</span>
          <Maximize2 className="w-4 h-4 text-slate-400 group-hover:text-indigo-600 transition-colors" />
        </button>
      </div>
    );
  }

  // 针对不同服务的高亮配色 (支持明亮与暗色双重对比)
  const getServiceBadgeStyle = (service: string) => {
    if (theme === 'light') {
      switch (service) {
        case 'api-gateway':
          return 'bg-indigo-50 text-indigo-700 border-indigo-200';
        case 'ai-service':
          return 'bg-purple-50 text-purple-700 border-purple-200';
        case 'product-service':
          return 'bg-sky-50 text-sky-700 border-sky-200';
        case 'order-service':
          return 'bg-amber-50 text-amber-800 border-amber-200';
        case 'user-service':
          return 'bg-emerald-50 text-emerald-700 border-emerald-200';
        default:
          return 'bg-slate-100 text-slate-700 border-slate-200';
      }
    } else {
      switch (service) {
        case 'api-gateway':
          return 'bg-indigo-950/80 text-indigo-300 border-indigo-700/60';
        case 'ai-service':
          return 'bg-purple-950/80 text-purple-300 border-purple-700/60';
        case 'product-service':
          return 'bg-sky-950/80 text-sky-300 border-sky-700/60';
        case 'order-service':
          return 'bg-amber-950/80 text-amber-300 border-amber-700/60';
        case 'user-service':
          return 'bg-emerald-950/80 text-emerald-300 border-emerald-700/60';
        default:
          return 'bg-slate-800 text-slate-300 border-slate-700';
      }
    }
  };

  const isLight = theme === 'light';

  return (
    <aside 
      className={`fixed z-40 flex flex-col shadow-2xl transition-all duration-300 border ${
        isLight
          ? 'bg-white text-slate-800 border-slate-200 shadow-xl'
          : 'bg-slate-900 text-slate-100 border-slate-700 shadow-black/40'
      } ${
        dockMode === 'left'
          ? 'top-16 left-0 bottom-0 w-full sm:w-[500px] max-w-[95vw] border-r'
          : 'bottom-0 left-0 right-0 h-80 sm:h-96 border-t'
      }`}
    >
      {/* 顶部标题工具栏 (纯白不透明) */}
      <div className={`flex items-center justify-between px-4 py-3 border-b ${
        isLight ? 'bg-white border-slate-200' : 'bg-slate-950 border-slate-800'
      }`}>
        <div className="flex items-center gap-2.5">
          <div className="p-1.5 rounded-lg bg-indigo-600 text-white shadow-sm shadow-indigo-300">
            <Layers className="w-4 h-4" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className={`font-bold text-sm tracking-tight ${isLight ? 'text-slate-900' : 'text-white'}`}>
                微服务链路透视仪
              </h3>
              <span className="flex h-2 w-2 relative">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
              </span>
              <span className={`text-[11px] font-semibold px-1.5 py-0.2 rounded-full border ${
                isLight ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-emerald-950 text-emerald-400 border-emerald-800'
              }`}>
                无遮罩穿透
              </span>
            </div>
            <p className={`text-xs ${isLight ? 'text-slate-500' : 'text-slate-400'}`}>
              实时监听网关路由、OpenFeign RPC 与 AI Tool 调用
            </p>
          </div>
        </div>

        {/* 顶部操作按钮组 */}
        <div className="flex items-center gap-1">
          {/* 主题切换按钮：明亮 / 暗色 */}
          <button
            onClick={() => setTheme(prev => prev === 'light' ? 'dark' : 'light')}
            className={`p-1.5 rounded-lg transition-colors border ${
              isLight 
                ? 'bg-slate-100 text-slate-700 border-slate-200 hover:bg-slate-200' 
                : 'bg-slate-800 text-amber-300 border-slate-700 hover:bg-slate-700'
            }`}
            title={isLight ? '切换为极客炫黑模式' : '切换为高亮清晰浅色模式'}
          >
            {isLight ? <Moon className="w-4 h-4" /> : <Sun className="w-4 h-4" />}
          </button>

          {/* 切换停靠方向：左侧边栏 / 底部控制台 */}
          <button
            onClick={() => setDockMode(prev => prev === 'left' ? 'bottom' : 'left')}
            className={`p-1.5 rounded-lg transition-colors border ${
              isLight 
                ? 'bg-slate-100 text-slate-700 border-slate-200 hover:bg-slate-200' 
                : 'bg-slate-800 text-slate-300 border-slate-700 hover:bg-slate-700'
            }`}
            title={dockMode === 'left' ? '停靠在底部控制台 (Chrome Console)' : '停靠在左侧栏'}
          >
            {dockMode === 'left' ? <PanelBottom className="w-4 h-4" /> : <PanelLeft className="w-4 h-4" />}
          </button>

          {/* 最小化 */}
          <button
            onClick={() => setIsMinimized(true)}
            className={`p-1.5 rounded-lg transition-colors border ${
              isLight 
                ? 'bg-slate-100 text-slate-700 border-slate-200 hover:bg-slate-200' 
                : 'bg-slate-800 text-slate-300 border-slate-700 hover:bg-slate-700'
            }`}
            title="最小化为左下角悬浮药丸"
          >
            <Minimize2 className="w-4 h-4" />
          </button>

          {/* 关闭 */}
          <button
            onClick={onClose}
            className={`p-1.5 rounded-lg transition-colors border ${
              isLight 
                ? 'bg-slate-100 text-slate-600 border-slate-200 hover:bg-rose-50 hover:text-rose-600 hover:border-rose-200' 
                : 'bg-slate-800 text-slate-400 border-slate-700 hover:bg-rose-950 hover:text-rose-400'
            }`}
            title="关闭透视仪"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* 核心微服务拓扑状态条 (亮丽高对比卡片) */}
      <div className={`p-3 border-b ${
        isLight ? 'bg-slate-50 border-slate-200' : 'bg-slate-950 border-slate-800'
      } ${dockMode === 'bottom' ? 'hidden sm:block' : ''}`}>
        <div className="grid grid-cols-4 gap-2 text-center">
          <div className={`p-2 rounded-xl border transition-all ${
            isLight 
              ? 'bg-white border-indigo-200 shadow-xs' 
              : 'bg-slate-900 border-indigo-800/60'
          }`}>
            <div className="text-indigo-600 font-bold text-xs">api-gateway</div>
            <div className={`text-[11px] font-medium ${isLight ? 'text-slate-500' : 'text-slate-400'}`}>
              :8080 · JWT鉴权
            </div>
          </div>

          <div className={`p-2 rounded-xl border transition-all ${
            isLight 
              ? 'bg-purple-50 border-purple-200 shadow-xs' 
              : 'bg-purple-950/40 border-purple-800/60'
          }`}>
            <div className="text-purple-600 font-bold text-xs">ai-service</div>
            <div className="text-[11px] font-bold text-emerald-600">
              :8084 · Qwen3.7
            </div>
          </div>

          <div className={`p-2 rounded-xl border transition-all ${
            isLight 
              ? 'bg-white border-sky-200 shadow-xs' 
              : 'bg-slate-900 border-sky-800/60'
          }`}>
            <div className="text-sky-600 font-bold text-xs">product</div>
            <div className={`text-[11px] font-medium ${isLight ? 'text-slate-500' : 'text-slate-400'}`}>
              :8082 · Redisson锁
            </div>
          </div>

          <div className={`p-2 rounded-xl border transition-all ${
            isLight 
              ? 'bg-white border-amber-200 shadow-xs' 
              : 'bg-slate-900 border-amber-800/60'
          }`}>
            <div className="text-amber-600 font-bold text-xs">order</div>
            <div className={`text-[11px] font-medium ${isLight ? 'text-slate-500' : 'text-slate-400'}`}>
              :8083 · 分布式锁
            </div>
          </div>
        </div>

        <div className={`mt-2.5 text-center text-xs font-medium flex items-center justify-center gap-1.5 ${
          isLight ? 'text-slate-500' : 'text-slate-400'
        }`}>
          <span className="font-semibold text-indigo-600">网关路由</span>
          <ArrowRight className="w-3 h-3 text-slate-400" />
          <span className="font-semibold text-sky-600">OpenFeign RPC</span>
          <ArrowRight className="w-3 h-3 text-slate-400" />
          <span className="font-semibold text-amber-600">Redisson 看门狗锁</span>
          <ArrowRight className="w-3 h-3 text-slate-400" />
          <span className="font-semibold text-purple-600">Qwen Tool Calling</span>
        </div>
      </div>

      {/* 搜索与过滤工具栏 */}
      <div className={`px-3 py-2 border-b flex flex-wrap items-center justify-between gap-2 text-xs ${
        isLight ? 'bg-slate-50 border-slate-200 text-slate-700' : 'bg-slate-950 border-slate-800 text-slate-300'
      }`}>
        {/* 关键字搜索框 */}
        <div className="relative flex-1 min-w-[140px]">
          <Search className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            placeholder="搜索动作 / 协议 / RPC内容..."
            value={filterKeyword}
            onChange={(e) => setFilterKeyword(e.target.value)}
            className={`w-full pl-8 pr-2.5 py-1 rounded-lg text-xs border outline-none transition-all ${
              isLight 
                ? 'bg-white border-slate-200 text-slate-800 placeholder-slate-400 focus:border-indigo-400 focus:ring-1 focus:ring-indigo-100' 
                : 'bg-slate-800 border-slate-700 text-slate-100 placeholder-slate-500 focus:border-indigo-500'
            }`}
          />
        </div>

        {/* 服务过滤标签 */}
        <div className="flex items-center gap-1 shrink-0 overflow-x-auto pb-0.5">
          {[
            { id: 'all', label: '全部' },
            { id: 'api-gateway', label: '网关' },
            { id: 'ai-service', label: 'AI' },
            { id: 'product-service', label: '商品' },
            { id: 'order-service', label: '订单' },
          ].map((item) => (
            <button
              key={item.id}
              onClick={() => setSelectedService(item.id)}
              className={`px-2 py-0.5 rounded-md text-[11px] font-medium border transition-colors ${
                selectedService === item.id
                  ? (isLight ? 'bg-indigo-600 text-white border-indigo-600 shadow-xs' : 'bg-indigo-500 text-white border-indigo-500')
                  : (isLight ? 'bg-white text-slate-600 border-slate-200 hover:bg-slate-100' : 'bg-slate-800 text-slate-400 border-slate-700 hover:text-white')
              }`}
            >
              {item.label}
            </button>
          ))}

          {/* 清空日志 */}
          <button
            onClick={clearLogs}
            className={`p-1 rounded-md text-xs border transition-colors ml-1 ${
              isLight 
                ? 'bg-white text-slate-500 border-slate-200 hover:text-rose-600 hover:border-rose-200 hover:bg-rose-50' 
                : 'bg-slate-800 text-slate-400 border-slate-700 hover:text-rose-400'
            }`}
            title="清空日志"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* 实时提示条 */}
      <div className={`px-3 py-1.5 flex items-center justify-between text-xs border-b ${
        isLight ? 'bg-emerald-50 border-emerald-100 text-emerald-800' : 'bg-emerald-950/30 border-emerald-900/40 text-emerald-300'
      }`}>
        <span className="flex items-center gap-1.5 font-medium">
          <Sparkles className="w-3.5 h-3.5 text-emerald-500 shrink-0" />
          <span>无遮罩：可随时点击页面【购买】、【分类】或【问AI】实时观察</span>
        </span>
        <span className="text-[11px] opacity-80">共 {filteredLogs.length} 条记录</span>
      </div>

      {/* 实时调用链日志内容区 */}
      <div className={`flex-1 overflow-y-auto p-3 space-y-2.5 font-mono text-xs ${
        isLight ? 'bg-slate-100' : 'bg-slate-900'
      }`}>
        {filteredLogs.length === 0 ? (
          <div className="text-center py-12 space-y-2">
            <Activity className="w-8 h-8 text-slate-300 mx-auto" />
            <div className={`text-xs font-medium ${isLight ? 'text-slate-400' : 'text-slate-500'}`}>
              暂无匹配调用日志
            </div>
            <p className={`text-[11px] ${isLight ? 'text-slate-400' : 'text-slate-600'}`}>
              在商城尝试点击商品、立即购买或与 AI 导购对话，此处将高亮捕获！
            </p>
          </div>
        ) : (
          filteredLogs.map((log) => (
            <div
              key={log.id}
              className={`p-3 rounded-xl border transition-all duration-150 animate-in fade-in ${
                isLight 
                  ? 'bg-white border-slate-200/90 shadow-sm hover:border-indigo-300 hover:shadow-md' 
                  : 'bg-slate-800/90 border-slate-700/80 hover:border-slate-600'
              }`}
            >
              {/* 日志头部：时间与协议 */}
              <div className="flex items-center justify-between text-xs pb-1.5 mb-1.5 border-b border-dashed border-slate-200/80">
                <span className={`font-semibold ${isLight ? 'text-slate-500' : 'text-slate-400'}`}>
                  {log.time}
                </span>
                <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold tracking-wide border uppercase ${
                  isLight 
                    ? 'bg-slate-100 text-slate-700 border-slate-200' 
                    : 'bg-slate-700 text-slate-200 border-slate-600'
                }`}>
                  {log.protocol}
                </span>
              </div>

              {/* 微服务与动作名称 */}
              <div className="flex items-center gap-2 mb-1.5">
                <span className={`px-2 py-0.5 rounded-md text-[11px] font-bold border ${getServiceBadgeStyle(log.service)}`}>
                  {log.service}
                </span>
                <span className={`font-bold text-xs sm:text-sm tracking-tight ${
                  isLight ? 'text-slate-900' : 'text-white'
                }`}>
                  {log.action}
                </span>
              </div>

              {/* 详细调用参数 / 报文数据卡片 */}
              <div className={`p-2 rounded-lg text-xs leading-relaxed break-all border font-mono ${
                isLight 
                  ? 'bg-slate-50 text-slate-700 border-slate-200' 
                  : 'bg-slate-950/80 text-emerald-300 border-slate-800'
              }`}>
                {log.detail}
              </div>
            </div>
          ))
        )}
        <div ref={logsEndRef} />
      </div>

      {/* 底部配置栏 (明亮清晰) */}
      <div className={`px-4 py-2.5 border-t flex items-center justify-between text-xs font-medium ${
        isLight ? 'bg-white border-slate-200 text-slate-600' : 'bg-slate-950 border-slate-800 text-slate-400'
      }`}>
        <span className="flex items-center gap-1.5">
          <ShieldCheck className="w-4 h-4 text-emerald-500" />
          <span className="text-emerald-700 font-bold">全链路真实调用</span>
        </span>
        <div className="flex items-center gap-2">
          <span>网关:</span>
          <input
            type="text"
            value={gatewayUrl}
            onChange={(e) => setGatewayUrl(e.target.value)}
            className={`border rounded-md px-2 py-1 text-xs font-mono outline-none transition-colors w-44 ${
              isLight 
                ? 'bg-slate-50 border-slate-300 text-slate-800 focus:border-indigo-500 focus:bg-white' 
                : 'bg-slate-900 border-slate-700 text-white focus:border-indigo-500'
            }`}
          />
        </div>
      </div>
    </aside>
  );
};
