import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api, getApiError } from "../services/api";
import {
  createAccount,
  getAccount,
  getPortfolioBalanceSummary,
  getStatement,
  listAccounts,
  loadStatements,
  moneyOperation,
  transferMoney,
  updateAdminAccount,
} from "../services/ledgerline";
import { useAuth } from "../context/AuthContext";
import { useToast } from "../context/ToastContext";
import {
  AccountTable,
  ActivityTable,
  CurrencyAmount,
  EmptyState,
  ErrorState,
  LoadingState,
  PageHeader,
  StatusBadge,
  SummaryCard,
  formatDate,
} from "../components/Ui";

const ACCOUNT_TYPES = ["CHECKING", "SAVINGS", "CASH"];
const TRANSACTION_TYPES = ["DEPOSIT", "WITHDRAWAL", "TRANSFER", "ADJUSTMENT"];

function homeForRole(role) {
  if (role === "ADMIN") return "/admin";
  if (role === "AUDITOR") return "/auditor";
  return "/dashboard";
}

function useAccounts() {
  const { user } = useAuth();
  const notify = useToast();
  const [accounts, setAccounts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const reload = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setAccounts(await listAccounts(user.role));
    } catch (requestError) {
      const message = getApiError(requestError);
      setError(message);
      notify(message, "error");
    } finally {
      setLoading(false);
    }
  }, [user.role, notify]);

  useEffect(() => { reload(); }, [reload]);
  return { accounts, setAccounts, loading, error, reload };
}

function formatCurrencyTotal(amount, currency) {
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  }).format(amount);
}

function flattenStatements(statements) {
  return statements.flatMap((statement) =>
    (statement.entries || []).map((entry) => ({
      ...entry,
      accountNumber: statement.accountNumber,
      accountId: statement.accountId,
      accountType: statement.accountType,
    })),
  ).sort((first, second) =>
    new Date(second.transactionDate).valueOf() - new Date(first.transactionDate).valueOf(),
  );
}

function PortfolioDashboard({ role, title, description, adminControls = false }) {
  const { accounts, setAccounts, loading, error, reload } = useAccounts();
  const [statements, setStatements] = useState([]);
  const [statementsLoading, setStatementsLoading] = useState(false);
  const [statementErrors, setStatementErrors] = useState(0);
  const [balanceSummary, setBalanceSummary] = useState([]);
  const [balanceSummaryError, setBalanceSummaryError] = useState("");
  const [updatingAccount, setUpdatingAccount] = useState(null);
  const notify = useToast();
  const activity = useMemo(() => flattenStatements(statements), [statements]);

  useEffect(() => {
    let active = true;
    getPortfolioBalanceSummary().then((summary) => {
      if (active) setBalanceSummary(summary);
    }).catch((requestError) => {
      if (active) setBalanceSummaryError(getApiError(requestError));
    });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    let active = true;
    if (!accounts.length) {
      setStatements([]);
      setStatementsLoading(false);
      setStatementErrors(0);
      return () => { active = false; };
    }
    setStatementsLoading(true);
    loadStatements(accounts).then((result) => {
      if (!active) return;
      setStatements(result.statements);
      setStatementErrors(result.errors.length);
    }).finally(() => { if (active) setStatementsLoading(false); });
    return () => { active = false; };
  }, [accounts]);

  async function changeStatus(account, status) {
    if (!status || status === account.status) return;
    setUpdatingAccount(account.id);
    try {
      const updated = await updateAdminAccount(account.id, { status });
      setAccounts((current) => current.map((item) => item.id === account.id ? updated : item));
      notify(`Account ${account.accountNumber} updated.`);
    } catch (requestError) {
      notify(getApiError(requestError), "error");
    } finally {
      setUpdatingAccount(null);
    }
  }

  if (loading) return <LoadingState label="Loading account balances…" />;
  if (error) return <ErrorState message={error} onRetry={reload} />;

  const accountAction = adminControls
    ? (account) => (
      account.accountType === "EXTERNAL_CLEARING"
        ? <span className="table-subtext">System account</span>
        : <select
          className="table-select"
          aria-label={`Change status for ${account.accountNumber}`}
          disabled={updatingAccount === account.id}
          value={account.status}
          onChange={(event) => changeStatus(account, event.target.value)}
        >
          {["ACTIVE", "FROZEN", "CLOSED"].map((status) => <option key={status}>{status}</option>)}
        </select>
    )
    : null;

  return (
    <>
      <PageHeader
        eyebrow={role === "ADMIN" ? "ADMINISTRATION" : role === "AUDITOR" ? "READ-ONLY ACCESS" : "YOUR FINANCES"}
        title={title}
        description={description}
        actions={role === "ACCOUNT_HOLDER" && <Link className="button button-primary" to="/accounts/new">＋ New account</Link>}
      />
      {statementErrors > 0 && (
        <div className="inline-alert" role="status">
          Some account statements could not be loaded. Dashboard totals below still use the balances returned by the account API.
        </div>
      )}
      {balanceSummaryError && (
        <div className="inline-alert" role="status">
          The backend portfolio summary is unavailable: {balanceSummaryError}
        </div>
      )}
      <section className="summary-grid" aria-label="Portfolio summary">
        <SummaryCard
          label="Accounts"
          value={accounts.length}
          note={role === "ACCOUNT_HOLDER" ? "Accounts returned for your user" : "Accounts visible to your role"}
          icon="▤"
          tone="blue"
        />
        <SummaryCard
          label="Total balance (by currency)"
          value={balanceSummary.length ? balanceSummary.map((item) => formatCurrencyTotal(Number(item.totalBalance), item.currency)).join(" · ") : "—"}
          note="Customer balances by currency; currencies are not combined"
          icon="◈"
          tone="mint"
        />
        <SummaryCard
          label="Deposits this month"
          value={balanceSummary.some((item) => item.monthlyDepositCount > 0) ? balanceSummary.filter((item) => item.monthlyDepositCount > 0).map((item) => formatCurrencyTotal(Number(item.monthlyDeposits), item.currency)).join(" · ") : "—"}
          note={`${balanceSummary.reduce((count, item) => count + item.monthlyDepositCount, 0)} posted deposits`}
          icon="↙"
          tone="blue"
        />
        <SummaryCard
          label="Withdrawals this month"
          value={balanceSummary.some((item) => item.monthlyWithdrawalCount > 0) ? balanceSummary.filter((item) => item.monthlyWithdrawalCount > 0).map((item) => formatCurrencyTotal(Number(item.monthlyWithdrawals), item.currency)).join(" · ") : "—"}
          note={`${balanceSummary.reduce((count, item) => count + item.monthlyWithdrawalCount, 0)} posted withdrawals`}
          icon="↗"
          tone="peach"
        />
      </section>
      <section className="panel section-panel">
        <div className="panel-heading">
          <div><h2>{role === "ACCOUNT_HOLDER" ? "Your accounts" : "Account directory"}</h2><p>Current account details and balances from LedgerLine.</p></div>
          <Link className="text-button" to="/accounts">All accounts <span>→</span></Link>
        </div>
        <AccountTable accounts={accounts.slice(0, 5)} role={role} />
      </section>
      <section className="panel section-panel">
        <div className="panel-heading">
          <div><h2>Recent posted activity</h2><p>Ledger entries returned by account statement APIs.</p></div>
          <Link className="text-button" to="/transactions">Transaction history <span>→</span></Link>
        </div>
        {statementsLoading
          ? <LoadingState label="Loading statements…" />
          : <ActivityTable entries={activity.slice(0, 6)} showAccount />}
      </section>
      {adminControls && (
        <section className="panel section-panel">
          <div className="panel-heading"><div><h2>Account administration</h2><p>Change an account’s permitted status. Balances cannot be edited here.</p></div></div>
          <AccountTable accounts={accounts} role={role} actions={accountAction} />
        </section>
      )}
      {role === "ACCOUNT_HOLDER" && (
        <div className="quick-actions">
          <Link className="quick-action" to="/deposit"><span className="quick-action-icon">＋</span><strong>Deposit</strong><small>Add funds to an account</small></Link>
          <Link className="quick-action" to="/withdraw"><span className="quick-action-icon">−</span><strong>Withdraw</strong><small>Move funds out</small></Link>
          <Link className="quick-action" to="/transfer"><span className="quick-action-icon">⇄</span><strong>Transfer</strong><small>Send money between accounts</small></Link>
        </div>
      )}
    </>
  );
}

export function DashboardPage() {
  const { user } = useAuth();
  return <PortfolioDashboard role={user.role} title="Money, in good order." description="A clear, live view of your accounts and recent ledger activity." />;
}

export function AdminDashboardPage() {
  return <PortfolioDashboard role="ADMIN" title="Administration" description="Review account status and system activity." adminControls />;
}

export function AuditorDashboardPage() {
  return <PortfolioDashboard role="AUDITOR" title="Audit overview" description="Read-only view of account balances and posted ledger activity." />;
}

function CreateAccountForm({ onCreated, onCancel }) {
  const notify = useToast();
  const [values, setValues] = useState({ name: "", accountType: "CHECKING", currency: "USD" });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  async function submit(event) {
    event.preventDefault();
    setSaving(true);
    setError("");
    try {
      const account = await createAccount(values);
      notify("Account created successfully.");
      onCreated(account);
    } catch (requestError) {
      const message = getApiError(requestError);
      setError(message);
      notify(message, "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="panel form-panel compact-form" onSubmit={submit}>
      <div className="panel-heading"><div><h2>Open a new account</h2><p>The account owner is taken from your signed-in profile.</p></div></div>
      {error && <div className="form-error" role="alert">{error}</div>}
      <div className="form-grid">
        <label className="field"><span>Account name</span><input required maxLength="100" value={values.name} onChange={(event) => setValues({ ...values, name: event.target.value })} placeholder="Everyday checking" /></label>
        <label className="field"><span>Account type</span><select value={values.accountType} onChange={(event) => setValues({ ...values, accountType: event.target.value })}>{ACCOUNT_TYPES.map((type) => <option key={type}>{type}</option>)}</select></label>
        <label className="field"><span>Currency</span><input required minLength="3" maxLength="3" pattern="[A-Za-z]{3}" value={values.currency} onChange={(event) => setValues({ ...values, currency: event.target.value.toUpperCase() })} /></label>
      </div>
      <div className="form-actions"><button className="button button-secondary" type="button" onClick={onCancel}>Cancel</button><button className="button button-primary" disabled={saving}>{saving ? "Creating…" : "Create account"}</button></div>
    </form>
  );
}

export function AccountsPage({ createOnly = false }) {
  const { user } = useAuth();
  const { accounts, loading, error, reload, setAccounts } = useAccounts();
  const [showCreate, setShowCreate] = useState(createOnly);
  const navigate = useNavigate();

  function created(account) {
    setAccounts((current) => [account, ...current]);
    setShowCreate(false);
    navigate(`/accounts/${account.id}`);
  }

  if (loading) return <LoadingState label="Loading accounts…" />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  return (
    <>
      <PageHeader
        eyebrow={user.role === "ACCOUNT_HOLDER" ? "ACCOUNT MANAGEMENT" : "READ-ONLY ACCOUNT ACCESS"}
        title={user.role === "ACCOUNT_HOLDER" ? "My accounts" : "Accounts"}
        description={user.role === "ACCOUNT_HOLDER" ? "View account balances and open an account." : "Account and balance data visible to your role."}
        actions={user.role === "ACCOUNT_HOLDER" && !showCreate && <button className="button button-primary" onClick={() => setShowCreate(true)} type="button">＋ New account</button>}
      />
      {showCreate && user.role === "ACCOUNT_HOLDER" && <CreateAccountForm onCreated={created} onCancel={() => { setShowCreate(false); if (createOnly) navigate("/accounts"); }} />}
      <section className="panel section-panel">
        <div className="panel-heading"><div><h2>Account directory</h2><p>Account details are loaded from your authorized LedgerLine API.</p></div></div>
        <AccountTable accounts={accounts} role={user.role} />
      </section>
    </>
  );
}

export function AccountDetailsPage() {
  const { accountId: pathAccountId } = useParams();
  const { user } = useAuth();
  const notify = useToast();
  const [account, setAccount] = useState(null);
  const [statement, setStatement] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);
  const editable = user.role === "ACCOUNT_HOLDER";

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [accountResult, statementResult] = await Promise.all([
        getAccount(pathAccountId, user.role),
        getStatement(pathAccountId),
      ]);
      setAccount(accountResult);
      setName(accountResult.name);
      setStatement(statementResult);
    } catch (requestError) {
      setError(getApiError(requestError));
    } finally {
      setLoading(false);
    }
  }, [pathAccountId, user.role]);

  useEffect(() => { load(); }, [load]);
  if (loading) return <LoadingState label="Loading account details…" />;
  if (error) return <ErrorState message={error} onRetry={load} />;

  async function renameAccount(event) {
    event.preventDefault();
    setSaving(true);
    try {
      const { data } = await api.patch(`/api/accounts/${account.id}`, { name });
      setAccount(data);
      notify("Account name updated.");
    } catch (requestError) {
      notify(getApiError(requestError), "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <PageHeader eyebrow="ACCOUNT DETAILS" title={account.name} description={account.accountNumber} actions={<Link className="button button-secondary" to={`/statements?accountId=${account.id}`}>View statement</Link>} />
      <section className="summary-grid">
        <SummaryCard label="Available balance" value={<CurrencyAmount amount={account.balance} currency={account.currency} />} note={`Balance reported by the account API · ${account.currency}`} icon="◈" tone="blue" />
        <SummaryCard label="Account type" value={account.accountType} note={`Currency: ${account.currency}`} icon="▤" tone="mint" />
        <SummaryCard label="Account status" value={<StatusBadge status={account.status} />} note="Operations are validated by the backend" icon="✓" tone="peach" />
      </section>
      {editable && (
        <form className="panel form-panel account-rename" onSubmit={renameAccount}>
          <div className="panel-heading"><div><h2>Account details</h2><p>Only the account name can be changed here.</p></div></div>
          <label className="field"><span>Account name</span><input required maxLength="100" value={name} onChange={(event) => setName(event.target.value)} /></label>
          <div className="form-actions"><button className="button button-primary" disabled={saving}>{saving ? "Saving…" : "Save name"}</button></div>
        </form>
      )}
      <section className="panel section-panel">
        <div className="panel-heading"><div><h2>Recent ledger entries</h2><p>Posted entries from the account statement API.</p></div><Link className="text-button" to={`/statements?accountId=${account.id}`}>Full statement <span>→</span></Link></div>
        <ActivityTable entries={(statement?.entries || []).slice(0, 8).map((entry) => ({ ...entry, accountNumber: statement.accountNumber }))} />
      </section>
    </>
  );
}

export function MoneyOperationPage({ operation }) {
  const { accounts, loading, error, reload } = useAccounts();
  const notify = useToast();
  const isDeposit = operation === "DEPOSIT";
  const [accountId, setAccountId] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState("");
  const [result, setResult] = useState(null);

  const activeAccounts = accounts.filter((account) => account.status === "ACTIVE");
  useEffect(() => {
    if (activeAccounts.length && !activeAccounts.some((account) => String(account.id) === accountId)) {
      setAccountId(String(activeAccounts[0].id));
    }
  }, [activeAccounts, accountId]);

  async function submit(event) {
    event.preventDefault();
    setSubmitting(true);
    setFormError("");
    setResult(null);
    try {
      const response = await moneyOperation(accountId, operation, {
        amount: Number(amount).toFixed(4),
        description: description.trim() || null,
      });
      setResult(response);
      notify(`${isDeposit ? "Deposit" : "Withdrawal"} posted successfully.`);
      setAmount("");
      setDescription("");
    } catch (requestError) {
      const message = getApiError(requestError);
      setFormError(message);
      notify(message, "error");
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <LoadingState label="Loading accounts…" />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  return (
    <>
      <PageHeader eyebrow="MONEY MOVEMENT" title={isDeposit ? "Deposit money" : "Withdraw money"} description={isDeposit ? "Post a deposit to an authorized active account." : "Withdraw from an authorized account with sufficient available funds."} />
      {!activeAccounts.length
        ? <EmptyState title="No active accounts available">Create or activate an account before starting a money movement.</EmptyState>
        : (
          <div className="operation-layout">
            <form className="panel form-panel operation-form" onSubmit={submit}>
              <div className="panel-heading"><div><h2>{isDeposit ? "Deposit details" : "Withdrawal details"}</h2><p>The server validates permissions, balance, and ledger postings.</p></div></div>
              {formError && <div className="form-error" role="alert">{formError}<small>Retrying the same details reuses the idempotency key.</small></div>}
              <label className="field"><span>Account</span><select required value={accountId} onChange={(event) => setAccountId(event.target.value)}>{activeAccounts.map((account) => <option value={account.id} key={account.id}>{account.name} · {account.accountNumber} · {account.currency}</option>)}</select></label>
              <label className="field"><span>Amount</span><div className="amount-input"><input required type="number" min="0.0001" step="0.0001" inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} placeholder="0.00" /><span>{activeAccounts.find((account) => String(account.id) === accountId)?.currency || ""}</span></div></label>
              <label className="field"><span>Description <small>Optional</small></span><textarea rows="3" maxLength="500" value={description} onChange={(event) => setDescription(event.target.value)} placeholder="Add a note for your records" /></label>
              {result && <div className="success-panel" role="status"><strong>Transaction posted</strong><span>Reference #{result.transactionId} · {formatDate(result.postedAt || result.createdAt)}</span></div>}
              <button className="button button-primary button-wide" disabled={submitting}>{submitting ? <><span className="button-spinner" />Submitting…</> : isDeposit ? "Confirm deposit" : "Confirm withdrawal"}</button>
              <p className="form-footnote">Each operation is protected with a server-verified idempotency key. Balances are never changed in the browser.</p>
            </form>
            <aside className="panel side-note"><div className="note-icon">✓</div><h2>Posted securely</h2><p>LedgerLine records the transaction and balanced ledger entries together. If the connection drops, retry this same request to safely receive its original result.</p><div className="note-rule" /><span className="secure-caption">Your account balance is always sourced from the backend.</span></aside>
          </div>
        )}
    </>
  );
}

export function TransferPage() {
  const { accounts, loading, error, reload } = useAccounts();
  const notify = useToast();
  const [senderId, setSenderId] = useState("");
  const [receiverAccountNumber, setReceiverAccountNumber] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState("");
  const [result, setResult] = useState(null);

  const activeAccounts = accounts.filter((account) => account.status === "ACTIVE");
  useEffect(() => {
    if (activeAccounts.length && !activeAccounts.some((account) => String(account.id) === senderId)) {
      setSenderId(String(activeAccounts[0].id));
    }
  }, [activeAccounts, senderId]);
  const sender = activeAccounts.find((account) => String(account.id) === senderId);

  async function submit(event) {
    event.preventDefault();
    setSubmitting(true);
    setFormError("");
    setResult(null);
    try {
      const response = await transferMoney({
        senderAccountNumber: sender.accountNumber,
        receiverAccountNumber: receiverAccountNumber.trim(),
        amount: Number(amount).toFixed(4),
        description: description.trim() || null,
      });
      setResult(response);
      notify("Transfer posted successfully.");
      setAmount("");
      setReceiverAccountNumber("");
      setDescription("");
    } catch (requestError) {
      const message = getApiError(requestError);
      setFormError(message);
      notify(message, "error");
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <LoadingState label="Loading accounts…" />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  return (
    <>
      <PageHeader eyebrow="MONEY MOVEMENT" title="Transfer money" description="Send funds to another active LedgerLine account." />
      {!activeAccounts.length
        ? <EmptyState title="No active sender accounts">You need an active account with transfer permission.</EmptyState>
        : (
          <div className="operation-layout">
            <form className="panel form-panel operation-form" onSubmit={submit}>
              <div className="panel-heading"><div><h2>Transfer details</h2><p>Both account rows are locked and validated by the backend.</p></div></div>
              {formError && <div className="form-error" role="alert">{formError}<small>Retrying the same details reuses the idempotency key.</small></div>}
              <label className="field"><span>From account</span><select required value={senderId} onChange={(event) => setSenderId(event.target.value)}>{activeAccounts.map((account) => <option value={account.id} key={account.id}>{account.name} · {account.accountNumber} · {account.currency}</option>)}</select></label>
              <label className="field"><span>To account number</span><input required maxLength="34" value={receiverAccountNumber} onChange={(event) => setReceiverAccountNumber(event.target.value)} placeholder="Enter receiver account number" /></label>
              <label className="field"><span>Amount</span><div className="amount-input"><input required type="number" min="0.0001" step="0.0001" inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} placeholder="0.00" /><span>{sender?.currency || ""}</span></div></label>
              <label className="field"><span>Description <small>Optional</small></span><textarea rows="3" maxLength="500" value={description} onChange={(event) => setDescription(event.target.value)} placeholder="What is this transfer for?" /></label>
              {result && <div className="success-panel" role="status"><strong>Transfer posted · {result.transactionReference}</strong><span>{result.senderAccountNumber} → {result.receiverAccountNumber} · {formatDate(result.postedAt || result.createdAt)}</span></div>}
              <button className="button button-primary button-wide" disabled={submitting || !sender}>{submitting ? <><span className="button-spinner" />Submitting…</> : "Confirm transfer"}</button>
              <p className="form-footnote">The transfer is atomic: sender debit, receiver credit, and both balances commit together.</p>
            </form>
            <aside className="panel side-note"><div className="note-icon">⇄</div><h2>Confirm the details</h2><p>Transfers are final once posted. The recipient account number, available balance, currency, and your permission are all verified server-side.</p><div className="note-rule" /><span className="secure-caption">Never share your password or authentication token.</span></aside>
          </div>
        )}
    </>
  );
}

function StatementsBrowser({ pageTitle, pageDescription, historyMode = false }) {
  const { accounts, loading: accountsLoading, error: accountsError, reload } = useAccounts();
  const [searchParams, setSearchParams] = useSearchParams();
  const [accountId, setAccountId] = useState(searchParams.get("accountId") || "");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [transactionType, setTransactionType] = useState("");
  const [statement, setStatement] = useState(null);
  const [statements, setStatements] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [partialFailureCount, setPartialFailureCount] = useState(0);

  useEffect(() => {
    const queryId = searchParams.get("accountId") || "";
    if (queryId && queryId !== accountId) setAccountId(queryId);
  }, [searchParams, accountId]);

  const runSearch = useCallback(async () => {
    setLoading(true);
    setError("");
    setStatement(null);
    setStatements([]);
    try {
      const filters = { fromDate, toDate, transactionType };
      if (historyMode) {
        const result = await loadStatements(accounts, filters);
        setStatements(result.statements);
        setPartialFailureCount(result.errors.length);
      } else if (accountId) {
        setStatement(await getStatement(accountId, filters));
        setPartialFailureCount(0);
      } else {
        setError("Select an account to view its statement.");
      }
    } catch (requestError) {
      setError(getApiError(requestError));
    } finally {
      setLoading(false);
    }
  }, [accounts, accountId, fromDate, historyMode, toDate, transactionType]);

  useEffect(() => {
    if (accountsLoading || accountsError || !accounts.length) return;
    if (historyMode || accountId) runSearch();
  }, [accountsLoading, accountsError, accounts, accountId, historyMode]);

  function changeAccount(value) {
    setAccountId(value);
    setSearchParams(value ? { accountId: value } : {});
  }

  if (accountsLoading) return <LoadingState label="Loading authorized accounts…" />;
  if (accountsError) return <ErrorState message={accountsError} onRetry={reload} />;

  const entries = historyMode
    ? flattenStatements(statements)
    : (statement?.entries || []).map((entry) => ({ ...entry, accountNumber: statement?.accountNumber }));

  return (
    <>
      <PageHeader eyebrow={historyMode ? "POSTED ACTIVITY" : "ACCOUNT RECORD"} title={pageTitle} description={pageDescription} />
      <section className="panel filter-panel">
        <form className="filter-form" onSubmit={(event) => { event.preventDefault(); runSearch(); }}>
          {!historyMode && (
            <label className="field"><span>Account</span><select value={accountId} onChange={(event) => changeAccount(event.target.value)}><option value="">Select an account</option>{accounts.map((account) => <option value={account.id} key={account.id}>{account.name} · {account.accountNumber}</option>)}</select></label>
          )}
          <label className="field"><span>From date</span><input type="date" value={fromDate} onChange={(event) => setFromDate(event.target.value)} /></label>
          <label className="field"><span>To date</span><input type="date" value={toDate} onChange={(event) => setToDate(event.target.value)} /></label>
          <label className="field"><span>Type</span><select value={transactionType} onChange={(event) => setTransactionType(event.target.value)}><option value="">All types</option>{TRANSACTION_TYPES.map((type) => <option key={type}>{type}</option>)}</select></label>
          <button className="button button-primary filter-submit" disabled={loading}>{loading ? "Loading…" : "Apply filters"}</button>
        </form>
      </section>
      {error && <div className="inline-alert error-alert" role="alert">{error}<button type="button" onClick={runSearch}>Retry</button></div>}
      {partialFailureCount > 0 && <div className="inline-alert" role="status">{partialFailureCount} account statement(s) could not be loaded. Displayed rows are from the accounts that responded.</div>}
      {!historyMode && statement && (
        <section className="statement-overview">
          <article className="panel statement-account">
            <span className="eyebrow">ACCOUNT</span>
            <strong>{statement.accountNumber}</strong>
            <span>Immutable posted ledger entries</span>
          </article>
          <article className="panel statement-balance">
            <span className="eyebrow">CURRENT BALANCE</span>
            <strong><CurrencyAmount amount={statement.currentBalance} currency={statement.currency} /></strong>
            <span>Balance returned by the account API</span>
          </article>
        </section>
      )}
      <section className="panel section-panel">
        <div className="panel-heading"><div><h2>{historyMode ? "Transaction history" : "Account statement"}</h2><p>Read-only ledger records; posted entries cannot be edited or deleted.</p></div><span className="results-count">{entries.length} entries</span></div>
        {loading ? <LoadingState label="Loading ledger entries…" /> : <ActivityTable entries={entries} showAccount={historyMode} />}
      </section>
    </>
  );
}

export function TransactionHistoryPage() {
  return <StatementsBrowser pageTitle="Transaction history" pageDescription="Review posted ledger activity across accounts available to your role." historyMode />;
}

export function StatementPage() {
  return <StatementsBrowser pageTitle="Account statement" pageDescription="Filter an account’s immutable ledger and inspect its running balances." />;
}

export function LoginPage() {
  const { user, login, initializing } = useAuth();
  const notify = useToast();
  const [values, setValues] = useState({ usernameOrEmail: "", password: "" });
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => { if (user && !initializing) navigate(homeForRole(user.role), { replace: true }); }, [user, initializing, navigate]);

  async function submit(event) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      const signedInUser = await login(values);
      navigate(location.state?.from || homeForRole(signedInUser.role), { replace: true });
    } catch (requestError) {
      const message = getApiError(requestError);
      setError(message);
      notify(message, "error");
    } finally {
      setSubmitting(false);
    }
  }

  return <AuthFrame mode="login" onSubmit={submit} values={values} setValues={setValues} error={error} submitting={submitting} />;
}

export function RegisterPage() {
  const { user, register, initializing } = useAuth();
  const notify = useToast();
  const [values, setValues] = useState({ username: "", email: "", fullName: "", password: "" });
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  useEffect(() => { if (user && !initializing) navigate(homeForRole(user.role), { replace: true }); }, [user, initializing, navigate]);

  async function submit(event) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      const newUser = await register(values);
      navigate(homeForRole(newUser.role), { replace: true });
    } catch (requestError) {
      const message = getApiError(requestError);
      setError(message);
      notify(message, "error");
    } finally {
      setSubmitting(false);
    }
  }
  return <AuthFrame mode="register" onSubmit={submit} values={values} setValues={setValues} error={error} submitting={submitting} />;
}

function AuthFrame({ mode, onSubmit, values, setValues, error, submitting }) {
  const isLogin = mode === "login";
  return (
    <div className="auth-page">
      <section className="auth-story">
        <Link className="brand auth-brand" to="/login"><span className="brand-mark" aria-hidden="true"><span /><span /><span /><span /></span><span>ledgerline</span></Link>
        <div className="auth-story-content">
          <span className="auth-kicker">MONEY MOVEMENT, MADE CLEAR</span>
          <h1>A ledger you can prove.</h1>
          <p>Every account, movement, and statement in one considered place.</p>
          <div className="auth-proof"><span>✓</span><div><strong>Ledger-first by design</strong><small>Balances follow posted records.</small></div></div>
        </div>
        <div className="auth-story-footer">Secure access · Account permissions · Append-only statements</div>
      </section>
      <main className="auth-main">
        <div className="auth-form-wrap">
          <div className="auth-mobile-brand"><span className="brand-mark" aria-hidden="true"><span /><span /><span /><span /></span> ledgerline</div>
          <span className="eyebrow">{isLogin ? "WELCOME BACK" : "GET STARTED"}</span>
          <h2>{isLogin ? "Sign in to LedgerLine" : "Create your account"}</h2>
          <p className="auth-subtitle">{isLogin ? "Use your registered email or username." : "Create an account holder profile to get started."}</p>
          <form className="auth-form" onSubmit={onSubmit}>
            {error && <div className="form-error" role="alert">{error}</div>}
            {!isLogin && <>
              <label className="field"><span>Full name</span><input required maxLength="120" autoComplete="name" value={values.fullName} onChange={(event) => setValues({ ...values, fullName: event.target.value })} /></label>
              <label className="field"><span>Username</span><input required minLength="3" maxLength="50" pattern="[A-Za-z0-9._-]+" autoComplete="username" value={values.username} onChange={(event) => setValues({ ...values, username: event.target.value })} /></label>
              <label className="field"><span>Email</span><input required type="email" maxLength="254" autoComplete="email" value={values.email} onChange={(event) => setValues({ ...values, email: event.target.value })} /></label>
            </>}
            {isLogin && <label className="field"><span>Email or username</span><input required maxLength="254" autoComplete="username" value={values.usernameOrEmail} onChange={(event) => setValues({ ...values, usernameOrEmail: event.target.value })} /></label>}
            <label className="field"><span>Password</span><input required type="password" minLength={isLogin ? undefined : 12} maxLength={isLogin ? 256 : 72} autoComplete={isLogin ? "current-password" : "new-password"} value={values.password} onChange={(event) => setValues({ ...values, password: event.target.value })} /></label>
            {!isLogin && <small className="field-hint">Use at least 12 characters. Registration creates an account-holder role.</small>}
            <button className="button button-primary button-wide" disabled={submitting}>{submitting ? <><span className="button-spinner" />Please wait…</> : isLogin ? "Sign in" : "Create account"}</button>
          </form>
          <div className="auth-switch">{isLogin ? "New to LedgerLine?" : "Already have an account?"} <Link to={isLogin ? "/register" : "/login"}>{isLogin ? "Create an account" : "Sign in"}</Link></div>
          <div className="auth-security"><span>▣</span> Protected with secure token-based authentication</div>
        </div>
      </main>
    </div>
  );
}
