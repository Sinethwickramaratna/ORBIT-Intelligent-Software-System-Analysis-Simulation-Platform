const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const REFRESH_KEY = "orbit.refreshToken";

export type DatabaseState = "NOT_CONFIGURED" | "CONNECTING" | "READY" | "FAILED";

export interface SetupStatus {
  secretKeyConfigured: boolean;
  databaseCredentialsConfigured: boolean;
  databaseState: DatabaseState;
  databaseMessage: string;
  databaseName: string;
  databasePort: number;
  hasUsers: boolean;
  themeSelected: boolean;
  theme: string | null;
}

export interface EnvironmentInput {
  secretKey?: string;
  dbUsername?: string;
  dbPassword?: string;
}

export interface User {
  userId: string;
  userName: string;
  createdAt: string;
  updatedAt: string;
}

interface TokenResponse {
  tokenType: string;
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  user: User | null;
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fieldErrors?: Record<string, string>,
  ) {
    super(message);
  }
}

/** Access token lives only in memory; the refresh token is kept so a page reload can resume the session. */
let accessToken: string | null = null;

function getStoredRefreshToken(): string | null {
  try {
    return localStorage.getItem(REFRESH_KEY);
  } catch {
    return null;
  }
}

function storeRefreshToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(REFRESH_KEY, token);
    else localStorage.removeItem(REFRESH_KEY);
  } catch {
    /* ignore */
  }
}

export function hasStoredSession(): boolean {
  return getStoredRefreshToken() !== null;
}

async function request<T>(path: string, init: RequestInit = {}, token?: string | null): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, { ...init, headers });
  } catch {
    throw new ApiError(0, "NETWORK", "Cannot reach the ORBIT backend. Is it running on " + BASE_URL + "?");
  }
  if (response.status === 204) return undefined as T;

  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new ApiError(
      response.status,
      data?.code ?? "UNKNOWN",
      data?.message ?? `Request failed (${response.status})`,
      data?.fieldErrors,
    );
  }
  return data as T;
}

/** Authenticated call: on an expired/invalid access token, mints a new one from the refresh token and retries once. */
async function authed<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!accessToken) await refreshAccessToken();
  try {
    return await request<T>(path, init, accessToken);
  } catch (e) {
    if (e instanceof ApiError && e.status === 401 && (e.code === "TOKEN_EXPIRED" || e.code === "TOKEN_INVALID")) {
      await refreshAccessToken();
      return request<T>(path, init, accessToken);
    }
    throw e;
  }
}

async function refreshAccessToken(): Promise<void> {
  const refreshToken = getStoredRefreshToken();
  if (!refreshToken) {
    accessToken = null;
    throw new ApiError(401, "NO_SESSION", "You are not logged in");
  }
  try {
    const res = await request<TokenResponse>("/api/auth/refresh", {
      method: "POST",
      body: JSON.stringify({ refreshToken }),
    });
    accessToken = res.accessToken;
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      // refresh token expired or was deleted - the session is over
      accessToken = null;
      storeRefreshToken(null);
    }
    throw e;
  }
}

export const api = {
  setupStatus: () => request<SetupStatus>("/api/setup/status"),
  saveEnvironment: (input: EnvironmentInput) =>
    request<SetupStatus>("/api/setup/environment", { method: "POST", body: JSON.stringify(input) }),
  retryDatabase: () => request<SetupStatus>("/api/setup/database/retry", { method: "POST" }),
  saveTheme: (theme: string) =>
    request<{ theme: string }>("/api/settings/theme", { method: "PUT", body: JSON.stringify({ theme }) }),
  register: (username: string, password: string) =>
    request<User>("/api/auth/register", { method: "POST", body: JSON.stringify({ username, password }) }),

  async login(username: string, password: string): Promise<User> {
    const res = await request<TokenResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
    accessToken = res.accessToken;
    storeRefreshToken(res.refreshToken);
    return res.user as User;
  },

  currentUser: () => authed<User>("/api/users/me"),

  async logout(): Promise<void> {
    const refreshToken = getStoredRefreshToken();
    try {
      if (refreshToken) {
        await authed<void>("/api/auth/logout", { method: "POST", body: JSON.stringify({ refreshToken }) });
      }
    } finally {
      accessToken = null;
      storeRefreshToken(null);
    }
  },
};
