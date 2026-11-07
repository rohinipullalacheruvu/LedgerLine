import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import AppShell from "./components/AppShell";
import ProtectedRoute from "./components/ProtectedRoute";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { ToastProvider } from "./context/ToastContext";
import {
  AccountDetailsPage,
  AccountsPage,
  AdminDashboardPage,
  AuditorDashboardPage,
  DashboardPage,
  LoginPage,
  MoneyOperationPage,
  RegisterPage,
  StatementPage,
  TransactionHistoryPage,
  TransferPage,
} from "./pages/Pages";

function LandingRedirect() {
  const { user, initializing } = useAuth();
  if (initializing) return <div className="full-screen-loader"><span className="spinner" />Loading LedgerLine…</div>;
  const home = user?.role === "ADMIN"
    ? "/admin"
    : user?.role === "AUDITOR"
      ? "/auditor"
      : user
        ? "/dashboard"
        : "/login";
  return <Navigate to={home} replace />;
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<LandingRedirect />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/accounts/new" element={<AccountsPage createOnly />} />
          <Route path="/accounts/:accountId" element={<AccountDetailsPage />} />
          <Route path="/transactions" element={<TransactionHistoryPage />} />
          <Route path="/statements" element={<StatementPage />} />
          <Route element={<ProtectedRoute roles={["ACCOUNT_HOLDER"]} />}>
            <Route path="/deposit" element={<MoneyOperationPage operation="DEPOSIT" />} />
            <Route path="/withdraw" element={<MoneyOperationPage operation="WITHDRAWAL" />} />
            <Route path="/transfer" element={<TransferPage />} />
          </Route>
          <Route element={<ProtectedRoute roles={["ADMIN"]} />}>
            <Route path="/admin" element={<AdminDashboardPage />} />
          </Route>
          <Route element={<ProtectedRoute roles={["AUDITOR"]} />}>
            <Route path="/auditor" element={<AuditorDashboardPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="*" element={<LandingRedirect />} />
    </Routes>
  );
}

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <ToastProvider>
          <AppRoutes />
        </ToastProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
