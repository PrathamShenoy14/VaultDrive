import { FolderOpen, Users, Clock3, Trash2, ArrowUpRight } from "lucide-react";
const content = {
  drive: {
    title: "My Drive",
    subtitle: "A home for everything you keep.",
    heading: "Your space is taking shape",
    description:
      "Your account is connected. File browsing and uploads arrive in a later milestone. Your files will appear here when browsing is available.",
    icon: FolderOpen,
    eyebrow: "A little room for what's next",
  },
  shared: {
    title: "Shared",
    subtitle: "Good work deserves a shared space.",
    heading: "Together, in a later milestone",
    description:
      "Shared files and permission controls will appear here when sharing is available in the frontend.",
    icon: Users,
    eyebrow: "Better together",
  },
  activity: {
    title: "Recent / Activity",
    subtitle: "Keep a little perspective on your work.",
    heading: "Your activity, in time",
    description:
      "Recent files and activity history are planned for a later milestone. No activity feed is loaded yet.",
    icon: Clock3,
    eyebrow: "Follow your flow",
  },
  trash: {
    title: "Trash",
    subtitle: "A second chance for the things you keep.",
    heading: "A place to reconsider",
    description:
      "Trash browsing, restore, and permanent deletion are planned for a later milestone. No Trash contents are loaded yet.",
    icon: Trash2,
    eyebrow: "Room to change your mind",
  },
};
export function MilestonePage({ section }: { section: keyof typeof content }) {
  const item = content[section];
  const Icon = item.icon;
  return (
    <>
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight">
            {item.title}
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">{item.subtitle}</p>
        </div>
        <span className="rounded-full border bg-muted/50 px-3 py-1.5 text-[11px] font-medium text-muted-foreground">
          Preview · More coming soon
        </span>
      </header>
      <section className="mt-9 overflow-hidden rounded-2xl border bg-background/60">
        <div className="grid items-center gap-8 p-7 sm:p-10 xl:grid-cols-[1fr_220px]">
          <div className="max-w-lg">
            <p className="mb-3 text-xs font-medium tracking-wide text-primary">
              {item.eyebrow}
            </p>
            <h2 className="text-2xl leading-tight font-semibold tracking-tight sm:text-3xl">
              {item.heading}
            </h2>
            <p className="mt-4 text-sm leading-7 text-muted-foreground">
              {item.description}
            </p>
            <div className="mt-6 inline-flex items-center gap-2 text-xs text-muted-foreground">
              <span className="size-1.5 rounded-full bg-primary" />
              Your account is connected. File features are coming soon.
            </div>
          </div>
          <div
            aria-hidden="true"
            className="relative hidden h-44 items-center justify-center xl:flex"
          >
            <div className="absolute size-40 rounded-full border border-primary/10 bg-primary/5" />
            <div className="rotate-[-8deg] rounded-2xl border border-primary/15 bg-card p-8 shadow-sm">
              <Icon className="size-20 text-primary/65" strokeWidth={1.1} />
            </div>
          </div>
        </div>
      </section>
      <section
        aria-label="Upcoming features"
        className="mt-8 grid gap-4 md:grid-cols-3"
      >
        {[
          {
            title: "Bring it all together",
            text: "File grids, folders, and uploads.",
            tag: "File workspace",
          },
          {
            title: "Find your focus",
            text: "Search and recent activity.",
            tag: "Discovery",
          },
          {
            title: "Make room for others",
            text: "Sharing with clear permissions.",
            tag: "Collaboration",
          },
        ].map((feature) => (
          <div key={feature.title} className="rounded-xl border p-5">
            <div className="mb-5 flex items-center justify-between">
              <span className="text-[11px] text-muted-foreground">
                {feature.tag}
              </span>
              <ArrowUpRight
                className="size-4 text-muted-foreground/60"
                aria-hidden="true"
              />
            </div>
            <h3 className="text-sm font-medium">{feature.title}</h3>
            <p className="mt-2 text-xs leading-6 text-muted-foreground">
              {feature.text}
            </p>
            <p className="mt-5 text-[10px] font-medium tracking-wide text-muted-foreground uppercase">
              Planned
            </p>
          </div>
        ))}
      </section>
    </>
  );
}
