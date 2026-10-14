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
 * 支持解析后端推送的结构化事件：
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
  }) => void
) {
  try {
    // 1. 记录网关路由转发日志
    onArchitectureLog?.({
      service: 'api-gateway',
      protocol: 'HTTP/SSE',
      action: 'Gateway Forwarding',
      detail: `GET /api/ai/chat/stream?sessionId=${sessionId} -> lb://ai-service`
    });

    onArchitectureLog?.({
      service: 'ai-service',
      protocol: 'HTTP/SSE',
      action: 'SSE Handshake',
      detail: `握手成功，开始调用 Spring AI ChatClient 与 MallTools ...`
    });

    // 2. 发起真实 SSE 响应式流请求（通过 Vite 代理 /api 或直接连 gatewayUrl）
    const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
    const url = `${targetBase}/api/ai/chat/stream?sessionId=${encodeURIComponent(sessionId)}&message=${encodeURIComponent(message)}`;

    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'Accept': 'text/event-stream',
        ...(token ? { 'Authorization': `Bearer ${token}` } : {})
      }
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
                onArchitectureLog?.({
                  service: eventObj.type === 'vector_search' ? 'ai-service' : eventObj.type === 'product_query' ? 'product-service' : 'order-service',
                  protocol: eventObj.type === 'vector_search' ? 'pgvector HNSW' : 'OpenFeign RPC',
                  action: `AI Tool: ${eventObj.title}`,
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

    const errorMsg = `\n\n⚠️ 【后端服务未启动或不可达】\n` +
      `无法连接微服务网关 (${gatewayUrl})。\n` +
      `请检查：\n` +
      `1. Docker 基础组件 (Nacos, PG, Redis) 是否运行：docker-compose up -d\n` +
      `2. api-gateway (8080) 与 ai-service (8084) 是否已启动；\n` +
      `3. 错误详情: ${err.message}`;

    callbacks.onChunk(errorMsg);
    callbacks.onError(err);
    callbacks.onComplete();
  }
}
