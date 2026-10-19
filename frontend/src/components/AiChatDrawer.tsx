import React, { useState, useRef, useEffect } from 'react';
import { 
  Bot, 
  X, 
  Send, 
  Trash2, 
  Sparkles, 
  CheckCircle2, 
  XCircle,
  Zap,
  ShieldCheck,
  Loader2, 
  Database, 
  Cpu, 
  ShoppingCart,
  Maximize2,
  Minimize2,
  Camera,
  Eye,
  Image as ImageIcon
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
      content: '您好！我是您的智能导购助手“小购” 🤖\n\n我已全面接入 **通义千问 qwen3.7-flash 多模态双智能体系统**：\n• **📷 拍立淘找平替**：点击输入框左侧相机图标上传商品照片，或直接 `Ctrl + V` 粘贴图片截图，AI 自动解构视觉特征并检索库内对标的高性价比平替！\n• **💬 智能导购决策**：“帮我推荐 500 元以内的跑鞋”、“对比小米 14 和 iPhone 15 Pro”\n• **⚡ 交易闭环**：“直接下单李宁赤兔跑鞋”、“查一下我的订单”',
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    }
  ]);
  const [input, setInput] = useState<string>('');
  const [selectedImage, setSelectedImage] = useState<string | null>(null);
  const [isStreaming, setIsStreaming] = useState<boolean>(false);
  const [isExpanded, setIsExpanded] = useState<boolean>(false);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  // 组件卸载或关闭时中止未完成的推流
  useEffect(() => {
    return () => {
      abortControllerRef.current?.abort();
    };
  }, []);

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

  // 智能图片压缩：将高分辨率截图自适应下采样至 1024px，转为轻量 JPEG (5MB PNG -> 100KB JPEG)，彻底消除大图上传与网关超时
  const compressImage = (file: File): Promise<string> => {
    return new Promise((resolve) => {
      const reader = new FileReader();
      reader.onload = (e) => {
        const img = new Image();
        img.onload = () => {
          const canvas = document.createElement('canvas');
          const MAX_SIZE = 1024;
          let { width, height } = img;
          if (width > height) {
            if (width > MAX_SIZE) {
              height = Math.round((height * MAX_SIZE) / width);
              width = MAX_SIZE;
            }
          } else {
            if (height > MAX_SIZE) {
              width = Math.round((width * MAX_SIZE) / height);
              height = MAX_SIZE;
            }
          }
          canvas.width = width;
          canvas.height = height;
          const ctx = canvas.getContext('2d');
          if (ctx) {
            ctx.drawImage(img, 0, 0, width, height);
            resolve(canvas.toDataURL('image/jpeg', 0.85));
          } else {
            resolve(e.target?.result as string);
          }
        };
        img.onerror = () => resolve(e.target?.result as string);
        img.src = e.target?.result as string;
      };
      reader.onerror = () => resolve('');
      reader.readAsDataURL(file);
    });
  };

  // 处理文件上传选择
  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      alert('请选择有效的图片文件 (PNG/JPG/WEBP)');
      return;
    }
    const compressed = await compressImage(file);
    if (compressed) {
      setSelectedImage(compressed);
    }
    e.target.value = '';
  };

  // 支持输入框粘贴剪贴板截图 (Ctrl + V)
  const handlePaste = async (e: React.ClipboardEvent<HTMLInputElement>) => {
    const items = e.clipboardData?.items;
    if (!items) return;
    for (let i = 0; i < items.length; i++) {
      if (items[i].type.startsWith('image/')) {
        const file = items[i].getAsFile();
        if (file) {
          e.preventDefault();
          const compressed = await compressImage(file);
          if (compressed) {
            setSelectedImage(compressed);
          }
          break;
        }
      }
    }
  };

  const handleSend = async (textToSend?: string, imageToSend?: string | null) => {
    const currentImage = imageToSend !== undefined ? imageToSend : selectedImage;
    const query = (textToSend || input).trim();
    if ((!query && !currentImage) || isStreaming) return;

    const finalQuery = query || (currentImage ? '帮我识别图中的商品，并在商城中推荐同款或高性价比平替' : '');
    setInput('');
    setSelectedImage(null);

    // 1. 添加用户消息（带图片标记）
    const userMessage: ChatMessage = {
      id: Math.random().toString(36).substring(2, 9),
      role: 'user',
      content: finalQuery,
      image: currentImage || undefined,
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

    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    const controller = new AbortController();
    abortControllerRef.current = controller;

    // 3. 启动流式传输
    await startAiStream(
      sessionId,
      finalQuery,
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
          abortControllerRef.current = null;
          onOrderChanged?.();
        },
        onError: (err) => {
          console.error(err);
          setIsStreaming(false);
          abortControllerRef.current = null;
        }
      },
      addLog,
      currentImage,
      controller.signal
    );
  };

  const handleSendPresetImage = (url: string, promptText: string) => {
    handleSend(promptText, url);
  };

  const handleClearMemory = async () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    setIsStreaming(false);
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
        isExpanded ? 'w-full sm:w-[720px]' : 'w-full sm:w-[480px]'
      }`}
    >
      {/* 头部标题栏 */}
      <div className="flex items-center justify-between px-5 py-4 border-b border-slate-200 bg-gradient-to-r from-indigo-50/50 via-purple-50/30 to-violet-50/50">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-indigo-600 to-violet-600 flex items-center justify-center text-white shadow-xs">
            <Bot className="w-5 h-5" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="font-bold text-slate-900 text-sm">小购 · 多模态导购 Agent</h2>
              <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-700 border border-indigo-200 flex items-center gap-1">
                <Sparkles className="w-2.5 h-2.5" /> qwen3.7-flash 双智能体
              </span>
            </div>
            <p className="text-[11px] text-slate-500">
              视觉解构专家 + 选品风控与交易专家 • 拍立淘找平替
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

            {/* 用户上传的多模态图片预览气泡 */}
            {msg.image && (
              <div className="mb-2 max-w-[220px] rounded-2xl overflow-hidden border-2 border-indigo-200 shadow-sm">
                <img
                  src={msg.image}
                  alt="用户上传商品图"
                  className="w-full h-auto object-cover max-h-[180px]"
                />
              </div>
            )}

            {/* 工具调用步骤卡片 (多模态视觉解构 + Tool Calling 进度) */}
            {msg.toolSteps && msg.toolSteps.length > 0 && (
              <div className="w-full max-w-[92%] sm:max-w-[85%] mb-2 space-y-1.5">
                {msg.toolSteps.map((step, idx) => (
                  <div
                    key={idx}
                    className={`p-2.5 rounded-xl border text-xs flex items-center justify-between ${
                      step.type === 'visual_analysis'
                        ? 'bg-gradient-to-r from-purple-50 to-indigo-50/50 border-purple-200'
                        : 'bg-slate-50 border-slate-200'
                    }`}
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      {step.type === 'visual_analysis' ? (
                        <div className="p-1 rounded-lg bg-purple-100 text-purple-700 shrink-0">
                          <Eye className="w-3.5 h-3.5" />
                        </div>
                      ) : (step.type === 'visual_cache_hit' || step.type === 'semantic_cache_hit') ? (
                        <div className="p-1 rounded-lg bg-amber-100 text-amber-700 shrink-0">
                          <Zap className="w-3.5 h-3.5" />
                        </div>
                      ) : step.type === 'vector_search' ? (
                        <div className="p-1 rounded-lg bg-violet-100 text-violet-700 shrink-0">
                          <Database className="w-3.5 h-3.5" />
                        </div>
                      ) : step.type === 'auth_check' ? (
                        <div className="p-1 rounded-lg bg-emerald-100 text-emerald-700 shrink-0">
                          <ShieldCheck className="w-3.5 h-3.5" />
                        </div>
                      ) : (
                        <div className="p-1 rounded-lg bg-indigo-100 text-indigo-700 shrink-0">
                          <Cpu className="w-3.5 h-3.5" />
                        </div>
                      )}
                      <div className="min-w-0">
                        <div className="font-semibold text-slate-800 truncate">{step.title}</div>
                        {step.detail && <div className="text-[11px] text-slate-500 truncate">{step.detail}</div>}
                      </div>
                    </div>
                    <div className="shrink-0 ml-2">
                      {step.status === 'running' ? (
                        <Loader2 className="w-4 h-4 text-indigo-600 animate-spin" />
                      ) : step.status === 'failed' ? (
                        <XCircle className="w-4 h-4 text-rose-500" />
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
                  <Sparkles className="w-3 h-3 text-indigo-600" /> AI 精选与平替推荐卡片：
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

      {/* 快捷拍立淘 & 提问 Prompt */}
      <div className="px-4 py-2 border-t border-slate-100 bg-slate-50/70 flex items-center gap-2 overflow-x-auto scrollbar-none text-xs">
        <button
          onClick={() => handleSendPresetImage(
            'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=600&q=80',
            '帮我看看这款 Nike 跑鞋，找找商城有什么高性价比平替'
          )}
          className="px-2.5 py-1 rounded-full bg-white border border-indigo-200 text-indigo-700 hover:bg-indigo-50 shrink-0 transition-colors flex items-center gap-1 font-medium shadow-2xs"
        >
          <Camera className="w-3 h-3" /> 👟 拍立淘跑鞋平替
        </button>
        <button
          onClick={() => handleSendPresetImage(
            'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=600&q=80',
            '识别这款索尼头戴耳机，推荐商城对标的高性价比降噪平替'
          )}
          className="px-2.5 py-1 rounded-full bg-white border border-purple-200 text-purple-700 hover:bg-purple-50 shrink-0 transition-colors flex items-center gap-1 font-medium shadow-2xs"
        >
          <Camera className="w-3 h-3" /> 🎧 拍立淘耳机平替
        </button>
        <button
          onClick={() => handleSendPresetImage(
            'https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=600&q=80',
            '识别这款客制化机械键盘，推荐商城性价比平替款'
          )}
          className="px-2.5 py-1 rounded-full bg-white border border-amber-200 text-amber-700 hover:bg-amber-50 shrink-0 transition-colors flex items-center gap-1 font-medium shadow-2xs"
        >
          <Camera className="w-3 h-3" /> ⌨️ 拍立淘机械键盘
        </button>
        <button
          onClick={() => handleSend('对比小米 14 和苹果 iPhone 15 Pro 的参数和性价比')}
          className="px-2.5 py-1 rounded-full bg-white border border-slate-200 text-slate-600 hover:border-indigo-300 hover:text-indigo-600 shrink-0 transition-colors"
        >
          📱 手机横向对比
        </button>
        <button
          onClick={() => handleSend('查询我最近的订单列表')}
          className="px-2.5 py-1 rounded-full bg-white border border-slate-200 text-slate-600 hover:border-indigo-300 hover:text-indigo-600 shrink-0 transition-colors"
        >
          📦 我的订单
        </button>
      </div>

      {/* 底部输入框与多模态图片上传栏 */}
      <div className="p-4 border-t border-slate-200 bg-white">
        {/* 图片选中预览条 */}
        {selectedImage && (
          <div className="relative inline-flex items-center gap-2 mb-2 p-1.5 pr-3 bg-indigo-50/80 border border-indigo-200 rounded-xl">
            <img
              src={selectedImage}
              alt="待上传图片预览"
              className="w-12 h-12 rounded-lg object-cover border border-indigo-300 bg-white"
            />
            <div className="text-xs text-indigo-900">
              <div className="font-semibold flex items-center gap-1">
                <ImageIcon className="w-3.5 h-3.5 text-indigo-600" /> 已就绪商品图片
              </div>
              <div className="text-[10px] text-indigo-600">点击发送即可开启拍立淘识图找平替</div>
            </div>
            <button
              onClick={() => setSelectedImage(null)}
              className="ml-2 w-5 h-5 rounded-full bg-slate-200 hover:bg-rose-500 hover:text-white flex items-center justify-center text-slate-600 transition-colors"
              title="移除图片"
            >
              <X className="w-3 h-3" />
            </button>
          </div>
        )}

        {/* 隐藏的文件输入组件 */}
        <input
          type="file"
          ref={fileInputRef}
          accept="image/*"
          onChange={handleFileChange}
          className="hidden"
        />

        <form
          onSubmit={(e) => {
            e.preventDefault();
            handleSend();
          }}
          className="flex items-center gap-2"
        >
          {/* 上传图片/拍照按钮 */}
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={isStreaming}
            title="上传商品照片 / 拍照找平替 (亦支持 Ctrl+V 直接粘贴)"
            className="p-2.5 bg-slate-100 hover:bg-indigo-50 hover:text-indigo-600 text-slate-600 rounded-xl border border-slate-200 transition-colors shrink-0 disabled:opacity-50"
          >
            <Camera className="w-4 h-4" />
          </button>

          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onPaste={handlePaste}
            disabled={isStreaming}
            placeholder={
              isStreaming
                ? '通义千问 qwen3.7-flash 正在思考与选品中...'
                : selectedImage
                ? '补充您的找平替要求（如：预算300以内），直接回车发送'
                : '输入需求或商品，支持直接 Ctrl+V 粘贴图片截图...'
            }
            className="flex-1 px-4 py-2.5 text-sm bg-slate-100 focus:bg-white border border-slate-200 focus:border-indigo-500 rounded-xl outline-hidden transition-all disabled:opacity-50"
          />
          <button
            type="submit"
            disabled={(!input.trim() && !selectedImage) || isStreaming}
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
          <span>支持拍照/上传/截图粘贴 • 毫秒级 SSE 流式推流</span>
          <span className="flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
            qwen3.7-flash 双智能体
          </span>
        </div>
      </div>
    </div>
  );
};
