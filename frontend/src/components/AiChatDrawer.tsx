import React, { useState, useRef, useEffect } from 'react';
import { 
  Bot, 
  X, 
  Send, 
  Trash2, 
  Sparkles, 
  CheckCircle2, 
  Loader2, 
  Database, 
  Cpu, 
  ShoppingCart,
  Maximize2,
  Minimize2
} from 'lucide-react';
import { ChatMessage, Product } from '../types';
import { startAiStream } from '../services/aiStream';
import { useAuth } from '../context/AuthContext';
import { useConfig } from '../context/ConfigContext';

interface AiChatDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  onBuyProduct: (product: Product) => void;
  initialPrompt?: string;
  onOrderChanged?: () => void;
}

export const AiChatDrawer: React.FC<AiChatDrawerProps> = ({
  isOpen,
  onClose,
  onBuyProduct,
  initialPrompt,
  onOrderChanged,
}) => {
  const { token } = useAuth();
  const { gatewayUrl, addLog } = useConfig();

  const [sessionId] = useState<string>(() => 'sess_' + Math.random().toString(36).substring(2, 10));
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: 'welcome',
      role: 'assistant',
      content: '您好！我是您的智能导购助手“小购” 🤖\n\n我深度整合了商城后台的 **pgvector 向量知识库** 与 **商品/订单/购物车微服务**。您可以问我：\n• “帮我推荐一双 500 元以内的跑鞋”\n• “对比一下小米 14 和 iPhone 15 Pro”\n• “直接帮我下单李宁赤兔跑鞋”\n• “查一下我的订单，或取消未付款订单”',
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    }
  ]);
  const [input, setInput] = useState<string>('');
  const [isStreaming, setIsStreaming] = useState<boolean>(false);
  const [isExpanded, setIsExpanded] = useState<boolean>(false);

  const messagesEndRef = useRef<HTMLDivElement>(null);

  // 自动滚动到底部
  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    if (isOpen) {
      scrollToBottom();
    }
  }, [messages, isOpen]);

  // 处理外部传入的快捷提问
  useEffect(() => {
    if (initialPrompt && isOpen && !isStreaming) {
      handleSend(initialPrompt);
    }
  }, [initialPrompt, isOpen]);

  const handleSend = async (textToSend?: string) => {
    const query = (textToSend || input).trim();
    if (!query || isStreaming) return;

    setInput('');

    // 1. 添加用户消息
    const userMessage: ChatMessage = {
      id: Math.random().toString(36).substring(2, 9),
      role: 'user',
      content: query,
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    };

    // 2. 初始化 AI 回复占位消息
    const assistantMessageId = Math.random().toString(36).substring(2, 9);
    const initialAssistantMessage: ChatMessage = {
      id: assistantMessageId,
      role: 'assistant',
      content: '',
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      toolSteps: [],
      embeddedProducts: []
    };

    setMessages((prev) => [...prev, userMessage, initialAssistantMessage]);
    setIsStreaming(true);

    // 3. 启动流式传输
    await startAiStream(
      sessionId,
      query,
      token,
      gatewayUrl,
      {
        onChunk: (chunk) => {
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantMessageId
                ? { ...msg, content: msg.content + chunk }
                : msg
            )
          );
        },
        onToolStep: (step) => {
          setMessages((prev) =>
            prev.map((msg) => {
              if (msg.id !== assistantMessageId) return msg;
              const existingSteps = msg.toolSteps || [];
              const index = existingSteps.findIndex((s) => s.type === step.type);
              let updatedSteps;
              if (index >= 0) {
                updatedSteps = [...existingSteps];
                updatedSteps[index] = step;
              } else {
                updatedSteps = [...existingSteps, step];
              }
              return { ...msg, toolSteps: updatedSteps };
            })
          );
        },
        onEmbeddedProducts: (products) => {
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantMessageId
                ? { ...msg, embeddedProducts: products }
                : msg
            )
          );
        },
        onComplete: () => {
          setIsStreaming(false);
          onOrderChanged?.();
        },
        onError: (err) => {
          console.error(err);
          setIsStreaming(false);
        }
      },
      addLog
    );
  };

  const handleClearMemory = async () => {
    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      await fetch(`${targetBase}/api/ai/chat/session/${encodeURIComponent(sessionId)}`, {
        method: 'DELETE',
        headers: token ? { 'Authorization': `Bearer ${token}` } : {}
      });
    } catch (e) {
      console.warn('清理后端会话缓存跳过:', e);
    }
    setMessages([
      {
        id: 'reset',
        role: 'system',
        content: '会话上下文（含 Redis 历史记忆）已重置。您可以开始新的提问。',
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      }
    ]);
  };

  if (!isOpen) return null;

  return (
    <div 
      className={`fixed top-0 right-0 h-full z-50 bg-white/95 backdrop-blur-xl border-l border-slate-200 shadow-2xl flex flex-col transition-all duration-300 ${
        isExpanded ? 'w-full sm:w-[680px]' : 'w-full sm:w-[460px]'
      }`}
    >
      {/* 头部标题栏 */}
      <div className="flex items-center justify-between px-5 py-4 border-b border-slate-200 bg-gradient-to-r from-indigo-50/50 to-violet-50/50">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-indigo-600 to-violet-600 flex items-center justify-center text-white shadow-xs">
            <Bot className="w-5 h-5" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="font-bold text-slate-900 text-sm">小购 · AI 导购 Agent</h2>
              <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200">
                Spring AI Agent
              </span>
            </div>
            <p className="text-[11px] text-slate-500">
              Redis 会话记忆 • OpenFeign Tool Calling 实时推流
            </p>
          </div>
        </div>

        <div className="flex items-center gap-1">
          {/* 清除记忆 */}
          <button
            onClick={handleClearMemory}
            title="清空会话记忆 (Reset Redis ChatMemory)"
            className="p-2 text-slate-400 hover:text-rose-600 rounded-lg hover:bg-slate-100 transition-colors"
          >
            <Trash2 className="w-4 h-4" />
          </button>

          {/* 展开/缩小宽屏 */}
          <button
            onClick={() => setIsExpanded(!isExpanded)}
            title={isExpanded ? '紧凑视图' : '宽屏视图'}
            className="p-2 text-slate-400 hover:text-slate-700 rounded-lg hover:bg-slate-100 transition-colors hidden sm:block"
          >
            {isExpanded ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}
          </button>

          {/* 关闭抽屉 */}
          <button
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-slate-700 rounded-lg hover:bg-slate-100 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>
      </div>

      {/* 消息历史滚动区域 */}
      <div className="flex-1 overflow-y-auto p-4 sm:p-5 space-y-4 text-sm">
        {messages.map((msg) => (
          <div
            key={msg.id}
            className={`flex flex-col ${msg.role === 'user' ? 'items-end' : 'items-start'}`}
          >
            {/* 角色标识与时间 */}
            <div className="flex items-center gap-1.5 text-[10px] text-slate-400 mb-1 px-1">
              <span>{msg.role === 'user' ? '您' : msg.role === 'system' ? '系统提示' : '小购 AI'}</span>
              <span>•</span>
              <span>{msg.timestamp}</span>
            </div>

            {/* 工具调用步骤卡片 (Tool Calling 进度，面试官最爱看这个！) */}
            {msg.toolSteps && msg.toolSteps.length > 0 && (
              <div className="w-full max-w-[90%] mb-2 space-y-1.5">
                {msg.toolSteps.map((step, idx) => (
                  <div
                    key={idx}
                    className="p-2.5 rounded-xl bg-slate-50 border border-slate-200 text-xs flex items-center justify-between"
                  >
                    <div className="flex items-center gap-2">
                      {step.type === 'vector_search' ? (
                        <Database className="w-4 h-4 text-violet-600" />
                      ) : (
                        <Cpu className="w-4 h-4 text-indigo-600" />
                      )}
                      <div>
                        <div className="font-semibold text-slate-800">{step.title}</div>
                        {step.detail && <div className="text-[11px] text-slate-500">{step.detail}</div>}
                      </div>
                    </div>
                    <div>
                      {step.status === 'running' ? (
                        <Loader2 className="w-4 h-4 text-indigo-600 animate-spin" />
                      ) : (
                        <CheckCircle2 className="w-4 h-4 text-emerald-600" />
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* 消息正文气泡 */}
            <div
              className={`rounded-2xl px-4 py-3 leading-relaxed max-w-[92%] sm:max-w-[85%] ${
                msg.role === 'user'
                  ? 'bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-sm'
                  : msg.role === 'system'
                  ? 'bg-slate-100 text-slate-600 text-xs text-center mx-auto'
                  : 'bg-white border border-slate-200 text-slate-800 shadow-xs'
              }`}
            >
              <div className="whitespace-pre-wrap break-words">
                {msg.content}
                {isStreaming && msg.id === messages[messages.length - 1].id && (
                  <span className="typewriter-cursor inline-block" />
                )}
              </div>
            </div>

            {/* 嵌套在对话中的交互商品卡片 */}
            {msg.embeddedProducts && msg.embeddedProducts.length > 0 && (
              <div className="w-full max-w-[92%] sm:max-w-[85%] mt-2 space-y-2">
                <div className="text-[11px] font-semibold text-slate-500 flex items-center gap-1">
                  <Sparkles className="w-3 h-3 text-indigo-600" /> AI 识别并推荐商品卡片：
                </div>
                {msg.embeddedProducts.map((p) => (
                  <div
                    key={p.id}
                    className="p-3 bg-white rounded-xl border border-indigo-100 shadow-xs flex items-center justify-between gap-3 hover:border-indigo-300 transition-colors"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <img
                        src={p.imageUrl}
                        alt={p.name}
                        className="w-12 h-12 rounded-lg object-cover bg-slate-100 shrink-0"
                      />
                      <div className="min-w-0">
                        <div className="font-bold text-xs text-slate-900 truncate">{p.name}</div>
                        <div className="text-xs font-black text-rose-600 mt-0.5">
                          ¥{Number(p.price).toFixed(2)}
                          <span className="text-[10px] text-slate-400 font-normal ml-1">库存:{p.stock}</span>
                        </div>
                      </div>
                    </div>
                    <button
                      onClick={() => onBuyProduct(p)}
                      className="shrink-0 px-3 py-1.5 rounded-lg text-xs font-semibold bg-indigo-600 hover:bg-indigo-700 text-white transition-colors flex items-center gap-1 shadow-xs"
                    >
                      <ShoppingCart className="w-3 h-3" />
                      <span>下单</span>
                    </button>
                  </div>
                ))}
              </div>
            )}

          </div>
        ))}
        <div ref={messagesEndRef} />
      </div>

      {/* 底部快捷 Prompt 提示词 */}
      <div className="px-4 py-2 border-t border-slate-100 bg-slate-50/50 flex items-center gap-2 overflow-x-auto scrollbar-none text-xs">
        <button
          onClick={() => handleSend('推荐一双适合跑步的鞋子，预算500')}
          className="px-2.5 py-1 rounded-full bg-white border border-slate-200 text-slate-600 hover:border-indigo-300 hover:text-indigo-600 shrink-0 transition-colors"
        >
          👟 500元预算跑鞋
        </button>
        <button
          onClick={() => handleSend('对比苹果和小米手机的参数')}
          className="px-2.5 py-1 rounded-full bg-white border border-slate-200 text-slate-600 hover:border-indigo-300 hover:text-indigo-600 shrink-0 transition-colors"
        >
          📱 手机横向对比
        </button>
        <button
          onClick={() => handleSend('帮我下单李宁赤兔6跑鞋')}
          className="px-2.5 py-1 rounded-full bg-white border border-slate-200 text-slate-600 hover:border-indigo-300 hover:text-indigo-600 shrink-0 transition-colors"
        >
          ⚡ 一键购买测试
        </button>
        <button
          onClick={() => handleSend('查询我最近的订单列表')}
          className="px-2.5 py-1 rounded-full bg-white border border-slate-200 text-slate-600 hover:border-indigo-300 hover:text-indigo-600 shrink-0 transition-colors"
        >
          📦 查询我的订单
        </button>
      </div>

      {/* 底部输入框 */}
      <div className="p-4 border-t border-slate-200 bg-white">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            handleSend();
          }}
          className="flex items-center gap-2"
        >
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            disabled={isStreaming}
            placeholder={isStreaming ? 'AI 正在回复中...' : '输入需求或商品名称，支持多轮对话...'}
            className="flex-1 px-4 py-2.5 text-sm bg-slate-100 focus:bg-white border border-slate-200 focus:border-indigo-500 rounded-xl outline-hidden transition-all disabled:opacity-50"
          />
          <button
            type="submit"
            disabled={!input.trim() || isStreaming}
            className="p-2.5 bg-indigo-600 hover:bg-indigo-700 disabled:bg-slate-300 text-white rounded-xl shadow-xs transition-colors shrink-0"
          >
            {isStreaming ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Send className="w-4 h-4" />
            )}
          </button>
        </form>
        <div className="mt-1.5 flex items-center justify-between text-[11px] text-slate-400">
          <span>回车发送 • SSE 响应式流</span>
          <span className="flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
            微服务网关就绪
          </span>
        </div>
      </div>
    </div>
  );
};
