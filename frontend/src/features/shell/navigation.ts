import { Bell, Clock3, HardDrive, Settings, Trash2, Users } from "lucide-react";
export const navigation = [
  { href: "/drive", label: "My Drive", icon: HardDrive },
  { href: "/shared", label: "Shared", icon: Users },
  { href: "/activity", label: "Recent / Activity", icon: Clock3 },
  { href: "/notifications", label: "Notifications", icon: Bell },
  { href: "/settings", label: "Settings", icon: Settings },
  { href: "/trash", label: "Trash", icon: Trash2 },
] as const;
