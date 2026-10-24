import { ExplorerPage, type ExplorerParams } from "@/features/explorer/page";
export const metadata = { title: "Search" };
export default function Search({
  searchParams,
}: {
  searchParams: ExplorerParams;
}) {
  return <ExplorerPage mode="search" searchParams={searchParams} />;
}
