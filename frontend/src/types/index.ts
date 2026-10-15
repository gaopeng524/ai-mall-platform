export interface Product {
  id: number;
  name: string;
  category: string;
  price: number;
  stock: number;
  description: string;
  imageUrl?: string;
  tags?: string[];
  specs?: Record<string, string>;
  isSeckill?: boolean;
  originalPrice?: number;
}

export interface User {
  id: number;
  username: string;
  role: string;
  email?: string;
  phone?: string;
}

export interface Order {
  id: number;
  orderNo: string;
  userId: number;
  productId: number;
  productName: string;
  price: number;
  quantity: number;
  totalAmount: number;
  status: 'PENDING' | 'PAID' | 'SHIPPED' | 'DONE' | 'CANCELLED';
  createdAt: string;
}

export type ToolCallStep = 
  | 'vector_search'       // 检索 pgvector
  | 'visual_analysis'     // Agent 1 多模态视觉解构
  | 'product_query'       // Feign RPC 查商品
  | 'order_create'        // Feign RPC 创建订单
  | 'done';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: string;
  image?: string;         // 多模态商品图
  toolSteps?: {
    type: ToolCallStep;
    title: string;
    detail?: string;
    status: 'running' | 'success' | 'failed';
  }[];
  embeddedProducts?: Product[];
}

export interface CartItem {
  productId: number;
  productName: string;
  price: number;
  imageUrl?: string;
  stock: number;
  quantity: number;
  selected: boolean;
  subtotal: number;
  valid: boolean;
}

export interface CartData {
  items: CartItem[];
  totalCount: number;
  selectedCount: number;
  totalAmount: number;
  allSelected: boolean;
}

