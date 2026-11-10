import Link from "next/link";

export default function NotFound() {
  return (
    <div className="max-w-xl mx-auto text-center pt-20">
      <h1 className="font-display text-[1.75rem]">Nothing here</h1>
      <p className="text-slate mt-2">This page doesn&apos;t exist, or it moved.</p>
      <div className="mt-6">
        <Link href="/" className="inline-flex items-center rounded-full px-4 h-9 text-[14px] font-medium bg-accent text-accent-ink hover:opacity-85">
          Go to the overview
        </Link>
      </div>
    </div>
  );
}
