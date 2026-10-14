import React, { createContext, useContext, useState, useEffect } from 'react';
import { User } from '../types';

interface AuthContextType {
  user: User | null;
  token: string | null;
  login: (token: string, user: User) => void;
  logout: () => void;
  isLoggedIn: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(() => {
    const saved = localStorage.getItem('aimall_user');
    return saved ? JSON.parse(saved) : { id: 2, username: 'testuser', role: 'ROLE_USER' }; // 默认模拟登录测试用户
  });

  const [token, setToken] = useState<string | null>(() => {
    const saved = localStorage.getItem('aimall_token');
    const validDefault = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyIiwidXNlcm5hbWUiOiJ0ZXN0dXNlciIsInJvbGUiOiJST0xFX1VTRVIiLCJpYXQiOjE3OTE1MzI5MTgsImV4cCI6MTc5MTYxOTMxOH0.MR4YQSuVoZg1cZusbqknj63BG6md1SDC-GXKUBvhLh0';
    if (!saved || saved.startsWith('mock-jwt-token')) {
      localStorage.setItem('aimall_token', validDefault);
      return validDefault;
    }
    return saved;
  });

  useEffect(() => {
    if (user && token) {
      localStorage.setItem('aimall_user', JSON.stringify(user));
      localStorage.setItem('aimall_token', token);
    } else {
      localStorage.removeItem('aimall_user');
      localStorage.removeItem('aimall_token');
    }
  }, [user, token]);

  const login = (newToken: string, newUser: User) => {
    setToken(newToken);
    setUser(newUser);
  };

  const logout = () => {
    setToken(null);
    setUser(null);
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        token,
        login,
        logout,
        isLoggedIn: !!user && !!token,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
};
