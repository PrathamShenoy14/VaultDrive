import { ExplorerPage, type ExplorerParams } from "@/features/explorer/page";
export const metadata = { title: "Trash" };
export default function Trash({
  searchParams,
}: {
  searchParams: ExplorerParams;
}) {
  return <ExplorerPage mode="trash" searchParams={searchParams} />;
}
