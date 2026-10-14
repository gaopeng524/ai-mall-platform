import React from 'react';
import { Sparkles, ShoppingCart, Zap } from 'lucide-react';
import { Product } from '../types';

interface ProductCardProps {
  product: Product;
  onAskAi: (product: Product) => void;
  onBuy: (product: Product) => void;
  onAddToCart: (product: Product) => void;
}

export const ProductCard: React.FC<ProductCardProps> = ({
  product,
  onAskAi,
  onBuy,
  onAddToCart,
}) => {
  const isSeckill = Boolean(
    product.isSeckill ||
    product.category?.includes('秒杀') ||
    product.name?.includes('秒杀') ||
    product.tags?.some((t) => t.includes('秒杀'))
  );

  const originalPrice = product.originalPrice || Math.round(product.price * 2.5);

  return (
    <div
      className={`group bg-white rounded-2xl overflow-hidden transition-all duration-300 flex flex-col justify-between border ${
        isSeckill
          ? 'border-rose-200 hover:border-rose-400 hover:shadow-lg hover:shadow-rose-50'
          : 'border-slate-200 hover:border-indigo-200 hover:shadow-lg'
      }`}
    >
      <div className="flex flex-col">
        {/* 商品主图与紧凑浮层：严格限定高度，杜绝纵向撑爆与大片空白 */}
        <div className="relative h-44 sm:h-48 w-full overflow-hidden bg-slate-100 shrink-0">
          <img
            src={product.imageUrl || 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=600&q=80'}
            alt={product.name}
            className="w-full h-full object-cover object-center group-hover:scale-105 transition-transform duration-500"
            loading="lazy"
            onError={(e) => {
              const target = e.currentTarget;
              const fallback = 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=600&q=80';
              if (target.src !== fallback) {
                target.src = fallback;
              }
            }}
          />

          {/* 左上角胶囊徽章 */}
          <div className="absolute top-2.5 left-2.5 flex items-center gap-1">
            {isSeckill ? (
              <span className="px-2 py-0.5 text-[10px] font-bold rounded-md bg-gradient-to-r from-rose-600 to-amber-600 text-white shadow-xs flex items-center gap-0.5">
                <Zap className="w-2.5 h-2.5 fill-amber-300 text-amber-300" />
                秒杀专享
              </span>
            ) : product.tags && product.tags.length > 0 ? (
              <span className="px-2 py-0.5 text-[10px] font-semibold rounded-md bg-white/90 backdrop-blur-xs text-indigo-600 shadow-xs border border-white/40">
                {product.tags[0]}
              </span>
            ) : null}
          </div>

          {/* 右上角库存 */}
          <div className="absolute top-2.5 right-2.5">
            <span
              className={`px-2 py-0.5 text-[10px] font-medium rounded-full backdrop-blur-xs text-white ${
                isSeckill ? 'bg-rose-600/90 font-bold' : 'bg-slate-900/80'
              }`}
            >
              {isSeckill ? `余 ${product.stock}` : `存 ${product.stock}`}
            </span>
          </div>
        </div>

        {/* 标题与描述 (紧凑单行) */}
        <div className="p-3 sm:p-3.5 pb-2">
          <div className="flex items-center justify-between text-[11px] mb-0.5">
            <span className={`font-semibold ${isSeckill ? 'text-rose-600' : 'text-indigo-600'}`}>
              {product.category}
            </span>
            {isSeckill && (
              <span className="text-[10px] text-amber-600 font-bold">已抢 85%</span>
            )}
          </div>
          <h3
            className={`font-bold text-sm leading-snug line-clamp-1 transition-colors ${
              isSeckill ? 'text-slate-900 group-hover:text-rose-600' : 'text-slate-900 group-hover:text-indigo-600'
            }`}
            title={product.name}
          >
            {product.name}
          </h3>
          <p className="text-xs text-slate-500 line-clamp-2 mt-1 leading-normal">
            {product.description}
          </p>
        </div>
      </div>

      {/* 底部价格与单行高效按钮组 */}
      <div className="p-3 sm:p-3.5 pt-0">
        <div className="flex items-baseline justify-between mb-2.5">
          <div className="flex items-baseline gap-1">
            <span className="text-xs font-semibold text-rose-600">¥</span>
            <span className="text-xl font-black text-rose-600 tracking-tight">
              {product.price.toFixed(2)}
            </span>
            {isSeckill && (
              <span className="line-through text-slate-400 text-xs ml-1">
                ¥{originalPrice.toFixed(0)}
              </span>
            )}
          </div>

          {isSeckill ? (
            <span className="text-[10px] font-bold text-rose-600 bg-rose-50 border border-rose-200/80 px-1.5 py-0.5 rounded">
              省¥{(originalPrice - product.price).toFixed(0)}
            </span>
          ) : (
            <span className="text-[10px] text-slate-400">起</span>
          )}
        </div>

        {/* 高度紧凑单行按钮组 */}
        <div className="flex items-center gap-1.5">
          {/* AI 导购紧凑图标按钮 */}
          <button
            onClick={() => onAskAi(product)}
            title="向 AI 导购咨询此商品"
            className="p-2 rounded-xl bg-indigo-50/80 hover:bg-indigo-100 text-indigo-600 border border-indigo-100/80 transition-colors shrink-0"
          >
            <Sparkles className="w-3.5 h-3.5" />
          </button>

          {isSeckill ? (
            /* 秒杀专属宽幅立即秒杀按钮 */
            <button
              onClick={() => onBuy(product)}
              className="flex-1 flex items-center justify-center gap-1 py-1.5 px-3 rounded-xl text-xs font-bold bg-gradient-to-r from-rose-600 via-rose-500 to-amber-500 hover:from-rose-500 hover:to-amber-400 text-white shadow-xs hover:shadow-md transition-all active:scale-98"
            >
              <Zap className="w-3.5 h-3.5 fill-amber-300 text-amber-300" />
              <span>立即秒杀</span>
            </button>
          ) : (
            /* 普通商品紧凑双按钮 */
            <>
              <button
                onClick={() => onAddToCart(product)}
                className="flex-1 flex items-center justify-center gap-1 py-1.5 px-2 rounded-xl text-xs font-medium bg-slate-100 hover:bg-slate-200 text-slate-700 transition-colors"
              >
                <ShoppingCart className="w-3 h-3 text-slate-500" />
                <span>加购</span>
              </button>

              <button
                onClick={() => onBuy(product)}
                className="flex-1 flex items-center justify-center py-1.5 px-3 rounded-xl text-xs font-semibold bg-slate-900 hover:bg-slate-800 text-white transition-colors"
              >
                <span>购买</span>
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
