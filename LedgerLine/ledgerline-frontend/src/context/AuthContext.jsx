import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { api, tokenStorageKey } from "../services/api";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [initializing, setInitializing] = useState(true);

  useEffect(() => {
    const handleUnauthorized = () => setUser(null);
    window.addEventListener("ledgerline:unauthorized", handleUnauthorized);
    let active = true;
    const token = window.sessionStorage.getItem(tokenStorageKey);
    if (!token) {
      setInitializing(false);
      return () => {
        active = false;
        window.removeEventListener("ledgerline:unauthorized", handleUnauthorized);
      };
    }

    api.get("/api/auth/me")
      .then(({ data }) => { if (active) setUser(data); })
      .catch(() => {
        window.sessionStorage.removeItem(tokenStorageKey);
        if (active) setUser(null);
      })
      .finally(() => { if (active) setInitializing(false); });

    return () => {
      active = false;
      window.removeEventListener("ledgerline:unauthorized", handleUnauthorized);
    };
  }, []);

  async function authenticate(path, values) {
    const { data } = await api.post(path, values);
    window.sessionStorage.setItem(tokenStorageKey, data.accessToken);
    setUser(data.user);
    return data.user;
  }

  async function login(values) {
    return authenticate("/api/auth/login", values);
  }

  async function register(values) {
    return authenticate("/api/auth/register", values);
  }

  async function logout() {
    try {
      await api.post("/api/auth/logout");
    } finally {
      window.sessionStorage.removeItem(tokenStorageKey);
      setUser(null);
    }
  }

  const value = useMemo(
    () => ({ user, initializing, login, register, logout }),
    [user, initializing],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside AuthProvider");
  return context;
}
