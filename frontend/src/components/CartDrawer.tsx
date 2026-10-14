import React from 'react';
import { X, Trash2, ShoppingCart, Plus, Minus, Check, ArrowRight, ShieldCheck, RefreshCw } from 'lucide-react';
import { CartData } from '../types';

interface CartDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  cartData: CartData | null;
  loading: boolean;
  onUpdateQuantity: (productId: number, quantity: number) => void;
  onToggleCheck: (productId: number, selected: boolean, checkAll?: boolean) => void;
  onRemoveItem: (productId: number) => void;
  onClearCart: () => void;
  onCheckout: () => void;
  checkoutLoading: boolean;
}

export const CartDrawer: React.FC<CartDrawerProps> = ({
  isOpen,
  onClose,
  cartData,
  loading,
  onUpdateQuantity,
  onToggleCheck,
  onRemoveItem,
  onClearCart,
  onCheckout,
  checkoutLoading,
}) => {
  if (!isOpen) return null;

  const items = cartData?.items || [];
  const totalCount = cartData?.totalCount || 0;
  const selectedCount = cartData?.selectedCount || 0;
  const totalAmount = cartData?.totalAmount || 0;
  const allSelected = cartData?.allSelected || false;

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-slate-900/40 backdrop-blur-xs animate-in fade-in duration-200">
      <div className="w-full max-w-md bg-white h-full shadow-2xl flex flex-col border-l border-slate-200 animate-in slide-in-from-right duration-300">
        
        {/* 抽屉头部 */}
        <div className="flex items-center justify-between p-5 border-b border-slate-200 bg-slate-50/50">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-xl bg-indigo-50 border border-indigo-200 flex items-center justify-center text-indigo-600">
              <ShoppingCart className="w-4 h-4" />
            </div>
            <div>
              <h3 className="font-bold text-slate-900 text-base leading-tight">我的购物车</h3>
              <p className="text-[11px] text-slate-400">共 {totalCount} 件好物 · Redis Hash 高速存储</p>
            </div>
          </div>
          
          <div className="flex items-center gap-1">
            {items.length > 0 && (
              <button
                onClick={onClearCart}
                className="text-xs text-slate-400 hover:text-rose-600 px-2 py-1 rounded-lg hover:bg-rose-50 transition-colors flex items-center gap-1"
                title="清空购物车"
              >
                <Trash2 className="w-3.5 h-3.5" />
                <span>清空</span>
              </button>
            )}
            <button
              onClick={onClose}
              className="p-2 text-slate-400 hover:text-slate-600 rounded-full hover:bg-slate-100 transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* 购物车列表区 */}
        <div className="flex-1 overflow-y-auto p-4 space-y-3">
          {loading && items.length === 0 ? (
            <div className="text-center py-24 text-slate-400 space-y-3">
              <RefreshCw className="w-8 h-8 animate-spin mx-auto text-indigo-500" />
              <p className="text-xs">正在同步云端购物车...</p>
            </div>
          ) : items.length > 0 ? (
            items.map((item) => (
              <div
                key={item.productId}
                className={`p-3.5 rounded-2xl border transition-all flex gap-3 items-center ${
                  item.selected
                    ? 'bg-white border-indigo-200 shadow-xs ring-1 ring-indigo-500/10'
                    : 'bg-slate-50/60 border-slate-200 opacity-80'
                }`}
              >
                {/* 勾选复选框 */}
                <button
                  onClick={() => onToggleCheck(item.productId, !item.selected)}
                  className={`w-5 h-5 rounded-lg border flex items-center justify-center transition-colors shrink-0 ${
                    item.selected
                      ? 'bg-indigo-600 border-indigo-600 text-white'
                      : 'border-slate-300 hover:border-indigo-400 bg-white'
                  }`}
                >
                  {item.selected && <Check className="w-3.5 h-3.5 stroke-[3]" />}
                </button>

                {/* 商品简略图片或占位图 */}
                <div className="w-14 h-14 rounded-xl bg-slate-100 border border-slate-200/80 overflow-hidden flex items-center justify-center shrink-0">
                  {item.imageUrl ? (
                    <img src={item.imageUrl} alt={item.productName} className="w-full h-full object-cover" />
                  ) : (
                    <span className="text-xl">🛍️</span>
                  )}
                </div>

                {/* 商品详情与操作 */}
                <div className="flex-1 min-w-0">
                  <h4 className="font-bold text-slate-800 text-xs line-clamp-1 leading-snug">
                    {item.productName}
                  </h4>
                  <div className="flex items-center gap-1.5 mt-0.5">
                    <span className="text-xs font-black text-rose-600">
                      ¥{item.price.toFixed(2)}
                    </span>
                    <span className="text-[10px] text-slate-400">
                      库存: {item.stock}
                    </span>
                  </div>

                  {/* 数量调节组件 */}
                  <div className="flex items-center justify-between mt-2">
                    <div className="flex items-center border border-slate-200 rounded-lg bg-white overflow-hidden shadow-2xs">
                      <button
                        onClick={() => onUpdateQuantity(item.productId, item.quantity - 1)}
                        className="p-1 hover:bg-slate-100 text-slate-500 active:bg-slate-200 transition-colors"
                        title="减少数量"
                      >
                        <Minus className="w-3 h-3" />
                      </button>
                      <span className="px-2.5 text-xs font-mono font-bold text-slate-800 select-none">
                        {item.quantity}
                      </span>
                      <button
                        onClick={() => onUpdateQuantity(item.productId, item.quantity + 1)}
                        className="p-1 hover:bg-slate-100 text-slate-500 active:bg-slate-200 transition-colors"
                        title="增加数量"
                      >
                        <Plus className="w-3 h-3" />
                      </button>
                    </div>

                    {/* 单项小计与删除 */}
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-mono font-bold text-slate-700">
                        小计 ¥{item.subtotal.toFixed(2)}
                      </span>
                      <button
                        onClick={() => onRemoveItem(item.productId)}
                        className="text-slate-300 hover:text-rose-500 p-1 transition-colors"
                        title="移除商品"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            ))
          ) : (
            <div className="text-center py-24 text-slate-400 space-y-3">
              <div className="w-14 h-14 rounded-2xl bg-slate-100 flex items-center justify-center mx-auto text-slate-400">
                <ShoppingCart className="w-7 h-7 stroke-1" />
              </div>
              <div>
                <p className="text-xs font-medium text-slate-600">购物车空空如也</p>
                <p className="text-[11px] text-slate-400 mt-1">
                  挑两件心仪的商品加入购物车，或对 AI 导购说“把李宁跑鞋加进购物车”！
                </p>
              </div>
            </div>
          )}
        </div>

        {/* 底部结算面板 */}
        {items.length > 0 && (
          <div className="p-4 border-t border-slate-200 bg-white shadow-lg space-y-3">
            <div className="flex items-center justify-between text-xs">
              {/* 全选 */}
              <button
                onClick={() => onToggleCheck(0, !allSelected, true)}
                className="flex items-center gap-2 text-slate-600 hover:text-slate-900 font-semibold cursor-pointer select-none"
              >
                <div className={`w-4 h-4 rounded border flex items-center justify-center transition-colors ${
                  allSelected
                    ? 'bg-indigo-600 border-indigo-600 text-white'
                    : 'border-slate-300 bg-white'
                }`}>
                  {allSelected && <Check className="w-3 h-3 stroke-[3]" />}
                </div>
                <span>全选</span>
              </button>

              {/* 金额统计 */}
              <div className="text-right">
                <span className="text-slate-400 text-[11px] mr-1">合计:</span>
                <span className="text-lg font-black text-rose-600">
                  ¥{totalAmount.toFixed(2)}
                </span>
              </div>
            </div>

            {/* 结算按钮 */}
            <button
              onClick={onCheckout}
              disabled={checkoutLoading || selectedCount === 0}
              className="w-full py-3 rounded-2xl bg-indigo-600 hover:bg-indigo-700 active:scale-98 text-white font-bold text-sm shadow-md shadow-indigo-600/25 transition-all flex items-center justify-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {checkoutLoading ? (
                <>
                  <RefreshCw className="w-4 h-4 animate-spin" />
                  <span>正在合并结算下单...</span>
                </>
              ) : (
                <>
                  <ShieldCheck className="w-4 h-4" />
                  <span>一键结算 ({selectedCount}件) · 进入5分钟收银台</span>
                  <ArrowRight className="w-4 h-4 ml-0.5" />
                </>
              )}
            </button>
          </div>
        )}

      </div>
    </div>
  );
};
