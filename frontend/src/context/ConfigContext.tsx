import React, { createContext, useContext, useState } from 'react';

export interface ArchitectureLog {
  id: string;
  time: string;
  service: 'api-gateway' | 'ai-service' | 'product-service' | 'order-service' | 'user-service' | 'payment-service';
  protocol: 'HTTP/SSE' | 'OpenFeign RPC' | 'Dubbo TCP' | 'pgvector HNSW';
  action: string;
  detail: string;
}

interface ConfigContextType {
  gatewayUrl: string;
  setGatewayUrl: (url: string) => void;
  logs: ArchitectureLog[];
  addLog: (log: Omit<ArchitectureLog, 'id' | 'time'>) => void;
  clearLogs: () => void;
}

const ConfigContext = createContext<ConfigContextType | undefined>(undefined);

export const ConfigProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  // 仅支持真实微服务网关通信
  const [gatewayUrl, setGatewayUrl] = useState<string>('http://localhost:8080');
  const [logs, setLogs] = useState<ArchitectureLog[]>([
    {
      id: '1',
      time: new Date().toLocaleTimeString(),
      service: 'api-gateway',
      protocol: 'HTTP/SSE',
      action: 'Gateway Initialized',
      detail: '网关路由映射已监听: /api/** -> 微服务集群 (真实后端调用模式)'
    }
  ]);

  const addLog = (logItem: Omit<ArchitectureLog, 'id' | 'time'>) => {
    const newLog: ArchitectureLog = {
      ...logItem,
      id: Math.random().toString(36).substring(2, 9),
      time: new Date().toLocaleTimeString(),
    };
    setLogs((prev) => [...prev.slice(-49), newLog]); // 保持最新 50 条，按时间顺序追加在末尾
  };

  const clearLogs = () => setLogs([]);

  return (
    <ConfigContext.Provider
      value={{
        gatewayUrl,
        setGatewayUrl,
        logs,
        addLog,
        clearLogs,
      }}
    >
      {children}
    </ConfigContext.Provider>
  );
};

export const useConfig = () => {
  const context = useContext(ConfigContext);
  if (!context) throw new Error('useConfig must be used within ConfigProvider');
  return context;
};
