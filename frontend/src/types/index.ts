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
  | 'visual_cache_hit'    // 视觉解构缓存命中
  | 'semantic_cache_hit'  // 导购问句语义缓存命中
  | 'failover_routing'    // 多模型高可用容灾路由
  | 'product_query'       // Feign RPC 查商品
  | 'product_detail'      // Feign RPC 商品详情
  | 'order_create'        // Feign RPC 创建订单
  | 'order_query'         // Feign RPC 查订单
  | 'order_cancel'        // Feign RPC 取消订单
  | 'cart_add'            // Feign RPC 加入购物车
  | 'cart_query'          // Feign RPC 查询购物车
  | 'cart_checkout'       // Feign RPC 结算购物车
  | 'auth_check'          // 认证与风控校验
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

