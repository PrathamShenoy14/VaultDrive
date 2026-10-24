import { SharePage } from "@/features/sharing/share-page";
export const dynamic = "force-dynamic";
export const metadata = {
  title: "Shared file · VaultDrive",
  robots: { index: false, follow: false },
};
export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <SharePage key={id} id={id} />;
}
