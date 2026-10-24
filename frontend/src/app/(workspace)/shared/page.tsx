import { ExplorerPage, type ExplorerParams } from "@/features/explorer/page";
export const metadata = { title: "Shared with me" };
export default function Shared({
  searchParams,
}: {
  searchParams: ExplorerParams;
}) {
  return <ExplorerPage mode="shared" searchParams={searchParams} />;
}
