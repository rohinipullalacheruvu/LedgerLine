import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { useToast } from "../context/ToastContext";

const iconByPath = {
  "/dashboard": "◫",
  "/accounts": "▤",
  "/deposit": "＋",
  "/withdraw": "−",
  "/transfer": "⇄",
  "/transactions": "↗",
  "/statements": "▧",
  "/admin": "⌘",
  "/auditor": "◎",
};

function itemsForRole(role) {
  if (role === "ADMIN") {
    return [
      ["/admin", "Admin dashboard"],
      ["/accounts", "All accounts"],
      ["/transactions", "Transactions"],
      ["/statements", "Statements"],
    ];
  }
  if (role === "AUDITOR") {
    return [
      ["/auditor", "Audit overview"],
      ["/accounts", "Accounts"],
      ["/transactions", "Transactions"],
      ["/statements", "Statements"],
    ];
  }
  return [
    ["/dashboard", "Overview"],
    ["/accounts", "My accounts"],
    ["/deposit", "Deposit money"],
    ["/withdraw", "Withdraw money"],
    ["/transfer", "Transfer money"],
    ["/transactions", "Transactions"],
    ["/statements", "Statements"],
  ];
}

export default function AppShell() {
  const { user, logout } = useAuth();
  const notify = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const items = itemsForRole(user.role);
  const currentLabel = items.find(([path]) =>
    location.pathname === path || location.pathname.startsWith(`${path}/`),
  )?.[1] || "LedgerLine";

  async function handleLogout() {
    try {
      await logout();
    } catch {
      notify("Your local session was cleared; the server logout could not be confirmed.", "error");
    }
    navigate("/login", { replace: true });
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <NavLink className="brand" to="/dashboard" aria-label="LedgerLine home">
          <span className="brand-mark" aria-hidden="true"><span /><span /><span /><span /></span>
          <span>ledgerline</span>
        </NavLink>
        <div className="workspace-label">WORKSPACE</div>
        <nav className="primary-nav" aria-label="Main navigation">
          {items.map(([path, label]) => (
            <NavLink
              className={({ isActive }) => `nav-link${isActive ? " active" : ""}`}
              end={path === "/dashboard" || path === "/admin" || path === "/auditor"}
              to={path}
              key={path}
            >
              <span className="nav-icon" aria-hidden="true">{iconByPath[path]}</span>
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="integrity-note"><span>✓</span><div><strong>Ledger-first</strong><small>Every movement is recorded.</small></div></div>
          <div className="profile">
            <div className="avatar">{(user.fullName || user.username || "?").slice(0, 1).toUpperCase()}</div>
            <div className="profile-copy"><strong>{user.fullName || user.username}</strong><span>{user.role.replaceAll("_", " ")}</span></div>
            <button className="logout-button" onClick={handleLogout} type="button">↗</button>
          </div>
        </div>
      </aside>
      <main className="main-content">
        <header className="topbar">
          <div className="breadcrumb"><span>LedgerLine</span><b>/</b> {currentLabel}</div>
          <span className="secure-pill"><span /> Secure session</span>
        </header>
        <div className="page-content"><Outlet /></div>
        <footer className="page-footer"><span>© {new Date().getFullYear()} LedgerLine</span><span>Money movement with a ledger you can prove.</span></footer>
      </main>
    </div>
  );
}

