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

export interface RepositoryInfo {
  found: boolean;
  message: string | null;
  repository: string | null;
  owner: string | null;
  defaultBranch: string | null;
  branchCount: number;
  visibility: string | null;
  branches: string[];
}

export interface CloneProjectInput {
  repositoryUrl: string;
  branch: string;
  /** Folder the repository is cloned INTO (it gets its own sub-folder). */
  location: string;
  projectName: string;
  projectType: ProjectType;
  description?: string;
}

export interface TreeEntry {
  name: string;
  path: string;
  kind: "FOLDER" | "FILE";
  size: number | null;
  hasChildren: boolean;
}

/** A text file opened in the editor (`content` is null on a save response). */
export interface FileContent {
  path: string;
  name: string;
  content: string | null;
  size: number;
  lastModified: number;
  bom: boolean;
  lineEnding: "LF" | "CRLF";
}

export interface ProjectTree {
  path: string;
  entries: TreeEntry[];
  truncated: boolean;
}

/** What a scan found for one language. `percentage` is the share of all source-code lines. */
export interface LanguageShare {
  language: string;
  files: number;
  lines: number;
  percentage: number;
}

/** A build system found in the project and the files (relative paths, shallowest first) that prove it. */
export interface BuildSystemFinding {
  name: string;
  evidence: string[];
}

/** A configuration file name (as stored in the database) and every place, relative to the project, it was found. */
export interface ConfigurationFileFinding {
  fileName: string;
  locations: string[];
}

/** A dependency declaration that proves a framework: manifest (relative path), 1-based line and column, name as written. */
export interface FrameworkEvidence {
  filePath: string;
  line: number;
  column: number;
  dependency: string;
}

/** A framework found in the project and the declarations that prove it (shallowest manifest first). */
export interface FrameworkFinding {
  name: string;
  evidence: FrameworkEvidence[];
}

/** How far a running scan is. `percent` is 0-99 while `active`. */
export interface ScanProgress {
  active: boolean;
  phase: string | null;
  percent: number;
}

/** One stored scan of a project; `languages` arrives sorted by lines, largest first. */
export interface ProjectScan {
  scanId: string;
  projectId: string;
  scannedAt: string;
  totalFiles: number;
  totalLines: number;
  languages: LanguageShare[];
  /** Detected after the languages; sorted by name, empty when the project has no build file. */
  buildSystems: BuildSystemFinding[];
  /** Detected after the build systems; sorted by file name, empty when none were found. */
  configurationFiles: ConfigurationFileFinding[];
  /** Detected last, from the dependency manifests; sorted by name, empty when none were found. */
  frameworks: FrameworkFinding[];
}

/** One declared dependency of a manifest group, as the dependency check saw it. */
/** UNUSED: declared, but the source code never uses it - not downloaded (does not make the check red). */
export type DependencyState = "INSTALLED" | "MISSING" | "OUTDATED" | "LOCAL" | "SDK" | "UNSUPPORTED" | "UNUSED";

export interface DependencyItem {
  name: string;
  /** Version requirement as written in the manifest (may be null). */
  required: string | null;
  installed: string | null;
  scope: string | null;
  state: DependencyState;
  note: string | null;
}

/** A manifest group: a package.json, a pom.xml module, the Python manifests of one folder ... */
export interface DependencyUnit {
  ecosystem: "npm" | "pip" | "maven" | "gradle" | "nuget" | "pub" | "native";
  ecosystemLabel: string;
  manifest: string;
  /** Where Setup Dependencies saves the files, relative to the project folder. */
  installLocation: string | null;
  status: DependencyCheckStatus;
  message: string | null;
  total: number;
  missing: number;
  dependencies: DependencyItem[];
}

/** INSTALLED = green check, MISSING = red X, NO_DEPENDENCIES = green (nothing declared). */
export type DependencyCheckStatus = "INSTALLED" | "MISSING" | "NO_DEPENDENCIES";

export interface DependencyCheck {
  checkId: string;
  projectId: string;
  checkedAt: string;
  status: DependencyCheckStatus;
  totalDependencies: number;
  missingDependencies: number;
  /** The computer the packages are checked / downloaded for, e.g. "Windows x64". */
  targetPlatform: string;
  units: DependencyUnit[];
}

export type DependencySetupState = "IDLE" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

/** One manifest group of a check / setup run. */
export interface DependencyProgressStep {
  title: string;
  state: "PENDING" | "RUNNING" | "DONE" | "FAILED";
  /** What happened, once the step has finished ("12 installed", "2 packages failed", ...). */
  summary: string | null;
}

/** A version changed in a project file, e.g. requirements.txt: pyyaml ==5.3.1 -> ==6.0.2. */
export interface DependencyChange {
  file: string;
  dependency: string;
  from: string | null;
  to: string;
  reason: string;
}

/** Progress of a Dependency Check (`kind` CHECK) or of Setup Dependencies (`kind` SETUP). */
export interface DependencySetupProgress {
  kind: "CHECK" | "SETUP";
  state: DependencySetupState;
  /** What is happening, e.g. "Node.js (npm) - package.json". */
  phase: string | null;
  /** The package being worked on right now, e.g. "react@18.3.1". */
  current: string | null;
  percent: number;
  unitIndex: number;
  unitCount: number;
  steps: DependencyProgressStep[];
  /** Packages downloaded and saved so far. */
  installed: number;
  /** Packages that could not be set up. */
  failed: number;
  /** Versions ORBIT changed in project files because the requested one could not be installed. */
  changes: DependencyChange[];
  startedAt: string | null;
  finishedAt: string | null;
  failures: string[];
  log: string[];
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
  inspectRepository: (repositoryUrl: string) =>
    authed<RepositoryInfo>("/api/projects/clone/inspect", { method: "POST", body: JSON.stringify({ repositoryUrl }) }),
  /** Resolves only when the clone is complete and the project is saved. */
  cloneProject: (input: CloneProjectInput) =>
    authed<Project>("/api/projects/clone", { method: "POST", body: JSON.stringify(input) }),
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
  /** One-time ticket for the terminal helper: a terminal in this project's folder (see lib/terminal.ts). */
  terminalTicket: (projectId: string) =>
    authed<{ ticket: string; expiresAt: number; cwd: string }>(`/api/projects/${projectId}/terminal/ticket`, { method: "POST" }),
  /** Opens a UTF-8 text file of the project for the editor. */
  readFile: (projectId: string, path: string) =>
    authed<FileContent>(`/api/projects/${projectId}/file?path=${encodeURIComponent(path)}`),
  /** Saves a file. With `expectedLastModified` the save fails (409 FILE_CHANGED_ON_DISK) when the file changed meanwhile. */
  saveFile: (projectId: string, input: { path: string; content: string; bom: boolean; expectedLastModified: number | null }) =>
    authed<FileContent>(`/api/projects/${projectId}/file`, { method: "PUT", body: JSON.stringify(input) }),
  /** New file or folder inside `parent` ("" = the project root). */
  createEntry: (projectId: string, parent: string, name: string, kind: TreeEntry["kind"]) =>
    authed<TreeEntry>(`/api/projects/${projectId}/files`, { method: "POST", body: JSON.stringify({ parent, name, kind }) }),
  renameEntry: (projectId: string, path: string, newName: string) =>
    authed<TreeEntry>(`/api/projects/${projectId}/files/rename`, { method: "POST", body: JSON.stringify({ path, newName }) }),
  /** Deletes a file, or a folder with everything in it, from disk. */
  deleteEntry: (projectId: string, path: string) =>
    authed<void>(`/api/projects/${projectId}/files?path=${encodeURIComponent(path)}`, { method: "DELETE" }),
  /** The Scan button: reads the project folder again and stores a new scan. */
  scanProject: (projectId: string) => authed<ProjectScan>(`/api/projects/${projectId}/scan`, { method: "POST" }),
  /** Progress of the scan that is running right now (poll it while `scanProject` is pending). */
  scanProgress: (projectId: string) => authed<ScanProgress>(`/api/projects/${projectId}/scan/progress`),
  /** The newest stored scan; `undefined` (HTTP 204) when the project was never scanned. */
  latestScan: (projectId: string) => authed<ProjectScan | undefined>(`/api/projects/${projectId}/scan`),

  /** The stored dependency check (made automatically when the project was added); `undefined` (204) when never checked. */
  dependencyCheck: (projectId: string) => authed<DependencyCheck | undefined>(`/api/projects/${projectId}/dependencies`),
  /** The Dependency Check button: checks the project folder now. */
  runDependencyCheck: (projectId: string) =>
    authed<DependencyCheck>(`/api/projects/${projectId}/dependencies/check`, { method: "POST" }),
  /** Progress of the running (or last) Dependency Check; poll it while `runDependencyCheck` is pending. */
  dependencyCheckProgress: (projectId: string) =>
    authed<DependencySetupProgress>(`/api/projects/${projectId}/dependencies/check/progress`),
  /** Setup Dependencies (after the user confirmed): starts the downloads and answers at once. */
  startDependencySetup: (projectId: string, updateManifests = true, skipUnused = true) =>
    authed<DependencySetupProgress>(`/api/projects/${projectId}/dependencies/setup`, {
      method: "POST",
      body: JSON.stringify({ updateManifests, skipUnused }),
    }),
  /** Progress of Setup Dependencies; poll until `state` is no longer RUNNING. */
  dependencySetupProgress: (projectId: string) =>
    authed<DependencySetupProgress>(`/api/projects/${projectId}/dependencies/setup/progress`),
  cancelDependencySetup: (projectId: string) =>
    authed<DependencySetupProgress>(`/api/projects/${projectId}/dependencies/setup`, { method: "DELETE" }),

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
