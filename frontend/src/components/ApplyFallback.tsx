import type { FolderSettings } from "@/lib/api";

interface Props {
  helper?: FolderSettings["helper"];
  /** The helper is running but the change still was not applied after a few minutes. */
  gaveUp?: boolean;
}

/** Plain-words message for when a folder change could not be applied automatically. */
export default function ApplyFallback({ helper, gaveUp }: Props) {
  if (helper === "RUNNING" || gaveUp) {
    return (
      <span>
        Your folders are saved, but applying them is taking longer than expected. If nothing changes in a minute, run{" "}
        <code>start.cmd</code> (Windows) or <code>./start.sh</code>.
      </span>
    );
  }
  return (
    <span>
      Your folders are saved, but not applied yet. To finish, run <code>start.cmd</code> (Windows) or{" "}
      <code>./start.sh</code>
      . {helper === "NO_SIGNAL_FOLDER"
        ? "You only need to do this one time - after that, folder changes apply by themselves."
        : "This also restarts the helper that applies folder changes by itself."}
    </span>
  );
}
