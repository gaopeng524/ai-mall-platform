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

/**
 * 启动真实 AI 导购流式对话（直接连接后端 Spring Cloud Gateway -> ai-service）
 */
export async function startAiStream(
  sessionId: string,
  message: string,
  token: string | null,
  gatewayUrl: string,
  callbacks: StreamEventCallbacks,
  onArchitectureLog?: (log: {
    service: 'api-gateway' | 'ai-service' | 'product-service' | 'order-service' | 'user-service';
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
      detail: `握手成功，开始调用 Spring AI ChatClient (Qwen3.7-Flash) ...`
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
        if (line.startsWith('data:')) {
          const chunk = line.substring(5).trim();
          if (chunk) {
            callbacks.onChunk(chunk);
          }
        }
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
