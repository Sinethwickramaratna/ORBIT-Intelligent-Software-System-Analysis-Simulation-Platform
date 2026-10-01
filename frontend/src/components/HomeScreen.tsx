"use client";

import { useEffect, useState, type ReactNode } from "react";
import { api, type FolderSettings, type Project, type User } from "@/lib/api";
import SettingsDialog from "./SettingsDialog";
import { useFolderApply } from "@/lib/useFolderApply";
import ProjectDialog from "./ProjectDialog";
import FileTree from "./FileTree";

/**
 * Home page shown after login (see screens/Wireframes/HomePage.png).
 * Working now: Sign Out, sidebar show/hide, Create New Project, the project list and the open project's file tree.
 */

const SIDEBAR_KEY = "orbit.sidebar.open";

const WALKTHROUGHS = [
  { title: "Getting started with ORBIT", desc: "A five-minute tour of the workspace and how a project moves through it." },
  { title: "Add a project from a local folder or Git", desc: "Bring a project into ORBIT and let it index and scan the files." },
  { title: "Explore the architecture graph", desc: "See the services, APIs, databases and dependencies ORBIT discovered." },
];

const MENUS = ["File", "Edit", "View", "Help"];

function Svg({ size = 18, children }: { size?: number; children: ReactNode }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {children}
    </svg>
  );
}

const SearchIcon = () => (
  <Svg size={16}>
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5" />
  </Svg>
);
const SettingsIcon = () => (
  <Svg>
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3h0a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5h0a1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8v0a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z" />
  </Svg>
);
const PlusIcon = () => (
  <Svg size={22}>
    <path d="M12 5v14M5 12h14" />
  </Svg>
);
const GitIcon = () => (
  <Svg size={22}>
    <circle cx="6" cy="6" r="2.5" />
    <circle cx="6" cy="18" r="2.5" />
    <circle cx="18" cy="9" r="2.5" />
    <path d="M6 8.5v7M18 11.5c0 3-3 3.5-9 5" />
  </Svg>
);
const FolderIcon = () => (
  <Svg>
    <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
  </Svg>
);
const ChevronIcon = () => (
  <Svg size={16}>
    <path d="m9 6 6 6-6 6" />
  </Svg>
);

function SidebarToggle({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      className="sidebar-toggle"
      onClick={onToggle}
      aria-expanded={open}
      aria-controls="projects-sidebar"
      aria-label={open ? "Hide projects panel" : "Show projects panel"}
      title={`${open ? "Hide" : "Show"} projects (Ctrl+B)`}
    >
      <ChevronIcon />
    </button>
  );
}

export default function HomeScreen({ user, onLoggedOut }: { user: User; onLoggedOut: () => void }) {
  const [busy, setBusy] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [projects, setProjects] = useState<Project[]>([]);
  const [query, setQuery] = useState("");
  const [activeId, setActiveId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [folderSettings, setFolderSettings] = useState<FolderSettings | null>(null);
  const applyGaveUp = useFolderApply(folderSettings, setFolderSettings);

  useEffect(() => {
    api.getFolderSettings().then(setFolderSettings).catch(() => setFolderSettings(null));
  }, []);

  useEffect(() => {
    api.listProjects().then(setProjects).catch(() => setProjects([]));
  }, []);

  const active = projects.find((p) => p.projectId === activeId) ?? null;
  const q = query.trim().toLowerCase();
  const visible = q
    ? projects.filter((p) => p.projectName.toLowerCase().includes(q) || p.location.toLowerCase().includes(q))
    : projects;

  function onCreated(project: Project) {
    setProjects((list) => [project, ...list.filter((p) => p.projectId !== project.projectId)]);
    setActiveId(project.projectId);
    setCreating(false);
  }

  // Restore the last choice (per browser only; purely a convenience).
  useEffect(() => {
    try {
      if (localStorage.getItem(SIDEBAR_KEY) === "0") setSidebarOpen(false);
    } catch {
      /* storage may be unavailable */
    }
  }, []);

  function setOpen(next: boolean) {
    setSidebarOpen(next);
    try {
      localStorage.setItem(SIDEBAR_KEY, next ? "1" : "0");
    } catch {
      /* ignore */
    }
  }

  // Ctrl/Cmd+B toggles the panel, like most editors.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase() === "b") {
        e.preventDefault();
        setSidebarOpen((open) => {
          try {
            localStorage.setItem(SIDEBAR_KEY, open ? "0" : "1");
          } catch {
            /* ignore */
          }
          return !open;
        });
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  async function logout() {
    setBusy(true);
    try {
      await api.logout();
    } catch {
      /* the local session is cleared regardless */
    } finally {
      onLoggedOut();
    }
  }

  const tab = sidebarOpen ? 0 : -1;

  return (
    <div className="workbench">
      <header className="menubar">
        <div className="menubar-left">
          <span className="brand-glyph" aria-hidden="true" />
          <nav className="menubar-menus" aria-label="Application menu">
            {MENUS.map((m) => (
              <button key={m} type="button" className="menu-item">
                {m}
              </button>
            ))}
          </nav>
        </div>
        <div className="menubar-right">
          <button type="button" className="btn ghost signout" onClick={logout} disabled={busy}>
            {busy ? "Signing out…" : "Sign Out"}
          </button>
        </div>
      </header>

      {folderSettings?.restartRequired && (
        <div className="notice-bar" role="status">
          {folderSettings.autoApply && !applyGaveUp ? (
            <span>
              Applying your folder changes - ORBIT restarts for a moment and this message disappears when it is done.
            </span>
          ) : (
            <span>
              {folderSettings.autoApply
                ? "The folder change was not applied automatically. "
                : "Folder access was changed. "}
              Run <code>start.cmd</code> (Windows) or <code>./start.sh</code> once (or <code>docker compose up -d</code>)
              to apply it. Until then ORBIT can only open: <strong>{folderSettings.active.join(", ")}</strong>
            </span>
          )}
          <button type="button" className="btn ghost" onClick={() => setSettingsOpen(true)}>Settings</button>
        </div>
      )}

      <div className={`workbench-body${sidebarOpen ? "" : " sidebar-hidden"}`}>
        <SidebarToggle open={sidebarOpen} onToggle={() => setOpen(!sidebarOpen)} />
        <aside id="projects-sidebar" className={`sidebar${sidebarOpen ? "" : " collapsed"}`} aria-label="Projects">
          <div className="sidebar-inner">
            <div className="sidebar-head">
              <h2 className="sidebar-title">Projects</h2>
              <span className="count-badge" aria-label={`${projects.length} projects`}>
                {projects.length}
              </span>
            </div>

            <div className="searchbar">
              <SearchIcon />
              <input type="search" placeholder="Search Bar" aria-label="Search projects" tabIndex={tab} value={query} onChange={(e) => setQuery(e.target.value)} />
            </div>

            {projects.length > 0 && <div className="section-label">Recent</div>}
            <ul className="project-list">
              {visible.map((p) => (
                <li key={p.projectId}>
                  <button
                    type="button"
                    className={`project-card${p.projectId === activeId ? " active" : ""}`}
                    tabIndex={tab}
                    onClick={() => setActiveId(p.projectId)}
                    title={`${p.location}\nCreated ${new Date(p.createdAt).toLocaleString()}`}
                    aria-current={p.projectId === activeId ? "true" : undefined}
                  >
                    <span className="project-icon">
                      <FolderIcon />
                    </span>
                    <span className="project-text">
                      <strong>{p.projectName}</strong>
                      <span>{p.location}</span>
                    </span>
                    <span className="project-chevron">
                      <ChevronIcon />
                    </span>
                  </button>
                </li>
              ))}
            </ul>

            <div className="sidebar-user">
              <div className="avatar" aria-hidden="true">
                {user.userName.slice(0, 1).toUpperCase()}
              </div>
              <div className="username" title={user.userName}>
                {user.userName}
              </div>
              <button type="button" className="icon-btn" aria-label="Settings" title="Settings" tabIndex={tab} onClick={() => setSettingsOpen(true)}>
                <SettingsIcon />
              </button>
            </div>
          </div>
        </aside>

        {active ? (
          <div className="project-view">
            <main className="project-middle" aria-label={`${active.projectName} workspace`}>
              <div className="project-tab">
                <span className="project-tab-name" title={`${active.location}\nCreated ${new Date(active.createdAt).toLocaleString()}`}>{active.projectName}</span>
                <button type="button" className="icon-btn small" aria-label="Close project" title="Close project" onClick={() => setActiveId(null)}>
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
                </button>
              </div>
            </main>
            <FileTree projectId={active.projectId} projectName={active.projectName} />
          </div>
        ) : (
        <main className="dashboard">
          <div className="dashboard-inner">
            <section className="hero">
              <p className="hello">Hello, {user.userName}</p>
              <h1 className="welcome-card">
                Welcome to <span className="gradient-text">Orbit</span>
              </h1>
              <p className="small-description">
                Intelligent Software System Analysis &amp; Simulation Platform. Open a project from the left, or start a new one.
              </p>
            </section>

            <div className="quick-actions">
              <button type="button" className="action-tile" onClick={() => setCreating(true)}>
                <PlusIcon />
                <span>Create New Project</span>
              </button>
              <button type="button" className="action-tile">
                <GitIcon />
                <span>Git Clone Repo</span>
              </button>
            </div>

            <section className="walk">
              <h2 className="section-heading">Walk Throughs</h2>
              <ul className="walkthroughs">
                {WALKTHROUGHS.map((w, i) => (
                  <li key={i}>
                    <button type="button" className="walkthrough">
                      <span className="walkthrough-num">{String(i + 1).padStart(2, "0")}</span>
                      <span className="walkthrough-text">
                        <strong>{w.title}</strong>
                        <span>{w.desc}</span>
                      </span>
                      <span className="project-chevron">
                        <ChevronIcon />
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          </div>
        </main>
        )}
      </div>

      {settingsOpen && <SettingsDialog onClose={() => setSettingsOpen(false)} onSaved={setFolderSettings} />}
      {creating && <ProjectDialog onClose={() => setCreating(false)} onCreated={onCreated} />}

      <footer className="statusbar">
        <span className="status-item">ORBIT</span>
        <span className="status-item">{active ? active.projectName : "Ready"}</span>
        <span className="status-spacer" />
        <span className="status-item">{user.userName}</span>
        <span className="status-item">Ctrl+B: toggle projects</span>
      </footer>
    </div>
  );
}
