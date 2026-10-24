import { Clock3, HardDrive, Trash2, Users } from "lucide-react";
export const navigation = [
  { href: "/drive", label: "My Drive", icon: HardDrive },
  { href: "/shared", label: "Shared", icon: Users },
  { href: "/activity", label: "Recent / Activity", icon: Clock3 },
  { href: "/trash", label: "Trash", icon: Trash2 },
] as const;
