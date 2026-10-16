import { ToolCallStep, Product } from '../types';

export interface StreamEventCallbacks {
  onChunk: (text: string) => void;
  onToolStep?: (step: {
    type: ToolCallStep;
    title: string;
    detail?: string;
    status: 'running' | 'success' | 'failed';
  }) => void;
  onEmbeddedProducts?: (products: Product[]) => void;
  onComplete: () => void;
  onError: (error: Error) => void;
}

const AI_EVENT_PREFIX = '__AI_EVENT__:';

/**
 * 启动真实 AI 导购流式对话（直接连接后端 Spring Cloud Gateway -> ai-service）
 * 支持多模态拍立淘识图与文本对话：
 * - visual_analysis: Agent 1 (视觉解构专家) 多模态特征提取
 * - tool_step: 实时展示 Tool Calling 执行进度卡片
 * - products: 实时渲染对话内嵌的可交互下单商品卡片
 * - chunk: 保留完整换行与缩进的流式文本块
 */
export async function startAiStream(
  sessionId: string,
  message: string,
  token: string | null,
  gatewayUrl: string,
  callbacks: StreamEventCallbacks,
  onArchitectureLog?: (log: {
    service: 'api-gateway' | 'ai-service' | 'product-service' | 'order-service' | 'user-service' | 'payment-service';
    protocol: 'HTTP/SSE' | 'OpenFeign RPC' | 'Dubbo TCP' | 'pgvector HNSW';
    action: string;
    detail: string;
  }) => void,
  image?: string | null
) {
  try {
    const hasImage = Boolean(image && image.trim().length > 0);

    // 1. 记录网关路由转发日志
    onArchitectureLog?.({
      service: 'api-gateway',
      protocol: 'HTTP/SSE',
      action: 'Gateway Forwarding',
      detail: hasImage 
        ? `POST /api/ai/chat/stream [多模态拍立淘识图] -> lb://ai-service`
        : `POST /api/ai/chat/stream [智能导购] -> lb://ai-service`
    });

    onArchitectureLog?.({
      service: 'ai-service',
      protocol: 'HTTP/SSE',
      action: hasImage ? 'Multimodal Dual-Agent' : 'SSE Handshake',
      detail: hasImage
        ? '已激活通义千问 qwen3.7-flash 视觉解构流水线，正在分析商品特征与找平替...'
        : '握手成功，开始调用 Spring AI ChatClient 与 MallTools ...'
    });

    // 2. 发起真实 SSE 响应式流请求（使用 POST 以稳定传输大体积 Base64 图片与请求负载）
    const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
    const url = `${targetBase}/api/ai/chat/stream`;

    const requestPayload = {
      sessionId,
      message,
      imageBase64: (image && image.startsWith('data:')) ? image : (image && !image.startsWith('http') ? image : undefined),
      imageUrl: (image && (image.startsWith('http://') || image.startsWith('https://'))) ? image : undefined
    };

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Accept': 'text/event-stream',
        'Content-Type': 'application/json',
        ...(token ? { 'Authorization': `Bearer ${token}` } : {})
      },
      body: JSON.stringify(requestPayload)
    });

    if (!response.ok) {
      const errText = await response.text().catch(() => '');
      throw new Error(`微服务网关响应异常 (HTTP ${response.status}): ${errText || '服务未启动或不可达'}`);
    }

    const reader = response.body?.getReader();
    if (!reader) {
      throw new Error('未获取到响应式流 ReadableStream');
    }

    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (!line.startsWith('data:')) continue;
        const rawPayload = line.substring(5).trim();
        if (!rawPayload) continue;

        if (rawPayload.startsWith(AI_EVENT_PREFIX)) {
          try {
            const eventObj = JSON.parse(rawPayload.substring(AI_EVENT_PREFIX.length));
            if (eventObj.event === 'chunk' && typeof eventObj.text === 'string') {
              callbacks.onChunk(eventObj.text);
            } else if (eventObj.event === 'tool_step') {
              callbacks.onToolStep?.({
                type: eventObj.type as ToolCallStep,
                title: eventObj.title,
                detail: eventObj.detail,
                status: eventObj.status
              });
              if (eventObj.status === 'running') {
                const isVisual = eventObj.type === 'visual_analysis';
                const isVector = eventObj.type === 'vector_search';
                const isProduct = eventObj.type === 'product_query';
                onArchitectureLog?.({
                  service: (isVisual || isVector) ? 'ai-service' : isProduct ? 'product-service' : 'order-service',
                  protocol: isVisual ? 'HTTP/SSE' : isVector ? 'pgvector HNSW' : 'OpenFeign RPC',
                  action: isVisual ? `Agent 1: ${eventObj.title}` : `AI Tool: ${eventObj.title}`,
                  detail: eventObj.detail || ''
                });
              }
            } else if (eventObj.event === 'products' && Array.isArray(eventObj.products)) {
              const normalizedProducts: Product[] = eventObj.products.map((p: any) => ({
                id: Number(p.id),
                name: String(p.name || '商品'),
                category: String(p.category || '精选'),
                price: Number(p.price || 0),
                stock: Number(p.stock ?? 0),
                description: String(p.description || ''),
                imageUrl: p.imageUrl ? String(p.imageUrl) : undefined,
                isSeckill: Boolean(p.isSeckill),
                originalPrice: p.originalPrice ? Number(p.originalPrice) : undefined
              }));
              callbacks.onEmbeddedProducts?.(normalizedProducts);
            }
            continue;
          } catch (parseErr) {
            console.warn('[AI Stream Event Parse Fallback]', parseErr);
          }
        }

        // 兼容纯文本回退
        callbacks.onChunk(rawPayload);
      }
    }

    onArchitectureLog?.({
      service: 'ai-service',
      protocol: 'HTTP/SSE',
      action: 'Stream Complete',
      detail: `SSE 流传输完毕，共完成本次多轮对话推流。`
    });

    callbacks.onComplete();
  } catch (err: any) {
    console.error('[AI Stream Error]', err);
    onArchitectureLog?.({
      service: 'api-gateway',
      protocol: 'HTTP/SSE',
      action: 'Connection Failed',
      detail: `请求失败: ${err.message || '网关连接超时'}`
    });

    // 智能容错降级：杜绝将底层端口号、Docker 调试指令直接暴露给终端消费者
    const fallbackMsg = `\n\n抱歉亲，AI 导购大脑刚刚开小差或网络稍有拥堵～\n` +
      `小购已为您调出商城热门推荐与高性价比平替好物（见上方商品推荐卡片），您可以先挑选看看；\n` +
      `您也可以换个问题或重新发送试一下哦！`;

    // 智能兜底商品卡片联动（保证 UI 不白屏、导购交易链条不断链）
    try {
      const fallbackProducts = [
        {
          id: 5,
          name: '漫步者 (EDIFIER) W820NB 双金标头戴式主动降噪耳机 (高性价比平替)',
          category: '数码外设',
          price: 299.0,
          description: 'Hi-Res 双金标高清认证，-43dB 深度混合主动降噪，零压感专利耳套，50小时超长续航。',
          imageUrl: 'https://images.unsplash.com/photo-1546435770-a3e426bf472b?auto=format&fit=crop&w=600&q=80',
          tags: ['平替黑马', '双金标认证', '百元降噪王']
        },
        {
          id: 3,
          name: '李宁赤兔6 Pro 专业竞速跑步鞋 (性价比平替)',
          category: '运动鞋',
          price: 499.0,
          description: '国产高性价比专业竞速训练鞋，全掌䨻科技中底，滚动感强，透气耐磨，千元内跑鞋平替首选。',
          imageUrl: 'https://images.unsplash.com/photo-1608231387042-66d1773070a5?auto=format&fit=crop&w=600&q=80',
          tags: ['千元内首选', '轻量回弹', '平替标杆']
        },
        {
          id: 7,
          name: '红米 Redmi Buds 5 Pro 真无线降噪耳机 (平替旗舰)',
          category: '数码外设',
          price: 399.0,
          description: '52dB 深度降噪，4kHz 超宽频降噪频宽，圈瓷同轴双单元，LHDC 5.0 高清音频传输。',
          imageUrl: 'https://images.unsplash.com/photo-1590658268037-6bf12165a8df?auto=format&fit=crop&w=600&q=80',
          tags: ['52dB降噪', '同轴双单元', '百元卷王']
        }
      ];
      callbacks.onEmbeddedProducts?.(fallbackProducts as any);
    } catch (fallbackErr) {
      console.warn('[AI Stream Fallback Card Error]', fallbackErr);
    }

    callbacks.onChunk(fallbackMsg);
    callbacks.onError(err);
    callbacks.onComplete();
  }
}
