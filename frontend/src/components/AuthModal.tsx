import React, { useState } from 'react';
import { X, User, Lock, Mail, Phone, CheckCircle2, Shield } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useConfig } from '../context/ConfigContext';

interface AuthModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const AuthModal: React.FC<AuthModalProps> = ({ isOpen, onClose }) => {
  const { login } = useAuth();
  const { gatewayUrl, addLog } = useConfig();

  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('testuser');
  const [password, setPassword] = useState('123456');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [loading, setLoading] = useState(false);
  const [successMsg, setSuccessMsg] = useState('');
  const [errorMsg, setErrorMsg] = useState('');

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setErrorMsg('');
    setSuccessMsg('');

    // 记录架构日志
    addLog({
      service: 'api-gateway',
      protocol: 'HTTP/SSE',
      action: 'Auth Request',
      detail: `POST /api/user/${mode} -> 路由至 lb://user-service`
    });

    try {
      const targetBase = gatewayUrl.endsWith('/') ? gatewayUrl.slice(0, -1) : gatewayUrl;
      const res = await fetch(`${targetBase}/api/user/${mode}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(
          mode === 'login'
            ? { username, password }
            : { username, password, email, phone }
        ),
      });

      const result = await res.json().catch(() => null);

      if (!res.ok || !result || result.code !== 200) {
        throw new Error(result?.message || `后端响应状态 HTTP ${res.status}`);
      }

      if (mode === 'login') {
        const data = result.data;
        login(data.token, {
          id: data.userId,
          username: data.username,
          role: data.role,
        });
        addLog({
          service: 'user-service',
          protocol: 'OpenFeign RPC',
          action: 'JWT Verification',
          detail: `登录成功，签发真实 JJWT 令牌，userId=${data.userId}`
        });
        setSuccessMsg('登录成功！已获取后端签发 JWT');
      } else {
        setSuccessMsg('注册成功！请切换至登录');
        setMode('login');
      }

      setTimeout(() => {
        setSuccessMsg('');
        if (mode === 'login') onClose();
      }, 1000);
    } catch (err: any) {
      console.warn('用户服务通信失败:', err);
      setErrorMsg(`【服务未启动或未开发】user-service (8081) 响应失败: ${err.message || '网络无法连接'}`);
      addLog({
        service: 'user-service',
        protocol: 'HTTP/SSE',
        action: 'Auth Failed',
        detail: `调用失败: ${err.message}`
      });
    } finally {
      setLoading(false);
    }
  };

  const handleQuickLogin = (uname: string) => {
    setUsername(uname);
    setPassword('123456');
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-xs animate-in fade-in duration-200">
      <div className="relative w-full max-w-md bg-white rounded-3xl shadow-2xl border border-slate-100 overflow-hidden">
        
        {/* 关闭按钮 */}
        <button
          onClick={onClose}
          className="absolute top-4 right-4 p-2 text-slate-400 hover:text-slate-600 rounded-full hover:bg-slate-100 transition-colors"
        >
          <X className="w-5 h-5" />
        </button>

        {/* 顶部标题 */}
        <div className="p-6 pb-0">
          <div className="flex items-center gap-2 mb-1">
            <Shield className="w-5 h-5 text-indigo-600" />
            <h3 className="text-lg font-bold text-slate-900">
              {mode === 'login' ? '用户登录' : '新用户注册'}
            </h3>
          </div>
          <p className="text-xs text-slate-500">
            微服务分布式统一鉴权 (JJWT + Gateway Header 透传)
          </p>

          {/* 切换 Tab */}
          <div className="flex gap-2 mt-4 p-1 bg-slate-100 rounded-xl text-xs font-semibold">
            <button
              onClick={() => setMode('login')}
              className={`flex-1 py-1.5 rounded-lg transition-all ${
                mode === 'login' ? 'bg-white text-indigo-600 shadow-xs' : 'text-slate-500 hover:text-slate-900'
              }`}
            >
              密码登录
            </button>
            <button
              onClick={() => setMode('register')}
              className={`flex-1 py-1.5 rounded-lg transition-all ${
                mode === 'register' ? 'bg-white text-indigo-600 shadow-xs' : 'text-slate-500 hover:text-slate-900'
              }`}
            >
              注册账号
            </button>
          </div>
        </div>

        {/* 快捷账号选择 (面试超方便) */}
        <div className="px-6 pt-3">
          <div className="text-[11px] text-slate-400 mb-1.5">一键预填内置测试账号：</div>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => handleQuickLogin('testuser')}
              className="text-xs px-2.5 py-1 rounded-lg bg-indigo-50 text-indigo-600 hover:bg-indigo-100 border border-indigo-100 transition-colors"
            >
              👤 普通用户 (testuser)
            </button>
            <button
              type="button"
              onClick={() => handleQuickLogin('admin')}
              className="text-xs px-2.5 py-1 rounded-lg bg-slate-100 text-slate-700 hover:bg-slate-200 border border-slate-200 transition-colors"
            >
              👑 管理员 (admin)
            </button>
          </div>
        </div>

        {/* 表单主体 */}
        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">用户名</label>
            <div className="relative">
              <User className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="请输入用户名"
                className="w-full pl-10 pr-4 py-2 text-sm bg-slate-50 focus:bg-white border border-slate-200 focus:border-indigo-500 rounded-xl outline-hidden transition-all"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">密码</label>
            <div className="relative">
              <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="请输入密码 (如: 123456)"
                className="w-full pl-10 pr-4 py-2 text-sm bg-slate-50 focus:bg-white border border-slate-200 focus:border-indigo-500 rounded-xl outline-hidden transition-all"
              />
            </div>
          </div>

          {mode === 'register' && (
            <>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">电子邮箱</label>
                <div className="relative">
                  <Mail className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                  <input
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="example@aimall.com"
                    className="w-full pl-10 pr-4 py-2 text-sm bg-slate-50 focus:bg-white border border-slate-200 focus:border-indigo-500 rounded-xl outline-hidden transition-all"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">联系手机</label>
                <div className="relative">
                  <Phone className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                  <input
                    type="tel"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                    placeholder="13800000000"
                    className="w-full pl-10 pr-4 py-2 text-sm bg-slate-50 focus:bg-white border border-slate-200 focus:border-indigo-500 rounded-xl outline-hidden transition-all"
                  />
                </div>
              </div>
            </>
          )}

          {errorMsg && (
            <div className="p-3 rounded-xl bg-rose-50 text-rose-700 text-xs font-semibold border border-rose-200">
              {errorMsg}
            </div>
          )}

          {successMsg && (
            <div className="flex items-center gap-1.5 p-3 rounded-xl bg-emerald-50 text-emerald-700 text-xs font-semibold border border-emerald-200">
              <CheckCircle2 className="w-4 h-4" />
              <span>{successMsg}</span>
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full py-2.5 px-4 rounded-xl text-sm font-semibold bg-indigo-600 hover:bg-indigo-700 disabled:bg-slate-400 text-white transition-all shadow-md shadow-indigo-200 active:scale-98"
          >
            {loading ? '正在通信 user-service...' : mode === 'login' ? '立即登录' : '完成注册并登录'}
          </button>
        </form>

      </div>
    </div>
  );
};
