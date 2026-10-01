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
  /** Docker only: the drives/folders ORBIT may open still have to be chosen. */
  foldersSetupNeeded: boolean;
  /** .env lists other folders than the running backend can open: containers must be re-created. */
  foldersRestartRequired: boolean;
  folders: string[];
}

export interface EnvironmentInput {
  secretKey?: string;
  dbUsername?: string;
  dbPassword?: string;
  folders?: string[];
}

export interface FolderSettings {
  folders: string[];
  active: string[];
  restartRequired: boolean;
  editable: boolean;
  max: number;
  /** The host helper is running: saving re-creates the containers by itself. */
  autoApply: boolean;
  applying: boolean;
  /** Why automatic apply is or is not possible. NO_SIGNAL_FOLDER: containers older than the helper. */
  helper: "RUNNING" | "NOT_RUNNING" | "NO_SIGNAL_FOLDER";
}

export interface User {
  userId: string;
  userName: string;
  createdAt: string;
  updatedAt: string;
}

export const PROJECT_TYPES = [
  { value: "WEB_APPLICATION", label: "Web Application" },
  { value: "DESKTOP_APPLICATION", label: "Desktop Application" },
  { value: "MOBILE_APPLICATION", label: "Mobile Application" },
  { value: "DISTRIBUTED_SYSTEM", label: "Distributed System" },
  { value: "MICROSERVICES_SYSTEM", label: "Microservices System" },
  { value: "BACKEND_API", label: "Backend / API" },
  { value: "DATA_ML_SYSTEM", label: "Data / ML System" },
  { value: "EMBEDDED_IOT_SYSTEM", label: "Embedded / IoT System" },
  { value: "OTHER", label: "Other" },
] as const;
export type ProjectType = (typeof PROJECT_TYPES)[number]["value"];

export interface Project {
  projectId: string;
  projectName: string;
  location: string;
  projectType: ProjectType;
  projectTypeLabel: string;
  description: string | null;
  userId: string;
  createdAt: string;
  folderAvailable: boolean;
  gitRepository: boolean;
  gitStatus: "INITIALIZED" | "ALREADY_EXISTS" | "SKIPPED" | null;
  folderCreated: boolean;
}

export interface CreateProjectInput {
  projectName: string;
  location: string;
  projectType: ProjectType;
  description?: string;
  initGit: boolean;
}

export interface TreeEntry {
  name: string;
  path: string;
  kind: "FOLDER" | "FILE";
  size: number | null;
  hasChildren: boolean;
}

export interface ProjectTree {
  path: string;
  entries: TreeEntry[];
  truncated: boolean;
}

export interface LocationInspection {
  valid: boolean;
  message: string | null;
  exists: boolean;
  gitRepository: boolean;
}

export interface BrowseFolder {
  name: string;
  path: string;
}

export interface FolderBrowse {
  path: string;
  parent: string | null;
  folders: BrowseFolder[];
  shortcuts: BrowseFolder[];
  truncated: boolean;
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

  getFolderSettings: () => authed<FolderSettings>("/api/settings/folders"),
  saveFolderSettings: (folders: string[]) =>
    authed<FolderSettings>("/api/settings/folders", { method: "PUT", body: JSON.stringify({ folders }) }),
  listProjects: () => authed<Project[]>("/api/projects"),
  createProject: (input: CreateProjectInput) =>
    authed<Project>("/api/projects", { method: "POST", body: JSON.stringify(input) }),
  deleteProject: (projectId: string, confirmName: string) =>
    authed<void>(`/api/projects/${projectId}?confirmName=${encodeURIComponent(confirmName)}`, { method: "DELETE" }),
  projectConfig: () => authed<{ root: string | null; roots: string[] }>("/api/projects/config"),
  inspectLocation: (location: string) =>
    authed<LocationInspection>(`/api/projects/inspect?location=${encodeURIComponent(location)}`),
  browseFolders: (path?: string) =>
    authed<FolderBrowse>(`/api/projects/browse${path ? `?path=${encodeURIComponent(path)}` : ""}`),
  createFolder: (parent: string, name: string) =>
    authed<FolderBrowse>("/api/projects/browse/folder", { method: "POST", body: JSON.stringify({ parent, name }) }),
  projectTree: (projectId: string, path = "") =>
    authed<ProjectTree>(`/api/projects/${projectId}/tree?path=${encodeURIComponent(path)}`),

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
