import axios from "axios";

const TOKEN_STORAGE_KEY = "ledgerline.accessToken";
const PENDING_OPERATIONS_KEY = "ledgerline.pendingOperations";
const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";
const apiBaseUrl = configuredApiBaseUrl && !/^https?:\/\//i.test(configuredApiBaseUrl)
  ? `https://${configuredApiBaseUrl}`
  : configuredApiBaseUrl;

export const api = axios.create({
  baseURL: apiBaseUrl || "http://localhost:8080",
  headers: { "Content-Type": "application/json" },
  timeout: 20000,
});

api.interceptors.request.use((config) => {
  const token = window.sessionStorage.getItem(TOKEN_STORAGE_KEY);
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && window.sessionStorage.getItem(TOKEN_STORAGE_KEY)) {
      window.sessionStorage.removeItem(TOKEN_STORAGE_KEY);
      window.dispatchEvent(new Event("ledgerline:unauthorized"));
    }
    return Promise.reject(error);
  },
);

export function getApiError(error) {
  if (error?.response?.data) {
    const body = error.response.data;
    return body.message || body.error || "The request could not be completed.";
  }
  if (error?.request) {
    return "Could not reach LedgerLine. Check the backend connection and retry.";
  }
  return error?.message || "Something went wrong.";
}

function randomKey() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID();
  const values = new Uint8Array(16);
  window.crypto.getRandomValues(values);
  return Array.from(values, (value) => value.toString(16).padStart(2, "0")).join("");
}

function getPendingOperations() {
  try {
    return JSON.parse(window.sessionStorage.getItem(PENDING_OPERATIONS_KEY) || "{}");
  } catch {
    return {};
  }
}

function operationFingerprint(path, payload) {
  return `${path}:${JSON.stringify(payload)}`;
}

export async function postIdempotent(path, payload) {
  const pending = getPendingOperations();
  const fingerprint = operationFingerprint(path, payload);
  const idempotencyKey = pending[fingerprint] || randomKey();
  pending[fingerprint] = idempotencyKey;
  window.sessionStorage.setItem(PENDING_OPERATIONS_KEY, JSON.stringify(pending));

  try {
    const response = await api.post(path, payload, {
      headers: { "Idempotency-Key": idempotencyKey },
    });
    const latest = getPendingOperations();
    delete latest[fingerprint];
    window.sessionStorage.setItem(PENDING_OPERATIONS_KEY, JSON.stringify(latest));
    return response.data;
  } catch (error) {
    // Keep the key and request body together so a retry cannot post the same
    // operation under a new key after a timeout or an expected refusal.
    throw error;
  }
}

export const tokenStorageKey = TOKEN_STORAGE_KEY;
