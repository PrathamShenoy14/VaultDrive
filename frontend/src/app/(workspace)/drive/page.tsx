import { ExplorerPage, type ExplorerParams } from "@/features/explorer/page";
export const metadata = { title: "My Drive" };
export default function Drive({
  searchParams,
}: {
  searchParams: ExplorerParams;
}) {
  return <ExplorerPage mode="drive" searchParams={searchParams} />;
}
