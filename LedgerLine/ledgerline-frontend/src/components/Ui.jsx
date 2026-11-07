import { Link } from "react-router-dom";

export function PageHeader({ eyebrow, title, description, actions }) {
  return (
    <div className="page-heading">
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="page-heading-actions">{actions}</div>}
    </div>
  );
}

export function LoadingState({ label = "Loading LedgerLine data…" }) {
  return <div className="loading-state"><span className="spinner" />{label}</div>;
}

export function ErrorState({ message, onRetry }) {
  return (
    <div className="state-card state-error" role="alert">
      <strong>We couldn’t load this information.</strong>
      <p>{message}</p>
      {onRetry && <button className="button button-secondary" onClick={onRetry} type="button">Try again</button>}
    </div>
  );
}

export function EmptyState({ title, children, action }) {
  return (
    <div className="state-card empty-card">
      <span className="empty-symbol" aria-hidden="true">⌁</span>
      <strong>{title}</strong>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function CurrencyAmount({ amount, currency, className = "" }) {
  const numericAmount = Number(amount);
  if (!Number.isFinite(numericAmount)) return <span className={className}>—</span>;
  return (
    <span className={className}>
      {new Intl.NumberFormat(undefined, {
        style: "currency",
        currency: currency || "USD",
        minimumFractionDigits: 2,
        maximumFractionDigits: 4,
      }).format(numericAmount)}
    </span>
  );
}

export function StatusBadge({ status }) {
  const normalized = (status || "UNKNOWN").toLowerCase();
  return <span className={`status-badge status-${normalized}`}>{status || "Unknown"}</span>;
}

export function AccountTable({ accounts, role, actions }) {
  if (!accounts.length) {
    return (
      <EmptyState
        title="No accounts to show"
        action={role === "ACCOUNT_HOLDER" && <Link className="button button-primary" to="/accounts/new">Open an account</Link>}
      >
        Accounts returned by your access permissions will appear here.
      </EmptyState>
    );
  }
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr><th>Account</th><th>Type</th><th>Balance</th><th>Status</th>{role !== "ACCOUNT_HOLDER" && <th>Owner</th>}{actions && <th>Actions</th>}</tr>
        </thead>
        <tbody>
          {accounts.map((account) => (
            <tr key={account.id}>
              <td>
                <Link className="table-account" to={`/accounts/${account.id}`}>
                  <span className="account-avatar">{(account.name || "A").slice(0, 1).toUpperCase()}</span>
                  <span><strong>{account.name}</strong><small>{account.accountNumber}</small></span>
                </Link>
              </td>
              <td>{account.accountType}</td>
              <td className="amount-cell"><CurrencyAmount amount={account.balance} currency={account.currency} /></td>
              <td><StatusBadge status={account.status} /></td>
              {role !== "ACCOUNT_HOLDER" && <td>{account.ownerUsername || "System account"}</td>}
              {actions && <td>{actions(account)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function ActivityTable({ entries, showAccount = false }) {
  if (!entries.length) return <EmptyState title="No transactions found">Posted ledger activity matching these filters will appear here.</EmptyState>;
  return (
    <div className="table-scroll">
      <table className="data-table activity-table">
        <thead><tr>{showAccount && <th>Account</th>}<th>Transaction</th><th>Type</th><th>Entry</th><th>Amount</th><th>Running balance</th><th>Status</th><th>Date</th></tr></thead>
        <tbody>
          {entries.map((entry, index) => (
            <tr key={`${entry.accountNumber || entry.accountId}-${entry.transactionId}-${entry.entrySide}-${index}`}>
              {showAccount && <td><span className="table-strong">{entry.accountNumber}</span></td>}
              <td>
                <span className="table-strong">#{entry.transactionId}</span>
                <small className="table-subtext">{entry.description || "No description"}</small>
              </td>
              <td><span className="type-chip">{entry.transactionType}</span></td>
              <td><span className={`entry-side side-${(entry.entrySide || "").toLowerCase()}`}>{entry.entrySide}</span></td>
              <td className="amount-cell"><CurrencyAmount amount={entry.amount} currency={entry.currency} /></td>
              <td className="amount-cell"><CurrencyAmount amount={entry.runningBalance} currency={entry.currency} /></td>
              <td><StatusBadge status={entry.status} /></td>
              <td>{formatDate(entry.transactionDate)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function formatDate(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.valueOf())
    ? "—"
    : new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

export function SummaryCard({ label, value, note, icon, tone = "navy" }) {
  return (
    <article className="summary-card">
      <div className={`summary-icon ${tone}`} aria-hidden="true">{icon}</div>
      <div className="summary-label">{label}</div>
      <div className="summary-value">{value}</div>
      <div className="summary-detail">{note}</div>
    </article>
  );
}

