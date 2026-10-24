"use client";
import {
  createContext,
  useContext,
  useEffect,
  useState,
  useSyncExternalStore,
} from "react";
import { useRouter } from "next/navigation";
import { UploadManager, restoreJournal } from "./manager";
import { TransferPanel } from "./transfer-panel";

const Context = createContext<UploadManager | null>(null);
export function useTransfers() {
  return useContext(Context);
}
export function useTransferList(manager: UploadManager) {
  return useSyncExternalStore(
    manager.subscribe,
    manager.snapshot,
    manager.snapshot,
  );
}
export function TransferProvider({
  userId,
  children,
}: {
  userId: string;
  children: React.ReactNode;
}) {
  const router = useRouter();
  const [manager, setManager] = useState<UploadManager | null>(null);
  useEffect(() => {
    const key = `vaultdrive-transfers-v1:${userId}`;
    const instance = new UploadManager({
      changed: () => {
        router.refresh();
        window.dispatchEvent(new Event("vaultdrive:transfers-changed"));
      },
      persist: (records) => {
        try {
          sessionStorage.setItem(key, JSON.stringify(records));
        } catch {
          /* The visible session IDs remain available when browser storage is disabled. */
        }
      },
    });
    let saved: string | null = null;
    try {
      saved = sessionStorage.getItem(key);
    } catch {
      /* In-memory transfers still work. */
    }
    instance.restore(restoreJournal(saved));
    // Each effect lifetime owns its manager, including StrictMode remounts.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setManager(instance);
    return () => instance.stop();
  }, [userId, router]);
  return (
    <Context.Provider value={manager}>
      {children}
      {manager && <TransferPanel manager={manager} />}
    </Context.Provider>
  );
}
