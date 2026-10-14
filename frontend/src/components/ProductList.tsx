import React, { useState } from 'react';
import { Product } from '../types';
import { ProductCard } from './ProductCard';
import { Zap, Sparkles, Flame, Clock } from 'lucide-react';

interface ProductListProps {
  products: Product[];
  activeCategory: string;
  searchQuery: string;
  onAskAi: (product: Product) => void;
  onBuy: (product: Product) => void;
  onAddToCart: (product: Product) => void;
  onTriggerPrompt: (prompt: string) => void;
}

export const ProductList: React.FC<ProductListProps> = ({
  products,
  activeCategory,
  searchQuery,
  onAskAi,
  onBuy,
  onAddToCart,
  onTriggerPrompt,
}) => {
  const [sortBy, setSortBy] = useState<'default' | 'price-asc' | 'price-desc'>('default');

  // 过滤商品（精准识别秒杀专区）
  const filteredProducts = products.filter((p) => {
    const isSeckillItem = Boolean(
      p.isSeckill ||
      p.category?.includes('秒杀') ||
      p.name?.includes('秒杀') ||
      p.tags?.some((t) => t.includes('秒杀'))
    );

    const matchCategory =
      activeCategory === '全部'
        ? true
        : activeCategory.includes('秒杀')
        ? isSeckillItem
        : p.category === activeCategory;

    const matchSearch =
      searchQuery.trim() === '' ||
      p.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      p.description.toLowerCase().includes(searchQuery.toLowerCase());

    return matchCategory && matchSearch;
  });

  // 排序商品
  const sortedProducts = [...filteredProducts].sort((a, b) => {
    if (sortBy === 'price-asc') return a.price - b.price;
    if (sortBy === 'price-desc') return b.price - a.price;
    return 0;
  });

  return (
    <div className="space-y-4">
      
      {/* 现代化精简 Header 栏（高度减半，紧凑大气） */}
      {activeCategory.includes('秒杀') ? (
        /* 秒杀专场精炼红金状态栏 (高度仅 46px) */
        <div className="rounded-2xl bg-gradient-to-r from-rose-600 via-rose-500 to-amber-500 text-white px-4 py-2.5 shadow-md flex items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Flame className="w-4 h-4 text-amber-200 fill-amber-200 animate-pulse" />
            <span className="font-bold text-sm tracking-tight">⚡ 限时极速秒杀专场</span>
            <span className="text-[10px] bg-black/25 px-2 py-0.5 rounded-full font-mono text-amber-200 font-semibold hidden sm:inline">
              10,271 QPS 极速通道
            </span>
          </div>
          <div className="flex items-center gap-1.5 text-xs font-mono font-bold bg-black/25 backdrop-blur-xs px-2.5 py-1 rounded-lg text-amber-100 border border-white/20">
            <Clock className="w-3.5 h-3.5 text-amber-200" />
            <span>02:18:45</span>
          </div>
        </div>
      ) : (
        /* 综合商城精简 Hero 栏 (单层紧凑排版，不占屏幕空间) */
        <div className="relative rounded-2xl overflow-hidden bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 text-white p-4 sm:p-5 shadow-md border border-indigo-900/40 flex flex-col md:flex-row md:items-center justify-between gap-3">
          <div className="space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-bold text-base sm:text-lg tracking-tight bg-gradient-to-r from-white via-indigo-100 to-indigo-300 bg-clip-text text-transparent">
                AI Mall 智购云商
              </span>
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-indigo-500/20 text-indigo-300 text-[10px] font-medium border border-indigo-400/30">
                <Sparkles className="w-3 h-3 text-indigo-300" /> Qwen3.7-Flash 导购
              </span>
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-rose-500/20 text-rose-300 text-[10px] font-medium border border-rose-400/30">
                <Zap className="w-3 h-3 text-amber-300" /> 10K QPS 秒杀削峰
              </span>
            </div>
            <p className="text-xs text-slate-400 max-w-lg leading-relaxed">
              分布式微服务架构 + 向量知识库 + RocketMQ 动静双通道治理
            </p>
          </div>

          {/* 快捷提问小胶囊 (单行横排) */}
          <div className="flex flex-wrap items-center gap-1.5 shrink-0">
            <span className="text-[11px] text-slate-400 hidden xl:inline">问AI：</span>
            <button
              onClick={() => onTriggerPrompt('我想买一双500元以内的跑鞋')}
              className="text-[11px] px-2.5 py-1 rounded-lg bg-white/10 hover:bg-white/20 border border-white/10 text-slate-200 transition-colors"
            >
              👟 500元跑鞋
            </button>
            <button
              onClick={() => onTriggerPrompt('比较一下小米14和iPhone 15 Pro')}
              className="text-[11px] px-2.5 py-1 rounded-lg bg-white/10 hover:bg-white/20 border border-white/10 text-slate-200 transition-colors"
            >
              📱 手机对比
            </button>
            <button
              onClick={() => onTriggerPrompt('帮我下一单李宁赤兔6跑鞋')}
              className="text-[11px] px-2.5 py-1 rounded-lg bg-indigo-500/30 hover:bg-indigo-500/40 border border-indigo-400/30 text-indigo-200 transition-colors"
            >
              🛒 Agent下单
            </button>
          </div>
        </div>
      )}

      {/* 筛选与排序控制条 */}
      <div className="flex flex-wrap items-center justify-between gap-4 py-2 border-b border-slate-200">
        <div className="flex items-center gap-2">
          <span className="text-sm font-bold text-slate-900">
            {activeCategory} ({sortedProducts.length})
          </span>
          {searchQuery && (
            <span className="text-xs text-slate-500 bg-slate-100 px-2 py-0.5 rounded-md">
              关键词: "{searchQuery}"
            </span>
          )}
        </div>

        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-500">排序:</span>
          <select
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as any)}
            className="text-xs bg-white border border-slate-200 rounded-lg px-2.5 py-1.5 text-slate-700 outline-hidden focus:border-indigo-500"
          >
            <option value="default">综合推荐</option>
            <option value="price-asc">价格: 低到高</option>
            <option value="price-desc">价格: 高到低</option>
          </select>
        </div>
      </div>

      {/* 商品卡片网格：紧凑对齐，杜绝错位拉伸 */}
      {sortedProducts.length > 0 ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5 items-start">
          {sortedProducts.map((product) => (
            <ProductCard
              key={product.id}
              product={product}
              onAskAi={onAskAi}
              onBuy={onBuy}
              onAddToCart={onAddToCart}
            />
          ))}
        </div>
      ) : (
        <div className="text-center py-16 bg-white rounded-2xl border border-dashed border-slate-300">
          <p className="text-sm text-slate-500">未找到符合条件的商品</p>
          <button
            onClick={() => onAskAi({ id: 0, name: searchQuery, category: '全部分类', price: 0, stock: 0, description: searchQuery })}
            className="mt-3 inline-flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-medium bg-indigo-600 text-white hover:bg-indigo-700 transition-colors"
          >
            <Sparkles className="w-3.5 h-3.5" />
            <span>让 AI 导购在全网知识库中搜索</span>
          </button>
        </div>
      )}

    </div>
  );
};
